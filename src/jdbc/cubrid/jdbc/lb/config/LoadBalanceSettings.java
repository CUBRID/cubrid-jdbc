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
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * In-memory LB settings, built from a URI {@code loadbalance://} URL by {@link #fromUrl}: role
 * topology with resolved ports ({@link #getResolvedTopology()}) plus {@link #getReadWeight()}.
 * Every other LB behavior is pinned to the {@code DEFAULT_*} values, which no URL option changes.
 */
public class LoadBalanceSettings {
    /**
     * When session RO physical connect fails after RW succeeds, RO may reuse the RW connection
     * ({@link cubrid.jdbc.lb.connection.SessionPhysicalConnManager}).
     */
    private static final boolean DEFAULT_RO_PHYSICAL_FAILOVER_TO_RW = true;

    /** Master switch for runtime (post-connect) physical failover and SQL retry. */
    private static final boolean DEFAULT_RT_FAILOVER_ENABLED = true;

    /**
     * Re-execute the same SQL once after physical-binding recovery succeeds. Ignored for writes and
     * for statements whose parameters cannot be replayed.
     */
    private static final boolean DEFAULT_RT_FAILOVER_RETRY_ONCE = true;

    /**
     * Max connect dials within each role group while recovering the binding. Counts dials, not
     * candidates — an excluded endpoint is skipped without consuming the budget. {@code 0} means
     * try the entire group.
     */
    private static final int DEFAULT_RT_FAILOVER_MAX_ATTEMPTS = 1;

    /**
     * Read failback: a displaced read leg (on a sibling, cross-role, or roOnRw) rebinds to its
     * weighted home endpoint once that endpoint recovers.
     */
    private static final boolean DEFAULT_READ_FAILBACK_ENABLED = true;

    /** Minimum interval (ms) between read failback probes while a session is displaced. */
    private static final int DEFAULT_READ_FAILBACK_PROBE_INTERVAL_MS = 3000;

    /**
     * Write (RW) failback: a session whose RW leg was bound to a sibling broker (master unreachable
     * or CAS-saturated at bind time) rebinds to the master RW once it recovers.
     */
    private static final boolean DEFAULT_WRITE_FAILBACK_ENABLED = true;

    /** Minimum interval (ms) between RW failback probes while a session is displaced. */
    private static final int DEFAULT_WRITE_FAILBACK_PROBE_INTERVAL_MS = 3000;

    /**
     * Also try RW failback from the pool health probe ({@code Connection.isValid()}). Off, because
     * it turns a pure probe into a path that may open a connection.
     */
    private static final boolean DEFAULT_WRITE_FAILBACK_ON_VALIDATE = false;

    /**
     * Minimum interval (ms) between recovery probes to an unreachable endpoint. Gates the
     * backoff-bypass paths ({@code recoverRw}/{@code recoverRo}/RO read-path) so a down broker is
     * probed at most once per interval, whatever the session count.
     */
    public static final int DEFAULT_RT_RECOVERY_PROBE_INTERVAL_MS = 3000;

    /** SQL strings kept in the JVM-wide READ/WRITE classification cache; {@code 0} disables it. */
    private static final int DEFAULT_SQL_CLASSIFY_CACHE_MAX_ENTRIES = 8192;

    // Pinned to the DEFAULT_* values. Assigned in the constructor, not inline: an inline constant
    // initializer is folded into every getter, and tests that exercise the non-default behavior
    // set these fields by reflection.
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

    private final ResolvedRoleTopology resolvedTopology;
    private final ReadWeight readWeight;
    private final String databaseName;
    private final String urlVariant;
    private final String urlUser;
    private final String urlPassword;
    private final Map<String, String> propagatedOptions;
    // Metrics export settings; DISABLED unless the URL enabled it.
    private final MetricsConfig metricsConfig;
    private final LbLogConfig lbLogConfig;

    /**
     * Build settings from a parsed URI URL. Roles/ports come from {@link ParsedRoleTopology}/{@link
     * ResolvedRoleTopology}, weights from {@link ReadWeight}.
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
                LoadBalanceOptionValidator.validate(parsed.getOptions()).getRetainedOptions();
        JdbcEndpointConnSpecFactory.checkPropagatable(propagated);
        final MetricsConfig metrics = MetricsConfig.parse(parsed.getOptions());
        final LbLogConfig lbLog = LbLogConfig.parse(parsed.getOptions());

        return new LoadBalanceSettings(
                resolved,
                weights,
                parsed.getDb(),
                parsed.getVariant(),
                parsed.getUser(),
                parsed.getPassword(),
                propagated,
                metrics,
                lbLog);
    }

    private LoadBalanceSettings(
            ResolvedRoleTopology resolvedTopology,
            ReadWeight readWeight,
            String databaseName,
            String urlVariant,
            String urlUser,
            String urlPassword,
            Map<String, String> propagatedOptions,
            MetricsConfig metricsConfig,
            LbLogConfig lbLogConfig) {
        this.roPhysicalFailoverToRw = DEFAULT_RO_PHYSICAL_FAILOVER_TO_RW;
        this.sqlClassifyCacheMaxEntries = DEFAULT_SQL_CLASSIFY_CACHE_MAX_ENTRIES;
        this.rtFailoverEnabled = DEFAULT_RT_FAILOVER_ENABLED;
        this.rtFailoverRetryOnce = DEFAULT_RT_FAILOVER_RETRY_ONCE;
        this.rtFailoverMaxAttempts = DEFAULT_RT_FAILOVER_MAX_ATTEMPTS;
        this.readFailbackEnabled = DEFAULT_READ_FAILBACK_ENABLED;
        this.readFailbackProbeIntervalMs = DEFAULT_READ_FAILBACK_PROBE_INTERVAL_MS;
        this.writeFailbackEnabled = DEFAULT_WRITE_FAILBACK_ENABLED;
        this.writeFailbackProbeIntervalMs = DEFAULT_WRITE_FAILBACK_PROBE_INTERVAL_MS;
        this.writeFailbackOnValidate = DEFAULT_WRITE_FAILBACK_ON_VALIDATE;
        this.recoveryProbeIntervalMs = DEFAULT_RT_RECOVERY_PROBE_INTERVAL_MS;
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
        this.metricsConfig = metricsConfig != null ? metricsConfig : MetricsConfig.DISABLED;
        this.lbLogConfig = lbLogConfig != null ? lbLogConfig : LbLogConfig.DEFAULTS;
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

    public JdbcPhyConnSpec buildPhysicalJdbcSpec(Properties clientInfo, Endpoint endpoint)
            throws SQLException {
        return JdbcEndpointConnSpecFactory.forEndpoint(this, clientInfo, endpoint);
    }

    /**
     * The {@link EndpointTopology} used by routing and failover, derived from the role topology.
     *
     * @return the endpoint topology
     */
    public EndpointTopology buildEndpointTopology() {
        return EndpointTopology.fromResolved(resolvedTopology);
    }

    /**
     * Role topology with resolved RW/RO/SO endpoints.
     *
     * @return the resolved role topology
     */
    public ResolvedRoleTopology getResolvedTopology() {
        return resolvedTopology;
    }

    /**
     * Role-based read weights.
     *
     * @return the read weights
     */
    public ReadWeight getReadWeight() {
        return readWeight;
    }

    /**
     * Database name from the URL.
     *
     * @return the database name
     */
    public String getDatabaseName() {
        return databaseName;
    }

    /**
     * Scheme variant ({@code ""}/{@code -oracle}/{@code -mysql}) from the URL.
     *
     * @return the scheme variant
     */
    String getUrlVariant() {
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
    String getUrlPassword() {
        return urlPassword;
    }

    /**
     * Common options propagated to each physical connection.
     *
     * @return the propagated options
     */
    Map<String, String> getPropagatedOptions() {
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
     * LB log-record settings; {@link LbLogConfig#DEFAULTS} outside a loadbalance URL.
     *
     * @return the LB log-record settings
     */
    public LbLogConfig getLbLogConfig() {
        return lbLogConfig;
    }
}
