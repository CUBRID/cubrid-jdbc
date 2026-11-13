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

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Core keeps a CallableStatement usable across its own failback. LB hands out the physical one, so
 * the RW failback that would close its leg waits until the application has closed it.
 */
public final class CallableFailbackTest {

    private static final Endpoint NODE1 = new Endpoint("cfbnode1", 33000);
    private static final Endpoint NODE2 = new Endpoint("cfbnode2", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("cfbnode2", 33002);

    private final Set<String> down = new HashSet<String>();
    private LoadBalanceConnection conn;

    @Before
    public void setUp() throws SQLException {
        EndpointTopology topo =
                new EndpointTopology(
                        Arrays.asList(NODE1, NODE2),
                        Arrays.asList(NODE2_RO),
                        Collections.<Endpoint>emptyList());
        LoadBalanceSettings settings =
                LbTestSettings.with(
                        LbTestSettings.forTopology(topo),
                        "writeFailbackProbeIntervalMs",
                        Integer.valueOf(0));
        conn = new LoadBalanceConnection(settings);
        conn.setConnectionManager(
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        new Properties(),
                        settings,
                        new JdbcConnectionFactory() {
                            public Connection getConnection(final String url, final Properties i)
                                    throws SQLException {
                                for (String endpoint : down) {
                                    if (url.contains(":" + endpoint + ":")) {
                                        throw new SQLException(
                                                "down", null, UErrorCode.CAS_ER_FREE_SERVER);
                                    }
                                }
                                return leg();
                            }
                        }));
        conn.setSharedSelectorState(new SharedSelectorState());
        down.add(NODE1.getId());
        UUnreachableHostList.getInstance().add(NODE1.getId());
        conn.initSessionBindings(topo);
    }

    @After
    public void tearDown() throws SQLException {
        conn.close();
        UUnreachableHostList.getInstance().remove(NODE1.getId());
    }

    @Test
    public void rwFailbackWaitsUntilACallableStatementOnTheLegIsClosed() throws SQLException {
        assertEquals(NODE2, conn.getCurrentRwEndpoint());
        CallableStatement cs = conn.prepareCall("CALL p()");
        down.remove(NODE1.getId());
        UUnreachableHostList.getInstance().remove(NODE1.getId());

        conn.createStatement().executeUpdate("UPDATE t SET a = 1");

        assertEquals(NODE2, conn.getCurrentRwEndpoint());
        assertFalse(cs.isClosed());

        cs.close();
        conn.createStatement().executeUpdate("UPDATE t SET a = 2");

        assertEquals(NODE1, conn.getCurrentRwEndpoint());
    }

    /** A physical leg whose statements close with it. */
    private static Connection leg() {
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
                                if ("createStatement".equals(name)) {
                                    return statement(Statement.class, closed);
                                }
                                if ("prepareCall".equals(name)) {
                                    return statement(CallableStatement.class, closed);
                                }
                                return zero(m.getReturnType());
                            }
                        });
    }

    private static Object statement(final Class<?> type, final boolean[] legClosed) {
        final boolean[] closed = {false};
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                new InvocationHandler() {
                    public Object invoke(final Object p, final Method m, final Object[] a) {
                        String name = m.getName();
                        if ("close".equals(name)) {
                            closed[0] = true;
                            return null;
                        }
                        if ("isClosed".equals(name)) {
                            return Boolean.valueOf(closed[0] || legClosed[0]);
                        }
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
        return null;
    }
}
