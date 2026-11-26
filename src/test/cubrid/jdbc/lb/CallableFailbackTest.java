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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.CUBRIDJDBCErrorCode;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Core keeps a CallableStatement usable across its own failover and failback: the socket is
 * replaced under the same connection and the statement is prepared again at its next run. Here the
 * write leg is replaced by another physical connection, so the statement prepares the CALL again on
 * it, with what was set, and a failback does not wait for it.
 */
public final class CallableFailbackTest {

    private static final Endpoint NODE1 = new Endpoint("cfbnode1", 33000);
    private static final Endpoint NODE2 = new Endpoint("cfbnode2", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("cfbnode2", 33002);

    private final Set<String> down = new HashSet<String>();
    // Every physical CallableStatement prepared, in order, with what it was asked.
    private final List<PhysicalCall> prepared = new ArrayList<PhysicalCall>();
    private LoadBalanceConnection conn;

    private void open(final boolean masterDown) throws SQLException {
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
                                for (Endpoint each : Arrays.asList(NODE1, NODE2, NODE2_RO)) {
                                    if (url.contains(":" + each.getId() + ":")) {
                                        if (down.contains(each.getId())) {
                                            throw new SQLException(
                                                    "down", null, UErrorCode.CAS_ER_FREE_SERVER);
                                        }
                                        return leg(each);
                                    }
                                }
                                throw new IllegalStateException(url);
                            }
                        }));
        conn.setSharedSelectorState(new SharedSelectorState());
        if (masterDown) {
            masterGoesDown();
        }
        conn.initSessionBindings(topo);
    }

    private void masterGoesDown() {
        down.add(NODE1.getId());
        UUnreachableHostList.getInstance().add(NODE1.getId());
    }

    private void masterComesBack() {
        down.remove(NODE1.getId());
        UUnreachableHostList.getInstance().remove(NODE1.getId());
    }

    @After
    public void tearDown() throws SQLException {
        conn.close();
        UUnreachableHostList.getInstance().remove(NODE1.getId());
        UUnreachableHostList.getInstance().remove(NODE2.getId());
    }

    private void write() throws SQLException {
        conn.createStatement().executeUpdate("UPDATE t SET a = 1");
    }

    @Test
    public void rwFailbackDoesNotWaitForACallableStatementLeftOpen() throws SQLException {
        open(true);
        assertEquals(NODE2, conn.getCurrentRwEndpoint());
        CallableStatement cs = conn.prepareCall("CALL p(?, ?)");
        masterComesBack();

        write();

        assertEquals(NODE1, conn.getCurrentRwEndpoint());
        assertFalse(cs.isClosed());
    }

    /** Failback closes the write connection, and with it a result set of the CALL still open. */
    @Test
    public void rwFailbackWaitsWhileAResultSetOfTheCallIsOpen() throws SQLException {
        open(true);
        CallableStatement cs = conn.prepareCall("CALL p(?, ?)");
        ResultSet rs = cs.executeQuery();
        masterComesBack();

        write();
        assertEquals(NODE2, conn.getCurrentRwEndpoint());
        assertFalse(rs.isClosed());

        rs.close();
        write();
        assertEquals(NODE1, conn.getCurrentRwEndpoint());
    }

    /** The same for a result set of SQL the statement ran on the write connection. */
    @Test
    public void rwFailbackWaitsWhileAResultSetOfSqlItRanIsOpen() throws SQLException {
        open(true);
        CallableStatement cs = conn.prepareCall("CALL p(?, ?)");
        ResultSet rs = cs.executeQuery("SELECT LAST_INSERT_ID() FROM db_root");
        masterComesBack();

        write();
        assertEquals(NODE2, conn.getCurrentRwEndpoint());
        assertFalse(rs.isClosed());

        rs.close();
        write();
        assertEquals(NODE1, conn.getCurrentRwEndpoint());
    }

    @Test
    public void afterAFailbackTheCallIsPreparedAgainWithWhatWasSet() throws SQLException {
        open(true);
        CallableStatement cs = conn.prepareCall("CALL p(?, ?)");
        cs.setInt(1, 4);
        cs.registerOutParameter(2, Types.INTEGER);
        cs.setQueryTimeout(7);
        cs.execute();
        masterComesBack();
        write();
        assertEquals(NODE1, conn.getCurrentRwEndpoint());

        cs.execute();

        assertEquals(2, prepared.size());
        assertEquals(NODE2, prepared.get(0).node);
        assertEquals(NODE1, prepared.get(1).node);
        assertEquals(
                Arrays.asList(
                        "setInt(1, 4)",
                        "registerOutParameter(2, 4)",
                        "setQueryTimeout(7)",
                        "execute()"),
                prepared.get(1).asked);
        assertFalse(cs.isClosed());
    }

    @Test
    public void aCallPreparedBeforeAFailoverRunsOnTheNewWriteLeg() throws SQLException {
        open(false);
        assertEquals(NODE1, conn.getCurrentRwEndpoint());
        CallableStatement cs = conn.prepareCall("CALL p(?, ?)");
        cs.setInt(1, 4);
        cs.registerOutParameter(2, Types.INTEGER);
        masterGoesDown();
        try {
            write();
            fail("a write the dead leg did not answer is reported");
        } catch (SQLException lost) {
            assertEquals(UErrorCode.ER_COMMUNICATION, lost.getErrorCode());
        }
        assertEquals(NODE2, conn.getCurrentRwEndpoint());

        cs.execute();

        assertEquals(NODE2, prepared.get(1).node);
        assertEquals(
                Arrays.asList("setInt(1, 4)", "registerOutParameter(2, 4)", "execute()"),
                prepared.get(1).asked);
    }

    @Test
    public void whileItsLegIsGoneWhatIsSetIsKeptAndTheDeadStatementIsAskedNothing()
            throws SQLException {
        open(true);
        CallableStatement cs = conn.prepareCall("CALL p(?, ?)");
        cs.setInt(1, 4);
        masterComesBack();
        write();
        final int askedOfTheDeadOne = prepared.get(0).asked.size();

        assertFalse(cs.isClosed());
        assertSame(conn, cs.getConnection());
        assertTrue(cs.isWrapperFor(CallableStatement.class));
        cs.clearWarnings();
        assertNull(cs.getWarnings());
        cs.clearBatch();
        cs.cancel();
        cs.clearParameters();
        cs.setInt(1, 9);
        cs.registerOutParameter(2, Types.INTEGER);

        assertEquals(1, prepared.size());
        assertEquals(askedOfTheDeadOne, prepared.get(0).asked.size());

        cs.execute();

        assertEquals(
                Arrays.asList("setInt(1, 9)", "registerOutParameter(2, 4)", "execute()"),
                prepared.get(1).asked);
    }

    @Test
    public void whatDescribesTheStatementPreparesItAgainWhenItsLegIsGone() throws SQLException {
        open(true);
        CallableStatement cs = conn.prepareCall("CALL p(?, ?)");
        cs.setQueryTimeout(7);
        masterComesBack();
        write();

        assertEquals(7, cs.getQueryTimeout());

        assertEquals(2, prepared.size());
        assertEquals(NODE1, prepared.get(1).node);
        assertEquals(
                Arrays.asList("setQueryTimeout(7)", "getQueryTimeout()"), prepared.get(1).asked);
    }

    @Test
    public void resultsOfARunBeforeTheLegWasReplacedAreRefused() throws SQLException {
        open(false);
        CallableStatement cs = conn.prepareCall("CALL p(?, ?)");
        cs.registerOutParameter(2, Types.INTEGER);
        cs.execute();
        masterGoesDown();
        try {
            write();
            fail("a write the dead leg did not answer is reported");
        } catch (SQLException lost) {
            assertEquals(UErrorCode.ER_COMMUNICATION, lost.getErrorCode());
        }

        try {
            cs.getInt(2);
            fail("the statement that ran is closed with its leg");
        } catch (SQLException closed) {
            assertEquals(CUBRIDJDBCErrorCode.prepared_statement_closed, closed.getErrorCode());
        }
        assertFalse(cs.isClosed());
    }

    @Test
    public void aClosedStatementIsNotPreparedAgain() throws SQLException {
        open(true);
        CallableStatement cs = conn.prepareCall("CALL p(?, ?)");
        cs.close();
        masterComesBack();
        write();

        assertTrue(cs.isClosed());
        try {
            cs.setInt(1, 4);
            fail("a closed statement refuses");
        } catch (SQLException closed) {
            assertEquals(CUBRIDJDBCErrorCode.prepared_statement_closed, closed.getErrorCode());
        }
        assertEquals(1, prepared.size());
    }

    @Test
    public void closingAStatementWhoseLegIsGoneReportsNothing() throws SQLException {
        open(true);
        CallableStatement cs = conn.prepareCall("CALL p(?, ?)");
        masterComesBack();
        write();
        assertEquals(NODE1, conn.getCurrentRwEndpoint());

        cs.close(); // core refuses to close some statements of a closed connection (-21101)

        assertTrue(cs.isClosed());
    }

    @Test
    public void closingTheConnectionClosesItsCallableStatements() throws SQLException {
        open(true);
        CallableStatement cs = conn.prepareCall("CALL p(?, ?)");

        conn.close();

        assertTrue(cs.isClosed());
    }

    /** One physical CallableStatement: where it was prepared and what it was asked. */
    private static final class PhysicalCall {
        private final Endpoint node;
        private final List<String> asked = new ArrayList<String>();

        PhysicalCall(final Endpoint node) {
            this.node = node;
        }
    }

    /** A physical leg whose statements close with it, and fail once its node is down. */
    private Connection leg(final Endpoint node) {
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
                                    return statement(
                                            Statement.class, (Connection) p, node, closed, null);
                                }
                                if ("prepareCall".equals(name)) {
                                    PhysicalCall call = new PhysicalCall(node);
                                    prepared.add(call);
                                    return statement(
                                            CallableStatement.class,
                                            (Connection) p,
                                            node,
                                            closed,
                                            call);
                                }
                                return zero(m.getReturnType());
                            }
                        });
    }

    private Object statement(
            final Class<?> type,
            final Connection leg,
            final Endpoint node,
            final boolean[] legClosed,
            final PhysicalCall record) {
        final boolean[] closed = {false};
        final int[] queryTimeout = {0};
        final ResultSet[] current = {null};
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                new InvocationHandler() {
                    public Object invoke(final Object p, final Method m, final Object[] a)
                            throws SQLException {
                        String name = m.getName();
                        if ("close".equals(name)) {
                            if (legClosed[0] && !closed[0]) {
                                throw new SQLException(
                                        "closed connection",
                                        null,
                                        CUBRIDJDBCErrorCode.connection_closed);
                            }
                            closed[0] = true;
                            return null;
                        }
                        if ("isClosed".equals(name)) {
                            return Boolean.valueOf(closed[0] || legClosed[0]);
                        }
                        if (closed[0] || legClosed[0]) {
                            throw new SQLException(
                                    "closed", null, CUBRIDJDBCErrorCode.prepared_statement_closed);
                        }
                        if (record != null) {
                            record.asked.add(name + args(a));
                        }
                        if (name.startsWith("execute") && down.contains(node.getId())) {
                            throw new SQLException("lost", null, UErrorCode.ER_COMMUNICATION);
                        }
                        if ("setQueryTimeout".equals(name)) {
                            queryTimeout[0] = ((Integer) a[0]).intValue();
                        }
                        if ("getQueryTimeout".equals(name)) {
                            return Integer.valueOf(queryTimeout[0]);
                        }
                        if ("getConnection".equals(name)) {
                            return leg;
                        }
                        if ("executeQuery".equals(name)) {
                            current[0] = resultSet(legClosed);
                            return current[0];
                        }
                        if ("getResultSet".equals(name)) {
                            return current[0];
                        }
                        return zero(m.getReturnType());
                    }
                });
    }

    /** A result set that is open until it or its leg is closed. */
    private static ResultSet resultSet(final boolean[] legClosed) {
        final boolean[] closed = {false};
        return (ResultSet)
                Proxy.newProxyInstance(
                        ResultSet.class.getClassLoader(),
                        new Class<?>[] {ResultSet.class},
                        new InvocationHandler() {
                            public Object invoke(final Object p, final Method m, final Object[] a) {
                                if ("close".equals(m.getName())) {
                                    closed[0] = true;
                                    return null;
                                }
                                if ("isClosed".equals(m.getName())) {
                                    return Boolean.valueOf(closed[0] || legClosed[0]);
                                }
                                return zero(m.getReturnType());
                            }
                        });
    }

    private static String args(final Object[] a) {
        return a == null ? "()" : Arrays.toString(a).replace('[', '(').replace(']', ')');
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
