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

package cubrid.jdbc.lb.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.SessionLeg;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.failover.PhysicalRecoveryContext;
import cubrid.jdbc.lb.failover.PhysicalRecoveryResult;
import cubrid.jdbc.lb.route.Router;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Home-anchored read failover/failback over a multi-node topology (master node1, slaves
 * node2/node3, replica rep1):
 *
 * <ul>
 *   <li>failover prefers a same-role sibling (node3 RO) over a cross-role endpoint (rep1 SO);
 *   <li>once displaced to a real endpoint, failback returns to the weighted home when it recovers —
 *       this is what keeps the readWeight distribution from drifting without pool recycling;
 *   <li>from roOnRw, failback climbs to the best reachable rung (sibling first, then home).
 * </ul>
 */
public class SessionPhysicalConnManagerHomeFailbackTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final Endpoint NODE1_RW = new Endpoint("node1", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("node2", 33002); // home (slave)
    private static final Endpoint NODE3_RO = new Endpoint("node3", 33002); // sibling slave
    private static final Endpoint REP1_SO = new Endpoint("rep1", 33004); // cross-role replica

    private static EndpointTopology topology() {
        return new EndpointTopology(
                Arrays.asList(NODE1_RW),
                Arrays.asList(NODE2_RO, NODE3_RO, REP1_SO),
                Arrays.asList(REP1_SO));
    }

    @Test
    public void failoverPrefersSiblingThenFailsBackToHome() throws SQLException {
        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManager(down);
        try {
            // home = node2 RO (slave), reads served there.
            mgr.bindSession(NODE1_RW, NODE2_RO, topology());
            assertEquals(NODE2_RO, mgr.getSessionEndpoint(SessionLeg.RO));
            assertEquals(NODE2_RO, mgr.getHomeReadEndpoint());

            // node2 fails -> runtime RO recovery must pick the sibling slave (node3), not rep1 SO.
            markDown(down, NODE2_RO);
            PhysicalRecoveryResult r = mgr.recoverRo(roFailure(NODE2_RO));
            assertEquals(NODE3_RO, r.getBoundEndpoint());
            assertEquals(NODE3_RO, mgr.getSessionEndpoint(SessionLeg.RO));
            assertEquals("home must never change on failover", NODE2_RO, mgr.getHomeReadEndpoint());
            assertEquals("displaced to a real RO, not roOnRw", "NONE", mgr.getRoFallbackReason());

            // node2 recovers -> failback returns straight to home (the drift fix).
            markUp(down, NODE2_RO);
            Endpoint back = mgr.restoreRoIfRecovered();
            assertEquals(NODE2_RO, back);
            assertEquals(NODE2_RO, mgr.getSessionEndpoint(SessionLeg.RO));
        } finally {
            cleanup(mgr);
        }
    }

    @Test
    public void roOnRwClimbsSiblingThenHome() throws SQLException {
        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManager(down);
        try {
            mgr.bindSession(NODE1_RW, NODE2_RO, topology());

            // every real read endpoint down -> reads absorbed by RW (roOnRw).
            markDown(down, NODE2_RO);
            markDown(down, NODE3_RO);
            markDown(down, REP1_SO);
            PhysicalRecoveryResult r = mgr.recoverRo(roFailure(NODE2_RO));
            assertEquals(NODE1_RW, r.getBoundEndpoint());
            assertEquals(
                    SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON,
                    mgr.getRoFallbackReason());

            // only the sibling recovers (home still down) -> climb to the sibling, off RW.
            markUp(down, NODE3_RO);
            assertEquals(NODE3_RO, mgr.restoreRoIfRecovered());
            assertEquals("NONE", mgr.getRoFallbackReason());

            // home recovers -> climb the rest of the way back home.
            markUp(down, NODE2_RO);
            assertEquals(NODE2_RO, mgr.restoreRoIfRecovered());
            assertEquals(NODE2_RO, mgr.getSessionEndpoint(SessionLeg.RO));
            assertEquals(NODE2_RO, mgr.getHomeReadEndpoint());
        } finally {
            cleanup(mgr);
        }
    }

    @Test
    public void noFailbackWhenAlreadyHome() throws SQLException {
        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManager(down);
        try {
            mgr.bindSession(NODE1_RW, NODE2_RO, topology());
            assertFalse(mgr.getSessionEndpoint(SessionLeg.RO) == null);
            // At home and nothing failed -> nothing to do.
            assertEquals(null, mgr.restoreRoIfRecovered());
        } finally {
            cleanup(mgr);
        }
    }

    /**
     * CR-5 / 03 §C-1: when recovery re-binds the read role to an endpoint that already has a live
     * physical connection (a replica SO read fails, but home is first candidate and still open),
     * the displaced home connection must be closed (no leak) and its prep cache dropped (no
     * physical connection split), not silently overwritten.
     */
    @Test
    public void recoveryReboundToLiveHomeReclaimsDisplacedConnection() throws SQLException {
        final AtomicInteger node2Closes = new AtomicInteger();
        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManagerTrackingNode2Closes(down, node2Closes);
        try {
            mgr.bindSession(NODE1_RW, NODE2_RO, topology()); // home = node2, live
            PreparedStatement psOld = mgr.prepareStatement(NODE2_RO, "SELECT 1");

            // A replica SO read fails; recovery's home-first order re-binds to node2 by opening a
            // second connection -> the old node2 connection is displaced and must be reclaimed.
            PhysicalRecoveryResult r = mgr.recoverRo(roFailure(REP1_SO));
            assertEquals(NODE2_RO, r.getBoundEndpoint());

            // Displaced connection closed (no leak)...
            assertEquals("displaced home connection must be closed", 1, node2Closes.get());
            // ...and its prep cache dropped, so re-prepare binds to the new connection.
            PreparedStatement psNew = mgr.prepareStatement(NODE2_RO, "SELECT 1");
            assertNotSame(
                    "stale prep bound to the displaced connection must be dropped", psOld, psNew);
        } finally {
            cleanup(mgr);
        }
    }

    private static SessionPhysicalConnManager newManagerTrackingNode2Closes(
            final Set<String> downHosts, final AtomicInteger node2Closes) {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        for (String host : downHosts) {
                            if (url.contains(host + ":")) {
                                throw new SQLException(
                                        "down: " + host, null, UErrorCode.ER_COMMUNICATION);
                            }
                        }
                        return closeTrackingProxy(
                                url.contains(NODE2_RO.getHost() + ":") ? node2Closes : null);
                    }
                };

        return new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), config, factory);
    }

    private static Connection closeTrackingProxy(final AtomicInteger closeCount) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            private boolean closed;

                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                String name = method.getName();
                                if ("close".equals(name)) {
                                    if (!closed && closeCount != null) {
                                        closeCount.incrementAndGet();
                                    }
                                    closed = true;
                                    return null;
                                }
                                if ("isClosed".equals(name)) {
                                    return Boolean.valueOf(closed);
                                }
                                if ("setAutoCommit".equals(name)) {
                                    return null;
                                }
                                if ("prepareStatement".equals(name)) {
                                    return newPreparedStatementStub();
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                if (rt.equals(Integer.TYPE)) {
                                    return Integer.valueOf(0);
                                }
                                return null;
                            }
                        });
    }

    private static PreparedStatement newPreparedStatementStub() {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new InvocationHandler() {
                            private boolean closed;

                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                String name = method.getName();
                                if ("close".equals(name)) {
                                    closed = true;
                                    return null;
                                }
                                if ("isClosed".equals(name)) {
                                    return Boolean.valueOf(closed);
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                if (rt.equals(Integer.TYPE)) {
                                    return Integer.valueOf(0);
                                }
                                return null;
                            }
                        });
    }

    private static PhysicalRecoveryContext roFailure(final Endpoint failed) {
        return new PhysicalRecoveryContext(
                SessionLeg.RO,
                failed,
                Router.RouteTarget.TO_READ_ONLY,
                topology(),
                Collections.<String>emptySet(),
                false);
    }

    private static void markDown(final Set<String> down, final Endpoint ep) {
        down.add(ep.getHost());
        UUnreachableHostList.getInstance().add(ep.getId());
    }

    private static void markUp(final Set<String> down, final Endpoint ep) {
        down.remove(ep.getHost());
        UUnreachableHostList.getInstance().remove(ep.getId());
    }

    private static void cleanup(final SessionPhysicalConnManager mgr) {
        try {
            mgr.releasePhysicalConnections();
        } catch (RuntimeException ignored) {
        }
        for (Endpoint ep : new Endpoint[] {NODE1_RW, NODE2_RO, NODE3_RO, REP1_SO}) {
            UUnreachableHostList.getInstance().remove(ep.getId());
        }
    }

    private static SessionPhysicalConnManager newManager(final Set<String> downHosts) {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        for (String host : downHosts) {
                            if (url.contains(host + ":")) {
                                throw new SQLException(
                                        "down: " + host, null, UErrorCode.ER_COMMUNICATION);
                            }
                        }
                        return connectionProxy();
                    }
                };

        return new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), config, factory);
    }

    private static Connection connectionProxy() {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                String name = method.getName();
                                if ("isClosed".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                if ("close".equals(name) || "setAutoCommit".equals(name)) {
                                    return null;
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                if (rt.equals(Integer.TYPE)) {
                                    return Integer.valueOf(0);
                                }
                                return null;
                            }
                        });
    }
}
