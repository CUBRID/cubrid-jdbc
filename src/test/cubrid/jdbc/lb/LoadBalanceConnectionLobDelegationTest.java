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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.NClob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class LoadBalanceConnectionLobDelegationTest {

    @Test
    public void assertCreateBlobAndCreateClobDelegateToMasterPhysical() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterCreateBlobCalls = new AtomicInteger();
        AtomicInteger readOnlyCreateBlobCalls = new AtomicInteger();
        AtomicInteger masterCreateClobCalls = new AtomicInteger();
        AtomicInteger readOnlyCreateClobCalls = new AtomicInteger();
        AtomicInteger masterCreateNClobCalls = new AtomicInteger();
        AtomicInteger readOnlyCreateNClobCalls = new AtomicInteger();
        AtomicInteger masterCreateSQLXMLCalls = new AtomicInteger();
        AtomicInteger readOnlyCreateSQLXMLCalls = new AtomicInteger();
        Blob expectedBlob = newBlobProxy();
        Clob expectedClob = newClobProxy();
        NClob masterNClob = newNClobProxy();
        SQLXML masterSqlxml = newSQLXMLProxy();
        Blob readOnlyBlob = newBlobProxy();
        Clob readOnlyClob = newClobProxy();
        NClob readOnlyNClob = newNClobProxy();
        SQLXML readOnlySqlxml = newSQLXMLProxy();
        Connection masterConnection =
                newConnectionProxy(
                        expectedBlob,
                        expectedClob,
                        masterNClob,
                        masterSqlxml,
                        masterCreateBlobCalls,
                        masterCreateClobCalls,
                        masterCreateNClobCalls,
                        masterCreateSQLXMLCalls);
        Connection readOnlyConnection =
                newConnectionProxy(
                        readOnlyBlob,
                        readOnlyClob,
                        readOnlyNClob,
                        readOnlySqlxml,
                        readOnlyCreateBlobCalls,
                        readOnlyCreateClobCalls,
                        readOnlyCreateNClobCalls,
                        readOnlyCreateSQLXMLCalls);
        bindPhysicalConnections(connection, masterConnection, readOnlyConnection);

        Blob actualBlob = connection.createBlob();
        Clob actualClob = connection.createClob();

        assertSame(expectedBlob, actualBlob);
        assertSame(expectedClob, actualClob);
        assertEquals(1, masterCreateBlobCalls.get());
        assertEquals(1, masterCreateClobCalls.get());
        assertEquals(0, readOnlyCreateBlobCalls.get());
        assertEquals(0, readOnlyCreateClobCalls.get());
    }

    @Test
    public void assertAdvancedTypeApisFollowDocumentedContract() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterCreateBlobCalls = new AtomicInteger();
        AtomicInteger readOnlyCreateBlobCalls = new AtomicInteger();
        AtomicInteger masterCreateClobCalls = new AtomicInteger();
        AtomicInteger readOnlyCreateClobCalls = new AtomicInteger();
        AtomicInteger masterCreateNClobCalls = new AtomicInteger();
        AtomicInteger readOnlyCreateNClobCalls = new AtomicInteger();
        AtomicInteger masterCreateSQLXMLCalls = new AtomicInteger();
        AtomicInteger readOnlyCreateSQLXMLCalls = new AtomicInteger();
        Blob masterBlob = newBlobProxy();
        Clob masterClob = newClobProxy();
        NClob expectedNClob = newNClobProxy();
        SQLXML expectedSqlxml = newSQLXMLProxy();
        Blob readOnlyBlob = newBlobProxy();
        Clob readOnlyClob = newClobProxy();
        NClob readOnlyNClob = newNClobProxy();
        SQLXML readOnlySqlxml = newSQLXMLProxy();
        Connection masterConnection =
                newConnectionProxy(
                        masterBlob,
                        masterClob,
                        expectedNClob,
                        expectedSqlxml,
                        masterCreateBlobCalls,
                        masterCreateClobCalls,
                        masterCreateNClobCalls,
                        masterCreateSQLXMLCalls);
        Connection readOnlyConnection =
                newConnectionProxy(
                        readOnlyBlob,
                        readOnlyClob,
                        readOnlyNClob,
                        readOnlySqlxml,
                        readOnlyCreateBlobCalls,
                        readOnlyCreateClobCalls,
                        readOnlyCreateNClobCalls,
                        readOnlyCreateSQLXMLCalls);
        bindPhysicalConnections(connection, masterConnection, readOnlyConnection);

        NClob actualNClob = connection.createNClob();
        SQLXML actualSqlxml = connection.createSQLXML();

        assertSame(expectedNClob, actualNClob);
        assertSame(expectedSqlxml, actualSqlxml);
        assertEquals(1, masterCreateNClobCalls.get());
        assertEquals(0, readOnlyCreateNClobCalls.get());
        assertEquals(1, masterCreateSQLXMLCalls.get());
        assertEquals(0, readOnlyCreateSQLXMLCalls.get());
    }

    @Test
    public void assertUnsupportedApisThrowConsistentSQLException() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        try {
            connection.createArrayOf("VARCHAR", new Object[] {"a"});
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().startsWith("Not supported method"));
        }
        try {
            connection.createStruct("S", new Object[] {Integer.valueOf(1)});
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().startsWith("Not supported method"));
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
            final Blob blob,
            final Clob clob,
            final NClob nclob,
            final SQLXML sqlxml,
            final AtomicInteger createBlobCalls,
            final AtomicInteger createClobCalls,
            final AtomicInteger createNClobCalls,
            final AtomicInteger createSQLXMLCalls) {
        return (Connection)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionLobDelegationTest.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                String name = method.getName();
                                if ("createBlob".equals(name)) {
                                    createBlobCalls.incrementAndGet();
                                    return blob;
                                }
                                if ("createClob".equals(name)) {
                                    createClobCalls.incrementAndGet();
                                    return clob;
                                }
                                if ("createNClob".equals(name)) {
                                    createNClobCalls.incrementAndGet();
                                    return nclob;
                                }
                                if ("createSQLXML".equals(name)) {
                                    createSQLXMLCalls.incrementAndGet();
                                    return sqlxml;
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

    private static Blob newBlobProxy() {
        return (Blob)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionLobDelegationTest.class.getClassLoader(),
                        new Class[] {Blob.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                String name = method.getName();
                                if ("free".equals(name)) {
                                    return null;
                                }
                                if ("isWrapperFor".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                if ("unwrap".equals(name)) {
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

    private static Clob newClobProxy() {
        return (Clob)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionLobDelegationTest.class.getClassLoader(),
                        new Class[] {Clob.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                String name = method.getName();
                                if ("free".equals(name)) {
                                    return null;
                                }
                                if ("isWrapperFor".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                if ("unwrap".equals(name)) {
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

    private static NClob newNClobProxy() {
        return (NClob)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionLobDelegationTest.class.getClassLoader(),
                        new Class[] {NClob.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                String name = method.getName();
                                if ("free".equals(name)) {
                                    return null;
                                }
                                if ("isWrapperFor".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                if ("unwrap".equals(name)) {
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

    private static SQLXML newSQLXMLProxy() {
        return (SQLXML)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionLobDelegationTest.class.getClassLoader(),
                        new Class[] {SQLXML.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                if ("free".equals(method.getName())) {
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
}
