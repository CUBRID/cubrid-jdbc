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
import java.util.HashSet;
import java.util.Set;

/**
 * CUBRID built-in function (and SQL-syntax) name registry used by {@link KeywordSqlClassifier} to
 * route {@code ident(...)} calls inside {@code SELECT}.
 *
 * <p>Names are classified along two axes:
 *
 * <ul>
 *   <li><b>Built-in RO vs RW</b> — {@link #isReadOnlyBuiltin(String)} are read-only-safe built-ins
 *       (RO routable); {@link #isWriteBuiltin(String)} are built-ins with write / session-state /
 *       LOB side effects that must use the RW (master) endpoint (e.g. {@code SERIAL_NEXT_VALUE},
 *       {@code SERIAL_CURRENT_VALUE}). RW built-ins split further by classification label: {@link
 *       #isWriteEffectBuiltin(String)} ones mutate state and make the {@code SELECT} {@code WRITE},
 *       while the rest are session-dependent / LOB and make it {@code UNKNOWN}. Both route to RW.
 *   <li><b>Built-in vs user routine</b> - {@link #isBuiltin(String)} (RO or RW) answers "is this a
 *       CUBRID built-in?". An {@code ident(...)} call that is not a built-in is assumed to be a
 *       stored procedure / Java SP / PL_CSQL / user-defined function and routed to RW.
 * </ul>
 *
 * <p>{@link #isSqlSyntax(String)} holds reserved words that can be immediately followed by {@code
 * (...)} — subquery / derived-table / quantified comparison / window keywords, plus clause and
 * operator keywords ({@code WHERE}, {@code AND}, {@code OR}, {@code HAVING}, {@code ON}, {@code
 * UNION}, {@code BETWEEN}, …). They must NOT be mistaken for function calls: these are SQL syntax,
 * not functions.
 *
 * <p>The name sets follow the CUBRID manual and need updating when a server release adds or removes
 * built-ins.
 *
 * <p>Note: serial pseudo-columns {@code seq.NEXTVAL}/{@code CURRVAL} (no parentheses) are not
 * function calls and are detected separately in {@link KeywordSqlClassifier}.
 */
public final class BuiltinFunctionCatalog {
    private static final Set<String> RO_BUILTINS;
    private static final Set<String> RW_BUILTINS;
    private static final Set<String> WRITE_EFFECT_BUILTINS;
    private static final Set<String> SQL_SYNTAX;
    private static final Set<String> ALL_BUILTINS;
    private static final Set<String> SERIAL_PSEUDO_COLUMNS;
    private static final Set<String> WRITE_EFFECT_PSEUDO_COLUMNS;

    private static final String[] NUMERIC = {
        "ABS",
        "ACOS",
        "ASIN",
        "ATAN",
        "ATAN2",
        "CEIL",
        "CONV",
        "COS",
        "COT",
        "CRC32",
        "DEGREES",
        "DRANDOM",
        "DRAND",
        "EXP",
        "FLOOR",
        "HEX",
        "LN",
        "LOG",
        "LOG2",
        "LOG10",
        "MOD",
        "PI",
        "POW",
        "POWER",
        "RADIANS",
        "RANDOM",
        "RAND",
        "ROUND",
        "SIGN",
        "SIN",
        "SQRT",
        "TAN",
        "TRUNC",
        "TRUNCATE",
        "WIDTH_BUCKET"
    };

    private static final String[] STRING = {
        "ASCII",
        "BIN",
        "BIT_LENGTH",
        "CHAR_LENGTH",
        "CHARACTER_LENGTH",
        "LENGTHB",
        "LENGTH",
        "CHR",
        "CONCAT",
        "CONCAT_WS",
        "ELT",
        "FIELD",
        "FIND_IN_SET",
        "FROM_BASE64",
        "INSERT",
        "INSTR",
        "LCASE",
        "LOWER",
        "LEFT",
        "LOCATE",
        "LPAD",
        "LTRIM",
        "MID",
        "OCTET_LENGTH",
        "POSITION",
        "REPEAT",
        "REPLACE",
        "REVERSE",
        "RIGHT",
        "RPAD",
        "RTRIM",
        "SPACE",
        "STRCMP",
        "SUBSTR",
        "SUBSTRING",
        "SUBSTRING_INDEX",
        "TO_BASE64",
        "TRANSLATE",
        "TRIM",
        "UCASE",
        "UPPER"
    };

    private static final String[] DATETIME = {
        "ADDDATE",
        "DATE_ADD",
        "ADDTIME",
        "ADD_MONTHS",
        "CURDATE",
        "CURRENT_DATE",
        "CURRENT_DATETIME",
        "NOW",
        "CURTIME",
        "CURRENT_TIME",
        "CURRENT_TIMESTAMP",
        "LOCALTIME",
        "LOCALTIMESTAMP",
        "DATEDIFF",
        "DATE_SUB",
        "SUBDATE",
        "DAY",
        "DAYOFMONTH",
        "DAYOFWEEK",
        "DAYOFYEAR",
        "EXTRACT",
        "FROM_DAYS",
        "FROM_TZ",
        "FROM_UNIXTIME",
        "HOUR",
        "LAST_DAY",
        "MAKEDATE",
        "MAKETIME",
        "MINUTE",
        "MONTH",
        "MONTHS_BETWEEN",
        "NEW_TIME",
        "QUARTER",
        "SEC_TO_TIME",
        "SECOND",
        "SYS_DATE",
        "SYSDATE",
        "SYS_DATETIME",
        "SYSDATETIME",
        "SYS_TIME",
        "SYSTIME",
        "SYS_TIMESTAMP",
        "SYSTIMESTAMP",
        "TIME",
        "TIME_TO_SEC",
        "TIMEDIFF",
        "TIMESTAMP",
        "TO_DAYS",
        "TZ_OFFSET",
        "UNIX_TIMESTAMP",
        "UTC_DATE",
        "UTC_TIME",
        "WEEK",
        "WEEKDAY",
        "YEAR"
    };

    private static final String[] BIT = {"BIT_COUNT"};

    private static final String[] AGGREGATE = {
        "AVG",
        "COUNT",
        "MAX",
        "MIN",
        "SUM",
        "STDDEV",
        "STDDEV_POP",
        "STDDEV_SAMP",
        "VARIANCE",
        "VAR_POP",
        "VAR_SAMP",
        "GROUP_CONCAT",
        "BIT_AND",
        "BIT_OR",
        "BIT_XOR",
        "MEDIAN"
    };

    private static final String[] INFORMATION = {
        "CHARSET",
        "COERCIBILITY",
        "COLLATION",
        "DATABASE",
        "SCHEMA",
        "DBTIMEZONE",
        "SESSIONTIMEZONE",
        "DEFAULT",
        "DISK_SIZE",
        "INDEX_CARDINALITY",
        "INET_ATON",
        "INET_NTOA",
        "LIST_DBS",
        "USER",
        "SYSTEM_USER",
        "VERSION"
    };

    private static final String[] TYPECAST = {
        "CAST",
        "DATE_FORMAT",
        "TIME_FORMAT",
        "FORMAT",
        "STR_TO_DATE",
        "TO_CHAR",
        "TO_DATE",
        "TO_DATETIME",
        "TO_DATETIME_TZ",
        "TO_NUMBER",
        "TO_TIME",
        "TO_TIMESTAMP",
        "TO_TIMESTAMP_TZ"
    };

    private static final String[] REGEX = {
        "REGEXP_COUNT", "REGEXP_INSTR", "REGEXP_LIKE", "REGEXP_REPLACE", "REGEXP_SUBSTR"
    };

    private static final String[] CONDITIONAL = {
        "NVL", "NVL2", "DECODE", "IF", "IFNULL", "COALESCE"
    };

    private static final String[] WINDOW = {
        "LEAD",
        "LAG",
        "FIRST_VALUE",
        "LAST_VALUE",
        "NTH_VALUE",
        "ROW_NUMBER",
        "RANK",
        "DENSE_RANK",
        "NTILE",
        "CUME_DIST",
        "PERCENT_RANK"
    };

    // ----- Built-ins with write / session-state / LOB side effects: must route to RW. -----

    /**
     * Write-effect functions: a {@code SELECT} invoking these mutates state and is classified
     * {@code WRITE}. {@code SERIAL_NEXT_VALUE} advances the serial; {@code INCR}/{@code DECR}
     * mutate a column.
     */
    private static final String[] WRITE_EFFECT_RW = {"SERIAL_NEXT_VALUE", "INCR", "DECR"};

    /**
     * Session-dependent functions: the value depends on the session's latest state, so they must
     * run on the same CAS (RW) as the preceding write. They write nothing, so a {@code SELECT}
     * using them is {@code UNKNOWN}. {@code SERIAL_CURRENT_VALUE} returns the session's last
     * NEXTVAL; {@code LAST_INSERT_ID}/{@code ROW_COUNT} return its last DML state.
     */
    private static final String[] SESSION_RW = {
        "SERIAL_CURRENT_VALUE", "LAST_INSERT_ID", "ROW_COUNT"
    };

    /**
     * LOB functions: CUBRID LOB values are not replicated to RO/SO brokers, so reads must use RW.
     * They perform no write, so a {@code SELECT} invoking these is classified {@code UNKNOWN}.
     */
    private static final String[] LOB_RW = {
        "BIT_TO_BLOB",
        "BLOB_FROM_FILE",
        "BLOB_LENGTH",
        "BLOB_TO_BIT",
        "CHAR_TO_BLOB",
        "CHAR_TO_CLOB",
        "CLOB_FROM_FILE",
        "CLOB_LENGTH",
        "CLOB_TO_CHAR"
    };

    // Serial pseudo-columns used WITHOUT parentheses, as serial_name.NEXTVAL / .CURRVAL. They are
    // not function calls, so the classifier detects them separately (dot-qualified).

    /**
     * {@code NEXTVAL} increments the serial (write) → a {@code SELECT} referencing it is {@code
     * WRITE}.
     */
    private static final String[] WRITE_EFFECT_PSEUDO = {"NEXTVAL", "NEXT_VALUE"};

    /** {@code CURRVAL} is the session's last NEXTVAL (session-dependent) → {@code UNKNOWN}. */
    private static final String[] SESSION_PSEUDO = {"CURRVAL", "CURRENT_VALUE"};

    /**
     * Reserved words that use {@code keyword (...)} syntax (subquery / derived-table / quantified
     * comparison / window). They are NOT functions and must not be treated as user routines.
     */
    private static final String[] SYNTAX = {
        // subquery / derived-table / quantified comparison / window
        "ALL",
        "ANY",
        "EXISTS",
        "FROM",
        "IN",
        "JOIN",
        "OVER",
        "SOME",
        // clause / operator keywords that take a parenthesized group: prevents "keyword (...)" from
        // being mistaken for a user-routine call. e.g. WHERE (a=1), AND (...), ON (...).
        "WHERE",
        "AND",
        "OR",
        "NOT",
        "HAVING",
        "ON",
        "USING",
        "UNION",
        "INTERSECT",
        "EXCEPT",
        "MINUS",
        "WHEN",
        "VALUES",
        "BETWEEN"
    };

    static {
        final HashSet<String> ro = new HashSet<String>(256);
        addAll(ro, NUMERIC);
        addAll(ro, STRING);
        addAll(ro, DATETIME);
        addAll(ro, BIT);
        addAll(ro, AGGREGATE);
        addAll(ro, INFORMATION);
        addAll(ro, TYPECAST);
        addAll(ro, REGEX);
        addAll(ro, CONDITIONAL);
        addAll(ro, WINDOW);
        RO_BUILTINS = Collections.unmodifiableSet(ro);

        final HashSet<String> rw = new HashSet<String>(32);
        addAll(rw, WRITE_EFFECT_RW);
        addAll(rw, SESSION_RW);
        addAll(rw, LOB_RW);
        RW_BUILTINS = Collections.unmodifiableSet(rw);

        final HashSet<String> writeEffect = new HashSet<String>(8);
        addAll(writeEffect, WRITE_EFFECT_RW);
        WRITE_EFFECT_BUILTINS = Collections.unmodifiableSet(writeEffect);

        final HashSet<String> syntax = new HashSet<String>(16);
        addAll(syntax, SYNTAX);
        SQL_SYNTAX = Collections.unmodifiableSet(syntax);

        final HashSet<String> all = new HashSet<String>(ro.size() + rw.size());
        all.addAll(ro);
        all.addAll(rw);
        ALL_BUILTINS = Collections.unmodifiableSet(all);

        final HashSet<String> pseudo = new HashSet<String>(8);
        addAll(pseudo, WRITE_EFFECT_PSEUDO);
        addAll(pseudo, SESSION_PSEUDO);
        SERIAL_PSEUDO_COLUMNS = Collections.unmodifiableSet(pseudo);

        final HashSet<String> writePseudo = new HashSet<String>(4);
        addAll(writePseudo, WRITE_EFFECT_PSEUDO);
        WRITE_EFFECT_PSEUDO_COLUMNS = Collections.unmodifiableSet(writePseudo);
    }

    private BuiltinFunctionCatalog() {}

    /**
     * RO-safe built-in: may be routed to a slave/replica when invoked as {@code ident(...)}.
     *
     * @param upperAsciiName built-in name, upper-cased ASCII
     * @return {@code true} if the name is a read-only built-in
     */
    public static boolean isReadOnlyBuiltin(final String upperAsciiName) {
        return contains(RO_BUILTINS, upperAsciiName);
    }

    /**
     * Built-in with a write or session-state side effect: must route to RW (master).
     *
     * @param upperAsciiName built-in name, upper-cased ASCII
     * @return {@code true} if the name is an RW built-in
     */
    public static boolean isWriteBuiltin(final String upperAsciiName) {
        return contains(RW_BUILTINS, upperAsciiName);
    }

    /**
     * Built-in that performs a write ({@code SERIAL_NEXT_VALUE}/{@code INCR}/{@code DECR}), so a
     * {@code SELECT} using it is {@code WRITE}. Strict subset of {@link #isWriteBuiltin(String)};
     * the other RW built-ins are session-dependent and give {@code UNKNOWN}.
     *
     * @param upperAsciiName built-in name, upper-cased ASCII
     * @return {@code true} if invoking the built-in performs a write
     */
    public static boolean isWriteEffectBuiltin(final String upperAsciiName) {
        return contains(WRITE_EFFECT_BUILTINS, upperAsciiName);
    }

    /**
     * Any CUBRID built-in (RO or RW). An {@code ident(...)} call not listed here is a user routine.
     *
     * @param upperAsciiName built-in name, upper-cased ASCII
     * @return {@code true} if the name is a known CUBRID built-in
     */
    public static boolean isBuiltin(final String upperAsciiName) {
        return contains(ALL_BUILTINS, upperAsciiName);
    }

    /**
     * Reserved word using {@code keyword (...)} syntax, not a function.
     *
     * @param upperAsciiName identifier, upper-cased ASCII
     * @return {@code true} if the name is SQL syntax rather than a function
     */
    public static boolean isSqlSyntax(final String upperAsciiName) {
        return contains(SQL_SYNTAX, upperAsciiName);
    }

    /**
     * Serial pseudo-column ({@code NEXTVAL}/{@code NEXT_VALUE}/{@code CURRVAL}/{@code
     * CURRENT_VALUE}) used without parentheses, as {@code serial_name.NEXTVAL}. It has a write or
     * session side effect, so it must route to RW. The caller must confirm dot-qualification.
     *
     * @param upperAsciiName pseudo-column name, upper-cased ASCII
     * @return {@code true} if the name is a serial pseudo-column
     */
    public static boolean isSerialPseudoColumn(final String upperAsciiName) {
        return contains(SERIAL_PSEUDO_COLUMNS, upperAsciiName);
    }

    /**
     * Serial increment pseudo-column ({@code NEXTVAL}/{@code NEXT_VALUE}): referencing it advances
     * the serial, so a {@code SELECT} is {@code WRITE}. Strict subset of {@link
     * #isSerialPseudoColumn(String)}; {@code CURRVAL}/{@code CURRENT_VALUE} give {@code UNKNOWN}.
     * The caller must confirm dot-qualification.
     *
     * @param upperAsciiName pseudo-column name, upper-cased ASCII
     * @return {@code true} if referencing the pseudo-column advances the serial
     */
    public static boolean isSerialIncrementPseudoColumn(final String upperAsciiName) {
        return contains(WRITE_EFFECT_PSEUDO_COLUMNS, upperAsciiName);
    }

    private static boolean contains(final Set<String> set, final String name) {
        if (name == null || name.length() == 0) {
            return false;
        }

        return set.contains(name);
    }

    private static void addAll(final HashSet<String> target, final String[] names) {
        for (int i = 0; i < names.length; i++) {
            target.add(names[i]);
        }
    }
}
