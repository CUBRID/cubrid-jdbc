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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a SQL {@code SET} statement means for a load-balanced session.
 *
 * <p>A {@code SET} changes the server session it runs on, and the RW and read legs are two
 * sessions. Session-scoped settings ({@code SET NAMES}, {@code SET TIMEZONE}, session system
 * parameters) are {@link #getReplay() replayed} on the read leg so reads see what the application
 * set, and so are tracing and {@code CALL login(...)}. A change the read leg cannot be given keeps
 * the session's reads on RW for a {@link #getPins() reason} that a later statement may {@link
 * #getUnpins() release}, as turning statistics collection off does. A server- or CAS-wide parameter
 * is neither copied to another node nor pinned: it is {@link #getNonSessionParams() reported}.
 */
public final class SessionSetStatement {

    /** Pin reason of execution statistics collection, released by setting it to 0. */
    static final String EXEC_STATS_PIN = "EXEC_STATS";

    /** Replay key of {@code CALL login(...)}, which changes the session's user. */
    static final String LOGIN_KEY = "LOGIN";

    /**
     * Replay key shared by {@code SET TRACE} and {@code SET SYSTEM PARAMETERS 'query_trace=..'}.
     */
    static final String QUERY_TRACE_KEY = "PARAM:QUERY_TRACE";

    /** Pin reason no later statement releases; only closing the connection does. */
    public static final String UNKNOWN_PIN = "UNKNOWN";

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
     * Parameters that are session-scoped (PRM_FOR_SESSION in the engine's system_parameter.c) in
     * every version from 10.2 to develop that has them, and change what a query parses, plans or
     * returns, or how it waits. compat_numeric_division_scale is session-scoped in every release
     * (10.2 to 11.4); develop keeps the name but reads it nowhere.
     */
    private static final Set<String> REPLAYED_PARAMS =
            names(
                    "add_column_update_hard_default",
                    "allow_truncated_string",
                    "alter_table_change_type_strict",
                    "auto_increment_cache_size",
                    "block_ddl_statement",
                    "block_nowhere_statement",
                    "compat_numeric_division_scale",
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
                    "max_connect_by_dfs_size",
                    "memoize_memory_limit",
                    "multi_range_optimization_limit",
                    "no_backslash_escapes",
                    "only_full_group_by",
                    "optimization_level",
                    "pl_transaction_control",
                    "print_index_detail",
                    "query_trace",
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
     * max_hash_list_scan_size, the memory a hash scan may use, is server-wide in 11.0 to 11.3.
     */
    private static final Set<String> IGNORED_PARAMS =
            names(
                    "xasl_debug_dump",
                    "insert_execution_mode",
                    "like_term_selectivity",
                    "max_hash_list_scan_size",
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
     * Parameters that are server- or CAS-wide (user-changeable, not PRM_FOR_SESSION) in every
     * version from 10.2 to develop that has them. One changes the node it runs on for every
     * session, and HA does not replicate it, so read nodes keep their own value whatever LB does.
     */
    private static final Set<String> NON_SESSION_PARAMS =
            names(
                    "access_ip_control",
                    "access_ip_control_file",
                    "adaptive_flush_control",
                    "agg_hash_respect_order",
                    "async_commit",
                    "auto_restart_server",
                    "background_archiving",
                    "backup_sleep_msecs",
                    "call_stack_dump_activation_list",
                    "call_stack_dump_deactivation_list",
                    "call_stack_dump_on_error",
                    "checkpoint_every_npages",
                    "checkpoint_every_size",
                    "checkpoint_interval",
                    "checkpoint_interval_in_mins",
                    "checkpoint_sleep_msecs",
                    "cost_cpu_tuples_per_page",
                    "cost_effective_cache_pages",
                    "cost_heap_fetch_per_oid",
                    "cost_random_page",
                    "cost_seq_page",
                    "data_buffer_flush_ratio",
                    "data_buffer_neighbor_flush_nondirty",
                    "data_buffer_neighbor_flush_pages",
                    "data_buffer_sequential_victim_flush",
                    "data_file_os_advise",
                    "deadlock_detection_interval_in_secs",
                    "debug_autocommit",
                    "debug_external_storage",
                    "debug_heap_bestspace",
                    "debug_log_archives",
                    "debug_log_writer",
                    "debug_replication_data",
                    "default_histogram_bucket_count",
                    "detailed_checkpoint_logging",
                    "dl_fork",
                    "dont_reuse_heap_file",
                    "double_write_buffer_blocks",
                    "double_write_buffer_enable_flush_thread",
                    "double_write_buffer_logging",
                    "double_write_buffer_size",
                    "enable_lazy_predicate_read",
                    "er_btree_debug",
                    "er_log_debug",
                    "error_log_level",
                    "error_log_production_mode",
                    "error_log_size",
                    "error_log_warning",
                    "fault_injection_ids",
                    "force_remove_log_archives",
                    "generic_vol_prealloc_size",
                    "group_commit_interval_in_msecs",
                    "group_concat_max_len",
                    "ha_delay_limit",
                    "ha_delay_limit_delta",
                    "ha_log_applier_state",
                    "ha_node_list",
                    "ha_replica_list",
                    "hostvar_late_binding",
                    "hostvar_peeking",
                    "index_build_buffer_size",
                    "index_scan_oid_buffer_size",
                    "index_unfill_factor",
                    "inquire_on_exit",
                    "json_max_array_idx",
                    "load_flush_interval",
                    "loaddb_worker_count",
                    "lock_escalation",
                    "log_max_archives",
                    "log_pgbuf_victim_flush",
                    "log_trace_debug",
                    "log_trace_flush_time",
                    "max_bestspace_entries",
                    "max_flush_pages_per_second",
                    "max_flush_size_per_second",
                    "max_outer_card_of_idxjoin",
                    "max_recursion_sql_depth",
                    "max_request_concurrency",
                    "max_request_worker",
                    "max_subquery_cache_size",
                    "monitor_waiting_thread",
                    "no_logging",
                    "optimizer_reserve_01",
                    "optimizer_reserve_02",
                    "optimizer_reserve_03",
                    "optimizer_reserve_04",
                    "optimizer_reserve_05",
                    "optimizer_reserve_06",
                    "optimizer_reserve_07",
                    "optimizer_reserve_08",
                    "optimizer_reserve_09",
                    "optimizer_reserve_10",
                    "optimizer_reserve_11",
                    "optimizer_reserve_12",
                    "optimizer_reserve_13",
                    "optimizer_reserve_14",
                    "optimizer_reserve_15",
                    "optimizer_reserve_16",
                    "optimizer_reserve_17",
                    "optimizer_reserve_18",
                    "optimizer_reserve_19",
                    "optimizer_reserve_20",
                    "oracle_style_outerjoin",
                    "page_flush_interval",
                    "page_flush_interval_in_msecs",
                    "page_validation_level",
                    "plan_cache_bind_sensitivity",
                    "plan_cache_bind_variants",
                    "print_key_value_on_unique_error",
                    "print_object_as_oid",
                    "qo_dump",
                    "remove_log_archive_interval_in_secs",
                    "reset_tr_parser_interval",
                    "rollback_on_lock_escalation",
                    "shutdown_wait_time_in_secs",
                    "sort_buffer_size",
                    "sql_trace_execution_plan",
                    "sql_trace_ioread_pages",
                    "sql_trace_slow",
                    "sql_trace_slow_msecs",
                    "statistics_sample_pages",
                    "statistics_sampling_threshold_pages",
                    "stats_fullscan_max_pages",
                    "stop_on_error",
                    "suppress_fsync",
                    "sync_on_flush_size",
                    "sync_on_nflush",
                    "track_client_requests",
                    "unfill_factor",
                    "unload_ignore_error",
                    "unload_lock_timeout",
                    "update_statistics_update_histogram",
                    "use_btree_fence_key",
                    "use_orderby_sort_limit",
                    "use_stat_estimation",
                    "vacuum_master_interval_in_msecs",
                    "volume_extension_path",
                    "workspace_memory_report");

    /**
     * {@code SHOW EXEC STATISTICS} reports what its own session collected so far, and a session
     * collects while this variable is not 0: split over two legs the totals would differ from
     * core's, so collecting pins until it is turned off.
     */
    private static final String COLLECT_EXEC_STATS = "@collect_exec_stats";

    private static final SessionSetStatement NONE_RESULT = new Builder().build();

    private final Map<String, String> replay;
    private final Set<String> pins;
    private final Set<String> unpins;
    private final List<String> nonSessionParams;
    private final Set<String> pinsIfFailed;
    private final String boundKey;

    private SessionSetStatement(
            final Map<String, String> replay,
            final Set<String> pins,
            final Set<String> unpins,
            final List<String> nonSessionParams,
            final Set<String> pinsIfFailed,
            final String boundKey) {
        this.replay = replay;
        this.pins = pins;
        this.unpins = unpins;
        this.nonSessionParams = nonSessionParams;
        this.pinsIfFailed = pinsIfFailed;
        this.boundKey = boundKey;
    }

    /**
     * Statements to run on another leg, keyed by the setting they change so a later {@code SET} of
     * the same setting replaces an earlier one. A replay the read leg takes also releases a pin
     * under the same key.
     *
     * @return key to statement, in statement order
     */
    public Map<String, String> getReplay() {
        return replay;
    }

    /**
     * Reasons to keep the session's reads on RW: {@link #EXEC_STATS_PIN}, {@link #UNKNOWN_PIN}, or
     * the replay key of a setting given a bound value, the read leg having rejected it otherwise.
     *
     * @return the reasons, in statement order
     */
    public Set<String> getPins() {
        return pins;
    }

    /**
     * Reasons this statement made untrue: {@link #EXEC_STATS_PIN} after collection is turned off.
     *
     * @return the released reasons
     */
    public Set<String> getUnpins() {
        return unpins;
    }

    /**
     * Server- or CAS-wide parameters this statement changed on the node it ran on only.
     *
     * @return their lower-cased names
     */
    public List<String> getNonSessionParams() {
        return nonSessionParams;
    }

    /**
     * Reasons to keep reads on RW when this string fails. CUBRID keeps what ran before the failing
     * statement, and which statements those were is not known, so every setting a multi-statement
     * string touches is one. A single statement that fails changed nothing.
     *
     * @return the replay keys and reasons of a multi-statement string, otherwise none
     */
    public Set<String> getPinsIfFailed() {
        return pinsIfFailed;
    }

    /**
     * The replay key of a login or a lock timeout that is this whole statement and takes its values
     * from {@code ?}, the two such forms the server accepts. Run again with the values of the run
     * it is replayed like one written out; without them it {@link #getPins() pins} under this key.
     *
     * @return the key, or null for any other statement
     */
    public String getBoundKey() {
        return boundKey;
    }

    /**
     * Whether the statement changed anything LB tracks.
     *
     * @return whether it did
     */
    public boolean changesSession() {
        return !replay.isEmpty()
                || !pins.isEmpty()
                || !unpins.isEmpty()
                || !nonSessionParams.isEmpty();
    }

    /**
     * Whether a read leg lacking what {@code pinReasons} name compiles a statement differently from
     * RW. Statistics collection and a lock timeout change nothing a statement is compiled under;
     * any other reason may.
     *
     * @param pinReasons the reasons reads are pinned for
     * @return whether one of them changes how a statement compiles
     */
    public static boolean changesCompilation(final Collection<String> pinReasons) {
        for (String reason : pinReasons) {
            if (!EXEC_STATS_PIN.equals(reason) && !LOCK_TIMEOUT_KEY.equals(reason)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Classifies {@code sql}. Each statement of a multi-statement string is classified on its own,
     * and a later one wins over an earlier one on the same setting.
     *
     * @param sql the statement text as the application sent it
     * @return what it changed, never {@code null}
     */
    public static SessionSetStatement parse(final String sql) {
        final SessionSetStatement read = parseReading(sql);
        if (sql == null || sql.indexOf('\\') < 0) {
            return read;
        }
        // As the classifier reads it: no_backslash_escapes is the server's, so both ways.
        final String escaped = SqlLexer.withBackslashEscapes(sql);
        if (escaped == null || escaped.equals(sql)) {
            return read;
        }
        return new Builder().then(read).then(parseReading(escaped)).build();
    }

    private static SessionSetStatement parseReading(final String sql) {
        if (sql == null) {
            return NONE_RESULT;
        }

        if (sql.indexOf(';') < 0) {
            return parseStatement(sql, SqlLexer.skipWhitespaceAndComments(sql, 0));
        }

        final Builder all = new Builder();
        SessionSetStatement last = NONE_RESULT;
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
                last = parseStatement(part, body);
                all.then(last);
            }
            start = i + 1;
            i++;
        }
        if (statements == 1) {
            return last; // one statement and a ';' after it
        }
        all.severalStatements = statements > 1;
        return all.build();
    }

    /** One statement with no top-level ';', its first word at {@code body}. */
    private static SessionSetStatement parseStatement(final String stmt, final int body) {
        if (startsWithSet(stmt, body)) {
            return parseSingle(stmt.substring(body));
        }
        return callsLogin(stmt, body)
                ? replayOrPin(LOGIN_KEY, stmt.substring(body).trim())
                : parseExecStatistics(stmt, body, false);
    }

    private static boolean startsWithSet(final String sql, final int pos) {
        return "SET".equals(wordAt(sql, pos));
    }

    private static boolean callsLogin(final String sql, final int pos) {
        if (!"CALL".equals(wordAt(sql, pos))) {
            return false;
        }
        final int name = SqlLexer.skipWhitespaceAndComments(sql, skipWord(sql, pos));
        final String routine;
        final int nameEnd;
        if (name < sql.length() && KeywordSqlClassifier.isQuotedIdentifierStart(sql.charAt(name))) {
            nameEnd = SqlLexer.skipCommentOrQuoted(sql, name);
            routine = KeywordSqlClassifier.extractQuotedIdentifierUpper(sql, name, nameEnd);
        } else {
            nameEnd = skipWord(sql, name);
            routine = wordAt(sql, name);
        }
        final int after = SqlLexer.skipWhitespaceAndComments(sql, nameEnd);
        return "LOGIN".equals(routine) && after < sql.length() && sql.charAt(after) == '(';
    }

    /** {@code stmt} starts with SET and holds a single statement (no top-level ';'). */
    private static SessionSetStatement parseSingle(final String stmt) {
        int pos = skipWord(stmt, 0);
        final String target = wordAt(stmt, pos);
        final String text = stmt.trim();
        if ("NAMES".equals(target)) {
            return replayOrPin(NAMES_KEY, text);
        }
        if ("TIMEZONE".equals(target)) {
            return replayOrPin(TIMEZONE_KEY, text);
        }
        if ("TIME".equals(target)) {
            pos = skipWord(stmt, pos);
            if ("ZONE".equals(wordAt(stmt, pos))) {
                return replayOrPin(TIMEZONE_KEY, text);
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
            final String what = wordAt(stmt, skipWord(stmt, pos));
            if ("COST".equals(what)) {
                // The cost functions of the CAS process, for every later client of it; no result.
                return notOfTheSession("optimization cost");
            }
            // SET OPTIMIZATION LEVEL n sets optimization_level; levels 2/258/514 skip execution.
            return "LEVEL".equals(what)
                    ? replayOrPin(OPTIMIZATION_LEVEL_KEY, text)
                    : pin(UNKNOWN_PIN);
        }
        if ("TRACE".equals(target)) {
            // SET TRACE ON sets query_trace=y; SHOW TRACE then runs where the traced query ran.
            return replay(QUERY_TRACE_KEY, text);
        }
        if ("TRANSACTION".equals(target)) {
            return parseTransaction(stmt, skipWord(stmt, pos));
        }
        // SET @collect_exec_stats pins or releases; other SET @var and SET TRIGGER ...: not
        // tracked.
        return parseExecStatistics(stmt, pos, true);
    }

    /**
     * What a statement's last assignment to {@link #COLLECT_EXEC_STATS} does: a pin, a release, or
     * nothing when it assigns another variable or none. The variable is read as the server reads
     * it, past comments and quotes: {@code SET @v = ..} at the top level of a SET, or {@code @v :=
     * ..} anywhere. Only a bare 0 turns collection off; any other value, a bound one included,
     * turns it on.
     */
    private static SessionSetStatement parseExecStatistics(
            final String stmt, final int pos, final boolean inSet) {
        if (stmt.indexOf('@', pos) < 0) {
            return NONE_RESULT;
        }
        Boolean collects = null;
        int depth = 0;
        int i = pos;
        while (i < stmt.length()) {
            final int after = SqlLexer.skipCommentOrQuoted(stmt, i);
            if (after != i) {
                i = after;
                continue;
            }
            final char c = stmt.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c != '@') {
                i++;
                continue;
            }
            // '@' is a token of its own: white space or a comment may come before the name.
            final int nameStart = SqlLexer.skipWhitespaceAndComments(stmt, i + 1);
            final int nameEnd = variableEnd(stmt, nameStart);
            final int op = SqlLexer.skipWhitespaceAndComments(stmt, nameEnd);
            int value = -1;
            if (stmt.startsWith(":=", op)) {
                value = op + 2;
            } else if (inSet && depth == 0 && op < stmt.length() && stmt.charAt(op) == '=') {
                value = op + 1;
            }
            if (value >= 0 && isCollectExecStats(stmt, nameStart, nameEnd)) {
                collects = Boolean.valueOf(!isBareZero(stmt, value));
            }
            i = Math.max(nameEnd, i + 1);
        }
        if (collects == null) {
            return NONE_RESULT;
        }
        return collects.booleanValue() ? pin(EXEC_STATS_PIN) : unpin(EXEC_STATS_PIN);
    }

    private static boolean isCollectExecStats(final String stmt, final int start, final int end) {
        final String name =
                start < end && KeywordSqlClassifier.isQuotedIdentifierStart(stmt.charAt(start))
                        ? KeywordSqlClassifier.extractQuotedIdentifierUpper(stmt, start, end)
                        : stmt.substring(start, end);
        return COLLECT_EXEC_STATS.substring(1).equalsIgnoreCase(name);
    }

    /** A 0 with nothing after it in its assignment. */
    private static boolean isBareZero(final String stmt, final int pos) {
        final int value = SqlLexer.skipWhitespaceAndComments(stmt, pos);
        if (value >= stmt.length() || stmt.charAt(value) != '0') {
            return false;
        }
        final int after = SqlLexer.skipWhitespaceAndComments(stmt, value + 1);
        return after >= stmt.length() || stmt.charAt(after) == ',';
    }

    /**
     * {@code SET TRANSACTION} with its clauses from {@code pos}. ISOLATION LEVEL and LOCK TIMEOUT
     * are replayed under keys of their own, so a statement setting both is split in two.
     */
    private static SessionSetStatement parseTransaction(final String stmt, final int pos) {
        final int comma = secondClauseComma(stmt, pos);
        if (comma < 0) {
            return parseTransactionClause(stmt.trim());
        }
        final String head = stmt.substring(0, pos).trim();
        return new Builder()
                .then(parseTransactionClause(head + " " + stmt.substring(pos, comma).trim()))
                .then(parseTransactionClause(head + " " + stmt.substring(comma + 1).trim()))
                .build();
    }

    /** A {@code SET TRANSACTION} with one clause. */
    private static SessionSetStatement parseTransactionClause(final String text) {
        final String upper = text.toUpperCase(Locale.ROOT);
        final boolean isolation = upper.contains("ISOLATION");
        final boolean lockTimeout = upper.contains("TIMEOUT");
        if (!isolation && !lockTimeout) {
            return NONE_RESULT;
        }
        if (isolation && lockTimeout) {
            return pin(UNKNOWN_PIN);
        }
        return replayOrPin(isolation ? ISOLATION_LEVEL_KEY : LOCK_TIMEOUT_KEY, text);
    }

    /**
     * The top-level ',' a LOCK or ISOLATION clause follows, or -1. A comma inside an isolation
     * level name, as in {@code REPEATABLE READ CLASS, READ UNCOMMITTED INSTANCES}, is not one.
     */
    private static int secondClauseComma(final String stmt, final int pos) {
        int i = pos;
        while (i < stmt.length()) {
            final int after = SqlLexer.skipCommentOrQuoted(stmt, i);
            if (after != i) {
                i = after;
                continue;
            }
            if (stmt.charAt(i) == ',') {
                final String next = wordAt(stmt, i + 1);
                if ("LOCK".equals(next) || "ISOLATION".equals(next)) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /** {@code SET SYSTEM PARAMETERS 'name=value[; name=value...]'} from {@code pos}. */
    private static SessionSetStatement parseParameters(final String stmt, final int pos) {
        final int open = SqlLexer.skipWhitespaceAndComments(stmt, pos);
        if (open >= stmt.length() || stmt.charAt(open) != '\'') {
            return pin(UNKNOWN_PIN);
        }
        final int close = SqlLexer.skipCommentOrQuoted(stmt, open);
        if (stmt.charAt(close - 1) != '\'' || close - open < 2) {
            return pin(UNKNOWN_PIN);
        }
        if (SqlLexer.skipWhitespaceAndComments(stmt, close) < stmt.length()) {
            return pin(UNKNOWN_PIN);
        }
        final String list = stmt.substring(open + 1, close - 1).replace("''", "'");
        final List<String[]> pairs = parameterPairs(list);
        if (pairs == null) {
            return pin(UNKNOWN_PIN);
        }
        final Builder result = new Builder();
        for (final String[] pair : pairs) {
            final String name = pair[0].toLowerCase(Locale.ROOT);
            final String value = asListValue(pair[1]);
            if (REPLAYED_PARAMS.contains(name)) {
                result.then(
                        replay(
                                replayKey(name),
                                "SET SYSTEM PARAMETERS '"
                                        + name
                                        + "="
                                        + value.replace("'", "''")
                                        + "'"));
            } else if (NON_SESSION_PARAMS.contains(name)) {
                result.nonSessionParams.add(name);
            } else if (!IGNORED_PARAMS.contains(name)) {
                result.then(pin(UNKNOWN_PIN));
            }
        }
        return result.build();
    }

    /**
     * The name=value pairs of a parameter list as the engine's prm_get_next_param_value() reads
     * them: a name ends at white space or '=', which may be left out; an unquoted value ends at
     * white space or ';', which is optional between pairs. Null where the engine refuses the list.
     */
    private static List<String[]> parameterPairs(final String list) {
        final List<String[]> pairs = new ArrayList<String[]>();
        final int n = list.length();
        int p = skipSpaces(list, 0);
        while (p < n) {
            final int nameStart = p;
            while (p < n && !Character.isWhitespace(list.charAt(p)) && list.charAt(p) != '=') {
                p++;
            }
            final String name = list.substring(nameStart, p);
            p = skipSpaces(list, p);
            if (p < n && list.charAt(p) == '=') {
                p = skipSpaces(list, p + 1);
            }
            if (p >= n || name.length() == 0) {
                return null;
            }
            final StringBuilder value = new StringBuilder();
            final char quote = list.charAt(p);
            if (quote == '"' || quote == '\'') {
                p++;
                while (p < n && list.charAt(p) != quote) {
                    if (list.charAt(p) == '\\' && p + 1 < n) {
                        p++;
                    }
                    value.append(list.charAt(p++));
                }
                if (p >= n) {
                    return null;
                }
                p++;
            } else {
                while (p < n && !Character.isWhitespace(list.charAt(p)) && list.charAt(p) != ';') {
                    value.append(list.charAt(p++));
                }
            }
            // The engine takes one ';' and white space between pairs; a list it refuses fails on
            // RW and is not replayed.
            while (p < n && (Character.isWhitespace(list.charAt(p)) || list.charAt(p) == ';')) {
                p++;
            }
            pairs.add(new String[] {name, value.toString()});
        }
        return pairs;
    }

    private static int skipSpaces(final String text, final int from) {
        int p = from;
        while (p < text.length() && Character.isWhitespace(text.charAt(p))) {
            p++;
        }
        return p;
    }

    /** A value as one token of a parameter list again: quoted when it would not read as one. */
    private static String asListValue(final String value) {
        boolean plain = value.length() > 0 && value.charAt(0) != '"' && value.charAt(0) != '\'';
        for (int i = 0; plain && i < value.length(); i++) {
            plain = !Character.isWhitespace(value.charAt(i)) && value.charAt(i) != ';';
        }
        return plain ? value : '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
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

    /** Replays {@code stmt}, or pins under {@code key} until a replayable SET of it. */
    private static SessionSetStatement replayOrPin(final String key, final String stmt) {
        if (replayable(stmt)) {
            return replay(key, stmt);
        }
        final boolean takesValues = LOGIN_KEY.equals(key) || LOCK_TIMEOUT_KEY.equals(key);
        return new SessionSetStatement(
                Collections.<String, String>emptyMap(),
                Collections.singleton(key),
                Collections.<String>emptySet(),
                Collections.<String>emptyList(),
                Collections.<String>emptySet(),
                takesValues ? key : null);
    }

    private static SessionSetStatement replay(final String key, final String stmt) {
        String text = stmt;
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return new SessionSetStatement(
                Collections.singletonMap(key, text),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                Collections.<String>emptyList(),
                Collections.<String>emptySet(),
                null);
    }

    private static SessionSetStatement pin(final String reason) {
        return new SessionSetStatement(
                Collections.<String, String>emptyMap(),
                Collections.singleton(reason),
                Collections.<String>emptySet(),
                Collections.<String>emptyList(),
                Collections.<String>emptySet(),
                null);
    }

    private static SessionSetStatement notOfTheSession(final String name) {
        return new SessionSetStatement(
                Collections.<String, String>emptyMap(),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                Collections.singletonList(name),
                Collections.<String>emptySet(),
                null);
    }

    private static SessionSetStatement unpin(final String reason) {
        return new SessionSetStatement(
                Collections.<String, String>emptyMap(),
                Collections.<String>emptySet(),
                Collections.singleton(reason),
                Collections.<String>emptyList(),
                Collections.<String>emptySet(),
                null);
    }

    /**
     * The level a {@code SET TRANSACTION ISOLATION LEVEL} statement sets, when a read on another
     * node cannot honour it.
     *
     * @param sql the statement, as kept for replay
     * @return {@link java.sql.Connection#TRANSACTION_REPEATABLE_READ} or {@link
     *     java.sql.Connection#TRANSACTION_SERIALIZABLE}, or 0 for any other level
     */
    public static int isolationAboveReadCommitted(final String sql) {
        int pos = 0;
        while (pos < sql.length() && !"LEVEL".equals(wordAt(sql, pos))) {
            final int next = skipWord(sql, pos);
            pos = next > pos ? next : pos + 1;
        }
        final int level = SqlLexer.skipWhitespaceAndComments(sql, skipWord(sql, pos));
        final String name = wordAt(sql, level);
        if ("SERIALIZABLE".equals(name) || sql.startsWith("6", level)) {
            return java.sql.Connection.TRANSACTION_SERIALIZABLE;
        }
        if ("REPEATABLE".equals(name) || sql.startsWith("5", level)) {
            return java.sql.Connection.TRANSACTION_REPEATABLE_READ;
        }
        return 0;
    }

    /**
     * Whether a statement of {@code sql} prepares a named statement or drops one ({@code PREPARE},
     * {@code DEALLOCATE PREPARE}, {@code DROP PREPARE}). It lives in the session of the leg that
     * ran it, as a variable does.
     *
     * @param sql the statement string
     * @return whether it does
     */
    public static boolean preparesOrDropsAStatement(final String sql) {
        if (sql == null) {
            return false;
        }
        int start = 0;
        int i = 0;
        while (i <= sql.length()) {
            if (i < sql.length()) {
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
            final String first = wordAt(sql, start);
            if ("PREPARE".equals(first)
                    || "DEALLOCATE".equals(first)
                    || "DROP".equals(first)
                            && "PREPARE".equals(wordAt(sql, skipWord(sql, start)))) {
                return true;
            }
            start = i + 1;
            i++;
        }
        return false;
    }

    /**
     * Whether {@code sql} assigns a user variable: {@code SET @v = ..}, or {@code @v := ..} in any
     * statement. The variable then exists only in the session of the leg that ran the string, and
     * reads of it go to RW.
     *
     * @param sql the statement string
     * @return whether it assigns one
     */
    public static boolean assignsUserVariable(final String sql) {
        if (sql == null || sql.indexOf('@') < 0) {
            return false;
        }
        boolean atStatementStart = true;
        boolean inSet = false;
        int i = 0;
        while (i < sql.length()) {
            final int skipped = SqlLexer.skipCommentOrQuoted(sql, i);
            if (skipped != i) {
                i = skipped;
                continue;
            }
            final char c = sql.charAt(i);
            if (c == ';') {
                atStatementStart = true;
                inSet = false;
            } else if (atStatementStart && !Character.isWhitespace(c)) {
                atStatementStart = false;
                inSet = startsWithSet(sql, i);
            }
            if (c == '@') {
                final int name = SqlLexer.skipWhitespaceAndComments(sql, i + 1);
                final int next = SqlLexer.skipWhitespaceAndComments(sql, variableEnd(sql, name));
                if (sql.startsWith(":=", next)
                        || (inSet && next < sql.length() && sql.charAt(next) == '=')) {
                    return true;
                }
            }
            i++;
        }
        return false;
    }

    /** Position just after the variable name that starts at {@code pos}, quoted or not. */
    private static int variableEnd(final String sql, final int pos) {
        if (pos < sql.length() && KeywordSqlClassifier.isQuotedIdentifierStart(sql.charAt(pos))) {
            return SqlLexer.skipCommentOrQuoted(sql, pos);
        }
        int end = pos;
        while (end < sql.length()
                && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
            end++;
        }
        return end;
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

    /** Statements in the order they ran: on each setting, the later one wins. */
    private static final class Builder {
        private final Map<String, String> replay = new LinkedHashMap<String, String>();
        private final Set<String> pins = new LinkedHashSet<String>();
        private final Set<String> unpins = new LinkedHashSet<String>();
        private final List<String> nonSessionParams = new ArrayList<String>();
        private boolean severalStatements;

        Builder then(final SessionSetStatement next) {
            for (Map.Entry<String, String> each : next.replay.entrySet()) {
                replay.remove(each.getKey());
                replay.put(each.getKey(), each.getValue());
                pins.remove(each.getKey());
            }
            for (String reason : next.pins) {
                replay.remove(reason);
                unpins.remove(reason);
                pins.add(reason);
            }
            for (String reason : next.unpins) {
                pins.remove(reason);
                unpins.add(reason);
            }
            nonSessionParams.addAll(next.nonSessionParams);
            return this;
        }

        SessionSetStatement build() {
            final Set<String> ifFailed = new LinkedHashSet<String>();
            if (severalStatements) {
                ifFailed.addAll(replay.keySet());
                ifFailed.addAll(pins);
                ifFailed.addAll(unpins);
            }
            return new SessionSetStatement(
                    Collections.unmodifiableMap(replay),
                    Collections.unmodifiableSet(pins),
                    Collections.unmodifiableSet(unpins),
                    Collections.unmodifiableList(nonSessionParams),
                    Collections.unmodifiableSet(ifFailed),
                    null);
        }
    }
}
