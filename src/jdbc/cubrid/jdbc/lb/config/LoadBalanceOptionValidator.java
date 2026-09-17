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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Mode-based option allow-list. An option that does not apply to the URL's mode is <b>ignored with
 * a WARN</b> (the pgJDBC / Connector-J convention), never a hard failure.
 *
 * <ul>
 *   <li>loadbalance-only ({@code rwPort}/{@code roPort}/{@code soPort}/{@code readWeight}, plus the
 *       {@code metrics*} and {@code lbLog*} options) &rarr; ignored in classic / single-node mode;
 *   <li>classic-only ({@code altHosts}/{@code loadBalance}/{@code useLazyConnection}) &rarr;
 *       ignored in loadbalance mode;
 *   <li>keys nothing reads any more ({@code hostBalance}/{@code weights}/{@code readWeights}/{@code
 *       hostWeights}, and every {@code cubrid.lb.*} key) &rarr; ignored in both modes, so they are
 *       not propagated onto physical broker URLs;
 *   <li>common and unknown keys are kept, so they reach every physical connection. A bad
 *       <em>value</em> still fails later; this validator only checks mode applicability.
 * </ul>
 */
public final class LoadBalanceOptionValidator {
    private static final Logger LOGGER =
            Logger.getLogger(LoadBalanceOptionValidator.class.getName());

    // Single source of truth for LB topology option keys, also used by
    // JdbcEndpointConnSpecFactory.isLbTopologyOption so the two filters cannot drift apart.
    static final String[] LOADBALANCE_ONLY = {
        "rwPort",
        "roPort",
        "soPort",
        "readWeight",
        // Metrics export options: loadbalance-only, and listing them here also keeps them off
        // physical broker URLs.
        MetricsConfig.OPT_ENABLED,
        MetricsConfig.OPT_EXPORT,
        MetricsConfig.OPT_PROMETHEUS_PORT,
        MetricsConfig.OPT_CSV_PATH,
        MetricsConfig.OPT_INTERVAL_SEC,
        MetricsConfig.OPT_CSV_MAX_SIZE_MB,
        MetricsConfig.OPT_CSV_MAX_FILES,
        // LB file-logging options: same treatment. They configure the LB layer only.
        LbLogConfig.OPT_FILE,
        LbLogConfig.OPT_LEVEL,
        LbLogConfig.OPT_MAX_SIZE_MB,
        LbLogConfig.OPT_MAX_FILES,
        LbLogConfig.OPT_TO_CONSOLE,
        LbLogConfig.OPT_THREAD,
        LbLogConfig.OPT_SQL,
        LbLogConfig.OPT_SQL_MAX_LEN,
        LbLogConfig.OPT_DIST_INTERVAL_SEC
    };
    private static final String[] CLASSIC_ONLY = {"altHosts", "loadBalance", "useLazyConnection"};
    // Distribution keys removed in the role-based readWeight redesign (spec 6.3). Nothing reads
    // them, so ignore (WARN) in both modes instead of letting them pass as unknown keys.
    private static final String[] REMOVED_KEYS = {
        "hostBalance", "weights", "readWeights", "hostWeights"
    };
    private static final String CUBRID_LB_PREFIX = "cubrid.lb.";

    private LoadBalanceOptionValidator() {}

    /**
     * Classify options for the given mode, retaining applicable ones and ignoring (with a WARN) any
     * that do not apply.
     *
     * @param loadBalanceMode true for a {@code loadbalance} URL, false for classic / single-node
     * @param options the raw option map to classify; may be {@code null}
     * @return the retained options and the ignored keys
     */
    public static Result validate(
            final boolean loadBalanceMode, final Map<String, String> options) {
        final Map<String, String> retained = new LinkedHashMap<String, String>();
        final List<String> ignoredKeys = new ArrayList<String>();

        if (options != null) {
            for (Map.Entry<String, String> e : options.entrySet()) {
                final String key = e.getKey();

                if (isCubridLbConfigKey(key)) {
                    // cubrid.lb.* configured a config-file-era feature; nothing reads these keys
                    // now. Ignore (WARN) instead of propagating them onto physical broker URLs.
                    ignore(key, ignoredKeys, "is not read (loadbalance uses fixed defaults)");
                } else if (OptionKeys.containsIgnoreCase(REMOVED_KEYS, key)) {
                    ignore(key, ignoredKeys, "was removed in the role-based readWeight redesign");
                } else if (loadBalanceMode && OptionKeys.containsIgnoreCase(CLASSIC_ONLY, key)) {
                    ignore(key, ignoredKeys, "is not applicable in loadbalance mode");
                } else if (!loadBalanceMode
                        && OptionKeys.containsIgnoreCase(LOADBALANCE_ONLY, key)) {
                    ignore(key, ignoredKeys, "is only applicable in loadbalance mode");
                } else {
                    retained.put(key, e.getValue());
                }
            }
        }

        return new Result(retained, ignoredKeys);
    }

    private static void ignore(
            final String key, final List<String> ignoredKeys, final String reason) {
        ignoredKeys.add(key);
        LOGGER.warning("option '" + key + "' " + reason + "; ignored");
    }

    private static boolean isCubridLbConfigKey(final String key) {
        return key != null
                && key.regionMatches(true, 0, CUBRID_LB_PREFIX, 0, CUBRID_LB_PREFIX.length());
    }

    /** Outcome of {@link #validate}: options to keep, and the keys that were ignored. */
    public static final class Result {
        private final Map<String, String> retainedOptions;
        private final List<String> ignoredKeys;

        Result(final Map<String, String> retainedOptions, final List<String> ignoredKeys) {
            this.retainedOptions = Collections.unmodifiableMap(retainedOptions);
            this.ignoredKeys = Collections.unmodifiableList(ignoredKeys);
        }

        /**
         * Options applicable to the mode (common + mode-appropriate), for propagation.
         *
         * @return the retained options, as an unmodifiable map
         */
        public Map<String, String> getRetainedOptions() {
            return retainedOptions;
        }

        /**
         * Keys ignored as inapplicable or no longer read; each is also logged at WARN.
         *
         * @return the ignored keys, as an unmodifiable list
         */
        public List<String> getIgnoredKeys() {
            return ignoredKeys;
        }

        public boolean hasIgnored() {
            return !ignoredKeys.isEmpty();
        }
    }
}
