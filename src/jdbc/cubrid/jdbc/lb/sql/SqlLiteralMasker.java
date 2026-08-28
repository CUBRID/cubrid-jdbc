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
 * Replaces the <em>values</em> in a statement with {@code ?} while leaving its shape intact, so SQL
 * can be logged without carrying data with it.
 *
 * <p>A {@code PreparedStatement} already arrives with {@code ?} in place of its values, but {@code
 * Statement.executeQuery("... WHERE ssn = '860101-1234567'")} does not, and LB routes both. Masking
 * keeps what a routing question needs (which tables, which clauses, where the hint sits) and drops
 * what must not be kept in a log.
 *
 * <p>Quoted regions are found with {@link SqlLexer}, the scanner the classifier and the hint parser
 * also use, so the three cannot disagree about what counts as a literal. Comments are kept, because
 * a hint lives in one and a routing record must show it.
 *
 * <p>Side effect: two executions of the same statement with different values collapse to the same
 * string, so a log can be grouped by statement shape.
 */
public final class SqlLiteralMasker {
    private static final char MASK = '?';

    private SqlLiteralMasker() {}

    /**
     * Returns {@code sql} with string literals and numeric literals replaced by {@code ?},
     * collapsed to a single line, and truncated to {@code maxLen} characters.
     *
     * @param sql the statement text; {@code null} yields {@code ""}
     * @param maxLen cap on the returned length; {@code 0} or less means no cap
     * @return the masked, single-line, truncated statement
     */
    public static String mask(final String sql, final int maxLen) {
        return render(sql, maxLen, true);
    }

    /**
     * Returns {@code sql} unmasked, collapsed to a single line and truncated. For the opt-in raw
     * mode; the caller is responsible for having warned that values reach the log.
     *
     * @param sql the statement text; {@code null} yields {@code ""}
     * @param maxLen cap on the returned length; {@code 0} or less means no cap
     * @return the single-line, truncated statement
     */
    public static String raw(final String sql, final int maxLen) {
        return render(sql, maxLen, false);
    }

    private static String render(final String sql, final int maxLen, final boolean maskValues) {
        if (sql == null) {
            return "";
        }

        final int len = sql.length();
        final StringBuilder out = new StringBuilder(maxLen > 0 && maxLen < len ? maxLen + 4 : len);

        int i = 0;
        boolean lastWasSpace = false;

        while (i < len) {
            // Checked at the top so every branch below is covered. Testing it only after appending
            // a plain character let a masked value or a quoted region carry the buffer past the
            // cap, and stopping exactly at the cap produced a truncated record with no ellipsis.
            // stopping exactly at the cap produced a truncated record with no ellipsis on it.
            if (maxLen > 0 && out.length() >= maxLen) {
                break;
            }

            final int afterQuoted = SqlLexer.skipCommentOrQuoted(sql, i);
            if (afterQuoted != i) {
                // A comment or a quoted region. Comments and quoted *identifiers* are structure and
                // are kept; a single-quoted string is a value and is masked.
                if (maskValues && sql.charAt(i) == '\'') {
                    out.append(MASK);
                } else {
                    appendCollapsed(out, sql, i, afterQuoted);
                }
                lastWasSpace = false;
                i = afterQuoted;
                continue;
            }

            final char c = sql.charAt(i);

            if (maskValues && isNumberStart(sql, i)) {
                out.append(MASK);
                i = scanNumberEnd(sql, i);
                lastWasSpace = false;
                continue;
            }

            if (Character.isWhitespace(c)) {
                // Newlines and runs of blanks would break the one-record-per-line shape.
                if (!lastWasSpace) {
                    out.append(' ');
                    lastWasSpace = true;
                }
                i++;
                continue;
            }

            out.append(c);
            lastWasSpace = false;
            i++;
        }

        return finish(out, maxLen, i < len);
    }

    /**
     * Trims the trailing space a collapsed run may leave, enforces the cap, and marks the text when
     * anything was cut: an unmarked truncation reads as a complete statement.
     */
    private static String finish(
            final StringBuilder out, final int maxLen, final boolean truncated) {
        while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
        }
        if (maxLen > 0 && out.length() > maxLen) {
            out.setLength(maxLen);
        }
        if (truncated) {
            out.append("...");
        }
        return out.toString();
    }

    private static void appendCollapsed(
            final StringBuilder out, final String sql, final int start, final int end) {
        boolean lastWasSpace = false;
        for (int p = start; p < end; p++) {
            final char c = sql.charAt(p);
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    out.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            out.append(c);
            lastWasSpace = false;
        }
    }

    /**
     * Whether a numeric literal starts at {@code pos}. A digit that continues an identifier ({@code
     * col2}, {@code t1.c}) is not a literal, so the preceding character decides.
     */
    private static boolean isNumberStart(final String sql, final int pos) {
        if (!isDigit(sql.charAt(pos))) {
            return false;
        }
        if (pos == 0) {
            return true;
        }
        final char prev = sql.charAt(pos - 1);
        return !isIdentifierPart(prev) && prev != '.';
    }

    private static int scanNumberEnd(final String sql, final int pos) {
        final int len = sql.length();
        int p = pos;
        while (p < len && (isDigit(sql.charAt(p)) || sql.charAt(p) == '.')) {
            p++;
        }
        // exponent form: 1.5e-3
        if (p < len && (sql.charAt(p) == 'e' || sql.charAt(p) == 'E')) {
            int q = p + 1;
            if (q < len && (sql.charAt(q) == '+' || sql.charAt(q) == '-')) {
                q++;
            }
            if (q < len && isDigit(sql.charAt(q))) {
                p = q;
                while (p < len && isDigit(sql.charAt(p))) {
                    p++;
                }
            }
        }
        return p;
    }

    private static boolean isDigit(final char c) {
        return '0' <= c && c <= '9';
    }

    private static boolean isIdentifierPart(final char c) {
        return ('a' <= c && c <= 'z')
                || ('A' <= c && c <= 'Z')
                || ('0' <= c && c <= '9')
                || c == '_';
    }
}
