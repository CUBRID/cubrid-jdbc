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

package cubrid.jdbc.lb.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class SessionSetStatementTest {

    private static Map<String, String> replay(final String sql) {
        return SessionSetStatement.parse(sql).getReplay();
    }

    private static List<String> replayed(final String sql) {
        return new ArrayList<String>(replay(sql).values());
    }

    private static Set<String> pins(final String sql) {
        return SessionSetStatement.parse(sql).getPins();
    }

    private static Set<String> unpins(final String sql) {
        return SessionSetStatement.parse(sql).getUnpins();
    }

    private static Set<String> only(final String reason) {
        return Collections.singleton(reason);
    }

    private static void assertUntracked(final String sql) {
        assertFalse(sql, SessionSetStatement.parse(sql).changesSession());
    }

    @Test
    public void namesAndTimezoneAreReplayedVerbatim() {
        assertEquals(
                "SET NAMES utf8 COLLATE utf8_bin",
                replay("SET NAMES utf8 COLLATE utf8_bin").get(SessionSetStatement.NAMES_KEY));
        assertEquals("set names euckr", replay("set names euckr;").get("NAMES"));
        assertEquals(
                "SET TIMEZONE 'Asia/Seoul'",
                replay("  SET TIMEZONE 'Asia/Seoul'").get(SessionSetStatement.TIMEZONE_KEY));
        assertEquals(
                "set time zone '+09:00'",
                replay("set time zone '+09:00'").get(SessionSetStatement.TIMEZONE_KEY));
    }

    @Test
    public void sessionParametersAreReplayedOnePerKey() {
        assertEquals(
                Arrays.asList("SET SYSTEM PARAMETERS 'default_week_format=1'"),
                replayed("set system parameters 'default_week_format=1'"));
        assertEquals(
                Arrays.asList(
                        "SET SYSTEM PARAMETERS 'intl_date_lang=ko_KR'",
                        "SET SYSTEM PARAMETERS 'return_null_on_function_errors=yes'"),
                replayed(
                        "SET SYSTEM PARAMETERS 'intl_date_lang=ko_KR; return_null_on_function_errors"
                                + "=yes'"));
        // SET TIMEZONE and the timezone parameter change one setting, so they share a key.
        assertTrue(
                replay("set system parameters 'timezone=UTC'")
                        .containsKey(SessionSetStatement.TIMEZONE_KEY));
    }

    /** {@code login()} changes the session's user, so the read leg logs in too. */
    @Test
    public void loginIsReplayed() {
        final String key = SessionSetStatement.LOGIN_KEY;
        assertEquals(
                "CALL login('u', 'pw') ON CLASS db_user",
                replay("CALL login('u', 'pw') ON CLASS db_user").get(key));
        assertEquals(
                "call /* c */ LOGIN ('u', 'pw') on class db_user",
                replay("  call /* c */ LOGIN ('u', 'pw') on class db_user").get(key));
        assertEquals(
                "CALL login('u', 'pw') ON CLASS db_user",
                replay("CALL login('u', 'pw') ON CLASS db_user; SELECT 1").get(key));
        assertEquals(only(key), pins("CALL login(?, ?) ON CLASS db_user"));
        assertUntracked("CALL login_audit('u')");
        assertUntracked("CALL p()");
    }

    /** The engine reads a quoted routine name as the same name, so these log in too. */
    @Test
    public void quotedLoginIsLogin() {
        final String key = SessionSetStatement.LOGIN_KEY;
        assertEquals(
                "CALL \"login\"('u', 'pw') ON CLASS db_user",
                replay("CALL \"login\"('u', 'pw') ON CLASS db_user").get(key));
        assertEquals(
                "call /* c */ `LOGIN` ('u', 'pw') on class db_user",
                replay("call /* c */ `LOGIN` ('u', 'pw') on class db_user").get(key));
        assertEquals(only(key), pins("CALL [login](?, ?) ON CLASS db_user"));
        assertUntracked("CALL \"login_audit\"('u')");
        assertUntracked("CALL \"log\"\"in\"('u')");
        assertUntracked("CALL [login]");
    }

    /** A parameter LB cannot place, or one server-wide in some released versions, pins. */
    @Test
    public void unknownParameterPinsUntilClose() {
        final Set<String> unknown = only(SessionSetStatement.UNKNOWN_PIN);
        assertEquals(unknown, pins("set system parameters 'compat_mode=mysql'"));
        assertEquals(unknown, pins("set system parameters 'no_equals_sign'"));
        assertEquals(unknown, pins("set system parameters ?"));
        assertEquals(unknown, pins("set system parameters 'max_hash_list_scan_size=8M'"));
    }

    /** Session-scoped in every release that reads it (10.2 to 11.4), so the read leg gets it. */
    @Test
    public void numericDivisionScaleIsReplayed() {
        final String sql = "set system parameters 'compat_numeric_division_scale=yes'";
        assertEquals(
                Arrays.asList("SET SYSTEM PARAMETERS 'compat_numeric_division_scale=yes'"),
                replayed(sql));
        assertTrue(pins(sql).isEmpty());
    }

    /**
     * A server- or CAS-wide parameter changed the RW node only: reported, not replayed or pinned.
     */
    @Test
    public void nonSessionParameterIsReportedWithoutPin() {
        SessionSetStatement s =
                SessionSetStatement.parse("set system parameters 'group_concat_max_len=10000'");
        assertEquals(Arrays.asList("group_concat_max_len"), s.getNonSessionParams());
        assertTrue(s.getPins().isEmpty());
        assertTrue(s.getReplay().isEmpty());

        s =
                SessionSetStatement.parse(
                        "set system parameters 'default_week_format=1; error_log_level=warning'");
        assertEquals(Arrays.asList("error_log_level"), s.getNonSessionParams());
        assertTrue(s.getPins().isEmpty());
        assertEquals(1, s.getReplay().size());
    }

    @Test
    public void parametersThatChangeNoReadAreIgnored() {
        assertUntracked("set system parameters 'xasl_debug_dump=yes'");
        assertUntracked("set system parameters 'insert_execution_mode=1; csql_auto_commit=no'");
        assertUntracked("set system parameters 'deduplicate_key_level=10'");
        assertEquals(
                Arrays.asList("SET SYSTEM PARAMETERS 'timezone=UTC'"),
                replayed("set system parameters 'like_term_selectivity=0.1; timezone=UTC'"));
    }

    @Test
    public void transactionSettingsAreReplayedUnderOneKeyEach() {
        assertEquals(
                "SET TRANSACTION ISOLATION LEVEL READ COMMITTED",
                replay("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")
                        .get(SessionSetStatement.ISOLATION_LEVEL_KEY));
        assertTrue(
                replay("set system parameters 'isolation_level=4'")
                        .containsKey(SessionSetStatement.ISOLATION_LEVEL_KEY));
        assertEquals(
                "set transaction lock timeout 5",
                replay("set transaction lock timeout 5;")
                        .get(SessionSetStatement.LOCK_TIMEOUT_KEY));
        // lock_timeout_in_secs is the deprecated half of the lock_timeout pair.
        assertTrue(
                replay("set system parameters 'lock_timeout_in_secs=5'")
                        .containsKey(SessionSetStatement.LOCK_TIMEOUT_KEY));
    }

    /** One statement setting both is replayed as two, each under its own key. */
    @Test
    public void transactionStatementSettingBothIsSplit() {
        assertEquals(
                Arrays.asList(
                        "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE",
                        "SET TRANSACTION LOCK TIMEOUT 5"),
                replayed("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE, LOCK TIMEOUT 5"));
        assertEquals(
                "set transaction isolation level 4",
                replay("set transaction lock timeout ?, isolation level 4")
                        .get(SessionSetStatement.ISOLATION_LEVEL_KEY));
        assertEquals(
                only(SessionSetStatement.LOCK_TIMEOUT_KEY),
                pins("set transaction lock timeout ?, isolation level 4"));
        // A comma inside an isolation level name does not start a second clause.
        assertEquals(
                Arrays.asList(
                        "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ CLASS, READ COMMITTED"
                                + " INSTANCES"),
                replayed(
                        "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ CLASS, READ COMMITTED"
                                + " INSTANCES"));
    }

    @Test
    public void optimizationLevelIsReplayedLikeItsParameter() {
        // Levels 2/258/514 plan without executing, so a read leg left at 1 would return rows.
        assertEquals(
                "SET OPTIMIZATION LEVEL 2",
                replay("SET OPTIMIZATION LEVEL 2").get("PARAM:OPTIMIZATION_LEVEL"));
        assertTrue(
                replay("set system parameters 'optimization_level=1'")
                        .containsKey("PARAM:OPTIMIZATION_LEVEL"));
        assertEquals(only("PARAM:OPTIMIZATION_LEVEL"), pins("SET OPTIMIZATION LEVEL ?"));
        assertEquals(
                only(SessionSetStatement.UNKNOWN_PIN), pins("SET OPTIMIZATION COST 'iscan' '0'"));
    }

    /** Both legs trace; SHOW TRACE then runs where the traced query ran. */
    @Test
    public void queryTraceIsReplayedUnderOneKey() {
        final String key = SessionSetStatement.QUERY_TRACE_KEY;
        assertEquals("SET TRACE ON", replay("SET TRACE ON").get(key));
        assertEquals("set trace on output json", replay("set trace on output json").get(key));
        assertEquals("SET TRACE OFF", replay("SET TRACE OFF").get(key));
        assertEquals(
                "SET SYSTEM PARAMETERS 'query_trace=yes'",
                replay("set system parameters 'query_trace=yes'").get(key));
        assertTrue(pins("SET TRACE ON").isEmpty());
        assertFalse(replay("set system parameters 'query_trace_format=json'").isEmpty());
    }

    /**
     * SHOW EXEC STATISTICS reports what its own session collected so far, and a session collects
     * only while its {@code @collect_exec_stats} is on: split over two legs the totals would
     * differ.
     */
    @Test
    public void execStatisticsCollectionPinsUntilTurnedOff() {
        final Set<String> stats = only(SessionSetStatement.EXEC_STATS_PIN);
        assertEquals(stats, pins("SET @collect_exec_stats = 1"));
        assertEquals(stats, pins("set @COLLECT_EXEC_STATS := 1"));
        assertEquals(stats, pins("SET @a = (1, 2), @collect_exec_stats = 1"));
        assertEquals(stats, pins("SET @collect_exec_stats = ?"));
        assertEquals(stats, unpins("SET @collect_exec_stats = 0 -- off"));
        assertEquals(stats, unpins("SET @collect_exec_stats = 0, @v = 1"));
        assertUntracked("SET @collect_exec_statsx = 1, @v = '@collect_exec_stats'");
    }

    /** The variable is the same one however it is written: with a comment, quoted, or by :=. */
    @Test
    public void execStatisticsSwitchIsReadInEveryFormTheServerTakes() {
        final Set<String> stats = only(SessionSetStatement.EXEC_STATS_PIN);
        final String[] on = {
            "SET /*c*/ @collect_exec_stats = 1",
            "SET\n-- turn on\n@collect_exec_stats = 1",
            "SET @collect_exec_stats /*on*/ = 1",
            "SET @a = 1, /*c*/ @collect_exec_stats = 1",
            "SET @\"collect_exec_stats\" = 1",
            "SELECT @collect_exec_stats := 1 FROM db_root",
            "SELECT 1 FROM db_root; SELECT @collect_exec_stats := 1 FROM db_root",
        };
        for (String sql : on) {
            assertEquals(sql, stats, pins(sql));
        }
        assertEquals(stats, unpins("SET /*c*/ @collect_exec_stats = 0"));
        assertUntracked("SELECT @collect_exec_stats FROM db_root");
        assertUntracked("SELECT 1 FROM t WHERE @collect_exec_stats = 1");
        assertUntracked("SET @a = (SELECT 1 FROM db_root WHERE @collect_exec_stats = 1)");
    }

    @Test
    public void otherStatementsAreNotTracked() {
        assertUntracked("SET @v = 1");
        assertUntracked("SELECT 'set names utf8' FROM t");
        assertUntracked("SETTINGS");
        assertUntracked(null);
        assertUntracked("SELECT 1; SELECT 2");
    }

    /** A bound value is not in the text, so the setting pins until a literal SET of it. */
    @Test
    public void boundValuePinsUnderItsSetting() {
        assertEquals(only(SessionSetStatement.TIMEZONE_KEY), pins("SET TIMEZONE ?"));
        assertEquals(only(SessionSetStatement.NAMES_KEY), pins("SET NAMES ?"));
        assertEquals(
                only(SessionSetStatement.ISOLATION_LEVEL_KEY),
                pins("SET TRANSACTION ISOLATION LEVEL ?"));
    }

    /** Each statement of a multi-statement string counts on its own; a later one wins. */
    @Test
    public void multiStatementStringIsReadInOrder() {
        assertEquals(Arrays.asList("SET NAMES utf8"), replayed("SELECT 1; SET NAMES utf8"));
        assertEquals(Arrays.asList("SET NAMES utf8"), replayed("SET NAMES utf8;  -- done\n"));
        assertEquals(
                Arrays.asList("SET NAMES euckr", "SET TIMEZONE 'UTC'"),
                replayed("SET NAMES euckr; SELECT 2; SET TIMEZONE 'UTC'"));
        assertEquals(Arrays.asList("SET NAMES euckr"), replayed("SET NAMES utf8; SET NAMES euckr"));

        final String stats = "SET @collect_exec_stats = 1; SET NAMES utf8";
        assertEquals(only(SessionSetStatement.EXEC_STATS_PIN), pins(stats));
        assertEquals(Arrays.asList("SET NAMES utf8"), replayed(stats));

        final String onOff = "SET @collect_exec_stats = 1; SET @collect_exec_stats = 0";
        assertTrue(pins(onOff).isEmpty());
        assertEquals(only(SessionSetStatement.EXEC_STATS_PIN), unpins(onOff));
        assertEquals(Arrays.asList("SET TRACE OFF"), replayed("SET TRACE ON; SET TRACE OFF"));

        assertTrue(pins("SET TIMEZONE ?; SET TIMEZONE 'UTC'").isEmpty());
        assertEquals(
                Arrays.asList("SET TIMEZONE 'UTC'"),
                replayed("SET TIMEZONE ?; SET TIMEZONE 'UTC'"));
        assertEquals(
                only(SessionSetStatement.TIMEZONE_KEY), pins("SET TIMEZONE 'UTC'; SET TIMEZONE ?"));
        assertTrue(replay("SET TIMEZONE 'UTC'; SET TIMEZONE ?").isEmpty());
    }

    /** A failed string may have run any of its SETs; a single failed statement ran none. */
    @Test
    public void everySettingOfAMultiStatementStringPinsIfItFails() {
        assertEquals(
                new ArrayList<String>(
                        Arrays.asList(
                                SessionSetStatement.NAMES_KEY, SessionSetStatement.EXEC_STATS_PIN)),
                new ArrayList<String>(
                        SessionSetStatement.parse(
                                        "SET NAMES utf8; SET @collect_exec_stats = 0; SELECT 1")
                                .getPinsIfFailed()));
        assertEquals(
                only(SessionSetStatement.UNKNOWN_PIN),
                SessionSetStatement.parse("SELECT 1; SET SYSTEM PARAMETERS 'no_such=1'")
                        .getPinsIfFailed());
        assertTrue(SessionSetStatement.parse("SET NAMES utf8").getPinsIfFailed().isEmpty());
        assertTrue(SessionSetStatement.parse("SET NAMES utf8;").getPinsIfFailed().isEmpty());
        assertTrue(
                SessionSetStatement.parse("SET TRANSACTION ISOLATION LEVEL 4, LOCK TIMEOUT 5")
                        .getPinsIfFailed()
                        .isEmpty());
        assertTrue(
                SessionSetStatement.parse("SELECT 1; SET SYSTEM PARAMETERS 'sort_buffer_size=1M'")
                        .getPinsIfFailed()
                        .isEmpty());
    }

    @Test
    public void quoteInsideParameterValueIsEscapedForReplay() {
        assertEquals(
                "SET SYSTEM PARAMETERS 'intl_date_lang=it''s'",
                replay("set system parameters 'intl_date_lang=it''s'").get("PARAM:INTL_DATE_LANG"));
    }

    @Test
    public void assignmentOfAUserVariableIsRecognized() {
        final String[] assign = {
            "SET @a = 5",
            "set @a := 5",
            "SET /* c */ @a = 5",
            "SET @x = 1, @a = 5",
            "SET @\"a\" = 5",
            "SELECT 1 FROM db_root; SET @a = 5",
            "SELECT @a := 5 FROM db_root",
            "SELECT @a /* c */ := @a + 1 FROM db_root",
        };
        for (String sql : assign) {
            assertTrue(sql, SessionSetStatement.assignsUserVariable(sql));
        }
    }

    @Test
    public void readOfAUserVariableIsNotAnAssignment() {
        final String[] read = {
            "SELECT @a FROM db_root",
            "SELECT 1 FROM t WHERE @a = 5",
            "UPDATE t SET v = 1 WHERE k = @a",
            "SET NAMES utf8; SELECT 1 FROM t WHERE @a = 5",
            "SELECT '@a := 5' FROM db_root",
            "SELECT 1 /* SET @a = 5 */ FROM db_root",
            "SELECT 1 FROM db_root",
        };
        for (String sql : read) {
            assertFalse(sql, SessionSetStatement.assignsUserVariable(sql));
        }
    }

    /** Statistics collection and a lock timeout leave how a statement compiles alone. */
    @Test
    public void onlySomePinReasonsChangeHowAStatementCompiles() {
        assertFalse(SessionSetStatement.changesCompilation(Collections.<String>emptySet()));
        assertFalse(
                SessionSetStatement.changesCompilation(
                        Arrays.asList(
                                SessionSetStatement.EXEC_STATS_PIN,
                                SessionSetStatement.LOCK_TIMEOUT_KEY)));
        assertTrue(
                SessionSetStatement.changesCompilation(
                        Arrays.asList(
                                SessionSetStatement.EXEC_STATS_PIN,
                                SessionSetStatement.LOGIN_KEY)));
        assertTrue(
                SessionSetStatement.changesCompilation(
                        Collections.singleton(SessionSetStatement.UNKNOWN_PIN)));
        assertTrue(
                SessionSetStatement.changesCompilation(
                        Collections.singleton(SessionSetStatement.TIMEZONE_KEY)));
    }

    @Test
    public void isolationLevelOfASetTransactionIsRead() {
        final int rr = java.sql.Connection.TRANSACTION_REPEATABLE_READ;
        final int ser = java.sql.Connection.TRANSACTION_SERIALIZABLE;
        assertEquals(
                ser,
                SessionSetStatement.isolationAboveReadCommitted(
                        "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE"));
        assertEquals(
                rr,
                SessionSetStatement.isolationAboveReadCommitted(
                        "set transaction isolation level repeatable read"));
        assertEquals(
                rr,
                SessionSetStatement.isolationAboveReadCommitted(
                        "SET TRANSACTION ISOLATION LEVEL /* rr */ 5"));
        assertEquals(
                ser,
                SessionSetStatement.isolationAboveReadCommitted(
                        "SET TRANSACTION ISOLATION LEVEL 6"));
        assertEquals(
                0,
                SessionSetStatement.isolationAboveReadCommitted(
                        "SET TRANSACTION ISOLATION LEVEL READ COMMITTED"));
        assertEquals(
                0,
                SessionSetStatement.isolationAboveReadCommitted(
                        "SET TRANSACTION ISOLATION LEVEL 4"));
    }
}
