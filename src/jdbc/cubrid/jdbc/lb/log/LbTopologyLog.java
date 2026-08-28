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

package cubrid.jdbc.lb.log;

import cubrid.jdbc.driver.CUBRIDDriver;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.NodeRole;
import cubrid.jdbc.lb.config.ReadWeight;
import cubrid.jdbc.lb.config.ResolvedRoleTopology;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Declares, once per distinct configuration, which cluster this JVM talks to and under which
 * settings. Three records, because they answer three different questions:
 *
 * <ol>
 *   <li><b>url</b> - what the application wrote. Identifies the cluster, and shows the options as
 *       given rather than as interpreted.
 *   <li><b>resolved</b> - the endpoint list with roles, plus the readWeight target. URL shorthand
 *       ({@code host:30000:33000}) does not say which node became master and which a read endpoint,
 *       and {@code LB BIND} reports only a {@code host:port}. The weight target appears nowhere
 *       else, so an observed distribution would have nothing to be compared against.
 *   <li><b>effective</b> - the settings in force. Option-parsing warnings say what was ignored,
 *       never what was applied, so "failover did not happen" could not be told from "failover is
 *       off".
 * </ol>
 *
 * <p>This cannot go in the {@link LbFileLogging} banner, which fires once per JVM: a JVM may hold
 * several DataSources on different clusters, and all but the first URL would be lost. It cannot be
 * written per connection either, because {@code LoadBalanceSettings.fromUrl} runs on every {@code
 * getConnection} and a hundred-connection pool would repeat the three lines a hundred times. Hence
 * the once-per-configuration gate below.
 *
 * <p>The password is masked by {@link CUBRIDDriver#maskUriUrlPassword}, the function the driver
 * already uses for the URL in JDBC errors and the CAS handshake, so the URL logged here matches
 * what an operator sees elsewhere.
 */
public final class LbTopologyLog {
    private static final Logger LOGGER = Logger.getLogger(LbTopologyLog.class.getName());

    /**
     * Backstop on the seen-configuration set. Real deployments hold a handful of DataSources; a cap
     * keeps a caller that somehow varies the URL per connection from growing this without bound.
     */
    private static final int MAX_SEEN = 64;

    private static final Set<String> SEEN = new LinkedHashSet<String>();

    private LbTopologyLog() {}

    /**
     * Writes the three records for {@code config} unless an identical URL was already declared.
     *
     * <p>Never throws: this is a diagnostic aid and must not be able to fail a {@code
     * getConnection}.
     *
     * @param url the URL the application passed; ignored when {@code null}
     * @param info the connection properties, for password masking; may be {@code null}
     * @param config the settings parsed from that URL; ignored when {@code null}
     */
    public static void declare(
            final String url, final Properties info, final LoadBalanceSettings config) {
        if (url == null || config == null || !LOGGER.isLoggable(java.util.logging.Level.INFO)) {
            return;
        }

        try {
            String masked = CUBRIDDriver.maskUriUrlPassword(url, info);
            if (!claim(masked)) {
                return;
            }

            LbLog.info(LOGGER, null, "LB TOPOLOGY: url=" + masked);
            LbLog.info(LOGGER, null, "LB TOPOLOGY: " + resolved(config));
            LbLog.info(LOGGER, null, "LB TOPOLOGY: " + effectiveOptionsLine(config));
        } catch (Exception e) {
            LOGGER.warning("LB TOPOLOGY: cannot render the configuration: " + e);
        }
    }

    private static synchronized boolean claim(final String masked) {
        if (SEEN.contains(masked)) {
            return false;
        }
        if (SEEN.size() >= MAX_SEEN) {
            SEEN.clear();
        }
        SEEN.add(masked);
        return true;
    }

    /**
     * Endpoints with their roles, plus the read-weight target. A slave is rendered with both legs
     * ({@code rw} and {@code ro}), because it serves writes through one port and reads through
     * another, and a bind record naming only one would be ambiguous.
     */
    private static String resolved(final LoadBalanceSettings config) {
        StringBuilder b = new StringBuilder(200);
        b.append("db=").append(config.getDatabaseName());

        ResolvedRoleTopology topo = config.getResolvedTopology();
        if (topo == null) {
            b.append(" (no resolved topology)");
            return b.toString();
        }

        ResolvedRoleTopology.ResolvedNode master = topo.getMaster();
        if (master != null) {
            b.append(" master=")
                    .append(master.getRw().getId())
                    .append("(ro ")
                    .append(master.getRo().getId())
                    .append(')');
        }

        List<ResolvedRoleTopology.ResolvedNode> slaves = topo.getSlaves();
        b.append(" slave=[");
        for (int i = 0; i < slaves.size(); i++) {
            ResolvedRoleTopology.ResolvedNode n = slaves.get(i);
            if (i > 0) {
                b.append(' ');
            }
            b.append(n.getRw().getId()).append("(ro ").append(n.getRo().getId()).append(')');
        }
        b.append(']');

        List<ResolvedRoleTopology.ResolvedReplica> replicas = topo.getReplicas();
        b.append(" replica=[");
        for (int i = 0; i < replicas.size(); i++) {
            if (i > 0) {
                b.append(' ');
            }
            b.append(replicas.get(i).getSo().getId());
        }
        b.append(']');

        b.append(" | readWeight target ").append(weights(config.getReadWeight()));
        return b.toString();
    }

    private static String weights(final ReadWeight weights) {
        if (weights == null) {
            return "(defaults)";
        }
        StringBuilder b = new StringBuilder(48);
        NodeRole[] roles = NodeRole.values();
        for (int i = 0; i < roles.length; i++) {
            b.append(roles[i].label()).append(':').append(weights.weightOf(roles[i])).append(' ');
        }
        b.append("(total ").append(weights.total()).append(')');
        return b.toString();
    }

    /**
     * The behaviour settings in force. Only those that change what an operator sees in this log: a
     * failover that does not fire, a read leg that does not come home, a candidate not retried for
     * N ms.
     */
    private static String effectiveOptionsLine(final LoadBalanceSettings config) {
        StringBuilder b = new StringBuilder(200);
        b.append("distributionMode=").append(config.getDistributionMode());

        b.append(" rtFailover=");
        if (config.isRuntimeFailoverEnabled()) {
            b.append("on(")
                    .append(config.isRuntimeFailoverRetryOnce() ? "retryOnce, " : "noRetry, ")
                    .append("max ")
                    .append(config.getRuntimeFailoverMaxAttempts())
                    .append(')');
        } else {
            b.append("off");
        }

        b.append(" readFailback=")
                .append(
                        config.isReadFailbackEnabled()
                                ? "on(" + config.getReadFailbackProbeIntervalMs() + "ms)"
                                : "off");
        b.append(" writeFailback=")
                .append(
                        config.isWriteFailbackEnabled()
                                ? "on("
                                        + config.getWriteFailbackProbeIntervalMs()
                                        + "ms"
                                        + (config.isWriteFailbackOnValidate() ? ", onValidate" : "")
                                        + ")"
                                : "off");
        b.append(" recoveryProbe=").append(config.getRecoveryProbeIntervalMs()).append("ms");
        b.append(" roFailoverToRw=").append(config.isRoPhysicalFailoverToRw() ? "on" : "off");
        b.append(" sqlClassifyCache=")
                .append(
                        config.getSqlClassifyCacheMaxEntries() > 0
                                ? String.valueOf(config.getSqlClassifyCacheMaxEntries())
                                : "off");
        b.append(" metrics=").append(config.getMetricsConfig().isEnabled() ? "on" : "off");
        return b.toString();
    }

    /** Test hook: forgets the declared configurations so a following test starts clean. */
    static synchronized void resetForTests() {
        SEEN.clear();
    }
}
