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

package cubrid.jdbc.lb;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.LbLogConfig;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.failover.PhysicalRecoveryContext;
import cubrid.jdbc.lb.failover.PhysicalRecoveryResult;
import cubrid.jdbc.lb.log.LbLog;
import cubrid.jdbc.lb.metrics.RuntimeMetrics;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.sql.HintParser;
import cubrid.jdbc.lb.sql.HintSet;
import cubrid.jdbc.lb.sql.SqlClassification;
import cubrid.jdbc.lb.sql.SqlClassifier;
import cubrid.jdbc.lb.sql.SqlLiteralMasker;
import cubrid.jdbc.lb.state.SessionRoutingState;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Routing metrics for one logical connection: owns the {@link RuntimeMetrics} buffer and builds
 * every routing/failover/failback event line, so the event vocabulary and format live in one place
 * instead of inside {@link LoadBalanceConnection}.
 */
final class RoutingMetricsRecorder {

    private static final Logger LOGGER = Logger.getLogger(RoutingMetricsRecorder.class.getName());

    private final long connectionId;
    private final LoadBalanceSettings config;
    private final SessionRoutingState sessionState;
    private final SqlClassifier sqlClassifier;
    private final RuntimeMetrics metrics = new RuntimeMetrics();

    RoutingMetricsRecorder(
            final long connectionId,
            final LoadBalanceSettings config,
            final SessionRoutingState sessionState,
            final SqlClassifier sqlClassifier) {
        this.connectionId = connectionId;
        this.config = config;
        this.sessionState = sessionState;
        this.sqlClassifier = sqlClassifier;
    }

    RuntimeMetrics runtimeMetrics() {
        return metrics;
    }

    /**
     * Builds a routing-metrics event line. {@code connectionId} is emitted first and {@code
     * distributionMode} is appended by {@link #build()}, so the shared format lives in one place.
     */
    private final class MetricEvent {
        private final StringBuilder sb = new StringBuilder("connectionId=");

        MetricEvent() {
            sb.append(connectionId);
        }

        MetricEvent field(final String key, final Object value) {
            sb.append(',').append(key).append('=').append(value);
            return this;
        }

        String build() {
            sb.append(",distributionMode=").append(config.getDistributionMode());
            return sb.toString();
        }
    }

    void recordFailoverRecovery(
            final PhysicalRecoveryContext ctx, final PhysicalRecoveryResult result) {
        if (!metrics.isEnabled()) {
            return;
        }
        String fallbackReason = result.getFallbackReason();
        if (fallbackReason == null || fallbackReason.length() == 0) {
            fallbackReason = FallbackReason.RUNTIME_FAILOVER;
        }

        if (!FallbackReason.RUNTIME_FAILOVER.equals(fallbackReason)) {
            metrics.incRwFallbackCount();
        }

        metrics.incUnclassifiedHit(result.getBoundEndpoint().getId());

        String event =
                new MetricEvent()
                        .field("event", "RUNTIME_FAILOVER")
                        .field("failedRole", ctx.getFailedLeg())
                        .field("failedEndpointId", ctx.getFailedEndpoint().getId())
                        .field("endpointId", result.getBoundEndpoint().getId())
                        .field("fallbackReason", fallbackReason)
                        .field("txActive", sessionState.isTransactionActive())
                        .build();

        metrics.recordEvent(event);
    }

    void recordRoFailback(final Endpoint restoredRo) {
        if (!metrics.isEnabled()) {
            return;
        }
        metrics.incUnclassifiedHit(restoredRo.getId());

        String event =
                new MetricEvent()
                        .field("event", "RO_PHYSICAL_RESTORE")
                        .field("endpointId", restoredRo.getId())
                        .build();

        metrics.recordEvent(event);
    }

    void recordRwFailback(final Endpoint restoredRw) {
        if (!metrics.isEnabled()) {
            return;
        }
        metrics.incUnclassifiedHit(restoredRw.getId());

        String event =
                new MetricEvent()
                        .field("event", "RW_PHYSICAL_RESTORE")
                        .field("endpointId", restoredRw.getId())
                        .build();

        metrics.recordEvent(event);
    }

    /**
     * Whether a routing decision fell back off its selected endpoint.
     *
     * <p>{@code null} means no reason was supplied, which is not a fallback - but a plain {@code
     * !FallbackReason.NONE.equals(reason)} test reads it as one, and any call site that left the
     * reason unset would then inflate {@code rwFallbackCount}. Operators read that counter before
     * re-tuning readWeight, so a wrong fallback rate sends them tuning the wrong node.
     */
    private static boolean isFallback(final String fallbackReason) {
        return fallbackReason != null && !FallbackReason.NONE.equals(fallbackReason);
    }

    void recordStatementRoute(
            final String sql,
            final Router.RouteTarget routeTarget,
            final Endpoint endpoint,
            final String fallbackReason) {
        if (!metrics.isEnabled()) {
            return;
        }
        if (routeTarget == Router.RouteTarget.TO_READ_ONLY) {
            metrics.incRoSelectionCount();
        }

        boolean fallback = isFallback(fallbackReason);
        if (fallback) {
            metrics.incRwFallbackCount();
        }

        SqlClassification classification = sqlClassifier.classify(sql);
        metrics.recordExec(endpoint.getId(), classification, fallback);

        String event =
                new MetricEvent()
                        .field("sqlType", classification.name())
                        .field("routeTarget", routeTarget)
                        .field("endpointId", endpoint.getId())
                        .field("fallbackReason", fallbackReason)
                        .field("txActive", sessionState.isTransactionActive())
                        .build();

        metrics.recordEvent(event);
    }

    /**
     * Records where one statement was routed and, above all, <b>what decided it</b>.
     *
     * <p>The endpoint alone does not answer the question that gets asked. A SELECT arriving at the
     * write node is correct in at least four situations, each calling for a different response: a
     * hint asked for it; a manual-commit transaction has the session pinned; the classifier read
     * the statement as a write (a LOB function, a serial increment, an unrecognised routine); or
     * the read leg had fallen back onto the RW connection. The {@code by=} token names which one,
     * following the precedence in {@link Router#decide}.
     *
     * <p>Gated on FINE alone, deliberately not on {@code metricsEnabled}: the two answer different
     * needs. The gate is checked before any string work, so with FINE off the record costs one
     * boolean test, which matters because this runs on every statement.
     *
     * <p>The hint is re-parsed here rather than threaded down from the statement layer. It is a
     * pure function of the SQL, so the recomputation cannot disagree with the decision that was
     * made, and it keeps the provider interfaces unchanged. Nothing is recomputed unless FINE is
     * on.
     *
     * @param sql the statement as issued
     * @param routeTarget the target the router resolved
     * @param endpoint the endpoint the statement actually ran on
     * @param fallbackReason why the intended target was not used, or {@code null}
     */
    void logRoute(
            final String sql,
            final Router.RouteTarget routeTarget,
            final Endpoint endpoint,
            final String fallbackReason) {
        if (!LOGGER.isLoggable(Level.FINE)) {
            return;
        }

        final HintSet.TargetHint hint = HintParser.parse(sql).getTargetHint();
        final SqlClassification classification = sqlClassifier.classify(sql);

        StringBuilder b = new StringBuilder(160);
        b.append("LB ROUTE: ")
                .append(shortTarget(routeTarget))
                .append('@')
                .append(endpoint == null ? "?" : endpoint.getId())
                .append(" by=")
                .append(decidedBy(hint, classification));

        if (isFallback(fallbackReason)) {
            b.append(" fallback=").append(fallbackReason);
        }

        final LbLogConfig logConfig = config.getLbLogConfig();
        final String sqlMode = logConfig.getSqlMode();
        if (!LbLogConfig.SQL_OFF.equals(sqlMode)) {
            b.append(" sql=")
                    .append(
                            LbLogConfig.SQL_RAW.equals(sqlMode)
                                    ? SqlLiteralMasker.raw(sql, logConfig.getSqlMaxLen())
                                    : SqlLiteralMasker.mask(sql, logConfig.getSqlMaxLen()));
        }

        LbLog.fine(LOGGER, LbLog.conn(connectionId), b.toString());
    }

    /**
     * The reason token, in {@link Router#decide}'s own precedence order: a hint wins unless an
     * active transaction overrides it, then the transaction pin, then the classification.
     */
    private String decidedBy(
            final HintSet.TargetHint hint, final SqlClassification classification) {
        if (hint != null) {
            // A read hint inside a live transaction is overridden, so the pin is the real reason.
            if (sessionState.isTransactionActive() && HintSet.TargetHint.TO_RO == hint) {
                return "txPin(hint=" + hint + ")";
            }
            return "hint(" + hint + ")";
        }
        if (sessionState.shouldRouteToRw()) {
            return "txPin(classify=" + classification.name() + ")";
        }
        return "classify(" + classification.name() + ")";
    }

    private static String shortTarget(final Router.RouteTarget target) {
        return Router.RouteTarget.TO_READ_ONLY == target ? "RO" : "RW";
    }

    void recordCommandRoute(
            final String commandName,
            final Router.RouteTarget routeTarget,
            final Endpoint endpoint,
            final String fallbackReason) {
        // The same short-circuit the SQL path has. The counters and recordEvent each check
        // isEnabled() themselves, so nothing was mis-counted, but the event string was still built
        // field by field and then dropped on every LB command of every session.
        if (!metrics.isEnabled()) {
            return;
        }
        if (endpoint == null) {
            return;
        }

        if (routeTarget == Router.RouteTarget.TO_READ_ONLY) {
            metrics.incRoSelectionCount();
        }

        if (isFallback(fallbackReason)) {
            metrics.incRwFallbackCount();
        }
        metrics.incUnclassifiedHit(endpoint.getId());

        String event =
                new MetricEvent()
                        .field("sqlType", "COMMAND")
                        .field("command", commandName)
                        .field("routeTarget", routeTarget)
                        .field("endpointId", endpoint.getId())
                        .field("fallbackReason", fallbackReason)
                        .field("txActive", sessionState.isTransactionActive())
                        .build();
        metrics.recordEvent(event);
    }
}
