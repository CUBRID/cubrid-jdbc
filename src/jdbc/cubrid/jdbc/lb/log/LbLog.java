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

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Logging entry point for LB call sites that know which connection they are speaking for, plus the
 * shared trimming used when a driver exception is quoted into a log line.
 *
 * <p>The context ({@code conn#7}) travels as the record's sole parameter rather than being pasted
 * into the message, so {@link LbLogFormatter} can render it as its own column and a reader can
 * filter one connection out of a busy pool with {@code awk -F'|' '$3=="conn#7"'}. Call sites with
 * no connection in scope keep using the plain {@code Logger} API and render as {@code -}.
 *
 * <p>Records are built directly and passed to {@link Logger#log(LogRecord)} rather than going
 * through {@code Logger.warning(String)}. That is not only for the parameter: it also leaves the
 * record's source class and method unset, which is what keeps the formatter from ever triggering
 * JUL's stack-walking caller inference.
 */
public final class LbLog {
    /**
     * Cap on a quoted driver message. Long enough for the CAS error text, short enough that a
     * hundred pooled connections reporting the same outage do not each write a paragraph.
     */
    private static final int MAX_CAUSE_MESSAGE = 160;

    /**
     * Bracketed trailers the base driver appends to every communication failure. They repeat the
     * CAS address, the session id and the <em>entire</em> connection URL on every line, which is
     * what turned a failover record into 400+ characters. The address is already in the LB message
     * and the URL is in the startup banner, so the message is cut at the first of these.
     */
    private static final String[] CAUSE_TRAILERS = {"[CAS INFO", "[SESSION-", "[URL-"};

    private LbLog() {}

    /**
     * Context string for a connection id; {@code null} when no connection is in scope.
     *
     * @param connectionId LB connection id, or a non-positive value when none is in scope
     * @return the context string, or {@code null}
     */
    public static String conn(final long connectionId) {
        return connectionId <= 0L ? null : "conn#" + connectionId;
    }

    public static void warn(final Logger logger, final String ctx, final String message) {
        log(logger, Level.WARNING, ctx, message, null);
    }

    public static void info(final Logger logger, final String ctx, final String message) {
        log(logger, Level.INFO, ctx, message, null);
    }

    public static void fine(final Logger logger, final String ctx, final String message) {
        log(logger, Level.FINE, ctx, message, null);
    }

    public static void warn(
            final Logger logger, final String ctx, final String message, final Throwable thrown) {
        log(logger, Level.WARNING, ctx, message, thrown);
    }

    /**
     * Logs at a level chosen by the caller, for helpers that carry the level as data.
     *
     * @param logger logger to record through
     * @param level level to log at
     * @param ctx context string (see {@link #conn}), or {@code null}
     * @param message message to log
     */
    public static void at(
            final Logger logger, final Level level, final String ctx, final String message) {
        log(logger, level, ctx, message, null);
    }

    /**
     * Logs a record produced while the JVM is shutting down.
     *
     * <p>Prefers a direct append to the LB log file, because by then {@code java.util.logging} may
     * already have torn the logger down from its own shutdown hook - see {@link
     * LbFileLogging#appendDirect}. Falls back to the logger when no LB log file is installed, which
     * is the case when LB records are wired to a container's handler instead.
     *
     * @param logger logger to fall back to when no LB log file is installed
     * @param level level to log at
     * @param ctx context string (see {@link #conn}), or {@code null}
     * @param message message to log
     */
    public static void atShutdown(
            final Logger logger, final Level level, final String ctx, final String message) {
        if (LbFileLogging.appendDirect(level, ctx, message)) {
            return;
        }
        log(logger, level, ctx, message, null);
    }

    private static void log(
            final Logger logger,
            final Level level,
            final String ctx,
            final String message,
            final Throwable thrown) {
        if (logger == null || !logger.isLoggable(level)) {
            return;
        }
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName(logger.getName());
        // Set the source explicitly, for two reasons. It stops JUL inferring the caller by walking
        // a stack trace, the cost this class exists to avoid. And it keeps the console rendering
        // meaningful: the inference would name this helper instead of the class that logged.
        record.setSourceClassName(logger.getName());
        if (ctx != null) {
            record.setParameters(new Object[] {ctx});
        }
        if (thrown != null) {
            record.setThrown(thrown);
        }
        logger.log(record);
    }

    /**
     * Renders a driver exception for a log line: SQLState, vendor code, and the leading trimmed
     * part of the message. A {@code null} cause yields {@code ""}.
     *
     * @param cause exception to render, or {@code null}
     * @return the rendered cause, or {@code ""}
     */
    public static String cause(final SQLException cause) {
        if (cause == null) {
            return "";
        }
        return "SQLState="
                + cause.getSQLState()
                + " errorCode="
                + cause.getErrorCode()
                + " msg="
                + shortMessage(cause.getMessage());
    }

    /**
     * First line of a driver message, with the repeated bracketed trailers dropped and capped.
     *
     * @param message driver message, or {@code null}
     * @return the shortened message, or {@code ""}
     */
    public static String shortMessage(final String message) {
        if (message == null) {
            return "";
        }

        String s = message;
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(0, nl);
        }

        int cut = -1;
        for (int i = 0; i < CAUSE_TRAILERS.length; i++) {
            int at = s.indexOf(CAUSE_TRAILERS[i]);
            if (at >= 0 && (cut < 0 || at < cut)) {
                cut = at;
            }
        }
        if (cut >= 0) {
            s = s.substring(0, cut);
        }

        s = s.trim();
        if (s.length() > MAX_CAUSE_MESSAGE) {
            s = s.substring(0, MAX_CAUSE_MESSAGE) + "...";
        }
        return s;
    }
}
