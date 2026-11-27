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

package cubrid.jdbc.lb;

import cubrid.jdbc.lb.log.LbLogDedup;
import cubrid.jdbc.lb.report.LbDistLog;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * What LB keeps JVM-wide past one connection: how many LB connections are open, and the flush of
 * what the log collapsing ({@link LbLogDedup}) and the {@code LB DIST} record ({@link LbDistLog})
 * still hold. The flush runs when the last connection closes, the point that can be relied on, and
 * from one shutdown hook kept while a connection is open, as a fallback: {@code java.util.logging}
 * removes its handlers from a hook of its own, and hooks run concurrently. The metrics exporters
 * stop by their own count of the connections registered for metrics ({@code MetricsExporters}).
 */
final class LbRuntime {
    private static final Logger LOGGER = Logger.getLogger(LoadBalanceConnection.class.getName());
    private static final AtomicInteger LIVE = new AtomicInteger();
    // Guarded by LbRuntime.class.
    private static Thread hook;

    private LbRuntime() {}

    static void connectionOpened() {
        LIVE.incrementAndGet();
        installHook();
    }

    static void connectionClosed() {
        if (LIVE.decrementAndGet() > 0) {
            return;
        }
        flush();
        removeHook();
        // A connection opened during the flush found the hook that was just removed.
        if (LIVE.get() > 0) {
            installHook();
        }
    }

    private static void flush() {
        LbLogDedup.drain();
        LbDistLog.drainFinal();
    }

    private static synchronized void installHook() {
        if (hook != null) {
            return;
        }
        final Thread t =
                new Thread("lb-flush") {
                    @Override
                    public void run() {
                        flush();
                    }
                };
        t.setContextClassLoader(null); // as core's own threads: no application loader held
        try {
            Runtime.getRuntime().addShutdownHook(t);
            hook = t;
        } catch (Exception e) {
            // A container may forbid shutdown hooks, or the JVM is exiting: the last close flushes.
            LOGGER.warning("LB: cannot register the shutdown flush hook: " + e);
        }
    }

    private static void removeHook() {
        final Thread installed;
        synchronized (LbRuntime.class) {
            installed = hook;
            hook = null;
        }
        if (installed != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(installed);
            } catch (Exception shuttingDown) {
                // this flush is the hook itself
            }
        }
    }
}
