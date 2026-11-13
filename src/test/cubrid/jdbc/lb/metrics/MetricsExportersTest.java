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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.config.ConfigTestAccess;
import cubrid.jdbc.lb.config.MetricsConfig;
import cubrid.jdbc.lb.sql.SqlClassification;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The operator-facing metric families: endpoint reachability, read-leg movements, and sessions
 * whose reads share the RW connection. These answer "is the LB healthy right now", so they are
 * rendered from JVM-wide state rather than per-connection counters and need their own coverage.
 */
public class MetricsExportersTest {

    private static final String MASTER = "10.0.0.1:30000";
    private static final String SLAVE = "10.0.0.2:33000";

    @Before
    public void setUp() {
        MetricsRegistry.clearForTests();
        UUnreachableHostList.getInstance().remove(SLAVE);
    }

    @After
    public void tearDown() {
        MetricsRegistry.clearForTests();
        UUnreachableHostList.getInstance().remove(SLAVE);
    }

    @Test
    public void endpointUpGaugeReportsOneUntilTheEndpointIsMarkedUnreachable() {
        register(SLAVE, "slave", false);

        assertTrue(
                MetricsExporters.renderPrometheus()
                        .contains("lb_endpoint_up{endpoint=\"" + SLAVE + "\",role=\"slave\"} 1"));

        UUnreachableHostList.getInstance().add(SLAVE);

        assertTrue(
                MetricsExporters.renderPrometheus()
                        .contains("lb_endpoint_up{endpoint=\"" + SLAVE + "\",role=\"slave\"} 0"));
    }

    @Test
    public void failoverAndFailbackCountersCarryFromAndToLabels() {
        register(SLAVE, "slave", false);
        MetricsRegistry.recordFailover("RO", SLAVE, MASTER);
        MetricsRegistry.recordFailover("RO", SLAVE, MASTER);
        MetricsRegistry.recordFailback("RO", MASTER, SLAVE);

        String text = MetricsExporters.renderPrometheus();

        assertTrue(
                text.contains(
                        "lb_failover_total{role=\"RO\",from=\""
                                + SLAVE
                                + "\",to=\""
                                + MASTER
                                + "\"} 2"));
        assertTrue(
                text.contains(
                        "lb_failback_total{role=\"RO\",from=\""
                                + MASTER
                                + "\",to=\""
                                + SLAVE
                                + "\"} 1"));
    }

    @Test
    public void roOnRwGaugeCountsOnlySessionsReadingOverTheRwConnection() {
        register(SLAVE, "slave", false);
        register(MASTER, "master", true);

        assertEquals(1, MetricsRegistry.countRoOnRwSessions());
        assertTrue(MetricsExporters.renderPrometheus().contains("lb_ro_on_rw_sessions 1"));
    }

    /** Unauthenticated, and it names every broker: by default nobody off this host may read it. */
    @Test
    public void prometheusListensOnLoopbackByDefault() throws Exception {
        InetAddress external = nonLoopbackAddress();
        Assume.assumeNotNull(external); // a host with no other interface proves nothing
        int port = freePort();
        MetricsExporters.configure(prometheus(port, 60));
        try {
            assertTrue(accepts(InetAddress.getLoopbackAddress(), port));
            assertFalse("reachable from " + external.getHostAddress(), accepts(external, port));
        } finally {
            MetricsExporters.stop();
        }
    }

    /** A Prometheus server on another host is still one option away. */
    @Test
    public void prometheusListensOnEveryInterfaceWhenAskedTo() throws Exception {
        InetAddress external = nonLoopbackAddress();
        Assume.assumeNotNull(external);
        int port = freePort();
        MetricsExporters.configure(prometheus(port, 60, "0.0.0.0"));
        try {
            assertTrue(accepts(external, port));
        } finally {
            MetricsExporters.stop();
        }
    }

    /**
     * Nothing stopped the server, so an undeployed application kept the port - the redeployed one
     * then ran without metrics - and its class loader. It now stops like the CSV writer: after a
     * stretch with no live connection, and the next connection starts it again.
     */
    @Test
    public void prometheusReleasesThePortOnceNoConnectionIsLive() throws Exception {
        int port = freePort();
        RuntimeMetrics metrics = new RuntimeMetrics();
        MetricsRegistry.register(
                metrics, new HashMap<String, String>(), new FixedProbe(SLAVE, false));
        MetricsExporters.configure(prometheus(port, 1));
        try {
            MetricsRegistry.unregister(metrics);

            long deadline = System.currentTimeMillis() + 15000L;
            while (accepts(InetAddress.getLoopbackAddress(), port)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(250L);
            }
            assertFalse("the port is released", accepts(InetAddress.getLoopbackAddress(), port));

            MetricsRegistry.register(
                    metrics, new HashMap<String, String>(), new FixedProbe(SLAVE, false));
            MetricsExporters.configure(prometheus(port, 1));
            assertTrue(
                    "the next connection starts it again",
                    accepts(InetAddress.getLoopbackAddress(), port));
        } finally {
            MetricsExporters.stop();
        }
    }

    /**
     * A client that connects and never finishes its request holds one scrape thread, not the
     * endpoint; only a GET or HEAD of exactly /metrics is answered with the body.
     */
    @Test
    public void aStalledClientDoesNotBlockAScrapeAndOnlyGetMetricsIsServed() throws Exception {
        int port = freePort();
        MetricsExporters.configure(prometheus(port, 60));
        Socket stalled = new Socket(InetAddress.getLoopbackAddress(), port);
        try {
            stalled.getOutputStream().write("GET /metr".getBytes("US-ASCII"));
            assertEquals(200, status(port, "GET", "/metrics"));
            assertEquals(200, status(port, "HEAD", "/metrics"));
            assertEquals(405, status(port, "POST", "/metrics"));
            assertEquals(404, status(port, "GET", "/metricsX"));
        } finally {
            stalled.close();
            MetricsExporters.stop();
        }
    }

    private static int status(final int port, final String method, final String path)
            throws IOException {
        java.net.HttpURLConnection c =
                (java.net.HttpURLConnection)
                        new java.net.URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    @Test
    public void csvRowsGoToTheCsvLoggerOneFieldPerHeaderColumn() throws Exception {
        RuntimeMetrics metrics = new RuntimeMetrics();
        metrics.setEnabled(true);
        MetricsRegistry.register(
                metrics, Collections.singletonMap(SLAVE, "slave"), new FixedProbe(SLAVE, false));
        metrics.recordExec(SLAVE, SqlClassification.READ, false);

        final List<String> rows = Collections.synchronizedList(new ArrayList<String>());
        Handler capture =
                new Handler() {
                    @Override
                    public void publish(final LogRecord record) {
                        rows.add(record.getMessage());
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        Logger csv = Logger.getLogger("cubrid.jdbc.lb.metrics.csv");
        csv.addHandler(capture);
        csv.setUseParentHandlers(false);
        Map<String, String> options = new HashMap<String, String>();
        options.put(ConfigTestAccess.OPT_ENABLED, "true");
        options.put(ConfigTestAccess.OPT_EXPORT, ConfigTestAccess.EXPORT_CSV);
        options.put(ConfigTestAccess.OPT_INTERVAL_SEC, "1");
        MetricsExporters.configure(ConfigTestAccess.parseMetricsConfig(options));
        try {
            long deadline = System.currentTimeMillis() + 5000L;
            while (rows.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
        } finally {
            MetricsExporters.stop();
            csv.removeHandler(capture);
            csv.setUseParentHandlers(true);
        }

        assertFalse("no CSV row within a few intervals", rows.isEmpty());
        String row = rows.get(0);
        assertEquals(row, MetricsFormatter.HEADER.trim().split(",").length, row.split(",").length);
        assertTrue(row, row.contains("," + SLAVE + ",slave,1,0,"));
    }

    /** m27: every connection configures the exporter, so a conflicting port is warned once. */
    @Test
    public void aConflictingPrometheusPortIsWarnedAboutOnce() throws Exception {
        int port = freePort();
        int other = port + 1;
        final List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
        Handler capture =
                new Handler() {
                    @Override
                    public void publish(final LogRecord record) {
                        warnings.add(record.getMessage());
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        Logger exporters = Logger.getLogger(MetricsExporters.class.getName());
        exporters.addHandler(capture);
        exporters.setUseParentHandlers(false);
        MetricsExporters.configure(prometheus(port, 60));
        try {
            for (int i = 0; i < 3; i++) {
                MetricsExporters.configure(prometheus(other, 60));
            }
        } finally {
            MetricsExporters.stop();
            exporters.removeHandler(capture);
            exporters.setUseParentHandlers(true);
        }

        int conflicts = 0;
        for (String each : warnings) {
            if (each.contains("conflicting")) {
                conflicts++;
            }
        }
        assertEquals(warnings.toString(), 1, conflicts);
    }

    /** m29: a later connection's different bind or interval was ignored without a word. */
    @Test
    public void aConflictingBindOrIntervalIsWarnedAboutOnce() throws Exception {
        int port = freePort();
        List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
        Handler capture = capture(warnings);
        MetricsExporters.configure(prometheus(port, 60));
        MetricsExporters.configure(csv(600));
        try {
            for (int i = 0; i < 2; i++) {
                MetricsExporters.configure(prometheus(port, 60, "127.0.0.1")); // the same address
                MetricsExporters.configure(prometheus(port, 60, "0.0.0.0"));
                MetricsExporters.configure(prometheus(port, 45));
                MetricsExporters.configure(csv(300));
            }
        } finally {
            MetricsExporters.stop();
            release(capture);
        }

        assertEquals(warnings.toString(), 3, count(warnings, "conflicting"));
        assertEquals(warnings.toString(), 1, count(warnings, "0.0.0.0"));
        assertEquals(warnings.toString(), 1, count(warnings, "metricsIntervalSec=45"));
        assertEquals(warnings.toString(), 1, count(warnings, "metricsIntervalSec=300"));
    }

    /**
     * m29: one busy port silenced every later bind failure and held back every other address for a
     * minute. Each address is now warned about and retried on its own.
     */
    @Test
    public void aBusyPortHoldsBackOnlyItselfAndEachIsWarnedAboutOnce() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        ServerSocket busyA = new ServerSocket(0, 1, loopback);
        ServerSocket busyB = new ServerSocket(0, 1, loopback);
        String portA = String.valueOf(busyA.getLocalPort());
        String portB = String.valueOf(busyB.getLocalPort());
        int free = freePort();
        List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
        Handler capture = capture(warnings);
        try {
            for (int i = 0; i < 2; i++) {
                MetricsExporters.configure(prometheus(busyA.getLocalPort(), 60));
                MetricsExporters.configure(prometheus(busyB.getLocalPort(), 60));
            }
            MetricsExporters.configure(prometheus(free, 60));
            assertTrue("a free port is bound at once", accepts(loopback, free));
        } finally {
            MetricsExporters.stop();
            release(capture);
            busyA.close();
            busyB.close();
        }

        assertEquals(warnings.toString(), 1, count(warnings, "failed", portA));
        assertEquals(warnings.toString(), 1, count(warnings, "failed", portB));
        // Closed now, so a later test may be handed the same port numbers.
        Field failedAt = MetricsExporters.class.getDeclaredField("BIND_FAILED_AT_MS");
        failedAt.setAccessible(true);
        ((Map<?, ?>) failedAt.get(null)).clear();
    }

    private static Handler capture(final List<String> messages) {
        Handler handler =
                new Handler() {
                    @Override
                    public void publish(final LogRecord record) {
                        messages.add(record.getMessage());
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        Logger exporters = Logger.getLogger(MetricsExporters.class.getName());
        exporters.addHandler(handler);
        exporters.setUseParentHandlers(false);
        return handler;
    }

    private static void release(final Handler handler) {
        Logger exporters = Logger.getLogger(MetricsExporters.class.getName());
        exporters.removeHandler(handler);
        exporters.setUseParentHandlers(true);
    }

    private static int count(final List<String> messages, final String... parts) {
        int n = 0;
        for (String each : messages) {
            boolean all = true;
            for (String part : parts) {
                all &= each.contains(part);
            }
            n += all ? 1 : 0;
        }
        return n;
    }

    private static MetricsConfig csv(final int intervalSec) throws java.sql.SQLException {
        Map<String, String> options = new HashMap<String, String>();
        options.put(ConfigTestAccess.OPT_ENABLED, "true");
        options.put(ConfigTestAccess.OPT_EXPORT, ConfigTestAccess.EXPORT_CSV);
        options.put(ConfigTestAccess.OPT_INTERVAL_SEC, String.valueOf(intervalSec));
        return ConfigTestAccess.parseMetricsConfig(options);
    }

    private static MetricsConfig prometheus(final int port, final int intervalSec)
            throws java.sql.SQLException {
        return prometheus(port, intervalSec, null);
    }

    private static MetricsConfig prometheus(
            final int port, final int intervalSec, final String bind) throws java.sql.SQLException {
        Map<String, String> options = new HashMap<String, String>();
        if (bind != null) {
            options.put(ConfigTestAccess.OPT_PROMETHEUS_BIND, bind);
        }
        options.put(ConfigTestAccess.OPT_ENABLED, "true");
        options.put(ConfigTestAccess.OPT_EXPORT, ConfigTestAccess.EXPORT_PROMETHEUS);
        options.put(ConfigTestAccess.OPT_PROMETHEUS_PORT, String.valueOf(port));
        options.put(ConfigTestAccess.OPT_INTERVAL_SEC, String.valueOf(intervalSec));
        return ConfigTestAccess.parseMetricsConfig(options);
    }

    private static int freePort() throws IOException {
        ServerSocket probe = new ServerSocket(0);
        try {
            return probe.getLocalPort();
        } finally {
            probe.close();
        }
    }

    private static boolean accepts(final InetAddress address, final int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), 1000);
            return true;
        } catch (IOException refused) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing to release
            }
        }
    }

    private static InetAddress nonLoopbackAddress() throws SocketException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address;
                }
            }
        }
        return null;
    }

    private static void register(
            final String endpointId, final String role, final boolean readsOnRw) {
        RuntimeMetrics metrics = new RuntimeMetrics();
        metrics.setEnabled(true);
        Map<String, String> roles = new HashMap<String, String>();
        roles.put(endpointId, role);
        MetricsRegistry.register(metrics, roles, new FixedProbe(endpointId, readsOnRw));
    }

    private static final class FixedProbe implements BindingView {
        private final String endpointId;
        private final boolean readsOnRw;

        FixedProbe(final String endpointId, final boolean readsOnRw) {
            this.endpointId = endpointId;
            this.readsOnRw = readsOnRw;
        }

        public String boundReadEndpointId() {
            return endpointId;
        }

        public boolean readsOnRwConnection() {
            return readsOnRw;
        }
    }
}
