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

package cubrid.jdbc.lb.metrics;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Immutable, URL-derived settings for LB metrics export.
 *
 * <p>These options are parsed <b>only</b> for a {@code loadbalance} URL (see {@link #parse(Map,
 * boolean)}); any other URL forces the feature to {@link #DISABLED}. Since {@link
 * cubrid.jdbc.lb.LoadBalanceConnection} is also the only place that acts on this config, the
 * exporters can never run outside loadbalance.
 *
 * <p>Recognized URL options (all camelCase, case-insensitive):
 *
 * <ul>
 *   <li>{@code metricsEnabled} — master on/off for recording + export (default false);
 *   <li>{@code metricsExport} — comma list of {@code prometheus} and/or {@code csv} (default none);
 *   <li>{@code metricsPrometheusPort} — HTTP port for the {@code /metrics} endpoint (default 9400);
 *   <li>{@code metricsCsvPath} — local directory for the CSV file (default {@code ./lb-metrics});
 *   <li>{@code metricsIntervalSec} — CSV write period in seconds (default 60; Prometheus is pull,
 *       so this does not affect it);
 *   <li>{@code metricsCsvMaxSizeMb} — rotate the CSV past this size (default 100, {@code 0}
 *       disables);
 *   <li>{@code metricsCsvMaxFiles} — rotated generations to keep (default 5).
 * </ul>
 */
public final class MetricsConfig {
    private static final Logger LOGGER = Logger.getLogger(MetricsConfig.class.getName());

    // URL option names. Package cubrid.jdbc.lb.config.LoadBalanceOptionValidator lists the same
    // names in its LB-only set so they are ignored (WARN) outside loadbalance and never leak onto
    // physical broker URLs.
    public static final String OPT_ENABLED = "metricsEnabled";
    public static final String OPT_EXPORT = "metricsExport";
    public static final String OPT_PROMETHEUS_PORT = "metricsPrometheusPort";
    public static final String OPT_CSV_PATH = "metricsCsvPath";
    public static final String OPT_INTERVAL_SEC = "metricsIntervalSec";
    public static final String OPT_CSV_MAX_SIZE_MB = "metricsCsvMaxSizeMb";
    public static final String OPT_CSV_MAX_FILES = "metricsCsvMaxFiles";

    public static final String EXPORT_PROMETHEUS = "prometheus";
    public static final String EXPORT_CSV = "csv";

    private static final int DEFAULT_PROMETHEUS_PORT = 9400;
    private static final String DEFAULT_CSV_PATH = "./lb-metrics";
    private static final int DEFAULT_INTERVAL_SEC = 60;

    /**
     * CSV rotation defaults. The writer appends forever, so an unbounded file would fill the disk
     * on a long-running service (4 endpoints at metricsIntervalSec=15 is roughly 1.2 GB/year).
     * Rotating at 100 MB and keeping 5 generations caps the total at 600 MB. {@code
     * metricsCsvMaxSizeMb=0} disables rotation.
     */
    private static final int DEFAULT_CSV_MAX_SIZE_MB = 100;

    private static final int DEFAULT_CSV_MAX_FILES = 5;

    /** Feature-off singleton used for every non-loadbalance / property-built config. */
    public static final MetricsConfig DISABLED =
            new MetricsConfig(
                    false,
                    Collections.<String>emptySet(),
                    DEFAULT_PROMETHEUS_PORT,
                    DEFAULT_CSV_PATH,
                    DEFAULT_INTERVAL_SEC,
                    DEFAULT_CSV_MAX_SIZE_MB,
                    DEFAULT_CSV_MAX_FILES);

    private final boolean enabled;
    private final Set<String> exports;
    private final int prometheusPort;
    private final String csvPath;
    private final int intervalSec;
    private final int csvMaxSizeMb;
    private final int csvMaxFiles;

    private MetricsConfig(
            final boolean enabled,
            final Set<String> exports,
            final int prometheusPort,
            final String csvPath,
            final int intervalSec,
            final int csvMaxSizeMb,
            final int csvMaxFiles) {
        this.enabled = enabled;
        this.exports = Collections.unmodifiableSet(new LinkedHashSet<String>(exports));
        this.prometheusPort = prometheusPort;
        this.csvPath = csvPath;
        this.intervalSec = intervalSec;
        this.csvMaxSizeMb = csvMaxSizeMb;
        this.csvMaxFiles = csvMaxFiles;
    }

    /**
     * Parse metrics options from a URL's option map. Returns {@link #DISABLED} unless the URL is in
     * loadbalance mode <em>and</em> {@code metricsEnabled=true}.
     *
     * @param options the URL's raw option map; may be {@code null}
     * @param loadBalanceMode whether the URL is a loadbalance URL
     * @return the parsed config, or {@link #DISABLED}
     */
    public static MetricsConfig parse(
            final Map<String, String> options, final boolean loadBalanceMode) {
        if (!loadBalanceMode || options == null) {
            return DISABLED;
        }
        if (!parseBoolean(find(options, OPT_ENABLED), false)) {
            return DISABLED;
        }

        Set<String> exports = new LinkedHashSet<String>();
        String exportRaw = find(options, OPT_EXPORT);
        if (exportRaw != null) {
            String[] parts = exportRaw.split(",");
            for (int i = 0; i < parts.length; i++) {
                String token = parts[i].trim().toLowerCase();
                if (token.length() == 0) {
                    continue;
                }
                if (EXPORT_PROMETHEUS.equals(token) || EXPORT_CSV.equals(token)) {
                    exports.add(token);
                } else {
                    LOGGER.warning("unknown metricsExport target '" + token + "'; ignored");
                }
            }
        }
        if (exports.isEmpty()) {
            LOGGER.warning(
                    "metricsEnabled=true but metricsExport lists no known target (prometheus/csv);"
                            + " recording is on but nothing is exported");
        }

        int port =
                parsePositiveInt(
                        find(options, OPT_PROMETHEUS_PORT),
                        DEFAULT_PROMETHEUS_PORT,
                        OPT_PROMETHEUS_PORT);
        String csvPath = find(options, OPT_CSV_PATH);
        if (csvPath == null || csvPath.trim().length() == 0) {
            csvPath = DEFAULT_CSV_PATH;
        }
        int interval =
                parsePositiveInt(
                        find(options, OPT_INTERVAL_SEC), DEFAULT_INTERVAL_SEC, OPT_INTERVAL_SEC);

        int csvMaxSizeMb =
                parseNonNegativeInt(
                        find(options, OPT_CSV_MAX_SIZE_MB),
                        DEFAULT_CSV_MAX_SIZE_MB,
                        OPT_CSV_MAX_SIZE_MB);
        int csvMaxFiles =
                parseNonNegativeInt(
                        find(options, OPT_CSV_MAX_FILES), DEFAULT_CSV_MAX_FILES, OPT_CSV_MAX_FILES);

        return new MetricsConfig(
                true, exports, port, csvPath.trim(), interval, csvMaxSizeMb, csvMaxFiles);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean exportsPrometheus() {
        return exports.contains(EXPORT_PROMETHEUS);
    }

    public boolean exportsCsv() {
        return exports.contains(EXPORT_CSV);
    }

    public int getPrometheusPort() {
        return prometheusPort;
    }

    public String getCsvPath() {
        return csvPath;
    }

    public int getIntervalSec() {
        return intervalSec;
    }

    /**
     * Rotate the CSV once it exceeds this many megabytes; {@code 0} disables rotation.
     *
     * @return the CSV rotation threshold in megabytes, or {@code 0}
     */
    public int getCsvMaxSizeMb() {
        return csvMaxSizeMb;
    }

    /**
     * How many rotated generations ({@code .1} ... {@code .N}) to keep; the oldest is deleted.
     *
     * @return the number of rotated CSV generations to keep
     */
    public int getCsvMaxFiles() {
        return csvMaxFiles;
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
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim());
    }

    /**
     * Like {@link #parsePositiveInt} but accepts 0, which the rotation options use as "disabled".
     */
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

    private static int parsePositiveInt(
            final String value, final int defaultValue, final String key) {
        if (value == null || value.trim().length() == 0) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0) {
                return parsed;
            }
            LOGGER.warning("option '" + key + "' must be positive; using default " + defaultValue);
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
