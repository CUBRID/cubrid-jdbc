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
 * <p>Within a window, the first occurrence of a transition key is written and the rest are counted.
 * The next occurrence after the window closes carries the tally of what was held back, so nothing
 * is silently dropped:
 *
 * <pre>
 * 13:25:39.583|WARN |conn#1|LB FAILOVER [RO] failed=… -&gt; new=… &lt;== REBOUND | SQLState=…
 * 13:25:49.902|WARN |conn#7|LB FAILOVER [RO] failed=… -&gt; new=… &lt;== REBOUND | SQLState=…  (+99 more in the previous 10s)
 * </pre>
 *
 * <p>A tally still pending when the JVM exits is flushed by {@link #drain()}, which {@link
 * LbFileLogging} calls from a shutdown hook. Without it, a burst never followed by another
 * transition - the common case once a cluster settles - would report "1" for an event that hit
 * every connection. A timer thread would close the gap sooner, but a JDBC driver should not start
 * one for logging.
 *
 * <p>Keys are transitions ({@code role|from|to}), so the map is bounded by the topology, not by
 * traffic. The cap is a backstop for a caller that keys on something unbounded.
 */
public final class LbLogDedup {
    /**
     * Default suppression window. Long enough to absorb a pool-wide reaction to one cluster event,
     * short enough that a genuinely repeating transition (a flapping broker) still shows its
     * rhythm.
     */
    public static final long DEFAULT_WINDOW_MS = 10000L;

    private static final int MAX_KEYS = 256;

    private static final Map<String, Pending> STATE = new LinkedHashMap<String, Pending>();

    private LbLogDedup() {}

    private static final class Pending {
        private long windowStartMs;
        private long suppressed;
        private Logger logger;
        private String message;
        private Level level;

        private Pending(final long windowStartMs) {
            this.windowStartMs = windowStartMs;
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
        String suffix = admit(logger, level, key, message, windowMs);
        if (suffix == null) {
            return;
        }
        LbLog.at(logger, level, ctx, message + suffix);
    }

    /**
     * @return {@code null} when this occurrence is suppressed, otherwise the (possibly empty)
     *     suffix reporting what was held back since the last admitted occurrence
     */
    private static synchronized String admit(
            final Logger logger,
            final Level level,
            final String key,
            final String message,
            final long windowMs) {
        long now = System.currentTimeMillis();
        Pending pending = STATE.get(key);

        if (pending == null) {
            if (STATE.size() >= MAX_KEYS) {
                // Unexpected for transition keys; drop the accumulated state rather than grow
                // without bound. Any tallies lost here were for keys that stopped recurring.
                STATE.clear();
            }
            pending = new Pending(now);
            pending.logger = logger;
            pending.message = message;
            pending.level = level;
            STATE.put(key, pending);
            return "";
        }

        pending.logger = logger;
        pending.message = message;
        pending.level = level;

        if (now - pending.windowStartMs < windowMs) {
            pending.suppressed++;
            return null;
        }

        long held = pending.suppressed;
        pending.windowStartMs = now;
        pending.suppressed = 0L;

        return held == 0L
                ? ""
                : "  (+" + held + " more in the previous " + (windowMs / 1000L) + "s)";
    }

    /**
     * Writes a summary for every key still holding a suppressed tally, then clears the state.
     * Called from the JVM shutdown hook so a burst that never recurred is still reported.
     */
    public static void drain() {
        List<Pending> flush = new ArrayList<Pending>();
        List<String> keys = new ArrayList<String>();

        synchronized (LbLogDedup.class) {
            for (Map.Entry<String, Pending> e : STATE.entrySet()) {
                Pending p = e.getValue();
                if (p.suppressed > 0L && p.logger != null) {
                    keys.add(e.getKey());
                    flush.add(p);
                }
            }
            STATE.clear();
        }

        for (int i = 0; i < flush.size(); i++) {
            Pending p = flush.get(i);
            // Written straight to the file: drain() runs from a shutdown hook, where the logger may
            // already be gone. See LbFileLogging#appendDirect.
            LbLog.atShutdown(
                    p.logger,
                    p.level,
                    null,
                    "LB SUPPRESSED: "
                            + p.suppressed
                            + " further occurrence(s) of ["
                            + keys.get(i)
                            + "] were collapsed and not written individually | last: "
                            + p.message);
        }
    }

    /** Test hook: forgets all windows so a following test starts from a clean state. */
    static synchronized void resetForTests() {
        STATE.clear();
    }
}
