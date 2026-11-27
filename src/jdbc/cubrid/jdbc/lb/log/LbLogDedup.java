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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collapses the burst of identical state-transition records a connection pool produces from a
 * single cluster event.
 *
 * <p>The transition records are written per connection, so one broker going down makes every
 * session in the pool log its own failover: a hundred-connection pool writes a hundred lines for
 * one event, then a hundred more when the broker returns and they fail back. The information in
 * lines 2..100 is the count, not the content.
 *
 * <p>Within a window, the first occurrence of a key is written and the rest are counted. What a
 * window held back is written as that window's own {@code LB SUPPRESSED} record, never dropped and
 * never attached to a later occurrence, which may come hours later: before the next occurrence of
 * the key, when the map reaches its cap, and by {@link #drain()}.
 *
 * <pre>
 * 13:25:39.583|WARN |conn#1|LB FAILOVER [RO] failed=… -&gt; new=… &lt;== REBOUND | SQLState=…
 * 15:02:11.006|WARN |-|LB SUPPRESSED: 99 further occurrence(s) of [FAILOVER|RO|…] in the 10s window that began 5791s ago were collapsed and not written individually | last: …
 * 15:02:11.006|WARN |conn#7|LB FAILOVER [RO] failed=… -&gt; new=… &lt;== REBOUND | SQLState=…
 * </pre>
 *
 * <p>{@link #drain()} runs when the last LB connection closes, and again from a shutdown hook
 * ({@code LbRuntime}). Without it, a burst never followed by another occurrence - the common case
 * once a cluster settles - would report "1" for an event that hit every connection. A timer thread
 * would close the gap sooner, but a JDBC driver should not start one for logging.
 *
 * <p>Most keys are transitions ({@code role|from|to}), bounded by the topology; {@code RO REJECTED}
 * keys carry a statement shape, so traffic can reach the cap.
 */
public final class LbLogDedup {
    /**
     * Default suppression window. Long enough to absorb a pool-wide reaction to one cluster event,
     * short enough that a genuinely repeating transition (a flapping broker) still shows its
     * rhythm.
     */
    private static final long DEFAULT_WINDOW_MS = 10000L;

    private static final int MAX_KEYS = 256;

    private static final Map<String, Pending> STATE = new LinkedHashMap<String, Pending>();

    private LbLogDedup() {}

    private static final class Pending {
        private long windowStartNanos;
        private final long windowMs;
        private long suppressed;
        private Logger logger;
        private String message;
        private Level level;

        private Pending(final long windowStartNanos, final long windowMs) {
            this.windowStartNanos = windowStartNanos;
            this.windowMs = windowMs;
        }
    }

    /** A record to write once the lock is released. */
    private static final class Line {
        private final Logger logger;
        private final Level level;
        private final String ctx;
        private final String text;

        private Line(final Logger logger, final Level level, final String ctx, final String text) {
            this.logger = logger;
            this.level = level;
            this.ctx = ctx;
            this.text = text;
        }
    }

    /**
     * Logs {@code message} at WARNING unless an identical {@code key} was already logged inside the
     * current window, in which case it is counted instead.
     *
     * @param logger the logger to write through
     * @param ctx the connection context for the record, or {@code null}
     * @param key the transition identity used for collapsing
     * @param message the record to write when admitted
     */
    public static void warn(
            final Logger logger, final String ctx, final String key, final String message) {
        log(logger, Level.WARNING, ctx, key, message, DEFAULT_WINDOW_MS);
    }

    /**
     * As {@link #warn} but at INFO, for transitions that are informative, not degraded states.
     *
     * @param logger the logger to write through
     * @param ctx the connection context for the record, or {@code null}
     * @param key the transition identity used for collapsing
     * @param message the record to write when admitted
     */
    public static void info(
            final Logger logger, final String ctx, final String key, final String message) {
        log(logger, Level.INFO, ctx, key, message, DEFAULT_WINDOW_MS);
    }

    /**
     * As {@link #warn} but at FINE, for the reproduction-run detail records.
     *
     * @param logger the logger to write through
     * @param ctx the connection context for the record, or {@code null}
     * @param key the transition identity used for collapsing
     * @param message the record to write when admitted
     */
    public static void fine(
            final Logger logger, final String ctx, final String key, final String message) {
        log(logger, Level.FINE, ctx, key, message, DEFAULT_WINDOW_MS);
    }

    static void warn(
            final Logger logger,
            final String ctx,
            final String key,
            final String message,
            final long windowMs) {
        log(logger, Level.WARNING, ctx, key, message, windowMs);
    }

    private static void log(
            final Logger logger,
            final Level level,
            final String ctx,
            final String key,
            final String message,
            final long windowMs) {
        // Cheap exit when the level is off: no window bookkeeping for records that cannot be
        // written anyway, so a FINE-keyed transition costs nothing at the default level.
        if (logger == null || !logger.isLoggable(level)) {
            return;
        }
        for (Line line : admit(logger, level, ctx, key, message, windowMs)) {
            LbLog.at(line.logger, line.level, line.ctx, line.text);
        }
    }

    /**
     * @return the records to write: the tallies of windows that are over, then this occurrence
     *     unless its window holds it back
     */
    private static synchronized List<Line> admit(
            final Logger logger,
            final Level level,
            final String ctx,
            final String key,
            final String message,
            final long windowMs) {
        final long now = System.nanoTime(); // a wall clock set back would stretch the window
        final List<Line> lines = new ArrayList<Line>(2);
        Pending pending = STATE.get(key);

        if (pending == null) {
            if (STATE.size() >= MAX_KEYS) {
                summarizeAll(now, lines);
                STATE.clear();
            }
            pending = new Pending(now, windowMs);
            STATE.put(key, pending);
        } else if (now - pending.windowStartNanos < TimeUnit.MILLISECONDS.toNanos(windowMs)) {
            pending.logger = logger;
            pending.message = message;
            pending.level = level;
            pending.suppressed++;
            return lines;
        } else {
            summarize(key, pending, now, lines);
            pending.windowStartNanos = now;
            pending.suppressed = 0L;
        }

        pending.logger = logger;
        pending.message = message;
        pending.level = level;
        lines.add(new Line(logger, level, ctx, message));
        return lines;
    }

    private static void summarizeAll(final long now, final List<Line> into) {
        for (Map.Entry<String, Pending> e : STATE.entrySet()) {
            summarize(e.getKey(), e.getValue(), now, into);
        }
    }

    /** The record of what {@code p}'s window held back, if anything. */
    private static void summarize(
            final String key, final Pending p, final long now, final List<Line> into) {
        if (p.suppressed == 0L || p.logger == null) {
            return;
        }
        into.add(
                new Line(
                        p.logger,
                        p.level,
                        null,
                        "LB SUPPRESSED: "
                                + p.suppressed
                                + " further occurrence(s) of ["
                                + key
                                + "] in the "
                                + (p.windowMs / 1000L)
                                + "s window that began "
                                + TimeUnit.NANOSECONDS.toSeconds(now - p.windowStartNanos)
                                + "s ago were collapsed and not written individually | last: "
                                + p.message));
    }

    /**
     * Writes a summary for every key still holding a suppressed tally, then clears the state, so a
     * burst that never recurred is still reported.
     */
    public static void drain() {
        final List<Line> flush = new ArrayList<Line>();

        synchronized (LbLogDedup.class) {
            summarizeAll(System.nanoTime(), flush);
            STATE.clear();
        }

        for (Line line : flush) {
            LbLog.at(line.logger, line.level, line.ctx, line.text);
        }
    }
}
