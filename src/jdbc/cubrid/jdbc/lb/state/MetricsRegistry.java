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

package cubrid.jdbc.lb.state;

import cubrid.jdbc.lb.state.RuntimeMetrics.EndpointStatsSnapshot;
import cubrid.jdbc.lb.state.RuntimeMetrics.LatencySnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-wide registry that lets an exporter read every pooled connection's {@link RuntimeMetrics}
 * <b>without borrowing the connection back from the pool</b>. Each {@link
 * cubrid.jdbc.lb.LoadBalanceConnection} registers its metrics at construction and unregisters at
 * close; the exporter iterates the registered objects directly, so sampling never contends with the
 * application for pool connections.
 *
 * <p><b>Flush on close.</b> A connection evicted by {@code maxLifetime} would otherwise take its
 * counts to the grave. {@link #unregister} folds the closing metrics' final snapshot into a
 * persistent per-endpoint cumulative tally, so {@link #aggregateByEndpoint()} keeps counting across
 * connection churn over a multi-day run.
 *
 * <p>Entries are never reclaimed except by close/unregister; the live set is bounded by the pool
 * size(s) and the role map by the number of distinct endpoints — both small for a fixed deployment.
 */
public final class MetricsRegistry {
    // Live metrics objects, one per open LoadBalanceConnection. Set-from-map so removal is O(1).
    private static final Set<RuntimeMetrics> LIVE =
            Collections.newSetFromMap(new ConcurrentHashMap<RuntimeMetrics, Boolean>());

    // Binding probe per live metrics object: the read endpoint each connection is bound to now.
    private static final ConcurrentHashMap<RuntimeMetrics, BindingView> PROBES =
            new ConcurrentHashMap<RuntimeMetrics, BindingView>();

    // Counts folded in from connections that have since closed. Guarded by CUMULATIVE's monitor.
    private static final Map<String, long[]> CUMULATIVE = new LinkedHashMap<String, long[]>();

    // Latency histograms folded in from closed connections, per endpoint. Guarded by its monitor.
    private static final Map<String, EndpointLatency> CUMULATIVE_LAT =
            new LinkedHashMap<String, EndpointLatency>();

    // endpointId (host:port) -> role ("master"/"slave"/"replica"). Union across registered URLs.
    private static final ConcurrentHashMap<String, String> ROLE =
            new ConcurrentHashMap<String, String>();

    // Read-leg movements, keyed "role|from|to". Failover = displaced off the weighted home;
    // failback = climbed back towards it. JVM-wide rather than per-connection: these are rare
    // events an operator correlates with an incident, and they must outlive the connection that
    // saw them. Guarded by each map's own monitor.
    private static final Map<String, long[]> FAILOVERS = new LinkedHashMap<String, long[]>();
    private static final Map<String, long[]> FAILBACKS = new LinkedHashMap<String, long[]>();

    private MetricsRegistry() {}

    /**
     * Registers a live metrics object, its endpoint→role map, and a probe for the read endpoint it
     * is bound to ({@code binding} may be null for a config with no read distribution).
     *
     * @param metrics the live metrics object to register; ignored when {@code null}
     * @param endpointRoles the endpoint id → role map to merge; may be {@code null}
     * @param binding the read-endpoint binding probe; may be {@code null}
     */
    public static void register(
            final RuntimeMetrics metrics,
            final Map<String, String> endpointRoles,
            final BindingView binding) {
        if (metrics == null) {
            return;
        }
        if (endpointRoles != null) {
            ROLE.putAll(endpointRoles);
        }
        LIVE.add(metrics);
        if (binding != null) {
            PROBES.put(metrics, binding);
        }
    }

    /**
     * Folds a closing metrics' final counts into the cumulative tally, then drops it.
     *
     * @param metrics the metrics object being closed; ignored when {@code null}
     */
    public static void unregister(final RuntimeMetrics metrics) {
        if (metrics == null) {
            return;
        }
        PROBES.remove(metrics);
        if (LIVE.remove(metrics)) {
            List<EndpointStatsSnapshot> snap = metrics.snapshotEndpoints();
            // Under CUMULATIVE's monitor, the one every reader takes. Pools close connections from
            // several threads at once (shutdown, maxLifetime eviction) and the accumulator arrays
            // are shared per endpoint, so an unguarded fold drops counts, and a reader iterating
            // the map while it is mutated can get a ConcurrentModificationException mid-scrape.
            // (foldLatencyInto takes CUMULATIVE_LAT's monitor itself.)
            synchronized (CUMULATIVE) {
                foldInto(CUMULATIVE, snap);
            }
            foldLatencyInto(snap);
        }
    }

    /**
     * Live connections currently bound to each read endpoint - a gauge, so closed connections drop
     * out. This is the distribution {@code readWeight} governs, unlike {@link
     * #aggregateByEndpoint()}, which counts executions and is skewed by per-node throughput.
     *
     * @return live connection counts keyed by bound read endpoint id
     */
    public static Map<String, Integer> aggregateBindingByEndpoint() {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (BindingView probe : PROBES.values()) {
            String id = probe.boundReadEndpointId();
            if (id == null) {
                continue;
            }
            Integer c = out.get(id);
            out.put(id, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
        }
        return out;
    }

    /**
     * Live sessions whose reads are served by the RW physical connection instead of their own read
     * endpoint ({@code roOnRw}). A gauge, and an early warning: a non-zero value outside a planned
     * master-role weight means those reads compete with write traffic on one connection, so read
     * latency degrades before any error is raised.
     *
     * @return the number of live sessions reading on their RW connection
     */
    public static int countRoOnRwSessions() {
        int n = 0;
        for (BindingView probe : PROBES.values()) {
            if (probe.readsOnRwConnection()) {
                n++;
            }
        }
        return n;
    }

    /**
     * How many live metrics objects are registered, that is, how many LB connections are open.
     *
     * <p>The CSV exporter reads this to decide when there is nothing left to export: zero for
     * several periods in a row means every LB connection has closed and the exporter thread can
     * stop.
     *
     * @return the number of registered live metrics objects
     */
    public static int liveCount() {
        return LIVE.size();
    }

    /**
     * Every endpoint id this JVM has seen in a topology, for per-endpoint gauges.
     *
     * @return the known endpoint ids, in first-seen order
     */
    public static Set<String> knownEndpointIds() {
        return new java.util.LinkedHashSet<String>(ROLE.keySet());
    }

    /**
     * Records that a session's read leg moved <em>away</em> from its intended endpoint — a
     * same-role sibling, a cross-role endpoint, or the RW connection. {@code toEndpointId} is where
     * it landed.
     *
     * @param role the role of the read leg's home endpoint
     * @param fromEndpointId the endpoint the read leg left
     * @param toEndpointId the endpoint the read leg landed on
     */
    public static void recordFailover(
            final String role, final String fromEndpointId, final String toEndpointId) {
        bump(FAILOVERS, role, fromEndpointId, toEndpointId);
    }

    /**
     * Records that a displaced read leg climbed back towards its weighted home endpoint.
     *
     * @param role the role of the read leg's home endpoint
     * @param fromEndpointId the endpoint the read leg left
     * @param toEndpointId the endpoint the read leg landed on
     */
    public static void recordFailback(
            final String role, final String fromEndpointId, final String toEndpointId) {
        bump(FAILBACKS, role, fromEndpointId, toEndpointId);
    }

    /**
     * Failover transitions observed so far, one entry per distinct (role, from, to).
     *
     * @return the failover transitions
     */
    public static List<Transition> failovers() {
        return snapshotTransitions(FAILOVERS);
    }

    /**
     * Failback transitions observed so far, one entry per distinct (role, from, to).
     *
     * @return the failback transitions
     */
    public static List<Transition> failbacks() {
        return snapshotTransitions(FAILBACKS);
    }

    private static void bump(
            final Map<String, long[]> target,
            final String role,
            final String fromEndpointId,
            final String toEndpointId) {
        String from = fromEndpointId == null ? "unknown" : fromEndpointId;
        String to = toEndpointId == null ? "unknown" : toEndpointId;
        String key = (role == null ? "unknown" : role) + '|' + from + '|' + to;
        synchronized (target) {
            long[] c = target.get(key);
            if (c == null) {
                c = new long[1];
                target.put(key, c);
            }
            c[0]++;
        }
    }

    private static List<Transition> snapshotTransitions(final Map<String, long[]> source) {
        List<Transition> out;
        synchronized (source) {
            out = new ArrayList<Transition>(source.size());
            for (Map.Entry<String, long[]> e : source.entrySet()) {
                String[] parts = e.getKey().split("\\|", 3);
                out.add(
                        new Transition(
                                parts[0],
                                parts.length > 1 ? parts[1] : "unknown",
                                parts.length > 2 ? parts[2] : "unknown",
                                e.getValue()[0]));
            }
        }
        return out;
    }

    /**
     * Role for an endpoint id, or {@code "unknown"} if it was never mapped.
     *
     * @param endpointId endpoint id (host:port)
     * @return the role, or {@code "unknown"}
     */
    public static String roleOf(final String endpointId) {
        String role = ROLE.get(endpointId);
        return role == null ? "unknown" : role;
    }

    /**
     * Point-in-time totals per endpoint: cumulative (closed connections) plus every live metrics
     * object. Each element is {@code {read, write, fallback}} keyed by endpoint id. Never null.
     *
     * @return per-endpoint totals, keyed by endpoint id
     */
    public static Map<String, long[]> aggregateByEndpoint() {
        Map<String, long[]> out = new LinkedHashMap<String, long[]>();
        synchronized (CUMULATIVE) {
            for (Map.Entry<String, long[]> e : CUMULATIVE.entrySet()) {
                long[] v = e.getValue();
                out.put(e.getKey(), new long[] {v[0], v[1], v[2]});
            }
        }
        for (RuntimeMetrics metrics : LIVE) {
            foldInto(out, metrics.snapshotEndpoints());
        }
        return out;
    }

    /** Clears the whole registry; for the export harness / unit tests only. */
    public static void clearForTests() {
        LIVE.clear();
        PROBES.clear();
        synchronized (CUMULATIVE) {
            CUMULATIVE.clear();
        }
        synchronized (CUMULATIVE_LAT) {
            CUMULATIVE_LAT.clear();
        }
        synchronized (FAILOVERS) {
            FAILOVERS.clear();
        }
        synchronized (FAILBACKS) {
            FAILBACKS.clear();
        }
        ROLE.clear();
    }

    /**
     * Per-endpoint execution-latency histograms: cumulative (closed connections) plus every live
     * metrics object, split read/write. The p50/p95/p99 signal, exported as Prometheus histograms.
     *
     * @return per-endpoint latency histograms, keyed by endpoint id
     */
    public static Map<String, EndpointLatency> aggregateLatencyByEndpoint() {
        Map<String, EndpointLatency> out = new LinkedHashMap<String, EndpointLatency>();
        synchronized (CUMULATIVE_LAT) {
            for (Map.Entry<String, EndpointLatency> e : CUMULATIVE_LAT.entrySet()) {
                EndpointLatency copy = new EndpointLatency();
                copy.addFrom(e.getValue());
                out.put(e.getKey(), copy);
            }
        }
        for (RuntimeMetrics metrics : LIVE) {
            for (EndpointStatsSnapshot s : metrics.snapshotEndpoints()) {
                EndpointLatency acc = out.get(s.getEndpointId());
                if (acc == null) {
                    acc = new EndpointLatency();
                    out.put(s.getEndpointId(), acc);
                }
                acc.add(s.getReadLatency(), s.getWriteLatency());
            }
        }
        return out;
    }

    private static void foldLatencyInto(final List<EndpointStatsSnapshot> stats) {
        synchronized (CUMULATIVE_LAT) {
            for (int i = 0; i < stats.size(); i++) {
                EndpointStatsSnapshot s = stats.get(i);
                EndpointLatency acc = CUMULATIVE_LAT.get(s.getEndpointId());
                if (acc == null) {
                    acc = new EndpointLatency();
                    CUMULATIVE_LAT.put(s.getEndpointId(), acc);
                }
                acc.add(s.getReadLatency(), s.getWriteLatency());
            }
        }
    }

    private static void foldInto(
            final Map<String, long[]> target, final List<EndpointStatsSnapshot> stats) {
        for (int i = 0; i < stats.size(); i++) {
            EndpointStatsSnapshot s = stats.get(i);
            long[] acc = target.get(s.getEndpointId());
            if (acc == null) {
                acc = new long[3];
                target.put(s.getEndpointId(), acc);
            }
            acc[0] += s.getReadCount();
            acc[1] += s.getWriteCount();
            acc[2] += s.getFallbackCount();
        }
    }

    /**
     * Summed latency histograms for one endpoint (read and write), across the pool. Bucket slots
     * align with {@link RuntimeMetrics#LATENCY_BUCKETS_MS} (+1 for the +Inf overflow); counts are
     * non-cumulative per slot (the exporter cumulates them into {@code le} buckets).
     */
    public static final class EndpointLatency {
        private final long[] readBuckets;
        private long readCount;
        private double readSumMs;
        private final long[] writeBuckets;
        private long writeCount;
        private double writeSumMs;

        EndpointLatency() {
            int slots = RuntimeMetrics.LATENCY_BUCKETS_MS.length + 1;
            this.readBuckets = new long[slots];
            this.writeBuckets = new long[slots];
        }

        void add(final LatencySnapshot read, final LatencySnapshot write) {
            addInto(readBuckets, read);
            readCount += read.getCount();
            readSumMs += read.getSumMs();
            addInto(writeBuckets, write);
            writeCount += write.getCount();
            writeSumMs += write.getSumMs();
        }

        void addFrom(final EndpointLatency o) {
            for (int i = 0; i < readBuckets.length; i++) {
                readBuckets[i] += o.readBuckets[i];
                writeBuckets[i] += o.writeBuckets[i];
            }
            readCount += o.readCount;
            readSumMs += o.readSumMs;
            writeCount += o.writeCount;
            writeSumMs += o.writeSumMs;
        }

        private static void addInto(final long[] dst, final LatencySnapshot s) {
            for (int i = 0; i < dst.length; i++) {
                dst[i] += s.bucketCount(i);
            }
        }

        public long[] getReadBuckets() {
            return readBuckets;
        }

        public long getReadCount() {
            return readCount;
        }

        public double getReadSumMs() {
            return readSumMs;
        }

        public long[] getWriteBuckets() {
            return writeBuckets;
        }

        public long getWriteCount() {
            return writeCount;
        }

        public double getWriteSumMs() {
            return writeSumMs;
        }
    }

    /**
     * Immutable count of one distinct read-leg movement: {@code role}, {@code from} to {@code to}.
     */
    public static final class Transition {
        private final String role;
        private final String from;
        private final String to;
        private final long count;

        Transition(final String role, final String from, final String to, final long count) {
            this.role = role;
            this.from = from;
            this.to = to;
            this.count = count;
        }

        public String getRole() {
            return role;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }

        public long getCount() {
            return count;
        }
    }
}
