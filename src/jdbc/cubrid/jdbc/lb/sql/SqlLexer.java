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

/**
 * Shared single-pass lexer for the minimal SQL lexical structure routing decisions depend on.
 *
 * <p>Both {@link KeywordSqlClassifier} (which scans the whole statement) and {@link HintParser}
 * (which locates the top-level {@code SELECT}) must skip the same comment and quoted regions, so a
 * keyword or hint hidden in a string, identifier, or comment is treated the same way. One
 * implementation keeps the two from drifting apart on quoting rules.
 *
 * <p>Recognized regions:
 *
 * <ul>
 *   <li>line comment {@code -- ...} or {@code // ...} (CUBRID supports both) to end of line
 *   <li>block comment {@code /* ... *}{@code /}
 *   <li>single-quoted string literal {@code '...'} with {@code ''} escape
 *   <li>double-quoted identifier {@code "..."} with {@code ""} escape
 *   <li>CUBRID bracket-quoted identifier {@code [...]} (no escape)
 *   <li>backtick-quoted identifier {@code `...`} (no escape)
 * </ul>
 *
 * <p>An unterminated region runs to the end of the input. A caller that treats an unterminated
 * comment as a syntax error sees the same end position and simply finds no further tokens.
 */
final class SqlLexer {
    private SqlLexer() {}

    /**
     * If a comment or quoted region begins at {@code pos}, returns the index one past its end;
     * otherwise returns {@code pos} unchanged.
     *
     * @param sql SQL text being scanned
     * @param pos index to test
     * @return index one past the skipped region, or {@code pos} if none begins there
     */
    static int skipCommentOrQuoted(final String sql, final int pos) {
        final int afterComment = skipComment(sql, pos);
        if (afterComment != pos) {
            return afterComment;
        }

        return skipQuoted(sql, pos);
    }

    /**
     * {@code sql} as a server with {@code no_backslash_escapes=no} reads it, rewritten so the
     * default scan reads it the same way: each backslash escape inside a quoted string becomes two
     * plain characters. This scanner cannot know that setting, so a caller classifies both.
     *
     * @param sql the statement text
     * @return the rewritten text, or {@code null} if a string does not close in that reading, which
     *     such a server rejects as a syntax error
     */
    static String withBackslashEscapes(final String sql) {
        final char[] text = sql.toCharArray();
        final int len = text.length;
        int i = 0;
        while (i < len) {
            final char quote = text[i];
            if (quote != '\'' && quote != '"') {
                final int after = skipCommentOrQuoted(sql, i);
                i = after == i ? i + 1 : after;
                continue;
            }
            int p = i + 1;
            while (true) {
                if (p >= len) {
                    return null;
                }
                if (text[p] == '\\' && p + 1 < len) {
                    text[p] = '_';
                    text[p + 1] = '_';
                    p += 2;
                } else if (text[p] == quote && p + 1 < len && text[p + 1] == quote) {
                    p += 2;
                } else if (text[p++] == quote) {
                    break;
                }
            }
            i = p;
        }

        return new String(text);
    }

    /**
     * Advances past leading whitespace and comments from {@code pos}. Quoted regions are not
     * skipped: a string or identifier is where a statement token begins.
     *
     * @param sql SQL text being scanned
     * @param pos index to start from
     * @return index of the first character that is neither whitespace nor part of a comment
     */
    static int skipWhitespaceAndComments(final String sql, int pos) {
        final int len = sql.length();

        while (pos < len) {
            if (Character.isWhitespace(sql.charAt(pos))) {
                pos++;
                continue;
            }

            final int afterComment = skipComment(sql, pos);
            if (afterComment != pos) {
                pos = afterComment;
                continue;
            }

            break;
        }

        return pos;
    }

    /**
     * End of a line/block comment beginning at {@code pos}, or {@code pos} if none begins there.
     */
    private static int skipComment(final String sql, final int pos) {
        final int len = sql.length();
        if (pos + 1 >= len) {
            return pos;
        }

        final char c = sql.charAt(pos);
        // line comment: -- or // (CUBRID supports both). The second char equals the first, so /* is
        // not swallowed here and falls to the block-comment branch below.
        if ((c == '-' || c == '/') && sql.charAt(pos + 1) == c) {
            int p = pos + 2;
            while (p < len && sql.charAt(p) != '\n' && sql.charAt(p) != '\r') {
                p++;
            }
            return p;
        }

        if (c == '/' && sql.charAt(pos + 1) == '*') {
            int p = pos + 2;
            while (p + 1 < len && !(sql.charAt(p) == '*' && sql.charAt(p + 1) == '/')) {
                p++;
            }
            if (p + 1 >= len) {
                return len;
            }
            return p + 2;
        }

        return pos;
    }

    /** End of a quoted string/identifier beginning at {@code pos}, or {@code pos} if none. */
    private static int skipQuoted(final String sql, final int pos) {
        final int len = sql.length();
        if (pos >= len) {
            return pos;
        }

        final char c = sql.charAt(pos);

        // single-quoted string ('' escape) and double-quoted identifier ("" escape).
        if (c == '\'' || c == '"') {
            int p = pos + 1;
            while (p < len) {
                if (sql.charAt(p) == c) {
                    if (p + 1 < len && sql.charAt(p + 1) == c) {
                        p += 2;
                        continue;
                    }
                    return p + 1;
                }
                p++;
            }
            return len;
        }

        // CUBRID bracket-quoted identifier [..] and MySQL-compat backtick-quoted identifier `..`.
        // Neither uses delimiter-doubling as an escape.
        if (c == '[' || c == '`') {
            final char close = c == '[' ? ']' : '`';
            int p = pos + 1;
            while (p < len && sql.charAt(p) != close) {
                p++;
            }
            return p < len ? p + 1 : len;
        }

        return pos;
    }
}
