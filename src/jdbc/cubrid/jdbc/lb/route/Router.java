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

import cubrid.jdbc.lb.sql.HintSet;
import cubrid.jdbc.lb.sql.IntoVariables;
import cubrid.jdbc.lb.sql.SqlClassification;
import cubrid.jdbc.lb.state.SessionRoutingState;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Decides the leg of one statement. {@link #decide} is the whole precedence, top to bottom, and the
 * {@link RouteRule} it returns names the line that decided.
 *
 * <p>Routing is stateless: {@link SessionRoutingState} is only read.
 */
public final class Router {
    public enum RouteTarget {
        TO_READ_WRITE,
        TO_READ_ONLY
    }

    public static final class RouteDecision {
        private final RouteTarget target;
        private final RouteRule rule;
        private final RoutedSql sql;

        private RouteDecision(final RouteTarget target, final RouteRule rule, final RoutedSql sql) {
            this.target = target;
            this.rule = rule;
            this.sql = sql;
        }

        public RouteTarget getTarget() {
            return target;
        }

        public RouteRule getRule() {
            return rule;
        }

        public RoutedSql getSql() {
            return sql;
        }
    }

    private Router() {}

    /**
     * The leg {@code sql} runs on. A transaction does not leave its RW connection, whatever the
     * hint. Outside one a hint decides, except that a {@code TO_RO} string that changes the session
     * runs on RW, as core runs it in its one session. With no hint, a session read pin, an
     * updatable result and the classification decide; a read then stays on RW to follow a variable
     * the session created there, or while core left an autocommit transaction open there, unless it
     * only reports on the statement before it.
     *
     * @param sql the statement
     * @param updatableResults whether the statement makes updatable result sets
     * @param preparedUnderReadPin whether the statement was prepared while reads were pinned: the
     *     read leg lacked a session setting then, and cannot compile it as core did
     * @param session the session routing state
     * @param rwTransactionOpen whether the write leg holds an open autocommit transaction; asked
     *     only for a statement that would otherwise leave RW
     * @return the leg and the rule that chose it
     */
    public static RouteDecision decide(
            final RoutedSql sql,
            final boolean updatableResults,
            final boolean preparedUnderReadPin,
            final SessionRoutingState session,
            final BooleanSupplier rwTransactionOpen) {
        final HintSet.TargetHint hint = sql.getHint();
        if (hint != null) {
            if (session.isTransactionActive()) {
                return rw(RouteRule.TRANSACTION, sql);
            }
            if (hint == HintSet.TargetHint.TO_RW) {
                return rw(RouteRule.HINT, sql);
            }
            if (sql.changesSession()) {
                return rw(RouteRule.SESSION_SET_IN_HINTED_STRING, sql);
            }
            if (rwTransactionOpen.getAsBoolean()) {
                return rw(RouteRule.RW_TRANSACTION_OPEN, sql);
            }
            return ro(RouteRule.HINT, sql);
        }

        if (session.shouldRouteToRw()) {
            return rw(RouteRule.TRANSACTION, sql);
        }
        if (session.isReadsPinnedToRw() || preparedUnderReadPin) {
            return rw(RouteRule.READ_PIN, sql);
        }
        if (updatableResults) {
            return rw(RouteRule.UPDATABLE_RESULT, sql);
        }

        final SqlClassification classification = sql.getClassification();
        final boolean followsPrevious = classification == SqlClassification.READ_ON_PREVIOUS_LEG;
        if (classification != SqlClassification.READ && !followsPrevious) {
            return rw(RouteRule.CLASSIFICATION, sql);
        }
        if (followsPrevious && !session.isLastStatementOnReadLeg()) {
            return rw(RouteRule.PREVIOUS_STATEMENT, sql);
        }
        final Set<String> variables = session.getSessionVariables();
        if (!variables.isEmpty() && IntoVariables.isReferencedIn(sql.getText(), variables)) {
            return rw(RouteRule.SESSION_VARIABLE, sql);
        }
        // A read that reports on the statement before it reads no data, so it has nothing to see
        // of the work that transaction holds; its own end on the read leg ends the transaction.
        if (!followsPrevious && rwTransactionOpen.getAsBoolean()) {
            return rw(RouteRule.RW_TRANSACTION_OPEN, sql);
        }
        return ro(followsPrevious ? RouteRule.PREVIOUS_STATEMENT : RouteRule.CLASSIFICATION, sql);
    }

    /**
     * The decision of a batch, which runs on RW whatever its statements, hints and session are.
     *
     * @param first the batch's first statement, which its routing record shows
     * @return RW, by the batch rule
     */
    public static RouteDecision batch(final RoutedSql first) {
        return rw(RouteRule.BATCH, first);
    }

    private static RouteDecision rw(final RouteRule rule, final RoutedSql sql) {
        return new RouteDecision(RouteTarget.TO_READ_WRITE, rule, sql);
    }

    private static RouteDecision ro(final RouteRule rule, final RoutedSql sql) {
        return new RouteDecision(RouteTarget.TO_READ_ONLY, rule, sql);
    }
}
