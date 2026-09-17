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

package cubrid.jdbc.lb.config;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * URL-derived, immutable configuration for LB file logging. Recognised options (camelCase,
 * case-insensitive, {@code loadbalance} URLs only):
 *
 * <ul>
 *   <li>{@code lbLogFile} — target path. <b>This option alone is the switch</b>: file logging is
 *       off until it is set to a non-empty value. The file is created exactly there; a relative
 *       path is taken against the JVM's working directory, as any other path in Java would be.
 *       Missing parent directories are created.
 *   <li>{@code lbLogLevel} — {@code WARNING} (default) / {@code INFO} / {@code FINE}. WARNING keeps
 *       only failure and degraded-state lines; FINE adds per-connection detail and is for
 *       reproduction runs, not production.
 *   <li>{@code lbLogMaxSizeMb} — rotate once the file reaches this size; {@code 0} disables
 *       rotation.
 *   <li>{@code lbLogMaxFiles} — how many rotated generations ({@code .1} … {@code .N}) to keep.
 *   <li>{@code lbLogToConsole} — keep publishing to the inherited console handler ({@code true},
 *       default). {@code false} routes LB logs to the file only, which is the point of the feature
 *       for a running service.
 *   <li>{@code lbLogThread} — add the thread name as an extra field ({@code false} by default; the
 *       per-connection context field is normally the more useful identifier).
 * </ul>
 *
 * <p>No separate {@code lbLogEnabled} flag exists on purpose: a boolean plus a path admits the
 * contradictory "enabled but no target" state that {@code MetricsConfig} has to warn about
 * separately. Deriving the switch from the path makes that state unrepresentable.
 */
public final class LbLogConfig {
    private static final Logger LOGGER = Logger.getLogger(LbLogConfig.class.getName());

    // URL option names. cubrid.jdbc.lb.config.LoadBalanceOptionValidator lists the same names in
    // its LB-only set, so they are ignored (WARN) outside loadbalance and never leak onto physical
    // broker URLs.
    public static final String OPT_FILE = "lbLogFile";
    public static final String OPT_LEVEL = "lbLogLevel";
    public static final String OPT_MAX_SIZE_MB = "lbLogMaxSizeMb";
    public static final String OPT_MAX_FILES = "lbLogMaxFiles";
    public static final String OPT_TO_CONSOLE = "lbLogToConsole";
    public static final String OPT_THREAD = "lbLogThread";
    public static final String OPT_SQL = "lbLogSql";
    public static final String OPT_SQL_MAX_LEN = "lbLogSqlMaxLen";
    public static final String OPT_DIST_INTERVAL_SEC = "lbLogDistIntervalSec";

    /** {@code lbLogSql} modes. */
    public static final String SQL_OFF = "off";

    public static final String SQL_MASKED = "masked";

    public static final String SQL_RAW = "raw";

    static final int DEFAULT_MAX_SIZE_MB = 10;
    static final int DEFAULT_MAX_FILES = 5;
    static final int DEFAULT_SQL_MAX_LEN = 200;
    static final int DEFAULT_DIST_INTERVAL_SEC = 0;

    /** Feature-off singleton used for every non-loadbalance / property-built config. */
    public static final LbLogConfig DISABLED =
            new LbLogConfig(
                    "",
                    Level.WARNING,
                    DEFAULT_MAX_SIZE_MB,
                    DEFAULT_MAX_FILES,
                    true,
                    false,
                    SQL_MASKED,
                    DEFAULT_SQL_MAX_LEN,
                    DEFAULT_DIST_INTERVAL_SEC);

    private final String file;
    private final Level level;
    private final int maxSizeMb;
    private final int maxFiles;
    private final boolean toConsole;
    private final boolean thread;
    private final String sqlMode;
    private final int sqlMaxLen;
    private final int distIntervalSec;

    private LbLogConfig(
            final String file,
            final Level level,
            final int maxSizeMb,
            final int maxFiles,
            final boolean toConsole,
            final boolean thread,
            final String sqlMode,
            final int sqlMaxLen,
            final int distIntervalSec) {
        this.file = file;
        this.level = level;
        this.maxSizeMb = maxSizeMb;
        this.maxFiles = maxFiles;
        this.toConsole = toConsole;
        this.thread = thread;
        this.sqlMode = sqlMode;
        this.sqlMaxLen = sqlMaxLen;
        this.distIntervalSec = distIntervalSec;
    }

    /**
     * Parses the file-logging options from a URL's option map. Returns {@link #DISABLED} unless the
     * URL is a loadbalance URL <em>and</em> {@code lbLogFile} names a target.
     *
     * @param options the URL's raw option map; may be {@code null}
     * @param loadBalanceMode whether the URL is a loadbalance URL
     * @return the parsed config, or {@link #DISABLED}
     */
    public static LbLogConfig parse(
            final Map<String, String> options, final boolean loadBalanceMode) {
        if (!loadBalanceMode || options == null) {
            return DISABLED;
        }

        // A missing lbLogFile switches off the *file*, not the other options. lbLogSql shapes a
        // record and lbLogDistIntervalSec decides whether one is produced at all; both matter when
        // the LB logger is wired up elsewhere (a JUL handler from logging.properties, a
        // container's own). Returning DISABLED here made those two silently inert.
        String path = find(options, OPT_FILE);
        String file = path == null ? "" : path.trim();

        return new LbLogConfig(
                file,
                parseLevel(find(options, OPT_LEVEL)),
                parseNonNegativeInt(
                        find(options, OPT_MAX_SIZE_MB), DEFAULT_MAX_SIZE_MB, OPT_MAX_SIZE_MB),
                parseNonNegativeInt(find(options, OPT_MAX_FILES), DEFAULT_MAX_FILES, OPT_MAX_FILES),
                parseBoolean(find(options, OPT_TO_CONSOLE), true),
                parseBoolean(find(options, OPT_THREAD), false),
                parseSqlMode(find(options, OPT_SQL)),
                parseNonNegativeInt(
                        find(options, OPT_SQL_MAX_LEN), DEFAULT_SQL_MAX_LEN, OPT_SQL_MAX_LEN),
                parseNonNegativeInt(
                        find(options, OPT_DIST_INTERVAL_SEC),
                        DEFAULT_DIST_INTERVAL_SEC,
                        OPT_DIST_INTERVAL_SEC));
    }

    /**
     * How much of a routed statement the {@code LB ROUTE} record carries. {@code masked} (the
     * default) replaces values with {@code ?}; {@code raw} keeps the statement as issued, including
     * whatever the application inlined; {@code off} records only the decision and the endpoint.
     *
     * @return one of {@link #SQL_OFF}, {@link #SQL_MASKED} or {@link #SQL_RAW}
     */
    public String getSqlMode() {
        return sqlMode;
    }

    /**
     * Cap on the statement text in a {@code LB ROUTE} record; {@code 0} means no cap.
     *
     * @return the statement-text cap in characters, or {@code 0}
     */
    public int getSqlMaxLen() {
        return sqlMaxLen;
    }

    /**
     * How often the periodic distribution record is written, in seconds; {@code 0} (the default)
     * turns it off.
     *
     * <p>A non-zero value also switches on the runtime counters the record is computed from, since
     * without them the aggregation APIs cannot see this connection. It does not start a metrics
     * exporter.
     *
     * @return the distribution-record interval in seconds, or {@code 0} when off
     */
    public int getDistIntervalSec() {
        return distIntervalSec;
    }

    /**
     * Defaults to {@link #SQL_MASKED} rather than {@link #SQL_RAW}: turning on FINE to investigate
     * routing must not, by that act alone, start writing application data into a file. {@code raw}
     * is available but has to be asked for.
     */
    private static String parseSqlMode(final String value) {
        if (value == null || value.trim().length() == 0) {
            return SQL_MASKED;
        }
        String v = value.trim().toLowerCase();
        if (SQL_OFF.equals(v) || SQL_MASKED.equals(v)) {
            return v;
        }
        if (SQL_RAW.equals(v)) {
            LOGGER.warning(
                    "option '"
                            + OPT_SQL
                            + "=raw' records statements as issued -- values inlined by"
                            + " the application (ids, addresses, card numbers) will be written to the"
                            + " log file. Use 'masked' unless the raw text is required.");
            return SQL_RAW;
        }
        LOGGER.warning(
                "option '"
                        + OPT_SQL
                        + "' must be one of off/masked/raw ('"
                        + value
                        + "'); using default masked");
        return SQL_MASKED;
    }

    /**
     * Whether a file target was named. Everything else here is tuning that only matters once this
     * is true.
     *
     * @return {@code true} if an LB log file was named
     */
    public boolean isEnabled() {
        return file.length() > 0;
    }

    public String getFile() {
        return file;
    }

    public Level getLevel() {
        return level;
    }

    /**
     * Rotate once the log exceeds this many megabytes; {@code 0} disables rotation.
     *
     * @return the rotation threshold in megabytes, or {@code 0}
     */
    public int getMaxSizeMb() {
        return maxSizeMb;
    }

    /**
     * How many rotated generations ({@code .1} ... {@code .N}) to keep; the oldest is deleted.
     *
     * @return the number of rotated generations to keep
     */
    public int getMaxFiles() {
        return maxFiles;
    }

    public boolean isToConsole() {
        return toConsole;
    }

    /**
     * Whether each record carries the logging thread's name as an extra field ({@code
     * lbLogThread}). It selects a column in the log line, not anything about threading.
     *
     * @return {@code true} if the thread name is logged
     */
    public boolean isThreadNameLogged() {
        return thread;
    }

    /**
     * Only the three levels the LB call sites actually use are accepted; anything else falls back
     * to WARNING with a WARN rather than silently enabling per-query FINE logging.
     */
    private static Level parseLevel(final String value) {
        if (value == null || value.trim().length() == 0) {
            return Level.WARNING;
        }
        String v = value.trim().toUpperCase();
        if ("WARNING".equals(v) || "WARN".equals(v)) {
            return Level.WARNING;
        }
        if ("INFO".equals(v)) {
            return Level.INFO;
        }
        if ("FINE".equals(v) || "DEBUG".equals(v)) {
            return Level.FINE;
        }
        LOGGER.warning(
                "option '"
                        + OPT_LEVEL
                        + "' must be one of WARNING/INFO/FINE ('"
                        + value
                        + "'); using default WARNING");
        return Level.WARNING;
    }

    private static String find(final Map<String, String> options, final String key) {
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (key.equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean parseBoolean(final String value, final boolean defaultValue) {
        if (value == null || value.trim().length() == 0) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim());
    }

    private static int parseNonNegativeInt(
            final String value, final int defaultValue, final String key) {
        if (value == null || value.trim().length() == 0) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 0) {
                return parsed;
            }
            LOGGER.warning(
                    "option '" + key + "' must not be negative; using default " + defaultValue);
        } catch (NumberFormatException e) {
            LOGGER.warning(
                    "option '"
                            + key
                            + "' is not an integer ('"
                            + value
                            + "'); using default "
                            + defaultValue);
        }
        return defaultValue;
    }
}
