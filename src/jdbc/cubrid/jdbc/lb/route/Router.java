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

package cubrid.jdbc.lb.route;

import cubrid.jdbc.lb.sql.HintParser;
import cubrid.jdbc.lb.sql.HintSet;
import cubrid.jdbc.lb.state.SessionRoutingState;

/**
 * SQL routing with fixed precedence: {@code Transaction > Hint > Default}.
 *
 * <p>A target hint decides the route, except while a transaction is active: then every target hint
 * is overridden to RW, because a transaction must not leave the bound RW connection. That makes
 * {@code TO_RO} invalid inside a transaction; for {@code TO_RW} the override changes nothing. With
 * no hint, an active transaction pins the statement to RW, otherwise the default advisor
 * (READ/WRITE classification) decides.
 *
 * <p>Routing is stateless: {@link SessionRoutingState} is only read, and the input SQL is returned
 * verbatim on the decision.
 */
public final class Router {
    public enum RouteTarget {
        TO_READ_WRITE,
        TO_READ_ONLY
    }

    public static final class RouteDecision {
        private final RouteTarget target;
        private final String sql;

        public RouteDecision(RouteTarget target, String sql) {
            if (target == null) {
                throw new IllegalArgumentException("RouteTarget must not be null");
            }
            this.target = target;
            this.sql = sql;
        }

        public RouteTarget getTarget() {
            return target;
        }

        public String getSql() {
            return sql;
        }
    }

    public interface DefaultRouteAdvisor {
        RouteTarget defaultTarget(String sql);
    }

    private Router() {}

    public static RouteDecision decide(
            final String sql,
            final SessionRoutingState session,
            final DefaultRouteAdvisor defaultAdvisor) {
        if (session == null) {
            throw new IllegalArgumentException("SessionRoutingState must not be null");
        }
        if (defaultAdvisor == null) {
            throw new IllegalArgumentException("DefaultRouteAdvisor must not be null");
        }

        final String effectiveSql = sql == null ? "" : sql;

        final HintSet hints = HintParser.parse(effectiveSql);

        if (hints.hasTargetHint()) {
            final RouteTarget mapped = mapTargetHint(hints.getTargetHint());
            // An active transaction overrides every target hint: the statement must not leave the
            // bound RW connection. Testing the transaction rather than the target keeps this
            // fail-closed if another target is ever added; for TO_RW the override is a no-op.
            if (session.isTransactionActive()) {
                return new RouteDecision(RouteTarget.TO_READ_WRITE, effectiveSql);
            }
            return new RouteDecision(mapped, effectiveSql);
        }

        if (session.shouldRouteToRw()) {
            return new RouteDecision(RouteTarget.TO_READ_WRITE, effectiveSql);
        }

        return new RouteDecision(defaultAdvisor.defaultTarget(effectiveSql), effectiveSql);
    }

    /**
     * Same precedence as {@link #decide(String, SessionRoutingState, DefaultRouteAdvisor)}, but
     * from values the caller already parsed and cached. A hot execute path (e.g. {@code
     * LBPreparedStatement}) can then skip {@link HintParser} and the classifier while the routing
     * rule stays defined in one place.
     *
     * @param targetFromHint target-hint already mapped via {@link #mapTargetHint}, or {@code null}
     * @param defaultTarget target to use when there is no hint and no active transaction
     * @param session the session routing state consulted for an active transaction
     * @return the resolved route target
     */
    public static RouteTarget decide(
            final RouteTarget targetFromHint,
            final RouteTarget defaultTarget,
            final SessionRoutingState session) {
        if (session == null) {
            throw new IllegalArgumentException("SessionRoutingState must not be null");
        }

        if (targetFromHint != null) {
            if (session.isTransactionActive()) {
                return RouteTarget.TO_READ_WRITE;
            }
            return targetFromHint;
        }

        if (session.shouldRouteToRw()) {
            return RouteTarget.TO_READ_WRITE;
        }

        return defaultTarget;
    }

    public static RouteTarget mapTargetHint(final HintSet.TargetHint hint) {
        switch (hint) {
            case TO_RW:
                return RouteTarget.TO_READ_WRITE;
            case TO_RO:
                return RouteTarget.TO_READ_ONLY;
            default:
                throw new IllegalArgumentException("Unsupported target hint: " + hint);
        }
    }
}
