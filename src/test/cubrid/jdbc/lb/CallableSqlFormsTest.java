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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.CUBRIDJDBCErrorCode;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.After;
import org.junit.Test;

/**
 * Core runs SQL given to a CallableStatement's Statement forms on the statement itself, with its
 * max rows and query timeout, and answers the result getters from that run. Here the SQL runs as an
 * LB statement of its own, so the getters and the settings must follow it there.
 */
public final class CallableSqlFormsTest {

    private static final Endpoint NODE1 = new Endpoint("csfnode1", 33000);
    private static final Endpoint NODE2 = new Endpoint("csfnode2", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("csfnode2", 33002);

    private static final ResultSet SQL_RESULT = resultSet();

    private final List<String> asked = new ArrayList<String>();
    private LoadBalanceConnection conn;

    private void open() throws SQLException {
        EndpointTopology topo =
                new EndpointTopology(
                        Arrays.asList(NODE1, NODE2),
                        Arrays.asList(NODE2_RO),
                        Collections.<Endpoint>emptyList());
        LoadBalanceSettings settings = LbTestSettings.forTopology(topo);
        conn = new LoadBalanceConnection(settings);
        conn.setConnectionManager(
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        new Properties(),
                        settings,
                        new JdbcConnectionFactory() {
                            public Connection getConnection(final String url, final Properties i) {
                                return leg();
                            }
                        }));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topo);
    }

    @After
    public void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    public void aSelectGivenAsSqlAnswersGetResultSet() throws SQLException {
        open();
        CallableStatement cs = conn.prepareCall("CALL p(?)");

        assertTrue(cs.execute("SELECT a FROM t"));

        assertSame(SQL_RESULT, cs.getResultSet());
    }

    @Test
    public void anUpdateGivenAsSqlAnswersGetUpdateCount() throws SQLException {
        open();
        CallableStatement cs = conn.prepareCall("CALL p(?)");

        cs.executeUpdate("UPDATE t SET a = 1");
        assertEquals(7, cs.getUpdateCount());
        cs.execute("UPDATE t SET a = 2");
        assertEquals(7, cs.getUpdateCount());
    }

    @Test
    public void aRunOfTheCallAfterwardsAnswersFromTheCall() throws SQLException {
        open();
        CallableStatement cs = conn.prepareCall("CALL p(?)");
        cs.execute("UPDATE t SET a = 1");

        cs.execute();

        assertEquals(-1, cs.getUpdateCount());
    }

    @Test
    public void theStatementSettingsReachTheSqlRun() throws SQLException {
        open();
        CallableStatement cs = conn.prepareCall("CALL p(?)");
        cs.setMaxRows(10);
        cs.setQueryTimeout(5);

        cs.execute("SELECT a FROM t");

        assertTrue(asked.toString(), asked.contains("stmt.setMaxRows[10]"));
        assertTrue(asked.toString(), asked.contains("stmt.setQueryTimeout[5]"));
    }

    @Test
    public void cancelReachesTheSqlRun() throws SQLException {
        open();
        CallableStatement cs = conn.prepareCall("CALL p(?)");
        cs.execute("SELECT a FROM t");

        cs.cancel();

        assertTrue(asked.toString(), asked.contains("stmt.cancel"));
    }

    @Test
    public void anSqlRunAfterTheConnectionClosedFailsAsAClosedStatement() throws SQLException {
        open();
        CallableStatement cs = conn.prepareCall("CALL p(?)");
        conn.close();

        try {
            cs.executeQuery("SELECT a FROM t");
            fail("closed with its connection");
        } catch (SQLException e) {
            assertEquals(CUBRIDJDBCErrorCode.statement_closed, e.getErrorCode());
        }
    }

    /** A leg whose statements answer as a SELECT, an UPDATE of 7 rows, or a CALL would. */
    private Connection leg() {
        final boolean[] closed = {false};
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(final Object p, final Method m, final Object[] a) {
                                String name = m.getName();
                                if ("close".equals(name)) {
                                    closed[0] = true;
                                    return null;
                                }
                                if ("isClosed".equals(name)) {
                                    return Boolean.valueOf(closed[0]);
                                }
                                if ("getAutoCommit".equals(name)) {
                                    return Boolean.TRUE;
                                }
                                if ("createStatement".equals(name)) {
                                    return statement(Statement.class, "stmt", closed);
                                }
                                if ("prepareCall".equals(name)) {
                                    return statement(CallableStatement.class, "call", closed);
                                }
                                return zero(m.getReturnType());
                            }
                        });
    }

    private Object statement(final Class<?> type, final String tag, final boolean[] legClosed) {
        final boolean[] closed = {false};
        final String[] lastSql = {null};
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                new InvocationHandler() {
                    public Object invoke(final Object p, final Method m, final Object[] a)
                            throws SQLException {
                        String name = m.getName();
                        if ("close".equals(name)) {
                            closed[0] = true;
                            return null;
                        }
                        if ("isClosed".equals(name)) {
                            return Boolean.valueOf(closed[0] || legClosed[0]);
                        }
                        if (name.startsWith("set") || "cancel".equals(name)) {
                            asked.add(tag + "." + name + (a == null ? "" : Arrays.toString(a)));
                        }
                        if ("stmt".equals(tag) && name.startsWith("execute")) {
                            lastSql[0] = String.valueOf(a[0]);
                            if ("executeQuery".equals(name)) {
                                return SQL_RESULT;
                            }
                            if ("execute".equals(name)) {
                                return Boolean.valueOf(lastSql[0].startsWith("SELECT"));
                            }
                            return Integer.valueOf(7);
                        }
                        if ("stmt".equals(tag) && "getResultSet".equals(name)) {
                            return lastSql[0] != null && lastSql[0].startsWith("SELECT")
                                    ? SQL_RESULT
                                    : null;
                        }
                        if ("stmt".equals(tag) && "getUpdateCount".equals(name)) {
                            return Integer.valueOf(
                                    lastSql[0] != null && lastSql[0].startsWith("SELECT") ? -1 : 7);
                        }
                        if ("getUpdateCount".equals(name)) {
                            return Integer.valueOf(-1);
                        }
                        return zero(m.getReturnType());
                    }
                });
    }

    private static ResultSet resultSet() {
        return (ResultSet)
                Proxy.newProxyInstance(
                        ResultSet.class.getClassLoader(),
                        new Class<?>[] {ResultSet.class},
                        new InvocationHandler() {
                            public Object invoke(final Object p, final Method m, final Object[] a) {
                                return zero(m.getReturnType());
                            }
                        });
    }

    private static Object zero(final Class<?> type) {
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        return type == Long.TYPE ? Long.valueOf(0L) : null;
    }
}
