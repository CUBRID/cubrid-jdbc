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

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import cubrid.jdbc.lb.statement.LBStatement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class CommandCoverageFollowupTest {

    @Test
    public void assertFinalExecutionCasCommandsPreserveAffinity() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        LBStatement statement = (LBStatement) connection.createStatement();
        final Statement physicalStatement = newPhysicalStatementProxy();
        final List<String> calls = new ArrayList<String>();
        statement.setStatementProvider(
                new LBStatement.PhysicalStmtProvider() {
                    public Statement getStatement(
                            final cubrid.jdbc.lb.route.Router.RouteTarget target,
                            final String rawSql)
                            throws SQLException {
                        calls.add("provider:" + target);
                        return physicalStatement;
                    }
                });

        statement.execute("INSERT INTO t(id) VALUES (1)", Statement.RETURN_GENERATED_KEYS);
        ResultSet generatedKeys = statement.getGeneratedKeys();
        statement.getMoreResults();

        assertEquals(1, countPrefix(calls, "provider:"));
        assertSame(generatedKeys, statement.getGeneratedKeys());
    }

    @Test
    public void assertExecuteBatchStatementFollowsBatchClassificationRule() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        LBStatement statement = (LBStatement) connection.createStatement();
        final List<cubrid.jdbc.lb.route.Router.RouteTarget> targets =
                new ArrayList<cubrid.jdbc.lb.route.Router.RouteTarget>();
        statement.setStatementProvider(
                new LBStatement.PhysicalStmtProvider() {
                    public Statement getStatement(
                            final cubrid.jdbc.lb.route.Router.RouteTarget target,
                            final String rawSql)
                            throws SQLException {
                        targets.add(target);
                        return newPhysicalStatementProxy();
                    }
                });

        statement.addBatch("SELECT 1");
        statement.addBatch("SELECT 2");
        statement.executeBatch();
        statement.clearBatch();
        statement.addBatch("SELECT 1");
        statement.addBatch("INSERT INTO t(id) VALUES (1)");
        statement.executeBatch();

        assertEquals(cubrid.jdbc.lb.route.Router.RouteTarget.TO_READ_ONLY, targets.get(0));
        assertEquals(cubrid.jdbc.lb.route.Router.RouteTarget.TO_READ_WRITE, targets.get(1));
    }

    @Test
    public void assertSetCasChangeModeAppliesToAllBoundCasAndFutureBindings() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger firstRwCalls = new AtomicInteger();
        AtomicInteger firstReadOnlyCalls = new AtomicInteger();
        AtomicInteger secondRwCalls = new AtomicInteger();
        AtomicInteger secondReadOnlyCalls = new AtomicInteger();
        bindPhysicalConnections(
                connection,
                newPhysicalConnection(firstRwCalls),
                newPhysicalConnection(firstReadOnlyCalls));

        int previous = connection.setCASChangeMode(1);
        assertEquals(0, previous);
        assertEquals(1, firstRwCalls.get());
        assertEquals(1, firstReadOnlyCalls.get());

        bindPhysicalConnections(
                connection,
                newPhysicalConnection(secondRwCalls),
                newPhysicalConnection(secondReadOnlyCalls));
        // Rebinding the session re-applies the recorded mode to whatever physical connections the
        // manager hands back — that is the "future bindings" half of this contract.
        connection.initSessionBindings(createTopologyWithSingleRo());

        assertEquals(1, secondRwCalls.get());
        assertEquals(1, secondReadOnlyCalls.get());
    }

    @Test
    public void assertUnsupportedCommandGroupUsesConsistentErrorContract() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        assertUnsupportedPrefix(connection, "createArrayOf");
        assertUnsupportedPrefix(connection, "createStruct");
        assertUnsupportedPrefix(connection, "getTypeMap");
        assertUnsupportedPrefix(connection, "setTypeMap");
    }

    /**
     * {@code LBStatement} extends {@code CUBRIDStatement} so that {@code (CUBRIDStatement) stmt}
     * works unmodified, which means {@code getShardId()} is inherited and cannot be made to
     * disappear. Absence was never the contract — unusability is. Assert the refusal instead, and
     * that it names the API so the message is actionable.
     */
    @Test
    public void assertShardInfoVendorApiIsRefusedByLbStatement() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        LBStatement statement = (LBStatement) connection.createStatement();
        try {
            statement.getShardId();
            fail("LBStatement.getShardId() must refuse: SHARD is not served by an LB session");
        } catch (IllegalStateException expected) {
            assertTrue(
                    "message must name the refused API: " + expected.getMessage(),
                    expected.getMessage().indexOf("getShardId()") >= 0);
            assertTrue(expected.getCause() instanceof SQLException);
        }
    }

    private static void assertUnsupportedPrefix(
            final LoadBalanceConnection connection, final String api) throws Exception {
        try {
            if ("createArrayOf".equals(api)) {
                connection.createArrayOf("V", new Object[] {"x"});
            } else if ("createStruct".equals(api)) {
                connection.createStruct("S", new Object[] {Integer.valueOf(1)});
            } else if ("getTypeMap".equals(api)) {
                connection.getTypeMap();
            } else {
                connection.setTypeMap(null);
            }
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().startsWith("Not supported method"));
        }
    }

    private static Statement newPhysicalStatementProxy() {
        final ResultSet resultSet =
                (ResultSet)
                        Proxy.newProxyInstance(
                                CommandCoverageFollowupTest.class.getClassLoader(),
                                new Class[] {ResultSet.class},
                                new InvocationHandler() {
                                    public Object invoke(
                                            final Object proxy,
                                            final Method method,
                                            final Object[] args)
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
        return (Statement)
                Proxy.newProxyInstance(
                        CommandCoverageFollowupTest.class.getClassLoader(),
                        new Class[] {Statement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                String name = method.getName();
                                if ("execute".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                if ("executeBatch".equals(name)) {
                                    return new int[] {1};
                                }
                                if ("getGeneratedKeys".equals(name)) {
                                    return resultSet;
                                }
                                if ("getMoreResults".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                if ("getResultSet".equals(name)) {
                                    return resultSet;
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

    private static Connection newPhysicalConnection(final AtomicInteger casChangeModeCalls) {
        return new FakePhysicalConnection() {
            @Override
            protected Object dispatch(final String name, final Object[] args) {
                if ("setCASChangeMode".equals(name)) {
                    casChangeModeCalls.incrementAndGet();
                    return Integer.valueOf(0);
                }
                return null;
            }
        };
    }

    private static LoadBalanceConnection createSessionConnection() {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        return new LoadBalanceConnection(LoadBalanceSettings.of(properties));
    }

    private static LoadBalanceConnection createBoundConnection() throws Exception {
        LoadBalanceConnection result = createSessionConnection();
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        result.setConnectionManager(manager);
        result.setSharedSelectorState(new SharedSelectorState());
        result.initSessionBindings(createTopologyWithSingleRo());
        return result;
    }

    private static void bindPhysicalConnections(
            final LoadBalanceConnection connection,
            final Connection master,
            final Connection readOnly)
            throws Exception {
        SimpleEndpointConnManager manager =
                (SimpleEndpointConnManager) connection.getConnectionManager();
        manager.setPhysicalConnection(connection.getCurrentEp(SessionLeg.RW), master);
        manager.setPhysicalConnection(connection.getCurrentEp(SessionLeg.RO), readOnly);
    }

    private static EndpointTopology createTopologyWithSingleRo() {
        List<Endpoint> roEndpoints = new ArrayList<Endpoint>();
        roEndpoints.add(new Endpoint("ro1", 33000));
        return new EndpointTopology(new Endpoint("rw", 33000), roEndpoints, null);
    }

    private static int countPrefix(final List<String> values, final String prefix) {
        int result = 0;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).startsWith(prefix)) {
                result++;
            }
        }
        return result;
    }
}
