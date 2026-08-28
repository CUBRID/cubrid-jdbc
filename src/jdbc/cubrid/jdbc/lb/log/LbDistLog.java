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

import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.NodeRole;
import cubrid.jdbc.lb.config.ReadWeight;
import cubrid.jdbc.lb.config.ResolvedRoleTopology;
import cubrid.jdbc.lb.state.MetricsRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodic distribution record: how connections and queries actually spread across the read
 * endpoints, against the {@code readWeight} that was asked for.
 *
 * <p>Three lines, because they answer three questions that come apart in practice:
 *
 * <ol>
 *   <li><b>{@code [conn]}</b> — live connections per endpoint, each next to its target share. This
 *       is the distribution {@code readWeight} actually governs.
 *   <li><b>{@code [query]}</b> — statements executed per endpoint in the window. Connections can be
 *       distributed exactly as configured while the queries are not, because the ratio is {@code
 *       connections x per-connection query rate} and the second factor belongs to the application.
 *       Seeing only {@code [conn]} sends an investigation at the weight configuration when the
 *       cause is an uneven workload.
 *   <li><b>{@code [role]}</b> — the same window folded by role, written <em>only</em> when some
 *       role has more than one node. With one node per role it would repeat {@code [query]}
 *       verbatim.
 * </ol>
 *
 * <p><b>No thread.</b> Emission rides on the routing path: every statement checks the elapsed time
 * and one CAS elects a single emitter per window, the same election {@code RecoveryBackoff} uses
 * for probe rights. A daemon thread would have to outlive the application that started it, and the
 * CSV exporter already showed the cost - a {@code Runnable} pinning a web application's class
 * loader across a redeploy. The trade is that a window with no traffic writes no line, which
 * matters only for the last window before traffic stops; {@link #drainFinal()} covers that.
 *
 * <p><b>Windows are differences, not totals.</b> {@link MetricsRegistry} counters are cumulative
 * since JVM start, and a cumulative ratio grows less sensitive: hours in, a fresh imbalance barely
 * moves it. So the previous snapshot is kept here and subtracted. {@code RuntimeMetrics.reset()} is
 * not used, because it would corrupt the totals the Prometheus and CSV exporters publish.
 */
public final class LbDistLog {
    private static final Logger LOGGER = Logger.getLogger(LbDistLog.class.getName());

    private static final int READ = 0;
    private static final int WRITE = 1;
    private static final int FALLBACK = 2;

    private static final AtomicLong lastEmitMs = new AtomicLong();

    private static volatile boolean armed;
    private static volatile long intervalMs;
    private static volatile LoadBalanceSettings target;
    private static volatile boolean hookInstalled;

    // Guarded by the class monitor: only the CAS winner reaches emit(), but drainFinal() can run
    // concurrently on the shutdown thread.
    private static Map<String, long[]> previous = Collections.emptyMap();

    private LbDistLog() {}

    /**
     * Arms the record for {@code config} if it asked for one and none is armed yet.
     *
     * <p>First configuration wins, as with the file handler: the counters this reads from are
     * JVM-wide, so a second interval could not give a second cluster its own window anyway.
     *
     * @param config the settings whose {@code lbLogDistIntervalSec} may arm this; ignored when null
     */
    public static synchronized void configure(final LoadBalanceSettings config) {
        if (config == null || armed) {
            return;
        }
        int sec = config.getLbLogConfig().getDistIntervalSec();
        if (sec <= 0) {
            return;
        }

        intervalMs = sec * 1000L;
        target = config;
        lastEmitMs.set(System.currentTimeMillis());
        previous = MetricsRegistry.aggregateByEndpoint();
        armed = true;

        installShutdownHook();
        LbLog.info(
                LOGGER,
                null,
                "LB DIST: distribution records every "
                        + sec
                        + "s (runtime counters enabled; no"
                        + " metrics exporter started)");
    }

    /**
     * Writes the records if the window has closed. Called from the routing path, so this runs on an
     * application thread — the elapsed-time check is the whole cost when the window is still open.
     */
    public static void onStatementRouted() {
        if (!armed) {
            return;
        }

        final long now = System.currentTimeMillis();
        final long prev = lastEmitMs.get();
        if (now - prev < intervalMs) {
            return;
        }
        // Exactly one thread per window gets past this, so concurrent statements cannot each emit a
        // set of records for the same window.
        if (!lastEmitMs.compareAndSet(prev, now)) {
            return;
        }

        emit((now - prev) / 1000L, false);
    }

    /**
     * Writes the window that was still open at shutdown. Without this the interval before traffic
     * stopped — often the one being investigated — would never be written, because the emission
     * needs a statement to ride on and there are no more statements.
     */
    public static void drainFinal() {
        if (!armed) {
            return;
        }
        long elapsed = (System.currentTimeMillis() - lastEmitMs.get()) / 1000L;
        emit(elapsed, true);
    }

    private static synchronized void emit(final long windowSec, final boolean isFinal) {
        // The final emission runs from a shutdown hook, where LogManager.reset() may already have
        // cleared the logger's level and removed its handlers; the record would be dropped before
        // being rendered. write() below takes it straight to the file.
        if (!isFinal && !LOGGER.isLoggable(Level.INFO)) {
            return;
        }

        try {
            Map<String, long[]> now = MetricsRegistry.aggregateByEndpoint();
            Map<String, long[]> delta = diff(now, previous);
            previous = now;

            Map<String, Integer> bound = MetricsRegistry.aggregateBindingByEndpoint();
            List<String> endpoints = orderedEndpoints(delta, bound);
            if (endpoints.isEmpty()) {
                return;
            }

            // A shutdown right after a regular emission leaves an empty window, and all-zero
            // records read as a defect rather than as "nothing happened". Periodic emissions are
            // kept even when empty: their cadence is itself the signal that the session is alive.
            if (isFinal && !hasTraffic(delta)) {
                return;
            }

            String suffix = isFinal ? " (final)" : "";
            write(isFinal, connLine(endpoints, bound) + suffix);
            write(isFinal, queryLine(endpoints, delta, windowSec) + suffix);

            String roleLine = roleLine(endpoints, delta, windowSec);
            if (roleLine != null) {
                write(isFinal, roleLine + suffix);
            }
        } catch (Exception e) {
            LOGGER.warning("LB DIST: cannot render the distribution: " + e);
        }
    }

    /**
     * Periodic records go through the logger; the final one cannot rely on it still being there.
     */
    private static void write(final boolean isFinal, final String message) {
        if (isFinal) {
            LbLog.atShutdown(LOGGER, Level.INFO, null, message);
        } else {
            LbLog.info(LOGGER, null, message);
        }
    }

    /**
     * Live connections per endpoint next to the share {@code readWeight} asks for. The target of a
     * role is split across the nodes in that role, so a two-slave topology at {@code slave:2} of a
     * total 4 targets 25% per slave, not 50%.
     */
    private static String connLine(final List<String> endpoints, final Map<String, Integer> bound) {
        int total = 0;
        for (Integer c : bound.values()) {
            total += c.intValue();
        }

        StringBuilder b = new StringBuilder(220);
        b.append("LB DIST [conn] live=").append(total);
        ReadWeight weights = target == null ? null : target.getReadWeight();
        if (weights != null) {
            b.append(" readWeight ");
            NodeRole[] roles = NodeRole.values();
            for (int i = 0; i < roles.length; i++) {
                b.append(roles[i].label())
                        .append(':')
                        .append(weights.weightOf(roles[i]))
                        .append(' ');
            }
        }

        for (int i = 0; i < endpoints.size(); i++) {
            String id = endpoints.get(i);
            Integer c = bound.get(id);
            int n = c == null ? 0 : c.intValue();
            String role = MetricsRegistry.roleOf(id);
            b.append("| ")
                    .append(id)
                    .append(' ')
                    .append(role)
                    .append(' ')
                    .append(n)
                    .append("conn ")
                    .append(pct(n, total));
            String goal = targetPct(role);
            if (goal != null) {
                b.append("(target ").append(goal).append(')');
            }
            b.append(' ');
        }
        b.append("| roOnRw=").append(MetricsRegistry.countRoOnRwSessions());
        return b.toString();
    }

    private static String queryLine(
            final List<String> endpoints, final Map<String, long[]> delta, final long windowSec) {
        long totalRead = 0L;
        long totalAll = 0L;
        long totalFallback = 0L;
        for (int i = 0; i < endpoints.size(); i++) {
            long[] v = delta.get(endpoints.get(i));
            if (v == null) {
                continue;
            }
            totalRead += v[READ];
            totalAll += v[READ] + v[WRITE];
            totalFallback += v[FALLBACK];
        }

        StringBuilder b = new StringBuilder(220);
        b.append("LB DIST [query] window=")
                .append(windowSec)
                .append("s total=")
                .append(totalAll)
                .append(' ');
        for (int i = 0; i < endpoints.size(); i++) {
            String id = endpoints.get(i);
            long[] v = delta.get(id);
            long r = v == null ? 0L : v[READ];
            long w = v == null ? 0L : v[WRITE];
            b.append("| ")
                    .append(id)
                    .append(' ')
                    .append(MetricsRegistry.roleOf(id))
                    .append(" r=")
                    .append(r)
                    .append(" w=")
                    .append(w)
                    .append(' ')
                    .append(pct(r, totalRead))
                    .append("r ");
        }
        b.append("| fallback=").append(totalFallback);
        return b.toString();
    }

    /**
     * The window folded by role, or {@code null} when every role has at most one node — in that
     * shape the fold is {@code [query]} again and adds nothing but a line to read past.
     */
    private static String roleLine(
            final List<String> endpoints, final Map<String, long[]> delta, final long windowSec) {
        Map<String, long[]> byRole = new LinkedHashMap<String, long[]>();
        Map<String, Integer> nodesPerRole = new LinkedHashMap<String, Integer>();

        for (int i = 0; i < endpoints.size(); i++) {
            String id = endpoints.get(i);
            String role = MetricsRegistry.roleOf(id);
            long[] acc = byRole.get(role);
            if (acc == null) {
                acc = new long[3];
                byRole.put(role, acc);
                nodesPerRole.put(role, Integer.valueOf(0));
            }
            nodesPerRole.put(role, Integer.valueOf(nodesPerRole.get(role).intValue() + 1));

            long[] v = delta.get(id);
            if (v != null) {
                acc[READ] += v[READ];
                acc[WRITE] += v[WRITE];
                acc[FALLBACK] += v[FALLBACK];
            }
        }

        boolean multiNodeRole = false;
        for (Integer n : nodesPerRole.values()) {
            if (n.intValue() > 1) {
                multiNodeRole = true;
                break;
            }
        }
        if (!multiNodeRole) {
            return null;
        }

        StringBuilder b = new StringBuilder(180);
        b.append("LB DIST [role]  window=").append(windowSec).append("s ");
        for (Map.Entry<String, long[]> e : byRole.entrySet()) {
            long[] v = e.getValue();
            b.append("| ")
                    .append(e.getKey())
                    .append('(')
                    .append(nodesPerRole.get(e.getKey()))
                    .append(" node) ")
                    .append("r=")
                    .append(v[READ])
                    .append(" w=")
                    .append(v[WRITE])
                    .append(" fb=")
                    .append(v[FALLBACK])
                    .append(' ');
        }
        return b.toString().trim();
    }

    /**
     * Per-endpoint target share for {@code role}: the role's weight over the total, divided by how
     * many nodes carry that role. {@code null} when the topology cannot be read or the role has no
     * nodes.
     */
    private static String targetPct(final String role) {
        LoadBalanceSettings cfg = target;
        if (cfg == null) {
            return null;
        }
        ReadWeight weights = cfg.getReadWeight();
        ResolvedRoleTopology topo = cfg.getResolvedTopology();
        if (weights == null || topo == null || weights.total() <= 0) {
            return null;
        }

        NodeRole parsed = roleOf(role);
        if (parsed == null) {
            return null;
        }

        int nodes = nodeCount(topo, parsed);
        if (nodes <= 0) {
            return null;
        }

        double share = 100.0d * weights.weightOf(parsed) / weights.total() / nodes;
        return format1(share) + "%";
    }

    private static NodeRole roleOf(final String label) {
        NodeRole[] roles = NodeRole.values();
        for (int i = 0; i < roles.length; i++) {
            if (roles[i].label().equalsIgnoreCase(label)) {
                return roles[i];
            }
        }
        return null;
    }

    private static int nodeCount(final ResolvedRoleTopology topo, final NodeRole role) {
        if (NodeRole.MASTER == role) {
            return topo.getMaster() == null ? 0 : 1;
        }
        if (NodeRole.SLAVE == role) {
            return topo.getSlaves().size();
        }
        if (NodeRole.REPLICA == role) {
            return topo.getReplicas().size();
        }
        return 0;
    }

    /**
     * Endpoints that either served something in the window or currently hold a connection, in a
     * stable order so consecutive records line up when read side by side.
     */
    private static List<String> orderedEndpoints(
            final Map<String, long[]> delta, final Map<String, Integer> bound) {
        Set<String> ids = new LinkedHashSet<String>();
        for (Map.Entry<String, long[]> e : delta.entrySet()) {
            long[] v = e.getValue();
            if (v[READ] != 0L || v[WRITE] != 0L || v[FALLBACK] != 0L) {
                ids.add(e.getKey());
            }
        }
        ids.addAll(bound.keySet());

        List<String> out = new ArrayList<String>(ids);
        Collections.sort(out);
        return out;
    }

    private static boolean hasTraffic(final Map<String, long[]> delta) {
        for (long[] v : delta.values()) {
            if (v[READ] != 0L || v[WRITE] != 0L || v[FALLBACK] != 0L) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, long[]> diff(
            final Map<String, long[]> now, final Map<String, long[]> before) {
        Map<String, long[]> out = new LinkedHashMap<String, long[]>();
        for (Map.Entry<String, long[]> e : now.entrySet()) {
            long[] n = e.getValue();
            long[] p = before.get(e.getKey());
            if (p == null) {
                out.put(e.getKey(), new long[] {n[READ], n[WRITE], n[FALLBACK]});
                continue;
            }
            out.put(
                    e.getKey(),
                    new long[] {n[READ] - p[READ], n[WRITE] - p[WRITE], n[FALLBACK] - p[FALLBACK]});
        }
        return out;
    }

    private static String pct(final long part, final long total) {
        if (total <= 0L) {
            return "0.0%";
        }
        return format1(100.0d * part / total) + "%";
    }

    /** One decimal place without {@code String.format}, which would honour the default locale. */
    private static String format1(final double value) {
        long scaled = Math.round(value * 10.0d);
        return (scaled / 10L) + "." + Math.abs(scaled % 10L);
    }

    private static void installShutdownHook() {
        if (hookInstalled) {
            return;
        }
        try {
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread("lb-dist-flush") {
                                @Override
                                public void run() {
                                    drainFinal();
                                }
                            });
            hookInstalled = true;
        } catch (Exception e) {
            // A container may forbid shutdown hooks; only the last window is then lost.
            LOGGER.warning("LB DIST: cannot register the shutdown flush hook: " + e);
        }
    }

    /** Test hook: disarms and forgets the window state. */
    static synchronized void resetForTests() {
        armed = false;
        intervalMs = 0L;
        target = null;
        previous = Collections.emptyMap();
        lastEmitMs.set(0L);
    }
}
