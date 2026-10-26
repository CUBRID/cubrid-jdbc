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

/**
 * The package-private members of {@code cubrid.jdbc.lb.config} that tests in other packages use. It
 * lives in the test tree, so the driver jar does not carry it.
 */
public final class ConfigTestAccess {
    private ConfigTestAccess() {}

    public static final String OPT_ENABLED = MetricsConfig.OPT_ENABLED;

    public static final String OPT_EXPORT = MetricsConfig.OPT_EXPORT;

    public static final String EXPORT_CSV = MetricsConfig.EXPORT_CSV;

    public static final String OPT_INTERVAL_SEC = MetricsConfig.OPT_INTERVAL_SEC;

    public static final String OPT_PROMETHEUS_BIND = MetricsConfig.OPT_PROMETHEUS_BIND;

    public static final String EXPORT_PROMETHEUS = MetricsConfig.EXPORT_PROMETHEUS;

    public static final String OPT_PROMETHEUS_PORT = MetricsConfig.OPT_PROMETHEUS_PORT;

    public static int getPort(final Endpoint endpoint) {
        return endpoint.getPort();
    }

    public static boolean hasRoEndpoints(final EndpointTopology topology) {
        return topology.hasRoEndpoints();
    }

    public static String getUrlPassword(final LoadBalanceSettings settings) {
        return settings.getUrlPassword();
    }

    public static MetricsConfig parseMetricsConfig(final Map<String, String> options) {
        return MetricsConfig.parse(options);
    }

    public static boolean hasReadTarget(final ReadWeight weight) {
        return weight.hasReadTarget();
    }

    public static String getHost(final ResolvedRoleTopology.ResolvedNode node) {
        return node.getHost();
    }

    public static String getHost(final ResolvedRoleTopology.ResolvedReplica replica) {
        return replica.getHost();
    }
}
