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

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * Extracts LB routing hints from a SQL string.
 *
 * <p>LB hints are recognized only when {@code /*+ ... * /} immediately follows the top-level {@code
 * SELECT} keyword. Two top-level shapes are supported:
 *
 * <ul>
 *   <li>Plain {@code SELECT ...}
 *   <li>{@code WITH [RECURSIVE] <cte_list> SELECT ...} — only the SELECT at paren depth 0 after the
 *       CTE list is treated as the top-level SELECT. Hints inside CTE bodies are ignored.
 * </ul>
 *
 * <p>If the top-level statement after {@code WITH} is a write (INSERT/UPDATE/DELETE/MERGE/
 * REPLACE/CREATE/ALTER/DROP/TRUNCATE), no LB hint is extracted.
 *
 * <p>Canonical hint tokens and accepted legacy aliases:
 *
 * <ul>
 *   <li>{@code TO_RW} ({@code TO_MASTER} alias)
 *   <li>{@code TO_RO} ({@code TO_SLAVE} alias)
 * </ul>
 *
 * <p>{@code AFTER_WRITE_TO_RW}, {@code STICKY_TO_RW} and {@code UNSTICK_FROM_RW} (and their {@code
 * *_MASTER} aliases) were removed in 2026-08. They are now unrecognized tokens and are skipped like
 * any other.
 *
 * <p>Duplicate and conflict rules:
 *
 * <ul>
 *   <li>Duplicate target hints: first-wins (matches CUBRID's duplicate-LEADING rule)
 *   <li>Unsupported tokens (optimizer hints etc.) are silently skipped
 *   <li>Syntax errors (unclosed comment) produce HintSet.empty()
 * </ul>
 *
 * <p>The parser never modifies the SQL string. It only reads it and returns a {@link HintSet}.
 */
public final class HintParser {
    private static final String HINT_OPEN = "/*+";
    private static final String HINT_CLOSE = "*/";

    // Canonical token + legacy alias → hint. Adding an alias is a one-line table edit.
    private static final Map<String, HintSet.TargetHint> TARGET_BY_TOKEN = buildTargetTokens();

    private static Map<String, HintSet.TargetHint> buildTargetTokens() {
        Map<String, HintSet.TargetHint> m = new HashMap<String, HintSet.TargetHint>();
        m.put("TO_RW", HintSet.TargetHint.TO_RW);
        m.put("TO_MASTER", HintSet.TargetHint.TO_RW);
        m.put("TO_RO", HintSet.TargetHint.TO_RO);
        m.put("TO_SLAVE", HintSet.TargetHint.TO_RO);
        return Collections.unmodifiableMap(m);
    }

    private HintParser() {}

    public static HintSet parse(String sql) {
        if (sql == null) {
            return HintSet.empty();
        }

        String hintBlock = extractHintBlock(sql);
        if (hintBlock == null) {
            return HintSet.empty();
        }

        return buildHintSet(hintBlock);
    }

    static String extractHintBlock(String sql) {
        // Skip leading whitespace and comments before matching the top-level SELECT/WITH, so an
        // ORM/proxy tag comment ("/* app-tag */ SELECT /*+ TO_RW */ ...") does not hide the LB
        // hint. Shares SqlLexer with KeywordSqlClassifier so both components lex the same way.
        String trimmed = sql.substring(SqlLexer.skipWhitespaceAndComments(sql, 0));

        int afterTopSelect = findAfterTopLevelSelect(trimmed);
        if (afterTopSelect < 0) {
            return null;
        }

        int len = trimmed.length();

        int pos = afterTopSelect;

        while (pos < len && Character.isWhitespace(trimmed.charAt(pos))) {
            pos++;
        }

        if (pos + HINT_OPEN.length() > len) {
            return null;
        }

        if (!trimmed.substring(pos, pos + HINT_OPEN.length()).equals(HINT_OPEN)) {
            return null;
        }

        int contentStart = pos + HINT_OPEN.length();

        int closeIdx = trimmed.indexOf(HINT_CLOSE, contentStart);
        if (closeIdx < 0) {
            return null;
        }

        return trimmed.substring(contentStart, closeIdx).trim();
    }

    static int findAfterTopLevelSelect(String trimmed) {
        if (matchesKeywordAt(trimmed, 0, "SELECT")) {
            return "SELECT".length();
        }

        if (matchesKeywordAt(trimmed, 0, "WITH")) {
            return scanForTopLevelSelect(trimmed, "WITH".length());
        }

        return -1;
    }

    private static int scanForTopLevelSelect(String sql, int startPos) {
        int len = sql.length();

        int i = startPos;

        int depth = 0;

        while (i < len) {
            // Skip comments and quoted strings/identifiers (including CUBRID [..] brackets) via the
            // shared lexer. An unterminated comment/quote runs to end of input, so the loop simply
            // finds no top-level SELECT and returns -1 below.
            final int afterSkip = SqlLexer.skipCommentOrQuoted(sql, i);
            if (afterSkip != i) {
                i = afterSkip;
                continue;
            }

            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
                i++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
            } else if (depth == 0 && isWordStart(c)) {
                if (matchesKeywordAt(sql, i, "SELECT")) {
                    return i + "SELECT".length();
                }

                if (isTopLevelNonSelectKeyword(sql, i)) {
                    return -1;
                }
                i = skipWord(sql, i);
            } else {
                i++;
            }
        }

        return -1;
    }

    private static boolean isTopLevelNonSelectKeyword(String sql, int i) {
        return matchesKeywordAt(sql, i, "INSERT")
                || matchesKeywordAt(sql, i, "UPDATE")
                || matchesKeywordAt(sql, i, "DELETE")
                || matchesKeywordAt(sql, i, "MERGE")
                || matchesKeywordAt(sql, i, "REPLACE")
                || matchesKeywordAt(sql, i, "CREATE")
                || matchesKeywordAt(sql, i, "ALTER")
                || matchesKeywordAt(sql, i, "DROP")
                || matchesKeywordAt(sql, i, "TRUNCATE");
    }

    private static boolean matchesKeywordAt(String sql, int pos, String keyword) {
        int kLen = keyword.length();
        if (pos < 0 || pos + kLen > sql.length()) {
            return false;
        }

        for (int j = 0; j < kLen; j++) {
            char a = sql.charAt(pos + j);

            char b = keyword.charAt(j);
            if (Character.toUpperCase(a) != b) {
                return false;
            }
        }

        if (pos > 0 && isWordChar(sql.charAt(pos - 1))) {
            return false;
        }

        if (pos + kLen < sql.length() && isWordChar(sql.charAt(pos + kLen))) {
            return false;
        }

        return true;
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int skipWord(String sql, int start) {
        int i = start;

        int len = sql.length();

        while (i < len && isWordChar(sql.charAt(i))) {
            i++;
        }

        return i == start ? start + 1 : i;
    }

    static HintSet buildHintSet(String hintContent) {
        HintSet.TargetHint target = null;

        StringTokenizer st = new StringTokenizer(hintContent, " \t\r\n,");

        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim().toUpperCase(Locale.ROOT);
            if (token.length() == 0) {
                continue;
            }

            // Duplicates: first-wins, the way CUBRID resolves a duplicate LEADING hint.
            if (target == null && TARGET_BY_TOKEN.containsKey(token)) {
                target = TARGET_BY_TOKEN.get(token);
            }

            // unsupported tokens (optimizer hints etc.) silently ignored
        }

        if (target == null) {
            return HintSet.empty();
        }

        return new HintSet(target);
    }
}
