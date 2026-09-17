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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class LBStatementExecuteFailoverTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";

    @Test
    public void executeQueryRecoversAndRetriesOnceAfterRoCommunicationFailure()
            throws SQLException {
        Endpoint rw = new Endpoint("rw", 33000);
        Endpoint ro1 = new Endpoint("ro1", 33001);
        Endpoint ro2 = new Endpoint("ro2", 33001);
        EndpointTopology topo =
                new EndpointTopology(
                        rw, Arrays.asList(ro1, ro2), Collections.<Endpoint>emptyList());

        final AtomicInteger executeCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        return connectionProxy(executeCalls);
                    }
                };

        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");

        LoadBalanceConnection conn = new LoadBalanceConnection(LoadBalanceSettings.of(cfg));
        conn.getRuntimeMetrics().setEnabled(true);
        conn.setConnectionManager(
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), LoadBalanceSettings.of(cfg), factory));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topo);

        LBStatement stmt = new LBStatement(conn);
        stmt.setStatementProvider(conn.createStatementProvider(0, 0, 0));

        ResultSet rs = stmt.executeQuery("SELECT 1");
        assertNotNull(rs);
        assertEquals(2, executeCalls.get());

        boolean foundFailoverEvent = false;
        for (String event : conn.getRuntimeMetrics().getEvents()) {
            if (event.contains("event=RUNTIME_FAILOVER")) {
                foundFailoverEvent = true;
                break;
            }
        }
        assertTrue(foundFailoverEvent);
        stmt.close();
        conn.close();
    }

    @Test
    public void secondExecuteQueryDoesNotTriggerSecondRecovery() throws SQLException {
        Endpoint rw = new Endpoint("rw", 33000);
        Endpoint ro1 = new Endpoint("ro1", 33001);
        Endpoint ro2 = new Endpoint("ro2", 33001);
        EndpointTopology topo =
                new EndpointTopology(
                        rw, Arrays.asList(ro1, ro2), Collections.<Endpoint>emptyList());

        final AtomicInteger executeCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        return connectionProxy(executeCalls);
                    }
                };

        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");

        LoadBalanceConnection conn = new LoadBalanceConnection(LoadBalanceSettings.of(cfg));
        conn.getRuntimeMetrics().setEnabled(true);
        conn.setConnectionManager(
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), LoadBalanceSettings.of(cfg), factory));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topo);

        LBStatement stmt = new LBStatement(conn);
        stmt.setStatementProvider(conn.createStatementProvider(0, 0, 0));

        stmt.executeQuery("SELECT 1");
        assertEquals(2, executeCalls.get());

        stmt.executeQuery("SELECT 2");
        assertEquals(3, executeCalls.get());

        int failoverEvents = 0;
        for (String event : conn.getRuntimeMetrics().getEvents()) {
            if (event.contains("event=RUNTIME_FAILOVER")) {
                failoverEvents++;
            }
        }
        assertEquals(1, failoverEvents);
        stmt.close();
        conn.close();
    }

    @Test
    public void executeBatchDoesNotInvokeRuntimeFailover() throws SQLException {
        final AtomicInteger executeCalls = new AtomicInteger();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        return connectionProxy(executeCalls);
                    }
                };

        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");

        LoadBalanceConnection conn = new LoadBalanceConnection(LoadBalanceSettings.of(cfg));
        conn.getRuntimeMetrics().setEnabled(true);
        conn.setConnectionManager(
                new SessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), LoadBalanceSettings.of(cfg), factory));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(
                new EndpointTopology(
                        new Endpoint("rw", 33000),
                        Arrays.asList(new Endpoint("ro1", 33000)),
                        Collections.<Endpoint>emptyList()));

        LBStatement stmt = new LBStatement(conn);
        stmt.setStatementProvider(conn.createStatementProvider(0, 0, 0));
        stmt.addBatch("SELECT 1");

        try {
            stmt.executeBatch();
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(UErrorCode.ER_COMMUNICATION == e.getErrorCode());
        }
        assertEquals(1, executeCalls.get());
        for (String event : conn.getRuntimeMetrics().getEvents()) {
            assertTrue(!event.contains("event=RUNTIME_FAILOVER"));
        }
        stmt.close();
        conn.close();
    }

    private static Connection connectionProxy(final AtomicInteger executeCalls) {
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
                                if ("createStatement".equals(method.getName())) {
                                    return statementProxy(executeCalls);
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

    private static Statement statementProxy(final AtomicInteger executeCalls) {
        return (Statement)
                Proxy.newProxyInstance(
                        Statement.class.getClassLoader(),
                        new Class[] {Statement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("executeQuery".equals(method.getName())) {
                                    if (executeCalls.incrementAndGet() == 1) {
                                        throw new SQLException(
                                                "comm fail", null, UErrorCode.ER_COMMUNICATION);
                                    }
                                    return resultSetProxy();
                                }
                                if ("executeBatch".equals(method.getName())) {
                                    if (executeCalls.incrementAndGet() == 1) {
                                        throw new SQLException(
                                                "comm fail", null, UErrorCode.ER_COMMUNICATION);
                                    }
                                    return new int[] {1};
                                }
                                if ("addBatch".equals(method.getName())) {
                                    return null;
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

    private static ResultSet resultSetProxy() {
        return (ResultSet)
                Proxy.newProxyInstance(
                        ResultSet.class.getClassLoader(),
                        new Class[] {ResultSet.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
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
}
