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

package cubrid.jdbc.lb.state;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-connection routing flags: autocommit, active transaction, the read pin a session SET that
 * cannot be replayed on the read leg leaves behind, and the variables an INTO/TO clause created on
 * the write leg.
 *
 * <p>Thread contract: these fields are non-volatile and carry the same contract as the owning
 * {@link cubrid.jdbc.lb.LoadBalanceConnection}. A JDBC connection is not meant to be used
 * concurrently; a pool handing one between threads establishes the happens-before edge that
 * publishes these fields.
 */
public class SessionRoutingState {
    private boolean autoCommit = true;
    private boolean transactionActive = false;
    private boolean readsPinnedToRw = false;
    private Set<String> sessionVariables = Collections.emptySet();

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) {
        boolean endingManualTxn = !this.autoCommit && autoCommit && transactionActive;
        // Set the new mode first so onTransactionBoundary() re-arms transactionActive from it: a
        // false->true switch must land at transactionActive=false.
        this.autoCommit = autoCommit;
        if (endingManualTxn) {
            onTransactionBoundary();
        } else if (!autoCommit) {
            transactionActive = true;
        }
    }

    public boolean isTransactionActive() {
        return transactionActive;
    }

    /**
     * Records that a transaction just ended (commit or rollback). Named for the boundary rather
     * than for the two calls that reach it, because it does not commit or roll anything back — it
     * only settles the routing flag the boundary leaves behind.
     */
    public void onTransactionBoundary() {
        // Manual-commit re-arms: CUBRID implicitly begins the next transaction on the following
        // statement, so a manual-commit session stays pinned to RW across commits and one logical
        // transaction never spans two physical connections. Autocommit lands at false.
        this.transactionActive = !autoCommit;
    }

    /**
     * Whether routing must send this statement to the write leg, whatever it is.
     *
     * <p>Kept separate from {@link #isTransactionActive()} although it returns the same field
     * today. They answer different questions: one is a fact about the session, the other a routing
     * decision. Routing sites read this one, so a future pin that is not a transaction changes one
     * method instead of every call site.
     *
     * @return whether the statement must be routed to the write leg
     */
    public boolean shouldRouteToRw() {
        return transactionActive;
    }

    /**
     * Keeps this session's reads on the write leg until the connection closes: a SET ran there that
     * the read leg cannot replay. Unlike a transaction, an explicit TO_RO hint still wins.
     */
    public void pinReadsToRw() {
        this.readsPinnedToRw = true;
    }

    public boolean isReadsPinnedToRw() {
        return readsPinnedToRw;
    }

    /**
     * Records variables an {@code INTO/TO} clause created on the write leg. They last as long as
     * that server session, so a read naming one is sent there until the connection closes.
     *
     * @param upperNames upper-cased variable names
     */
    public void addSessionVariables(final Set<String> upperNames) {
        if (upperNames.isEmpty() || sessionVariables.containsAll(upperNames)) {
            return;
        }
        final Set<String> merged = new HashSet<String>(sessionVariables);
        merged.addAll(upperNames);
        this.sessionVariables = Collections.unmodifiableSet(merged);
    }

    /**
     * Forgets variables {@code DROP VARIABLE} removed from the write leg, so reads naming them are
     * routed as usual again.
     *
     * @param upperNames upper-cased variable names
     */
    public void removeSessionVariables(final Set<String> upperNames) {
        if (upperNames.isEmpty() || Collections.disjoint(sessionVariables, upperNames)) {
            return;
        }
        final Set<String> remaining = new HashSet<String>(sessionVariables);
        remaining.removeAll(upperNames);
        this.sessionVariables = Collections.unmodifiableSet(remaining);
    }

    /**
     * Variables this session created on the write leg.
     *
     * @return their upper-cased names
     */
    public Set<String> getSessionVariables() {
        return sessionVariables;
    }

    public void reset() {
        this.autoCommit = true;
        this.transactionActive = false;
        this.readsPinnedToRw = false;
        this.sessionVariables = Collections.emptySet();
    }
}
