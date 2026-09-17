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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.jci.UUnreachableHostList;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
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
