/*
 * Copyright (c) 2016 CUBRID Corporation.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * - Neither the name of the <ORGANIZATION> nor the names of its contributors
 *   may be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 *
 */

package cubrid.jdbc.lb.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The steps around an execution are in {@code LBStatement}'s execute path alone: prepare open
 * statements before a session SET, run under failover, record what the leg answered. A path that
 * called them itself left one out each time (a batch's SET, a batch's failure, a batch not sent).
 */
public final class ExecutionPathArchitectureTest {
    private static final Pattern SESSION_STEP =
            Pattern.compile(
                    "\\.(beforeSessionSet|afterStatementSucceeded|afterStatementFailed"
                            + "|afterBatchAnswered)\\(");
    // A member declared at class level: four spaces, a name, an opening parenthesis, no semicolon.
    private static final Pattern METHOD =
            Pattern.compile("^ {4}(?=\\S)[\\w<>\\[\\], ]*?(\\w+)\\([^;]*$");
    private static final List<String> EXECUTE_PATH = Arrays.asList("runStatement", "runBatch");

    private static final String[] SOURCE_ROOTS = {
        "src/jdbc/cubrid/jdbc/lb",
        "cubrid-jdbc/src/jdbc/cubrid/jdbc/lb",
        "../src/jdbc/cubrid/jdbc/lb"
    };

    @Test
    public void sessionStepsAreCalledOnlyFromTheExecutePath() throws IOException {
        final List<File> files = new ArrayList<File>();
        collectJava(sourceRoot(), files);
        assertTrue("no LB sources found", files.size() > 10);

        final List<String> outside = new ArrayList<String>();
        int inside = 0;
        for (File file : files) {
            // The connection declares the steps and composes them (a batch's entries).
            if ("LoadBalanceConnection.java".equals(file.getName())) {
                continue;
            }
            String method = "";
            int lineNo = 0;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                lineNo++;
                final Matcher declared = METHOD.matcher(line);
                if (declared.find()) {
                    method = declared.group(1);
                }
                final String code = line.trim();
                if (code.startsWith("*") || code.startsWith("//")) {
                    continue;
                }
                final Matcher step = SESSION_STEP.matcher(line);
                if (!step.find()) {
                    continue;
                }
                if ("LBStatement.java".equals(file.getName()) && EXECUTE_PATH.contains(method)) {
                    inside++;
                } else {
                    outside.add(file.getName() + ":" + lineNo + " " + step.group(1));
                }
            }
        }

        assertEquals("called outside LBStatement's execute path", "[]", outside.toString());
        assertTrue("the execute path no longer calls the session steps", inside >= 4);
    }

    /** A prepared statement reaches the failover handler through its delegate's execute path. */
    @Test
    public void preparedStatementDoesNotCallTheFailoverHandlerItself() throws IOException {
        final File file = new File(sourceRoot(), "statement/LBPreparedStatement.java");
        final String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        assertTrue(source.contains("executeWithFailover("));
        assertEquals(-1, source.indexOf("getExecuteFailoverHandler"));
    }

    private static void collectJava(final File dir, final List<File> out) {
        final File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File each : entries) {
            if (each.isDirectory()) {
                collectJava(each, out);
            } else if (each.getName().endsWith(".java")) {
                out.add(each);
            }
        }
    }

    private static File sourceRoot() {
        for (String candidate : SOURCE_ROOTS) {
            final File dir = new File(candidate);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "LB sources not found from " + new File(".").getAbsolutePath());
    }
}
