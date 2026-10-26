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

package cubrid.jdbc.lb.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.lb.LbTestAccess;
import cubrid.jdbc.lb.LbTestSettings;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.metrics.MetricsTestAccess;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * What LB does when the read leg cannot serve a statement it classified READ: a read the read-only
 * broker refuses as a write runs again on RW, and the session SETs that ran on RW are replayed on
 * the read leg (or keep reads on RW when they cannot be).
 */
public class ReadLegSafetyTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final int ER_DB_NO_MODIFICATIONS = -581;
    private static final int ER_SP_EXECUTE_ERROR = -889;

    private Legs legs;
    private LoadBalanceConnection conn;

    @Before
    public void setUp() throws SQLException {
        legs = new Legs();
        conn =
                LbTestAccess.newLoadBalanceConnection(
                        LbTestSettings.forBinding(
                                new Endpoint("rw", 33000), new Endpoint("ro", 33001)));
        LbTestAccess.getRuntimeMetrics(conn).setEnabled(true);
        LbTestAccess.getRuntimeMetrics(conn).setEventsEnabled(true);
        LbTestAccess.setConnectionManager(
                conn,
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        LOGICAL_URL,
                        new Properties(),
                        LbTestSettings.forBinding(
                                new Endpoint("rw", 33000), new Endpoint("ro", 33001)),
                        legs));
        LbTestAccess.setSharedSelectorState(conn, new SharedSelectorState());
        LbTestAccess.initSessionBindings(
                conn,
                new EndpointTopology(
                        new Endpoint("rw", 33000),
                        Arrays.asList(new Endpoint("ro", 33001)),
                        Collections.<Endpoint>emptyList()));
    }

    @After
    public void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    public void readRefusedAsWriteRunsOnceMoreOnRw() throws SQLException {
        legs.refuseOnRo("v_seq", ER_DB_NO_MODIFICATIONS);

        assertNotNull(conn.createStatement().executeQuery("SELECT * FROM v_seq"));

        assertEquals(1, legs.count("ro", "SELECT * FROM v_seq"));
        assertEquals(1, legs.count("rw", "SELECT * FROM v_seq"));
        assertTrue(lastRouteEvent().contains("routeTarget=TO_READ_WRITE"));
        assertTrue(lastRouteEvent().contains("fallbackReason=RO_WRITE_REJECTED"));
    }

    /** A routine that writes, called from a view, fails on the read node as -889. */
    @Test
    public void storedRoutineFailureOnReadLegIsAlsoRetried() throws SQLException {
        legs.refuseOnRo("v_fn", ER_SP_EXECUTE_ERROR);

        conn.createStatement().executeQuery("SELECT * FROM v_fn");

        assertEquals(1, legs.count("rw", "SELECT * FROM v_fn"));
    }

    @Test
    public void otherReadLegFailuresAreNotRetried() throws SQLException {
        legs.refuseOnRo("no_such", -493);
        try {
            conn.createStatement().executeQuery("SELECT * FROM no_such");
            fail("the read leg's failure must reach the caller");
        } catch (SQLException expected) {
            assertEquals(-493, expected.getErrorCode());
        }
        assertEquals(0, legs.count("rw", "SELECT * FROM no_such"));
    }

    /** An explicit TO_RO hint was the application's choice of leg, so its failure is reported. */
    @Test
    public void readLegChosenByHintIsNotRetried() throws SQLException {
        legs.refuseOnRo("v_seq", ER_DB_NO_MODIFICATIONS);
        try {
            conn.createStatement().executeQuery("SELECT /*+ TO_RO */ * FROM v_seq");
            fail("a hinted read must not move to RW");
        } catch (SQLException expected) {
            assertEquals(ER_DB_NO_MODIFICATIONS, expected.getErrorCode());
        }
        assertEquals(0, legs.count("rw", "SELECT /*+ TO_RO */ * FROM v_seq"));
    }

    @Test
    public void preparedReadRefusedAsWriteRunsOnRw() throws SQLException {
        legs.refuseOnRo("v_seq", ER_DB_NO_MODIFICATIONS);
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM v_seq WHERE k = ?");
        ps.setInt(1, 7);

        assertNotNull(ps.executeQuery());

        assertEquals(1, legs.count("rw", "SELECT * FROM v_seq WHERE k = ?"));
        assertTrue(lastRouteEvent().contains("fallbackReason=RO_WRITE_REJECTED"));
    }

    /** A read leg opened after the SETs (here: a replacement) gets all of them, in order. */
    @Test
    public void sessionSetsAreReplayedOnReadLegOpenedLater() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("SET TIMEZONE 'Asia/Seoul'");
        st.execute("SET NAMES euckr");
        legs.refuseNextSetsOnRo(1);
        st.execute("set system parameters 'intl_date_lang=ko_KR'");
        assertEquals(1, legs.count("rw", "set system parameters 'intl_date_lang=ko_KR'"));

        st.executeQuery("SELECT 1");

        assertEquals(2, legs.roOpens.get());
        assertEquals(
                Arrays.asList(
                        "SET TIMEZONE 'Asia/Seoul'",
                        "SET NAMES euckr",
                        "SET SYSTEM PARAMETERS 'intl_date_lang=ko_KR'",
                        "SELECT 1"),
                tail(legs.log("ro"), 4));
    }

    @Test
    public void sessionSetIsAppliedToOpenReadLeg() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("set system parameters 'intl_date_lang=ko_KR'");
        st.executeQuery("SELECT 2");

        assertEquals(
                Arrays.asList(
                        "SELECT 1", "SET SYSTEM PARAMETERS 'intl_date_lang=ko_KR'", "SELECT 2"),
                legs.log("ro"));
        assertEquals(1, legs.roOpens.get());
    }

    /** The application's SET succeeded on RW; a read leg lost while applying it is replaced. */
    @Test
    public void readLegLostWhileApplyingTheSetIsReopenedWithIt() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        legs.refuseNextSetsOnRo(1);

        st.execute("SET NAMES euckr");
        st.executeQuery("SELECT 2");

        assertEquals(2, legs.roOpens.get());
        List<String> ro = legs.log("ro");
        assertEquals("SET NAMES euckr", ro.get(ro.size() - 2));
        assertEquals("SELECT 2", ro.get(ro.size() - 1));
    }

    /** A broker that rejects the SET would reject it again when reopened, so reads go to RW. */
    @Test
    public void readLegThatRejectsTheSetKeepsReadsOnRw() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        legs.refuseSetsOnRo(-204);

        st.execute("SET NAMES euckr");
        st.executeQuery("SELECT 2");
        st.executeQuery("SELECT 3");

        assertEquals(1, legs.roOpens.get());
        assertEquals(Arrays.asList("SELECT 1"), legs.log("ro"));
        assertEquals(Arrays.asList("SELECT 2", "SELECT 3"), tail(legs.log("rw"), 2));
        assertTrue(lastRouteEvent().contains("fallbackReason=SESSION_SET_PIN"));
    }

    @Test
    public void laterSetOfTheSameSettingReplacesTheEarlierOne() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("SET TIMEZONE 'Asia/Seoul'");
        st.execute("set system parameters 'timezone=UTC'");
        legs.refuseNextSetsOnRo(1);
        st.execute("SET NAMES euckr");

        st.executeQuery("SELECT 1");

        assertEquals(
                Arrays.asList(
                        "SET SYSTEM PARAMETERS 'timezone=UTC'", "SET NAMES euckr", "SELECT 1"),
                tail(legs.log("ro"), 3));
        // Only the open leg saw the older timezone; the replacement leg was never given it.
        assertEquals(1, legs.count("ro", "SET TIMEZONE 'Asia/Seoul'"));
    }

    @Test
    public void setTheReadLegCannotReplayKeepsReadsOnRw() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("SET SYSTEM PARAMETERS 'compat_mode=mysql'");

        st.executeQuery("SELECT 1");
        assertEquals(1, legs.count("rw", "SELECT 1"));
        assertFalse(legs.log("ro").contains("SELECT 1"));
        assertTrue(lastRouteEvent().contains("fallbackReason=SESSION_SET_PIN"));

        st.executeQuery("SELECT /*+ TO_RO */ 2");
        assertEquals(1, legs.count("ro", "SELECT /*+ TO_RO */ 2"));
    }

    @Test
    public void failedSetIsNotRecorded() throws SQLException {
        legs.refuseOnRw("Nowhere", -204);
        Statement st = conn.createStatement();
        try {
            st.execute("SET TIMEZONE 'Nowhere/Invalid'");
            fail("the RW leg's failure must reach the caller");
        } catch (SQLException expected) {
            assertEquals(-204, expected.getErrorCode());
        }
        st.executeQuery("SELECT 1");

        assertEquals(Arrays.asList("SELECT 1"), legs.log("ro"));
    }

    @Test
    public void optimizationLevelIsReplayedOnReadLeg() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("SET OPTIMIZATION LEVEL 2");
        st.executeQuery("SELECT 2");

        assertEquals(
                Arrays.asList("SELECT 1", "SET OPTIMIZATION LEVEL 2", "SELECT 2"), legs.log("ro"));
    }

    @Test
    public void queryTraceKeepsReadsWithTheTrace() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("SET TRACE ON");
        st.executeQuery("SELECT 1");

        assertEquals(1, legs.count("rw", "SELECT 1"));
        assertTrue(lastRouteEvent().contains("fallbackReason=SESSION_SET_PIN"));
    }

    @Test
    public void parameterThatChangesNoReadNeitherReplaysNorPins() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("set system parameters 'xasl_debug_dump=yes'");
        st.executeQuery("SELECT 1");

        assertEquals(Arrays.asList("SELECT 1"), legs.log("ro"));
    }

    /** A JDBC isolation setter after a SQL one wins on a leg opened later too. */
    @Test
    public void jdbcIsolationSetterReplacesSqlIsolationForLaterLegs() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE");
        assertEquals(1, legs.count("ro", "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE"));

        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        legs.refuseNextSetsOnRo(1);
        st.execute("SET NAMES euckr");
        st.executeQuery("SELECT 2");

        assertEquals(2, legs.roOpens.get());
        assertEquals(Arrays.asList("SET NAMES euckr", "SELECT 2"), tail(legs.log("ro"), 2));
        assertEquals(1, legs.count("ro", "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE"));
    }

    @Test
    public void setInStatementBatchIsReplayed() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.addBatch("SET NAMES euckr");
        st.executeBatch();

        assertEquals(Arrays.asList("SELECT 1", "SET NAMES euckr"), legs.log("ro"));
    }

    @Test
    public void setInPreparedBatchWithBoundValuePins() throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SET TIMEZONE ?");
        ps.setString(1, "UTC");
        ps.addBatch();
        ps.executeBatch();

        conn.createStatement().executeQuery("SELECT 1");
        assertEquals(1, legs.count("rw", "SELECT 1"));
        assertTrue(lastRouteEvent().contains("fallbackReason=SESSION_SET_PIN"));
    }

    /** A read naming a SELECT INTO variable runs on RW, where it lives; other reads stay on RO. */
    @Test
    public void readNamingSelectIntoVariableFollowsItToRw() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("select x into set1 from xoo");
        st.executeQuery("select t from table(set1) as tbl(t)");

        assertEquals(1, legs.count("rw", "select t from table(set1) as tbl(t)"));
        assertTrue(lastRouteEvent().contains("fallbackReason=SESSION_VARIABLE"));

        st.executeQuery("SELECT 1");
        assertEquals(Arrays.asList("SELECT 1"), legs.log("ro"));
    }

    @Test
    public void selectToVariableRunsOnRwAndReadsOfItFollow() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("select i to i1 from n");
        st.executeQuery("select i from n where i1 = 3");

        assertEquals(1, legs.count("rw", "select i to i1 from n"));
        assertEquals(1, legs.count("rw", "select i from n where i1 = 3"));
    }

    /** A variable the application put on the read leg with a hint stays there, with its reads. */
    @Test
    public void variableCreatedOnReadLegByHintIsNotFollowedToRw() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("SELECT /*+ TO_RO */ x INTO v FROM t");
        st.executeQuery("SELECT v");

        assertEquals(
                Arrays.asList("SELECT /*+ TO_RO */ x INTO v FROM t", "SELECT v"), legs.log("ro"));
    }

    /** The check runs at execute time: the statement was prepared before the variable existed. */
    @Test
    public void preparedReadNamingVariableFollowsIt() throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SELECT v FROM db_root");
        conn.createStatement().execute("SELECT 1 INTO v FROM db_root");
        ps.executeQuery();

        assertEquals(1, legs.count("rw", "SELECT v FROM db_root"));
    }

    /**
     * HA does not replicate LOB files, so a read that touches LOB content fails on the read leg.
     */
    @Test
    public void lobReadTheReadLegCannotServeRunsOnRw() throws SQLException {
        legs.refuseOnRo(
                "REVERSE(c)",
                -181,
                "Cannot coerce value of domain \"clob\" to domain \"character varying\".");
        conn.createStatement().executeQuery("SELECT REVERSE(c) FROM t");

        assertEquals(1, legs.count("rw", "SELECT REVERSE(c) FROM t"));
        assertTrue(lastRouteEvent().contains("fallbackReason=RO_LOB_UNAVAILABLE"));
    }

    @Test
    public void missingLobFileOnReadLegRunsOnRw() throws SQLException {
        legs.refuseOnRo("FROM lobs", -1020, "External file \"ces_1/t.0001\" was not found.");
        conn.createStatement().executeQuery("SELECT c FROM lobs");

        assertEquals(1, legs.count("rw", "SELECT c FROM lobs"));
    }

    /** A coercion failure that names no LOB is the statement's own error, the same on RW. */
    @Test
    public void coercionFailureWithoutLobIsNotRetried() throws SQLException {
        legs.refuseOnRo(
                "CAST(s",
                -181,
                "Cannot coerce value of domain \"character varying\" to domain \"integer\".");
        try {
            conn.createStatement().executeQuery("SELECT CAST(s AS INT) FROM t");
            fail("the read leg's own error must reach the caller");
        } catch (SQLException expected) {
            assertEquals(-181, expected.getErrorCode());
        }
        assertEquals(0, legs.count("rw", "SELECT CAST(s AS INT) FROM t"));
    }

    @Test
    public void droppedVariableNoLongerSendsReadsToRw() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("SELECT 1 INTO v FROM db_root");
        st.executeQuery("SELECT v FROM db_root");
        st.execute("DROP VARIABLE v");
        st.executeQuery("SELECT v + 1 FROM db_root");

        assertEquals(1, legs.count("rw", "SELECT v FROM db_root"));
        assertEquals(1, legs.count("ro", "SELECT v + 1 FROM db_root"));
    }

    /** DEALLOCATE VARIABLE drops @session variables, a different namespace from INTO variables. */
    @Test
    public void deallocatingSessionVariableKeepsIntoVariableTracked() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("SELECT 1 INTO v FROM db_root");
        st.execute("DEALLOCATE VARIABLE @v");
        st.executeQuery("SELECT v FROM db_root");

        assertEquals(1, legs.count("rw", "SELECT v FROM db_root"));
    }

    private static List<String> tail(final List<String> list, final int n) {
        return list.subList(Math.max(0, list.size() - n), list.size());
    }

    private String lastRouteEvent() {
        List<String> events = MetricsTestAccess.getEvents(LbTestAccess.getRuntimeMetrics(conn));
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).contains("routeTarget=")) {
                return events.get(i);
            }
        }
        fail("no route event recorded");
        return null;
    }

    /** Fake RW ("rw" host) and RO ("ro" host) brokers that log every statement they run. */
    private static final class Legs implements JdbcConnectionFactory {
        final AtomicInteger roOpens = new AtomicInteger();
        private final List<String> entries = Collections.synchronizedList(new ArrayList<String>());
        private volatile String refuseRo;
        private volatile int refuseRoCode;
        private volatile String refuseRoMessage = "refused on ro";
        private volatile String refuseRw;
        private volatile int refuseRwCode;
        private final AtomicInteger roSetRefusals = new AtomicInteger();
        private volatile int roSetRefusalCode;

        void refuseOnRo(final String fragment, final int code, final String message) {
            refuseOnRo(fragment, code);
            refuseRoMessage = message;
        }

        void refuseOnRo(final String fragment, final int code) {
            refuseRo = fragment;
            refuseRoCode = code;
        }

        void refuseOnRw(final String fragment, final int code) {
            refuseRw = fragment;
            refuseRwCode = code;
        }

        /** The next {@code n} SETs on the read leg lose the connection. */
        void refuseNextSetsOnRo(final int n) {
            roSetRefusals.set(n);
        }

        /** Every SET on the read leg fails with {@code code}, as a broker that rejects it would. */
        void refuseSetsOnRo(final int code) {
            roSetRefusalCode = code;
        }

        List<String> log(final String role) {
            List<String> out = new ArrayList<String>();
            synchronized (entries) {
                for (String e : entries) {
                    if (e.startsWith(role + "|")) {
                        out.add(e.substring(role.length() + 1));
                    }
                }
            }
            return out;
        }

        int count(final String role, final String sql) {
            return Collections.frequency(log(role), sql);
        }

        public Connection getConnection(final String url, final Properties info) {
            final String role = url.indexOf(":ro:") >= 0 ? "ro" : "rw";
            if ("ro".equals(role)) {
                roOpens.incrementAndGet();
            }
            return (Connection) proxy(Connection.class, new ConnectionHandler(role));
        }

        void run(final String role, final String sql) throws SQLException {
            if ("ro".equals(role) && sql.trim().toUpperCase().startsWith("SET")) {
                if (roSetRefusals.getAndDecrement() > 0) {
                    throw new SQLException("connection lost", null, UErrorCode.ER_COMMUNICATION);
                }
                if (roSetRefusalCode != 0) {
                    throw new SQLException("set refused", null, roSetRefusalCode);
                }
            }
            entries.add(role + "|" + sql);
            if ("ro".equals(role) && refuseRo != null && sql.indexOf(refuseRo) >= 0) {
                throw new SQLException(refuseRoMessage, null, refuseRoCode);
            }
            if ("rw".equals(role) && refuseRw != null && sql.indexOf(refuseRw) >= 0) {
                throw new SQLException("refused on rw", null, refuseRwCode);
            }
        }

        private final class ConnectionHandler implements InvocationHandler {
            private final String role;
            private boolean closed;

            ConnectionHandler(final String role) {
                this.role = role;
            }

            public Object invoke(final Object proxy, final Method m, final Object[] args) {
                String name = m.getName();
                if ("close".equals(name)) {
                    closed = true;
                    return null;
                }
                if ("isClosed".equals(name)) {
                    return Boolean.valueOf(closed);
                }
                if ("isValid".equals(name) || "getAutoCommit".equals(name)) {
                    return Boolean.TRUE;
                }
                if (name.startsWith("createStatement")) {
                    return proxy(Statement.class, new StatementHandler(role, null));
                }
                if (name.startsWith("prepareStatement")) {
                    return proxy(
                            PreparedStatement.class, new StatementHandler(role, (String) args[0]));
                }
                return defaultValue(m.getReturnType());
            }
        }

        private final class StatementHandler implements InvocationHandler {
            private final String role;
            private final String prepared;

            StatementHandler(final String role, final String prepared) {
                this.role = role;
                this.prepared = prepared;
            }

            public Object invoke(final Object proxy, final Method m, final Object[] args)
                    throws SQLException {
                String name = m.getName();
                if (name.startsWith("execute") && !"executeBatch".equals(name)) {
                    String sql = prepared != null ? prepared : (String) args[0];
                    run(role, sql);
                    if ("executeQuery".equals(name)) {
                        return proxy(ResultSet.class, new ResultSetHandler());
                    }
                    if ("executeUpdate".equals(name)) {
                        return Integer.valueOf(0);
                    }
                    return Boolean.valueOf(sql.trim().toUpperCase().startsWith("SELECT"));
                }
                if ("getResultSet".equals(name)) {
                    return proxy(ResultSet.class, new ResultSetHandler());
                }
                if ("getUpdateCount".equals(name)) {
                    return Integer.valueOf(-1);
                }
                return defaultValue(m.getReturnType());
            }
        }

        private static final class ResultSetHandler implements InvocationHandler {
            public Object invoke(final Object proxy, final Method m, final Object[] args) {
                return defaultValue(m.getReturnType());
            }
        }

        private static Object proxy(final Class<?> type, final InvocationHandler handler) {
            return Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[] {type},
                    new InvocationHandler() {
                        public Object invoke(final Object p, final Method m, final Object[] a)
                                throws Throwable {
                            if ("hashCode".equals(m.getName())) {
                                return Integer.valueOf(System.identityHashCode(p));
                            }
                            if ("equals".equals(m.getName())) {
                                return Boolean.valueOf(p == a[0]);
                            }
                            if ("toString".equals(m.getName())) {
                                return type.getSimpleName() + "@" + System.identityHashCode(p);
                            }
                            try {
                                return handler.invoke(p, m, a);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        }
                    });
        }

        private static Object defaultValue(final Class<?> type) {
            if (type == Boolean.TYPE) {
                return Boolean.FALSE;
            }
            if (type == Integer.TYPE) {
                return Integer.valueOf(0);
            }
            if (type == Long.TYPE) {
                return Long.valueOf(0L);
            }
            return null;
        }
    }
}
