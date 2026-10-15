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
import java.util.logging.Logger;

/**
 * URL-derived, immutable settings for what the LB log records carry. Where they go - file, level,
 * rotation, console - is configured in logging.properties (see {@code
 * cubrid.jdbc.lb.log.LbFileHandler}). Recognised options (camelCase, case-insensitive, {@code
 * loadbalance} URLs only):
 *
 * <ul>
 *   <li>{@code lbLogSql} — {@code masked} (default) / {@code raw} / {@code off}: how much of a
 *       routed statement the records quote.
 *   <li>{@code lbLogSqlMaxLen} — cap on that statement text (default 2048, {@code 0} for none).
 *   <li>{@code lbLogDistIntervalSec} — period of the distribution record; {@code 0} (default) turns
 *       it off.
 * </ul>
 */
public final class LbLogConfig {
    private static final Logger LOGGER = Logger.getLogger(LbLogConfig.class.getName());

    // URL option names. cubrid.jdbc.lb.config.LoadBalanceOptionValidator lists the same names in
    // its LB-only set, so they are ignored (WARN) outside loadbalance and never leak onto physical
    // broker URLs.
    public static final String OPT_SQL = "lbLogSql";
    public static final String OPT_SQL_MAX_LEN = "lbLogSqlMaxLen";
    public static final String OPT_DIST_INTERVAL_SEC = "lbLogDistIntervalSec";

    /** {@code lbLogSql} modes. */
    public static final String SQL_OFF = "off";

    public static final String SQL_MASKED = "masked";

    public static final String SQL_RAW = "raw";

    static final int DEFAULT_SQL_MAX_LEN = 2048;
    static final int DEFAULT_DIST_INTERVAL_SEC = 0;

    /** Used for every non-loadbalance / property-built config, where the options do not apply. */
    public static final LbLogConfig DEFAULTS =
            new LbLogConfig(SQL_MASKED, DEFAULT_SQL_MAX_LEN, DEFAULT_DIST_INTERVAL_SEC);

    private final String sqlMode;
    private final int sqlMaxLen;
    private final int distIntervalSec;

    private LbLogConfig(final String sqlMode, final int sqlMaxLen, final int distIntervalSec) {
        this.sqlMode = sqlMode;
        this.sqlMaxLen = sqlMaxLen;
        this.distIntervalSec = distIntervalSec;
    }

    /**
     * Parses the LB log options from a URL's option map. Returns {@link #DEFAULTS} unless the URL
     * is a loadbalance URL.
     *
     * @param options the URL's raw option map; may be {@code null}
     * @param loadBalanceMode whether the URL is a loadbalance URL
     * @return the parsed config, or {@link #DEFAULTS}
     */
    public static LbLogConfig parse(
            final Map<String, String> options, final boolean loadBalanceMode) {
        if (!loadBalanceMode || options == null) {
            return DEFAULTS;
        }

        return new LbLogConfig(
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

    private static String find(final Map<String, String> options, final String key) {
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (key.equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
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
