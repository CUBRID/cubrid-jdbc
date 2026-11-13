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

import org.junit.Test;

/**
 * What survives masking and what must not. The purpose is that a routed statement can be written to
 * a log file without carrying application data with it, while still answering the routing question
 * — which tables, which clauses, and where the hint sits.
 */
public class SqlLiteralMaskerTest {

    private static String mask(final String sql) {
        return SqlLiteralMasker.mask(sql, 0);
    }

    @Test
    public void stringValuesAreReplacedShapeIsKept() {
        assertEquals(
                "SELECT * FROM member WHERE ssn = ? AND email = ?",
                mask("SELECT * FROM member WHERE ssn = '860101-1234567' AND email = 'kim@x.com'"));
    }

    @Test
    public void numbersAreReplacedButIdentifiersEndingInDigitsAreNot() {
        assertEquals(
                "SELECT col2 FROM t1 WHERE age > ? AND rate = ?",
                mask("SELECT col2 FROM t1 WHERE age > 30 AND rate = 1.5"));
    }

    @Test
    public void exponentAndDecimalFormsAreOneValueEach() {
        assertEquals("SELECT ? + ? FROM t", mask("SELECT 1.5e-3 + 42 FROM t"));
    }

    @Test
    public void aHintIsPreservedBecauseItIsWhatTheRecordExistsToShow() {
        assertEquals(
                "SELECT /*+ TO_RO */ count(*) FROM log WHERE id > ?",
                mask("SELECT /*+ TO_RO */ count(*) FROM log WHERE id > 100"));
    }

    /**
     * Hex, binary and leading-dot numbers are values too; a comment other than a hint can carry
     * one; an identifier is read as the classifier reads it, so its digits are not values.
     */
    @Test
    public void everyValueFormIsMaskedAndIdentifiersAreKept() {
        assertEquals(
                "SELECT * FROM t WHERE k = ?", mask("SELECT * FROM t WHERE k = 0xDEADBEEFCAFE"));
        assertEquals("SELECT * FROM t WHERE k = ?", mask("SELECT * FROM t WHERE k = 0b101010"));
        assertEquals("SELECT * FROM t WHERE amt = ?", mask("SELECT * FROM t WHERE amt = .75"));
        assertEquals(
                "SELECT * FROM t WHERE c = ? --?",
                mask("SELECT * FROM t WHERE c = 1 -- pw='hunter2'"));
        assertEquals(
                "SELECT /*?*/ /*+ TO_RO */ c FROM t",
                mask("SELECT /* card 4111111111111111 */ /*+ TO_RO */ c FROM t"));
        assertEquals("SELECT 이름1, #t2 FROM t", mask("SELECT 이름1, #t2 FROM t"));
    }

    /** m32: {@code \KRW5000} is a currency code and an amount; only the amount is a value. */
    @Test
    public void aCurrencyAmountIsMaskedAndItsCodeIsKept() {
        assertEquals(
                "UPDATE account SET balance = \\KRW? WHERE id = ?",
                mask("UPDATE account SET balance = \\KRW5000000 WHERE id = 7"));
        assertEquals(
                "INSERT INTO pay VALUES (?, \\USD?, -\\JPY?, \\TL?, \\EUR?)",
                mask("INSERT INTO pay VALUES (1, \\USD12345.67, -\\JPY777, \\TL300, \\EUR99.5e2)"));
        assertEquals(
                "SELECT \\KRW ?, \\?, $? FROM t", mask("SELECT \\KRW 5000, \\5000, $678 FROM t"));
        assertEquals("SELECT KRW5000, t.USD1 FROM t", mask("SELECT KRW5000, t.USD1 FROM t"));
    }

    /** Where a string ends depends on the server's no_backslash_escapes, so nothing is kept. */
    @Test
    public void aStringWithABackslashMasksTheWholeStatement() {
        assertEquals(
                "<masked>",
                mask("SELECT * FROM t WHERE a = 'O\\'Brien' AND b = 'Seoul-ssn-860101-1234567'"));
    }

    @Test
    public void quotedIdentifiersAreStructureAndStay() {
        // "name" and [order] name columns, not values.
        assertEquals(
                "SELECT \"name\", [order] FROM t WHERE \"name\" = ?",
                mask("SELECT \"name\", [order] FROM t WHERE \"name\" = 'kim'"));
    }

    @Test
    public void sqlLookingTextInsideAValueCannotLeakOut() {
        // The lexer treats the whole quoted run as one region, so the value is masked entire.
        String masked = mask("SELECT * FROM t WHERE c = 'DROP TABLE x WHERE ssn=1'");
        assertEquals("SELECT * FROM t WHERE c = ?", masked);
        assertEquals(-1, masked.indexOf("DROP"));
    }

    @Test
    public void anEscapedQuoteInsideAValueDoesNotEndTheValueEarly() {
        String masked = mask("SELECT * FROM t WHERE name = 'O''Brien' AND id = 7");
        assertEquals("SELECT * FROM t WHERE name = ? AND id = ?", masked);
        assertEquals(
                "no fragment of the value may survive: " + masked, -1, masked.indexOf("Brien"));
    }

    @Test
    public void multiLineStatementsCollapseToOneRecordLine() {
        String masked = mask("SELECT *\n  FROM member\n WHERE id = 3");
        assertEquals("SELECT * FROM member WHERE id = ?", masked);
        assertEquals(-1, masked.indexOf('\n'));
    }

    @Test
    public void theTextIsCappedAndMarkedWhenTruncated() {
        StringBuilder longSql = new StringBuilder("SELECT ");
        for (int i = 0; i < 50; i++) {
            longSql.append("column_").append(i).append(", ");
        }
        longSql.append("x FROM t");

        String masked = SqlLiteralMasker.mask(longSql.toString(), 60);
        assertTrue(masked.endsWith("..."));
        assertTrue("the cap plus the ellipsis: " + masked.length(), masked.length() <= 63);
    }

    @Test
    public void rawModeKeepsValuesButStillCollapsesAndCaps() {
        assertEquals(
                "SELECT * FROM t WHERE c = 'kim'",
                SqlLiteralMasker.raw("SELECT *\n FROM t\n WHERE c = 'kim'", 0));
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertEquals("", mask(null));
        assertEquals("", mask(""));
        assertEquals("", SqlLiteralMasker.raw(null, 100));
    }

    @Test
    public void anUnterminatedValueRunsToTheEndAndIsStillMasked() {
        // A malformed statement must not leave the tail exposed.
        String masked = mask("SELECT * FROM t WHERE c = 'unclosed 860101-1234567");
        assertEquals("SELECT * FROM t WHERE c = ?", masked);
        assertEquals(-1, masked.indexOf("860101"));
    }
}
