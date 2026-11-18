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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.CUBRIDException;
import cubrid.jdbc.driver.CUBRIDJDBCErrorCode;
import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.route.Router.RouteTarget;
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
import java.util.concurrent.Executor;
import org.junit.Test;

/**
 * Core supports {@code setNetworkTimeout} since APIS-1123: a read that outlasts it fails with
 * -21031 and closes the connection, and every later call fails as closed. A load-balanced
 * connection gives the value to each of its legs, and a leg closed that way is not reopened.
 */
public class LoadBalanceConnectionNetworkTimeoutTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";

    // master nt1 (RW 33000), slave nt2 (RW 33000, RO 33001); reads go to the slave.
    private static final String URL =
            "jdbc:cubrid:loadbalance://nt1,nt2/testdb:::"
                    + "?rwPort=33000&roPort=33001&readWeight=slave:1";

    private static final Executor EXECUTOR =
            new Executor() {
                public void execute(final Runnable command) {
                    command.run();
                }
            };

    private final List<Leg> opened = new ArrayList<Leg>();

    @Test
    public void theTimeoutReachesBothLegsAndALegOpenedLater() throws SQLException {
        LoadBalanceConnection conn = newConnection();
        try {
            assertEquals(0, conn.getNetworkTimeout());

            conn.setNetworkTimeout(EXECUTOR, 1500);

            assertEquals(1500, conn.getNetworkTimeout());
            assertEquals("[1500]", leg("nt1:33000").timeouts.toString());
            assertEquals("[1500]", leg("nt2:33001").timeouts.toString());

            // The write leg fails over to nt2's RW broker: the new leg gets the value when opened.
            Endpoint failed = conn.resolveFailedExecEndpoint(RouteTarget.TO_READ_WRITE);
            conn.recoverPhyBinding(
                    conn.buildRecoveryCtx(RouteTarget.TO_READ_WRITE, failed),
                    new SQLException("broker gone", null, UErrorCode.ER_COMMUNICATION));
            assertEquals("[1500]", leg("nt2:33000").timeouts.toString());
        } finally {
            conn.close();
        }
    }

    /** Core checks the arguments; a value the write leg refused is not kept. */
    @Test
    public void aRefusedTimeoutIsNotKept() throws SQLException {
        LoadBalanceConnection conn = newConnection();
        try {
            conn.setNetworkTimeout(EXECUTOR, 1000);
            assertRefusedAsInvalid(conn, null, 5);
            assertRefusedAsInvalid(conn, EXECUTOR, -1);
            assertEquals(1000, conn.getNetworkTimeout());
        } finally {
            conn.close();
        }
    }

    /**
     * ISSUE-24: the write leg timed out mid-transaction. Reopening it ran the next statement in a
     * new server session, as if the transaction had gone on; core fails it as closed.
     */
    @Test
    public void aWriteLegClosedByItsTimeoutIsNotReopened() throws SQLException {
        LoadBalanceConnection conn = newConnection();
        try {
            conn.setAutoCommit(false);
            conn.prepareStatement("UPDATE t SET a = 1").executeUpdate();
            leg("nt1:33000").closed = true;
            int legs = opened.size();

            assertFailsAsClosed(conn, "UPDATE t SET a = 2");

            assertEquals("no leg was reopened", legs, opened.size());
            assertTrue(conn.isClosed());
            assertFalse(conn.isValid(1));
        } finally {
            conn.close();
        }
    }

    /** As core's one connection: a read leg that timed out leaves the whole connection closed. */
    @Test
    public void aReadLegClosedByItsTimeoutClosesTheConnection() throws SQLException {
        LoadBalanceConnection conn = newConnection();
        try {
            conn.prepareStatement("SELECT a FROM t").executeQuery();
            leg("nt2:33001").closed = true;
            int legs = opened.size();

            assertFailsAsClosed(conn, "SELECT a FROM t");
            assertFailsAsClosed(conn, "UPDATE t SET a = 1");

            assertEquals("no leg was reopened", legs, opened.size());
            assertTrue(conn.isClosed());
        } finally {
            conn.close();
        }
    }

    private static void assertRefusedAsInvalid(
            final Connection conn, final Executor executor, final int milliseconds) {
        try {
            conn.setNetworkTimeout(executor, milliseconds);
            fail("refused by core");
        } catch (SQLException e) {
            assertEquals(CUBRIDJDBCErrorCode.invalid_value, e.getErrorCode());
        }
    }

    private static void assertFailsAsClosed(final Connection conn, final String sql) {
        try {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.execute();
            fail("a connection whose leg timed out is closed");
        } catch (SQLException e) {
            assertEquals(CUBRIDJDBCErrorCode.connection_closed, e.getErrorCode());
        }
    }

    private LoadBalanceConnection newConnection() throws SQLException {
        LoadBalanceSettings config = LbTestSettings.fromUrl(URL);
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        Leg leg = new Leg(url);
                        opened.add(leg);
                        return leg.connection;
                    }
                };
        LoadBalanceConnection conn = new LoadBalanceConnection(config);
        conn.setConnectionManager(
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), config, factory));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(config.buildEndpointTopology());
        return conn;
    }

    /** The most recently opened leg to {@code hostPort}. */
    private Leg leg(final String hostPort) {
        for (int i = opened.size() - 1; i >= 0; i--) {
            if (opened.get(i).url.contains(":" + hostPort + ":")) {
                return opened.get(i);
            }
        }
        throw new AssertionError("no leg to " + hostPort);
    }

    /**
     * A physical leg. It takes a network timeout as core does, refusing the values core refuses,
     * and {@link #closed} stands for core closing it when the timeout expired.
     */
    private static final class Leg {
        final String url;
        final List<Integer> timeouts = new ArrayList<Integer>();
        volatile boolean closed;
        final Connection connection;

        Leg(final String url) {
            this.url = url;
            this.connection =
                    (Connection)
                            Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class[] {Connection.class},
                                    new InvocationHandler() {
                                        public Object invoke(
                                                final Object proxy,
                                                final Method method,
                                                final Object[] args)
                                                throws SQLException {
                                            return onConnection(proxy, method, args);
                                        }
                                    });
        }

        private Object onConnection(final Object proxy, final Method method, final Object[] args)
                throws SQLException {
            String name = method.getName();
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            if ("isClosed".equals(name)) {
                return Boolean.valueOf(closed);
            }
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("setNetworkTimeout".equals(name)) {
                int milliseconds = ((Integer) args[1]).intValue();
                if (args[0] == null || milliseconds < 0) {
                    throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
                }
                timeouts.add(Integer.valueOf(milliseconds));
                return null;
            }
            if ("getAutoCommit".equals(name)) {
                return Boolean.TRUE;
            }
            if ("prepareStatement".equals(name)) {
                return statement(proxy);
            }
            return defaultFor(method.getReturnType());
        }

        private PreparedStatement statement(final Object owner) {
            return (PreparedStatement)
                    Proxy.newProxyInstance(
                            PreparedStatement.class.getClassLoader(),
                            new Class[] {PreparedStatement.class},
                            new InvocationHandler() {
                                public Object invoke(
                                        final Object proxy,
                                        final Method method,
                                        final Object[] args)
                                        throws SQLException {
                                    String name = method.getName();
                                    if ("hashCode".equals(name)) {
                                        return Integer.valueOf(System.identityHashCode(proxy));
                                    }
                                    if ("equals".equals(name)) {
                                        return Boolean.valueOf(proxy == args[0]);
                                    }
                                    if ("getConnection".equals(name)) {
                                        return owner;
                                    }
                                    if (name.startsWith("execute") && closed) {
                                        throw new CUBRIDException(
                                                CUBRIDJDBCErrorCode.connection_closed);
                                    }
                                    if ("executeQuery".equals(name)) {
                                        return resultSet();
                                    }
                                    return defaultFor(method.getReturnType());
                                }
                            });
        }

        private static ResultSet resultSet() {
            return (ResultSet)
                    Proxy.newProxyInstance(
                            ResultSet.class.getClassLoader(),
                            new Class[] {ResultSet.class},
                            new InvocationHandler() {
                                public Object invoke(
                                        final Object rs, final Method m, final Object[] a) {
                                    return defaultFor(m.getReturnType());
                                }
                            });
        }
    }

    private static Object defaultFor(final Class<?> type) {
        if (type.equals(Boolean.TYPE)) {
            return Boolean.FALSE;
        }
        if (type.equals(Integer.TYPE)) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
