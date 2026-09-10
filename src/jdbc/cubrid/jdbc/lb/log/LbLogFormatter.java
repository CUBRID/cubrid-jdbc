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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Single-line, pipe-delimited formatter: {@code ts|LEVEL|[thread|]ctx|message}.
 *
 * <p>The leading field is a sortable timestamp so {@code sort}/{@code grep}/{@code awk -F'|'} all
 * work on the file directly; a thrown exception follows on tab-indented continuation lines, so
 * {@code grep '^20'} still isolates the records. The delimiter and timestamp shape match {@code
 * cubrid.jdbc.log.BasicLogger} so CUBRID's driver-side logs read alike.
 *
 * <p>{@code ctx} is the per-connection identifier ({@code conn#7}) supplied by {@link LbLog}; call
 * sites with no connection in scope (URL/option parsing, exporter setup) log through the plain
 * {@code Logger} API and render as {@code -}.
 *
 * <p>Deliberately absent: the source class and method. They cost a quarter of the line while
 * repeating the {@code cubrid.jdbc.lb.} prefix on every record, and JUL fills them by walking a
 * stack trace, which costs far more than formatting the message itself. Every LB record already
 * opens with a unique tag ({@code LB FAILOVER [RO]}, {@code LB BIND [RW]}) that {@code grep} finds.
 *
 * <p>{@link #formatMessage} is <b>not</b> used: it would run the text through {@code MessageFormat}
 * whenever parameters are present, and a logged SQL string containing braces would then be mangled
 * (or throw). The raw message is emitted as-is and the parameter array carries only the context.
 */
public final class LbLogFormatter extends Formatter {
    private static final String NO_CONTEXT = "-";
    private static final String LINE_SEPARATOR = System.getProperty("line.separator", "\n");

    private final boolean withThread;
    // SimpleDateFormat is not thread-safe; format() is synchronized so the shared instance is only
    // ever touched by one thread at a time.
    private final SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    public LbLogFormatter(final boolean withThread) {
        this.withThread = withThread;
    }

    @Override
    public synchronized String format(final LogRecord record) {
        StringBuilder b = new StringBuilder(192);

        b.append(timestamp.format(new Date(record.getMillis()))).append('|');
        b.append(levelName(record.getLevel())).append('|');
        if (withThread) {
            b.append(Thread.currentThread().getName()).append('|');
        }
        b.append(context(record)).append('|');
        b.append(record.getMessage() == null ? "" : record.getMessage());
        b.append(LINE_SEPARATOR);

        Throwable thrown = record.getThrown();
        if (thrown != null) {
            b.append(indentedStackTrace(thrown));
        }

        return b.toString();
    }

    /**
     * The context field: whatever {@link LbLog} attached as the sole parameter, or {@code -}. A
     * plain {@code LOGGER.warning(...)} call carries no parameters and lands on the default.
     */
    private static String context(final LogRecord record) {
        Object[] params = record.getParameters();
        if (params == null || params.length == 0 || params[0] == null) {
            return NO_CONTEXT;
        }
        String ctx = params[0].toString();
        return ctx.length() == 0 ? NO_CONTEXT : ctx;
    }

    /**
     * Fixed-width level names, so the message column lines up when the file is read directly. The
     * JUL names are mapped to the shorter conventional ones ({@code WARNING} → {@code WARN}, {@code
     * SEVERE} → {@code ERROR}, {@code FINE} → {@code DEBUG}) that CUBRID's other driver-side log
     * already uses.
     */
    private static String levelName(final Level level) {
        if (level == null) {
            return "     ";
        }
        if (Level.WARNING.equals(level)) {
            return "WARN ";
        }
        if (Level.SEVERE.equals(level)) {
            return "ERROR";
        }
        if (Level.INFO.equals(level)) {
            return "INFO ";
        }
        if (Level.FINE.equals(level)) {
            return "DEBUG";
        }
        String name = level.getName();
        if (name.length() >= 5) {
            return name.substring(0, 5);
        }
        return (name + "     ").substring(0, 5);
    }

    /**
     * Tab-indents every line of the stack trace so continuation lines never look like new records
     * to a line-oriented reader.
     */
    private static String indentedStackTrace(final Throwable thrown) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        thrown.printStackTrace(pw);
        pw.close();

        StringBuilder b = new StringBuilder(sw.getBuffer().length() + 64);
        String[] lines = sw.toString().split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].length() == 0) {
                continue;
            }
            b.append('\t').append(lines[i].trim()).append(LINE_SEPARATOR);
        }
        return b.toString();
    }
}
