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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.sql.SessionSetStatement.Action;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import org.junit.Test;

public class SessionSetStatementTest {

    private static Action action(final String sql) {
        return SessionSetStatement.parse(sql).getAction();
    }

    private static Map<String, String> replay(final String sql) {
        return SessionSetStatement.parse(sql).getReplay();
    }

    @Test
    public void namesAndTimezoneAreReplayedVerbatim() {
        assertEquals(Action.REPLAY, action("SET NAMES utf8 COLLATE utf8_bin"));
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
        Map<String, String> r =
                replay(
                        "SET SYSTEM PARAMETERS 'intl_date_lang=ko_KR; return_null_on_function_errors"
                                + "=yes'");
        assertEquals(Action.REPLAY, action("set system parameters 'default_week_format=1'"));
        assertEquals(
                Arrays.asList(
                        "SET SYSTEM PARAMETERS 'intl_date_lang=ko_KR'",
                        "SET SYSTEM PARAMETERS 'return_null_on_function_errors=yes'"),
                new ArrayList<String>(r.values()));
        // SET TIMEZONE and the timezone parameter change one setting, so they share a key.
        assertTrue(
                replay("set system parameters 'timezone=UTC'")
                        .containsKey(SessionSetStatement.TIMEZONE_KEY));
    }

    @Test
    public void parameterOutsideTheSessionListPins() {
        assertEquals(Action.PIN_TO_RW, action("set system parameters 'compat_mode=mysql'"));
        assertEquals(
                Action.PIN_TO_RW,
                action("set system parameters 'default_week_format=1; error_log_level=warning'"));
        assertEquals(Action.PIN_TO_RW, action("set system parameters 'no_equals_sign'"));
    }

    @Test
    public void parametersLbAlreadyOwnsAreIgnored() {
        assertEquals(Action.NONE, action("set system parameters 'isolation_level=4'"));
        assertEquals(
                Action.NONE, action("set system parameters 'lock_timeout=5; csql_auto_commit=no'"));
        assertEquals(
                Arrays.asList("SET SYSTEM PARAMETERS 'timezone=UTC'"),
                new ArrayList<String>(
                        replay("set system parameters 'lock_timeout=5; timezone=UTC'").values()));
    }

    @Test
    public void otherStatementsAreNotTracked() {
        assertEquals(Action.NONE, action("SET @v = 1"));
        assertEquals(Action.NONE, action("SET TRANSACTION ISOLATION LEVEL READ COMMITTED"));
        assertEquals(Action.NONE, action("SET OPTIMIZATION LEVEL 1"));
        assertEquals(Action.NONE, action("SELECT 'set names utf8' FROM t"));
        assertEquals(Action.NONE, action("SETTINGS"));
        assertEquals(Action.NONE, action(null));
    }

    @Test
    public void setThatCannotBeReplayedOnItsOwnPins() {
        // A compound string cannot be replayed without its other statements.
        assertEquals(Action.PIN_TO_RW, action("SELECT 1; SET NAMES utf8"));
        // A bound value is not in the text.
        assertEquals(Action.PIN_TO_RW, action("SET TIMEZONE ?"));
        // Only the trailing ';' of a single statement is not a compound string.
        assertEquals(Action.REPLAY, action("SET NAMES utf8;  -- done\n"));
        assertEquals(Action.NONE, action("SELECT 1; SELECT 2"));
    }

    @Test
    public void quoteInsideParameterValueIsEscapedForReplay() {
        assertEquals(
                "SET SYSTEM PARAMETERS 'intl_date_lang=it''s'",
                replay("set system parameters 'intl_date_lang=it''s'").get("PARAM:INTL_DATE_LANG"));
    }
}
