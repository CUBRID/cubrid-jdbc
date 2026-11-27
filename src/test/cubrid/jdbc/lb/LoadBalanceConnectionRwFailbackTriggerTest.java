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

import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.EndpointConnManager;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.route.Router.RouteTarget;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Where RW failback is triggered from, and where it must stay silent:
 *
 * <ul>
 *   <li>statement path — an autocommit session corrects itself on its next statement, a read on its
 *       own leg included, unless the write leg still holds an open transaction;
 *   <li>transaction boundary — a manual-commit session (transaction-pinned even between commits)
 *       corrects itself on {@code commit()}/{@code rollback()}, never mid-transaction;
 *   <li>pool probe — never: {@code isValid()} only checks the write socket;
 *   <li>throttle — at most one probe per configured interval per session.
 * </ul>
 */
public class LoadBalanceConnectionRwFailbackTriggerTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final Endpoint NODE1_RW = new Endpoint("node1", 33000); // master
    private static final Endpoint NODE2_RW = new Endpoint("node2", 33000); // sibling RW
    private static final Endpoint NODE2_RO = new Endpoint("node2", 33002);
    private static final Endpoint NODE3_RO = new Endpoint("node3", 33002); // NODE2_RO's sibling

    private final Set<String> down = new HashSet<String>();

    @After
    public void clearUnreachable() {
        for (Endpoint ep : new Endpoint[] {NODE1_RW, NODE2_RW, NODE2_RO, NODE3_RO}) {
            UUnreachableHostList.getInstance().remove(ep.getId());
        }
    }

    /**
     * Failback closes the connection it leaves, and with it every result set on it. An outer cursor
     * iterated around an inner query must survive the inner query triggering failback.
     */
    @Test
    public void failbackWaitsWhileACursorIsOpenOnTheLegItWouldReplace() throws SQLException {
        List<CursorConnection> opened = new ArrayList<CursorConnection>();
        LoadBalanceConnection conn = displacedConnection(tuning(0), false, cursorFactory(opened));
        try {
            ResultSet outer = conn.createStatement().executeQuery("SELECT a FROM t");
            CursorConnection node2 = openedFor(opened, NODE2_RW);

            markUp(NODE1_RW);
            conn.createStatement().executeQuery("SELECT b FROM t").close();

            assertFalse("the outer cursor's connection was closed under it", node2.closed);
            assertEquals(NODE2_RW, conn.getCurrentRwEndpoint());

            outer.close();
            conn.createStatement().executeQuery("SELECT c FROM t").close();

            assertEquals(
                    "failback resumes once no cursor is open",
                    NODE1_RW,
                    conn.getCurrentRwEndpoint());
        } finally {
            conn.close();
        }
    }

    /** The same on the read leg: returning home must not close a cursor open on the sibling. */
    @Test
    public void readFailbackWaitsWhileACursorIsOpenOnTheSibling() throws SQLException {
        EndpointTopology topo =
                new EndpointTopology(
                        Arrays.asList(NODE1_RW),
                        Arrays.asList(NODE2_RO, NODE3_RO),
                        Collections.<Endpoint>emptyList());
        LoadBalanceSettings config = LbTestSettings.forTopology(topo);
        LbTestSettings.with(config, "writeFailbackProbeIntervalMs", Integer.valueOf(0));
        LbTestSettings.with(config, "readFailbackProbeIntervalMs", Integer.valueOf(0));
        List<CursorConnection> opened = new ArrayList<CursorConnection>();
        LoadBalanceConnection conn = new LoadBalanceConnection(config);
        conn.setConnectionManager(
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), config, cursorFactory(opened)));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topo);
        try {
            assertEquals(NODE2_RO, conn.getCurrentRoEndpoint());
            markDown(NODE2_RO);
            conn.recoverPhyBinding(
                    conn.buildRecoveryCtx(RouteTarget.TO_READ_ONLY, NODE2_RO),
                    new SQLException("comm fail", null, UErrorCode.ER_COMMUNICATION));
            assertEquals(NODE3_RO, conn.getCurrentRoEndpoint());

            ResultSet outer = conn.createStatement().executeQuery("SELECT a FROM t");
            CursorConnection node3 = openedFor(opened, NODE3_RO);

            markUp(NODE2_RO);
            conn.createStatement().executeQuery("SELECT b FROM t").close();

            assertFalse("the outer cursor's connection was closed under it", node3.closed);
            assertEquals(NODE3_RO, conn.getCurrentRoEndpoint());

            outer.close();
            conn.createStatement().executeQuery("SELECT c FROM t").close();

            assertEquals(
                    "read failback resumes once no cursor is open",
                    NODE2_RO,
                    conn.getCurrentRoEndpoint());
        } finally {
            conn.close();
        }
    }

    /**
     * Core leaves an autocommit transaction open after an OID write or a CALL, and ends it when the
     * next statement on that connection completes. Failing back first closes the connection that
     * holds it, and the server rolls the work back.
     */
    @Test
    public void failbackWaitsWhileTheWriteLegHoldsAnOpenTransaction() throws SQLException {
        final List<TxConnection> opened = new ArrayList<TxConnection>();
        LoadBalanceConnection conn = displacedConnection(tuning(0), true, txFactory(opened));
        try {
            final TxConnection sibling = txOpenedFor(opened, NODE2_RW);
            sibling.server.report(true);
            markUp(NODE1_RW);

            executeWrite(conn);
            assertEquals(NODE2_RW, conn.getCurrentRwEndpoint());
            assertFalse(sibling.closed);

            sibling.server.report(false);
            executeWrite(conn);
            assertEquals(NODE1_RW, conn.getCurrentRwEndpoint());
        } finally {
            conn.close();
        }
    }

    @Test
    public void autocommitWriteTriggersFailback() throws SQLException {
        LoadBalanceConnection conn = displacedConnection(tuning(0));
        try {
            assertEquals(NODE2_RW, conn.getCurrentRwEndpoint());

            markUp(NODE1_RW);
            executeWrite(conn);

            assertEquals(NODE1_RW, conn.getCurrentRwEndpoint());
        } finally {
            conn.close();
        }
    }

    /** A session that only reads would keep a CAS of the sibling RW broker until its next write. */
    @Test
    public void readOnItsOwnLegTriggersFailback() throws SQLException {
        LoadBalanceConnection conn = displacedConnection(tuning(0));
        try {
            assertEquals(NODE2_RW, conn.getCurrentRwEndpoint());
            assertEquals(NODE2_RO, conn.getCurrentRoEndpoint());

            markUp(NODE1_RW);
            conn.createStatementProvider(
                            ResultSet.TYPE_FORWARD_ONLY,
                            ResultSet.CONCUR_READ_ONLY,
                            ResultSet.HOLD_CURSORS_OVER_COMMIT)
                    .getStatement(RouteTarget.TO_READ_ONLY, "SELECT 1 FROM db_root");

            assertEquals(NODE1_RW, conn.getCurrentRwEndpoint());
            assertEquals(NODE2_RO, conn.getCurrentRoEndpoint());
        } finally {
            conn.close();
        }
    }

    @Test
    public void masterReadTriggersFailbackWhenReadRidesOnRw() throws SQLException {
        // No RO broker: reads are served by the RW connection, so a read is enough of a trigger.
        LoadBalanceConnection conn = displacedConnection(tuning(0), false);
        try {
            assertEquals(NODE2_RW, conn.getCurrentRwEndpoint());

            markUp(NODE1_RW);
            conn.createStatementProvider(
                            ResultSet.TYPE_FORWARD_ONLY,
                            ResultSet.CONCUR_READ_ONLY,
                            ResultSet.HOLD_CURSORS_OVER_COMMIT)
                    .getStatement(RouteTarget.TO_READ_ONLY, "SELECT 1 FROM db_root");

            assertEquals(NODE1_RW, conn.getCurrentRwEndpoint());
            assertEquals(NODE1_RW, conn.getCurrentRoEndpoint());
        } finally {
            conn.close();
        }
    }

    @Test
    public void manualCommitFailsBackOnCommitNotMidTransaction() throws SQLException {
        LoadBalanceConnection conn = displacedConnection(tuning(0));
        try {
            conn.setAutoCommit(false);
            markUp(NODE1_RW);

            executeWrite(conn);
            assertEquals(
                    "must not swap the RW connection inside a transaction",
                    NODE2_RW,
                    conn.getCurrentRwEndpoint());

            conn.commit();
            assertEquals(NODE1_RW, conn.getCurrentRwEndpoint());
        } finally {
            conn.close();
        }
    }

    @Test
    public void manualCommitFailsBackOnRollback() throws SQLException {
        LoadBalanceConnection conn = displacedConnection(tuning(0));
        try {
            conn.setAutoCommit(false);
            markUp(NODE1_RW);
            executeWrite(conn);

            conn.rollback();
            assertEquals(NODE1_RW, conn.getCurrentRwEndpoint());
        } finally {
            conn.close();
        }
    }

    @Test
    public void poolProbeDoesNotFailBackByDefault() throws SQLException {
        LoadBalanceConnection conn = displacedConnection(tuning(0));
        try {
            markUp(NODE1_RW);
            conn.isValid(0);
            assertEquals(NODE2_RW, conn.getCurrentRwEndpoint());
        } finally {
            conn.close();
        }
    }

    /** A session at home has nothing to fail back: its statements are not scanned for a cursor. */
    @Test
    public void sessionAtHomeAsksOnlyWhetherItIsDisplaced() throws SQLException {
        final List<String> asked = new ArrayList<String>();
        LoadBalanceSettings config = LbTestSettings.forTopology(topology(true));
        LbTestSettings.with(config, "writeFailbackProbeIntervalMs", Integer.valueOf(0));
        LbTestSettings.with(config, "readFailbackProbeIntervalMs", Integer.valueOf(0));
        final EndpointConnManager manager =
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), config, factory());
        LoadBalanceConnection conn = new LoadBalanceConnection(config);
        conn.setConnectionManager(
                (EndpointConnManager)
                        Proxy.newProxyInstance(
                                EndpointConnManager.class.getClassLoader(),
                                new Class<?>[] {EndpointConnManager.class},
                                new InvocationHandler() {
                                    public Object invoke(
                                            final Object proxy, final Method m, final Object[] a)
                                            throws Throwable {
                                        asked.add(m.getName());
                                        try {
                                            return m.invoke(manager, a);
                                        } catch (InvocationTargetException failed) {
                                            throw failed.getCause();
                                        }
                                    }
                                }));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topology(true));
        try {
            asked.clear();
            conn.createStatementProvider(
                            ResultSet.TYPE_FORWARD_ONLY,
                            ResultSet.CONCUR_READ_ONLY,
                            ResultSet.HOLD_CURSORS_OVER_COMMIT)
                    .getStatement(RouteTarget.TO_READ_ONLY, "SELECT 1 FROM db_root");
            executeWrite(conn);

            assertTrue(asked.toString(), asked.contains("isReadDisplaced"));
            assertTrue(asked.toString(), asked.contains("isRwDisplaced"));
            assertFalse(asked.toString(), asked.contains("boundConnection"));
            assertFalse(asked.toString(), asked.contains("restoreRoIfRecovered"));
            assertFalse(asked.toString(), asked.contains("restoreRwIfRecovered"));
        } finally {
            conn.close();
        }
    }

    @Test
    public void disabledByOption() throws SQLException {
        Map<String, Object> cfg = tuning(0);
        cfg.put("writeFailbackEnabled", Boolean.FALSE);
        LoadBalanceConnection conn = displacedConnection(cfg);
        try {
            markUp(NODE1_RW);
            executeWrite(conn);
            assertEquals(NODE2_RW, conn.getCurrentRwEndpoint());
        } finally {
            conn.close();
        }
    }

    @Test
    public void probeIsThrottledPerInterval() throws SQLException {
        LoadBalanceConnection conn = displacedConnection(tuning(60000));
        try {
            // First write probes while the master is still down: the attempt is consumed.
            executeWrite(conn);
            assertEquals(NODE2_RW, conn.getCurrentRwEndpoint());

            // Master recovers, but the next write is inside the throttle window -> no probe.
            markUp(NODE1_RW);
            executeWrite(conn);
            assertEquals(NODE2_RW, conn.getCurrentRwEndpoint());
        } finally {
            conn.close();
        }
    }

    /** The interval is read from the monotonic clock, whose readings span the whole long range. */
    @Test
    public void throttleIntervalIsMeasuredOnTheMonotonicClock() {
        final Long last = Long.valueOf(Long.MAX_VALUE - 5L);
        final long justInside = last.longValue() + 2999L * 1000000L; // wraps past the long range
        final long atTheInterval = last.longValue() + 3000L * 1000000L;

        assertFalse("before the first attempt", LegFailback.attemptedWithin(null, -1L, 3000L));
        assertTrue(LegFailback.attemptedWithin(last, justInside, 3000L));
        assertFalse(LegFailback.attemptedWithin(last, atTheInterval, 3000L));
        assertFalse("no interval", LegFailback.attemptedWithin(last, last.longValue(), 0L));
    }

    private void executeWrite(final LoadBalanceConnection conn) throws SQLException {
        conn.createStatementProvider(
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY,
                        ResultSet.HOLD_CURSORS_OVER_COMMIT)
                .getStatement(RouteTarget.TO_READ_WRITE, "UPDATE t SET a = 1");
    }

    /** Pinned-default overrides by {@code LoadBalanceSettings} field name. */
    private static Map<String, Object> tuning(final int probeIntervalMs) {
        Map<String, Object> cfg = new LinkedHashMap<String, Object>();
        cfg.put("writeFailbackProbeIntervalMs", Integer.valueOf(probeIntervalMs));
        return cfg;
    }

    private LoadBalanceConnection displacedConnection(final Map<String, Object> cfg)
            throws SQLException {
        return displacedConnection(cfg, true);
    }

    /**
     * A session bound with the master RW down, so its RW leg starts displaced on the sibling — the
     * state a real session ends up in when the master broker is down or CAS-saturated at bind time.
     */
    private LoadBalanceConnection displacedConnection(
            final Map<String, Object> cfg, final boolean withRoBroker) throws SQLException {
        return displacedConnection(cfg, withRoBroker, factory());
    }

    private LoadBalanceConnection displacedConnection(
            final Map<String, Object> cfg,
            final boolean withRoBroker,
            final JdbcConnectionFactory factory)
            throws SQLException {
        LoadBalanceSettings config = LbTestSettings.forTopology(topology(withRoBroker));
        for (Map.Entry<String, Object> e : cfg.entrySet()) {
            LbTestSettings.with(config, e.getKey(), e.getValue());
        }
        LoadBalanceConnection conn = new LoadBalanceConnection(config);
        conn.setConnectionManager(
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), config, factory));
        conn.setSharedSelectorState(new SharedSelectorState());

        markDown(NODE1_RW);
        conn.initSessionBindings(topology(withRoBroker));
        return conn;
    }

    private static EndpointTopology topology(final boolean withRoBroker) {
        return new EndpointTopology(
                Arrays.asList(NODE1_RW, NODE2_RW),
                withRoBroker ? Arrays.asList(NODE2_RO) : Collections.<Endpoint>emptyList(),
                Collections.<Endpoint>emptyList());
    }

    private JdbcConnectionFactory factory() {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info)
                    throws SQLException {
                for (String epId : down) {
                    if (url.contains(":" + epId + ":")) {
                        throw new SQLException(
                                "down: " + epId, null, UErrorCode.CAS_ER_FREE_SERVER);
                    }
                }
                return physicalConnection();
            }
        };
    }

    private void markDown(final Endpoint ep) {
        down.add(ep.getId());
        UUnreachableHostList.getInstance().add(ep.getId());
    }

    private void markUp(final Endpoint ep) {
        down.remove(ep.getId());
        UUnreachableHostList.getInstance().remove(ep.getId());
    }

    private JdbcConnectionFactory cursorFactory(final List<CursorConnection> opened) {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info)
                    throws SQLException {
                for (String epId : down) {
                    if (url.contains(":" + epId + ":")) {
                        throw new SQLException(
                                "down: " + epId, null, UErrorCode.CAS_ER_FREE_SERVER);
                    }
                }
                CursorConnection conn = new CursorConnection(url);
                opened.add(conn);
                return conn.proxy;
            }
        };
    }

    private JdbcConnectionFactory txFactory(final List<TxConnection> opened) {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info)
                    throws SQLException {
                for (String epId : down) {
                    if (url.contains(":" + epId + ":")) {
                        throw new SQLException(
                                "down: " + epId, null, UErrorCode.CAS_ER_FREE_SERVER);
                    }
                }
                TxConnection conn = new TxConnection(url);
                opened.add(conn);
                return conn;
            }
        };
    }

    private static TxConnection txOpenedFor(final List<TxConnection> opened, final Endpoint ep) {
        for (TxConnection conn : opened) {
            if (conn.url.contains(":" + ep.getId() + ":")) {
                return conn;
            }
        }
        throw new AssertionError("no physical connection was opened to " + ep.getId());
    }

    /** What the server last reported on a connection: whether a transaction is open there. */
    private static final class ServerTxStatus extends UConnection {
        void report(final boolean txOpen) {
            casInfo = new byte[] {(byte) (txOpen ? 1 : 0), 0, 0, 0};
        }

        public void endTransaction(final boolean type) {}

        protected void closeInternal() {}

        public void setAutoCommit(final boolean autoCommit) {}

        public boolean getAutoCommit() {
            return true;
        }
    }

    /** A physical connection that reports its server's transaction status. */
    private static final class TxConnection extends FakePhysicalConnection {
        final String url;
        final ServerTxStatus server = new ServerTxStatus();
        boolean closed;

        TxConnection(final String url) {
            this.url = url;
            server.report(false);
        }

        @Override
        protected Object dispatch(final String name, final Object[] args) {
            if ("getUConnection".equals(name)) {
                return server;
            }
            if ("close".equals(name)) {
                closed = true;
            }
            if ("isClosed".equals(name)) {
                return Boolean.valueOf(closed);
            }
            if ("isValid".equals(name)) {
                return Boolean.valueOf(!closed);
            }
            return null;
        }
    }

    private static CursorConnection openedFor(
            final List<CursorConnection> opened, final Endpoint ep) {
        for (CursorConnection conn : opened) {
            if (conn.url.contains(":" + ep.getId() + ":")) {
                return conn;
            }
        }
        throw new AssertionError("no physical connection was opened to " + ep.getId());
    }

    /** A physical connection whose statements open result sets that stay open until closed. */
    private static final class CursorConnection implements InvocationHandler {

        final String url;

        final Connection proxy;

        boolean closed;

        CursorConnection(final String url) {
            this.url = url;
            this.proxy =
                    (Connection)
                            Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class[] {Connection.class},
                                    this);
        }

        public Object invoke(final Object p, final Method method, final Object[] args) {
            String name = method.getName();
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return Boolean.valueOf(closed);
            }
            if ("createStatement".equals(name)) {
                return statement();
            }
            return zeroOf(method.getReturnType());
        }

        private Statement statement() {
            return (Statement)
                    Proxy.newProxyInstance(
                            Statement.class.getClassLoader(),
                            new Class[] {Statement.class},
                            new InvocationHandler() {
                                private ResultSet current;

                                public Object invoke(
                                        final Object p, final Method method, final Object[] args) {
                                    String name = method.getName();
                                    if ("executeQuery".equals(name)) {
                                        current = cursor();
                                        return current;
                                    }
                                    if ("getResultSet".equals(name)) {
                                        return current;
                                    }
                                    if ("getConnection".equals(name)) {
                                        return proxy;
                                    }
                                    return zeroOf(method.getReturnType());
                                }
                            });
        }

        private ResultSet cursor() {
            return (ResultSet)
                    Proxy.newProxyInstance(
                            ResultSet.class.getClassLoader(),
                            new Class[] {ResultSet.class},
                            new InvocationHandler() {
                                private boolean rsClosed;

                                public Object invoke(
                                        final Object p, final Method method, final Object[] args) {
                                    String name = method.getName();
                                    if ("close".equals(name)) {
                                        rsClosed = true;
                                        return null;
                                    }
                                    if ("isClosed".equals(name)) {
                                        return Boolean.valueOf(rsClosed || closed);
                                    }
                                    return zeroOf(method.getReturnType());
                                }
                            });
        }

        private static Object zeroOf(final Class<?> type) {
            if (Boolean.TYPE.equals(type)) {
                return Boolean.FALSE;
            }
            if (Integer.TYPE.equals(type)) {
                return Integer.valueOf(0);
            }
            if (Long.TYPE.equals(type)) {
                return Long.valueOf(0L);
            }
            return null;
        }
    }

    private static Connection physicalConnection() {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            private boolean closed;

                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                String name = method.getName();
                                if ("close".equals(name)) {
                                    closed = true;
                                    return null;
                                }
                                if ("isClosed".equals(name)) {
                                    return Boolean.valueOf(closed);
                                }
                                if ("isValid".equals(name)) {
                                    return Boolean.valueOf(!closed);
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                if (rt.equals(Integer.TYPE)) {
                                    return Integer.valueOf(0);
                                }
                                return null;
                            }
                        });
    }
}
