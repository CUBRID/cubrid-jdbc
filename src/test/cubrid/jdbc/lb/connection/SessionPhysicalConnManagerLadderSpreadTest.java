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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.SessionLeg;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.failover.PhysicalRecoveryContext;
import cubrid.jdbc.lb.route.Router;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.Test;

/**
 * Displaced read legs must SPREAD over a multi-node tier, not pile onto its first node.
 *
 * <p>The read ladder is home → same-role siblings → cross-role. With one slave and one replica the
 * tiers hold a single node each and order is all there is, but a real cluster can run several
 * slaves and several replicas — and then "always take the tier's first entry" concentrates every
 * displaced session on one node while its siblings stay idle. Each tier is therefore rotated by a
 * per-session offset.
 *
 * <p>Topology here: master node1 (RW), slaves node2/node3/node4 (RO), replicas rep1/rep2 (SO).
 */
public class SessionPhysicalConnManagerLadderSpreadTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final Endpoint NODE1_RW = new Endpoint("node1", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("node2", 33002);
    private static final Endpoint NODE3_RO = new Endpoint("node3", 33002);
    private static final Endpoint NODE4_RO = new Endpoint("node4", 33002);
    private static final Endpoint REP1_SO = new Endpoint("rep1", 33004);
    private static final Endpoint REP2_SO = new Endpoint("rep2", 33004);

    private static EndpointTopology topology() {
        return new EndpointTopology(
                Arrays.asList(NODE1_RW),
                Arrays.asList(NODE2_RO, NODE3_RO, NODE4_RO, REP1_SO, REP2_SO),
                Arrays.asList(REP1_SO, REP2_SO));
    }

    /** One master, ONE slave, two replicas — the live 3-node test configuration. */
    private static EndpointTopology onlyOneSlaveTopology() {
        return new EndpointTopology(
                Arrays.asList(NODE1_RW),
                Arrays.asList(NODE2_RO, REP1_SO, REP2_SO),
                Arrays.asList(REP1_SO, REP2_SO));
    }

    /**
     * Twelve sessions all homed on node2; node2 dies. The surviving sibling slaves are node3 and
     * node4, so the displaced sessions must end up on BOTH — before the tier rotation they all went
     * to node3.
     */
    @Test
    public void displacedSlaveReadsSpreadOverSiblingSlaves() throws SQLException {
        final int sessions = 12;
        Set<String> down = new HashSet<String>();
        markDown(down, NODE2_RO);

        Map<String, Integer> landedOn = new HashMap<String, Integer>();
        List<SessionPhysicalConnManager> mgrs = new ArrayList<SessionPhysicalConnManager>();
        try {
            for (int i = 0; i < sessions; i++) {
                SessionPhysicalConnManager mgr = newManager(down);
                mgrs.add(mgr);
                // Home is node2 (the weighted assignment); it is down, so the bind already falls
                // over.
                mgr.bindSession(NODE1_RW, NODE2_RO, topology());
                Endpoint ro = mgr.getSessionEndpoint(SessionLeg.RO);
                bump(landedOn, ro.getId());
            }

            assertEquals("home must never move", NODE2_RO, mgrs.get(0).getHomeReadEndpoint());
            assertTrue(
                    "displaced reads must use both sibling slaves, got " + landedOn,
                    landedOn.containsKey(NODE3_RO.getId())
                            && landedOn.containsKey(NODE4_RO.getId()));
            assertEquals(
                    "no displaced read may cross to a replica while a sibling slave is up: "
                            + landedOn,
                    2,
                    landedOn.size());
            assertEven("displaced share must be even across the sibling slaves", landedOn);
        } finally {
            cleanup(mgrs, down);
        }
    }

    /**
     * The rotation must survive the WEIGHTED HOME ASSIGNMENT, which is itself a round robin.
     *
     * <p>Every session takes a rotation offset when it binds, so with one JVM-wide counter the
     * offsets follow bind order — and sessions that share a home occur every P-th in bind order, P
     * being the weight pattern's period. Their offsets are then all congruent mod P, so {@code
     * offset % tierSize} is constant whenever tierSize divides P and the entire displaced share
     * piles onto one node again, exactly the bug the rotation was meant to fix. Measured before the
     * per-home counter: 8 of 8 on the first SO.
     *
     * <p>Models the live 3-node config: {@code readWeight=master:0,slave:1,replica:1} over one
     * slave RO and two replica SOs gives the repeating home pattern {@code slave, rep1, slave,
     * rep2} (P=4) while the surviving tier holds 2 endpoints.
     */
    @Test
    public void weightedHomeInterleavingDoesNotDefeatRotation() throws SQLException {
        final Endpoint[] homePattern = {NODE2_RO, REP1_SO, NODE2_RO, REP2_SO};
        final int sessions = 16;
        Set<String> down = new HashSet<String>();
        markDown(down, NODE2_RO);

        Map<String, Integer> landedOn = new HashMap<String, Integer>();
        List<SessionPhysicalConnManager> mgrs = new ArrayList<SessionPhysicalConnManager>();
        try {
            for (int i = 0; i < sessions; i++) {
                Endpoint home = homePattern[i % homePattern.length];
                SessionPhysicalConnManager mgr = newManager(down);
                mgrs.add(mgr);
                mgr.bindSession(NODE1_RW, home, onlyOneSlaveTopology());
                if (!home.equals(NODE2_RO)) {
                    continue; // replica-homed sessions are not displaced; ignore them
                }
                bump(landedOn, mgr.getSessionEndpoint(SessionLeg.RO).getId());
            }

            // No sibling slave exists, so the displaced share crosses to the replica tier — and
            // must
            // spread over BOTH replicas rather than stacking on the first one.
            assertEquals(
                    "displaced reads must use both replicas, got " + landedOn, 2, landedOn.size());
            assertEven("weighted-home interleaving must not collapse the rotation", landedOn);
        } finally {
            cleanup(mgrs, down);
        }
    }

    /**
     * Same requirement for replicas: a displaced replica read must spread over the sibling
     * replicas.
     */
    @Test
    public void displacedReplicaReadsSpreadOverSiblingReplicas() throws SQLException {
        final int sessions = 8;
        Set<String> down = new HashSet<String>();
        markDown(down, REP1_SO);

        Map<String, Integer> landedOn = new HashMap<String, Integer>();
        List<SessionPhysicalConnManager> mgrs = new ArrayList<SessionPhysicalConnManager>();
        try {
            for (int i = 0; i < sessions; i++) {
                SessionPhysicalConnManager mgr = newManager(down);
                mgrs.add(mgr);
                mgr.bindSession(NODE1_RW, REP1_SO, topology());
                bump(landedOn, mgr.getSessionEndpoint(SessionLeg.RO).getId());
            }
            assertEquals(
                    "sibling replica must absorb the displaced reads, got " + landedOn,
                    Integer.valueOf(sessions),
                    landedOn.get(REP2_SO.getId()));
        } finally {
            cleanup(mgrs, down);
        }
    }

    /**
     * The cross-role tier must be reached even when the same-role tier is alive-but-failing.
     *
     * <p>{@code cubrid.lb.runtime.failover.max.attempts.per.group} (default 1) used to be counted
     * over the whole flattened ladder, so a displaced replica spent its single dial on the sibling
     * SO and dropped to {@code roOnRw} — the MASTER — while a slave RO was up. The budget is per
     * role group now, so the sibling failing costs the sibling tier's budget only.
     *
     * <p>The sibling here must fail to CONNECT while staying OFF the unreachable list: an excluded
     * endpoint is skipped without consuming a dial, which would let the old code pass by accident.
     * That is exactly why the live outage produced a split result — sessions whose sibling had
     * already been flagged by another session escaped, the rest did not.
     */
    @Test
    public void crossRoleIsReachedWhenTheSiblingTierFails() throws SQLException {
        Set<String> down = new HashSet<String>();
        List<SessionPhysicalConnManager> mgrs = new ArrayList<SessionPhysicalConnManager>();
        try {
            SessionPhysicalConnManager mgr = newManager(down);
            mgrs.add(mgr);
            mgr.bindSession(NODE1_RW, REP1_SO, topology());
            assertEquals(REP1_SO, mgr.getSessionEndpoint(SessionLeg.RO));

            // Both replicas unreachable, but only as CONNECT failures — no unreachable-list
            // entries.
            down.add(REP1_SO.getId());
            down.add(REP2_SO.getId());

            Endpoint moved = mgr.recoverRo(roFailure(REP1_SO)).getBoundEndpoint();
            assertTrue(
                    "a live cross-role slave RO must be reached instead of roOnRw, got " + moved,
                    NODE2_RO.equals(moved) || NODE3_RO.equals(moved) || NODE4_RO.equals(moved));
            assertEquals("home must never move", REP1_SO, mgr.getHomeReadEndpoint());
        } finally {
            cleanup(mgrs, down);
        }
    }

    /**
     * Rotation must not disturb the ladder's meaning: the failed endpoint's HOME is still preferred
     * on recovery, and a session already at home is not moved.
     */
    @Test
    public void rotationKeepsHomeFirstOnFailback() throws SQLException {
        Set<String> down = new HashSet<String>();
        List<SessionPhysicalConnManager> mgrs = new ArrayList<SessionPhysicalConnManager>();
        try {
            SessionPhysicalConnManager mgr = newManager(down);
            mgrs.add(mgr);
            mgr.bindSession(NODE1_RW, NODE3_RO, topology());
            assertEquals(NODE3_RO, mgr.getSessionEndpoint(SessionLeg.RO));

            // node3 fails: recovery may pick any surviving sibling, but never the home itself.
            markDown(down, NODE3_RO);
            Endpoint moved = mgr.recoverRo(roFailure(NODE3_RO)).getBoundEndpoint();
            assertTrue("must move off the failed home: " + moved, !NODE3_RO.equals(moved));

            // node3 back: failback returns to home, whatever sibling it had climbed to.
            markUp(down, NODE3_RO);
            assertEquals(NODE3_RO, mgr.restoreRoIfRecovered());
            assertEquals(NODE3_RO, mgr.getSessionEndpoint(SessionLeg.RO));
            assertEquals("at home -> nothing left to climb", null, mgr.restoreRoIfRecovered());
        } finally {
            cleanup(mgrs, down);
        }
    }

    /**
     * The displaced share must be spread evenly, not merely spread. Tolerance is one session, which
     * is all an exact round robin can be off by when the count does not divide the tier size — and
     * it also makes the assertion independent of where the shared per-home counter happens to
     * start.
     */
    private static void assertEven(final String msg, final Map<String, Integer> counts) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            min = Math.min(min, e.getValue().intValue());
            max = Math.max(max, e.getValue().intValue());
        }
        assertTrue(msg + ", got " + counts, max - min <= 1);
    }

    private static void bump(final Map<String, Integer> counts, final String key) {
        Integer prev = counts.get(key);
        counts.put(key, Integer.valueOf(prev == null ? 1 : prev.intValue() + 1));
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
        down.add(ep.getId());
        UUnreachableHostList.getInstance().add(ep.getId());
    }

    private static void markUp(final Set<String> down, final Endpoint ep) {
        down.remove(ep.getId());
        UUnreachableHostList.getInstance().remove(ep.getId());
    }

    private static void cleanup(
            final List<SessionPhysicalConnManager> mgrs, final Set<String> down) {
        for (int i = 0; i < mgrs.size(); i++) {
            try {
                mgrs.get(i).releasePhysicalConnections();
            } catch (RuntimeException ignored) {
            }
        }
        for (Endpoint ep :
                new Endpoint[] {NODE1_RW, NODE2_RO, NODE3_RO, NODE4_RO, REP1_SO, REP2_SO}) {
            UUnreachableHostList.getInstance().remove(ep.getId());
        }
        down.clear();
    }

    private static SessionPhysicalConnManager newManager(final Set<String> downEndpointIds) {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        final LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        for (String epId : downEndpointIds) {
                            if (url.contains(":" + epId + ":")) {
                                throw new SQLException(
                                        "down: " + epId, null, UErrorCode.ER_COMMUNICATION);
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
