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
import static org.junit.Assert.assertNotNull;

import org.junit.Before;
import org.junit.Test;

public class KeywordSqlClassifierTest {

    private SqlClassifier classifier;

    @Before
    public void setUp() {
        classifier = new KeywordSqlClassifier();
    }

    /**
     * A session variable lives on the one physical connection that ran the {@code SET}. A read
     * endpoint does not have it, and the server does not answer NULL: it fails with {@code Session
     * variable '@name' not defined}. On a live cluster, {@code SET @start = SYS_DATETIME} succeeded
     * on a loadbalance URL (UNKNOWN, so the write endpoint) while {@code SELECT @start} failed; the
     * same pair worked with a {@code TO_RW} hint and on a classic URL.
     *
     * <p>Classifying the read {@code UNKNOWN} sends it where the {@code SET} went. The cost is that
     * such a statement leaves the read distribution, which is the point, not a side effect.
     */
    @Test
    public void assertSessionVariableReferenceIsNotReadOnly() {
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("SELECT @start"));
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("SELECT @start, id FROM t"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT id FROM t WHERE created > @start"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("WITH c AS (SELECT 1 AS x FROM t) SELECT @start FROM c"));
    }

    /**
     * The sigil must not misfire where {@code @} is data or part of a name. The scan consults
     * {@link cubrid.jdbc.lb.sql.SqlLexer#skip} before the sigil branch, so a string literal, either
     * comment form, and all three quoted-identifier forms are already stepped over. Pinned because
     * a naive {@code indexOf('@')} would send every SELECT containing an email address to the write
     * endpoint and quietly halve the read distribution of a normal application.
     */
    @Test
    public void assertAtSignInsideLiteralsAndIdentifiersStaysReadOnly() {
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT id FROM t WHERE email = 'user@example.com'"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT id FROM t -- keep @start out of this\n"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT id /* @start is a comment here */ FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT \"a@b\" FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT [a@b] FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT `a@b` FROM t"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT id FROM t WHERE note = 'it''s @home'"));
    }

    @Test
    public void assertSqlClassificationEnumValues() {
        assertEquals(3, SqlClassification.values().length);
        assertNotNull(SqlClassification.valueOf("READ"));
        assertNotNull(SqlClassification.valueOf("WRITE"));
        assertNotNull(SqlClassification.valueOf("UNKNOWN"));
    }

    @Test
    public void assertSelectClassifiedAsRead() {
        assertEquals(SqlClassification.READ, classifier.classify("   SELECT * FROM t"));
    }

    /** CTE with a top-level SELECT is a READ (spec §8.2 "CTE READ" / §8.5 T5). */
    @Test
    public void assertWithSelectClassifiedAsRead() {
        assertEquals(
                SqlClassification.READ,
                classifier.classify("WITH cte AS (SELECT 1) SELECT * FROM cte"));
    }

    @Test
    public void assertWithRecursiveSelectClassifiedAsRead() {
        assertEquals(
                SqlClassification.READ,
                classifier.classify(
                        "WITH RECURSIVE oc AS (\n"
                                + "  -- anchor\n"
                                + "  SELECT emp_id, manager_id, 1 AS lvl FROM employees WHERE manager_id IS NULL\n"
                                + "  UNION ALL\n"
                                + "  SELECT e.emp_id, e.manager_id, o.lvl + 1 FROM employees e\n"
                                + "  JOIN oc o ON e.manager_id = o.emp_id)\n"
                                + "SELECT * FROM oc ORDER BY lvl, emp_id"));
    }

    /**
     * CUBRID does not support the {@code EXPLAIN <statement>} syntax (it errors), so a leading
     * EXPLAIN is an unrecognized statement -> conservative UNKNOWN -> RW.
     */
    @Test
    public void assertExplainClassifiedAsUnknown() {
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("EXPLAIN SELECT * FROM t"));
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("EXPLAIN t"));
    }

    @Test
    public void assertInsertUpdateDeleteClassifiedAsWrite() {
        assertEquals(SqlClassification.WRITE, classifier.classify("INSERT INTO t VALUES (1)"));
        assertEquals(SqlClassification.WRITE, classifier.classify("UPDATE t SET c=1"));
        assertEquals(SqlClassification.WRITE, classifier.classify("DELETE FROM t"));

        assertEquals(SqlClassification.WRITE, classifier.classify("INSERT INTO t VALUES (1)"));
    }

    @Test
    public void assertDdlClassifiedAsWrite() {
        assertEquals(SqlClassification.WRITE, classifier.classify("CREATE TABLE t(id int)"));
        assertEquals(
                SqlClassification.WRITE, classifier.classify("ALTER TABLE t ADD COLUMN c int"));
        assertEquals(SqlClassification.WRITE, classifier.classify("DROP TABLE t"));
        assertEquals(SqlClassification.WRITE, classifier.classify("TRUNCATE TABLE t"));
    }

    @Test
    public void assertSelectForUpdateClassifiedAsWrite() {
        assertEquals(SqlClassification.WRITE, classifier.classify("SELECT * FROM t FOR UPDATE"));
    }

    /**
     * A clause/operator keyword immediately followed by {@code (} must NOT be mistaken for a
     * user-routine call — these SELECTs are plain READs and must route to RO/SO, not RW.
     */
    @Test
    public void assertClauseKeywordParenthesisClassifiedAsRead() {
        assertEquals(SqlClassification.READ, classifier.classify("SELECT * FROM t WHERE (a=1)"));
        assertEquals(
                SqlClassification.READ, classifier.classify("SELECT * FROM users WHERE (id=1)"));
        assertEquals(
                SqlClassification.READ, classifier.classify("SELECT * FROM t WHERE a=1 AND (b=2)"));
        assertEquals(
                SqlClassification.READ, classifier.classify("SELECT * FROM t WHERE a=1 OR (b=2)"));
        assertEquals(
                SqlClassification.READ, classifier.classify("SELECT * FROM t WHERE NOT (a=1)"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT a, COUNT(*) FROM t GROUP BY a HAVING (COUNT(*) > 1)"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT * FROM t1 JOIN t2 ON (t1.a=t2.b)"));
        assertEquals(
                SqlClassification.READ, classifier.classify("SELECT * FROM t1 JOIN t2 USING (a)"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT a FROM t UNION (SELECT a FROM s)"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT a FROM t INTERSECT (SELECT a FROM s)"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT a FROM t EXCEPT (SELECT a FROM s)"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT CASE WHEN (a=1) THEN 1 ELSE 0 END FROM t"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT * FROM t WHERE a BETWEEN (1) AND (2)"));
    }

    /**
     * Negative controls: the clause-keyword fix must NOT relax genuine non-RO calls. A real user
     * routine, serial write, and session-state builtin still route off RO.
     */
    @Test
    public void assertClauseKeywordFixDoesNotRegressNonRoCalls() {
        // user-defined routine call -> conservative UNKNOWN (RW)
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("SELECT my_udf(a) FROM t"));
        // serial NEXTVAL -> WRITE
        assertEquals(SqlClassification.WRITE, classifier.classify("SELECT seq.NEXTVAL FROM t"));
        // session-state builtin -> UNKNOWN (RW)
        assertEquals(
                SqlClassification.UNKNOWN, classifier.classify("SELECT LAST_INSERT_ID() FROM t"));
        // RO-safe builtin call -> READ
        assertEquals(SqlClassification.READ, classifier.classify("SELECT UPPER(name) FROM t"));
    }

    @Test
    public void assertLeadingCallClassifiedAsWrite() {
        assertEquals(SqlClassification.WRITE, classifier.classify("CALL my_proc(?)"));
        assertEquals(SqlClassification.WRITE, classifier.classify("call my_proc()"));
    }

    @Test
    public void assertJdbcEscapeCallClassifiedAsWrite() {
        assertEquals(SqlClassification.WRITE, classifier.classify("{call my_proc(?)}"));
        assertEquals(SqlClassification.WRITE, classifier.classify("{ CALL my_proc() }"));
    }

    @Test
    public void assertSelectWithPotentialSideEffectsClassifiedAsUnknown() {
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT * INTO OUTFILE '/tmp/x' FROM t"));
        assertEquals(
                SqlClassification.UNKNOWN, classifier.classify("SELECT CALL proc_from_select()"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT * FROM t; UPDATE t SET c=1"));
    }

    /**
     * INTO-less INSERT ("INSERT tbl VALUES ..."/"INSERT tbl SET ...", CUBRID allows omitting INTO)
     * trailing a SELECT in a multi-statement must not be classified READ, or the write would route
     * to a slave/replica. It must fall to UNKNOWN → RW.
     */
    @Test
    public void assertIntolessInsertAfterSelectClassifiedAsUnknown() {
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT 1 FROM t; INSERT t VALUES (1)"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT 1 FROM t; INSERT t SET c=1"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT 1 FROM t; INSERT INTO t VALUES (1)"));
    }

    @Test
    public void assertNullEmptyUnknownClassifiedAsUnknown() {
        assertEquals(SqlClassification.UNKNOWN, classifier.classify(null));
        assertEquals(SqlClassification.UNKNOWN, classifier.classify(""));
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("   "));
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("FOOBAR BAZ"));
    }

    @Test
    public void assertLeadingWhitespaceAndCommentsHandled() {
        assertEquals(SqlClassification.READ, classifier.classify("   \n\t SELECT * FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("/* comment */ SELECT * FROM t"));
        assertEquals(
                SqlClassification.READ, classifier.classify("-- line comment\nSELECT * FROM t"));
        assertEquals(
                SqlClassification.READ, classifier.classify("// line comment\nSELECT * FROM t"));
    }

    @Test
    public void assertUnclosedBlockCommentReturnsUnknown() {
        assertEquals(
                SqlClassification.UNKNOWN, classifier.classify("/* not closed SELECT * FROM t"));
    }

    /**
     * CUBRID 11.4 SELECT: FROM may use {@code <subquery> <correlation>} (see manual SELECT
     * grammar). Statement still begins with SELECT → READ.
     */
    @Test
    public void assertSelectWithSubqueryInFromClassifiedAsRead() {
        assertEquals(
                SqlClassification.READ,
                classifier.classify(
                        "SELECT * FROM (SELECT a FROM t1 WHERE a > 0) AS subq WHERE subq.a < 10"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify(
                        "SELECT o.id FROM orders o "
                                + "WHERE EXISTS (SELECT 1 FROM line_items l WHERE l.order_id = o.id)"));
    }

    @Test
    public void assertDmlWithSubqueryClassifiedAsWrite() {
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify("INSERT INTO target_tbl SELECT * FROM source_tbl"));
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify(
                        "UPDATE t1 SET c = (SELECT MAX(d) FROM t2 WHERE t2.ref = t1.id) WHERE id = 1"));
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify(
                        "DELETE FROM t1 WHERE id IN (SELECT parent_id FROM t2 WHERE flag = 'Y')"));
    }

    /**
     * CTE-led DML is classified by its top-level statement verb (spec §8.2): WITH ...
     * INSERT/UPDATE/DELETE -> WRITE, even though the CTE body is a SELECT subquery.
     */
    @Test
    public void assertCteLeadingWriteClassifiedAsWrite() {
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify(
                        "WITH cte AS (SELECT 1 AS x) DELETE FROM t WHERE c = (SELECT x FROM cte)"));
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify(
                        "WITH cte AS (SELECT id FROM src) INSERT INTO dst SELECT id FROM cte"));
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify("WITH cte AS (SELECT 1) UPDATE t SET c = 1 WHERE id = 1"));
    }

    /**
     * Real-world CTE-led statements (multi-line, window functions, HAVING, column list on INSERT):
     * classified by the top-level verb after the CTE definition list.
     */
    @Test
    public void assertMultilineCteLedStatementsClassifiedByTopLevelVerb() {
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify(
                        "WITH VipStats AS (\n"
                                + "  SELECT u.user_id, u.user_name, SUM(o.total_amount) AS total_spent\n"
                                + "  FROM users u JOIN orders o ON u.user_id = o.user_id\n"
                                + "  WHERE u.status = 'VIP' AND o.order_date >= '2026-01-01'\n"
                                + "  GROUP BY u.user_id, u.user_name)\n"
                                + "INSERT INTO vip_summary (user_id, user_name, total_spent)\n"
                                + "SELECT user_id, user_name, total_spent FROM VipStats"));
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify(
                        "WITH DuplicateRows AS (\n"
                                + "  SELECT id, email, ROW_NUMBER() OVER (PARTITION BY email ORDER BY id ASC) as row_num\n"
                                + "  FROM users)\n"
                                + "DELETE FROM DuplicateRows WHERE row_num > 1"));
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify(
                        "WITH TargetUsers AS (\n"
                                + "  SELECT u.user_id FROM users u JOIN orders o ON u.user_id = o.user_id\n"
                                + "  WHERE o.order_date >= '2026-01-01'\n"
                                + "  GROUP BY u.user_id HAVING SUM(o.total_amount) >= 1000000)\n"
                                + "UPDATE users SET status = 'VIP'\n"
                                + "WHERE user_id IN (SELECT user_id FROM TargetUsers)"));
    }

    /** FOR UPDATE [OF …] per CUBRID SELECT syntax → WRITE. */
    @Test
    public void assertSelectForUpdateOfClassifiedAsWrite() {
        assertEquals(
                SqlClassification.WRITE, classifier.classify("SELECT * FROM t FOR UPDATE OF t"));
    }

    @Test
    public void assertExplainSelectCaseInsensitiveClassifiedAsUnknown() {
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("explain select * from t"));
    }

    @Test
    public void assertWritePrefixesCaseInsensitive() {
        assertEquals(SqlClassification.WRITE, classifier.classify("insert into t values (1)"));
        assertEquals(SqlClassification.WRITE, classifier.classify("update t set c = 1"));
        assertEquals(SqlClassification.WRITE, classifier.classify("delete from t where id = 1"));
    }

    @Test
    public void assertExplainNonSelectClassifiedAsUnknown() {
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("EXPLAIN DELETE FROM t"));
    }

    /**
     * CUBRID LOB is master-local (not replicated to slaves). SELECT touching LOB functions must not
     * be classified as READ for RO routing — UNKNOWN → master (see Router REASON_UNKNOWN_TO_RW).
     * Functions per <a href="https://www.cubrid.org/manual/ko/11.4/sql/function/lob_fn.html">LOB
     * functions</a>.
     */
    @Test
    public void assertSelectWithCubridLobFunctionsClassifiedAsUnknown() {
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify(
                        "SELECT CAST(BLOB_FROM_FILE('file:/tmp/x') AS BIT VARYING) FROM db_root"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify(
                        "SELECT CLOB_TO_CHAR(doc) FROM t WHERE id = 1")); // CLOB_TO_CHAR per manual
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT BLOB_LENGTH(b), CLOB_LENGTH(c) FROM lob_tbl"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify(
                        "SELECT * FROM t WHERE id IN (SELECT BLOB_LENGTH(x) FROM u)")); // subquery
    }

    /**
     * CUBRID {@code LAST_INSERT_ID()} {@code ROW_COUNT()} return the session's last DML state and
     * must execute on the same CAS as the preceding write (the LB-JDBC RW endpoint). SELECTs
     * referencing them are therefore UNKNOWN so the Router default sends them to RW. See <a
     * href="https://www.cubrid.org/manual/ko/11.4/sql/function/information_fn.html#LAST_INSERT_ID">information
     * functions</a>.
     */
    @Test
    public void assertSelectWithWriteStateFunctionClassifiedAsUnknown() {
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("SELECT LAST_INSERT_ID()"));
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("SELECT ROW_COUNT()"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT id, LAST_INSERT_ID() FROM tbl"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify(
                        "SELECT a FROM t WHERE b = (SELECT LAST_INSERT_ID() FROM dual)"));
    }

    @Test
    public void assertSelectWithWriteStateFunctionCaseInsensitive() {
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("select last_insert_id()"));
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("SELECT Row_Count()"));
    }

    /**
     * Counter-mutating built-ins {@code INCR()}/{@code DECR()} write a column value, so a {@code
     * SELECT} invoking them is classified WRITE. Routing is RW either way; the WRITE label keeps
     * the classification consistent.
     */
    @Test
    public void assertSelectWithCounterWriteFunctionClassifiedAsWrite() {
        assertEquals(SqlClassification.WRITE, classifier.classify("SELECT INCR(counter_col)"));
        assertEquals(SqlClassification.WRITE, classifier.classify("SELECT INCR ( counter_col )"));
        assertEquals(SqlClassification.WRITE, classifier.classify("SELECT DECR (  )"));
        assertEquals(SqlClassification.WRITE, classifier.classify("SELECT DECR (counter_col )"));
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify("SELECT INCR(hit) FROM board WHERE id=1"));
    }

    @Test
    public void assertSelectWithCounterWriteFunctionCaseInsensitive() {
        assertEquals(SqlClassification.WRITE, classifier.classify("select incr(counter_col)"));
        assertEquals(SqlClassification.WRITE, classifier.classify("SELECT DeCr(counter_col)"));
    }

    @Test
    public void assertColumnNameContainingWriteStateFunctionWordDoesNotMatchFunctionCall() {
        assertEquals(SqlClassification.READ, classifier.classify("SELECT x_incr_y FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT x_decr_y FROM t"));
        assertEquals(
                SqlClassification.READ, classifier.classify("SELECT my_last_insert_id_val FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT row_count_flag FROM t"));
    }

    @Test
    public void assertUnsafeSelectKeywordInsideIdentifierClassifiedAsRead() {
        assertEquals(
                SqlClassification.READ, classifier.classify("SELECT my_last_insert_id_val FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT pre_update_flag FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT drop_rate FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT update_t FROM t"));
    }

    @Test
    public void assertUnsafeKeywordInsideStringLiteralClassifiedAsRead() {
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT * FROM t WHERE ddl = 'DROP TABLE X'"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT * FROM t WHERE note = 'INSERT INTO X'"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT * FROM t WHERE note = 'CREATE USER foo'"));
        assertEquals(
                SqlClassification.READ, classifier.classify("SELECT 'CALL proc()' AS hint FROM t"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT 'GRANT SELECT ON t TO u' FROM dual"));
    }

    @Test
    public void assertForUpdatePhraseInsideStringLiteralClassifiedAsRead() {
        assertEquals(
                SqlClassification.READ, classifier.classify("SELECT 'FOR UPDATE' AS tag FROM t"));
    }

    @Test
    public void assertIntoInsideStringLiteralClassifiedAsRead() {
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT 'copy INTO archive' AS note FROM t"));
    }

    @Test
    public void assertUnsafeKeywordInsideCommentClassifiedAsRead() {
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT /* UPDATE t SET c=1 */ * FROM t"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT * FROM t -- DROP TABLE X\n WHERE id=1"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT * FROM t // audit_log()\n WHERE id=1"));
    }

    @Test
    public void assertFunctionNameInsideStringLiteralClassifiedAsRead() {
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT 'INCR(counter_col)' AS sample FROM t"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT 'BLOB_FROM_FILE(x)' AS sample FROM t"));
    }

    @Test
    public void assertUnsafeKeywordInsideQuotedIdentifierClassifiedAsRead() {
        assertEquals(SqlClassification.READ, classifier.classify("SELECT \"update\" FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT \"drop\" AS c FROM t"));
    }

    @Test
    public void assertForUpdatePhraseWithCommentBetweenClassifiedAsWrite() {
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify("SELECT * FROM t FOR /* lock */ UPDATE"));
    }

    /**
     * Stored procedures and user-defined routines invoked as {@code ident(...)} inside {@code
     * SELECT} are not on the CUBRID 11.4 read-only built-in whitelist → UNKNOWN → RW routing.
     */
    @Test
    public void assertSelectWithStoredOrUserFunctionClassifiedAsUnknown() {
        assertEquals(SqlClassification.UNKNOWN, classifier.classify("SELECT Hello() FROM db_root"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT count_medals('USA') FROM db_root"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT pkg.my_routine(1) FROM db_root"));
    }

    @Test
    public void assertSelectWithReadOnlyBuiltinFunctionClassifiedAsRead() {
        assertEquals(SqlClassification.READ, classifier.classify("SELECT COUNT(*) FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT AVG(price) FROM items"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT NVL(c, 0) FROM t"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT INSERT('abc', 1, 1, 'xyz') FROM db_root"));
        assertEquals(
                SqlClassification.READ,
                classifier.classify("SELECT ROW_NUMBER() OVER (ORDER BY id) FROM t"));
    }

    @Test
    public void assertStoredFunctionNameInsideStringLiteralClassifiedAsRead() {
        assertEquals(SqlClassification.READ, classifier.classify("SELECT 'Hello()' FROM db_root"));
    }

    /**
     * Serial increment — pseudo-column {@code seq.NEXTVAL} (and spaced/{@code NEXT_VALUE} variants)
     * and function form {@code SERIAL_NEXT_VALUE(...)} advance the serial (write) → WRITE → RW.
     * Must not leak to a slave/replica.
     */
    @Test
    public void assertSelectWithSerialNextValClassifiedAsWrite() {
        assertEquals(
                SqlClassification.WRITE, classifier.classify("SELECT seq.NEXTVAL FROM db_root"));
        assertEquals(
                SqlClassification.WRITE, classifier.classify("SELECT seq.NEXT_VALUE FROM db_root"));
        assertEquals(
                SqlClassification.WRITE, classifier.classify("SELECT seq . NEXTVAL FROM db_root"));
        assertEquals(
                SqlClassification.WRITE, classifier.classify("select seq.nextval from db_root"));
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify("SELECT SERIAL_NEXT_VALUE(seq, 1) FROM db_root"));
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify("select serial_next_value(seq, 1) from db_root"));
    }

    /**
     * Serial {@code seq.CURRVAL} / {@code SERIAL_CURRENT_VALUE(...)} depend on the session's last
     * NEXTVAL → UNKNOWN → RW (same CAS), not a slave/replica.
     */
    @Test
    public void assertSelectWithSerialCurrValClassifiedAsUnknown() {
        assertEquals(
                SqlClassification.UNKNOWN, classifier.classify("SELECT seq.CURRVAL FROM db_root"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT seq.CURRENT_VALUE FROM db_root"));
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT SERIAL_CURRENT_VALUE(seq, 1) FROM db_root"));
    }

    /**
     * Serial pseudo-column detection is dot-qualified, so a bare column named like a pseudo-column
     * is not a false positive — plain SELECT stays READ.
     */
    @Test
    public void assertBareSerialPseudoColumnWithoutDotClassifiedAsRead() {
        assertEquals(SqlClassification.READ, classifier.classify("SELECT nextval FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT currval, x FROM t"));
    }

    /**
     * 05 §M2: a dot-qualified serial pseudo-column written with a quoted identifier — ANSI {@code
     * "NEXTVAL"}, CUBRID {@code [NEXTVAL]}, or backtick {@code `NEXTVAL`} — is still a serial
     * increment (WRITE) and must not leak to a slave/replica. Before the fix the quoted region was
     * skipped and the increment went undetected (classified READ).
     */
    @Test
    public void assertDotQualifiedQuotedNextValClassifiedAsWrite() {
        assertEquals(
                SqlClassification.WRITE, classifier.classify("SELECT s.\"NEXTVAL\" FROM db_root"));
        assertEquals(
                SqlClassification.WRITE, classifier.classify("SELECT s.[NEXTVAL] FROM db_root"));
        assertEquals(
                SqlClassification.WRITE, classifier.classify("SELECT s.`NEXTVAL` FROM db_root"));
        assertEquals(
                SqlClassification.WRITE,
                classifier.classify("SELECT s . \"nextval\" FROM db_root"));
    }

    /** 05 §M2: dot-qualified quoted CURRVAL is session-dependent → UNKNOWN → RW (same CAS). */
    @Test
    public void assertDotQualifiedQuotedCurrValClassifiedAsUnknown() {
        assertEquals(
                SqlClassification.UNKNOWN,
                classifier.classify("SELECT s.\"CURRVAL\" FROM db_root"));
        assertEquals(
                SqlClassification.UNKNOWN, classifier.classify("SELECT s.[CURRVAL] FROM db_root"));
    }

    /**
     * 05 §M2: an unsafe keyword used as a bracket/backtick-quoted identifier is a plain column
     * reference, not the keyword — must stay READ (mirrors the double-quoted-identifier case).
     */
    @Test
    public void assertUnsafeKeywordInsideBracketOrBacktickIdentifierClassifiedAsRead() {
        assertEquals(SqlClassification.READ, classifier.classify("SELECT [update] FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT `update` FROM t"));
        assertEquals(SqlClassification.READ, classifier.classify("SELECT [drop] AS c FROM t"));
    }
}
