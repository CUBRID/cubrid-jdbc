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

package cubrid.jdbc.lb.metrics;

import cubrid.jdbc.lb.LbTestReflection;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The package-private members of {@code cubrid.jdbc.lb.metrics} that tests in other packages use.
 * It lives in the test tree, so the driver jar does not carry it.
 */
public final class MetricsTestAccess {
    private MetricsTestAccess() {}

    /** Empties the registry, each collection under the lock the registry itself takes for it. */
    /**
     * Stops both exporters and forgets them, as the driver does once no LB connection has been live
     * for a while, so the next {@code configure} starts fresh.
     */
    public static void stopExporters() {
        synchronized (LbTestReflection.staticField(MetricsExporters.class, "LOCK")) {
            final com.sun.net.httpserver.HttpServer server =
                    (com.sun.net.httpserver.HttpServer)
                            LbTestReflection.staticField(
                                    MetricsExporters.class, "prometheusServer");
            if (server != null) {
                server.stop(0);
                ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdown();
            }
            for (String thread : new String[] {"prometheusWatch", "csvThread"}) {
                final Thread t =
                        (Thread) LbTestReflection.staticField(MetricsExporters.class, thread);
                if (t != null) {
                    t.interrupt();
                }
                LbTestReflection.setStatic(MetricsExporters.class, thread, null);
            }
            LbTestReflection.setStatic(MetricsExporters.class, "prometheusServer", null);
            LbTestReflection.setStatic(MetricsExporters.class, "prometheusStarted", Boolean.FALSE);
            LbTestReflection.setStatic(MetricsExporters.class, "csvStarted", Boolean.FALSE);
        }
    }

    public static void clearForTests() {
        collection("LIVE").clear();
        map("PROBES").clear();
        for (String guarded :
                new String[] {"CUMULATIVE", "CUMULATIVE_LAT", "FAILOVERS", "FAILBACKS"}) {
            final Map<?, ?> each = map(guarded);
            synchronized (each) {
                each.clear();
            }
        }
        map("ROLE").clear();
    }

    private static Collection<?> collection(final String field) {
        return (Collection<?>) LbTestReflection.staticField(MetricsRegistry.class, field);
    }

    private static Map<?, ?> map(final String field) {
        return (Map<?, ?>) LbTestReflection.staticField(MetricsRegistry.class, field);
    }

    public static long getRwFallbackCount(final RuntimeMetrics metrics) {
        return metrics.getRwFallbackCount();
    }

    public static List<String> getEvents(final RuntimeMetrics metrics) {
        return metrics.getEvents();
    }

    public static long getRoSelectionCount(final RuntimeMetrics metrics) {
        return metrics.getRoSelectionCount();
    }

    public static long getEndpointCount(final RuntimeMetrics metrics, final String endpointId) {
        return metrics.getEndpointCount(endpointId);
    }

    public static boolean isEventsEnabled(final RuntimeMetrics metrics) {
        return ((Boolean) LbTestReflection.field(metrics, "eventsEnabled")).booleanValue();
    }

    /** {@code {read, write}} as {@code lb_endpoint_exec_total} reports them for one endpoint. */
    public static long[] getReadWriteCounts(final RuntimeMetrics metrics, final String endpointId) {
        for (RuntimeMetrics.EndpointStatsSnapshot s : metrics.snapshotEndpoints()) {
            if (s.getEndpointId().equals(endpointId)) {
                return new long[] {s.getReadCount(), s.getWriteCount()};
            }
        }
        return new long[] {0L, 0L};
    }

    /** The distinct (role, from, to) movements behind {@code lb_failover_total}. */
    public static int getFailoverTransitions() {
        return MetricsRegistry.failovers().size();
    }

    /** The distinct (role, from, to) movements behind {@code lb_failback_total}. */
    public static int getFailbackTransitions() {
        return MetricsRegistry.failbacks().size();
    }
}
