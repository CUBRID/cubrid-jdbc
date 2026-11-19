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

import static org.junit.Assert.assertEquals;

import cubrid.jdbc.lb.route.Router.RouteTarget;
import cubrid.jdbc.lb.sql.HintSet.TargetHint;
import cubrid.jdbc.lb.sql.SqlClassification;
import cubrid.jdbc.lb.sql.SqlClassifier;
import cubrid.jdbc.lb.state.SessionRoutingState;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import org.junit.Test;

/**
 * The leg of a statement for every combination of what the decision reads, against the precedence
 * table: the first row that holds decides. A change of routing is a change of {@link #expected}.
 */
public final class RouterDecisionTableTest {
    private static final RouteTarget RW = RouteTarget.TO_READ_WRITE;
    private static final RouteTarget RO = RouteTarget.TO_READ_ONLY;
    private static final TargetHint[] HINTS = {null, TargetHint.TO_RW, TargetHint.TO_RO};
    private static final boolean[] BOTH = {false, true};

    /** What one statement and its session look like to the decision. */
    private static final class Input {
        TargetHint hint;
        boolean changesSession;
        SqlClassification classification;
        boolean updatable;
        boolean transactionActive;
        boolean readsPinned;
        boolean readsRwVariable;
        boolean previousOnReadLeg;
        boolean rwTransactionOpen;

        @Override
        public String toString() {
            return "hint="
                    + hint
                    + " changesSession="
                    + changesSession
                    + " "
                    + classification
                    + " updatable="
                    + updatable
                    + " tx="
                    + transactionActive
                    + " pinned="
                    + readsPinned
                    + " rwVariable="
                    + readsRwVariable
                    + " previousOnReadLeg="
                    + previousOnReadLeg
                    + " rwTxOpen="
                    + rwTransactionOpen;
        }
    }

    private static RouteTarget expected(final Input in) {
        if (in.hint != null) {
            if (in.transactionActive) {
                return RW; // a transaction does not leave its connection
            }
            if (in.hint == TargetHint.TO_RW) {
                return RW;
            }
            if (in.changesSession) {
                return RW; // core runs the whole string in its one session
            }
            if (in.rwTransactionOpen) {
                return RW;
            }
            return RO;
        }
        if (in.transactionActive || in.readsPinned || in.updatable) {
            return RW;
        }
        if (in.classification == SqlClassification.WRITE
                || in.classification == SqlClassification.UNKNOWN) {
            return RW;
        }
        if (in.classification == SqlClassification.READ_ON_PREVIOUS_LEG && !in.previousOnReadLeg) {
            return RW;
        }
        if (in.readsRwVariable) {
            return RW;
        }
        // A read that reports on the statement before it reads no data the transaction holds.
        if (in.rwTransactionOpen && in.classification == SqlClassification.READ) {
            return RW;
        }
        return RO;
    }

    @Test
    public void everyCombinationFollowsTheTable() {
        int combinations = 0;
        for (TargetHint hint : HINTS) {
            for (boolean changesSession : BOTH) {
                for (SqlClassification classification : SqlClassification.values()) {
                    for (int flags = 0; flags < 64; flags++) {
                        final Input in = new Input();
                        in.hint = hint;
                        in.changesSession = changesSession;
                        in.classification = classification;
                        in.updatable = (flags & 1) != 0;
                        in.transactionActive = (flags & 2) != 0;
                        in.readsPinned = (flags & 4) != 0;
                        in.readsRwVariable = (flags & 8) != 0;
                        in.previousOnReadLeg = (flags & 16) != 0;
                        in.rwTransactionOpen = (flags & 32) != 0;

                        final Router.RouteDecision decision = decide(in);
                        assertEquals(in.toString(), expected(in), decision.getTarget());
                        assertEquals(in.toString(), rule(in), decision.getRule());
                        combinations++;
                    }
                }
            }
        }
        assertEquals(1536, combinations);
    }

    /** The line of the table that decides. */
    private static RouteRule rule(final Input in) {
        if (in.hint != null) {
            if (in.transactionActive) {
                return RouteRule.TRANSACTION;
            }
            if (in.hint == TargetHint.TO_RW) {
                return RouteRule.HINT;
            }
            if (in.changesSession) {
                return RouteRule.SESSION_SET_IN_HINTED_STRING;
            }
            return in.rwTransactionOpen ? RouteRule.RW_TRANSACTION_OPEN : RouteRule.HINT;
        }
        if (in.transactionActive) {
            return RouteRule.TRANSACTION;
        }
        if (in.readsPinned) {
            return RouteRule.READ_PIN;
        }
        if (in.updatable) {
            return RouteRule.UPDATABLE_RESULT;
        }
        if (in.classification == SqlClassification.WRITE
                || in.classification == SqlClassification.UNKNOWN) {
            return RouteRule.CLASSIFICATION;
        }
        final boolean previous = in.classification == SqlClassification.READ_ON_PREVIOUS_LEG;
        if (previous && !in.previousOnReadLeg) {
            return RouteRule.PREVIOUS_STATEMENT;
        }
        if (in.readsRwVariable) {
            return RouteRule.SESSION_VARIABLE;
        }
        if (in.rwTransactionOpen && !previous) {
            return RouteRule.RW_TRANSACTION_OPEN;
        }
        return previous ? RouteRule.PREVIOUS_STATEMENT : RouteRule.CLASSIFICATION;
    }

    private static Router.RouteDecision decide(final Input in) {
        final SessionRoutingState session = new SessionRoutingState();
        if (in.transactionActive) {
            session.setAutoCommit(false);
        }
        if (in.readsPinned) {
            session.pinReadsToRw(Collections.singleton("TEST"));
        }
        session.addSessionVariables(Collections.singleton("V"));
        session.setLastStatementOnReadLeg(in.previousOnReadLeg);

        final String sql =
                "SELECT "
                        + (in.hint == null ? "" : "/*+ " + in.hint + " */ ")
                        + (in.readsRwVariable ? "v" : "1")
                        + " FROM t"
                        + (in.changesSession ? "; SET NAMES utf8" : "");
        return Router.decide(
                RoutedSql.of(
                        sql,
                        new SqlClassifier() {
                            public SqlClassification classify(final String text) {
                                return in.classification;
                            }
                        }),
                in.updatable,
                session,
                new BooleanSupplier() {
                    public boolean getAsBoolean() {
                        return in.rwTransactionOpen;
                    }
                });
    }
}
