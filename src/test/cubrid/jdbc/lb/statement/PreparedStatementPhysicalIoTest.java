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

package cubrid.jdbc.lb.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class PreparedStatementPhysicalIoTest {

    @Test
    public void executeQueryDelegatesToPhysicalStatement() throws SQLException {
        final AtomicInteger executeQueryCalls = new AtomicInteger();
        LBPreparedStatement.PhysicalPsProvider provider =
                new LBPreparedStatement.PhysicalPsProvider() {
                    public PreparedStatement getPreparedStatement(
                            final Router.RouteTarget target, final String rawSql)
                            throws SQLException {
                        return (PreparedStatement)
                                Proxy.newProxyInstance(
                                        PreparedStatement.class.getClassLoader(),
                                        new Class[] {PreparedStatement.class},
                                        new InvocationHandler() {
                                            public Object invoke(
                                                    final Object proxy,
                                                    final Method method,
                                                    final Object[] args)
                                                    throws Throwable {
                                                if ("executeQuery".equals(method.getName())) {
                                                    executeQueryCalls.incrementAndGet();
                                                    return Proxy.newProxyInstance(
                                                            ResultSet.class.getClassLoader(),
                                                            new Class[] {ResultSet.class},
                                                            new InvocationHandler() {
                                                                public Object invoke(
                                                                        final Object p,
                                                                        final Method m,
                                                                        final Object[] a) {
                                                                    return null;
                                                                }
                                                            });
                                                }
                                                if ("executeUpdate".equals(method.getName())) {
                                                    return Integer.valueOf(0);
                                                }
                                                if ("execute".equals(method.getName())) {
                                                    return Boolean.FALSE;
                                                }
                                                if ("close".equals(method.getName())) {
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

                    public void prepareOnBoundReadEndpoint(final String rawSql)
                            throws SQLException {}

                    public void prepareOnWriteEndpoint(final String rawSql) throws SQLException {}

                    public void closePrepStmts() throws SQLException {}
                };

        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceConnection conn = new LoadBalanceConnection(LoadBalanceSettings.of(p));
        LBPreparedStatement ps = new LBPreparedStatement(conn, "SELECT 1");
        ps.setPsProvider(provider);
        ps.executeQuery();
        assertEquals(1, executeQueryCalls.get());
    }

    /**
     * 02 §M1: statement attributes (queryTimeout/maxRows/fetchSize/maxFieldSize/escapeProcessing)
     * set on the logical PreparedStatement must be propagated to the physical PS on each execution.
     * Before the fix the PS execute path called only applyVendorFlags and dropped these entirely.
     */
    @Test
    public void executePropagatesStatementAttributesToPhysicalPs() throws SQLException {
        final AtomicInteger appliedMaxRows = new AtomicInteger(-1);
        final AtomicInteger appliedQueryTimeout = new AtomicInteger(-1);
        final AtomicInteger appliedFetchSize = new AtomicInteger(-1);
        final AtomicInteger appliedMaxFieldSize = new AtomicInteger(-1);
        LBPreparedStatement.PhysicalPsProvider provider =
                new LBPreparedStatement.PhysicalPsProvider() {
                    public PreparedStatement getPreparedStatement(
                            final Router.RouteTarget target, final String rawSql)
                            throws SQLException {
                        return (PreparedStatement)
                                Proxy.newProxyInstance(
                                        PreparedStatement.class.getClassLoader(),
                                        new Class[] {PreparedStatement.class},
                                        new InvocationHandler() {
                                            public Object invoke(
                                                    final Object proxy,
                                                    final Method method,
                                                    final Object[] args)
                                                    throws Throwable {
                                                String name = method.getName();
                                                if ("setMaxRows".equals(name)) {
                                                    appliedMaxRows.set(
                                                            ((Integer) args[0]).intValue());
                                                    return null;
                                                }
                                                if ("setQueryTimeout".equals(name)) {
                                                    appliedQueryTimeout.set(
                                                            ((Integer) args[0]).intValue());
                                                    return null;
                                                }
                                                if ("setFetchSize".equals(name)) {
                                                    appliedFetchSize.set(
                                                            ((Integer) args[0]).intValue());
                                                    return null;
                                                }
                                                if ("setMaxFieldSize".equals(name)) {
                                                    appliedMaxFieldSize.set(
                                                            ((Integer) args[0]).intValue());
                                                    return null;
                                                }
                                                if ("executeQuery".equals(name)) {
                                                    return Proxy.newProxyInstance(
                                                            ResultSet.class.getClassLoader(),
                                                            new Class[] {ResultSet.class},
                                                            new InvocationHandler() {
                                                                public Object invoke(
                                                                        final Object p,
                                                                        final Method m,
                                                                        final Object[] a) {
                                                                    return null;
                                                                }
                                                            });
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

                    public void prepareOnBoundReadEndpoint(final String rawSql)
                            throws SQLException {}

                    public void prepareOnWriteEndpoint(final String rawSql) throws SQLException {}

                    public void closePrepStmts() throws SQLException {}
                };

        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceConnection conn = new LoadBalanceConnection(LoadBalanceSettings.of(p));
        LBPreparedStatement ps = new LBPreparedStatement(conn, "SELECT 1");
        ps.setPsProvider(provider);
        ps.setMaxRows(37);
        ps.setQueryTimeout(11);
        ps.setFetchSize(23);
        ps.setMaxFieldSize(4096);
        ps.executeQuery();

        assertEquals(37, appliedMaxRows.get());
        assertEquals(11, appliedQueryTimeout.get());
        assertEquals(23, appliedFetchSize.get());
        assertEquals(4096, appliedMaxFieldSize.get());
    }

    @Test
    public void sessionReadPrepareIssuesOnlyReadEndpointPhysicalPrepare() throws SQLException {
        final List<String> connectionPrepareSqls = new ArrayList<String>();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        final String cid = url.contains("ro1") ? "ro" : "rw";
                        return (Connection)
                                Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class[] {Connection.class},
                                        new InvocationHandler() {
                                            public Object invoke(
                                                    final Object proxy,
                                                    final Method method,
                                                    final Object[] args)
                                                    throws Throwable {
                                                if ("prepareStatement".equals(method.getName())
                                                        && args != null
                                                        && args.length >= 1
                                                        && args[0] instanceof String) {
                                                    connectionPrepareSqls.add(cid + ":" + args[0]);
                                                    return createMinimalPreparedStatementProxy();
                                                }
                                                if ("close".equals(method.getName())) {
                                                    return null;
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
                };

        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings loadBalanceSettings = LoadBalanceSettings.of(cfg);
        SessionPhysicalConnManager pool =
                new SessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        new Properties(),
                        loadBalanceSettings,
                        factory);

        LoadBalanceConnection conn = new LoadBalanceConnection(loadBalanceSettings);
        conn.setConnectionManager(pool);
        conn.setSharedSelectorState(new SharedSelectorState());
        List<Endpoint> roList = new ArrayList<Endpoint>();
        roList.add(new Endpoint("ro1", 33000));
        EndpointTopology topology = new EndpointTopology(new Endpoint("rw", 33000), roList, null);
        conn.initSessionBindings(topology);

        LBPreparedStatement ps = (LBPreparedStatement) conn.prepareStatement("  SELECT  1  ");
        ps.executeQuery();

        // Dual prepare removed: a READ prepares only the bound read endpoint; no
        // eager RW leg. The lazy execute reuses the cached read PS, so exactly one physical
        // prepare.
        assertEquals(1, connectionPrepareSqls.size());
        assertTrue(connectionPrepareSqls.contains("ro:  SELECT  1  "));
        assertFalse(connectionPrepareSqls.contains("rw:  SELECT  1  "));
    }

    /**
     * A read routes to the session's bound read (RO) connection and never opens a separate replica
     * SO connection: a configured replica broker (so1) is never contacted — even though it is in
     * the topology (and would throw here). Replica endpoints are only ever used when readWeight
     * binds one as the session read target; there is no hint- or execution-time replica connect.
     */
    @Test
    public void readRoutesToBoundReadNeverContactsReplica() throws SQLException {
        final List<String> prepares = new ArrayList<String>();
        final List<String> connectAttempts = new ArrayList<String>();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        connectAttempts.add(url);
                        if (url.contains("so1")) {
                            throw new SQLException("replica SO unreachable");
                        }
                        final String cid = url.contains("ro1") ? "ro" : "rw";
                        return (Connection)
                                Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class[] {Connection.class},
                                        new InvocationHandler() {
                                            public Object invoke(
                                                    final Object proxy,
                                                    final Method method,
                                                    final Object[] args)
                                                    throws Throwable {
                                                if ("prepareStatement".equals(method.getName())
                                                        && args != null
                                                        && args.length >= 1
                                                        && args[0] instanceof String) {
                                                    prepares.add(cid + ":" + args[0]);
                                                    return createMinimalPreparedStatementProxy();
                                                }
                                                if ("isClosed".equals(method.getName())) {
                                                    return Boolean.FALSE;
                                                }
                                                if ("close".equals(method.getName())) {
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
                };

        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings loadBalanceSettings = LoadBalanceSettings.of(cfg);
        SessionPhysicalConnManager pool =
                new SessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        new Properties(),
                        loadBalanceSettings,
                        factory);

        LoadBalanceConnection conn = new LoadBalanceConnection(loadBalanceSettings);
        conn.setConnectionManager(pool);
        conn.setSharedSelectorState(new SharedSelectorState());
        List<Endpoint> roList = new ArrayList<Endpoint>();
        roList.add(new Endpoint("ro1", 33000));
        List<Endpoint> replList = new ArrayList<Endpoint>();
        replList.add(new Endpoint("so1", 33004));
        EndpointTopology topology =
                new EndpointTopology(new Endpoint("rw", 33000), roList, replList);
        conn.initSessionBindings(topology);

        LBPreparedStatement ps = (LBPreparedStatement) conn.prepareStatement("SELECT 1");
        ps.executeQuery(); // must not throw — routes to the bound read, never the replica

        boolean triedReplica = false;
        for (int i = 0; i < connectAttempts.size(); i++) {
            if (connectAttempts.get(i).contains("so1")) {
                triedReplica = true;
            }
        }
        assertFalse("a read must NOT open a replica SO connection", triedReplica);
        assertTrue(
                "the read must route to the bound RO connection", prepares.contains("ro:SELECT 1"));
    }

    private static PreparedStatement createMinimalPreparedStatementProxy() {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("executeQuery".equals(method.getName())) {
                                    return Proxy.newProxyInstance(
                                            ResultSet.class.getClassLoader(),
                                            new Class[] {ResultSet.class},
                                            new InvocationHandler() {
                                                public Object invoke(
                                                        final Object p,
                                                        final Method m,
                                                        final Object[] a) {
                                                    return null;
                                                }
                                            });
                                }
                                if ("executeUpdate".equals(method.getName())) {
                                    return Integer.valueOf(0);
                                }
                                if ("execute".equals(method.getName())) {
                                    return Boolean.FALSE;
                                }
                                if ("close".equals(method.getName())) {
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
