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
import cubrid.jdbc.lb.log.LbLogDedup;
import cubrid.jdbc.lb.metrics.MetricsTestAccess;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.BatchUpdateException;
import java.sql.CallableStatement;
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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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

    /** {@code lbLogSql=off} keeps a statement out of the record of collapsed occurrences too. */
    @Test
    public void rejectedReadsCollapsedWithSqlLoggingOffLeaveNoSqlInTheLog() throws SQLException {
        final String url =
                "jdbc:cubrid:loadbalance://rw:33000,ro:1:33001/testdb:::"
                        + "?readWeight=slave:1&lbLogSql=off";
        final List<String> logged = Collections.synchronizedList(new ArrayList<String>());
        final Handler capture =
                new Handler() {
                    @Override
                    public void publish(final LogRecord record) {
                        logged.add(String.valueOf(record.getMessage()));
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        final Logger lbLogger = Logger.getLogger("cubrid.jdbc.lb");
        final LoadBalanceConnection off =
                LbTestAccess.newLoadBalanceConnection(LbTestSettings.fromUrl(url));
        LbTestAccess.setConnectionManager(
                off,
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), LbTestSettings.fromUrl(url), legs));
        LbTestAccess.setSharedSelectorState(off, new SharedSelectorState());
        LbTestAccess.initSessionBindings(
                off,
                new EndpointTopology(
                        new Endpoint("rw", 33000),
                        Arrays.asList(new Endpoint("ro", 33001)),
                        Collections.<Endpoint>emptyList()));
        legs.refuseOnRo("v_seq", ER_DB_NO_MODIFICATIONS);
        LbLogDedup.drain();
        lbLogger.addHandler(capture);
        try {
            off.createStatement().executeQuery("SELECT * FROM v_seq WHERE a = 1");
            off.createStatement().executeQuery("SELECT * FROM v_seq WHERE a = 2");
            LbLogDedup.drain();
        } finally {
            lbLogger.removeHandler(capture);
            off.close();
        }

        int summaries = 0;
        for (String message : logged) {
            assertFalse(message, message.contains("v_seq"));
            if (message.startsWith("LB SUPPRESSED")) {
                summaries++;
            }
        }
        assertEquals(logged.toString(), 1, summaries);
    }

    /** The record of a read an updatable result keeps on RW says so, not just that it is a READ. */
    @Test
    public void routeRecordNamesTheUpdatableResultThatKeptAReadOnRw() throws SQLException {
        final List<String> routes = new ArrayList<String>();
        final Handler capture = routeRecordsInto(routes);
        try {
            conn.createStatement(
                            ResultSet.TYPE_FORWARD_ONLY,
                            ResultSet.CONCUR_UPDATABLE,
                            ResultSet.CLOSE_CURSORS_AT_COMMIT)
                    .executeQuery("SELECT * FROM t");
        } finally {
            stopRouteRecords(capture);
        }

        assertEquals(1, legs.count("rw", "SELECT * FROM t"));
        assertEquals(routes.toString(), 1, routes.size());
        assertTrue(
                routes.toString(),
                routes.get(0).startsWith("LB ROUTE: RW@rw:33000 by=updatable(classify=READ)"));
    }

    /** A batch runs on RW whatever its statements are; its record names the batch as the reason. */
    @Test
    public void routeRecordNamesABatch() throws SQLException {
        final List<String> routes = new ArrayList<String>();
        final Handler capture = routeRecordsInto(routes);
        try {
            Statement st = conn.createStatement();
            st.addBatch("SELECT /*+ TO_RO */ * FROM t");
            st.executeBatch();
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM t WHERE s = ?");
            ps.setString(1, "a");
            ps.addBatch();
            ps.executeBatch();
        } finally {
            stopRouteRecords(capture);
        }

        assertEquals(routes.toString(), 2, routes.size());
        assertTrue(routes.toString(), routes.get(0).startsWith("LB ROUTE: RW@rw:33000 by=batch "));
        assertTrue(routes.toString(), routes.get(1).startsWith("LB ROUTE: RW@rw:33000 by=batch "));
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

    /** Both legs trace, and SHOW TRACE reports on the leg the last query ran on. */
    @Test
    public void queryTraceIsReplayedAndShowTraceFollowsTheTracedQuery() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("SET TRACE ON");
        st.executeQuery("SELECT 1");
        st.executeQuery("SHOW TRACE");
        st.execute("UPDATE t SET a = 1");
        st.executeQuery("SHOW TRACE");

        assertEquals(Arrays.asList("SET TRACE ON", "SELECT 1", "SHOW TRACE"), legs.log("ro"));
        assertEquals(
                Arrays.asList("SET TRACE ON", "UPDATE t SET a = 1", "SHOW TRACE"), legs.log("rw"));
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

    /**
     * Core compiled an open statement before a later SET, which leaves it as compiled. One prepared
     * only on the read leg is prepared on RW before a SET sent in a batch, as before a single one.
     */
    @Test
    public void openStatementIsPreparedOnRwBeforeASetInAStatementBatch() throws SQLException {
        conn.prepareStatement("SELECT * FROM t WHERE s = ?");
        Statement st = conn.createStatement();
        st.addBatch("SET NAMES utf8 COLLATE utf8_bin");
        st.executeBatch();

        assertPreparedOnRwBefore("SELECT * FROM t WHERE s = ?", "SET NAMES utf8 COLLATE utf8_bin");
    }

    /** A login, which changes how names resolve; core sends a prepared batch only with values. */
    @Test
    public void openStatementIsPreparedOnRwBeforeASetInAPreparedBatch() throws SQLException {
        conn.prepareStatement("SELECT * FROM t WHERE s = ?");
        PreparedStatement login = conn.prepareStatement("CALL login(?, ?) ON CLASS db_user");
        login.setString(1, "u2");
        login.setString(2, "pw");
        login.addBatch();
        login.executeBatch();

        assertPreparedOnRwBefore(
                "SELECT * FROM t WHERE s = ?", "CALL login(?, ?) ON CLASS db_user");
    }

    /** The server keeps a SET that ran before a failed entry, so it is prepared before it too. */
    @Test
    public void openStatementIsPreparedOnRwBeforeASetInAFailedBatch() throws SQLException {
        legs.refuseOnRw("no_such", -493);
        conn.prepareStatement("SELECT * FROM t WHERE s = ?");
        Statement st = conn.createStatement();
        st.addBatch("SET NAMES utf8 COLLATE utf8_bin");
        st.addBatch("INSERT INTO no_such VALUES (1)");
        try {
            st.executeBatch();
            fail("the failed entry must reach the caller");
        } catch (BatchUpdateException expected) {
            assertEquals(-493, expected.getErrorCode());
        }

        assertPreparedOnRwBefore("SELECT * FROM t WHERE s = ?", "SET NAMES utf8 COLLATE utf8_bin");
    }

    /**
     * A read statement prepared while every statement runs on RW has no physical statement on the
     * read leg. It is prepared there too before a SET reaches that leg, or its first run there
     * would compile under the new setting.
     */
    @Test
    public void readStatementPreparedInATransactionIsPreparedOnTheReadLegBeforeASet()
            throws SQLException {
        conn.createStatement().executeQuery("SELECT 1");
        conn.setAutoCommit(false);
        conn.prepareStatement("SELECT * FROM t WHERE s = ?");
        conn.setAutoCommit(true);
        conn.createStatement().execute("SET NAMES utf8 COLLATE utf8_bin");

        assertPreparedBefore(
                "ro", "SELECT * FROM t WHERE s = ?", "SET NAMES utf8 COLLATE utf8_bin");
    }

    /**
     * While reads are pinned the read leg lacks a session setting, so a statement prepared then
     * cannot be compiled there as core compiled it. It stays on RW after the pin is released.
     */
    @Test
    public void readStatementPreparedWhileReadsWerePinnedStaysOnRw() throws SQLException {
        conn.createStatement().executeQuery("SELECT 1");
        PreparedStatement bound = conn.prepareStatement("SET TIMEZONE ?");
        bound.setString(1, "UTC");
        bound.execute();
        PreparedStatement read = conn.prepareStatement("SELECT * FROM t WHERE s = ?");
        conn.createStatement().execute("SET TIMEZONE 'UTC'");
        read.setString(1, "a");
        read.executeQuery();
        conn.createStatement().executeQuery("SELECT 2");

        assertEquals(1, legs.count("rw", "SELECT * FROM t WHERE s = ?"));
        assertEquals(-1, legs.timeline("ro").indexOf("prepare SELECT * FROM t WHERE s = ?"));
        assertEquals(1, legs.count("ro", "SELECT 2"));
    }

    /**
     * Collecting statistics changes nothing a statement is compiled under, so one prepared while
     * that pins reads leaves RW with the pin.
     */
    @Test
    public void readStatementPreparedWhileStatisticsPinnedReadsLeavesRwWithThePin()
            throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("SET @collect_exec_stats = 1");
        PreparedStatement read = conn.prepareStatement("SELECT * FROM t WHERE s = ?");
        read.setString(1, "a");
        read.executeQuery();
        st.execute("SET @collect_exec_stats = 0");
        read.executeQuery();

        assertEquals(1, legs.count("rw", "SELECT * FROM t WHERE s = ?"));
        assertEquals(1, legs.count("ro", "SELECT * FROM t WHERE s = ?"));
    }

    /** Nor does a lock timeout the read leg was not given. */
    @Test
    public void readStatementPreparedWhileABoundLockTimeoutPinnedReadsLeavesRwWithThePin()
            throws SQLException {
        conn.createStatement().executeQuery("SELECT 1");
        PreparedStatement bound = conn.prepareStatement("SET TRANSACTION LOCK TIMEOUT ?");
        bound.setInt(1, 3);
        bound.execute();
        PreparedStatement read = conn.prepareStatement("SELECT * FROM t WHERE s = ?");
        read.setString(1, "a");
        read.executeQuery();
        conn.createStatement().execute("SET TRANSACTION LOCK TIMEOUT 5");
        read.executeQuery();

        assertEquals(1, legs.count("rw", "SELECT * FROM t WHERE s = ?"));
        assertEquals(1, legs.count("ro", "SELECT * FROM t WHERE s = ?"));
    }

    /** Under such a pin the read leg compiles as RW does: a SET finds the statement there. */
    @Test
    public void readStatementPreparedWhileStatisticsPinnedReadsIsPreparedOnTheReadLegBeforeASet()
            throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("SET @collect_exec_stats = 1");
        conn.prepareStatement("SELECT * FROM t WHERE s = ?");
        st.execute("SET NAMES utf8 COLLATE utf8_bin");

        assertPreparedBefore(
                "ro", "SELECT * FROM t WHERE s = ?", "SET NAMES utf8 COLLATE utf8_bin");
    }

    /** A statement that never runs on the read leg is not prepared there. */
    @Test
    public void writeStatementIsNotPreparedOnTheReadLegBeforeASet() throws SQLException {
        conn.createStatement().executeQuery("SELECT 1");
        conn.prepareStatement("INSERT INTO t VALUES (?)");
        conn.createStatement().execute("SET NAMES utf8 COLLATE utf8_bin");

        assertEquals(-1, legs.timeline("ro").indexOf("prepare INSERT INTO t VALUES (?)"));
        assertEquals(1, legs.count("ro", "SET NAMES utf8 COLLATE utf8_bin"));
    }

    /** The read leg logs in as the same user, so reads stay distributed. */
    @Test
    public void loginIsReplayedOnTheReadLeg() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("CALL login('u2', 'pw') ON CLASS db_user");
        st.executeQuery("SELECT 2");

        assertEquals(
                Arrays.asList("SELECT 1", "CALL login('u2', 'pw') ON CLASS db_user", "SELECT 2"),
                legs.log("ro"));
    }

    /** A login with bound arguments pins until a literal one is replayed, as a login back does. */
    @Test
    public void literalLoginReleasesThePinOfABoundLogin() throws SQLException {
        PreparedStatement ps = conn.prepareStatement("CALL login(?, ?) ON CLASS db_user");
        ps.setString(1, "u2");
        ps.setString(2, "pw");
        ps.execute();
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("CALL login('public', '') ON CLASS db_user");
        st.executeQuery("SELECT 2");

        assertEquals(1, legs.count("rw", "SELECT 1"));
        assertEquals(1, legs.count("ro", "SELECT 2"));
    }

    /**
     * LB sees a CallableStatement run. A login prepared with prepareCall changes nothing until it
     * runs, and then is replayed on the read leg like a login run any other way.
     */
    @Test
    public void loginRunAsPreparedCallIsReplayedOnTheReadLeg() throws SQLException {
        CallableStatement cs = conn.prepareCall("CALL login('u2', 'pw') ON CLASS db_user");
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        cs.execute();
        st.executeQuery("SELECT 2");

        assertEquals(
                Arrays.asList("SELECT 1", "CALL login('u2', 'pw') ON CLASS db_user", "SELECT 2"),
                legs.log("ro"));
    }

    /** With bound arguments it pins when it runs, as a PreparedStatement's does. */
    @Test
    public void boundLoginRunAsPreparedCallPinsUntilALiteralLogin() throws SQLException {
        CallableStatement cs = conn.prepareCall("CALL login(?, ?) ON CLASS db_user");
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        cs.setString(1, "u2");
        cs.setString(2, "pw");
        cs.execute();
        st.executeQuery("SELECT 2");
        st.execute("CALL login('public', '') ON CLASS db_user");
        st.executeQuery("SELECT 3");

        assertEquals(1, legs.count("ro", "SELECT 1"));
        assertEquals(1, legs.count("rw", "SELECT 2"));
        assertEquals(1, legs.count("ro", "SELECT 3"));
    }

    @Test
    public void callPreparedWithoutLoginOrThatFailedKeepsReadsDistributed() throws SQLException {
        legs.refuseOnRw("broken", -494);
        conn.prepareCall("CALL p()");
        for (String sql :
                Arrays.asList(
                        "CALL login('broken', 'pw') ON CLASS db_user", "CALL broken() INTO :v")) {
            try {
                conn.prepareCall(sql);
                fail("the prepare must fail: " + sql);
            } catch (SQLException expected) {
                assertEquals(-494, expected.getErrorCode());
            }
        }
        conn.createStatement().executeQuery("SELECT v");

        assertEquals(Arrays.asList("SELECT v"), legs.log("ro"));
    }

    @Test
    public void execStatisticsOffReleasesTheReadPin() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("SET @collect_exec_stats = 1");
        st.executeQuery("SELECT 1");
        st.execute("SET @collect_exec_stats = 0");
        st.executeQuery("SELECT 2");

        assertEquals(1, legs.count("rw", "SELECT 1"));
        assertEquals(1, legs.count("ro", "SELECT 2"));
    }

    /** A literal SET of the setting a bound value pinned is replayed, so reads leave RW. */
    @Test
    public void literalSetReleasesThePinOfABoundValue() throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SET TIMEZONE ?");
        ps.setString(1, "UTC");
        ps.execute();
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("SET TIMEZONE 'Asia/Seoul'");
        st.executeQuery("SELECT 2");

        assertEquals(1, legs.count("rw", "SELECT 1"));
        assertEquals(
                Arrays.asList("SET TIMEZONE 'Asia/Seoul'", "SELECT 2"), tail(legs.log("ro"), 2));
    }

    @Test
    public void readsStayOnRwUntilEveryPinReasonClears() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("SET @collect_exec_stats = 1");
        PreparedStatement ps = conn.prepareStatement("SET TIMEZONE ?");
        ps.setString(1, "UTC");
        ps.execute();
        st.execute("SET @collect_exec_stats = 0");
        st.executeQuery("SELECT 1");
        st.execute("SET TIMEZONE 'UTC'");
        st.executeQuery("SELECT 2");

        assertEquals(1, legs.count("rw", "SELECT 1"));
        assertEquals(1, legs.count("ro", "SELECT 2"));
    }

    @Test
    public void laterReplayOfARejectedSettingReleasesThePin() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        legs.refuseSetsOnRo(-204);
        st.execute("SET NAMES euckr");
        st.executeQuery("SELECT 2");
        legs.refuseSetsOnRo(0);
        st.execute("SET NAMES utf8");
        st.executeQuery("SELECT 3");

        assertEquals(1, legs.count("rw", "SELECT 2"));
        assertEquals(Arrays.asList("SET NAMES utf8", "SELECT 3"), tail(legs.log("ro"), 2));
    }

    @Test
    public void jdbcIsolationSetterReleasesThePinOfABoundIsolation() throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SET TRANSACTION ISOLATION LEVEL ?");
        ps.setInt(1, 4);
        ps.execute();
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        st.executeQuery("SELECT 2");

        assertEquals(1, legs.count("rw", "SELECT 1"));
        assertEquals(1, legs.count("ro", "SELECT 2"));
    }

    /** The SETs of a multi-statement string are replayed on their own, in order. */
    @Test
    public void setsOfAMultiStatementStringAreReplayed() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("SET NAMES euckr; SELECT 2; SET TIMEZONE 'UTC'");
        st.executeQuery("SELECT 3");

        assertEquals(
                Arrays.asList("SET NAMES euckr", "SET TIMEZONE 'UTC'", "SELECT 3"),
                tail(legs.log("ro"), 3));
    }

    @Test
    public void bothTransactionSettingsOfOneStatementAreReplayed() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("SET TRANSACTION ISOLATION LEVEL 4, LOCK TIMEOUT 5");
        st.executeQuery("SELECT 2");

        assertEquals(
                Arrays.asList(
                        "SET TRANSACTION ISOLATION LEVEL 4",
                        "SET TRANSACTION LOCK TIMEOUT 5",
                        "SELECT 2"),
                tail(legs.log("ro"), 3));
    }

    /**
     * CUBRID keeps the SETs that ran before a later statement of the string failed. Which ones ran
     * is not known, so reads stay on RW until the setting is set again.
     */
    @Test
    public void setOfAStringThatFailedPartwayKeepsReadsOnRwUntilSetAgain() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        legs.refuseOnRw("missing", -493);
        try {
            st.execute("SET NAMES euckr; SELECT * FROM missing");
            fail("the RW leg's failure must reach the caller");
        } catch (SQLException expected) {
            assertEquals(-493, expected.getErrorCode());
        }
        st.executeQuery("SELECT 2");
        st.execute("SET NAMES utf8");
        st.executeQuery("SELECT 3");

        assertEquals(1, legs.count("rw", "SELECT 2"));
        assertEquals(Arrays.asList("SELECT 1", "SET NAMES utf8", "SELECT 3"), legs.log("ro"));
    }

    /** Each entry of a batch the server answered with a failure ran or failed on its own. */
    @Test
    public void setThatRanInAFailedBatchIsReplayed() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        legs.refuseOnRw("missing", -493);
        st.addBatch("SET TIMEZONE 'missing/zone'");
        st.addBatch("SET NAMES euckr");
        st.addBatch("INSERT INTO missing VALUES (1)");
        try {
            st.executeBatch();
            fail("the batch failure must reach the caller");
        } catch (BatchUpdateException expected) {
            assertEquals(
                    Arrays.toString(
                            new int[] {Statement.EXECUTE_FAILED, 0, Statement.EXECUTE_FAILED}),
                    Arrays.toString(expected.getUpdateCounts()));
        }
        st.executeQuery("SELECT 2");

        assertEquals(Arrays.asList("SELECT 1", "SET NAMES euckr", "SELECT 2"), legs.log("ro"));
    }

    /** A prepared batch is sent only with values, and a SET with a bound value pins. */
    @Test
    public void setThatRanInAFailedPreparedBatchPins() throws SQLException {
        conn.createStatement().executeQuery("SELECT 1");
        PreparedStatement ps = conn.prepareStatement("SET TIMEZONE ?");
        ps.setString(1, "UTC");
        ps.addBatch();
        ps.setString(1, "UTC");
        ps.addBatch();
        legs.refuseOnceOnRw("TIMEZONE", -493);
        try {
            ps.executeBatch();
            fail("the batch failure must reach the caller");
        } catch (BatchUpdateException expected) {
            assertEquals(
                    Arrays.toString(new int[] {Statement.EXECUTE_FAILED, 0}),
                    Arrays.toString(expected.getUpdateCounts()));
        }
        conn.createStatement().executeQuery("SELECT 2");

        assertEquals(1, legs.count("rw", "SELECT 2"));
        assertTrue(lastRouteEvent().contains("fallbackReason=SESSION_SET_PIN"));
    }

    /**
     * Core sends no batch for a prepared statement without parameters, so its SET did not run and
     * the read leg is not given it.
     */
    @Test
    public void setInAPreparedBatchCoreDoesNotSendIsNotReplayed() throws SQLException {
        conn.createStatement().executeQuery("SELECT 1");
        PreparedStatement ps = conn.prepareStatement("SET NAMES euckr");
        ps.addBatch();

        assertEquals(0, ps.executeBatch().length);
        assertEquals(Arrays.asList("SELECT 1"), legs.log("ro"));
        assertEquals(0, legs.count("rw", "SET NAMES euckr"));
    }

    /**
     * Reads of a user variable go to RW, so a string that assigns one runs there whatever its hint:
     * on the read leg alone the variable would be missing where it is read.
     */
    @Test
    public void hintedReadStringThatSetsAVariableRunsOnRw() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("SELECT /*+ TO_RO */ 2; SET @a = 5");
        st.executeQuery("SELECT /*+ TO_RO */ @a := 7");

        assertEquals(1, legs.count("rw", "SELECT /*+ TO_RO */ 2; SET @a = 5"));
        assertEquals(1, legs.count("rw", "SELECT /*+ TO_RO */ @a := 7"));
        assertEquals(Arrays.asList("SELECT 1"), legs.log("ro"));
    }

    /** A hinted read of a variable stays the application's choice. */
    @Test
    public void hintedReadOfAVariableRunsOnTheReadLeg() throws SQLException {
        conn.createStatement().executeQuery("SELECT /*+ TO_RO */ @a");

        assertEquals(Arrays.asList("SELECT /*+ TO_RO */ @a"), legs.log("ro"));
    }

    /** Core runs the whole string in its one session, so a TO_RO hint does not take a SET away. */
    @Test
    public void hintedReadStringWithASetRunsOnRwAndIsReplayed() throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT 1");
        st.execute("SELECT /*+ TO_RO */ 2; SET NAMES euckr");
        st.executeQuery("SELECT 3");

        assertEquals(1, legs.count("rw", "SELECT /*+ TO_RO */ 2; SET NAMES euckr"));
        assertEquals(Arrays.asList("SELECT 1", "SET NAMES euckr", "SELECT 3"), legs.log("ro"));
    }

    @Test
    public void preparedHintedReadStringWithASetRunsOnRw() throws SQLException {
        conn.prepareStatement("SELECT /*+ TO_RO */ 1; SET NAMES euckr").execute();
        conn.createStatement().executeQuery("SELECT 2");

        assertEquals(1, legs.count("rw", "SELECT /*+ TO_RO */ 1; SET NAMES euckr"));
        assertEquals(Arrays.asList("SET NAMES euckr", "SELECT 2"), legs.log("ro"));
    }

    /** A server-wide parameter changed the RW node only; read nodes keep their own value. */
    @Test
    public void nonSessionParameterNeitherReplaysNorPins() throws SQLException {
        Statement st = conn.createStatement();
        st.execute("set system parameters 'group_concat_max_len=10000'");
        st.executeQuery("SELECT 1");

        assertEquals(Arrays.asList("SELECT 1"), legs.log("ro"));
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

    /** A CallableStatement that runs again after DROP VARIABLE assigns its variable again. */
    @Test
    public void variableOfCallPreparedAsCallIsFollowedAgainWhenItRunsAfterDrop()
            throws SQLException {
        CallableStatement cs = conn.prepareCall("CALL next_no() INTO :no");
        cs.execute();
        Statement st = conn.createStatement();
        st.executeQuery("SELECT no");
        st.execute("DROP VARIABLE no");
        cs.execute();
        st.executeQuery("SELECT no + 1");

        assertEquals(1, legs.count("rw", "SELECT no"));
        assertEquals(1, legs.count("rw", "SELECT no + 1"));
        assertTrue(lastRouteEvent().contains("fallbackReason=SESSION_VARIABLE"));
    }

    /**
     * The variable of a prepared CALL exists once it has run, and DROP VARIABLE forgets it whether
     * or not the CallableStatement is still open.
     */
    @Test
    public void variableOfAPreparedCallIsFollowedFromItsRunToItsDrop() throws SQLException {
        CallableStatement cs = conn.prepareCall("CALL next_no() INTO :no");
        Statement st = conn.createStatement();
        st.executeQuery("SELECT no - 1");
        cs.execute();
        st.executeQuery("SELECT no");
        st.execute("DROP VARIABLE no");
        st.executeQuery("SELECT no + 1");

        assertEquals(1, legs.count("ro", "SELECT no - 1"));
        assertEquals(1, legs.count("rw", "SELECT no"));
        assertEquals(1, legs.count("ro", "SELECT no + 1"));
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

    /** Collects the {@code LB ROUTE} records written until {@link #stopRouteRecords}. */
    private static Handler routeRecordsInto(final List<String> routes) {
        final Handler capture =
                new Handler() {
                    @Override
                    public void publish(final LogRecord record) {
                        final String message = String.valueOf(record.getMessage());
                        if (message.startsWith("LB ROUTE: ")) {
                            routes.add(message);
                        }
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        final Logger lbLogger = Logger.getLogger("cubrid.jdbc.lb");
        lbLogger.setLevel(Level.FINE);
        lbLogger.addHandler(capture);
        return capture;
    }

    private static void stopRouteRecords(final Handler capture) {
        final Logger lbLogger = Logger.getLogger("cubrid.jdbc.lb");
        lbLogger.removeHandler(capture);
        lbLogger.setLevel(null);
    }

    private void assertPreparedOnRwBefore(final String sql, final String set) {
        assertPreparedBefore("rw", sql, set);
    }

    private void assertPreparedBefore(final String role, final String sql, final String set) {
        List<String> timeline = legs.timeline(role);
        int prepared = timeline.indexOf("prepare " + sql);
        assertTrue(timeline.toString(), prepared >= 0 && prepared < timeline.indexOf(set));
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
        private volatile boolean refuseRwOnce;
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

        /** Only the first statement on RW containing {@code fragment} fails. */
        void refuseOnceOnRw(final String fragment, final int code) {
            refuseOnRw(fragment, code);
            refuseRwOnce = true;
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

        /** {@code role}'s statements in order, with each prepare on it as "prepare <sql>". */
        List<String> timeline(final String role) {
            List<String> out = new ArrayList<String>();
            synchronized (entries) {
                for (String e : entries) {
                    if (e.startsWith(role + "|")) {
                        out.add(e.substring(role.length() + 1));
                    } else if (e.startsWith(role + "+prepare|")) {
                        out.add("prepare " + e.substring(role.length() + "+prepare|".length()));
                    }
                }
            }
            return out;
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
                if (refuseRwOnce) {
                    refuseRw = null;
                }
                throw new SQLException("refused on rw", null, refuseRwCode);
            }
        }

        private final class ConnectionHandler implements InvocationHandler {
            private final String role;
            private boolean closed;

            ConnectionHandler(final String role) {
                this.role = role;
            }

            public Object invoke(final Object proxy, final Method m, final Object[] args)
                    throws SQLException {
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
                    entries.add(role + "+prepare|" + args[0]);
                    return proxy(
                            PreparedStatement.class, new StatementHandler(role, (String) args[0]));
                }
                if (name.startsWith("prepareCall")) {
                    final String sql = (String) args[0];
                    if ("rw".equals(role) && refuseRw != null && sql.indexOf(refuseRw) >= 0) {
                        throw new SQLException("prepare refused on rw", null, refuseRwCode);
                    }
                    return proxy(CallableStatement.class, new StatementHandler(role, sql));
                }
                return defaultValue(m.getReturnType());
            }
        }

        private final class StatementHandler implements InvocationHandler {
            private final String role;
            private final String prepared;
            private final List<String> batch = new ArrayList<String>();
            private volatile boolean closed;

            StatementHandler(final String role, final String prepared) {
                this.role = role;
                this.prepared = prepared;
            }

            public Object invoke(final Object proxy, final Method m, final Object[] args)
                    throws SQLException {
                String name = m.getName();
                if ("close".equals(name)) {
                    closed = true;
                    return null;
                }
                if ("isClosed".equals(name)) {
                    return Boolean.valueOf(closed);
                }
                if ("addBatch".equals(name)) {
                    // Core keeps no batch row for a prepared statement without parameters.
                    if (prepared == null || prepared.indexOf('?') >= 0) {
                        batch.add(prepared != null ? prepared : (String) args[0]);
                    }
                    return null;
                }
                if ("clearBatch".equals(name)) {
                    batch.clear();
                    return null;
                }
                if ("executeBatch".equals(name)) {
                    return runBatch();
                }
                if (name.startsWith("execute")) {
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

            /** Runs every entry as the server does, a failed one included, then reports them. */
            private int[] runBatch() throws SQLException {
                final int[] counts = new int[batch.size()];
                SQLException failed = null;
                for (int i = 0; i < counts.length; i++) {
                    try {
                        run(role, batch.get(i));
                    } catch (SQLException e) {
                        counts[i] = Statement.EXECUTE_FAILED;
                        failed = e;
                    }
                }
                batch.clear();
                if (failed != null) {
                    throw new BatchUpdateException(
                            failed.getMessage(), null, failed.getErrorCode(), counts);
                }
                return counts;
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
