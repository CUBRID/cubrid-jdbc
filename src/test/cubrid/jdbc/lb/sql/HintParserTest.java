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

import static org.junit.Assert.*;

import org.junit.Test;

public class HintParserTest {

    @Test
    public void assertNullSqlReturnsEmpty() {
        HintSet hs = HintParser.parse(null);
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertEmptyStringReturnsEmpty() {
        HintSet hs = HintParser.parse("");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertNonSelectReturnsEmpty() {
        HintSet hs = HintParser.parse("INSERT INTO t VALUES (1)");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertSelectWithoutHintReturnsEmpty() {
        HintSet hs = HintParser.parse("SELECT * FROM t");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertSelectToMaster() {
        HintSet hs = HintParser.parse("SELECT /*+ TO_RW */  * FROM t");
        assertTrue(hs.hasTargetHint());
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertSelectToSlave() {
        HintSet hs = HintParser.parse("SELECT /*+ TO_RO */  * FROM t");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertUnsupportedToSoSkippedLeavesFollowingTargetHint() {
        // The removed TO_SO token is skipped as unsupported, so a following TO_RW still wins.
        HintSet hs = HintParser.parse("SELECT /*+ TO_SO TO_RW */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertCaseInsensitiveSelectKeyword() {
        HintSet hs = HintParser.parse("select /*+ TO_RW */  * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertCaseInsensitiveHintToken() {
        HintSet hs = HintParser.parse("SELECT /*+ to_ro */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertMixedCaseHintToken() {
        HintSet hs = HintParser.parse("SELECT /*+ To_Rw */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertLeadingWhitespace() {
        HintSet hs = HintParser.parse("   SELECT /*+ TO_RW */  * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertLeadingNewlines() {
        HintSet hs = HintParser.parse("\n\n  SELECT /*+ TO_RO */  * FROM t");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertLeadingBlockCommentDoesNotHideHint() {
        HintSet hs = HintParser.parse("/* app-tag */ SELECT /*+ TO_RW */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertLeadingLineCommentDoesNotHideHint() {
        HintSet hs = HintParser.parse("-- comment\nSELECT /*+ TO_RW */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertLeadingSlashSlashLineCommentDoesNotHideHint() {
        HintSet hs = HintParser.parse("// comment\nSELECT /*+ TO_RW */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertMultipleLeadingCommentsDoNotHideHint() {
        HintSet hs =
                HintParser.parse("/* a */\n-- b\n  // c\n /* c */ SELECT /*+ TO_RO */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertLeadingCommentBeforeWithClauseDoesNotHideHint() {
        HintSet hs =
                HintParser.parse("/* tag */ WITH cte AS (SELECT 1) SELECT /*+ TO_RW */ * FROM cte");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertUnterminatedLeadingBlockCommentReturnsEmpty() {
        HintSet hs = HintParser.parse("/* never closed SELECT /*+ TO_RW */ * FROM t");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertCommaDelimitedTokens() {
        HintSet hs = HintParser.parse("SELECT /*+ USE_IDX,TO_RW */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertMixedDelimiters() {
        HintSet hs = HintParser.parse("SELECT /*+ TO_RO , USE_IDX */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertOptimizerHintIgnored() {
        HintSet hs = HintParser.parse("SELECT /*+ USE_IDX FULL_SCAN TO_RW */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertOnlyOptimizerHintsReturnsEmpty() {
        HintSet hs = HintParser.parse("SELECT /*+ USE_IDX FULL_SCAN */ * FROM t");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertUnclosedCommentReturnsEmpty() {
        HintSet hs = HintParser.parse("SELECT /*+ TO_RW * FROM t");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertMissingPlusReturnsEmpty() {
        HintSet hs = HintParser.parse("SELECT /* TO_RW */ * FROM t");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertHintNotImmediatelyAfterSelectReturnsEmpty() {
        HintSet hs = HintParser.parse("SELECT * FROM /*+ TO_RW */  t");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertUpdateWithHintReturnsEmpty() {
        HintSet hs = HintParser.parse("UPDATE /*+ TO_RW */  t SET a = 1");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertDeleteWithHintReturnsEmpty() {
        HintSet hs = HintParser.parse("DELETE /*+ TO_RW */  FROM t");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertInsertWithHintReturnsEmpty() {
        HintSet hs = HintParser.parse("INSERT /*+ TO_RW */  INTO t VALUES (1)");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertDuplicateTargetFirstWins() {
        HintSet hs = HintParser.parse("SELECT /*+ TO_RW TO_RO */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertDuplicateTargetFirstWinsThreeWay() {
        HintSet hs = HintParser.parse("SELECT /*+ TO_RO TO_RW TO_RO */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertLbHintPlusOptimizerHintOnlyLbExtracted() {
        HintSet hs =
                HintParser.parse("SELECT /*+ USE_NL(t1 t2) TO_RO ORDERED */ * FROM t1 JOIN t2");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertDeterministicOutput() {
        String sql = "SELECT /*+ TO_RW TO_RO */ * FROM t";
        HintSet first = HintParser.parse(sql);
        HintSet second = HintParser.parse(sql);
        assertEquals(first.getTargetHint(), second.getTargetHint());
    }

    @Test
    public void assertEmptyHintBlockReturnsEmpty() {
        HintSet hs = HintParser.parse("SELECT /*+  */ * FROM t");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertTabAndNewlineInHintBlock() {
        HintSet hs = HintParser.parse("SELECT /*+\tTO_RW\n*/ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertMultipleSpacesBetweenSelectAndHint() {
        HintSet hs = HintParser.parse("SELECT     /*+ TO_RW */  * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertToMasterAliasMapsToToRw() {
        HintSet hs = HintParser.parse("SELECT /*+ TO_MASTER */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertToSlaveAliasMapsToToRo() {
        HintSet hs = HintParser.parse("SELECT /*+ TO_SLAVE */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertCanonicalAndAliasMixedFirstWins() {
        HintSet hs = HintParser.parse("SELECT /*+ TO_RW TO_SLAVE */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertAliasIsCaseInsensitive() {
        HintSet hs = HintParser.parse("SELECT /*+ to_master */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertWithSelectExtractsTopLevelHint() {
        HintSet hs =
                HintParser.parse(
                        "WITH cte AS (SELECT item FROM products) "
                                + "SELECT /*+ TO_RO */ item FROM cte");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertWithSelectIgnoresHintInsideCte() {
        HintSet hs =
                HintParser.parse(
                        "WITH cte AS (SELECT /*+ TO_RW */ item FROM products) "
                                + "SELECT item FROM cte");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertWithSelectPrefersTopLevelHintOverCteHint() {
        HintSet hs =
                HintParser.parse(
                        "WITH cte AS (SELECT /*+ TO_RW */ item FROM products) "
                                + "SELECT /*+ TO_RO */ item FROM cte");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertWithRecursiveSelectExtractsTopLevelHint() {
        HintSet hs =
                HintParser.parse(
                        "WITH RECURSIVE cars (id, parent_id) AS ("
                                + " SELECT id, parent_id FROM products WHERE item LIKE 'Car%'"
                                + " UNION ALL"
                                + " SELECT /*+ TO_RW */ p.id, p.parent_id FROM products p"
                                + " INNER JOIN cars c ON p.parent_id = c.id)"
                                + " SELECT /*+ TO_RO */ id FROM cars");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertWithMultipleCtesExtractsTopLevelHint() {
        HintSet hs =
                HintParser.parse(
                        "WITH a AS (SELECT 1), b AS (SELECT 2) "
                                + "SELECT /*+ TO_RW */ * FROM a UNION ALL SELECT * FROM b");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertWithSelectAcceptsLegacyAlias() {
        HintSet hs =
                HintParser.parse(
                        "WITH cte AS (SELECT /*+ TO_RW */ 1) SELECT /*+ TO_SLAVE */ * FROM cte");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertWithSelectMissingHintReturnsEmpty() {
        HintSet hs = HintParser.parse("WITH cte AS (SELECT 1) SELECT * FROM cte");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertWithInsertReturnsEmpty() {
        HintSet hs =
                HintParser.parse(
                        "WITH cte AS (SELECT 1 AS v) "
                                + "INSERT /*+ TO_RW */ INTO t SELECT /*+ TO_RW */ v FROM cte");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertWithUpdateReturnsEmpty() {
        HintSet hs =
                HintParser.parse(
                        "WITH cte AS (SELECT id FROM s) UPDATE t SET a = 1 WHERE id IN (SELECT id FROM cte)");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertWithSelectCaseInsensitive() {
        HintSet hs =
                HintParser.parse(
                        "with cte as (select /*+ TO_RW */ 1) select /*+ TO_RO */ * from cte");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertWithSelectColumnListBeforeAs() {
        HintSet hs =
                HintParser.parse(
                        "WITH cte (a, b) AS (SELECT /*+ TO_RO */ 1, 2) SELECT /*+ TO_RW */ a FROM cte");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertWithSelectStringLiteralWithParenIgnored() {
        HintSet hs =
                HintParser.parse("WITH cte AS (SELECT '(' AS v) SELECT /*+ TO_RW */ v FROM cte");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertSelectorIdentifierNotTreatedAsSelect() {
        HintSet hs = HintParser.parse("SELECTOR /*+ TO_RW */ * FROM t");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertSubqueryOptimizerHintInSelectListReturnsEmpty() {
        HintSet hs =
                HintParser.parse(
                        "SELECT h.host_year, "
                                + "(SELECT /*+ QUERY_CACHE */ host_nation FROM olympic o "
                                + "WHERE o.host_year > 1994 limit 1) AS host_nation, "
                                + "h.event_code, h.score, h.unit "
                                + "FROM history h");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertDerivedTableOptimizerHintsReturnEmpty() {
        HintSet hs =
                HintParser.parse(
                        "SELECT * "
                                + "FROM (SELECT /*+ NO_MERGE*/ * FROM athlete WHERE nation_code = 'USA') a, "
                                + "(SELECT /*+ NO_MERGE*/ * FROM record WHERE medal = 'G') b "
                                + "WHERE a.code = b.athlete_code");
        assertTrue(hs.isEmpty());
    }

    @Test
    public void assertSubqueryOptimizerHintPlusTopLevelToRo() {
        HintSet hs =
                HintParser.parse(
                        "SELECT /*+ TO_RO */ h.host_year, "
                                + "(SELECT /*+ QUERY_CACHE */ host_nation FROM olympic o "
                                + "WHERE o.host_year > 1994 limit 1) AS host_nation, "
                                + "h.event_code, h.score, h.unit "
                                + "FROM history h");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertSubqueryOptimizerHintPlusTopLevelToRw() {
        HintSet hs =
                HintParser.parse(
                        "SELECT /*+ TO_RW */ h.host_year, "
                                + "(SELECT /*+ QUERY_CACHE */ host_nation FROM olympic o "
                                + "WHERE o.host_year > 1994 limit 1) AS host_nation, "
                                + "h.event_code, h.score, h.unit "
                                + "FROM history h");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    @Test
    public void assertDerivedTableOptimizerHintPlusTopLevelToRo() {
        HintSet hs =
                HintParser.parse(
                        "SELECT /*+ TO_RO */ * "
                                + "FROM (SELECT /*+ NO_MERGE*/ * FROM athlete WHERE nation_code = 'USA') a, "
                                + "(SELECT /*+ NO_MERGE*/ * FROM record WHERE medal = 'G') b "
                                + "WHERE a.code = b.athlete_code");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }

    @Test
    public void assertDerivedTableOptimizerHintPlusTopLevelToRw() {
        HintSet hs =
                HintParser.parse(
                        "SELECT /*+ TO_RW */ * "
                                + "FROM (SELECT /*+ TO_RO NO_MERGE*/ * FROM athlete WHERE nation_code = 'USA') a, "
                                + "(SELECT /*+ TO_RO NO_MERGE*/ * FROM record WHERE medal = 'G') b "
                                + "WHERE a.code = b.athlete_code");
        assertEquals(HintSet.TargetHint.TO_RW, hs.getTargetHint());
    }

    /**
     * The stateful trio is gone (2026-08-11): the tokens must be skipped like any optimizer hint,
     * not recognized. They pinned session routing that a pooled connection carried past the
     * borrower that wrote it.
     */
    @Test
    public void assertRemovedStatefulTokensAreIgnored() {
        assertTrue(HintParser.parse("SELECT /*+ AFTER_WRITE_TO_RW */ * FROM t").isEmpty());
        assertTrue(HintParser.parse("SELECT /*+ STICKY_TO_RW */ * FROM t").isEmpty());
        assertTrue(HintParser.parse("SELECT /*+ UNSTICK_FROM_RW */ * FROM t").isEmpty());
        assertTrue(HintParser.parse("SELECT /*+ AFTER_WRITE_TO_MASTER */ * FROM t").isEmpty());
        assertTrue(HintParser.parse("SELECT /*+ STICKY_TO_MASTER */ * FROM t").isEmpty());
        assertTrue(HintParser.parse("SELECT /*+ UNSTICK_FROM_MASTER */ * FROM t").isEmpty());
    }

    /** A removed token next to a live one must not swallow it. */
    @Test
    public void assertRemovedStatefulTokenDoesNotHideTargetHint() {
        HintSet hs = HintParser.parse("SELECT /*+ STICKY_TO_RW TO_RO */ * FROM t");
        assertEquals(HintSet.TargetHint.TO_RO, hs.getTargetHint());
    }
}
