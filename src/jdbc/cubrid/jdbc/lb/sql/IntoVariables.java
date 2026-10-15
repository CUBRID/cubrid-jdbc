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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Variables an {@code INTO v} / {@code TO v} clause assigns, and whether a statement names one.
 *
 * <p>{@code SELECT ... INTO v}, {@code INSERT ... INTO v} (the new row's OID), {@code CALL f() INTO
 * v}, {@code EVALUATE e INTO v} and {@code GET ... INTO v} create a variable in the server session
 * of the connection that ran them, so a later read that names it ({@code SELECT v}, {@code
 * TABLE(v)}) must run on that connection. Names are compared upper-cased, as CUBRID compares
 * identifiers.
 */
public final class IntoVariables {

    /** Leading verbs whose statement can end in an {@code INTO}/{@code TO} variable clause. */
    private static final Set<String> ASSIGNING =
            new HashSet<String>(
                    Arrays.asList(
                            "SELECT", "WITH", "INSERT", "REPLACE", "CALL", "EVALUATE", "GET"));

    private IntoVariables() {}

    /**
     * Names the top-level {@code INTO}/{@code TO} clause of each statement in {@code sql} assigns.
     * The {@code INTO} right after a leading {@code INSERT}/{@code REPLACE} names the table, not a
     * variable.
     *
     * @param sql the statement text as the application sent it
     * @return upper-cased names without a leading {@code :}; empty when there are none
     */
    public static Set<String> assignedBy(final String sql) {
        if (sql == null) {
            return Collections.emptySet();
        }

        Set<String> names = null;
        final int len = sql.length();
        int depth = 0;
        int words = 0;
        boolean assigning = false;
        boolean insert = false;
        // 0: not in a list, 1: a name is expected (after INTO/TO or ','), 2: after a name
        int list = 0;
        int i = 0;
        while (i < len) {
            final char c = sql.charAt(i);
            final int after = SqlLexer.skipCommentOrQuoted(sql, i);
            if (after != i) {
                if (KeywordSqlClassifier.isQuotedIdentifierStart(c) && depth == 0 && list == 1) {
                    names = add(names, sql.substring(i + 1, after - 1));
                    list = 2;
                } else if (c == '\'' || KeywordSqlClassifier.isQuotedIdentifierStart(c)) {
                    list = 0;
                }
                i = after;
                continue;
            }

            if (KeywordSqlClassifier.isIdentifierStart(c)) {
                final int end = KeywordSqlClassifier.scanIdentifierEnd(sql, i);
                if (depth == 0) {
                    final String word = sql.substring(i, end).toUpperCase(Locale.ROOT);
                    if (words++ == 0) {
                        assigning = ASSIGNING.contains(word);
                        insert = "INSERT".equals(word) || "REPLACE".equals(word);
                    }
                    if (list == 1) {
                        names = add(names, word);
                        list = 2;
                    } else {
                        final boolean into = "INTO".equals(word) || "TO".equals(word);
                        list = assigning && into && !(insert && words == 2) ? 1 : 0;
                    }
                }
                i = end;
                continue;
            }

            if (c == ';') {
                depth = 0;
                words = 0;
                assigning = false;
                insert = false;
                list = 0;
            } else if (c == '(') {
                depth++;
                list = 0;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
                list = 0;
            } else if (c == ',' && list == 2) {
                list = 1;
            } else if (!(c == ':' && list == 1) && !Character.isWhitespace(c)) {
                list = 0;
            }
            i++;
        }

        return names == null ? Collections.<String>emptySet() : names;
    }

    /**
     * Names {@code DROP VARIABLE v[, ...]} removes. {@code DEALLOCATE/DROP VARIABLE @v} drops a
     * session variable, a namespace an {@code INTO/TO} clause never writes, so it names none.
     *
     * @param sql the statement text as the application sent it
     * @return upper-cased names; empty for any other statement
     */
    public static Set<String> droppedBy(final String sql) {
        if (sql == null) {
            return Collections.emptySet();
        }

        Set<String> names = null;
        final int len = sql.length();
        String first = null;
        int words = 0;
        boolean drop = false;
        boolean sessionVariable = false;
        int i = 0;
        while (i < len) {
            final char c = sql.charAt(i);
            final int after = SqlLexer.skipCommentOrQuoted(sql, i);
            if (after != i) {
                if (drop && KeywordSqlClassifier.isQuotedIdentifierStart(c)) {
                    if (!sessionVariable) {
                        names = add(names, sql.substring(i + 1, after - 1));
                    }
                    sessionVariable = false;
                }
                i = after;
                continue;
            }

            if (KeywordSqlClassifier.isIdentifierStart(c)) {
                final int end = KeywordSqlClassifier.scanIdentifierEnd(sql, i);
                final String word = sql.substring(i, end).toUpperCase(Locale.ROOT);
                if (++words == 1) {
                    first = word;
                } else if (words == 2) {
                    drop = "DROP".equals(first) && "VARIABLE".equals(word);
                } else if (drop) {
                    if (!sessionVariable) {
                        names = add(names, word);
                    }
                    sessionVariable = false;
                }
                i = end;
                continue;
            }

            if (c == ';') {
                first = null;
                words = 0;
                drop = false;
            } else if (c == '@') {
                sessionVariable = true;
            }
            i++;
        }

        return names == null ? Collections.<String>emptySet() : names;
    }

    /**
     * Whether {@code sql} names one of {@code upperNames} as an identifier. Literals and comments
     * do not count, nor does a name after {@code .}, which is a column of a table.
     *
     * @param sql the statement text
     * @param upperNames upper-cased variable names
     * @return whether any of them appears
     */
    public static boolean isReferencedIn(final String sql, final Set<String> upperNames) {
        if (sql == null || upperNames.isEmpty()) {
            return false;
        }

        final int len = sql.length();
        boolean afterDot = false;
        int i = 0;
        while (i < len) {
            final char c = sql.charAt(i);
            final int after = SqlLexer.skipCommentOrQuoted(sql, i);
            if (after != i) {
                if (KeywordSqlClassifier.isQuotedIdentifierStart(c)) {
                    final String name = sql.substring(i + 1, after - 1).toUpperCase(Locale.ROOT);
                    if (!afterDot && upperNames.contains(name)) {
                        return true;
                    }
                    afterDot = false;
                } else if (c == '\'') {
                    afterDot = false;
                }
                i = after;
                continue;
            }

            if (KeywordSqlClassifier.isIdentifierStart(c)) {
                final int end = KeywordSqlClassifier.scanIdentifierEnd(sql, i);
                final String name = sql.substring(i, end).toUpperCase(Locale.ROOT);
                if (!afterDot && upperNames.contains(name)) {
                    return true;
                }
                afterDot = false;
                i = end;
                continue;
            }

            if (c == '.') {
                afterDot = true;
            } else if (!Character.isWhitespace(c)) {
                afterDot = false;
            }
            i++;
        }

        return false;
    }

    private static Set<String> add(final Set<String> names, final String name) {
        final Set<String> out = names == null ? new HashSet<String>() : names;
        out.add(name.toUpperCase(Locale.ROOT));
        return out;
    }
}
