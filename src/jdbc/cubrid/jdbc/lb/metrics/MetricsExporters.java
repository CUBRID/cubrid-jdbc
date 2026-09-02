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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import cubrid.jdbc.lb.config.MetricsConfig;
import cubrid.jdbc.lb.failover.UnreachableEndpoints;
import cubrid.jdbc.lb.log.LbFileRotation;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Process-level exporters for LB runtime metrics. Both are JVM singletons started once via the
 * idempotent {@link #configure(MetricsConfig)} — the first loadbalance connection whose URL enables
 * them wins; later connections carrying the same options are no-ops, and a conflicting value is
 * ignored with a WARN (a well-formed pool uses one URL for all its connections).
 *
 * <ul>
 *   <li><b>Prometheus</b> is a passive {@code /metrics} HTTP endpoint (pull): it does no work until
 *       a Prometheus server scrapes it, then reads {@link MetricsRegistry} and renders the text
 *       exposition format. The scrape period is the Prometheus server's {@code scrape_interval}.
 *   <li><b>CSV</b> is an active daemon thread that appends one row per endpoint every {@code
 *       metricsIntervalSec} to a local file. Reads/writes happen off the application's query
 *       threads.
 * </ul>
 */
public final class MetricsExporters {
    private static final Logger LOGGER = Logger.getLogger(MetricsExporters.class.getName());

    private static final Object LOCK = new Object();
    private static boolean prometheusStarted;
    private static int prometheusPort;
    private static HttpServer prometheusServer;
    private static boolean csvStarted;
    private static String csvDir;
    private static Thread csvThread;

    private MetricsExporters() {}

    /**
     * Starts the exporters requested by {@code config}, once per JVM.
     *
     * @param config the metrics config; ignored when {@code null} or disabled
     */
    public static void configure(final MetricsConfig config) {
        if (config == null || !config.isEnabled()) {
            return;
        }
        synchronized (LOCK) {
            if (config.exportsPrometheus()) {
                startPrometheus(config.getPrometheusPort());
            }
            if (config.exportsCsv()) {
                startCsv(
                        config.getCsvPath(),
                        config.getIntervalSec(),
                        config.getCsvMaxSizeMb(),
                        config.getCsvMaxFiles());
            }
        }
    }

    /**
     * Stops both exporters and forgets them, so a following {@link #configure} can start fresh.
     *
     * <p>For the redeploy case. Both exporters are daemons, so a JVM exit needs nothing from this,
     * but undeploying a web application is not a JVM exit: the CSV thread keeps running and its
     * {@code Runnable} holds the webapp's class loader - the classic container-side leak. A
     * container hook, or the application's own shutdown path, can call this to release both.
     */
    public static void stop() {
        synchronized (LOCK) {
            if (prometheusServer != null) {
                prometheusServer.stop(0);
                prometheusServer = null;
            }
            prometheusStarted = false;

            if (csvThread != null) {
                csvThread.interrupt();
                csvThread = null;
            }
            csvStarted = false;
            csvDir = null;
        }
    }

    /**
     * Called by the CSV writer when it stops itself after an idle stretch, so the next {@link
     * #configure} starts a new one instead of assuming one is already running.
     */
    private static void csvStopped(final Thread self) {
        synchronized (LOCK) {
            if (csvThread == self) {
                csvThread = null;
                csvStarted = false;
                csvDir = null;
            }
        }
    }

    private static void startPrometheus(final int port) {
        if (prometheusStarted) {
            if (prometheusPort != port) {
                LOGGER.warning(
                        "Prometheus exporter already started on port "
                                + prometheusPort
                                + "; ignoring conflicting metricsPrometheusPort="
                                + port);
            }
            return;
        }
        try {
            final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/metrics", new MetricsHandler());
            server.setExecutor(null); // default single-threaded executor: scrapes are infrequent
            startAsDaemon(server);
            prometheusServer = server;
            prometheusStarted = true;
            prometheusPort = port;
            LOGGER.info("LB metrics: Prometheus /metrics endpoint listening on port " + port);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "failed to start Prometheus exporter on port " + port, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "interrupted starting the Prometheus exporter", e);
        }
    }

    /**
     * Starts {@code server} from a daemon thread, so the dispatcher thread it creates is a daemon
     * too.
     *
     * <p>{@code HttpServer.start()} creates its dispatcher with a plain {@code new Thread(...)},
     * and a thread inherits the daemon flag of the thread that created it. Started from the
     * caller's (normally non-daemon) thread, the dispatcher is non-daemon and <b>the JVM can no
     * longer terminate</b>: a program that turned on {@code metricsExport=prometheus} would hang
     * after {@code main} returned. Handing {@code start()} to a short-lived daemon thread makes the
     * dispatcher inherit {@code daemon=true}, and the JVM exits normally.
     */
    private static void startAsDaemon(final HttpServer server) throws InterruptedException {
        Thread starter =
                new Thread(
                        new Runnable() {
                            public void run() {
                                server.start();
                            }
                        },
                        "lb-metrics-prometheus-start");
        starter.setDaemon(true);
        starter.start();
        starter.join();
    }

    private static void startCsv(
            final String dir, final int intervalSec, final int maxSizeMb, final int maxFiles) {
        if (csvStarted) {
            if (dir != null && !dir.equals(csvDir)) {
                LOGGER.warning(
                        "CSV exporter already writing to '"
                                + csvDir
                                + "'; ignoring conflicting metricsCsvPath="
                                + dir);
            }
            return;
        }
        Thread t =
                new Thread(new CsvWriter(dir, intervalSec, maxSizeMb, maxFiles), "lb-metrics-csv");
        t.setDaemon(true);
        t.start();
        csvThread = t;
        csvStarted = true;
        csvDir = dir;
        LOGGER.info(
                "LB metrics: CSV exporter writing to '"
                        + dir
                        + "' every "
                        + intervalSec
                        + "s"
                        + (maxSizeMb > 0
                                ? " (rotate at "
                                        + maxSizeMb
                                        + "MB, keeping "
                                        + maxFiles
                                        + " generations)"
                                : " (rotation disabled)"));
    }

    private static String localHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    // ------------------------------------------------------------------
    // Prometheus text exposition (version 0.0.4)
    // ------------------------------------------------------------------

    static String renderPrometheus() {
        Map<String, long[]> byEndpoint = MetricsRegistry.aggregateByEndpoint();
        StringBuilder sb = new StringBuilder(512);
        sb.append("# HELP lb_endpoint_exec_total Statements routed per endpoint since JVM start\n");
        sb.append("# TYPE lb_endpoint_exec_total counter\n");
        for (Map.Entry<String, long[]> e : byEndpoint.entrySet()) {
            String id = e.getKey();
            String role = MetricsRegistry.roleOf(id);
            long[] v = e.getValue();
            sb.append(execLine(id, role, "read", v[0]));
            sb.append(execLine(id, role, "write", v[1]));
        }
        sb.append(
                "# HELP lb_endpoint_fallback_total Routes that did not land on their intended target\n");
        sb.append("# TYPE lb_endpoint_fallback_total counter\n");
        for (Map.Entry<String, long[]> e : byEndpoint.entrySet()) {
            String id = e.getKey();
            String role = MetricsRegistry.roleOf(id);
            long[] v = e.getValue();
            sb.append("lb_endpoint_fallback_total{endpoint=\"")
                    .append(escape(id))
                    .append("\",role=\"")
                    .append(escape(role))
                    .append("\"} ")
                    .append(v[2])
                    .append('\n');
        }
        // Binding gauge: live connections bound to each read role. This is what readWeight governs,
        // as opposed to executions above which are skewed by per-node read throughput.
        Map<String, Integer> bindingByRole = bindingByRole();
        sb.append(
                "# HELP lb_bound_connections Live connections currently bound to each read role\n");
        sb.append("# TYPE lb_bound_connections gauge\n");
        for (Map.Entry<String, Integer> e : bindingByRole.entrySet()) {
            sb.append("lb_bound_connections{role=\"")
                    .append(escape(e.getKey()))
                    .append("\"} ")
                    .append(e.getValue().intValue())
                    .append('\n');
        }
        // Reachability gauge: is each endpoint usable, or excluded by the unreachable list? The
        // first thing to look at during an incident - a weight that "stopped working" is usually an
        // endpoint that is down, not a routing bug.
        sb.append(
                "# HELP lb_endpoint_up 1 when the endpoint is usable, 0 while it is excluded as unreachable\n");
        sb.append("# TYPE lb_endpoint_up gauge\n");
        for (String id : MetricsRegistry.knownEndpointIds()) {
            sb.append("lb_endpoint_up{endpoint=\"")
                    .append(escape(id))
                    .append("\",role=\"")
                    .append(escape(MetricsRegistry.roleOf(id)))
                    .append("\"} ")
                    .append(UnreachableEndpoints.isEndpointIdUnreachable(id) ? 0 : 1)
                    .append('\n');
        }

        // Read-leg movements. failover_total rising = something went down; failback_total catching
        // up = it recovered and the weighted distribution self-healed.
        appendTransitions(
                sb,
                "lb_failover_total",
                "Read/write legs displaced from their intended endpoint",
                MetricsRegistry.failovers());
        appendTransitions(
                sb,
                "lb_failback_total",
                "Displaced read legs that climbed back towards their weighted home endpoint",
                MetricsRegistry.failbacks());

        // Sessions reading over the RW connection (roOnRw) — by design for master-role weight, or
        // because every read endpoint failed. Either way these reads compete with write traffic.
        sb.append(
                "# HELP lb_ro_on_rw_sessions Live sessions whose reads run on the RW physical connection\n");
        sb.append("# TYPE lb_ro_on_rw_sessions gauge\n");
        sb.append("lb_ro_on_rw_sessions ")
                .append(MetricsRegistry.countRoOnRwSessions())
                .append('\n');

        // Execution-latency histograms (real per-node response time; the p50/p95/p99 signal).
        Map<String, MetricsRegistry.EndpointLatency> lat =
                MetricsRegistry.aggregateLatencyByEndpoint();
        appendLatencyHistogram(sb, "lb_read_latency_ms", "Read", lat, true);
        appendLatencyHistogram(sb, "lb_write_latency_ms", "Write", lat, false);
        return sb.toString();
    }

    /**
     * Emits one counter family for read-leg movements, labelled {@code role}, {@code from}, {@code
     * to}.
     */
    private static void appendTransitions(
            final StringBuilder sb,
            final String metric,
            final String help,
            final List<MetricsRegistry.Transition> transitions) {
        sb.append("# HELP ").append(metric).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(metric).append(" counter\n");
        for (int i = 0; i < transitions.size(); i++) {
            MetricsRegistry.Transition t = transitions.get(i);
            sb.append(metric)
                    .append("{role=\"")
                    .append(escape(t.getRole()))
                    .append("\",from=\"")
                    .append(escape(t.getFrom()))
                    .append("\",to=\"")
                    .append(escape(t.getTo()))
                    .append("\"} ")
                    .append(t.getCount())
                    .append('\n');
        }
    }

    /**
     * Emits one Prometheus histogram (cumulative {@code le} buckets + _sum + _count) per endpoint.
     */
    private static void appendLatencyHistogram(
            final StringBuilder sb,
            final String metric,
            final String opLabel,
            final Map<String, MetricsRegistry.EndpointLatency> lat,
            final boolean read) {
        sb.append("# HELP ")
                .append(metric)
                .append(' ')
                .append(opLabel)
                .append(" execution latency per endpoint in milliseconds\n");
        sb.append("# TYPE ").append(metric).append(" histogram\n");
        double[] bounds = RuntimeMetrics.LATENCY_BUCKETS_MS;
        for (Map.Entry<String, MetricsRegistry.EndpointLatency> e : lat.entrySet()) {
            String id = e.getKey();
            String labels =
                    "endpoint=\""
                            + escape(id)
                            + "\",role=\""
                            + escape(MetricsRegistry.roleOf(id))
                            + "\"";
            MetricsRegistry.EndpointLatency v = e.getValue();
            long[] buckets = read ? v.getReadBuckets() : v.getWriteBuckets();
            long count = read ? v.getReadCount() : v.getWriteCount();
            double sumMs = read ? v.getReadSumMs() : v.getWriteSumMs();
            long cumulative = 0L;
            for (int i = 0; i < bounds.length; i++) {
                cumulative += buckets[i];
                sb.append(metric)
                        .append("_bucket{")
                        .append(labels)
                        .append(",le=\"")
                        .append(leStr(bounds[i]))
                        .append("\"} ")
                        .append(cumulative)
                        .append('\n');
            }
            cumulative += buckets[bounds.length]; // +Inf overflow slot
            sb.append(metric)
                    .append("_bucket{")
                    .append(labels)
                    .append(",le=\"+Inf\"} ")
                    .append(cumulative)
                    .append('\n');
            sb.append(metric)
                    .append("_sum{")
                    .append(labels)
                    .append("} ")
                    .append(sumMs)
                    .append('\n');
            sb.append(metric)
                    .append("_count{")
                    .append(labels)
                    .append("} ")
                    .append(count)
                    .append('\n');
        }
    }

    /**
     * Formats a bucket bound as a Prometheus {@code le} value (whole numbers without a decimal).
     */
    private static String leStr(final double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    /** Folds the per-endpoint binding gauge into role buckets. */
    private static Map<String, Integer> bindingByRole() {
        Map<String, Integer> byRole = new java.util.LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> e :
                MetricsRegistry.aggregateBindingByEndpoint().entrySet()) {
            String role = MetricsRegistry.roleOf(e.getKey());
            Integer c = byRole.get(role);
            byRole.put(
                    role,
                    Integer.valueOf((c == null ? 0 : c.intValue()) + e.getValue().intValue()));
        }
        return byRole;
    }

    private static String execLine(
            final String id, final String role, final String op, final long value) {
        return "lb_endpoint_exec_total{endpoint=\""
                + escape(id)
                + "\",role=\""
                + escape(role)
                + "\",op=\""
                + op
                + "\"} "
                + value
                + "\n";
    }

    private static String escape(final String label) {
        if (label == null) {
            return "";
        }
        // Prometheus label values escape backslash, double-quote and newline.
        return label.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static final class MetricsHandler implements HttpHandler {
        public void handle(final HttpExchange exchange) throws IOException {
            byte[] body = renderPrometheus().getBytes("UTF-8");
            exchange.getResponseHeaders()
                    .set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            OutputStream os = exchange.getResponseBody();
            try {
                os.write(body);
            } finally {
                os.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // CSV daemon
    // ------------------------------------------------------------------

    /**
     * Consecutive empty periods after which the CSV writer stops itself. Long enough that a pool
     * briefly at zero idle connections is not mistaken for a shut-down application.
     */
    private static final int IDLE_PERIODS_BEFORE_STOP = 5;

    private static final class CsvWriter implements Runnable {
        private static final String HEADER =
                "ts,host,endpoint,role,read,write,fallback,bound,up,failover_in,failback_in,"
                        + "ro_on_rw_sessions,"
                        + "read_p50_ms,read_p95_ms,read_p99_ms,read_avg_ms,"
                        + "write_p50_ms,write_p95_ms,write_p99_ms,write_avg_ms\n";
        private final File file;
        private final long periodMs;
        private final String host;
        private final long maxBytes;
        private final int maxFiles;
        private final SimpleDateFormat ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        CsvWriter(
                final String dir, final int intervalSec, final int maxSizeMb, final int maxFiles) {
            this.host = localHost();
            this.periodMs = Math.max(1, intervalSec) * 1000L;
            File d = new File(dir == null ? "." : dir);
            this.file = new File(d, "lb-metrics-" + this.host + ".csv");
            this.maxBytes = maxSizeMb <= 0 ? 0L : maxSizeMb * 1024L * 1024L;
            this.maxFiles = Math.max(0, maxFiles);
        }

        public void run() {
            int idlePeriods = 0;
            for (; ; ) {
                try {
                    Thread.sleep(periodMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    csvStopped(Thread.currentThread());
                    return;
                }

                // Stop once nothing is left to export. Without this the thread runs for the life of
                // the JVM after every LB connection has closed, and on a web-application redeploy
                // it
                // outlives the application while its Runnable pins the old class loader.
                // Registering
                // a connection again restarts it through configure().
                if (MetricsRegistry.liveCount() == 0) {
                    if (++idlePeriods >= IDLE_PERIODS_BEFORE_STOP) {
                        LOGGER.info(
                                "LB metrics: no live LB connections for "
                                        + (IDLE_PERIODS_BEFORE_STOP * periodMs / 1000L)
                                        + "s -> stopping the CSV exporter; it restarts with the next"
                                        + " connection");
                        csvStopped(Thread.currentThread());
                        return;
                    }
                } else {
                    idlePeriods = 0;
                }

                try {
                    writeOnce();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "LB metrics CSV write failed", e);
                }
            }
        }

        private void writeOnce() throws IOException {
            // Snapshot first (the registry copies under its own lock), then do file I/O unlocked.
            Map<String, long[]> byEndpoint = MetricsRegistry.aggregateByEndpoint();
            if (byEndpoint.isEmpty()) {
                return;
            }
            Map<String, Integer> boundByEndpoint = MetricsRegistry.aggregateBindingByEndpoint();
            Map<String, MetricsRegistry.EndpointLatency> latByEndpoint =
                    MetricsRegistry.aggregateLatencyByEndpoint();
            // Movements are counted per (from,to); the CSV is per-endpoint, so fold them into
            // "arrivals at this endpoint" — enough to see when and where a leg moved.
            Map<String, Long> failoverIn = arrivalsByEndpoint(MetricsRegistry.failovers());
            Map<String, Long> failbackIn = arrivalsByEndpoint(MetricsRegistry.failbacks());
            // Session-wide gauge, repeated on every row so a single row is self-contained.
            String roOnRw = Integer.toString(MetricsRegistry.countRoOnRwSessions());
            String now = ts.format(new Date());
            File parent = file.getParentFile();
            // bound disk use; a rotated-away file makes the next write re-emit the header
            LbFileRotation.rotateIfOversized(file, maxBytes, maxFiles);
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            boolean fresh = !file.exists() || file.length() == 0;
            OutputStream out = new FileOutputStream(file, true);
            Writer w = new OutputStreamWriter(out, "UTF-8");
            try {
                if (fresh) {
                    w.write(HEADER);
                }
                for (Map.Entry<String, long[]> e : byEndpoint.entrySet()) {
                    String id = e.getKey();
                    long[] v = e.getValue();
                    Integer bound = boundByEndpoint.get(id);
                    w.write(now);
                    w.write(',');
                    w.write(host);
                    w.write(',');
                    w.write(id);
                    w.write(',');
                    w.write(MetricsRegistry.roleOf(id));
                    w.write(',');
                    w.write(Long.toString(v[0]));
                    w.write(',');
                    w.write(Long.toString(v[1]));
                    w.write(',');
                    w.write(Long.toString(v[2]));
                    w.write(',');
                    w.write(Integer.toString(bound == null ? 0 : bound.intValue()));
                    w.write(',');
                    w.write(UnreachableEndpoints.isEndpointIdUnreachable(id) ? "0" : "1");
                    w.write(',');
                    w.write(Long.toString(count(failoverIn, id)));
                    w.write(',');
                    w.write(Long.toString(count(failbackIn, id)));
                    w.write(',');
                    w.write(roOnRw);
                    MetricsRegistry.EndpointLatency lat = latByEndpoint.get(id);
                    appendLatencyCols(w, lat, true); // read p50,p95,p99,avg
                    appendLatencyCols(w, lat, false); // write p50,p95,p99,avg
                    w.write('\n');
                }
                w.flush();
            } finally {
                w.close();
            }
        }

        /** Movements folded by destination endpoint: how many legs landed on each endpoint. */
        private static Map<String, Long> arrivalsByEndpoint(
                final List<MetricsRegistry.Transition> transitions) {
            Map<String, Long> out = new java.util.LinkedHashMap<String, Long>();
            for (int i = 0; i < transitions.size(); i++) {
                MetricsRegistry.Transition t = transitions.get(i);
                Long prev = out.get(t.getTo());
                out.put(
                        t.getTo(),
                        Long.valueOf((prev == null ? 0L : prev.longValue()) + t.getCount()));
            }
            return out;
        }

        private static long count(final Map<String, Long> counts, final String endpointId) {
            Long v = counts.get(endpointId);
            return v == null ? 0L : v.longValue();
        }

        /** Appends {@code ,p50,p95,p99,avg} (ms) for the read or write latency of one endpoint. */
        private static void appendLatencyCols(
                final Writer w, final MetricsRegistry.EndpointLatency lat, final boolean read)
                throws IOException {
            long[] buckets = null;
            long count = 0L;
            double sumMs = 0.0d;
            if (lat != null) {
                buckets = read ? lat.getReadBuckets() : lat.getWriteBuckets();
                count = read ? lat.getReadCount() : lat.getWriteCount();
                sumMs = read ? lat.getReadSumMs() : lat.getWriteSumMs();
            }
            w.write(',');
            w.write(csvNum(histoQuantile(buckets, count, 50.0d)));
            w.write(',');
            w.write(csvNum(histoQuantile(buckets, count, 95.0d)));
            w.write(',');
            w.write(csvNum(histoQuantile(buckets, count, 99.0d)));
            w.write(',');
            w.write(csvNum(count == 0L ? 0.0d : sumMs / count));
        }

        /**
         * Percentile (ms) interpolated from a histogram, the same way Prometheus {@code
         * histogram_quantile} does it.
         */
        private static double histoQuantile(
                final long[] buckets, final long count, final double p) {
            if (buckets == null || count == 0L) {
                return 0.0d;
            }
            double[] bounds = RuntimeMetrics.LATENCY_BUCKETS_MS;
            double rank = p / 100.0d * count;
            long cum = 0L;
            double lower = 0.0d;
            for (int i = 0; i < bounds.length; i++) {
                long inBucket = buckets[i];
                if (cum + inBucket >= rank) {
                    double frac = inBucket == 0L ? 0.0d : (rank - cum) / inBucket;
                    return lower + (bounds[i] - lower) * frac;
                }
                cum += inBucket;
                lower = bounds[i];
            }
            return lower; // in the +Inf overflow: report the top finite bound as a floor
        }

        /** 2-decimal, dot-separator formatting so the CSV parses regardless of the JVM locale. */
        private static String csvNum(final double v) {
            return String.format(java.util.Locale.US, "%.2f", v);
        }
    }
}
