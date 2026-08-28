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

package cubrid.jdbc.lb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import cubrid.jdbc.lb.failover.PhysicalRecoveryContext;
import cubrid.jdbc.lb.failover.PhysicalRecoveryResult;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class LoadBalanceConnectionRecoverPhyBindingTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";

    @Test
    public void recoverPhyBindingThrowsOriginalWhenFailoverDisabled() throws SQLException {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        cfg.setProperty(LoadBalanceSettings.KEY_RT_FAILOVER_ENABLED, "false");
        LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        LoadBalanceConnection conn = newConnection(config, alwaysOpenFactory());
        EndpointTopology topo = topology("rw", "ro1");
        conn.initSessionBindings(topo);

        SQLException original = new SQLException("comm fail", null, UErrorCode.ER_COMMUNICATION);
        PhysicalRecoveryContext ctx =
                new PhysicalRecoveryContext(
                        SessionLeg.RO,
                        new Endpoint("ro1", 33000),
                        Router.RouteTarget.TO_READ_ONLY,
                        topo,
                        Collections.<String>emptySet(),
                        false);

        try {
            conn.recoverPhyBinding(ctx, original);
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertSame(original, e);
        }
        conn.close();
    }

    @Test
    public void recoverPhyBindingThrowsOriginalDuringActiveTransaction() throws SQLException {
        LoadBalanceConnection conn =
                newConnection(LoadBalanceSettings.of(sessionProperties()), alwaysOpenFactory());
        EndpointTopology topo = topology("rw", "ro1");
        conn.initSessionBindings(topo);
        conn.setAutoCommit(false);

        SQLException original = new SQLException("comm fail", null, UErrorCode.ER_COMMUNICATION);
        PhysicalRecoveryContext ctx =
                new PhysicalRecoveryContext(
                        SessionLeg.RW,
                        new Endpoint("rw", 33000),
                        Router.RouteTarget.TO_READ_WRITE,
                        topo,
                        Collections.<String>emptySet(),
                        true);

        try {
            conn.recoverPhyBinding(ctx, original);
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertSame(original, e);
        }
        conn.close();
    }

    @Test
    public void recoverPhyBindingRecoversRoEndpoint() throws SQLException {
        Endpoint rw = new Endpoint("rw", 33000);
        Endpoint ro1 = new Endpoint("ro1", 33001);
        Endpoint ro2 = new Endpoint("ro2", 33001);
        EndpointTopology topo =
                new EndpointTopology(
                        rw, Arrays.asList(ro1, ro2), Collections.<Endpoint>emptyList());

        final AtomicInteger openCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        int n = openCalls.incrementAndGet();
                        if (n <= 2) {
                            return connectionProxy();
                        }
                        if (url.contains("ro1:33001")) {
                            throw new SQLException("ro1 down");
                        }
                        return connectionProxy();
                    }
                };

        LoadBalanceConnection conn =
                newConnection(LoadBalanceSettings.of(sessionProperties()), factory);
        conn.initSessionBindings(topo);

        SQLException original = new SQLException("comm fail", null, UErrorCode.ER_COMMUNICATION);
        PhysicalRecoveryContext ctx =
                new PhysicalRecoveryContext(
                        SessionLeg.RO,
                        ro1,
                        Router.RouteTarget.TO_READ_ONLY,
                        topo,
                        Collections.<String>emptySet(),
                        false);

        PhysicalRecoveryResult result = conn.recoverPhyBinding(ctx, original);
        assertEquals("ro2:33001", result.getBoundEndpoint().getId());
        assertEquals(ro2, conn.getCurrentRoEndpoint());
        conn.close();
    }

    @Test
    public void rebindWaitsWhileRecoverPhyBindingHoldsRecoveryLock() throws Exception {
        Endpoint rw = new Endpoint("rw", 33000);
        Endpoint ro1 = new Endpoint("ro1", 33001);
        Endpoint ro2 = new Endpoint("ro2", 33001);
        EndpointTopology topo =
                new EndpointTopology(
                        rw, Arrays.asList(ro1, ro2), Collections.<Endpoint>emptyList());
        final EndpointTopology rebindTopo = topo;

        final CountDownLatch recoveryInsideLock = new CountDownLatch(1);
        final CountDownLatch allowRecoveryToFinish = new CountDownLatch(1);
        final AtomicBoolean recoveryThreadStarted = new AtomicBoolean();

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (recoveryThreadStarted.get() && url.contains("ro2:33001")) {
                            recoveryInsideLock.countDown();
                            try {
                                if (!allowRecoveryToFinish.await(5, TimeUnit.SECONDS)) {
                                    throw new SQLException("recovery wait timed out");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new SQLException("interrupted during recovery", e);
                            }
                        }
                        if (url.contains("ro1:33001")) {
                            throw new SQLException("ro1 down");
                        }
                        return connectionProxy();
                    }
                };

        LoadBalanceConnection conn =
                newConnection(LoadBalanceSettings.of(sessionProperties()), factory);
        conn.initSessionBindings(topo);

        final SQLException original =
                new SQLException("comm fail", null, UErrorCode.ER_COMMUNICATION);
        final PhysicalRecoveryContext ctx =
                new PhysicalRecoveryContext(
                        SessionLeg.RO,
                        ro1,
                        Router.RouteTarget.TO_READ_ONLY,
                        topo,
                        Collections.<String>emptySet(),
                        false);

        final LoadBalanceConnection sharedConn = conn;
        Thread recoveryThread =
                new Thread(
                        new Runnable() {
                            public void run() {
                                recoveryThreadStarted.set(true);
                                try {
                                    sharedConn.recoverPhyBinding(ctx, original);
                                } catch (SQLException ignored) {
                                }
                            }
                        },
                        "recovery-thread");
        recoveryThread.start();

        assertTrue(recoveryInsideLock.await(5, TimeUnit.SECONDS));

        // Second actor: close(). It opens nothing of its own, so if it completes while the recovery
        // is still parked inside the connection factory, nothing serializes the two, and close()
        // tears down the very endpoint fields and physical connections the recovery is mid-way
        // through rewriting. The serialization comes from the session monitor
        // (releasePhysicalConnections) and
        // recoveryLock together; this asserts the behaviour, not which of the two got there first.
        final AtomicBoolean closeCompleted = new AtomicBoolean();
        Thread closeThread =
                new Thread(
                        new Runnable() {
                            public void run() {
                                try {
                                    sharedConn.close();
                                } catch (SQLException ignored) {
                                }
                                closeCompleted.set(true);
                            }
                        },
                        "close-thread");
        closeThread.start();

        Thread.sleep(200);
        assertTrue("close() must not run while a recovery holds the lock", !closeCompleted.get());

        allowRecoveryToFinish.countDown();
        recoveryThread.join(5000);
        closeThread.join(5000);

        assertTrue(closeCompleted.get());
        conn.close();
    }

    @Test
    public void initSessionBindingsStoresTopologyForRecovery() throws SQLException {
        LoadBalanceConnection conn =
                newConnection(LoadBalanceSettings.of(sessionProperties()), alwaysOpenFactory());
        EndpointTopology topo = topology("rw", "ro1");
        conn.initSessionBindings(topo);
        assertSame(topo, conn.getSessionTopology());
        conn.close();
    }

    /**
     * Regression (03 §M-1): buildRecoveryCtx must NOT fold the JCI unreachable set into the ctx's
     * hard exclude. recoverRw/recoverRo already apply that set softly via softExcludedIds(), so
     * hard-excluding it here would re-add every backoff-eligible endpoint and defeat the
     * backoff-bypass probe. The ctx exclude carries only caller-specific excludes (none here).
     */
    @Test
    public void buildRecoveryCtxDoesNotHardExcludeJciUnreachableSet() throws SQLException {
        Endpoint ro1 = new Endpoint("ro1", 33000);
        EndpointTopology topo =
                new EndpointTopology(
                        new Endpoint("rw", 33000),
                        Arrays.asList(ro1),
                        Collections.<Endpoint>emptyList());

        LoadBalanceConnection conn =
                newConnection(LoadBalanceSettings.of(sessionProperties()), alwaysOpenFactory());
        conn.initSessionBindings(topo);

        // Simulate JCI having marked ro1 unreachable (global list keyed by "host:port").
        UUnreachableHostList.getInstance().add(ro1.getId());
        try {
            PhysicalRecoveryContext ctx =
                    conn.buildRecoveryCtx(Router.RouteTarget.TO_READ_ONLY, ro1);
            assertFalse(
                    "JCI unreachable endpoint must not leak into ctx hard exclude",
                    ctx.getExcludeEndpointIds().contains(ro1.getId()));
            assertTrue(ctx.getExcludeEndpointIds().isEmpty());
        } finally {
            UUnreachableHostList.getInstance().remove(ro1.getId());
            conn.close();
        }
    }

    /**
     * Regression : a physical connection opened by failover recovery must inherit the session
     * state. Runtime recovery only runs outside a transaction (autocommit here, since a
     * manual-commit session stays transaction-pinned -- 04 §C-1), so this asserts a non-autoCommit
     * property: after the session sets SERIALIZABLE, the recovered RO connection must be
     * SERIALIZABLE rather than the driver default, or isolation silently drifts on failover.
     */
    @Test
    public void recoveredRoConnectionInheritsSessionTransactionIsolation() throws SQLException {
        Endpoint rw = new Endpoint("rw", 33000);
        Endpoint ro1 = new Endpoint("ro1", 33001);
        Endpoint ro2 = new Endpoint("ro2", 33001);
        EndpointTopology topo =
                new EndpointTopology(
                        rw, Arrays.asList(ro1, ro2), Collections.<Endpoint>emptyList());

        final AtomicInteger openCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        int n = openCalls.incrementAndGet();
                        if (n > 2 && url.contains("ro1:33001")) {
                            throw new SQLException("ro1 down");
                        }
                        return isolationTrackingProxy();
                    }
                };

        LoadBalanceConnection conn =
                newConnection(LoadBalanceSettings.of(sessionProperties()), factory);
        conn.initSessionBindings(topo);
        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        SQLException original = new SQLException("comm fail", null, UErrorCode.ER_COMMUNICATION);
        PhysicalRecoveryContext ctx =
                new PhysicalRecoveryContext(
                        SessionLeg.RO,
                        ro1,
                        Router.RouteTarget.TO_READ_ONLY,
                        topo,
                        Collections.<String>emptySet(),
                        false);

        PhysicalRecoveryResult result = conn.recoverPhyBinding(ctx, original);
        assertEquals("ro2:33001", result.getBoundEndpoint().getId());
        assertEquals(
                "recovered RO connection must inherit session transaction isolation",
                Connection.TRANSACTION_SERIALIZABLE,
                conn.getConnectionManager().getPhyConn(ro2).getTransactionIsolation());
        conn.close();
    }

    /**
     * Regression (01 §M-5): in manual-commit mode a read routed to a separate RO physical
     * connection opens a transaction there (autoCommit=false). The logical commit()/rollback() must
     * terminate that RO transaction too, or it lingers on the slave for the session's lifetime.
     */
    @Test
    public void manualCommitAlsoCommitsSeparateRoPhysicalConnection() throws SQLException {
        Endpoint rw = new Endpoint("rw", 33000);
        Endpoint ro1 = new Endpoint("ro1", 33001);
        EndpointTopology topo =
                new EndpointTopology(rw, Arrays.asList(ro1), Collections.<Endpoint>emptyList());

        final java.util.Map<String, AtomicInteger> commitsByUrl =
                new java.util.HashMap<String, AtomicInteger>();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        AtomicInteger commits = new AtomicInteger();
                        commitsByUrl.put(url, commits);
                        return commitCountingProxy(commits);
                    }
                };

        LoadBalanceConnection conn =
                newConnection(LoadBalanceSettings.of(sessionProperties()), factory);
        conn.initSessionBindings(topo);
        conn.setAutoCommit(false);
        conn.commit();

        int roCommits = 0;
        for (java.util.Map.Entry<String, AtomicInteger> e : commitsByUrl.entrySet()) {
            if (e.getKey().contains("ro1")) {
                roCommits += e.getValue().get();
            }
        }
        assertTrue(
                "separate RO physical connection must be committed on logical commit",
                roCommits >= 1);
        conn.close();
    }

    private static Connection commitCountingProxy(final AtomicInteger commitCount) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                String name = method.getName();
                                if ("commit".equals(name)) {
                                    commitCount.incrementAndGet();
                                    return null;
                                }
                                if ("isClosed".equals(name)) {
                                    return Boolean.FALSE;
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

    private static Connection isolationTrackingProxy() {
        final AtomicInteger isolation =
                new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED); // driver default
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                String name = method.getName();
                                if ("setTransactionIsolation".equals(name)) {
                                    isolation.set(((Integer) args[0]).intValue());
                                    return null;
                                }
                                if ("getTransactionIsolation".equals(name)) {
                                    return Integer.valueOf(isolation.get());
                                }
                                if ("isClosed".equals(name)) {
                                    return Boolean.FALSE;
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

    private static Properties sessionProperties() {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        return cfg;
    }

    private static EndpointTopology topology(final String rwHost, final String roHost) {
        return new EndpointTopology(
                new Endpoint(rwHost, 33000),
                Arrays.asList(new Endpoint(roHost, 33000)),
                Collections.<Endpoint>emptyList());
    }

    private static LoadBalanceConnection newConnection(
            final LoadBalanceSettings config, final JdbcConnectionFactory factory)
            throws SQLException {
        LoadBalanceConnection conn = new LoadBalanceConnection(config);
        conn.setConnectionManager(
                new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), config, factory));
        conn.setSharedSelectorState(new SharedSelectorState());
        return conn;
    }

    private static JdbcConnectionFactory alwaysOpenFactory() {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info)
                    throws SQLException {
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
                                if ("close".equals(method.getName())) {
                                    return null;
                                }
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.FALSE;
                                }
                                if ("setAutoCommit".equals(method.getName())) {
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
