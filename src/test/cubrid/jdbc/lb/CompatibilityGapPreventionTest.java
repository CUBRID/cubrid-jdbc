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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.config.TestConfigHelper;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import cubrid.jdbc.lb.statement.LBStatement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class CompatibilityGapPreventionTest {

    @Test
    public void assertUnwrapAndIsWrapperForContractIsConsistent() throws Exception {
        LoadBalanceConnection connection =
                new LoadBalanceConnection(TestConfigHelper.emptyConfig());
        assertTrue(connection.isWrapperFor(Connection.class));
        assertSame(connection, connection.unwrap(Connection.class));
        Statement statement = connection.createStatement();
        assertTrue(statement.isWrapperFor(Statement.class));
        assertSame(statement, statement.unwrap(Statement.class));
    }

    /**
     * {@code CUBRIDConnection} is no longer an unsupported contract here — the connection extends
     * it so an unmodified application's cast holds. What must still produce an actionable message
     * is an interface this connection genuinely does not implement.
     */
    @Test
    public void assertUnwrapUnsupportedContractProvidesActionableMessage() throws Exception {
        LoadBalanceConnection connection =
                new LoadBalanceConnection(TestConfigHelper.emptyConfig());

        assertSame(
                "an application casting to the driver's own connection class must succeed",
                connection,
                connection.unwrap(cubrid.jdbc.driver.CUBRIDConnection.class));

        try {
            connection.unwrap(java.sql.Driver.class);
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().startsWith("Not supported method"));
        }
    }

    @Test
    public void assertStatementPropertyPropagationToPhysicalStatement() throws Exception {
        LoadBalanceConnection connection =
                new LoadBalanceConnection(TestConfigHelper.emptyConfig());
        LBStatement statement = (LBStatement) connection.createStatement();
        final List<String> calls = new ArrayList<String>();
        statement.setStatementProvider(
                new LBStatement.PhysicalStmtProvider() {
                    public Statement getStatement(
                            final Router.RouteTarget target, final String rawSql)
                            throws SQLException {
                        return newStatementProxy(calls);
                    }
                });
        statement.setMaxRows(11);
        statement.setQueryTimeout(12);
        statement.setFetchSize(13);
        statement.setFetchDirection(ResultSet.FETCH_REVERSE);
        statement.setMaxFieldSize(14);
        statement.closeOnCompletion();

        statement.executeQuery("SELECT 1");

        assertTrue(calls.contains("setMaxRows:11"));
        assertTrue(calls.contains("setQueryTimeout:12"));
        assertTrue(calls.contains("setFetchSize:13"));
        // Forward-only physical statement: CUBRID does not allow setFetchDirection (see
        // CUBRIDStatement.is_scrollable).
        assertFalse(calls.contains("setFetchDirection:" + ResultSet.FETCH_REVERSE));
        assertTrue(calls.contains("setMaxFieldSize:14"));
        assertTrue(calls.contains("closeOnCompletion"));
        assertTrue(calls.contains("executeQuery:SELECT 1"));
    }

    @Test
    public void assertScrollableStatementPropagatesFetchDirectionToPhysical() throws Exception {
        LoadBalanceConnection connection =
                new LoadBalanceConnection(TestConfigHelper.emptyConfig());
        LBStatement statement =
                (LBStatement)
                        connection.createStatement(
                                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        final List<String> calls = new ArrayList<String>();
        statement.setStatementProvider(
                new LBStatement.PhysicalStmtProvider() {
                    public Statement getStatement(
                            final Router.RouteTarget target, final String rawSql)
                            throws SQLException {
                        return newStatementProxy(calls);
                    }
                });
        statement.setFetchDirection(ResultSet.FETCH_REVERSE);
        statement.executeQuery("SELECT 1");
        assertTrue(calls.contains("setFetchDirection:" + ResultSet.FETCH_REVERSE));
        assertTrue(calls.contains("executeQuery:SELECT 1"));
    }

    @Test
    public void assertResultSetAndGeneratedKeysAffinityIsStable() throws Exception {
        LoadBalanceConnection connection =
                new LoadBalanceConnection(TestConfigHelper.emptyConfig());
        LBStatement statement = (LBStatement) connection.createStatement();
        final List<String> calls = new ArrayList<String>();
        statement.setStatementProvider(
                new LBStatement.PhysicalStmtProvider() {
                    public Statement getStatement(
                            final Router.RouteTarget target, final String rawSql)
                            throws SQLException {
                        return newStatementProxy(calls);
                    }
                });

        statement.execute("INSERT INTO t(id) VALUES (1)", Statement.RETURN_GENERATED_KEYS);
        ResultSet generatedKeys = statement.getGeneratedKeys();
        statement.getMoreResults();

        assertTrue(calls.contains("executeWithKeys:INSERT INTO t(id) VALUES (1):1"));
        assertTrue(calls.contains("getGeneratedKeys"));
        assertTrue(calls.contains("getMoreResults"));
        assertTrue(null != generatedKeys);
    }

    @Test
    public void assertUnsupportedContractIsConsistentAcrossApis() throws Exception {
        LoadBalanceConnection connection =
                new LoadBalanceConnection(TestConfigHelper.emptyConfig());
        assertUnsupportedMessageStartsWithPrefix(connection, "createArrayOf");
        assertUnsupportedMessageStartsWithPrefix(connection, "createStruct");
        assertUnsupportedMessageStartsWithPrefix(connection, "getTypeMap");
        assertUnsupportedMessageStartsWithPrefix(connection, "setTypeMap");
    }

    // CUBRID SHARD APIs and the driver-internal OUT-ResultSet hook are refused by name: a
    // load-balance session has no shard behind it, and addOutResultSet only ever runs on the
    // physical connection that created the CUBRIDOutResultSet.
    @Test
    public void assertShardAndInternalApisAreRefusedByName() throws Exception {
        LoadBalanceConnection connection =
                new LoadBalanceConnection(TestConfigHelper.emptyConfig());
        // isShard/getShardId/addOutResultSet are declared without SQLException on
        // CUBRIDConnection, so these overrides cannot widen the clause and the refusal arrives
        // unchecked; the named message is what both forms must keep.
        try {
            connection.isShard();
            fail("Expected the refusal");
        } catch (RuntimeException ex) {
            assertTrue(ex.getMessage().startsWith("Not supported method"));
            assertTrue(ex.getMessage().indexOf("isShard()") >= 0);
        }
        try {
            connection.getShardId();
            fail("Expected the refusal");
        } catch (RuntimeException ex) {
            assertTrue(ex.getMessage().indexOf("getShardId()") >= 0);
        }
        try {
            connection.getShardMetaData();
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().indexOf("getShardMetaData()") >= 0);
        }
        // addOutResultSet is no longer in this list. It became reachable once the session claimed
        // value-object ownership of its legs, so refusing it would break every stored procedure
        // that returns an OUT result set. See addOutResultSetIsNotRefused below.
    }

    /**
     * {@code CUBRIDOutResultSet} registers itself on the connection that owns the socket it came
     * from. That used to be the physical leg, so this method was unreachable on an LB session and
     * refused. Now the session owns its legs' value objects, so the registration arrives here and
     * must be accepted.
     *
     * <p>Only the refusal is covered offline. Exercising the tracking and close path needs a stored
     * procedure that returns a result set, which needs a Java SP deployed on the server — not
     * something this suite can set up.
     */
    @Test
    public void addOutResultSetIsNotRefused() throws Exception {
        LoadBalanceConnection connection =
                new LoadBalanceConnection(TestConfigHelper.emptyConfig());

        connection.addOutResultSet(null);
    }

    @Test
    public void assertCompatibilitySmokeScenarioHasNoUnexpectedNotSupported() throws Exception {
        LoadBalanceConnection connection =
                new LoadBalanceConnection(TestConfigHelper.emptyConfig());
        LBStatement statement = (LBStatement) connection.createStatement();
        statement.setStatementProvider(
                new LBStatement.PhysicalStmtProvider() {
                    public Statement getStatement(
                            final Router.RouteTarget target, final String rawSql)
                            throws SQLException {
                        return newStatementProxy(new ArrayList<String>());
                    }
                });
        statement.executeQuery("SELECT 1");
        assertTrue("SELECT 1".equals(connection.nativeSQL("SELECT 1")));
        connection.setReadOnly(true);
        assertTrue(connection.isReadOnly());
        connection.setCatalog("demo");
        assertTrue("demo".equals(connection.getCatalog()));

        LBPreparedStatement prepared =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        final List<String> preparedCalls = new ArrayList<String>();
        prepared.setPsProvider(
                new LBPreparedStatement.PhysicalPsProvider() {
                    public PreparedStatement getPreparedStatement(
                            final Router.RouteTarget target, final String rawSql)
                            throws SQLException {
                        return newPreparedStatementProxy(preparedCalls);
                    }

                    public void prepareOnBoundReadEndpoint(final String rawSql)
                            throws SQLException {}

                    public void prepareOnWriteEndpoint(final String rawSql) throws SQLException {}

                    public void closePrepStmts() throws SQLException {}
                });
        prepared.setInt(1, 1);
        prepared.setLong(1, 2L);
        prepared.setBoolean(1, true);
        prepared.executeQuery();
        assertTrue(preparedCalls.contains("setBoolean:1:true"));
    }

    @Test
    public void assertConnectionIsValidReflectsClosedState() throws Exception {
        LoadBalanceConnection connection =
                new LoadBalanceConnection(TestConfigHelper.emptyConfig());
        assertTrue(connection.isValid(1));
        connection.close();
        assertFalse(connection.isValid(1));
    }

    private static void assertUnsupportedMessageStartsWithPrefix(
            final LoadBalanceConnection connection, final String api) throws SQLException {
        try {
            if ("createArrayOf".equals(api)) {
                connection.createArrayOf("VARCHAR", new Object[] {"v"});
            } else if ("createStruct".equals(api)) {
                connection.createStruct("S", new Object[] {Integer.valueOf(1)});
            } else if ("getTypeMap".equals(api)) {
                connection.getTypeMap();
            } else if ("setTypeMap".equals(api)) {
                connection.setTypeMap(null);
            }
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().startsWith("Not supported method"));
        }
    }

    private static Statement newStatementProxy(final List<String> calls) {
        return (Statement)
                Proxy.newProxyInstance(
                        CompatibilityGapPreventionTest.class.getClassLoader(),
                        new Class[] {Statement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                String name = method.getName();
                                if ("setMaxRows".equals(name)
                                        || "setQueryTimeout".equals(name)
                                        || "setFetchSize".equals(name)
                                        || "setFetchDirection".equals(name)
                                        || "setMaxFieldSize".equals(name)) {
                                    calls.add(name + ":" + args[0]);
                                    return null;
                                }
                                if ("setPoolable".equals(name)) {
                                    calls.add("setPoolable:" + args[0]);
                                    return null;
                                }
                                if ("closeOnCompletion".equals(name)) {
                                    calls.add("closeOnCompletion");
                                    return null;
                                }
                                if ("executeQuery".equals(name)) {
                                    calls.add("executeQuery:" + args[0]);
                                    return newResultSetProxy();
                                }
                                if ("execute".equals(name) && null != args && 2 == args.length) {
                                    calls.add("executeWithKeys:" + args[0] + ":" + args[1]);
                                    return Boolean.FALSE;
                                }
                                if ("getGeneratedKeys".equals(name)) {
                                    calls.add("getGeneratedKeys");
                                    return newResultSetProxy();
                                }
                                if ("getMoreResults".equals(name)) {
                                    calls.add("getMoreResults");
                                    return Boolean.FALSE;
                                }
                                if ("getUpdateCount".equals(name)) {
                                    return Integer.valueOf(1);
                                }
                                if ("isClosed".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                Class<?> returnType = method.getReturnType();
                                if (Boolean.TYPE.equals(returnType)) {
                                    return Boolean.FALSE;
                                }
                                if (Integer.TYPE.equals(returnType)) {
                                    return Integer.valueOf(0);
                                }
                                if (Long.TYPE.equals(returnType)) {
                                    return Long.valueOf(0L);
                                }
                                return null;
                            }
                        });
    }

    private static PreparedStatement newPreparedStatementProxy(final List<String> calls) {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        CompatibilityGapPreventionTest.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("setInt".equals(method.getName())) {
                                    calls.add("setInt:" + args[0] + ":" + args[1]);
                                    return null;
                                }
                                if ("setLong".equals(method.getName())) {
                                    calls.add("setLong:" + args[0] + ":" + args[1]);
                                    return null;
                                }
                                if ("setBoolean".equals(method.getName())) {
                                    calls.add("setBoolean:" + args[0] + ":" + args[1]);
                                    return null;
                                }
                                if ("executeQuery".equals(method.getName())) {
                                    return newResultSetProxy();
                                }
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.FALSE;
                                }
                                Class<?> returnType = method.getReturnType();
                                if (Boolean.TYPE.equals(returnType)) {
                                    return Boolean.FALSE;
                                }
                                if (Integer.TYPE.equals(returnType)) {
                                    return Integer.valueOf(0);
                                }
                                if (Long.TYPE.equals(returnType)) {
                                    return Long.valueOf(0L);
                                }
                                return null;
                            }
                        });
    }

    private static ResultSet newResultSetProxy() {
        return (ResultSet)
                Proxy.newProxyInstance(
                        CompatibilityGapPreventionTest.class.getClassLoader(),
                        new Class[] {ResultSet.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                Class<?> returnType = method.getReturnType();
                                if (Boolean.TYPE.equals(returnType)) {
                                    return Boolean.FALSE;
                                }
                                if (Integer.TYPE.equals(returnType)) {
                                    return Integer.valueOf(0);
                                }
                                if (Long.TYPE.equals(returnType)) {
                                    return Long.valueOf(0L);
                                }
                                return null;
                            }
                        });
    }
}
