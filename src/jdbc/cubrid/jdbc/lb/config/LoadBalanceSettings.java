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

import cubrid.jdbc.lb.LbExceptions;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser.ParsedUrl;
import cubrid.jdbc.lb.log.LbLogConfig;
import cubrid.jdbc.lb.metrics.MetricsConfig;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * In-memory LB settings.
 *
 * <p>{@link #fromUrl(LoadBalanceUrlParser.ParsedUrl)} builds them from a URI {@code loadbalance://}
 * URL: role topology with resolved ports ({@link #getResolvedTopology()}) plus {@link
 * #getReadWeight()}, with every other LB behavior pinned to the {@code DEFAULT_*} values. {@link
 * #of(Properties)} builds a property-backed instance for in-memory/test use, where the {@code
 * cubrid.lb.*} keys below can override those defaults.
 */
public class LoadBalanceSettings {
    private static final Logger LOGGER = Logger.getLogger(LoadBalanceSettings.class.getName());

    public static final String KEY_DISTRIBUTION_MODE = "cubrid.lb.distribution.mode";

    /**
     * Optional override for physical (per-broker) JDBC user when opening core connections. When
     * unset, the logical URL and {@code Properties} user apply.
     */
    public static final String KEY_PHYSICAL_JDBC_USER = "cubrid.lb.jdbc.physical.user";

    /** Optional override for physical (per-broker) JDBC password. */
    public static final String KEY_PHYSICAL_JDBC_PASSWORD = "cubrid.lb.jdbc.physical.password";

    /**
     * When true and session RO physical connect fails after RW succeeds, RO may reuse the RW
     * connection ({@link cubrid.jdbc.lb.connection.SessionPhysicalConnManager}). The property key
     * string is {@code cubrid.lb.ro.physical.failover.to.master} for historical compatibility.
     */
    public static final String KEY_RO_PHYSICAL_FAILOVER_TO_RW =
            "cubrid.lb.ro.physical.failover.to.master";

    /** Master switch for runtime (post-connect) physical failover and SQL retry. */
    public static final String KEY_RT_FAILOVER_ENABLED = "cubrid.lb.runtime.failover.enabled";

    /**
     * When true, re-execute the same SQL once after physical-binding recovery succeeds. Ignored for
     * writes and for statements whose parameters cannot be replayed.
     */
    public static final String KEY_RT_FAILOVER_RETRY_ONCE = "cubrid.lb.runtime.failover.retry.once";

    /**
     * Max connect dials to make within each role group while recovering the binding. Counts dials,
     * not candidates — an excluded endpoint is skipped without consuming the budget. {@code 0}
     * means try the entire group.
     */
    public static final String KEY_RT_FAILOVER_MAX_ATTEMPTS =
            "cubrid.lb.runtime.failover.max.attempts.per.group";

    /**
     * Master switch for read failback: a displaced read leg (on a sibling, cross-role, or roOnRw)
     * rebinds to its weighted home endpoint once that endpoint recovers.
     */
    public static final String KEY_READ_FAILBACK_ENABLED =
            "cubrid.lb.runtime.read.failback.enabled";

    /**
     * Minimum interval (ms) between read failback probes while a session is displaced. Throttles
     * how often it re-checks whether a better read endpoint (ideally its home) has recovered.
     */
    public static final String KEY_READ_FAILBACK_PROBE_INTERVAL_MS =
            "cubrid.lb.runtime.read.failback.probe.interval.ms";

    /**
     * Master switch for write (RW) failback. A session whose RW leg was bound to a sibling broker
     * (master unreachable or CAS-saturated at bind time) rebinds to the master RW once it recovers.
     * Without it the session writes to the sibling for its whole lifetime.
     */
    public static final String KEY_WRITE_FAILBACK_ENABLED =
            "cubrid.lb.runtime.write.failback.enabled";

    /**
     * Minimum interval (ms) between RW failback probes while a session is displaced. Throttles how
     * often a displaced session re-checks whether its master RW has recovered.
     */
    public static final String KEY_WRITE_FAILBACK_PROBE_INTERVAL_MS =
            "cubrid.lb.runtime.write.failback.probe.interval.ms";

    /**
     * Opt-in: also try RW failback from the pool health probe ({@code Connection.isValid()}), so an
     * idle pooled connection self-corrects without traffic. Off by default, because it turns a pure
     * probe into a path that may open a connection.
     */
    public static final String KEY_WRITE_FAILBACK_ON_VALIDATE =
            "cubrid.lb.runtime.write.failback.on.validate";

    /**
     * Minimum interval (ms) between recovery probes to an unreachable endpoint. Gates the
     * backoff-bypass paths ({@code recoverRw}/{@code recoverRo}/RO read-path) so a down broker is
     * probed at most once per interval, whatever the session count. Zero probes on every attempt; a
     * very large value falls back to the 60s JCI poll (kill switch).
     */
    public static final String KEY_RT_RECOVERY_PROBE_INTERVAL_MS =
            "cubrid.lb.runtime.recovery.probe.interval.ms";

    /**
     * Maximum SQL strings kept in the JVM-wide READ/WRITE classification cache. Zero or negative
     * disables caching (each call classifies without storing).
     */
    public static final String KEY_SQL_CLASSIFY_CACHE_MAX_ENTRIES =
            "cubrid.lb.sql.classify.cache.max.entries";

    public static final String DISTRIBUTION_MODE_SESSION = "session";
    public static final String DEFAULT_DISTRIBUTION_MODE = DISTRIBUTION_MODE_SESSION;
    public static final boolean DEFAULT_RO_PHYSICAL_FAILOVER_TO_RW = true;
    public static final boolean DEFAULT_RT_FAILOVER_ENABLED = true;
    public static final boolean DEFAULT_RT_FAILOVER_RETRY_ONCE = true;
    public static final int DEFAULT_RT_FAILOVER_MAX_ATTEMPTS = 1;
    public static final boolean DEFAULT_READ_FAILBACK_ENABLED = true;
    public static final int DEFAULT_READ_FAILBACK_PROBE_INTERVAL_MS = 3000;
    public static final boolean DEFAULT_WRITE_FAILBACK_ENABLED = true;
    public static final int DEFAULT_WRITE_FAILBACK_PROBE_INTERVAL_MS = 3000;
    public static final boolean DEFAULT_WRITE_FAILBACK_ON_VALIDATE = false;
    public static final int DEFAULT_RT_RECOVERY_PROBE_INTERVAL_MS = 3000;

    /** Default cap for {@link #KEY_SQL_CLASSIFY_CACHE_MAX_ENTRIES}. */
    public static final int DEFAULT_SQL_CLASSIFY_CACHE_MAX_ENTRIES = 8192;

    /**
     * Upper bound for {@link #KEY_SQL_CLASSIFY_CACHE_MAX_ENTRIES} when positive. Larger configured
     * values are clamped to limit JVM heap use (each entry retains the SQL string key).
     */
    public static final int MAX_SQL_CLASSIFY_CACHE_MAX_ENTRIES = 262144;

    private final String distributionMode;
    private final String physicalJdbcUser;
    private final String physicalJdbcPassword;
    private final boolean roPhysicalFailoverToRw;
    private final int sqlClassifyCacheMaxEntries;
    private final boolean rtFailoverEnabled;
    private final boolean rtFailoverRetryOnce;
    private final int rtFailoverMaxAttempts;
    private final boolean readFailbackEnabled;
    private final int readFailbackProbeIntervalMs;
    private final boolean writeFailbackEnabled;
    private final int writeFailbackProbeIntervalMs;
    private final boolean writeFailbackOnValidate;
    private final int recoveryProbeIntervalMs;

    // URL-derived role model; null for property-built (in-memory/test) configs.
    private final ResolvedRoleTopology resolvedTopology;
    private final ReadWeight readWeight;
    private final String databaseName;
    private final String urlVariant;
    private final String urlUser;
    private final String urlPassword;
    private final Map<String, String> propagatedOptions;
    private final boolean urlDerived;
    // Metrics export settings; DISABLED for property-built and non-loadbalance configs.
    private final MetricsConfig metricsConfig;
    // LB file-logging settings; DISABLED for property-built and non-loadbalance configs.
    private final LbLogConfig lbLogConfig;

    public static LoadBalanceSettings of(Properties properties) {
        return new LoadBalanceSettings(properties);
    }

    /**
     * Build in-memory settings from a parsed URI URL. Roles/ports come from {@link
     * ParsedRoleTopology}/{@link ResolvedRoleTopology}, weights from {@link ReadWeight}, and all
     * other LB behaviors are pinned to the fixed defaults.
     *
     * @param parsed the structurally parsed URI URL
     * @return the settings for the parsed URL
     * @throws SQLException if the URL is null or its topology/weights are invalid
     */
    public static LoadBalanceSettings fromUrl(final ParsedUrl parsed) throws SQLException {
        if (parsed == null) {
            throw LbExceptions.invalidUrl("parsed URL is null", null);
        }

        final ParsedRoleTopology roleTopology = ParsedRoleTopology.fromUrl(parsed);
        final ResolvedRoleTopology resolved =
                ResolvedRoleTopology.resolve(roleTopology, parsed.getOptions());
        final ReadWeight weights =
                ReadWeight.resolve(
                        OptionKeys.findValueIgnoreCase(parsed.getOptions(), "readWeight"),
                        roleTopology);
        final Map<String, String> propagated =
                LoadBalanceOptionValidator.validate(parsed.isLoadBalance(), parsed.getOptions())
                        .getRetainedOptions();
        // Metrics-export options apply only in loadbalance mode; parse() returns DISABLED
        // otherwise, so the exporters can never run outside loadbalance.
        final MetricsConfig metrics =
                MetricsConfig.parse(parsed.getOptions(), parsed.isLoadBalance());
        // Same scoping as metrics: file logging is a loadbalance-only feature, so parse() returns
        // DISABLED elsewhere and the lbLog* options can never reach a physical broker URL.
        final LbLogConfig lbLog = LbLogConfig.parse(parsed.getOptions(), parsed.isLoadBalance());

        return new LoadBalanceSettings(
                // No explicit properties: the constructor applies the DEFAULT_* fallbacks, so an
                // empty Properties yields the fixed defaults.
                new Properties(),
                resolved,
                weights,
                parsed.getDb(),
                parsed.getVariant(),
                parsed.getUser(),
                parsed.getPassword(),
                propagated,
                true,
                metrics,
                lbLog);
    }

    LoadBalanceSettings(Properties properties) {
        this(
                properties,
                null,
                null,
                null,
                "",
                null,
                null,
                null,
                false,
                MetricsConfig.DISABLED,
                LbLogConfig.DISABLED);
    }

    private LoadBalanceSettings(
            Properties properties,
            ResolvedRoleTopology resolvedTopology,
            ReadWeight readWeight,
            String databaseName,
            String urlVariant,
            String urlUser,
            String urlPassword,
            Map<String, String> propagatedOptions,
            boolean urlDerived,
            MetricsConfig metricsConfig,
            LbLogConfig lbLogConfig) {
        this.distributionMode =
                getOrDefault(properties, KEY_DISTRIBUTION_MODE, DEFAULT_DISTRIBUTION_MODE);
        this.physicalJdbcUser = properties.getProperty(KEY_PHYSICAL_JDBC_USER);
        this.physicalJdbcPassword = properties.getProperty(KEY_PHYSICAL_JDBC_PASSWORD);
        this.roPhysicalFailoverToRw =
                parseBooleanOrDefault(
                        properties.getProperty(KEY_RO_PHYSICAL_FAILOVER_TO_RW),
                        DEFAULT_RO_PHYSICAL_FAILOVER_TO_RW);
        this.sqlClassifyCacheMaxEntries =
                clampSqlClassifyCacheMaxEntries(
                        parseIntOrDefault(
                                KEY_SQL_CLASSIFY_CACHE_MAX_ENTRIES,
                                properties.getProperty(KEY_SQL_CLASSIFY_CACHE_MAX_ENTRIES),
                                DEFAULT_SQL_CLASSIFY_CACHE_MAX_ENTRIES));
        this.rtFailoverEnabled =
                parseBooleanOrDefault(
                        properties.getProperty(KEY_RT_FAILOVER_ENABLED),
                        DEFAULT_RT_FAILOVER_ENABLED);
        this.rtFailoverRetryOnce =
                parseBooleanOrDefault(
                        properties.getProperty(KEY_RT_FAILOVER_RETRY_ONCE),
                        DEFAULT_RT_FAILOVER_RETRY_ONCE);
        this.rtFailoverMaxAttempts =
                parseNonNegativeIntOrDefault(
                        KEY_RT_FAILOVER_MAX_ATTEMPTS,
                        properties.getProperty(KEY_RT_FAILOVER_MAX_ATTEMPTS),
                        DEFAULT_RT_FAILOVER_MAX_ATTEMPTS);
        this.readFailbackEnabled =
                parseBooleanOrDefault(
                        properties.getProperty(KEY_READ_FAILBACK_ENABLED),
                        DEFAULT_READ_FAILBACK_ENABLED);
        this.readFailbackProbeIntervalMs =
                parseNonNegativeIntOrDefault(
                        KEY_READ_FAILBACK_PROBE_INTERVAL_MS,
                        properties.getProperty(KEY_READ_FAILBACK_PROBE_INTERVAL_MS),
                        DEFAULT_READ_FAILBACK_PROBE_INTERVAL_MS);
        this.writeFailbackEnabled =
                parseBooleanOrDefault(
                        properties.getProperty(KEY_WRITE_FAILBACK_ENABLED),
                        DEFAULT_WRITE_FAILBACK_ENABLED);
        this.writeFailbackProbeIntervalMs =
                parseNonNegativeIntOrDefault(
                        KEY_WRITE_FAILBACK_PROBE_INTERVAL_MS,
                        properties.getProperty(KEY_WRITE_FAILBACK_PROBE_INTERVAL_MS),
                        DEFAULT_WRITE_FAILBACK_PROBE_INTERVAL_MS);
        this.writeFailbackOnValidate =
                parseBooleanOrDefault(
                        properties.getProperty(KEY_WRITE_FAILBACK_ON_VALIDATE),
                        DEFAULT_WRITE_FAILBACK_ON_VALIDATE);
        this.recoveryProbeIntervalMs =
                parseNonNegativeIntOrDefault(
                        KEY_RT_RECOVERY_PROBE_INTERVAL_MS,
                        properties.getProperty(KEY_RT_RECOVERY_PROBE_INTERVAL_MS),
                        DEFAULT_RT_RECOVERY_PROBE_INTERVAL_MS);

        this.resolvedTopology = resolvedTopology;
        this.readWeight = readWeight;
        this.databaseName = databaseName;
        this.urlVariant = urlVariant;
        this.urlUser = urlUser;
        this.urlPassword = urlPassword;
        this.propagatedOptions =
                propagatedOptions != null
                        ? Collections.unmodifiableMap(
                                new LinkedHashMap<String, String>(propagatedOptions))
                        : Collections.<String, String>emptyMap();
        this.urlDerived = urlDerived;
        this.metricsConfig = metricsConfig != null ? metricsConfig : MetricsConfig.DISABLED;
        this.lbLogConfig = lbLogConfig != null ? lbLogConfig : LbLogConfig.DISABLED;
    }

    static int clampSqlClassifyCacheMaxEntries(final int parsed) {
        if (parsed <= 0) {
            return parsed;
        }

        if (parsed > MAX_SQL_CLASSIFY_CACHE_MAX_ENTRIES) {
            return MAX_SQL_CLASSIFY_CACHE_MAX_ENTRIES;
        }

        return parsed;
    }

    public int getSqlClassifyCacheMaxEntries() {
        return sqlClassifyCacheMaxEntries;
    }

    public boolean isRoPhysicalFailoverToRw() {
        return roPhysicalFailoverToRw;
    }

    public boolean isRuntimeFailoverEnabled() {
        return rtFailoverEnabled;
    }

    public boolean isRuntimeFailoverRetryOnce() {
        return rtFailoverRetryOnce;
    }

    public int getRuntimeFailoverMaxAttempts() {
        return rtFailoverMaxAttempts;
    }

    public boolean isReadFailbackEnabled() {
        return readFailbackEnabled;
    }

    public int getReadFailbackProbeIntervalMs() {
        return readFailbackProbeIntervalMs;
    }

    public boolean isWriteFailbackEnabled() {
        return writeFailbackEnabled;
    }

    public int getWriteFailbackProbeIntervalMs() {
        return writeFailbackProbeIntervalMs;
    }

    public boolean isWriteFailbackOnValidate() {
        return writeFailbackOnValidate;
    }

    /**
     * Minimum interval (ms) between physical recovery probes to an unreachable endpoint.
     *
     * @return the recovery-probe interval in milliseconds
     */
    public int getRecoveryProbeIntervalMs() {
        return recoveryProbeIntervalMs;
    }

    public String getPhysicalJdbcUser() {
        return physicalJdbcUser;
    }

    public String getPhysicalJdbcPassword() {
        return physicalJdbcPassword;
    }

    public JdbcPhyConnSpec buildPhysicalJdbcSpec(
            String logicalJdbcUrl, Properties clientInfo, Endpoint endpoint) throws SQLException {
        if (urlDerived) {
            // URI loadbalance:// URL: the logical URL is not colon-delimited, so build the
            // per-broker physical URL directly from the parsed config fields.
            return JdbcEndpointConnSpecFactory.forEndpointFromConfig(this, clientInfo, endpoint);
        }
        return JdbcEndpointConnSpecFactory.forEndpoint(logicalJdbcUrl, clientInfo, endpoint, this);
    }

    /**
     * The {@link EndpointTopology} used by routing and failover. Derived from the role topology for
     * URL-derived configs; empty otherwise.
     *
     * @return the endpoint topology, empty for non-URL-derived configs
     * @throws SQLException if the topology cannot be built
     */
    public EndpointTopology buildEndpointTopology() throws SQLException {
        if (resolvedTopology != null) {
            return EndpointTopology.fromResolved(resolvedTopology);
        }
        // Non-URL (in-memory/test) configs carry no topology; sessions are bound from an explicitly
        // supplied EndpointTopology (see LoadBalanceConnection.initSessionBindings).
        return new EndpointTopology((Endpoint) null, null, null);
    }

    public String getDistributionMode() {
        return distributionMode;
    }

    /**
     * True if built from a URI URL ({@link #fromUrl}); false for property-built configs.
     *
     * @return {@code true} if this config was derived from a URL
     */
    public boolean isUrlDerived() {
        return urlDerived;
    }

    /**
     * Role topology with resolved RW/RO/SO endpoints (URL-derived configs only; else null).
     *
     * @return the resolved role topology, or {@code null}
     */
    public ResolvedRoleTopology getResolvedTopology() {
        return resolvedTopology;
    }

    /**
     * Role-based read weights (URL-derived configs only; else null).
     *
     * @return the read weights, or {@code null}
     */
    public ReadWeight getReadWeight() {
        return readWeight;
    }

    /**
     * Database name from the URL (URL-derived configs only; else null).
     *
     * @return the database name, or {@code null}
     */
    public String getDatabaseName() {
        return databaseName;
    }

    /**
     * Scheme variant ({@code ""}/{@code -oracle}/{@code -mysql}) from the URL.
     *
     * @return the scheme variant
     */
    public String getUrlVariant() {
        return urlVariant;
    }

    /**
     * Positional URL user, or {@code null}.
     *
     * @return the URL user, or {@code null}
     */
    public String getUrlUser() {
        return urlUser;
    }

    /**
     * Positional URL password, or {@code null}.
     *
     * @return the URL password, or {@code null}
     */
    public String getUrlPassword() {
        return urlPassword;
    }

    /**
     * Common options propagated to each physical connection.
     *
     * @return the propagated options
     */
    public Map<String, String> getPropagatedOptions() {
        return propagatedOptions;
    }

    /**
     * Metrics export settings; {@link MetricsConfig#DISABLED} unless a loadbalance URL enabled it.
     *
     * @return the metrics export settings
     */
    public MetricsConfig getMetricsConfig() {
        return metricsConfig;
    }

    /**
     * LB file-logging settings; {@link LbLogConfig#DISABLED} unless a loadbalance URL named an
     * {@code lbLogFile}.
     *
     * @return the LB file-logging settings
     */
    public LbLogConfig getLbLogConfig() {
        return lbLogConfig;
    }

    private static String getOrDefault(Properties props, String key, String defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().length() == 0) {
            return defaultValue;
        }

        return value.trim();
    }

    private static boolean parseBooleanOrDefault(String value, boolean defaultValue) {
        if (value == null || value.trim().length() == 0) {
            return defaultValue;
        }

        return Boolean.parseBoolean(value.trim());
    }

    private static int parseIntOrDefault(String key, String value, int defaultValue) {
        if (value == null || value.trim().length() == 0) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warning(
                    "Invalid integer for '"
                            + key
                            + "': \""
                            + value.trim()
                            + "\"; using default "
                            + defaultValue);
            return defaultValue;
        }
    }

    /** Like {@link #parseIntOrDefault} but also falls back to the default on negative values. */
    private static int parseNonNegativeIntOrDefault(String key, String value, int defaultValue) {
        int parsed = parseIntOrDefault(key, value, defaultValue);
        if (parsed < 0) {
            LOGGER.warning(
                    "Negative value for '"
                            + key
                            + "': "
                            + parsed
                            + "; using default "
                            + defaultValue);
            return defaultValue;
        }
        return parsed;
    }
}
