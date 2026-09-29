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
import cubrid.jdbc.lb.config.MetricsConfig;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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

    private static MetricsConfig prometheus(final int port, final int intervalSec) {
        return prometheus(port, intervalSec, null);
    }

    private static MetricsConfig prometheus(
            final int port, final int intervalSec, final String bind) {
        Map<String, String> options = new HashMap<String, String>();
        if (bind != null) {
            options.put(MetricsConfig.OPT_PROMETHEUS_BIND, bind);
        }
        options.put(MetricsConfig.OPT_ENABLED, "true");
        options.put(MetricsConfig.OPT_EXPORT, MetricsConfig.EXPORT_PROMETHEUS);
        options.put(MetricsConfig.OPT_PROMETHEUS_PORT, String.valueOf(port));
        options.put(MetricsConfig.OPT_INTERVAL_SEC, String.valueOf(intervalSec));
        return MetricsConfig.parse(options, true);
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
