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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.CUBRIDException;
import cubrid.jdbc.lb.LbJDBCErrorCode;
import cubrid.jdbc.lb.SessionLeg;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.failover.PhysicalRecoveryContext;
import cubrid.jdbc.lb.failover.PhysicalRecoveryResult;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.statement.PreparedSql;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class JdbcBrokerConnectionManagerTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";

    @Test
    public void bindSessionOpensTwoConnectionsWithBrokerHostsInUrls() throws SQLException {
        final List<String> urls = new ArrayList<String>();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        urls.add(url);
                        return newConnectionProxy(new AtomicInteger());
                    }
                };
        LoadBalanceSettings cfg = sessionConfig();
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), cfg, factory);
        Endpoint rw = new Endpoint("rw-b", 33000);
        Endpoint ro = new Endpoint("ro-b", 33001);
        mgr.bindSession(rw, ro);
        assertEquals(2, urls.size());
        assertTrue(urls.get(0).contains("rw-b:33000"));
        assertTrue(urls.get(1).contains("ro-b:33001"));
        assertEquals(rw, mgr.getSessionEndpoint(SessionLeg.RW));
        assertEquals(ro, mgr.getSessionEndpoint(SessionLeg.RO));
        mgr.releasePhysicalConnections();
    }

    @Test
    public void bindSessionFailsOverRwWhenPreferredUnreachable() throws SQLException {
        Endpoint badRw = new Endpoint("bad-rw", 30000);
        Endpoint okRw = new Endpoint("ok-rw", 30000);
        Endpoint ro = new Endpoint("ro", 33000);
        List<Endpoint> rws = new ArrayList<Endpoint>();
        rws.add(badRw);
        rws.add(okRw);
        List<Endpoint> ros = new ArrayList<Endpoint>();
        ros.add(ro);
        EndpointTopology topo = new EndpointTopology(rws, ros, new ArrayList<Endpoint>());
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (url.contains("bad-rw:30000")) {
                            throw new SQLException("bad rw down");
                        }
                        return newConnectionProxy(new AtomicInteger());
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfigFailoverTopology(), factory);
        mgr.bindSession(badRw, ro, topo);
        assertEquals(okRw, mgr.getSessionEndpoint(SessionLeg.RW));
        assertEquals(ro, mgr.getSessionEndpoint(SessionLeg.RO));
        mgr.releasePhysicalConnections();
    }

    @Test
    public void bindSessionFailsOverRoWhenPreferredUnreachable() throws SQLException {
        Endpoint rw = new Endpoint("rw", 30000);
        Endpoint badRo = new Endpoint("ro-bad", 33000);
        Endpoint okRo = new Endpoint("ro-ok", 33001);
        List<Endpoint> rws = new ArrayList<Endpoint>();
        rws.add(rw);
        List<Endpoint> ros = new ArrayList<Endpoint>();
        ros.add(badRo);
        ros.add(okRo);
        EndpointTopology topo = new EndpointTopology(rws, ros, new ArrayList<Endpoint>());
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (url.contains("ro-bad:33000")) {
                            throw new SQLException("bad ro down");
                        }
                        return newConnectionProxy(new AtomicInteger());
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfigFailoverTopology(), factory);
        mgr.bindSession(rw, badRo, topo);
        assertEquals(rw, mgr.getSessionEndpoint(SessionLeg.RW));
        assertEquals(okRo, mgr.getSessionEndpoint(SessionLeg.RO));
        mgr.releasePhysicalConnections();
    }

    @Test
    public void bindSessionThrowsWhenAllRwUnreachable() throws SQLException {
        Endpoint rw1 = new Endpoint("w1", 1);
        Endpoint rw2 = new Endpoint("w2", 2);
        Endpoint ro = new Endpoint("r", 3);
        List<Endpoint> rws = new ArrayList<Endpoint>();
        rws.add(rw1);
        rws.add(rw2);
        List<Endpoint> ros = new ArrayList<Endpoint>();
        ros.add(ro);
        EndpointTopology topo = new EndpointTopology(rws, ros, new ArrayList<Endpoint>());
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        throw new SQLException("unreachable");
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfigFailoverTopology(), factory);
        try {
            mgr.bindSession(rw1, ro, topo);
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e instanceof CUBRIDException);
            assertEquals(LbJDBCErrorCode.lb_broker_group_exhausted, e.getErrorCode());
            assertTrue(e.getMessage().contains("group=RW"));
        }
        mgr.releasePhysicalConnections();
    }

    @Test
    public void releasePhysicalConnectionsClosesOpenedConnections() throws SQLException {
        final AtomicInteger closeCount = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        return newConnectionProxy(closeCount);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        mgr.bindSession(new Endpoint("m", 1), new Endpoint("r", 2));
        mgr.releasePhysicalConnections();
        assertEquals(2, closeCount.get());
    }

    @Test
    public void bindSessionUsesRwPhysicalWhenRoFailsWithDefaultFailover() throws SQLException {
        final AtomicInteger openCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (openCalls.incrementAndGet() == 2) {
                            throw new SQLException("ro physical unavailable");
                        }
                        return newConnectionProxy(new AtomicInteger());
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        Endpoint m = new Endpoint("m", 3);
        Endpoint r = new Endpoint("r", 4);
        mgr.bindSession(m, r);
        assertEquals(
                SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON, mgr.getRoFallbackReason());
        mgr.releasePhysicalConnections();
    }

    @Test
    public void bindSessionThrowsWhenRoPhysicalOpenFailsWithoutFailover() throws SQLException {
        final AtomicInteger openCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (openCalls.incrementAndGet() == 2) {
                            throw new SQLException("ro physical unavailable");
                        }
                        return newConnectionProxy(new AtomicInteger());
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfigWithRoFailover(false), factory);
        try {
            mgr.bindSession(new Endpoint("m", 1), new Endpoint("r", 2));
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(
                    e.getMessage().contains("ro physical unavailable")
                            || (e.getCause() != null
                                    && e.getCause().getMessage() != null
                                    && e.getCause()
                                            .getMessage()
                                            .contains("ro physical unavailable")));
        }
        mgr.releasePhysicalConnections();
    }

    @Test
    public void bindSessionUsesRwPhysicalWhenRoFailsWithFailover() throws SQLException {
        final AtomicInteger openCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (openCalls.incrementAndGet() == 2) {
                            throw new SQLException("ro physical unavailable");
                        }
                        return newConnectionProxy(new AtomicInteger());
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfigWithRoFailover(true), factory);
        Endpoint m = new Endpoint("m", 3);
        Endpoint r = new Endpoint("r", 4);
        mgr.bindSession(m, r);
        assertEquals(
                SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON, mgr.getRoFallbackReason());
        assertNotNull(mgr.prepareStatement(m, "SELECT 1"));
        assertNotNull(mgr.prepareStatement(r, "SELECT 1"));
        mgr.releasePhysicalConnections();
    }

    @Test
    public void releasePhysicalConnectionsClosesSharedConnectionOnceWhenRoFailoverReusesRw()
            throws SQLException {
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicInteger openCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (openCalls.incrementAndGet() == 2) {
                            throw new SQLException("ro down");
                        }
                        return newConnectionProxy(closeCount);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfigWithRoFailover(true), factory);
        mgr.bindSession(new Endpoint("m", 3), new Endpoint("r", 4));
        mgr.releasePhysicalConnections();
        assertEquals(1, closeCount.get());
    }

    @Test
    public void applyPhyAutoCommitInvokesSetAutoCommitOnBothConnections() throws SQLException {
        final AtomicInteger rwAutoCommitCalls = new AtomicInteger();
        final AtomicInteger roAutoCommitCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    private int ordinal;

                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        final boolean rwFirst = ordinal++ == 0;
                        return newConnectionProxyWithAutoCommitTracking(
                                rwFirst ? rwAutoCommitCalls : roAutoCommitCalls);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        mgr.bindSession(new Endpoint("m", 3), new Endpoint("r", 4));
        mgr.applyPhyAutoCommit(false);
        assertEquals(1, rwAutoCommitCalls.get());
        assertEquals(1, roAutoCommitCalls.get());
        mgr.applyPhyAutoCommit(true);
        assertEquals(2, rwAutoCommitCalls.get());
        assertEquals(2, roAutoCommitCalls.get());
        mgr.releasePhysicalConnections();
    }

    // 01 §M-5: a separate RO physical connection is committed together with RW, so a manual-commit
    // read transaction opened on the slave is terminated instead of lingering for the session.
    @Test
    public void commitPhyTxInvokesRwAndSeparateRoConnection() throws SQLException {
        final AtomicInteger rwCommitCalls = new AtomicInteger();
        final AtomicInteger roCommitCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    private int ordinal;

                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        final boolean rwFirst = ordinal++ == 0;
                        return newConnectionProxyWithCommitTracking(
                                rwFirst ? rwCommitCalls : roCommitCalls);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        mgr.bindSession(new Endpoint("m", 3), new Endpoint("r", 4));
        mgr.commitPhyTx();
        assertEquals(1, rwCommitCalls.get());
        assertEquals(1, roCommitCalls.get());
        mgr.releasePhysicalConnections();
    }

    @Test
    public void rollbackPhyTxInvokesRwAndSeparateRoConnection() throws SQLException {
        final AtomicInteger rwRollbackCalls = new AtomicInteger();
        final AtomicInteger roRollbackCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    private int ordinal;

                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        final boolean rwFirst = ordinal++ == 0;
                        return newConnectionProxyWithRollbackTracking(
                                rwFirst ? rwRollbackCalls : roRollbackCalls);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        mgr.bindSession(new Endpoint("m", 3), new Endpoint("r", 4));
        mgr.rollbackPhyTx();
        assertEquals(1, rwRollbackCalls.get());
        assertEquals(1, roRollbackCalls.get());
        mgr.releasePhysicalConnections();
    }

    // 02 §M3: only outer whitespace is normalized away, so SQLs differing solely by
    // leading/trailing
    // whitespace share one physical PreparedStatement, while internal-whitespace differences (which
    // may live inside string literals) map to distinct physical statements — no cache aliasing.
    @Test
    public void prepareStatementReusesCacheForOuterWhitespaceButNotInner() throws SQLException {
        final AtomicInteger prepareCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        return newConnectionProxyWithPrepare(prepareCalls);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        Endpoint m = new Endpoint("m", 3);
        Endpoint r = new Endpoint("r", 4);
        mgr.bindSession(m, r);
        // Outer-whitespace-only difference -> same key -> one physical prepare.
        mgr.prepareStatement(m, "  SELECT 1  ");
        mgr.prepareStatement(m, "SELECT 1");
        assertEquals(1, prepareCalls.get());
        // Internal-whitespace difference -> distinct key -> a second physical prepare.
        mgr.prepareStatement(m, "SELECT  1");
        assertEquals(2, prepareCalls.get());
        assertEquals("SELECT 1", PreparedSql.normalizeForCacheKey("  SELECT 1  "));
        assertEquals("SELECT  1", PreparedSql.normalizeForCacheKey("SELECT  1"));
        mgr.releasePhysicalConnections();
    }

    @Test
    public void prepareStatementReturnsStatementFromPhysicalConnection() throws SQLException {
        final AtomicInteger prepareCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        return newConnectionProxyWithPrepare(prepareCalls);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        Endpoint m = new Endpoint("m", 3);
        Endpoint r = new Endpoint("r", 4);
        mgr.bindSession(m, r);
        PreparedStatement ps1 = mgr.prepareStatement(m, "SELECT 1");
        PreparedStatement ps2 = mgr.prepareStatement(m, "SELECT 1");
        assertNotNull(ps1);
        assertEquals(1, prepareCalls.get());
        assertSame(ps1, ps2);
        mgr.releasePhysicalConnections();
    }

    // CR-3 / 02 §C2: two logical PreparedStatements (distinct owners) with the same SQL must NOT
    // share one physical PS; the same owner re-using the SQL still hits the cache.
    @Test
    public void distinctOwnersDoNotSharePhysicalPreparedStatement() throws SQLException {
        final AtomicInteger prepareCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        return newConnectionProxyWithTrackablePrepare(prepareCalls);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        Endpoint m = new Endpoint("m", 3);
        mgr.bindSession(m, new Endpoint("r", 4));

        PreparedStatement a1 = mgr.prepareStatement(m, "SELECT 1", "owner-A");
        PreparedStatement b1 = mgr.prepareStatement(m, "SELECT 1", "owner-B");
        assertNotSame("distinct logical PS must get distinct physical PS", a1, b1);

        PreparedStatement a2 = mgr.prepareStatement(m, "SELECT 1", "owner-A");
        assertSame("same owner + SQL re-uses the cached physical PS", a1, a2);
        mgr.releasePhysicalConnections();
    }

    // CR-3 / 01 §C-2: closing one logical PS (closePrepForOwner) must release only its own physical
    // PS, leaving other logical PS on the same connection intact.
    @Test
    public void closePrepForOwnerReleasesOnlyThatOwnersStatements() throws SQLException {
        final AtomicInteger prepareCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        return newConnectionProxyWithTrackablePrepare(prepareCalls);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        Endpoint m = new Endpoint("m", 3);
        mgr.bindSession(m, new Endpoint("r", 4));

        PreparedStatement a1 = mgr.prepareStatement(m, "SELECT 1", "owner-A");
        PreparedStatement b1 = mgr.prepareStatement(m, "SELECT 2", "owner-B");

        mgr.closePrepForOwner("owner-A");

        // owner-A was evicted -> re-prepared as a new object; owner-B untouched -> still cached.
        PreparedStatement a2 = mgr.prepareStatement(m, "SELECT 1", "owner-A");
        PreparedStatement b2 = mgr.prepareStatement(m, "SELECT 2", "owner-B");
        assertNotSame("closed owner must be re-prepared", a1, a2);
        assertSame("other owner's cached PS must survive", b1, b2);
        mgr.releasePhysicalConnections();
    }

    // 01 §M-1: RETURN_GENERATED_KEYS must reach the physical prepare (2-arg form), not be dropped;
    // the plain path keeps the 1-arg prepare.
    @Test
    public void prepareStatementPropagatesGeneratedKeysFlagToPhysical() throws SQLException {
        final AtomicInteger lastPrepareArgCount = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        return prepareArgCountProxy(lastPrepareArgCount);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        Endpoint m = new Endpoint("m", 3);
        mgr.bindSession(m, new Endpoint("r", 4));

        mgr.prepareStatement(m, "INSERT INTO t VALUES (1)", "gk", Statement.RETURN_GENERATED_KEYS);
        assertEquals(
                "generated-keys prepare uses the 2-arg physical prepare",
                2,
                lastPrepareArgCount.get());

        mgr.prepareStatement(m, "SELECT 1", "plain", Statement.NO_GENERATED_KEYS);
        assertEquals("plain prepare uses the 1-arg physical prepare", 1, lastPrepareArgCount.get());

        mgr.releasePhysicalConnections();
    }

    private static Connection prepareArgCountProxy(final AtomicInteger lastPrepareArgCount) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            private boolean closed;

                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                if ("prepareStatement".equals(method.getName())) {
                                    lastPrepareArgCount.set(args == null ? 0 : args.length);
                                    return createTrackablePreparedStatement((Connection) proxy);
                                }
                                if ("isClosed".equals(method.getName())) {
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

    @Test
    public void prepareStatementEvictsClosedCachedStatementAndRePrepares() throws SQLException {
        final AtomicInteger prepareCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        return newConnectionProxyWithTrackablePrepare(prepareCalls);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        Endpoint m = new Endpoint("m", 3);
        Endpoint r = new Endpoint("r", 4);
        mgr.bindSession(m, r);

        PreparedStatement ps1 = mgr.prepareStatement(m, "SELECT 1");
        assertEquals(1, prepareCalls.get());

        ps1.close();

        PreparedStatement ps2 = mgr.prepareStatement(m, "SELECT 1");
        assertEquals(2, prepareCalls.get());
        assertNotSame(ps1, ps2);
        mgr.releasePhysicalConnections();
    }

    @Test
    public void prepareStatementEvictsCachedStatementWhenConnectionClosed() throws SQLException {
        final AtomicInteger prepareCalls = new AtomicInteger();
        final AtomicInteger openCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        openCalls.incrementAndGet();
                        return newConnectionProxyWithTrackablePrepare(prepareCalls);
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        Endpoint m = new Endpoint("m", 3);
        Endpoint r = new Endpoint("r", 4);
        mgr.bindSession(m, r);
        assertEquals(2, openCalls.get());

        PreparedStatement ps1 = mgr.prepareStatement(m, "SELECT 1");
        assertEquals(1, prepareCalls.get());
        ps1.getConnection().close();

        PreparedStatement ps2 = mgr.prepareStatement(m, "SELECT 1");
        assertEquals(3, openCalls.get());
        assertEquals(2, prepareCalls.get());
        assertNotSame(ps1, ps2);
        mgr.releasePhysicalConnections();
    }

    private static LoadBalanceSettings sessionConfig() {
        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        return LoadBalanceSettings.of(p);
    }

    private static LoadBalanceSettings sessionConfigWithRoFailover(final boolean enabled) {
        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        p.setProperty(
                LoadBalanceSettings.KEY_RO_PHYSICAL_FAILOVER_TO_RW, Boolean.toString(enabled));
        return LoadBalanceSettings.of(p);
    }

    private static LoadBalanceSettings sessionConfigFailoverTopology() {
        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        return LoadBalanceSettings.of(p);
    }

    private static Connection newConnectionProxy(final AtomicInteger closeCount) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("close".equals(method.getName())) {
                                    closeCount.incrementAndGet();
                                    return null;
                                }
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.FALSE;
                                }
                                if ("prepareStatement".equals(method.getName())) {
                                    return createStubPreparedStatement();
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

    private static Connection newConnectionProxyWithPrepare(final AtomicInteger prepareCalls) {
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
                                if ("prepareStatement".equals(method.getName())) {
                                    prepareCalls.incrementAndGet();
                                    return createStubPreparedStatement();
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

    private static Connection newConnectionProxyWithTrackablePrepare(
            final AtomicInteger prepareCalls) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            private final AtomicBoolean closed = new AtomicBoolean(false);

                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("close".equals(method.getName())) {
                                    closed.set(true);
                                    return null;
                                }
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.valueOf(closed.get());
                                }
                                if ("prepareStatement".equals(method.getName())) {
                                    prepareCalls.incrementAndGet();
                                    return createTrackablePreparedStatement((Connection) proxy);
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

    private static PreparedStatement createTrackablePreparedStatement(final Connection conn) {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new InvocationHandler() {
                            private final AtomicBoolean closed = new AtomicBoolean(false);

                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("close".equals(method.getName())) {
                                    closed.set(true);
                                    return null;
                                }
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.valueOf(closed.get());
                                }
                                if ("getConnection".equals(method.getName())) {
                                    return conn;
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                return null;
                            }
                        });
    }

    private static PreparedStatement createStubPreparedStatement() {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("close".equals(method.getName())) {
                                    return null;
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                return null;
                            }
                        });
    }

    private static Connection newConnectionProxyWithAutoCommitTracking(
            final AtomicInteger setAutoCommitCalls) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("setAutoCommit".equals(method.getName())) {
                                    setAutoCommitCalls.incrementAndGet();
                                    return null;
                                }
                                if ("close".equals(method.getName())) {
                                    return null;
                                }
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.FALSE;
                                }
                                if ("prepareStatement".equals(method.getName())) {
                                    return createStubPreparedStatement();
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

    private static Connection newConnectionProxyWithCommitTracking(
            final AtomicInteger commitCalls) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("commit".equals(method.getName())) {
                                    commitCalls.incrementAndGet();
                                    return null;
                                }
                                if ("close".equals(method.getName())) {
                                    return null;
                                }
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.FALSE;
                                }
                                if ("prepareStatement".equals(method.getName())) {
                                    return createStubPreparedStatement();
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

    private static Connection newConnectionProxyWithRollbackTracking(
            final AtomicInteger rollbackCalls) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("rollback".equals(method.getName())) {
                                    rollbackCalls.incrementAndGet();
                                    return null;
                                }
                                if ("close".equals(method.getName())) {
                                    return null;
                                }
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.FALSE;
                                }
                                if ("prepareStatement".equals(method.getName())) {
                                    return createStubPreparedStatement();
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

    @Test
    public void recoverRwFailsOverWithinGroup() throws SQLException {
        Endpoint rw1 = new Endpoint("rw1", 33000);
        Endpoint rw2 = new Endpoint("rw2", 33000);
        Endpoint ro = new Endpoint("ro", 33001);
        List<Endpoint> rwList = new ArrayList<Endpoint>();
        rwList.add(rw1);
        rwList.add(rw2);
        EndpointTopology topo = new EndpointTopology(rwList, Arrays.asList(ro), null);

        final AtomicInteger openCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        int n = openCalls.incrementAndGet();
                        if (n <= 2) {
                            return newConnectionProxy(new AtomicInteger());
                        }
                        if (url.contains("rw1:33000")) {
                            throw new SQLException("rw1 down");
                        }
                        return newConnectionProxy(new AtomicInteger());
                    }
                };

        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        mgr.bindSession(rw1, ro, topo);

        PhysicalRecoveryContext ctx =
                new PhysicalRecoveryContext(
                        SessionLeg.RW,
                        rw1,
                        Router.RouteTarget.TO_READ_WRITE,
                        topo,
                        Collections.<String>emptySet(),
                        false);

        PhysicalRecoveryResult result = mgr.recoverRw(ctx);
        assertEquals("rw2:33000", result.getBoundEndpoint().getId());
        assertEquals(SessionLeg.RW, result.getBoundLeg());
        assertEquals(rw2, mgr.getSessionEndpoint(SessionLeg.RW));
        mgr.releasePhysicalConnections();
    }

    @Test
    public void recoverRwRejectsActiveTransaction() throws SQLException {
        Endpoint rw = new Endpoint("rw", 33000);
        Endpoint ro = new Endpoint("ro", 33001);
        EndpointTopology topo =
                new EndpointTopology(rw, Arrays.asList(ro), Collections.<Endpoint>emptyList());
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL,
                        new Properties(),
                        sessionConfig(),
                        new JdbcConnectionFactory() {
                            public Connection getConnection(final String url, final Properties info)
                                    throws SQLException {
                                return newConnectionProxy(new AtomicInteger());
                            }
                        });
        mgr.bindSession(rw, ro, topo);

        PhysicalRecoveryContext ctx =
                new PhysicalRecoveryContext(
                        SessionLeg.RW,
                        rw,
                        Router.RouteTarget.TO_READ_WRITE,
                        topo,
                        Collections.<String>emptySet(),
                        true);
        try {
            mgr.recoverRw(ctx);
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e instanceof CUBRIDException);
            assertEquals(LbJDBCErrorCode.lb_failover_tx_forbidden, e.getErrorCode());
        }
    }

    @Test
    public void recoverRoFailsOverToAlternateRoBroker() throws SQLException {
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
                            return newConnectionProxy(new AtomicInteger());
                        }
                        if (url.contains("ro1:33001")) {
                            throw new SQLException("ro1 down");
                        }
                        return newConnectionProxy(new AtomicInteger());
                    }
                };

        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        mgr.bindSession(rw, ro1, topo);

        PhysicalRecoveryContext ctx =
                new PhysicalRecoveryContext(
                        SessionLeg.RO,
                        ro1,
                        Router.RouteTarget.TO_READ_ONLY,
                        topo,
                        Collections.<String>emptySet(),
                        false);

        PhysicalRecoveryResult result = mgr.recoverRo(ctx);
        assertEquals("ro2:33001", result.getBoundEndpoint().getId());
        assertEquals(SessionLeg.RO, result.getBoundLeg());
        assertEquals(ro2, mgr.getSessionEndpoint(SessionLeg.RO));
        mgr.releasePhysicalConnections();
    }

    @Test
    public void recoverRoFailsOverToRwWhenRoGroupExhausted() throws SQLException {
        Endpoint rw = new Endpoint("rw", 33000);
        Endpoint ro1 = new Endpoint("ro1", 33001);
        EndpointTopology topo =
                new EndpointTopology(rw, Arrays.asList(ro1), Collections.<Endpoint>emptyList());

        final AtomicInteger openCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        int n = openCalls.incrementAndGet();
                        if (n <= 2) {
                            return newConnectionProxy(new AtomicInteger());
                        }
                        throw new SQLException("ro down");
                    }
                };

        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        mgr.bindSession(rw, ro1, topo);

        PhysicalRecoveryContext ctx =
                new PhysicalRecoveryContext(
                        SessionLeg.RO,
                        ro1,
                        Router.RouteTarget.TO_READ_ONLY,
                        topo,
                        Collections.<String>emptySet(),
                        false);

        PhysicalRecoveryResult result = mgr.recoverRo(ctx);
        assertEquals("rw:33000", result.getBoundEndpoint().getId());
        assertEquals(SessionLeg.RW, result.getBoundLeg());
        assertEquals(
                SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON, result.getFallbackReason());
        assertEquals(
                SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON, mgr.getRoFallbackReason());
        mgr.releasePhysicalConnections();
    }

    @Test
    public void openFirstSkipsExcludedEndpoints() throws SQLException {
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        return newConnectionProxy(new AtomicInteger());
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        List<Endpoint> order = Arrays.asList(new Endpoint("skip", 1), new Endpoint("ok", 2));
        HashSet<String> exclude = new HashSet<String>();
        exclude.add("skip:1");

        SessionPhysicalConnManager.BindResult result =
                mgr.openFirstReachable(order, "RO", exclude, 0);
        assertEquals("ok:2", result.getEndpoint().getId());
        assertNotNull(result.getConnection());
    }

    @Test
    public void openFirstHonorsMaxAttempts() throws SQLException {
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        throw new SQLException("fail");
                    }
                };
        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), sessionConfig(), factory);
        List<Endpoint> order =
                Arrays.asList(new Endpoint("a", 1), new Endpoint("b", 2), new Endpoint("c", 3));
        try {
            mgr.openFirstReachable(order, "RW", Collections.<String>emptySet(), 1);
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e instanceof CUBRIDException);
            assertEquals(LbJDBCErrorCode.lb_broker_group_exhausted, e.getErrorCode());
            assertTrue(e.getMessage().contains("a:1"));
            assertTrue(e.getMessage().indexOf("b:2") < 0);
            assertNotNull(e.getCause());
        }
    }
}
