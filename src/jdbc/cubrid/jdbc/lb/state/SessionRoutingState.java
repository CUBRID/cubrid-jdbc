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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-connection routing flags: autocommit, active transaction, the reasons a session SET the read
 * leg does not have keeps reads on the write leg, the variables an INTO/TO clause created on the
 * write leg, and the reads whose result had OID columns.
 *
 * <p>Thread contract: these fields are non-volatile and carry the same contract as the owning
 * {@link cubrid.jdbc.lb.LoadBalanceConnection}. A JDBC connection is not meant to be used
 * concurrently; a pool handing one between threads establishes the happens-before edge that
 * publishes these fields.
 */
public class SessionRoutingState {
    /** How many reads {@link #rememberOidRead} keeps. */
    static final int OID_READS_KEPT = 100;

    private boolean autoCommit = true;
    private boolean transactionActive = false;
    private Set<String> readPinReasons = Collections.emptySet();
    private Set<String> sessionVariables = Collections.emptySet();
    private Set<String> preparedCallVariables = Collections.emptySet();
    private boolean lastStatementOnReadLeg = false;
    // By SQL text, least recently used first. Synchronized, unlike the fields above: a lookup
    // reorders the map, and two at once would break it where a stale flag only misroutes.
    private final Map<String, Boolean> oidReads =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                        private static final long serialVersionUID = 1L;

                        @Override
                        protected boolean removeEldestEntry(
                                final Map.Entry<String, Boolean> eldest) {
                            return size() > OID_READS_KEPT;
                        }
                    });

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
     * Keeps this session's reads on the write leg while any reason holds: a SET ran there that the
     * read leg does not have. Unlike a transaction, an explicit TO_RO hint still wins.
     *
     * @param reasons what the read leg lacks, as named by {@code SessionSetStatement}
     * @return the reasons this call added
     */
    public Set<String> pinReadsToRw(final Collection<String> reasons) {
        final Set<String> added = new LinkedHashSet<String>(reasons);
        added.removeAll(readPinReasons);
        if (!added.isEmpty()) {
            final Set<String> merged = new LinkedHashSet<String>(readPinReasons);
            merged.addAll(added);
            this.readPinReasons = Collections.unmodifiableSet(merged);
        }
        return added;
    }

    /**
     * Drops reasons a later statement made untrue, for example tracing turned off again.
     *
     * @param reasons the reasons that no longer hold
     * @return whether this call released the last reason
     */
    public boolean unpinReads(final Collection<String> reasons) {
        if (readPinReasons.isEmpty() || Collections.disjoint(readPinReasons, reasons)) {
            return false;
        }
        final Set<String> remaining = new LinkedHashSet<String>(readPinReasons);
        remaining.removeAll(reasons);
        this.readPinReasons = Collections.unmodifiableSet(remaining);
        return remaining.isEmpty();
    }

    public boolean isReadsPinnedToRw() {
        return !readPinReasons.isEmpty();
    }

    public Set<String> getReadPinReasons() {
        return readPinReasons;
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
     * Records variables a {@code CALL} prepared with {@code prepareCall} assigns. LB does not see
     * that statement run, so it may assign them again after a {@code DROP VARIABLE}: they are kept
     * until the connection closes.
     *
     * @param upperNames upper-cased variable names
     */
    public void addPreparedCallVariables(final Set<String> upperNames) {
        addSessionVariables(upperNames);
        if (upperNames.isEmpty() || preparedCallVariables.containsAll(upperNames)) {
            return;
        }
        final Set<String> merged = new HashSet<String>(preparedCallVariables);
        merged.addAll(upperNames);
        this.preparedCallVariables = Collections.unmodifiableSet(merged);
    }

    /**
     * Forgets variables {@code DROP VARIABLE} removed from the write leg, so reads naming them are
     * routed as usual again, except those of a prepared {@code CALL}.
     *
     * @param upperNames upper-cased variable names
     */
    public void removeSessionVariables(final Set<String> upperNames) {
        if (upperNames.isEmpty() || Collections.disjoint(sessionVariables, upperNames)) {
            return;
        }
        final Set<String> remaining = new HashSet<String>(sessionVariables);
        remaining.removeAll(upperNames);
        remaining.addAll(preparedCallVariables);
        this.sessionVariables = Collections.unmodifiableSet(remaining);
    }

    /**
     * Records a read whose result had OID columns. An OID names a row on the node that returned it,
     * and every OID call runs on the write leg, so the statement runs there from now on. The last
     * {@value #OID_READS_KEPT} are kept; one forgotten is found again by its result.
     *
     * @param sql the statement as the application issued it
     */
    public void rememberOidRead(final String sql) {
        oidReads.put(sql, Boolean.TRUE);
    }

    public boolean isOidRead(final String sql) {
        return !oidReads.isEmpty() && oidReads.get(sql) != null;
    }

    /**
     * Variables this session created on the write leg.
     *
     * @return their upper-cased names
     */
    public Set<String> getSessionVariables() {
        return sessionVariables;
    }

    /**
     * Records the leg the last statement that succeeded ran on: {@code ROW_COUNT()} reports on the
     * statement before it in its own server session, so it is sent where that statement ran.
     *
     * @param onReadLeg whether that statement ran on the read leg
     */
    public void setLastStatementOnReadLeg(final boolean onReadLeg) {
        this.lastStatementOnReadLeg = onReadLeg;
    }

    public boolean isLastStatementOnReadLeg() {
        return lastStatementOnReadLeg;
    }

    public void reset() {
        this.autoCommit = true;
        this.transactionActive = false;
        this.readPinReasons = Collections.emptySet();
        this.sessionVariables = Collections.emptySet();
        this.preparedCallVariables = Collections.emptySet();
        this.lastStatementOnReadLeg = false;
    }
}
