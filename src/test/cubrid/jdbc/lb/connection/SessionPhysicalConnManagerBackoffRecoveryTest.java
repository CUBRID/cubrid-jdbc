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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.LbTestSettings;
import cubrid.jdbc.lb.SessionLeg;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.failover.PhysicalRecoveryContext;
import cubrid.jdbc.lb.failover.PhysicalRecoveryResult;
import cubrid.jdbc.lb.route.Router.RouteTarget;
import cubrid.jdbc.lb.state.RecoveryBackoff;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

public class SessionPhysicalConnManagerBackoffRecoveryTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final String RW_ID = "softrw:34100";
    private static final String RO_ID = "softro:34101";
    private static final long MS = 1000000L; // nanos per milli

    private final Endpoint rw = new Endpoint("softrw", 34100);
    private final Endpoint ro = new Endpoint("softro", 34101);
    private final EndpointTopology topo =
            new EndpointTopology(rw, Arrays.asList(ro), Collections.<Endpoint>emptyList());

    @After
    public void tearDown() {
        UUnreachableHostList list = UUnreachableHostList.getInstance();
        list.remove(RW_ID);
        list.remove(RO_ID);
        list.remove("bkrw1:34110");
        list.remove("bkrw2:34111");
        list.remove("bkro:34112");
        list.remove("bkro1:34121");
        list.remove("bkro2:34122");
        list.remove("bkrw:34120");
        list.remove("bkso:34130");
        list.remove("trirw1:34140");
        list.remove("trirw2:34141");
        list.remove("trirw3:34142");
        list.remove("triro:34143");
        list.remove("clrw:34150");
        list.remove("clsl1:34151");
        list.remove("clsl2:34152");
        list.remove("clsl3:34153");
    }

    private final Endpoint clRw = new Endpoint("clrw", 34150);
    private final Endpoint sl1 = new Endpoint("clsl1", 34151);
    private final Endpoint sl2 = new Endpoint("clsl2", 34152);
    private final Endpoint sl3 = new Endpoint("clsl3", 34153);
    private final EndpointTopology slaves =
            new EndpointTopology(
                    clRw, Arrays.asList(sl1, sl2, sl3), Collections.<Endpoint>emptyList());

    // m38 rest (F1): the sibling tier has a budget of one dial, so the sibling it never reaches
    // must keep its probe for another session.
    @Test
    public void recoverRoClaimsOnlyTheSiblingItDials() throws SQLException {
        final Set<String> down = new HashSet<String>();
        final Map<String, Integer> dials = new HashMap<String, Integer>();
        RecoveryBackoff backoff = new RecoveryBackoff(3600000);
        SessionPhysicalConnManager mgr = newManager(backoff, countingFactory(down, dials, 0));
        try {
            mgr.bindSession(clRw, sl1, slaves);
            down.addAll(Arrays.asList(sl1.getId(), sl2.getId(), sl3.getId()));
            UUnreachableHostList.getInstance().add(sl2.getId());
            UUnreachableHostList.getInstance().add(sl3.getId());
            dials.clear();

            mgr.recoverRo(roCtx(sl1, slaves));

            Endpoint skipped = dials.containsKey(sl2.getId()) ? sl3 : sl2;
            assertEquals(dials.toString(), 1, dials.size());
            assertTrue(backoff.tryClaimProbe(skipped.getId(), System.nanoTime()));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    // m38 rest (F1): a failback that comes home on the first rung leaves the rungs below unclaimed.
    @Test
    public void readFailbackClaimsOnlyTheRungsItDials() throws SQLException {
        final Set<String> down = new HashSet<String>();
        final Map<String, Integer> dials = new HashMap<String, Integer>();
        RecoveryBackoff backoff = new RecoveryBackoff(3600000);
        SessionPhysicalConnManager mgr = newManager(backoff, countingFactory(down, dials, 0));
        try {
            mgr.bindSession(clRw, sl1, slaves);
            down.addAll(Arrays.asList(sl1.getId(), sl2.getId(), sl3.getId()));
            mgr.recoverRo(roCtx(sl1, slaves));
            // All back but still listed; their windows have passed.
            down.clear();
            for (Endpoint sl : Arrays.asList(sl1, sl2, sl3)) {
                UUnreachableHostList.getInstance().add(sl.getId());
                backoff.reset(sl.getId());
            }
            dials.clear();

            assertEquals(sl1, mgr.restoreRoIfRecovered());

            assertEquals(Collections.singletonMap(sl1.getId(), 1), dials);
            assertTrue(backoff.tryClaimProbe(sl2.getId(), System.nanoTime()));
            assertTrue(backoff.tryClaimProbe(sl3.getId(), System.nanoTime()));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    // m38 rest (F3): the failure lists the endpoint, so it must also start its window, or the next
    // session takes the "first probe" and dials it again at once.
    @Test
    public void aFailedDialStartsTheWindowForTheNextSession() throws SQLException {
        final Set<String> down = new HashSet<String>(Collections.singleton(sl1.getId()));
        final Map<String, Integer> dials = new HashMap<String, Integer>();
        RecoveryBackoff backoff = new RecoveryBackoff(3600000);
        SessionPhysicalConnManager a = newManager(backoff, countingFactory(down, dials, 0));
        SessionPhysicalConnManager b = newManager(backoff, countingFactory(down, dials, 0));
        try {
            a.bindSession(clRw, sl1, slaves);
            b.bindSession(clRw, sl1, slaves);

            assertEquals(Integer.valueOf(1), dials.get(sl1.getId()));
        } finally {
            a.releasePhysicalConnections();
            b.releasePhysicalConnections();
        }
    }

    // m38 rest (F3): a dial longer than the window used to find the window over when it failed.
    @Test
    public void aDialSlowerThanTheWindowHoldsTheNextSessionOff() throws SQLException {
        final Set<String> down = new HashSet<String>(Collections.singleton(sl1.getId()));
        final Map<String, Integer> dials = new HashMap<String, Integer>();
        UUnreachableHostList.getInstance().add(sl1.getId());
        RecoveryBackoff backoff = new RecoveryBackoff(500);
        SessionPhysicalConnManager a = newManager(backoff, countingFactory(down, dials, 700));
        SessionPhysicalConnManager b = newManager(backoff, countingFactory(down, dials, 700));
        try {
            a.bindSession(clRw, sl1, slaves);
            b.bindSession(clRw, sl1, slaves);

            assertEquals(Integer.valueOf(1), dials.get(sl1.getId()));
        } finally {
            a.releasePhysicalConnections();
            b.releasePhysicalConnections();
        }
    }

    @Test
    public void anUnlistedEndpointIsDialedWithoutItsProbe() throws SQLException {
        RecoveryBackoff backoff = new RecoveryBackoff(3600000);
        assertTrue(backoff.tryClaimProbe(RO_ID, System.nanoTime()));
        SessionPhysicalConnManager mgr = newManager(backoff, alwaysUpFactory());
        try {
            mgr.bindSession(rw, ro, topo);

            assertEquals("NONE", mgr.getRoFallbackReason());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void poolSharedBackoffElectsSingleProberAcrossManagers() throws SQLException {
        UUnreachableHostList.getInstance().add(RO_ID);
        final Map<String, Integer> dials = new HashMap<String, Integer>();
        final Set<String> down = Collections.singleton(RO_ID);
        RecoveryBackoff shared = new RecoveryBackoff(3600000);
        SessionPhysicalConnManager mgrA = newManager(shared, countingFactory(down, dials, 0));
        SessionPhysicalConnManager mgrB = newManager(shared, countingFactory(down, dials, 0));
        try {
            mgrA.bindSession(rw, ro, topo);
            mgrB.bindSession(rw, ro, topo);

            assertEquals(Integer.valueOf(1), dials.get(RO_ID));
        } finally {
            mgrA.releasePhysicalConnections();
            mgrB.releasePhysicalConnections();
        }
    }

    @Test
    public void aListedEndpointIsProbedAgainOnceItsWindowPasses() throws Exception {
        UUnreachableHostList.getInstance().add(RO_ID);
        final Map<String, Integer> dials = new HashMap<String, Integer>();
        final Set<String> down = Collections.singleton(RO_ID);
        RecoveryBackoff shared = new RecoveryBackoff(50);
        SessionPhysicalConnManager[] sessions = new SessionPhysicalConnManager[3];
        try {
            for (int i = 0; i < sessions.length; i++) {
                if (i == 2) {
                    Thread.sleep(80);
                }
                sessions[i] = newManager(shared, countingFactory(down, dials, 0));
                sessions[i].bindSession(rw, ro, topo);
            }

            assertEquals("the second session falls in the window", 2, (int) dials.get(RO_ID));
        } finally {
            for (int i = 0; i < sessions.length; i++) {
                if (sessions[i] != null) {
                    sessions[i].releasePhysicalConnections();
                }
            }
        }
    }

    @Test
    public void roReadPathProbesAndRestoresDespiteJciListing() throws Exception {
        final AtomicBoolean roUp = new AtomicBoolean(false);
        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(50), roGatedFactory(roUp));

        try {
            // Bind while RO is down -> roOnRw fallback, RO marked unreachable.
            mgr.bindSession(rw, ro, topo);
            assertEquals(
                    SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON,
                    mgr.getRoFallbackReason());
            assertTrue(UUnreachableHostList.getInstance().contains(RO_ID));

            // Broker back up but the JCI list still holds RO (no 60s poll ran): once the window the
            // bind failure started has passed, the probe must recover it anyway and markReachable
            // must clear the list.
            roUp.set(true);
            Thread.sleep(80);
            assertEquals(ro, mgr.restoreRoIfRecovered());
            assertEquals("NONE", mgr.getRoFallbackReason());
            assertFalse(UUnreachableHostList.getInstance().contains(RO_ID));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void roReadPathStaysSuppressedWithinBackoffWindow() throws SQLException {
        final AtomicBoolean roUp = new AtomicBoolean(false);
        // Huge interval: the window the bind failure starts outlasts the test.
        SessionPhysicalConnManager mgr =
                newManager(new RecoveryBackoff(3600000), roGatedFactory(roUp));

        try {
            mgr.bindSession(rw, ro, topo);

            // The bind failure started the window, so no probe -> stays roOnRw.
            assertNull(mgr.restoreRoIfRecovered());
            assertEquals(
                    SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON,
                    mgr.getRoFallbackReason());

            // Broker recovers, but the window has not elapsed -> no probe, no restore yet.
            roUp.set(true);
            assertNull(mgr.restoreRoIfRecovered());
            assertTrue(UUnreachableHostList.getInstance().contains(RO_ID));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void stillDownHigherRungDoesNotStarveRecoveredLowerRung() throws Exception {
        // Regression (live: slave RO stopped + replica SO restarted): the backoff bypass lets a
        // still-down home rung through the soft gate every window; failback must fall past its
        // failed probe to the genuinely recovered lower rung instead of returning null forever.
        Endpoint bkRw = new Endpoint("bkrw", 34120);
        Endpoint ro1 = new Endpoint("bkro1", 34121); // home; stays down
        Endpoint ro2 = new Endpoint("bkro2", 34122); // recovers, but stays JCI-listed
        EndpointTopology roTopo =
                new EndpointTopology(
                        bkRw, Arrays.asList(ro1, ro2), Collections.<Endpoint>emptyList());

        final AtomicBoolean ro2Up = new AtomicBoolean(false);
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (url.contains("bkro1:34121")
                                || (url.contains("bkro2:34122") && !ro2Up.get())) {
                            throw new SQLException("ro down", null, UErrorCode.ER_COMMUNICATION);
                        }
                        return connectionProxy();
                    }
                };

        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(50), factory);
        try {
            // Both RO rungs down at bind -> roOnRw, both JCI-listed.
            mgr.bindSession(bkRw, ro1, roTopo);
            assertEquals(
                    SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON,
                    mgr.getRoFallbackReason());

            // ro2 comes back (list still stale); ro1 (the better rung) remains down. Both windows
            // the bind failures started have passed.
            ro2Up.set(true);
            Thread.sleep(80);
            assertEquals(ro2, mgr.restoreRoIfRecovered());
            assertEquals("NONE", mgr.getRoFallbackReason());
            assertFalse(UUnreachableHostList.getInstance().contains(ro2.getId()));
            assertTrue(UUnreachableHostList.getInstance().contains(ro1.getId()));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void recoverRwProbesJciListedCandidateWhenBackoffDue() throws SQLException {
        Endpoint rw1 = new Endpoint("bkrw1", 34110);
        Endpoint rw2 = new Endpoint("bkrw2", 34111);
        Endpoint bkRo = new Endpoint("bkro", 34112);
        EndpointTopology rwTopo =
                new EndpointTopology(
                        Arrays.asList(rw1, rw2),
                        Arrays.asList(bkRo),
                        Collections.<Endpoint>emptyList());

        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(3000), alwaysUpFactory());
        try {
            mgr.bindSession(rw1, bkRo, rwTopo);

            // rw2 is still on the JCI list (e.g. failed earlier, broker since restarted).
            UUnreachableHostList.getInstance().add(rw2.getId());

            PhysicalRecoveryResult result = mgr.recoverRw(rwCtx(rw1, rwTopo, null));

            assertEquals(rw2.getId(), result.getBoundEndpoint().getId());
            assertEquals(SessionLeg.RW, result.getBoundLeg());
            assertFalse(UUnreachableHostList.getInstance().contains(rw2.getId()));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void recoverRwKeepsHardExcludesDespiteBackoffDue() throws SQLException {
        Endpoint rw1 = new Endpoint("bkrw1", 34110);
        Endpoint rw2 = new Endpoint("bkrw2", 34111);
        Endpoint bkRo = new Endpoint("bkro", 34112);
        EndpointTopology rwTopo =
                new EndpointTopology(
                        Arrays.asList(rw1, rw2),
                        Arrays.asList(bkRo),
                        Collections.<Endpoint>emptyList());

        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(3000), alwaysUpFactory());
        try {
            mgr.bindSession(rw1, bkRo, rwTopo);
            UUnreachableHostList.getInstance().add(rw2.getId());

            // rw2 backoff-due but hard-excluded via ctx -> recovery must not use it.
            try {
                mgr.recoverRw(rwCtx(rw1, rwTopo, Collections.singleton(rw2.getId())));
                fail("expected recovery exhaustion: both RW endpoints are hard-excluded");
            } catch (SQLException expected) {
                // rw1 = failed, rw2 = ctx-excluded
            }
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    // 03 M-3: single-RW topology - the just-failed endpoint is the only RW candidate.
    // Hard-excluding it would leave no candidate, so recovery would always throw
    // brokerCandidatesFiltered and strand RW until a pool rebind, even after a transient blip.
    // One reconnection attempt to the (recovered) master must be allowed.
    @Test
    public void recoverRwReattemptsSoleEndpointInSingleRwTopology() throws SQLException {
        Endpoint rw1 = new Endpoint("solorw", 34120);
        Endpoint bkRo = new Endpoint("soloro", 34121);
        EndpointTopology rwTopo =
                new EndpointTopology(
                        Arrays.asList(rw1), Arrays.asList(bkRo), Collections.<Endpoint>emptyList());

        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(3000), alwaysUpFactory());
        try {
            mgr.bindSession(rw1, bkRo, rwTopo);

            PhysicalRecoveryResult result = mgr.recoverRw(rwCtx(rw1, rwTopo, null));

            assertEquals(rw1.getId(), result.getBoundEndpoint().getId());
            assertEquals(SessionLeg.RW, result.getBoundLeg());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    /**
     * Three RW endpoints, the first live sibling also down: recovery must reach the second sibling.
     *
     * <p>RW candidates are ONE group, so the per-group attempt budget (default 1) used to allow a
     * single dial per recovery — spent on rw2, leaving rw3 untried and the recovery exhausted while
     * a working RW broker was up. Unlike RO there is no tier split to hand out a fresh budget and
     * no {@code roOnRw} bottom rung, and a failed write is not re-executed, so that exception
     * reaches the application. Invisible on a 2-RW cluster: there the budget has no third candidate
     * to cut off.
     *
     * <p>rw2 must fail to CONNECT while staying OFF the unreachable list — an excluded candidate is
     * skipped without consuming a dial, which would let the capped code pass by accident.
     */
    @Test
    public void recoverRwReachesTheSecondSiblingWhenTheFirstIsAlsoDown() throws SQLException {
        Endpoint rw1 = new Endpoint("trirw1", 34140);
        Endpoint rw2 = new Endpoint("trirw2", 34141);
        Endpoint rw3 = new Endpoint("trirw3", 34142);
        Endpoint triRo = new Endpoint("triro", 34143);
        EndpointTopology rwTopo =
                new EndpointTopology(
                        Arrays.asList(rw1, rw2, rw3),
                        Arrays.asList(triRo),
                        Collections.<Endpoint>emptyList());

        Set<String> down = new HashSet<String>();
        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(3000), downFactory(down));
        try {
            mgr.bindSession(rw1, triRo, rwTopo);

            down.add(rw1.getId());
            down.add(rw2.getId());

            PhysicalRecoveryResult result = mgr.recoverRw(rwCtx(rw1, rwTopo, null));

            assertEquals(rw3.getId(), result.getBoundEndpoint().getId());
            assertEquals(SessionLeg.RW, result.getBoundLeg());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void recoverRoProbesJciListedCandidateWhenBackoffDue() throws SQLException {
        Endpoint bkRw = new Endpoint("bkrw", 34120);
        Endpoint ro1 = new Endpoint("bkro1", 34121);
        Endpoint ro2 = new Endpoint("bkro2", 34122);
        EndpointTopology roTopo =
                new EndpointTopology(
                        bkRw, Arrays.asList(ro1, ro2), Collections.<Endpoint>emptyList());

        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(3000), alwaysUpFactory());
        try {
            mgr.bindSession(bkRw, ro1, roTopo);
            UUnreachableHostList.getInstance().add(ro2.getId());

            PhysicalRecoveryResult result = mgr.recoverRo(roCtx(ro1, roTopo));

            assertEquals(ro2.getId(), result.getBoundEndpoint().getId());
            assertEquals(SessionLeg.RO, result.getBoundLeg());
            assertFalse(UUnreachableHostList.getInstance().contains(ro2.getId()));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void recoverRoFallsBackToRwWhenProbeFails() throws SQLException {
        final Endpoint bkRw = new Endpoint("bkrw", 34120);
        Endpoint ro1 = new Endpoint("bkro1", 34121);
        Endpoint ro2 = new Endpoint("bkro2", 34122);
        EndpointTopology roTopo =
                new EndpointTopology(
                        bkRw, Arrays.asList(ro1, ro2), Collections.<Endpoint>emptyList());

        // RO brokers connect at bind time, then go down for the recovery attempt.
        final AtomicBoolean roDown = new AtomicBoolean(false);
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (roDown.get() && !url.contains("bkrw:34120")) {
                            throw new SQLException("ro down", null, UErrorCode.ER_COMMUNICATION);
                        }
                        return connectionProxy();
                    }
                };

        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(3000), factory);
        try {
            mgr.bindSession(bkRw, ro1, roTopo);
            UUnreachableHostList.getInstance().add(ro2.getId());
            roDown.set(true);

            // ro2 is backoff-due so it IS probed, but the probe fails -> the pre-existing
            // roOnRw fallback must still engage.
            PhysicalRecoveryResult result = mgr.recoverRo(roCtx(ro1, roTopo));

            assertEquals(bkRw.getId(), result.getBoundEndpoint().getId());
            assertEquals(SessionLeg.RW, result.getBoundLeg());
            assertEquals(
                    SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON,
                    result.getFallbackReason());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void recoverRoKeepsHardExcludesDespiteBackoffDue() throws SQLException {
        Endpoint bkRw = new Endpoint("bkrw", 34120);
        Endpoint ro1 = new Endpoint("bkro1", 34121);
        Endpoint ro2 = new Endpoint("bkro2", 34122);
        EndpointTopology roTopo =
                new EndpointTopology(
                        bkRw, Arrays.asList(ro1, ro2), Collections.<Endpoint>emptyList());

        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(3000), alwaysUpFactory());
        try {
            mgr.bindSession(bkRw, ro1, roTopo);
            UUnreachableHostList.getInstance().add(ro2.getId());

            // ro2 backoff-due but ctx-hard-excluded -> must not be used; RO group is exhausted
            // (ro1 = failed) so the roOnRw fallback engages instead.
            PhysicalRecoveryResult result =
                    mgr.recoverRo(
                            new PhysicalRecoveryContext(
                                    SessionLeg.RO,
                                    ro1,
                                    RouteTarget.TO_READ_ONLY,
                                    roTopo,
                                    Collections.singleton(ro2.getId()),
                                    false));

            assertEquals(bkRw.getId(), result.getBoundEndpoint().getId());
            assertEquals(SessionLeg.RW, result.getBoundLeg());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void txActiveStillForbidsRecoveryEvenWhenBackoffDue() throws SQLException {
        Endpoint rw1 = new Endpoint("bkrw1", 34110);
        Endpoint rw2 = new Endpoint("bkrw2", 34111);
        Endpoint bkRo = new Endpoint("bkro", 34112);
        EndpointTopology rwTopo =
                new EndpointTopology(
                        Arrays.asList(rw1, rw2),
                        Arrays.asList(bkRo),
                        Collections.<Endpoint>emptyList());

        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(3000), alwaysUpFactory());
        try {
            mgr.bindSession(rw1, bkRo, rwTopo);
            // Backoff-due candidates exist, but the tx guard must still fire first.
            UUnreachableHostList.getInstance().add(rw2.getId());

            try {
                mgr.recoverRw(
                        new PhysicalRecoveryContext(
                                SessionLeg.RW,
                                rw1,
                                RouteTarget.TO_READ_WRITE,
                                rwTopo,
                                Collections.<String>emptySet(),
                                true));
                fail("expected recoverRw to be forbidden during an active transaction");
            } catch (SQLException expected) {
            }

            try {
                mgr.recoverRo(
                        new PhysicalRecoveryContext(
                                SessionLeg.RO,
                                bkRo,
                                RouteTarget.TO_READ_ONLY,
                                rwTopo,
                                Collections.<String>emptySet(),
                                true));
                fail("expected recoverRo to be forbidden during an active transaction");
            } catch (SQLException expected) {
            }

            // The tx guard fired before any probe: rw2 must still be listed.
            assertTrue(UUnreachableHostList.getInstance().contains(rw2.getId()));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void oneSessionRestoreImmediatelyPropagatesToOtherSessions() throws Exception {
        final AtomicBoolean roUp = new AtomicBoolean(false);
        // A backoff per session. B binds after A has listed RO, so B's bind spends B's claim on a
        // probe while RO is still down and starts a window that outlasts the test; B can then come
        // back only through A clearing the list once A's short window has passed.
        SessionPhysicalConnManager mgrA = newManager(new RecoveryBackoff(50), roGatedFactory(roUp));
        SessionPhysicalConnManager mgrB =
                newManager(new RecoveryBackoff(3600000), roGatedFactory(roUp));

        try {
            // Both sessions bind while RO is down -> both roOnRw, RO listed.
            mgrA.bindSession(rw, ro, topo);
            mgrB.bindSession(rw, ro, topo);
            assertEquals(
                    SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON,
                    mgrB.getRoFallbackReason());

            roUp.set(true);
            Thread.sleep(80);

            // Session A claims its probe and restores -> markReachable clears the list.
            assertEquals(ro, mgrA.restoreRoIfRecovered());
            assertFalse(UUnreachableHostList.getInstance().contains(RO_ID));

            // Session B cannot probe (its claim is spent, the window is huge) yet restores
            // immediately on its next read because the endpoint is no longer listed: the
            // propagation path.
            assertEquals(ro, mgrB.restoreRoIfRecovered());
            assertEquals("NONE", mgrB.getRoFallbackReason());
        } finally {
            mgrA.releasePhysicalConnections();
            mgrB.releasePhysicalConnections();
        }
    }

    @Test
    public void singleSessionRecoversWithinOneRealTimeInterval() throws SQLException {
        final AtomicBoolean roUp = new AtomicBoolean(false);
        // Real-clock integration of the recovery paths (they pass System.nanoTime()).
        final long intervalMs = 50;
        SessionPhysicalConnManager mgr =
                newManager(new RecoveryBackoff(intervalMs), roGatedFactory(roUp));

        try {
            mgr.bindSession(rw, ro, topo);
            assertNull(mgr.restoreRoIfRecovered()); // still down: held, or a failed probe

            roUp.set(true);
            long deadline = System.nanoTime() + 2000 * MS; // generous CI bound (>> interval)
            Endpoint restored = null;
            while (restored == null && System.nanoTime() < deadline) {
                restored = mgr.restoreRoIfRecovered();
            }

            assertEquals(ro, restored);
            assertEquals("NONE", mgr.getRoFallbackReason());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    // M12: a new session must reach a broker that is back but still listed. The bind path used to
    // hard-exclude the list and throw -23010 without dialing until the 60s JCI poll cleared it.
    @Test
    public void bindRwOnlyDialsListedRwOnceItsProbeIsDue() throws SQLException {
        UUnreachableHostList.getInstance().add(RW_ID);
        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(3000), alwaysUpFactory());
        try {
            mgr.bindSessionRwOnly(rw, topo);

            assertEquals(rw, mgr.getSessionEndpoint(SessionLeg.RW));
            assertFalse(UUnreachableHostList.getInstance().contains(RW_ID));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    // M12: one caller per window wins the probe; the other new sessions in that window must still
    // dial the listed RW once more, as core's reconnect() does, instead of failing undialed.
    @Test
    public void bindRwOnlyDialsListedRwWhileAnotherCallerHoldsTheProbe() throws SQLException {
        UUnreachableHostList.getInstance().add(RW_ID);
        RecoveryBackoff backoff = new RecoveryBackoff(3600000);
        assertTrue(backoff.tryClaimProbe(RW_ID, System.nanoTime()));
        SessionPhysicalConnManager mgr = newManager(backoff, alwaysUpFactory());
        try {
            mgr.bindSessionRwOnly(rw, topo);

            assertEquals(rw, mgr.getSessionEndpoint(SessionLeg.RW));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    // M12 with m38: the RW step must not spend the RO endpoint's probe, or the RO step finds it
    // held and puts the read leg on RW although the read broker is up.
    @Test
    public void bindSessionDialsListedRwAndRoOnceTheirProbesAreDue() throws SQLException {
        UUnreachableHostList.getInstance().add(RW_ID);
        UUnreachableHostList.getInstance().add(RO_ID);
        SessionPhysicalConnManager mgr = newManager(new RecoveryBackoff(3000), alwaysUpFactory());
        try {
            mgr.bindSession(rw, ro, topo);

            assertEquals(rw, mgr.getSessionEndpoint(SessionLeg.RW));
            assertEquals("NONE", mgr.getRoFallbackReason());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    // The read leg has roOnRw to fall back on, so the bind does not dial a listed RO whose probe
    // another caller holds; a down read broker would otherwise slow every getConnection().
    @Test
    public void bindSessionPutsReadsOnRwWithoutDialingListedRoWhoseProbeIsHeld()
            throws SQLException {
        UUnreachableHostList.getInstance().add(RO_ID);
        RecoveryBackoff backoff = new RecoveryBackoff(3600000);
        assertTrue(backoff.tryClaimProbe(RO_ID, System.nanoTime()));
        final AtomicInteger roDials = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        if (url.contains(RO_ID)) {
                            roDials.incrementAndGet();
                        }
                        return connectionProxy();
                    }
                };
        SessionPhysicalConnManager mgr = newManager(backoff, factory);
        try {
            mgr.bindSession(rw, ro, topo);

            assertEquals(
                    SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON,
                    mgr.getRoFallbackReason());
            assertEquals(0, roDials.get());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    // m38: recoverRw dials only RW endpoints, so it must leave a listed read endpoint's probe to
    // the read path instead of spending that window on a probe nobody makes.
    @Test
    public void recoverRwLeavesTheProbeOfAListedReadEndpoint() throws SQLException {
        Endpoint rw1 = new Endpoint("bkrw1", 34110);
        Endpoint rw2 = new Endpoint("bkrw2", 34111);
        Endpoint bkRo = new Endpoint("bkro", 34112);
        EndpointTopology rwTopo =
                new EndpointTopology(
                        Arrays.asList(rw1, rw2),
                        Arrays.asList(bkRo),
                        Collections.<Endpoint>emptyList());
        RecoveryBackoff backoff = new RecoveryBackoff(3600000);

        SessionPhysicalConnManager mgr = newManager(backoff, alwaysUpFactory());
        try {
            mgr.bindSession(rw1, bkRo, rwTopo);
            UUnreachableHostList.getInstance().add(bkRo.getId());

            mgr.recoverRw(rwCtx(rw1, rwTopo, null));

            assertTrue(backoff.tryClaimProbe(bkRo.getId(), System.nanoTime()));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    private static PhysicalRecoveryContext rwCtx(
            final Endpoint failed, final EndpointTopology topology, final Set<String> exclude) {
        return new PhysicalRecoveryContext(
                SessionLeg.RW,
                failed,
                RouteTarget.TO_READ_WRITE,
                topology,
                exclude == null ? Collections.<String>emptySet() : exclude,
                false);
    }

    private static PhysicalRecoveryContext roCtx(
            final Endpoint failed, final EndpointTopology topology) {
        return new PhysicalRecoveryContext(
                SessionLeg.RO,
                failed,
                RouteTarget.TO_READ_ONLY,
                topology,
                Collections.<String>emptySet(),
                false);
    }

    private static SessionPhysicalConnManager newManager(
            final RecoveryBackoff backoff, final JdbcConnectionFactory factory) {
        LoadBalanceSettings config = LbTestSettings.defaults();

        return new SessionPhysicalConnManager(
                LOGICAL_URL, new Properties(), config, factory, backoff);
    }

    private static JdbcConnectionFactory roGatedFactory(final AtomicBoolean roUp) {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info)
                    throws SQLException {
                if (url.contains("softro:34101") && !roUp.get()) {
                    throw new SQLException("ro down", null, UErrorCode.ER_COMMUNICATION);
                }
                return connectionProxy();
            }
        };
    }

    /** Connects to every endpoint except the ones listed in {@code down} (a CONNECT failure). */
    private static JdbcConnectionFactory downFactory(final Set<String> down) {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info)
                    throws SQLException {
                for (String epId : down) {
                    if (url.contains(":" + epId + ":")) {
                        throw new SQLException("down: " + epId, null, UErrorCode.ER_COMMUNICATION);
                    }
                }
                return connectionProxy();
            }
        };
    }

    /** Counts dials per {@code host:port}; a {@code down} one fails after {@code failAfterMs}. */
    private static JdbcConnectionFactory countingFactory(
            final Set<String> down, final Map<String, Integer> dials, final long failAfterMs) {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info)
                    throws SQLException {
                final String[] parts = url.split(":");
                final String epId = parts[2] + ":" + parts[3];
                synchronized (dials) {
                    final Integer n = dials.get(epId);
                    dials.put(epId, n == null ? 1 : n + 1);
                }
                if (down.contains(epId)) {
                    try {
                        Thread.sleep(failAfterMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new SQLException("down: " + epId, null, UErrorCode.ER_TIMEOUT);
                }
                return connectionProxy();
            }
        };
    }

    private static JdbcConnectionFactory alwaysUpFactory() {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info) {
                return connectionProxy();
            }
        };
    }

    private static Connection connectionProxy() {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
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
