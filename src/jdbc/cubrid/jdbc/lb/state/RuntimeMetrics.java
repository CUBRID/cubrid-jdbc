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

import cubrid.jdbc.lb.sql.SqlClassification;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime metrics state for LB execution.
 *
 * <p>Recording is <em>disabled by default</em>: counters and the event log only accumulate after
 * {@link #setEnabled(boolean)} is called with {@code true}. This keeps production paying nothing
 * for a diagnostic that has no production consumer, while tests (and any caller that opts in via
 * {@link cubrid.jdbc.lb.LoadBalanceConnection#getRuntimeMetrics()}) can switch it on.
 *
 * <p>The event log is a bounded ring buffer: once it holds {@code maxEvents} records, each new
 * record evicts the oldest. This caps memory for long-lived connections that would otherwise grow
 * an unbounded list, one entry per executed statement.
 */
public final class RuntimeMetrics {
    /** Default cap on retained event records once metrics recording is enabled. */
    static final int DEFAULT_MAX_EVENTS = 256;

    private final AtomicLong roSelectionCount = new AtomicLong();
    private final AtomicLong rwFallbackCount = new AtomicLong();
    private final Map<String, EndpointTally> endpointStats = new HashMap<String, EndpointTally>();
    private final int maxEvents;
    private final ArrayDeque<String> events;
    private volatile boolean enabled;
    private volatile boolean eventsEnabled;

    public RuntimeMetrics() {
        this(DEFAULT_MAX_EVENTS);
    }

    public RuntimeMetrics(final int maxEvents) {
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
        this.maxEvents = maxEvents;
        this.events = new ArrayDeque<String>(maxEvents);
    }

    /**
     * Whether recording is active. Disabled by default; see the class javadoc.
     *
     * @return {@code true} if recording is active
     */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        this.eventsEnabled = enabled;
    }

    /**
     * Whether the per-execution event log is kept, separately from the counters.
     *
     * <p>The two cost very different amounts. The counters are a few atomic increments and a map
     * update; the event log builds a string for every statement and keeps the last {@link
     * #DEFAULT_MAX_EVENTS} of them. A consumer that only needs totals - the periodic {@code LB
     * DIST} record - would pay for strings it never reads.
     *
     * <p>Defaults to following {@link #setEnabled}, so a caller that wants everything still gets
     * it.
     *
     * @return whether events are retained
     */
    public boolean isEventsEnabled() {
        return eventsEnabled;
    }

    public void setEventsEnabled(final boolean eventsEnabled) {
        this.eventsEnabled = eventsEnabled;
    }

    public void incRoSelectionCount() {
        if (enabled) {
            roSelectionCount.incrementAndGet();
        }
    }

    public void incRwFallbackCount() {
        if (enabled) {
            rwFallbackCount.incrementAndGet();
        }
    }

    /**
     * Counts one hit on {@code endpointId} that has no read/write classification (commands,
     * failover rebind, RO/RW restore) into the {@code unknown} bucket, not into a total. {@link
     * #getEndpointCount(String)} is what sums read + write + unknown.
     *
     * @param endpointId the endpoint that was hit
     */
    public synchronized void incUnclassifiedHit(final String endpointId) {
        if (!enabled) {
            return;
        }
        statFor(endpointId).unknown++;
    }

    /**
     * Records one routed statement against {@code endpointId}, split by read/write. A role whose
     * read reuses the RW connection (master roOnRw, same host:port as its writes) still has reads
     * and writes counted apart, which is what a ratio recomputation needs. {@code fallback} marks a
     * route that did not land on its intended target.
     *
     * @param endpointId the endpoint the statement ran on
     * @param classification the statement's READ/WRITE classification
     * @param fallback whether the route did not land on its intended target
     */
    public synchronized void recordExec(
            final String endpointId,
            final SqlClassification classification,
            final boolean fallback) {
        if (!enabled) {
            return;
        }
        EndpointTally stat = statFor(endpointId);
        if (classification == SqlClassification.READ) {
            stat.read++;
        } else if (classification == SqlClassification.WRITE) {
            stat.write++;
        } else {
            stat.unknown++;
        }
        if (fallback) {
            stat.fallback++;
        }
    }

    /**
     * Records the execution latency of one statement on {@code endpointId}, into a Prometheus-style
     * histogram split by read/write. Called from the <em>execute</em> path, not prepare, so it is
     * real per-node response time - the p50/p95/p99 signal readWeight tuning needs. {@code nanos}
     * is wall time and is stored in milliseconds.
     *
     * @param endpointId the endpoint the statement ran on
     * @param write whether the statement was a write
     * @param nanos the measured wall time in nanoseconds
     */
    public synchronized void recordLatency(
            final String endpointId, final boolean write, final long nanos) {
        if (!enabled) {
            return;
        }
        double ms = nanos / 1000000.0d;
        EndpointTally stat = statFor(endpointId);
        (write ? stat.writeLat : stat.readLat).record(ms);
    }

    /**
     * Total statements observed on {@code endpointId} (read + write + unclassified).
     *
     * @param endpointId endpoint id (host:port)
     * @return the total statement count, or {@code 0} if the endpoint was never observed
     */
    public synchronized long getEndpointCount(final String endpointId) {
        EndpointTally stat = endpointStats.get(endpointId);
        return stat == null ? 0 : stat.read + stat.write + stat.unknown;
    }

    /**
     * Per-endpoint read count. The live-cluster harnesses assert the readWeight distribution and
     * the failover/failback legs from it.
     *
     * @param endpointId endpoint id (host:port)
     * @return the read count, or {@code 0} if the endpoint was never observed
     */
    public synchronized long getEndpointReadCount(final String endpointId) {
        EndpointTally stat = endpointStats.get(endpointId);
        return stat == null ? 0 : stat.read;
    }

    /**
     * Per-endpoint write count; the write-leg counterpart of {@link #getEndpointReadCount}.
     *
     * @param endpointId endpoint id (host:port)
     * @return the write count, or {@code 0} if the endpoint was never observed
     */
    public synchronized long getEndpointWriteCount(final String endpointId) {
        EndpointTally stat = endpointStats.get(endpointId);
        return stat == null ? 0 : stat.write;
    }

    /**
     * Consistent point-in-time copy of every observed endpoint's counts. Immutable snapshots let a
     * caller sum per-connection metrics across a whole pool without racing live counters.
     *
     * @return one immutable snapshot per observed endpoint
     */
    public synchronized List<EndpointStatsSnapshot> snapshotEndpoints() {
        List<EndpointStatsSnapshot> out =
                new ArrayList<EndpointStatsSnapshot>(endpointStats.size());
        for (Map.Entry<String, EndpointTally> each : endpointStats.entrySet()) {
            EndpointTally s = each.getValue();
            out.add(
                    new EndpointStatsSnapshot(
                            each.getKey(),
                            s.read,
                            s.write,
                            s.unknown,
                            s.fallback,
                            s.readLat.snapshot(),
                            s.writeLat.snapshot()));
        }
        return out;
    }

    /**
     * Clears all counters and the event log so a long run can be measured in windows (e.g. reset at
     * the top of each hour, snapshot at the bottom). Leaves {@link #isEnabled()} untouched.
     */
    public synchronized void reset() {
        roSelectionCount.set(0);
        rwFallbackCount.set(0);
        endpointStats.clear();
        events.clear();
    }

    private EndpointTally statFor(final String endpointId) {
        EndpointTally stat = endpointStats.get(endpointId);
        if (stat == null) {
            stat = new EndpointTally();
            endpointStats.put(endpointId, stat);
        }
        return stat;
    }

    public synchronized void recordEvent(final String event) {
        if (!enabled || !eventsEnabled) {
            return;
        }
        if (events.size() == maxEvents) {
            events.pollFirst(); // ring buffer: evict the oldest record
        }
        events.addLast(event);
    }

    public synchronized List<String> getEvents() {
        return new ArrayList<String>(events);
    }

    public long getRoSelectionCount() {
        return roSelectionCount.get();
    }

    public long getRwFallbackCount() {
        return rwFallbackCount.get();
    }

    /** Upper bounds (ms, ascending) for the latency histogram; a final +Inf slot follows. */
    public static final double[] LATENCY_BUCKETS_MS = {
        1.0d, 2.0d, 5.0d, 10.0d, 25.0d, 50.0d, 100.0d, 250.0d, 500.0d, 1000.0d, 2500.0d, 5000.0d,
        10000.0d
    };

    /** Mutable per-endpoint tally, guarded by the enclosing instance's monitor. */
    private static final class EndpointTally {
        // long, not int: these are never reset while the connection lives, so a busy long-lived
        // connection would wrap an int within months and the aggregate would jump backwards
        // (Prometheus reads a decreasing counter as a reset).
        private long read;
        private long write;
        private long unknown;
        private long fallback;
        private final LatencyHistogram readLat = new LatencyHistogram();
        private final LatencyHistogram writeLat = new LatencyHistogram();
    }

    /** Mutable latency histogram; guarded by the enclosing instance's monitor. */
    private static final class LatencyHistogram {
        // one slot per bucket boundary plus a final +Inf overflow slot
        private final long[] counts = new long[LATENCY_BUCKETS_MS.length + 1];
        private long count;
        private double sumMs;

        void record(final double ms) {
            count++;
            sumMs += ms;
            for (int i = 0; i < LATENCY_BUCKETS_MS.length; i++) {
                if (ms <= LATENCY_BUCKETS_MS[i]) {
                    counts[i]++;
                    return;
                }
            }
            counts[LATENCY_BUCKETS_MS.length]++; // +Inf
        }

        LatencySnapshot snapshot() {
            long[] copy = new long[counts.length];
            System.arraycopy(counts, 0, copy, 0, counts.length);
            return new LatencySnapshot(copy, count, sumMs);
        }
    }

    /**
     * Immutable copy of one latency histogram. Bucket counts are <b>non-cumulative</b> (per slot);
     * a Prometheus exporter cumulates them into {@code le} buckets.
     */
    public static final class LatencySnapshot {
        private final long[] bucketCounts; // index i pairs with LATENCY_BUCKETS_MS[i]; last = +Inf
        private final long count;
        private final double sumMs;

        LatencySnapshot(final long[] bucketCounts, final long count, final double sumMs) {
            this.bucketCounts = bucketCounts;
            this.count = count;
            this.sumMs = sumMs;
        }

        /**
         * Number of slots ({@code LATENCY_BUCKETS_MS.length + 1}; the last one is +Inf).
         *
         * @return the number of histogram slots
         */
        public int slots() {
            return bucketCounts.length;
        }

        /**
         * Non-cumulative count in slot {@code i}.
         *
         * @param i slot index, in {@code [0, slots())}
         * @return the count recorded in that slot
         */
        public long bucketCount(final int i) {
            return bucketCounts[i];
        }

        public long getCount() {
            return count;
        }

        public double getSumMs() {
            return sumMs;
        }
    }

    /**
     * Immutable point-in-time copy of one endpoint's counts, returned by {@link
     * #snapshotEndpoints()}.
     */
    public static final class EndpointStatsSnapshot {
        private final String endpointId;
        private final long readCount;
        private final long writeCount;
        private final long unknownCount;
        private final long fallbackCount;
        private final LatencySnapshot readLatency;
        private final LatencySnapshot writeLatency;

        EndpointStatsSnapshot(
                final String endpointId,
                final long readCount,
                final long writeCount,
                final long unknownCount,
                final long fallbackCount,
                final LatencySnapshot readLatency,
                final LatencySnapshot writeLatency) {
            this.endpointId = endpointId;
            this.readCount = readCount;
            this.writeCount = writeCount;
            this.unknownCount = unknownCount;
            this.fallbackCount = fallbackCount;
            this.readLatency = readLatency;
            this.writeLatency = writeLatency;
        }

        /**
         * Read-latency histogram snapshot for this endpoint.
         *
         * @return the read-latency snapshot
         */
        public LatencySnapshot getReadLatency() {
            return readLatency;
        }

        /**
         * Write-latency histogram snapshot for this endpoint.
         *
         * @return the write-latency snapshot
         */
        public LatencySnapshot getWriteLatency() {
            return writeLatency;
        }

        public String getEndpointId() {
            return endpointId;
        }

        public long getReadCount() {
            return readCount;
        }

        public long getWriteCount() {
            return writeCount;
        }

        public long getUnknownCount() {
            return unknownCount;
        }

        public long getFallbackCount() {
            return fallbackCount;
        }

        /**
         * Read + write + unclassified: every statement observed on this endpoint.
         *
         * @return the total statement count
         */
        public long getTotalCount() {
            return readCount + writeCount + unknownCount;
        }
    }
}
