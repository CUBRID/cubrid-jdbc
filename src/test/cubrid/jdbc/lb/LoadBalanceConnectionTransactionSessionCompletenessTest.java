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
import static org.junit.Assert.assertNotNull;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class LoadBalanceConnectionTransactionSessionCompletenessTest {

    @Test
    public void assertSetSavepointDelegatesToMasterPhysical() throws Exception {
        Fixture fixture = createFixture();

        Savepoint actual = fixture.connection.setSavepoint();

        assertNotNull(actual);
        assertEquals(1, fixture.masterSetSavepointCalls.get());
        assertEquals(0, fixture.readOnlySetSavepointCalls.get());
    }

    @Test
    public void assertRollbackSavepointDelegatesToOwningPhysicalConnection() throws Exception {
        Fixture fixture = createFixture();
        Savepoint savepoint = fixture.connection.setSavepoint("sp1");

        fixture.connection.rollback(savepoint);

        assertEquals(1, fixture.masterRollbackSavepointCalls.get());
        assertEquals(0, fixture.readOnlyRollbackSavepointCalls.get());
    }

    @Test
    public void assertReleaseSavepointDelegatesToOwningPhysicalConnection() throws Exception {
        Fixture fixture = createFixture();
        Savepoint savepoint = fixture.connection.setSavepoint("sp2");

        fixture.connection.releaseSavepoint(savepoint);

        assertEquals(1, fixture.masterReleaseSavepointCalls.get());
        assertEquals(0, fixture.readOnlyReleaseSavepointCalls.get());
    }

    @Test
    public void assertSetReadOnlyPolicyIsConsistentWithRoutingRules() throws Exception {
        Fixture fixture = createFixture();

        fixture.connection.setReadOnly(true);
        assertFalse(fixture.connection.isReadOnly());
        fixture.connection.setReadOnly(false);
        assertFalse(fixture.connection.isReadOnly());
    }

    @Test
    public void assertCatalogApiBehaviorMatchesDocumentedContract() throws Exception {
        Fixture fixture = createFixture();

        fixture.connection.setCatalog("app");
        assertEquals("", fixture.connection.getCatalog());
        fixture.connection.setCatalog(null);
        assertEquals("", fixture.connection.getCatalog());
    }

    private static Fixture createFixture() throws Exception {
        Fixture result = new Fixture();
        result.connection = createBoundConnection();
        Connection master =
                newConnectionProxy(
                        result.masterSetSavepointCalls,
                        result.masterRollbackSavepointCalls,
                        result.masterReleaseSavepointCalls);
        Connection readOnly =
                newConnectionProxy(
                        result.readOnlySetSavepointCalls,
                        result.readOnlyRollbackSavepointCalls,
                        result.readOnlyReleaseSavepointCalls);
        bindPhysicalConnections(result.connection, master, readOnly);
        return result;
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
            final AtomicInteger setSavepointCalls,
            final AtomicInteger rollbackSavepointCalls,
            final AtomicInteger releaseSavepointCalls) {
        return (Connection)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionTransactionSessionCompletenessTest.class
                                .getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            private int savepointSeq;

                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                String name = method.getName();
                                if ("setSavepoint".equals(name)) {
                                    setSavepointCalls.incrementAndGet();
                                    if (args != null && args.length == 1) {
                                        return newSimpleSavepoint(
                                                ++savepointSeq, String.valueOf(args[0]));
                                    }
                                    return newSimpleSavepoint(++savepointSeq, null);
                                }
                                if ("rollback".equals(name)
                                        && args != null
                                        && args.length == 1
                                        && args[0] instanceof Savepoint) {
                                    rollbackSavepointCalls.incrementAndGet();
                                    return null;
                                }
                                if ("releaseSavepoint".equals(name)
                                        && args != null
                                        && args.length == 1
                                        && args[0] instanceof Savepoint) {
                                    releaseSavepointCalls.incrementAndGet();
                                    return null;
                                }
                                // core contract: setCatalog is a no-op, getCatalog returns "".
                                if ("getCatalog".equals(name)) {
                                    return "";
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

    private static Savepoint newSimpleSavepoint(final int id, final String name) {
        return new Savepoint() {
            public int getSavepointId() {
                return id;
            }

            public String getSavepointName() {
                return name;
            }
        };
    }

    private static final class Fixture {
        private LoadBalanceConnection connection;

        private final AtomicInteger masterSetSavepointCalls = new AtomicInteger();
        private final AtomicInteger readOnlySetSavepointCalls = new AtomicInteger();
        private final AtomicInteger masterRollbackSavepointCalls = new AtomicInteger();
        private final AtomicInteger readOnlyRollbackSavepointCalls = new AtomicInteger();
        private final AtomicInteger masterReleaseSavepointCalls = new AtomicInteger();
        private final AtomicInteger readOnlyReleaseSavepointCalls = new AtomicInteger();
    }
}
