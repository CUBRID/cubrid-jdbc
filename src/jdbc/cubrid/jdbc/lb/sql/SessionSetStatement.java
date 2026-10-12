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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a SQL {@code SET} statement means for a load-balanced session.
 *
 * <p>A {@code SET} changes the server session it runs on, and the RW and read legs are two
 * sessions. Session-scoped settings ({@code SET NAMES}, {@code SET TIMEZONE}, session system
 * parameters) are {@link Action#REPLAY replayed} on the read leg so reads see what the application
 * set. A parameter outside that list may be server- or CAS-wide, so it is not copied to another
 * node; the session's reads {@link Action#PIN_TO_RW stay on RW} instead.
 */
public final class SessionSetStatement {

    /** What LB does after the statement succeeded on the RW leg. */
    public enum Action {
        /** Not a session setting LB tracks. */
        NONE,
        /** Re-run {@link #getReplay()} on the read leg and on every leg opened later. */
        REPLAY,
        /** Keep this session's reads on RW until the connection closes. */
        PIN_TO_RW
    }

    /**
     * Replay key shared by {@code SET TIMEZONE} and {@code SET SYSTEM PARAMETERS 'timezone=..'}.
     */
    static final String TIMEZONE_KEY = "PARAM:TIMEZONE";

    static final String NAMES_KEY = "NAMES";

    /** Replay key of a SQL-set isolation level, which a later JDBC setter replaces. */
    public static final String ISOLATION_LEVEL_KEY = "PARAM:ISOLATION_LEVEL";

    /** Replay key of a SQL-set lock timeout, which a later JDBC setter replaces. */
    public static final String LOCK_TIMEOUT_KEY = "PARAM:LOCK_TIMEOUT";

    private static final String OPTIMIZATION_LEVEL_KEY = "PARAM:OPTIMIZATION_LEVEL";

    /**
     * Session parameters (PRM_FOR_SESSION | PRM_USER_CHANGE in the engine's system_parameter.c)
     * that change what a query parses, plans or returns, or how it waits.
     */
    private static final Set<String> REPLAYED_PARAMS =
            names(
                    "add_column_update_hard_default",
                    "allow_truncated_string",
                    "alter_table_change_type_strict",
                    "auto_increment_cache_size",
                    "block_ddl_statement",
                    "block_nowhere_statement",
                    "compat_primary_key",
                    "create_table_reuseoid",
                    "cte_max_recursions",
                    "default_week_format",
                    "index_scan_in_oid_order",
                    "intl_collation",
                    "intl_date_lang",
                    "intl_number_lang",
                    "isolation_level",
                    "lock_timeout",
                    "lock_timeout_in_secs",
                    "max_hash_list_scan_size",
                    "memoize_memory_limit",
                    "multi_range_optimization_limit",
                    "no_backslash_escapes",
                    "only_full_group_by",
                    "optimization_level",
                    "pl_transaction_control",
                    "print_index_detail",
                    "query_trace_format",
                    "regexp_engine",
                    "return_null_on_function_errors",
                    "sort_limit_max_count",
                    "stored_procedure_dump_icode",
                    "string_max_size_bytes",
                    "timezone",
                    "update_use_attribute_references");

    /**
     * Parameters that change nothing a read returns: an XASL debug dump, how INSERT runs, plan
     * choice, index builds, csql and connection settings. They are not copied to the read leg -
     * several live in the CAS process and would outlast the session there - and need no pin.
     */
    private static final Set<String> IGNORED_PARAMS =
            names(
                    "xasl_debug_dump",
                    "insert_execution_mode",
                    "like_term_selectivity",
                    "optimizer_enable_merge_join",
                    "deduplicate_key_level",
                    "csql_auto_commit",
                    "csql_history_num",
                    "csql_single_line_mode",
                    "connection_timeout",
                    "check_peer_alive",
                    "db_hosts",
                    "commit_on_shutdown",
                    "communication_histogram",
                    "garbage_collection");

    /**
     * Query tracing pins instead of replaying: {@code SHOW TRACE} reports the last query of the
     * session it runs on, so the traced query and the {@code SHOW TRACE} must share a leg.
     */
    private static final String QUERY_TRACE = "query_trace";

    private static final Set<String> FALSE_WORDS = names("no", "n", "0", "false", "off");

    private static final SessionSetStatement NONE_RESULT =
            new SessionSetStatement(Action.NONE, Collections.<String, String>emptyMap());
    private static final SessionSetStatement PIN_RESULT =
            new SessionSetStatement(Action.PIN_TO_RW, Collections.<String, String>emptyMap());

    private final Action action;
    private final Map<String, String> replay;

    private SessionSetStatement(final Action action, final Map<String, String> replay) {
        this.action = action;
        this.replay = replay;
    }

    public Action getAction() {
        return action;
    }

    /**
     * Statements to run on another leg, keyed by the setting they change so a later {@code SET} of
     * the same setting replaces an earlier one. Empty unless {@link Action#REPLAY}.
     *
     * @return key to statement, in statement order
     */
    public Map<String, String> getReplay() {
        return replay;
    }

    /**
     * Classifies {@code sql}. A {@code SET} inside a multi-statement string pins, since the string
     * cannot be replayed without its other statements.
     *
     * @param sql the statement text as the application sent it
     * @return the action for it, never {@code null}
     */
    public static SessionSetStatement parse(final String sql) {
        if (sql == null) {
            return NONE_RESULT;
        }

        final int first = SqlLexer.skipWhitespaceAndComments(sql, 0);
        if (sql.indexOf(';') < 0) {
            return startsWithSet(sql, first) ? parseSingle(sql.substring(first)) : NONE_RESULT;
        }

        SessionSetStatement only = null;
        int statements = 0;
        int start = 0;
        int i = 0;
        final int len = sql.length();
        while (i <= len) {
            if (i < len) {
                final int after = SqlLexer.skipCommentOrQuoted(sql, i);
                if (after != i) {
                    i = after;
                    continue;
                }
                if (sql.charAt(i) != ';') {
                    i++;
                    continue;
                }
            }
            final String part = sql.substring(start, i);
            final int body = SqlLexer.skipWhitespaceAndComments(part, 0);
            if (body < part.length()) {
                statements++;
                final SessionSetStatement parsed =
                        startsWithSet(part, body) ? parseSingle(part.substring(body)) : NONE_RESULT;
                if (only == null || parsed.action != Action.NONE) {
                    only = parsed;
                }
            }
            start = i + 1;
            i++;
        }

        if (only == null || only.action == Action.NONE) {
            return NONE_RESULT;
        }
        return statements == 1 ? only : PIN_RESULT;
    }

    private static boolean startsWithSet(final String sql, final int pos) {
        return "SET".equals(wordAt(sql, pos));
    }

    /** {@code stmt} starts with SET and holds a single statement (no top-level ';'). */
    private static SessionSetStatement parseSingle(final String stmt) {
        int pos = skipWord(stmt, 0);
        final String target = wordAt(stmt, pos);
        final String text = stmt.trim();

        if ("NAMES".equals(target)) {
            return replayable(text) ? replay(NAMES_KEY, text) : PIN_RESULT;
        }
        if ("TIMEZONE".equals(target)) {
            return replayable(text) ? replay(TIMEZONE_KEY, text) : PIN_RESULT;
        }
        if ("TIME".equals(target)) {
            pos = skipWord(stmt, pos);
            if ("ZONE".equals(wordAt(stmt, pos))) {
                return replayable(text) ? replay(TIMEZONE_KEY, text) : PIN_RESULT;
            }
            return NONE_RESULT;
        }
        if ("SYSTEM".equals(target)) {
            pos = skipWord(stmt, pos);
            if ("PARAMETERS".equals(wordAt(stmt, pos))) {
                return parseParameters(stmt, skipWord(stmt, pos));
            }
        }
        if ("OPTIMIZATION".equals(target)) {
            // SET OPTIMIZATION LEVEL n sets optimization_level; levels 2/258/514 skip execution.
            final boolean level = "LEVEL".equals(wordAt(stmt, skipWord(stmt, pos)));
            return level && replayable(text) ? replay(OPTIMIZATION_LEVEL_KEY, text) : PIN_RESULT;
        }
        if ("TRACE".equals(target)) {
            // SET TRACE ON sets query_trace=y.
            return "OFF".equals(wordAt(stmt, skipWord(stmt, pos))) ? NONE_RESULT : PIN_RESULT;
        }
        if ("TRANSACTION".equals(target)) {
            return parseTransaction(text);
        }
        // SET @var, SET TRIGGER ...: not tracked.
        return NONE_RESULT;
    }

    /**
     * {@code SET TRANSACTION ISOLATION LEVEL ...} or {@code SET TRANSACTION LOCK TIMEOUT ...}. A
     * statement setting both pins, since one replay key cannot hold it.
     */
    private static SessionSetStatement parseTransaction(final String text) {
        final String upper = text.toUpperCase(Locale.ROOT);
        final boolean isolation = upper.contains("ISOLATION");
        final boolean lockTimeout = upper.contains("TIMEOUT");
        if (!isolation && !lockTimeout) {
            return NONE_RESULT;
        }
        if ((isolation && lockTimeout) || !replayable(text)) {
            return PIN_RESULT;
        }
        return replay(isolation ? ISOLATION_LEVEL_KEY : LOCK_TIMEOUT_KEY, text);
    }

    /** {@code SET SYSTEM PARAMETERS 'name=value[; name=value...]'} from {@code pos}. */
    private static SessionSetStatement parseParameters(final String stmt, final int pos) {
        final int open = SqlLexer.skipWhitespaceAndComments(stmt, pos);
        if (open >= stmt.length() || stmt.charAt(open) != '\'') {
            return PIN_RESULT;
        }
        final int close = SqlLexer.skipCommentOrQuoted(stmt, open);
        if (stmt.charAt(close - 1) != '\'' || close - open < 2) {
            return PIN_RESULT;
        }
        if (SqlLexer.skipWhitespaceAndComments(stmt, close) < stmt.length()) {
            return PIN_RESULT;
        }

        final String list = stmt.substring(open + 1, close - 1).replace("''", "'");
        final Map<String, String> replay = new LinkedHashMap<String, String>();
        for (final String assignment : list.split(";")) {
            final String trimmed = assignment.trim();
            if (trimmed.length() == 0) {
                continue;
            }
            final int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                return PIN_RESULT;
            }
            final String name = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            final String value = trimmed.substring(eq + 1).trim();
            if (QUERY_TRACE.equals(name)) {
                if (FALSE_WORDS.contains(value.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                return PIN_RESULT;
            }
            if (IGNORED_PARAMS.contains(name)) {
                continue;
            }
            if (!REPLAYED_PARAMS.contains(name)) {
                return PIN_RESULT;
            }
            replay.put(
                    replayKey(name),
                    "SET SYSTEM PARAMETERS '" + name + "=" + value.replace("'", "''") + "'");
        }

        return replay.isEmpty()
                ? NONE_RESULT
                : new SessionSetStatement(Action.REPLAY, Collections.unmodifiableMap(replay));
    }

    /**
     * The deprecated {@code lock_timeout_in_secs} sets the same timeout as {@code lock_timeout}.
     */
    private static String replayKey(final String name) {
        return "lock_timeout_in_secs".equals(name)
                ? LOCK_TIMEOUT_KEY
                : "PARAM:" + name.toUpperCase(Locale.ROOT);
    }

    /** A statement with a bound {@code ?} cannot be replayed from its text alone. */
    private static boolean replayable(final String stmt) {
        int i = 0;
        while (i < stmt.length()) {
            final int after = SqlLexer.skipCommentOrQuoted(stmt, i);
            if (after != i) {
                i = after;
                continue;
            }
            if (stmt.charAt(i) == '?') {
                return false;
            }
            i++;
        }
        return true;
    }

    private static SessionSetStatement replay(final String key, final String stmt) {
        String text = stmt;
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return new SessionSetStatement(Action.REPLAY, Collections.singletonMap(key, text));
    }

    /** Upper-cased ASCII word starting at {@code pos} after whitespace/comments, or "". */
    private static String wordAt(final String sql, final int pos) {
        final int start = SqlLexer.skipWhitespaceAndComments(sql, pos);
        int end = start;
        while (end < sql.length() && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        return sql.substring(start, end).toUpperCase(Locale.ROOT);
    }

    /** Position just after the word {@link #wordAt} reads at {@code pos}. */
    private static int skipWord(final String sql, final int pos) {
        int end = SqlLexer.skipWhitespaceAndComments(sql, pos);
        while (end < sql.length() && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        return end;
    }

    private static Set<String> names(final String... names) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(names)));
    }
}
