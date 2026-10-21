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

import org.junit.Test;

/**
 * Unit tests for the shared comment/quote scanner used by both {@link KeywordSqlClassifier} and
 * {@link HintParser}. Each {@code skip} case starts at position 0 and asserts the index the lexer
 * advances to (one past the region), so the two consumers can rely on identical lexing.
 */
public class SqlLexerTest {

    @Test
    public void assertPlainTokenNotSkipped() {
        assertEquals(0, SqlLexer.skipCommentOrQuoted("SELECT", 0));
    }

    @Test
    public void assertWhitespaceNotSkippedBySkip() {
        // skipCommentOrQuoted() handles comments/quotes only; whitespace is left to the caller.
        assertEquals(0, SqlLexer.skipCommentOrQuoted("  x", 0));
    }

    @Test
    public void assertLineCommentDashSkippedToEol() {
        // stops at the newline (index 4); the newline itself is left for the caller.
        assertEquals(4, SqlLexer.skipCommentOrQuoted("-- c\nAFTER", 0));
    }

    @Test
    public void assertLineCommentSlashSlashSkippedToEol() {
        assertEquals(4, SqlLexer.skipCommentOrQuoted("// c\nAFTER", 0));
    }

    @Test
    public void assertLineCommentStopsAtCarriageReturn() {
        assertEquals(4, SqlLexer.skipCommentOrQuoted("-- x\rY", 0));
    }

    @Test
    public void assertBlockCommentSkipped() {
        // "/* c */" spans indices 0..6, so the region ends at 7 (' ' before x).
        assertEquals(7, SqlLexer.skipCommentOrQuoted("/* c */ x", 0));
    }

    @Test
    public void assertUnterminatedBlockCommentSkipsToEnd() {
        String s = "/* never closed";
        assertEquals(s.length(), SqlLexer.skipCommentOrQuoted(s, 0));
    }

    @Test
    public void assertSlashStarNotConfusedWithSlashSlash() {
        // '/' followed by '*' is a block comment, not a // line comment.
        assertEquals(5, SqlLexer.skipCommentOrQuoted("/*a*/z", 0));
    }

    @Test
    public void assertSingleQuotedStringSkipped() {
        assertEquals(5, SqlLexer.skipCommentOrQuoted("'abc'X", 0));
    }

    @Test
    public void assertSingleQuoteDoublingEscape() {
        // 'a''b' is one literal containing a'b.
        assertEquals(6, SqlLexer.skipCommentOrQuoted("'a''b'X", 0));
    }

    @Test
    public void assertDoubleQuotedIdentifierSkipped() {
        assertEquals(9, SqlLexer.skipCommentOrQuoted("\"NEXTVAL\"X", 0));
    }

    @Test
    public void assertDoubleQuoteDoublingEscape() {
        assertEquals(6, SqlLexer.skipCommentOrQuoted("\"a\"\"b\"X", 0));
    }

    @Test
    public void assertBracketQuotedIdentifierSkipped() {
        assertEquals(9, SqlLexer.skipCommentOrQuoted("[NEXTVAL]X", 0));
    }

    @Test
    public void assertBacktickQuotedIdentifierSkipped() {
        assertEquals(9, SqlLexer.skipCommentOrQuoted("`NEXTVAL`X", 0));
    }

    @Test
    public void assertUnterminatedQuoteSkipsToEnd() {
        String s = "'unclosed";
        assertEquals(s.length(), SqlLexer.skipCommentOrQuoted(s, 0));
    }

    @Test
    public void assertLeadingWhitespaceAndCommentsSkipped() {
        String s = "  /* a */\n-- b\n // c\n SELECT 1";
        int pos = SqlLexer.skipWhitespaceAndComments(s, 0);
        assertEquals(s.indexOf("SELECT"), pos);
    }

    @Test
    public void assertLeadingSkipStopsAtQuote() {
        // A leading string is where a token begins; it must not be skipped.
        String s = "  'x'";
        assertEquals(2, SqlLexer.skipWhitespaceAndComments(s, 0));
    }

    @Test
    public void assertLeadingUnterminatedCommentSkipsToEnd() {
        String s = "/* never closed SELECT";
        assertEquals(s.length(), SqlLexer.skipWhitespaceAndComments(s, 0));
    }
}
