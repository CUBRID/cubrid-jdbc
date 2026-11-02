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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Core reconnects before it sends a request, so a write meeting a dead broker runs on the next
 * host. A failure to connect (-21013) means nothing was sent: LB runs the write, or the batch, once
 * more on the rebound leg. A write that may have been sent (-21003) is not run again.
 */
public final class NotSentWriteFailoverTest {

    private static final Endpoint NODE1 = new Endpoint("nsnode1", 33000);
    private static final Endpoint NODE2 = new Endpoint("nsnode2", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("nsnode2", 33002);

    private final List<String> ranOnNode2 = new ArrayList<String>();
    private int node1Failure;
    private LoadBalanceConnection conn;

    @Before
    public void setUp() throws SQLException {
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
                            public Connection getConnection(final String url, final Properties i)
                                    throws SQLException {
                                final boolean node1 = url.contains(":" + NODE1.getId() + ":");
                                if (node1 && node1Failure != 0) {
                                    throw new SQLException("down", null, UErrorCode.ER_CONNECTION);
                                }
                                return leg(node1);
                            }
                        }));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topo);
    }

    @After
    public void tearDown() throws SQLException {
        conn.close();
        UUnreachableHostList.getInstance().remove(NODE1.getId());
    }

    @Test
    public void aWriteThatFailedToConnectRunsAgainOnTheReboundLeg() throws SQLException {
        Statement statement = conn.createStatement();
        node1Failure = UErrorCode.ER_CONNECTION;

        assertEquals(1, statement.executeUpdate("INSERT INTO t VALUES (1)"));

        assertEquals(NODE2, conn.getCurrentRwEndpoint());
        assertEquals(Arrays.asList("INSERT INTO t VALUES (1)"), ranOnNode2);
    }

    @Test
    public void aBatchThatFailedToConnectRunsAgainOnTheReboundLeg() throws SQLException {
        Statement statement = conn.createStatement();
        statement.addBatch("INSERT INTO t VALUES (1)");
        node1Failure = UErrorCode.ER_CONNECTION;

        assertArrayEquals(new int[] {1}, statement.executeBatch());

        assertEquals(NODE2, conn.getCurrentRwEndpoint());
        assertEquals(Arrays.asList("batch"), ranOnNode2);
    }

    @Test
    public void aPreparedBatchThatFailedToConnectRunsAgainOnTheReboundLeg() throws SQLException {
        PreparedStatement statement = conn.prepareStatement("INSERT INTO t VALUES (?)");
        statement.setInt(1, 1);
        statement.addBatch();
        node1Failure = UErrorCode.ER_CONNECTION;

        assertArrayEquals(new int[] {1}, statement.executeBatch());

        assertEquals(NODE2, conn.getCurrentRwEndpoint());
        assertEquals(Arrays.asList("batch"), ranOnNode2);
    }

    @Test
    public void aBatchThatMayHaveBeenSentIsReboundButNotRunAgain() throws SQLException {
        Statement statement = conn.createStatement();
        statement.addBatch("INSERT INTO t VALUES (1)");
        node1Failure = UErrorCode.ER_COMMUNICATION;
        try {
            statement.executeBatch();
            fail("a batch that may have run must not run again");
        } catch (SQLException expected) {
            assertEquals(UErrorCode.ER_COMMUNICATION, expected.getErrorCode());
        }

        assertEquals(NODE2, conn.getCurrentRwEndpoint());
        assertEquals(Collections.<String>emptyList(), ranOnNode2);
    }

    /** A physical leg; node1's statements fail with the test's code once it is set. */
    private Connection leg(final boolean node1) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(final Object p, final Method m, final Object[] a) {
                                String name = m.getName();
                                if ("createStatement".equals(name)) {
                                    return statement(Statement.class, node1, null);
                                }
                                if ("prepareStatement".equals(name)) {
                                    return statement(PreparedStatement.class, node1, (String) a[0]);
                                }
                                return zero(m.getReturnType());
                            }
                        });
    }

    private Object statement(final Class<?> type, final boolean node1, final String prepared) {
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                new InvocationHandler() {
                    public Object invoke(final Object p, final Method m, final Object[] a)
                            throws SQLException {
                        String name = m.getName();
                        if (!name.startsWith("execute")) {
                            return zero(m.getReturnType());
                        }
                        if (node1 && node1Failure != 0) {
                            throw new SQLException("broker gone", null, node1Failure);
                        }
                        if ("executeBatch".equals(name)) {
                            if (!node1) {
                                ranOnNode2.add("batch");
                            }
                            return new int[] {1};
                        }
                        if (!node1) {
                            ranOnNode2.add(prepared != null ? prepared : (String) a[0]);
                        }
                        return "executeUpdate".equals(name)
                                ? Integer.valueOf(1)
                                : zero(m.getReturnType());
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
