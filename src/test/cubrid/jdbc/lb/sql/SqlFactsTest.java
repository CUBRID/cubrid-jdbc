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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * {@link SqlFacts} answers what the detectors answer, once per text: every step of an execution
 * reads the same object instead of reading the text again.
 */
public class SqlFactsTest {
    private static final String[] SQLS = {
        "SELECT * FROM t WHERE id = ?",
        "/*+ TO_RO */ SELECT 1",
        "SET NAMES utf8",
        "SET SYSTEM PARAMETERS 'default_week_format=1'",
        "SET TRANSACTION ISOLATION LEVEL 4; SET TRANSACTION LOCK TIMEOUT 5",
        "SET @collect_exec_stats = 1",
        "SELECT a INTO :v FROM t; SELECT 1/0",
        "SELECT a INTO :v FROM t",
        "DROP VARIABLE v, @w",
        "SET @x = 1",
        "PREPARE s FROM 'SELECT 1'",
        "CALL login('u', 'pw') ON CLASS db_user",
        "CREATE PROCEDURE p() AS v INT; BEGIN SELECT 1 INTO v; END;",
        "UPDATE t SET c = 1",
        ""
    };

    @Test
    public void answersWhatTheDetectorsAnswer() {
        for (String sql : SQLS) {
            final SqlFacts facts = SqlFacts.of(sql);
            assertEquals(sql, HintParser.parse(sql).getTargetHint(), facts.hints().getTargetHint());
            final SessionSetStatement set = SessionSetStatement.parse(sql);
            assertEquals(sql, set.getReplay(), facts.sessionSet().getReplay());
            assertEquals(sql, set.getPins(), facts.sessionSet().getPins());
            assertEquals(sql, set.getUnpins(), facts.sessionSet().getUnpins());
            assertEquals(sql, set.getPinsIfFailed(), facts.sessionSet().getPinsIfFailed());
            assertEquals(sql, set.getBoundKey(), facts.sessionSet().getBoundKey());
            assertEquals(sql, IntoVariables.assignedBy(sql), facts.assignedVariables());
            assertEquals(sql, IntoVariables.droppedBy(sql), facts.droppedVariables());
            assertEquals(sql, IntoVariables.assignedIfFailed(sql), facts.assignedIfFailed());
            assertEquals(
                    sql, SessionSetStatement.assignsUserVariable(sql), facts.assignsUserVariable());
            assertEquals(
                    sql,
                    SessionSetStatement.preparesOrDropsAStatement(sql),
                    facts.preparesOrDropsAStatement());
        }
    }

    @Test
    public void aTextIsReadOnce() {
        final String sql = new String("SELECT a INTO :v FROM t WHERE id = 1");
        final SqlFacts first = SqlFacts.of(sql);
        assertSame(first, SqlFacts.of(new String(sql)));
        assertSame(first.sessionSet(), SqlFacts.of(sql).sessionSet());
        assertSame(first.assignedVariables(), SqlFacts.of(sql).assignedVariables());
    }

    /** As the classification cache, a text too long to keep is read each time it is asked. */
    @Test
    public void aLongTextIsNotKept() {
        final StringBuilder b = new StringBuilder("SELECT 1");
        while (b.length() <= 4096) {
            b.append(" + 1");
        }
        final String sql = b.toString();
        assertNotSame(SqlFacts.of(sql), SqlFacts.of(sql));
    }

    /** One object serves every connection, so no reader may change what it holds. */
    @Test
    public void sharedSetsCannotBeChanged() {
        final SqlFacts facts = SqlFacts.of("SELECT a INTO :v FROM t; SELECT 1/0");
        for (java.util.Set<String> each :
                new java.util.Set[] {
                    facts.assignedVariables(), facts.droppedVariables(), facts.assignedIfFailed()
                }) {
            try {
                each.add("X");
                fail("a shared set took a change");
            } catch (UnsupportedOperationException expected) {
            }
        }
    }

    @Test
    public void aNullTextIsTheEmptyOne() {
        assertSame(SqlFacts.of(""), SqlFacts.of(null));
    }
}
