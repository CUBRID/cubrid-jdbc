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

package cubrid.jdbc.lb.log;

import cubrid.jdbc.driver.CUBRIDDriver;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileLock;
import java.security.CodeSource;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * JVM-wide installation of the LB log file handler. Idempotent: the first {@code loadbalance}
 * connection that names an {@code lbLogFile} installs the handler, and later ones are no-ops.
 *
 * <p><b>The singleton is mandatory, not an optimisation.</b> The handler is attached to the package
 * logger {@code cubrid.jdbc.lb}, which is one JVM-wide object. Attaching per connection would give
 * a hundred-connection pool a hundred handlers, and JUL publishes every record to all of them: one
 * failover written a hundred times, into a hundred files (a handler that cannot take the path's
 * lock falls back to a suffixed name).
 *
 * <p><b>Cross-JVM.</b> The path is locked through a sidecar {@code .lck} file. A second JVM naming
 * the same path cannot take that lock and writes to {@code <path>.<pid>} instead, so the two never
 * share an inode. Without it, the JVM that rotates first renames the file out from under the other:
 * generation numbers stop matching time order and the size cap is exceeded. The lock is held for
 * the life of the JVM, and the {@link RandomAccessFile} is kept because releasing it drops the
 * lock. It is taken on the <em>canonical</em> path, so a symlink or a {@code ./} prefix is
 * recognised as the same target.
 *
 * <p><b>What the lock cannot catch.</b> Two JVMs whose paths differ but overlap in the rotation
 * namespace ({@code a.log} and {@code a.log.1}) destroy each other's history, because one's live
 * file is the other's generation slot. Different paths mean different locks, so this is a WARN at
 * install time and otherwise left to documentation.
 *
 * <p><b>Conflicting paths in one JVM: first wins.</b> The logger is per class, not per connection,
 * so two DataSources naming different files cannot be separated - every record would go to both.
 * The second path is ignored with a WARN, the way {@code MetricsExporters} handles a conflicting
 * Prometheus port or CSV directory.
 */
public final class LbFileLogging {
    /**
     * The package logger every LB class logs through. One handler here covers {@code
     * LoadBalanceConnection}, {@code SessionPhysicalConnManager}, the config parsers and the
     * exporters, without touching a single call site.
     */
    static final String LB_LOGGER_NAME = "cubrid.jdbc.lb";

    private static final Logger LOGGER = Logger.getLogger(LbFileLogging.class.getName());

    private static boolean installed;
    private static String installedPath;
    private static Handler installedHandler;
    // Retained for the life of the JVM: dropping the RandomAccessFile releases the FileLock, which
    // would let a second JVM take the same path.
    private static RandomAccessFile lockFile;
    private static FileLock lock;
    private static File lockPath;

    private LbFileLogging() {}

    /**
     * Installs the file handler for {@code config} if one is not installed yet.
     *
     * <p>Never throws: logging is a diagnostic aid, and an unwritable path must not stop a
     * connection from being created. A failure is reported once on {@code stderr} and file logging
     * stays off.
     *
     * @param config the parsed file-logging options; ignored when {@code null} or switched off
     */
    public static synchronized void install(final LbLogConfig config) {
        if (config == null || !config.isEnabled()) {
            return;
        }

        String requested = config.getFile();
        if (installed) {
            if (!sameTarget(requested)) {
                LOGGER.warning(
                        "LB log: file logging already active at '"
                                + installedPath
                                + "'; ignoring conflicting "
                                + LbLogConfig.OPT_FILE
                                + "="
                                + requested
                                + " (LB records go to one file per JVM, never split between two)");
            }
            return;
        }

        try {
            File target = new File(requested);
            warnIfRotationNamespace(target);

            target = lockOrSuffix(target);

            LbLogFileHandler handler =
                    new LbLogFileHandler(
                            target,
                            config.getMaxSizeMb(),
                            config.getMaxFiles(),
                            config.getLevel(),
                            new LbLogFormatter(config.isThreadNameLogged()));

            Logger pkg = Logger.getLogger(LB_LOGGER_NAME);
            pkg.setLevel(config.getLevel());
            pkg.setUseParentHandlers(config.isToConsole());
            pkg.addHandler(handler);

            installed = true;
            installedPath = target.getPath();
            installedHandler = handler;

            banner(handler, config, target);
            installShutdownHook(handler);
        } catch (Exception e) {
            // Deliberately broad: SecurityException from the logger/lock, IOException from the
            // stream, anything else from path resolution. None of it may break getConnection().
            System.err.println("CUBRID LB: cannot enable file logging (" + requested + "): " + e);
        }
    }

    /**
     * First line of every log file: which build produced the records that follow, and the settings
     * in force. Without it, a stale driver jar reproducing an already-fixed defect cannot be told
     * from a regression except by decompiling the jar in use.
     *
     * <p>Published straight to the handler at the configured level rather than through the logger.
     * As INFO it would be filtered out at the default {@code lbLogLevel=WARNING}, the setting most
     * files are written under. It is a file header, not an event, so the level filter should not
     * apply to it.
     */
    private static void banner(
            final LbLogFileHandler handler, final LbLogConfig config, final File target) {
        LogRecord record =
                new LogRecord(
                        config.getLevel(),
                        "LB LOG: file logging started -> "
                                + target.getPath()
                                + " | driver="
                                + CUBRIDDriver.version_string
                                + " | "
                                + codeSource()
                                + " | level="
                                + config.getLevel().getName()
                                + " | rotate="
                                + (config.getMaxSizeMb() > 0
                                        ? config.getMaxSizeMb()
                                                + "MB x "
                                                + config.getMaxFiles()
                                                + " generations"
                                        : "disabled")
                                + " | console="
                                + config.isToConsole());
        record.setSourceClassName(LbFileLogging.class.getName());
        handler.publish(record);
        handler.flush();
    }

    /**
     * Which artefact the running driver classes were loaded from, and when it was last written.
     *
     * <p>The version string alone does not settle the question: two builds of the same version
     * carry the same string, so the path and its timestamp are what identify the jar. This is the
     * answer one would otherwise get by decompiling it or running with {@code -verbose:class}.
     */
    private static String codeSource() {
        try {
            CodeSource cs = CUBRIDDriver.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return "from=unknown";
            }
            File f = new File(cs.getLocation().toURI());
            return "from="
                    + f.getPath()
                    + " ("
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(f.lastModified()))
                    + ")";
        } catch (Exception e) {
            // A security manager, or a location that is not a plain file (a jar inside a war, an
            // OSGi bundle URL). The version string above still identifies the release.
            return "from=unknown";
        }
    }

    /**
     * Flushes collapsed transition tallies and the stream on JVM exit. Without the flush, a burst
     * that never recurred - the usual case once a cluster settles - would leave the log claiming
     * one connection failed over when the whole pool did.
     */
    private static void installShutdownHook(final Handler handler) {
        try {
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread("lb-log-flush") {
                                @Override
                                public void run() {
                                    LbLogDedup.drain();
                                    handler.flush();
                                    releaseLock();
                                }
                            });
        } catch (Exception e) {
            // A container may forbid shutdown hooks; the log stays usable, only the trailing
            // suppression summary is lost.
            LOGGER.warning("LB log: cannot register the shutdown flush hook: " + e);
        }
    }

    /**
     * Writes one record straight to the log file, bypassing the logger and the handler.
     *
     * <p>This is for the records produced while the JVM is shutting down. {@code java.util.logging}
     * registers a shutdown hook of its own ({@code LogManager$Cleaner}) that calls {@code
     * LogManager.reset()}: it removes every handler from every logger and clears the configured
     * levels. The JVM starts all shutdown hooks at once, so a record written through the logger
     * from our own hook loses that race about half the time, and loses it silently, because a
     * logger with no handlers discards records. The tallies that report what a whole pool did are
     * written exactly then.
     *
     * <p>Level filtering uses the handler's configured level rather than the logger's, which {@code
     * reset()} may already have cleared - that would drop the FINE-keyed tallies first.
     *
     * <p>Rotation is not checked here: these are the last few lines of a run, and reopening a
     * rotated generation from a shutdown hook would be a larger risk than the bytes are worth.
     *
     * @param level the level to record at
     * @param ctx the connection context, or {@code null}
     * @param message the record
     * @return {@code true} when the record was written, or deliberately skipped by the level
     *     filter; {@code false} when no LB log file is installed and the caller should use the
     *     logger
     */
    static synchronized boolean appendDirect(
            final Level level, final String ctx, final String message) {
        if (!installed || installedPath == null || installedHandler == null) {
            return false;
        }

        Level handlerLevel = installedHandler.getLevel();
        if (handlerLevel != null && level.intValue() < handlerLevel.intValue()) {
            return true;
        }

        Formatter formatter = installedHandler.getFormatter();
        if (formatter == null) {
            return false;
        }

        LogRecord record = new LogRecord(level, message);
        record.setLoggerName(LB_LOGGER_NAME);
        record.setSourceClassName(LB_LOGGER_NAME);
        if (ctx != null) {
            record.setParameters(new Object[] {ctx});
        }

        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(installedPath, true));
            writer.write(formatter.format(record));
            writer.flush();
            return true;
        } catch (Exception e) {
            // Nothing left to fall back to: the logger is the thing that is already gone. Losing
            // the summary is not worth an exception out of a shutdown hook.
            return true;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // The bytes were flushed above.
                }
            }
        }
    }

    /**
     * Releases the path lock and removes the sidecar, so a clean exit leaves no empty file behind.
     *
     * <p>The lock ends with the JVM either way; this is about not littering the log directory. A
     * crash leaves the sidecar, which is harmless: a stale {@code .lck} holds no lock, so the next
     * run reacquires it.
     */
    private static synchronized void releaseLock() {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // best effort
            }
            lock = null;
        }
        closeQuietly(lockFile);
        lockFile = null;

        if (lockPath != null) {
            lockPath.delete();
            lockPath = null;
        }
    }

    /** Takes the path's lock, or falls back to a PID-suffixed sibling when another JVM holds it. */
    private static File lockOrSuffix(final File preferred) throws IOException {
        if (tryLock(preferred)) {
            return preferred;
        }

        File fallback = new File(preferred.getPath() + "." + pid());
        if (tryLock(fallback)) {
            LOGGER.warning(
                    "LB log: '"
                            + preferred.getPath()
                            + "' is in use by another JVM; this JVM logs to '"
                            + fallback.getPath()
                            + "'");
            return fallback;
        }

        // Both taken: the PID is unique per live process, so the lock failed for an environmental
        // reason (a filesystem without locking, a permission problem). Write to the PID path anyway
        // rather than losing the log entirely.
        LOGGER.warning(
                "LB log: cannot lock '"
                        + preferred.getPath()
                        + "'; writing to '"
                        + fallback.getPath()
                        + "' without a lock");
        return fallback;
    }

    private static boolean tryLock(final File target) {
        RandomAccessFile raf = null;
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File sidecar = new File(target.getPath() + ".lck");
            raf = new RandomAccessFile(sidecar, "rw");
            FileLock acquired = raf.getChannel().tryLock();
            if (acquired == null) {
                raf.close();
                return false;
            }
            lockFile = raf;
            lock = acquired;
            lockPath = sidecar;
            return true;
        } catch (Exception e) {
            // OverlappingFileLockException (same JVM), IOException (unlockable filesystem),
            // SecurityException. All mean "not ours"; the caller falls back.
            closeQuietly(raf);
            return false;
        }
    }

    private static void closeQuietly(final RandomAccessFile raf) {
        if (raf == null) {
            return;
        }
        try {
            raf.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static String pid() {
        try {
            // Java 6 has no ProcessHandle; the RuntimeMXBean name is "pid@host" on every mainstream
            // JVM. A name without '@' is used as-is rather than guessing.
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return at > 0 ? name.substring(0, at) : name;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * A path ending in {@code .<digits>} is a rotation generation slot of some other log file. Two
     * JVMs configured that way shift each other's history and both lose records; the lock cannot
     * see it because the paths genuinely differ.
     */
    private static void warnIfRotationNamespace(final File target) {
        String name = target.getName();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return;
        }
        for (int i = dot + 1; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return;
            }
        }
        LOGGER.warning(
                "LB log: '"
                        + name
                        + "' ends in a numeric suffix, which is the rotation generation"
                        + " slot of '"
                        + name.substring(0, dot)
                        + "'; if another JVM logs to that"
                        + " file, rotation will overwrite this one");
    }

    /**
     * The file is created exactly where {@code lbLogFile} says, with a relative path taken against
     * the JVM's working directory, as any other path in Java would be.
     *
     * <p>Not redirected to {@code $CUBRID/log}: this driver runs on the <em>application</em>
     * machine, where a CUBRID server installation usually does not exist and {@code $CUBRID/log}
     * may be absent or unwritable. Redirecting also made {@code lbLogFile=./lb.log} land somewhere
     * other than the current directory, and resolved relative paths differently from {@code
     * metricsCsvPath} in the same URL.
     */
    private static boolean sameTarget(final String requested) {
        try {
            return new File(requested)
                    .getCanonicalPath()
                    .equals(new File(installedPath).getCanonicalPath());
        } catch (IOException e) {
            return requested.equals(installedPath);
        }
    }

    /**
     * Test hook: removes the handler and releases the lock so a following test can install again.
     */
    static synchronized void resetForTests() {
        if (installedHandler != null) {
            Logger.getLogger(LB_LOGGER_NAME).removeHandler(installedHandler);
            installedHandler.close();
            installedHandler = null;
        }
        releaseLock();

        Logger pkg = Logger.getLogger(LB_LOGGER_NAME);
        pkg.setLevel(null);
        pkg.setUseParentHandlers(true);

        installed = false;
        installedPath = null;
    }
}
