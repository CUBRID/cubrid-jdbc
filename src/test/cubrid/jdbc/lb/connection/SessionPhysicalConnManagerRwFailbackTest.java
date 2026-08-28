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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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
 * RW failback: a session whose RW leg was displaced onto a sibling RW broker — because the master
 * broker was down or CAS-saturated ({@code CAS_ER_FREE_SERVER}) at bind time, or because runtime
 * recovery moved it — returns to the master RW once that recovers, without waiting for pool
 * recycling ({@code maxLifetime}).
 *
 * <p>Topology: master node1 (RW), sibling node2 (RW + RO).
 */
public class SessionPhysicalConnManagerRwFailbackTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final Endpoint NODE1_RW = new Endpoint("node1", 33000); // master (home RW)
    private static final Endpoint NODE2_RW = new Endpoint("node2", 33000); // sibling RW
    private static final Endpoint NODE2_RO = new Endpoint("node2", 33002); // read endpoint

    /** RW-only topology (no RO/replica): a displaced master read has nowhere else to go. */
    private static EndpointTopology topologyRwOnly() {
        return new EndpointTopology(
                Arrays.asList(NODE1_RW, NODE2_RW),
                Collections.<Endpoint>emptyList(),
                Collections.<Endpoint>emptyList());
    }

    private static EndpointTopology topology() {
        return new EndpointTopology(
                Arrays.asList(NODE1_RW, NODE2_RW),
                Arrays.asList(NODE2_RO),
                Collections.<Endpoint>emptyList());
    }

    /** The reported case: master CAS exhausted at bind time strands RW on the sibling broker. */
    @Test
    public void casSaturatedMasterStrandsRwThenFailsBack() throws SQLException {
        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManager(down, UErrorCode.CAS_ER_FREE_SERVER);
        try {
            markDown(down, NODE1_RW);
            mgr.bindSession(NODE1_RW, NODE2_RO, topology());

            // Displaced: writes are on the sibling RW, but home stays the master.
            assertEquals(NODE2_RW, mgr.getSessionEndpoint(SessionLeg.RW));
            assertEquals(NODE1_RW, mgr.getHomeRwEndpoint());
            // The read leg is untouched by RW displacement.
            assertEquals(NODE2_RO, mgr.getSessionEndpoint(SessionLeg.RO));

            // Master CAS frees up -> failback returns the RW leg to the master.
            markUp(down, NODE1_RW);
            assertEquals(NODE1_RW, mgr.restoreRwIfRecovered());
            assertEquals(NODE1_RW, mgr.getSessionEndpoint(SessionLeg.RW));
            assertEquals(NODE2_RO, mgr.getSessionEndpoint(SessionLeg.RO));
        } finally {
            cleanup(mgr);
        }
    }

    @Test
    public void noFailbackWhenAlreadyOnMaster() throws SQLException {
        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManager(down, UErrorCode.ER_COMMUNICATION);
        try {
            mgr.bindSession(NODE1_RW, NODE2_RO, topology());
            assertEquals(NODE1_RW, mgr.getSessionEndpoint(SessionLeg.RW));
            assertNull(mgr.restoreRwIfRecovered());
        } finally {
            cleanup(mgr);
        }
    }

    @Test
    public void noFailbackWhileMasterStillDown() throws SQLException {
        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManager(down, UErrorCode.ER_COMMUNICATION);
        try {
            markDown(down, NODE1_RW);
            mgr.bindSession(NODE1_RW, NODE2_RO, topology());
            assertEquals(NODE2_RW, mgr.getSessionEndpoint(SessionLeg.RW));

            // Still down: the probe fails, the session keeps writing on the sibling.
            assertNull(mgr.restoreRwIfRecovered());
            assertEquals(NODE2_RW, mgr.getSessionEndpoint(SessionLeg.RW));
        } finally {
            cleanup(mgr);
        }
    }

    @Test
    public void disabledByOption() throws SQLException {
        Set<String> down = new HashSet<String>();
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_WRITE_FAILBACK_ENABLED, "false");
        SessionPhysicalConnManager mgr =
                newManager(down, UErrorCode.ER_COMMUNICATION, cfg, null, null);
        try {
            markDown(down, NODE1_RW);
            mgr.bindSession(NODE1_RW, NODE2_RO, topology());
            assertEquals(NODE2_RW, mgr.getSessionEndpoint(SessionLeg.RW));

            markUp(down, NODE1_RW);
            assertNull("failback must be off when disabled", mgr.restoreRwIfRecovered());
            assertEquals(NODE2_RW, mgr.getSessionEndpoint(SessionLeg.RW));
        } finally {
            cleanup(mgr);
        }
    }

    @Test
    public void runtimeFailoverToSiblingThenFailback() throws SQLException {
        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManager(down, UErrorCode.ER_COMMUNICATION);
        try {
            mgr.bindSession(NODE1_RW, NODE2_RO, topology());
            assertEquals(NODE1_RW, mgr.getSessionEndpoint(SessionLeg.RW));

            markDown(down, NODE1_RW);
            PhysicalRecoveryResult r = mgr.recoverRw(rwFailure(NODE1_RW));
            assertEquals(NODE2_RW, r.getBoundEndpoint());
            assertEquals("home must never move on failover", NODE1_RW, mgr.getHomeRwEndpoint());

            markUp(down, NODE1_RW);
            assertEquals(NODE1_RW, mgr.restoreRwIfRecovered());
            assertEquals(NODE1_RW, mgr.getSessionEndpoint(SessionLeg.RW));
        } finally {
            cleanup(mgr);
        }
    }

    /**
     * Master-read session with NO real read rung (RW-only topology): the read role stays mapped
     * onto the RW endpoint itself, so failback must move the read leg — and its home — along with
     * the RW leg, otherwise reads stay keyed to the stranded node.
     */
    @Test
    public void masterReadSessionMovesBothLegs() throws SQLException {
        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManager(down, UErrorCode.ER_COMMUNICATION);
        try {
            markDown(down, NODE1_RW);
            mgr.bindSessionWithReadTarget(NODE1_RW, NODE1_RW, true, topologyRwOnly());
            assertEquals(NODE2_RW, mgr.getSessionEndpoint(SessionLeg.RW));
            assertEquals(NODE2_RW, mgr.getSessionEndpoint(SessionLeg.RO));

            markUp(down, NODE1_RW);
            assertEquals(NODE1_RW, mgr.restoreRwIfRecovered());
            assertEquals(NODE1_RW, mgr.getSessionEndpoint(SessionLeg.RW));
            assertEquals(NODE1_RW, mgr.getSessionEndpoint(SessionLeg.RO));
            assertEquals(NODE1_RW, mgr.getHomeReadEndpoint());
            assertSame(
                    "read still rides on the (new) RW physical connection",
                    mgr.getPhyConn(NODE1_RW),
                    mgr.getPhyConn(mgr.getSessionEndpoint(SessionLeg.RO)));
        } finally {
            cleanup(mgr);
        }
    }

    /**
     * A master-read session bound while the master RW is down must NOT read through the sibling RW
     * (that hop lands on the master DB anyway); it takes a real read rung instead, keeps the master
     * as its read home, and both legs rejoin ONE connection once the master is back.
     */
    @Test
    public void displacedMasterReadUsesRealReadRungThenRejoins() throws SQLException {
        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManager(down, UErrorCode.ER_COMMUNICATION);
        try {
            markDown(down, NODE1_RW);
            mgr.bindSessionWithReadTarget(NODE1_RW, NODE1_RW, true, topology());

            assertEquals(
                    "write leg on the sibling RW", NODE2_RW, mgr.getSessionEndpoint(SessionLeg.RW));
            assertEquals(
                    "read leg on a real read broker, not the sibling RW",
                    NODE2_RO,
                    mgr.getSessionEndpoint(SessionLeg.RO));
            assertEquals("read home stays the master", NODE1_RW, mgr.getHomeReadEndpoint());
            assertEquals(NODE1_RW, mgr.getHomeRwEndpoint());
            assertNotSame(
                    "the two legs must be separate connections while displaced",
                    mgr.getPhyConn(NODE2_RW),
                    mgr.getPhyConn(NODE2_RO));
            assertEquals(
                    "a real read rung is not an roOnRw fallback",
                    "NONE",
                    mgr.getRoFallbackReason());

            // Master back: write leg first, then the read leg rejoins it on the same connection.
            markUp(down, NODE1_RW);
            assertEquals(NODE1_RW, mgr.restoreRwIfRecovered());
            assertEquals(NODE1_RW, mgr.restoreRoIfRecovered());
            assertEquals(NODE1_RW, mgr.getSessionEndpoint(SessionLeg.RO));
            assertSame(
                    "both legs share one connection again",
                    mgr.getPhyConn(NODE1_RW),
                    mgr.getPhyConn(mgr.getSessionEndpoint(SessionLeg.RO)));
            assertNull("nothing left to fail back", mgr.restoreRoIfRecovered());
        } finally {
            cleanup(mgr);
        }
    }

    /**
     * roOnRw session: the read leg keeps its logical RO key, but must follow the new RW connection.
     */
    @Test
    public void roOnRwSessionFollowsNewRwConnection() throws SQLException {
        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManager(down, UErrorCode.ER_COMMUNICATION);
        try {
            markDown(down, NODE1_RW);
            markDown(down, NODE2_RO);
            mgr.bindSession(NODE1_RW, NODE2_RO, topology()); // RO down -> roOnRw on the sibling RW
            assertEquals(NODE2_RW, mgr.getSessionEndpoint(SessionLeg.RW));
            assertEquals(
                    SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON,
                    mgr.getRoFallbackReason());

            markUp(down, NODE1_RW);
            assertEquals(NODE1_RW, mgr.restoreRwIfRecovered());
            assertSame(
                    "roOnRw read must use the new master RW connection",
                    mgr.getPhyConn(NODE1_RW),
                    mgr.getPhyConn(NODE2_RO));
        } finally {
            cleanup(mgr);
        }
    }

    @Test
    public void failbackReclaimsDisplacedConnectionAndPrepCache() throws SQLException {
        Set<String> down = new HashSet<String>();
        AtomicInteger node2Closes = new AtomicInteger();
        SessionPhysicalConnManager mgr =
                newManager(down, UErrorCode.ER_COMMUNICATION, new Properties(), node2Closes, null);
        try {
            markDown(down, NODE1_RW);
            mgr.bindSession(NODE1_RW, NODE2_RO, topology());
            PreparedStatement psOld = mgr.prepareStatement(NODE2_RW, "UPDATE t SET a = 1");

            markUp(down, NODE1_RW);
            assertEquals(NODE1_RW, mgr.restoreRwIfRecovered());

            assertEquals("displaced sibling RW connection must be closed", 1, node2Closes.get());
            PreparedStatement psNew = mgr.prepareStatement(NODE1_RW, "UPDATE t SET a = 1");
            assertNotSame(
                    "stale prep bound to the displaced connection must be dropped", psOld, psNew);
        } finally {
            cleanup(mgr);
        }
    }

    /**
     * Master-read session (read home = the RW endpoint): after both legs were displaced and the RW
     * leg failed back, the read failback must rebind onto the SAME RW connection — not open a
     * second connection to the same broker, which would land on the same key and close the live
     * write leg.
     */
    @Test
    public void masterReadSessionReusesRwConnectionOnReadFailback() throws SQLException {
        Set<String> down = new HashSet<String>();
        AtomicInteger masterOpens = new AtomicInteger();
        SessionPhysicalConnManager mgr =
                newManager(down, UErrorCode.ER_COMMUNICATION, new Properties(), null, masterOpens);
        try {
            mgr.bindSessionWithReadTarget(NODE1_RW, NODE1_RW, true, topology());
            assertEquals(NODE1_RW, mgr.getSessionEndpoint(SessionLeg.RW));
            assertEquals(NODE1_RW, mgr.getSessionEndpoint(SessionLeg.RO));

            // Master down: both legs move away — RW to the sibling RW, the read leg to the slave
            // RO.
            markDown(down, NODE1_RW);
            assertEquals(NODE2_RW, mgr.recoverRw(rwFailure(NODE1_RW)).getBoundEndpoint());
            assertEquals(NODE2_RO, mgr.recoverRo(roFailure(NODE1_RW)).getBoundEndpoint());

            // Master back: the RW leg returns first.
            markUp(down, NODE1_RW);
            assertEquals(NODE1_RW, mgr.restoreRwIfRecovered());
            Connection rwConn = mgr.getPhyConn(NODE1_RW);
            int opensBeforeReadFailback = masterOpens.get();

            // Read failback must reuse that connection, not open another one to the same broker.
            assertEquals(NODE1_RW, mgr.restoreRoIfRecovered());
            assertEquals(
                    "read failback must not open a second connection to the master",
                    opensBeforeReadFailback,
                    masterOpens.get());
            assertEquals(NODE1_RW, mgr.getSessionEndpoint(SessionLeg.RO));
            assertSame("read must run on the live RW connection", rwConn, mgr.getPhyConn(NODE1_RW));
            assertFalse("the write leg's connection must still be open", rwConn.isClosed());
            assertEquals("at home, so not an roOnRw fallback", "NONE", mgr.getRoFallbackReason());
            // Nothing left to climb.
            assertNull(mgr.restoreRoIfRecovered());
        } finally {
            cleanup(mgr);
        }
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

    private static PhysicalRecoveryContext rwFailure(final Endpoint failed) {
        return new PhysicalRecoveryContext(
                SessionLeg.RW,
                failed,
                Router.RouteTarget.TO_READ_WRITE,
                topology(),
                Collections.<String>emptySet(),
                false);
    }

    private static void markDown(final Set<String> down, final Endpoint ep) {
        down.add(ep.getId());
        UUnreachableHostList.getInstance().add(ep.getId());
    }

    private static void markUp(final Set<String> down, final Endpoint ep) {
        down.remove(ep.getId());
        UUnreachableHostList.getInstance().remove(ep.getId());
    }

    private static void cleanup(final SessionPhysicalConnManager mgr) {
        try {
            mgr.releasePhysicalConnections();
        } catch (RuntimeException ignored) {
        }
        for (Endpoint ep : new Endpoint[] {NODE1_RW, NODE2_RW, NODE2_RO}) {
            UUnreachableHostList.getInstance().remove(ep.getId());
        }
    }

    private static SessionPhysicalConnManager newManager(
            final Set<String> downEndpointIds, final int connectErrno) {
        return newManager(downEndpointIds, connectErrno, new Properties(), null, null);
    }

    /**
     * Manager over a fake broker set: connecting to an endpoint id in {@code downEndpointIds} fails
     * with {@code connectErrno} (an errno the unreachable filter recognizes, so the endpoint lands
     * on the JCI unreachable list exactly as in production).
     */
    private static SessionPhysicalConnManager newManager(
            final Set<String> downEndpointIds,
            final int connectErrno,
            final Properties extraProps,
            final AtomicInteger node2Closes,
            final AtomicInteger masterOpens) {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        // No throttling at this level; the per-session throttle lives in LoadBalanceConnection.
        cfg.putAll(extraProps);
        final LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        for (String epId : downEndpointIds) {
                            if (url.contains(hostPortToken(epId))) {
                                throw new SQLException("down: " + epId, null, connectErrno);
                            }
                        }
                        if (masterOpens != null && url.contains(hostPortToken(NODE1_RW.getId()))) {
                            masterOpens.incrementAndGet();
                        }
                        return connectionProxy(
                                url.contains(hostPortToken(NODE2_RW.getId())) ? node2Closes : null);
                    }
                };

        return new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), config, factory);
    }

    /**
     * {@code host:port} as it appears in a classic physical URL: {@code jdbc:cubrid:host:port:...}.
     */
    private static String hostPortToken(final String endpointId) {
        return ":" + endpointId + ":";
    }

    private static Connection connectionProxy(final AtomicInteger closeCount) {
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
}
