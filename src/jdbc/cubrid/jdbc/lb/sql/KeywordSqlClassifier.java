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
import java.util.Map;

/**
 * Keyword-based conservative SQL classifier.
 *
 * <p>Classification is a single pass over the statement driven by the shared {@link SqlLexer}, so
 * every keyword/function/phrase check skips comment and quoted regions — a literal such as {@code
 * WHERE c = 'DROP TABLE X'} does not force the statement off RO by false positive.
 *
 * <p>Reads deliberately classified away from RO:
 *
 * <ul>
 *   <li>LOB functions — CUBRID LOB values are not replicated to RO brokers, so these reads must use
 *       the RW endpoint. See <a
 *       href="https://www.cubrid.org/manual/ko/11.4/sql/function/lob_fn.html">LOB functions</a>.
 *   <li>session-state functions {@code LAST_INSERT_ID()} / {@code ROW_COUNT()} - they report the
 *       session's last DML state, so they only mean something on the CAS that ran that write.
 *       {@link SqlClassification#UNKNOWN} sends them down the {@code Router}'s default path to the
 *       RW endpoint, which is that same CAS (one RW endpoint per {@code LoadBalanceConnection}).
 *       See <a
 *       href="https://www.cubrid.org/manual/ko/11.4/sql/function/information_fn.html#LAST_INSERT_ID">information
 *       functions</a>.
 *   <li>{@code ident(...)} calls whose name is not a CUBRID 11.4 read-only built-in ({@link
 *       BuiltinFunctionCatalog}) — stored procedures, Java SP and user-defined routines route to RW
 *       conservatively.
 * </ul>
 *
 * <p>An explicit {@code TO_RO} hint still wins, because the {@code Router} resolves hints before it
 * consults the classifier.
 */
public final class KeywordSqlClassifier implements SqlClassifier {
    private static final int CAT_NONE = 0;
    private static final int CAT_UNSAFE = 1;
    private static final int CAT_UPDATE = 2;
    private static final int CAT_FOR = 3;
    private static final Map<String, Integer> KEYWORDS;
    private static final Map<String, SqlClassification> LEADING_WRITE_KEYWORDS;

    static {
        final Map<String, Integer> kw = new HashMap<String, Integer>(64);

        // UPDATE is CAT_UPDATE (only "FOR UPDATE" makes it a write); the other DDL/DML keywords are
        // CAT_UNSAFE when they appear inside a SELECT body.
        kw.put("UPDATE", CAT_UPDATE);
        kw.put("DELETE", CAT_UNSAFE);
        kw.put("MERGE", CAT_UNSAFE);
        kw.put("REPLACE", CAT_UNSAFE);
        kw.put("CALL", CAT_UNSAFE);
        kw.put("EXECUTE", CAT_UNSAFE);
        kw.put("TRUNCATE", CAT_UNSAFE);
        kw.put("ALTER", CAT_UNSAFE);
        kw.put("DROP", CAT_UNSAFE);
        kw.put("CREATE", CAT_UNSAFE);
        kw.put("GRANT", CAT_UNSAFE);
        kw.put("REVOKE", CAT_UNSAFE);
        kw.put("INTO", CAT_UNSAFE);
        // CUBRID allows INTO-less INSERT ("INSERT tbl VALUES ..."/"INSERT tbl SET ..."), so a
        // trailing write in a multi-statement SELECT cannot rely on INTO detection. Registering
        // INSERT as CAT_UNSAFE catches it; the CAT_UNSAFE branch's isFunctionCallAt && isRoSafeName
        // exemption still lets the read-only string built-in INSERT(...) stay READ.
        kw.put("INSERT", CAT_UNSAFE);
        kw.put("FOR", CAT_FOR);

        // RW built-ins (serial / session-state / LOB) and user routines are NOT registered here;
        // they fall to CAT_NONE → the default branch routes any non-RO-safe ident(...) call to RW.
        // See BuiltinFunctionCatalog (RW_BUILTINS / ALL_BUILTINS).
        KEYWORDS = Collections.unmodifiableMap(kw);

        final Map<String, SqlClassification> write = new HashMap<String, SqlClassification>(16);
        write.put("INSERT", SqlClassification.WRITE);
        write.put("UPDATE", SqlClassification.WRITE);
        write.put("DELETE", SqlClassification.WRITE);
        write.put("MERGE", SqlClassification.WRITE);
        // REPLACE INTO is an upsert (write); a leading EXECUTE runs a prepared statement of unknown
        // effect -> conservatively WRITE (RW). Both only match in leading position; the string
        // built-in REPLACE(...) inside a SELECT is handled by the CAT_UNSAFE RO-safe exemption.
        write.put("REPLACE", SqlClassification.WRITE);
        write.put("EXECUTE", SqlClassification.WRITE);
        write.put("CREATE", SqlClassification.WRITE);
        write.put("ALTER", SqlClassification.WRITE);
        write.put("DROP", SqlClassification.WRITE);
        write.put("TRUNCATE", SqlClassification.WRITE);
        write.put("GRANT", SqlClassification.WRITE);
        write.put("REVOKE", SqlClassification.WRITE);
        // Stored procedure / routine call: body side effects are not statically known, so a
        // leading CALL (or JDBC escape {call ...}, normalized in normalizeLeading) is WRITE -> RW.
        write.put("CALL", SqlClassification.WRITE);
        LEADING_WRITE_KEYWORDS = Collections.unmodifiableMap(write);
    }

    @Override
    public SqlClassification classify(final String sql) {
        final String normalized = normalizeLeading(sql);
        if (normalized.length() == 0) {
            return SqlClassification.UNKNOWN;
        }

        final String first = scanLeadingKeyword(normalized);
        if ("SELECT".equals(first)) {
            return classifySelect(normalized);
        }

        if ("WITH".equals(first)) {
            return classifyCte(normalized);
        }

        final SqlClassification classified = LEADING_WRITE_KEYWORDS.get(first);

        return classified != null ? classified : SqlClassification.UNKNOWN;
    }

    /**
     * Classifies a CTE-led statement ({@code WITH [RECURSIVE] cte AS (...) [, ...] <stmt>}) by its
     * <b>top-level verb</b>, not by the CTE body: the SELECT/DML inside {@code AS (...)} is a
     * subquery and is skipped (spec 8.2). {@code WITH ... SELECT} goes through {@link
     * #classifySelect}, so a serial NEXTVAL or write built-in in the main statement still forces
     * WRITE; {@code WITH ... INSERT/UPDATE/DELETE/MERGE} is WRITE; an unrecognized verb is UNKNOWN
     * (safe side, RW).
     */
    private static SqlClassification classifyCte(final String sql) {
        final int verbPos = findCteTopLevelVerb(sql);
        if (verbPos < 0) {
            return SqlClassification.UNKNOWN;
        }

        final String verb = extractUpperAscii(sql, verbPos, scanIdentifierEnd(sql, verbPos));
        if ("SELECT".equals(verb)) {
            return classifySelect(sql.substring(verbPos));
        }

        final SqlClassification classified = LEADING_WRITE_KEYWORDS.get(verb);
        return classified != null ? classified : SqlClassification.UNKNOWN;
    }

    /**
     * Index of the top-level statement verb after the CTE definition list, or {@code -1} if there
     * is none. Scans at parenthesis depth 0 (CTE bodies and column lists sit deeper), skipping
     * comments and quoted regions via {@link SqlLexer}. CTE names, {@code RECURSIVE} and {@code AS}
     * are stepped over; the first depth-0 identifier that is {@code SELECT} or a leading-write
     * keyword is the verb, since a CTE name can never be a reserved verb.
     */
    private static int findCteTopLevelVerb(final String sql) {
        final int len = sql.length();
        int i = "WITH".length();
        int depth = 0;

        while (i < len) {
            final int after = SqlLexer.skipCommentOrQuoted(sql, i);
            if (after != i) {
                i = after;
                continue;
            }

            final char c = sql.charAt(i);
            if (c == '(') {
                depth++;
                i++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
            } else if (depth == 0 && isIdentifierStart(c)) {
                final int end = scanIdentifierEnd(sql, i);
                final String name = extractUpperAscii(sql, i, end);
                if ("SELECT".equals(name) || LEADING_WRITE_KEYWORDS.containsKey(name)) {
                    return i;
                }
                i = end;
            } else {
                i++;
            }
        }

        return -1;
    }

    private static String scanLeadingKeyword(final String normalized) {
        if (normalized.length() == 0 || !isIdentifierStart(normalized.charAt(0))) {
            return "";
        }

        final int end = scanIdentifierEnd(normalized, 0);

        return extractUpperAscii(normalized, 0, end);
    }

    private static SqlClassification classifySelect(final String sql) {
        final int len = sql.length();

        int i = 0;

        boolean prevWasFor = false;
        boolean prevWasDot = false;

        boolean sawWrite = false;
        boolean sawUnsafe = false;

        while (i < len) {
            // Dot-qualified quoted identifier: seq."NEXTVAL" / seq.[NEXTVAL] / seq.`NEXTVAL`. These
            // reference the serial pseudo-column just like the unquoted seq.NEXTVAL, so extract the
            // inner name and apply the same write/session check. Must run BEFORE the lexer skip,
            // which would otherwise swallow the quoted region and miss the serial increment.
            if (prevWasDot && isQuotedIdentifierStart(sql.charAt(i))) {
                final int qend = SqlLexer.skipCommentOrQuoted(sql, i);
                final int effect =
                        serialPseudoColumnEffect(extractQuotedIdentifierUpper(sql, i, qend));
                if (effect == SERIAL_WRITE) {
                    sawWrite = true;
                } else if (effect == SERIAL_UNSAFE) {
                    sawUnsafe = true;
                }
                prevWasFor = false;
                prevWasDot = false;
                i = qend;
                continue;
            }

            final int after = SqlLexer.skipCommentOrQuoted(sql, i);
            if (after != i) {
                i = after;
                continue;
            }

            final char c = sql.charAt(i);
            if (isIdentifierStart(c)) {
                final int end = scanIdentifierEnd(sql, i);
                final String name = extractUpperAscii(sql, i, end);

                // serial pseudo-column seq.NEXTVAL / .CURRVAL (no parens) — write/session → RW.
                if (prevWasDot) {
                    final int serial = serialPseudoColumnEffect(name);
                    if (serial != SERIAL_NONE) {
                        if (serial == SERIAL_WRITE) {
                            sawWrite = true;
                        } else {
                            sawUnsafe = true;
                        }
                        prevWasFor = false;
                        prevWasDot = false;
                        i = end;
                        continue;
                    }
                }

                final Integer catObj = KEYWORDS.get(name);
                final int cat = catObj == null ? CAT_NONE : catObj.intValue();

                switch (cat) {
                    case CAT_UPDATE:
                        if (prevWasFor) {
                            return SqlClassification.WRITE;
                        }
                        sawUnsafe = true;
                        prevWasFor = false;
                        break;
                    case CAT_UNSAFE:
                        if (!(isFunctionCallAt(sql, end) && isRoSafeName(name))) {
                            sawUnsafe = true;
                        }
                        prevWasFor = false;
                        break;
                    case CAT_FOR:
                        prevWasFor = true;
                        break;
                    default:
                        if (isFunctionCallAt(sql, end) && !isRoSafeName(name)) {
                            // Non-RO-safe ident(...): RW built-in or user routine.
                            // Write-effect built-ins (SERIAL_NEXT_VALUE/INCR/DECR) → WRITE;
                            // session-dependent built-ins / LOB / user routines → UNKNOWN.
                            if (BuiltinFunctionCatalog.isWriteEffectBuiltin(name)) {
                                sawWrite = true;
                            } else {
                                sawUnsafe = true;
                            }
                        }
                        prevWasFor = false;
                        break;
                }
                prevWasDot = false;
                i = end;
                continue;
            }

            if (c == '.') {
                prevWasDot = true;
            } else if (!Character.isWhitespace(c)) {
                if (c == SESSION_VARIABLE_SIGIL) {
                    sawUnsafe = true;
                }
                prevWasFor = false;
                prevWasDot = false;
            }
            i++;
        }

        if (sawWrite) {
            return SqlClassification.WRITE;
        }

        return sawUnsafe ? SqlClassification.UNKNOWN : SqlClassification.READ;
    }

    /**
     * Marks a user-defined session variable reference ({@code @name}).
     *
     * <p>A session variable lives on the one physical connection that ran the {@code SET}, so a
     * read endpoint cannot serve a statement that reads it: the server answers {@code Session
     * variable '@name' not defined}. Classifying such a SELECT {@code UNKNOWN} sends it to the
     * write endpoint, where the {@code SET} went, so an application using session variables keeps
     * working.
     *
     * <p>Detection is a single character, because {@code @} has no other role in CUBRID SQL and
     * cannot start an unquoted identifier. It cannot misfire inside a string, a comment, or a
     * quoted identifier: the scan loop consults {@link SqlLexer#skip} before reaching this branch.
     *
     * <p>What this cannot fix: a failover that rebinds the write leg loses the variable, and the
     * next read of it fails again. Routing decides which leg answers, not what that leg still
     * holds.
     */
    private static final char SESSION_VARIABLE_SIGIL = '@';

    private static final int SERIAL_NONE = 0;
    private static final int SERIAL_WRITE = 1;
    private static final int SERIAL_UNSAFE = 2;

    /**
     * Effect of a dot-qualified serial pseudo-column reference ({@code seq.NEXTVAL} / {@code
     * seq."NEXTVAL"}): {@link #SERIAL_WRITE} for the increment form, {@link #SERIAL_UNSAFE} for the
     * session-dependent form, {@link #SERIAL_NONE} when {@code upperName} is not one.
     */
    private static int serialPseudoColumnEffect(final String upperName) {
        if (!BuiltinFunctionCatalog.isSerialPseudoColumn(upperName)) {
            return SERIAL_NONE;
        }

        return BuiltinFunctionCatalog.isSerialIncrementPseudoColumn(upperName)
                ? SERIAL_WRITE
                : SERIAL_UNSAFE;
    }

    /**
     * RO-safe name for an {@code ident(...)} call: a read-only built-in, or a SQL-syntax keyword
     * (subquery / derived-table / quantified comparison / window), which must not be taken for a
     * user routine. RW built-ins such as {@code SERIAL_NEXT_VALUE} are excluded on purpose, so they
     * fall through to the write path and route to RW.
     *
     * <p>{@code name} arrives uppercased and the caller has already confirmed the {@code (}.
     */
    private static boolean isRoSafeName(final String name) {
        return BuiltinFunctionCatalog.isReadOnlyBuiltin(name)
                || BuiltinFunctionCatalog.isSqlSyntax(name);
    }

    private static String extractUpperAscii(final String s, final int start, final int end) {
        final int len = end - start;
        if (len == 0) {
            return "";
        }

        final char[] buf = new char[len];

        for (int i = 0; i < len; i++) {
            char c = s.charAt(start + i);
            if ('a' <= c && c <= 'z') {
                c = (char) (c - 32);
            }
            buf[i] = c;
        }

        return new String(buf);
    }

    private static boolean isFunctionCallAt(final String sql, final int afterIdentifier) {
        final int paren = skipWhitespaceAndSkippable(sql, afterIdentifier);

        return paren < sql.length() && sql.charAt(paren) == '(';
    }

    private static int skipWhitespaceAndSkippable(final String sql, int pos) {
        final int len = sql.length();

        while (pos < len) {
            final int after = SqlLexer.skipCommentOrQuoted(sql, pos);
            if (after != pos) {
                pos = after;
                continue;
            }

            if (Character.isWhitespace(sql.charAt(pos))) {
                pos++;
                continue;
            }
            break;
        }

        return pos;
    }

    private static int scanIdentifierEnd(final String sql, final int pos) {
        final int len = sql.length();

        int p = pos + 1;

        while (p < len && isIdentifierPart(sql.charAt(p))) {
            p++;
        }

        return p;
    }

    private static boolean isIdentifierStart(final char ch) {
        return ('A' <= ch && ch <= 'Z') || ('a' <= ch && ch <= 'z') || ch == '_';
    }

    /** Opening delimiter of a quoted identifier: ANSI {@code "}, CUBRID {@code [}, backtick. */
    private static boolean isQuotedIdentifierStart(final char ch) {
        return ch == '"' || ch == '[' || ch == '`';
    }

    /**
     * Inner name of the quoted identifier spanning {@code [start, end)} (start at the opening
     * delimiter, end one past the closing delimiter), uppercased ASCII. Collapses {@code ""}
     * escapes for the double-quote form; brackets/backticks have no escape in this scanner.
     */
    private static String extractQuotedIdentifierUpper(
            final String sql, final int start, final int end) {
        if (end - start < 2) {
            return "";
        }
        final char delim = sql.charAt(start);
        final int contentEnd = end - 1; // exclusive: points at the closing delimiter
        final StringBuilder sb = new StringBuilder(contentEnd - start);
        int p = start + 1;
        while (p < contentEnd) {
            char ch = sql.charAt(p);
            if (delim == '"' && ch == '"' && p + 1 < contentEnd && sql.charAt(p + 1) == '"') {
                sb.append('"');
                p += 2;
                continue;
            }
            if ('a' <= ch && ch <= 'z') {
                ch = (char) (ch - 32);
            }
            sb.append(ch);
            p++;
        }
        return sb.toString();
    }

    private static boolean isIdentifierPart(final char ch) {
        return isIdentifierStart(ch) || ('0' <= ch && ch <= '9');
    }

    private static String normalizeLeading(final String sql) {
        if (sql == null) {
            return "";
        }

        final int pos = SqlLexer.skipWhitespaceAndComments(sql, 0);

        final String normalized = sql.substring(pos).trim();

        // JDBC stored-procedure escape: strip a leading '{' (e.g. {call proc(...)}) so the leading
        // keyword scanner sees CALL. The trailing '}' is irrelevant to leading-keyword
        // classification.
        if (normalized.length() > 0 && normalized.charAt(0) == '{') {
            return normalized.substring(1).trim();
        }

        return normalized;
    }
}
