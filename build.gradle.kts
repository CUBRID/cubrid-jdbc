import java.util.Locale
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    `maven-publish`
    signing
}

group = "org.cubrid"

// The rule of cmake/gen_version.cmake, which still versions the Ant build (the build-parity CI job
// compares the two). A source tarball has neither VERSION nor git history, only the
// output/VERSION-DIST it was packed with.
val versionFile = file("VERSION")
val versionDist = file("output/VERSION-DIST")
val fromTarball = !versionFile.exists() && versionDist.exists()
val commits = if (fromTarball) null else try {
    providers.exec {
        workingDir = projectDir
        commandLine("git", "rev-list", "--count", "--after", "2021-03-30", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull()
} catch (e: Exception) {
    null // git is not on the PATH
}
val serialMissing = !fromTarball && commits == null
if (serialMissing) logger.warn("Could not count the commits with git, so the serial is 0000")
version = if (fromTarball) {
    versionDist.readText().trim()
} else {
    versionFile.readText().trim() + "." + "%04d".format(Locale.ROOT, commits ?: 0) // ASCII digits in every locale
}
val jdbcVersion = version.toString()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val filterSources by tasks.registering(Sync::class) {
    from("src/jdbc") { include("**/*.java") }
    into(layout.buildDirectory.dir("filtered-src"))
    inputs.property("version", jdbcVersion)
    filteringCharset = "UTF-8"
    filter<ReplaceTokens>("tokens" to mapOf("JDBC_DRIVER_VERSION_STRING" to jdbcVersion))
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src/jdbc")) // for IDEs and javadoc; compileJava takes the filtered copy
        resources.setSrcDirs(emptyList<Any>())
    }
    test {
        java.setSrcDirs(listOf("src/test"))
        resources.setSrcDirs(emptyList<Any>())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.compileJava {
    setSource(filterSources)
}

val jarExtras by tasks.registering {
    val dir = layout.buildDirectory.dir("jar-extras")
    inputs.property("version", jdbcVersion)
    outputs.dir(dir)
    doLast {
        val root = dir.get().asFile
        root.deleteRecursively()
        root.resolve("META-INF/services").mkdirs()
        root.resolve("META-INF/services/java.sql.Driver").writeText("cubrid.jdbc.driver.CUBRIDDriver")
        root.resolve("CUBRID-JDBC-$jdbcVersion").writeText("")
    }
}

val deleteStaleJars by tasks.registering(Delete::class) {
    delete(provider {
        projectDir.listFiles { f ->
            f.isFile && f.name.endsWith(".jar") && !f.name.startsWith("cubrid-jdbc-$jdbcVersion")
        }?.toList() ?: emptyList()
    })
}

// Created-By names the toolchain JDK, not the one running Gradle. The vendor is Gradle's name for it,
// e.g. "Eclipse Temurin" where Ant wrote "Temurin".
val buildJdk = javaToolchains.compilerFor(java.toolchain).map { it.metadata }

// The engine packaging (CPack, build.sh -z jdbc) takes every jar in the project root, so deleteStaleJars
// clears the other ones first, as Ant's clean did.
tasks.withType<Jar>().configureEach {
    destinationDirectory.set(layout.projectDirectory)
    dependsOn(deleteStaleJars)
    manifest.attributes("Created-By" to buildJdk.map { "${it.javaRuntimeVersion} (${it.vendor})" })
}

tasks.jar {
    archiveFileName.set("cubrid-jdbc-$jdbcVersion.jar")
    from("src/jdbc/sql-error-codes.xml")
    from(jarExtras)
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveFileName.set("cubrid-jdbc-$jdbcVersion-sources.jar")
    from(filterSources)
    from("src/jdbc/sql-error-codes.xml")
    from(jarExtras)
}

tasks.javadoc {
    isFailOnError = false // as Ant's <javadoc>: report doclint errors and keep building
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        docEncoding = "UTF-8"
        charSet = "UTF-8"
        isAuthor = true
        isVersion = true
    }
}

val javadocJar by tasks.registering(Jar::class) {
    archiveFileName.set("cubrid-jdbc-$jdbcVersion-javadoc.jar")
    from(tasks.javadoc)
}

// Gradle's counterpart of Ant's dist-cubrid.
tasks.register("buildJdbc") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds the driver, sources and javadoc jars in the project root."
    dependsOn(tasks.jar, sourcesJar, javadocJar)
}

// CI keeps no test report, so its log needs the whole exception, as Ant printed it.
tasks.withType<Test>().configureEach {
    testLogging.exceptionFormat = TestExceptionFormat.FULL
}

tasks.test {
    useJUnit()
    exclude("**/*IT.class")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs the load-balance ITs against a live cluster."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnit()
    include("**/*IT.class")
    // Without lb.it.enabled a class gated in @BeforeClass runs no test; with it, matching nothing is a --tests typo.
    filter.isFailOnNoMatchingTests = providers.systemProperty("lb.it.enabled").orNull.equals("true", ignoreCase = true)
    // As JVM arguments: as systemProperties they would be stored in .gradle/, passwords included.
    val lbIt = providers.systemPropertiesPrefixedBy("lb.it.")
    jvmArgumentProviders.add(object : CommandLineArgumentProvider {
        override fun asArguments() = lbIt.get().map { (k, v) -> "-D$k=$v" }
    })
    outputs.upToDateWhen { false } // the result depends on the cluster, not on the inputs
}

// The Maven Central upload is configured in gradle/central-publish.init.gradle, which only release.sh loads.
publishing {
    publications {
        create<MavenPublication>("jdbc") {
            artifactId = "cubrid-jdbc"
            artifact(tasks.jar)
            artifact(sourcesJar) { classifier = "sources" }
            artifact(javadocJar) { classifier = "javadoc" }
            pom {
                name.set("org.cubrid:cubrid-jdbc")
                description.set("JDBC for CUBRID Database")
                url.set("https://www.cubrid.org")
                licenses {
                    license {
                        name.set("The 3-Clause BSD License")
                        url.set("https://opensource.org/license/BSD-3-Clause")
                    }
                }
                developers {
                    developer {
                        name.set("CUBRID JDBC Developer Team")
                        email.set("cubrid-jdbc@cubrid.com")
                        organization.set("CUBRID")
                        organizationUrl.set("https://www.cubrid.org")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/CUBRID/cubrid-jdbc.git")
                    developerConnection.set("scm:git:ssh://github.com/CUBRID/cubrid-jdbc.git")
                    url.set("https://github.com/CUBRID/cubrid-jdbc")
                }
                issueManagement {
                    system.set("JIRA")
                    url.set("http://jira.cubrid.org/projects/APIS/issues")
                }
            }
        }
    }
    repositories {
        maven {
            name = "localStaging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}

gradle.taskGraph.whenReady {
    if (allTasks.any { it is PublishToMavenRepository }) {
        if (serialMissing) {
            throw GradleException("Could not count the commits with git, so the release version has no serial")
        }
        logger.lifecycle("Publishing cubrid-jdbc $jdbcVersion")
    }
}

signing {
    val signingKey = providers.gradleProperty("signingKey").orNull
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, providers.gradleProperty("signingPassword").orNull)
    } else {
        useGpgCmd()
    }
    sign(publishing.publications["jdbc"])
}

// For a manual upload in the Central Portal.
val centralBundle by tasks.registering(Zip::class) {
    dependsOn("publishJdbcPublicationToLocalStagingRepository")
    from(layout.buildDirectory.dir("staging-repo")) { include("org/cubrid/cubrid-jdbc/$jdbcVersion/**") }
    archiveFileName.set("cubrid-jdbc-$jdbcVersion-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("central"))
}
