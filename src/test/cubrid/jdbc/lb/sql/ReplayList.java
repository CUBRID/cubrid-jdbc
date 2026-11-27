/*
 * Copyright (C) 2008 Search Solution Corporation.
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
 * The statements LB runs by itself, on a read leg or on a leg it opens later: the closed list of
 * spec 15.2.3. Written here apart from the driver, so a new replay is a decision recorded in this
 * list and the spec rather than a side effect of the classifier (review r5, recommendation 3).
 */
public final class ReplayList {
    private ReplayList() {}

    /** Replay keys, besides {@code PARAM:<name>} for each of {@link #PARAMS}. */
    public static final Set<String> KEYS =
            set(
                    "LOGIN",
                    "NAMES",
                    "PARAM:TIMEZONE",
                    "PARAM:ISOLATION_LEVEL",
                    "PARAM:LOCK_TIMEOUT",
                    "PARAM:OPTIMIZATION_LEVEL",
                    "PARAM:QUERY_TRACE");

    /** The session parameters {@code SET SYSTEM PARAMETERS} replays. */
    public static final Set<String> PARAMS =
            set(
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

    private static final String[] SET_PREFIXES = {
        "SET NAMES",
        "SET TIMEZONE",
        "SET TIME ZONE",
        "SET TRANSACTION ISOLATION LEVEL",
        "SET TRANSACTION LOCK TIMEOUT",
        "SET OPTIMIZATION LEVEL",
        "SET TRACE"
    };

    /**
     * Whether a leg may run {@code sql} without the application sending it there: one statement of
     * the list as a leg runs it, with bound values appended as {@code [..]} by the kit.
     */
    public static boolean allows(final String sql) {
        final String t = sql.trim().toUpperCase(Locale.ROOT);
        for (String prefix : SET_PREFIXES) {
            if (t.startsWith(prefix)) {
                return true;
            }
        }
        if (t.startsWith("CALL ")) {
            return t.contains("LOGIN") && t.contains("CLASS DB_USER");
        }
        final String params = "SET SYSTEM PARAMETERS '";
        if (!t.startsWith(params) || t.indexOf('\'', params.length()) < 0) {
            return false;
        }
        final String list = t.substring(params.length(), t.indexOf('\'', params.length()));
        for (String setting : list.split("[;\\s]+")) {
            final int eq = setting.indexOf('=');
            if (!setting.isEmpty()
                    && (eq <= 0
                            || !PARAMS.contains(
                                    setting.substring(0, eq).trim().toLowerCase(Locale.ROOT)))) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> set(final String... names) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(names)));
    }
}
