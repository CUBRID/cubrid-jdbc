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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class LoadBalanceConnectionCallableDelegationTest {

    @Test
    public void assertPrepareCallDelegatesToMasterPhysicalConnection() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterPrepareCallCount = new AtomicInteger();
        AtomicInteger readOnlyPrepareCallCount = new AtomicInteger();
        Connection master =
                newConnectionProxy(masterPrepareCallCount, readOnlyPrepareCallCount, false);
        Connection readOnly = newConnectionProxy(new AtomicInteger(), new AtomicInteger(), false);
        bindPhysicalConnections(connection, master, readOnly);

        CallableStatement actual = connection.prepareCall("{call p()}");

        assertNotNull(actual);
        assertEquals(1, masterPrepareCallCount.get());
        assertEquals(0, readOnlyPrepareCallCount.get());
    }

    @Test
    public void assertPrepareCallOverloadsUseSameRoutingPolicy() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterPrepareCallCount = new AtomicInteger();
        AtomicInteger readOnlyPrepareCallCount = new AtomicInteger();
        Connection master =
                newConnectionProxy(masterPrepareCallCount, readOnlyPrepareCallCount, false);
        Connection readOnly = newConnectionProxy(new AtomicInteger(), new AtomicInteger(), false);
        bindPhysicalConnections(connection, master, readOnly);

        connection.prepareCall("{call p1()}");
        connection.prepareCall(
                "{call p2()}", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        connection.prepareCall(
                "{call p3()}",
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);

        assertEquals(3, masterPrepareCallCount.get());
        assertEquals(0, readOnlyPrepareCallCount.get());
    }

    @Test
    public void assertPrepareCallPreservesPhysicalSQLException() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        Connection master = newConnectionProxy(new AtomicInteger(), new AtomicInteger(), true);
        Connection readOnly = newConnectionProxy(new AtomicInteger(), new AtomicInteger(), false);
        bindPhysicalConnections(connection, master, readOnly);

        try {
            connection.prepareCall("{call p()}");
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertEquals("boom-prepareCall", ex.getMessage());
        }
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

    private static LoadBalanceConnection createSessionConnection() {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        return new LoadBalanceConnection(LoadBalanceSettings.of(properties));
    }

    private static EndpointTopology createTopologyWithSingleRo() {
        List<Endpoint> roEndpoints = new ArrayList<Endpoint>();
        roEndpoints.add(new Endpoint("ro1", 33000));
        return new EndpointTopology(new Endpoint("rw", 33000), roEndpoints, null);
    }

    private static Connection newConnectionProxy(
            final AtomicInteger masterPrepareCallCount,
            final AtomicInteger readOnlyPrepareCallCount,
            final boolean throwOnPrepareCall) {
        return (Connection)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionCallableDelegationTest.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                String name = method.getName();
                                if ("prepareCall".equals(name)) {
                                    masterPrepareCallCount.incrementAndGet();
                                    if (throwOnPrepareCall) {
                                        throw new SQLException("boom-prepareCall");
                                    }
                                    return newCallableStatementProxy();
                                }
                                if ("isClosed".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                if ("close".equals(name)) {
                                    return null;
                                }
                                if ("unwrap".equals(name)) {
                                    return null;
                                }
                                if ("isWrapperFor".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                if ("prepareStatement".equals(name)) {
                                    readOnlyPrepareCallCount.incrementAndGet();
                                    return null;
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

    private static CallableStatement newCallableStatementProxy() {
        return (CallableStatement)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionCallableDelegationTest.class.getClassLoader(),
                        new Class[] {CallableStatement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
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
