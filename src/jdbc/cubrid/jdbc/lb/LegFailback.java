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
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.EndpointConnManager;
import cubrid.jdbc.lb.route.Router.RouteTarget;
import cubrid.jdbc.lb.state.SessionRoutingState;
import cubrid.jdbc.lb.statement.LBCallableStatement;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import cubrid.jdbc.lb.statement.LBStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Moves a displaced leg back to its home once the home is reachable again. Where in a session that
 * may happen, and for which leg, is written here alone; the connection only says which point it
 * reached.
 *
 * <ul>
 *   <li>{@link #beforeStatement}: the read leg for a read, and the write leg outside a transaction,
 *       a read on its own leg included: a session that only reads would otherwise keep a CAS of the
 *       sibling RW broker until its next write.
 *   <li>{@link #beforeReadPrepare}: the read leg, so a read is not prepared on the old endpoint and
 *       again on the new one.
 *   <li>{@link #beforePrepare}: the write leg outside a transaction, before the prepare opens one
 *       that would hold it.
 *   <li>{@link #afterTransactionEnd}: the write leg. A manual-commit session is transaction-pinned
 *       even between commits, so this is the point that covers it; a pool resets a returned
 *       connection with {@code rollback()}, which lands here too.
 * </ul>
 *
 * <p>Each leg is tried at most once per probe interval, never while a result set the application
 * holds lives on the connection it would leave, and the write leg never while it reports an open
 * transaction, which core ends when the next statement completes.
 */
final class LegFailback {
    private final LoadBalanceConnection conn;
    private final LoadBalanceSettings config;
    private final SessionRoutingState sessionState;
    // System.nanoTime() of the last attempt; null before the first, as nanoTime has no "never".
    private Long lastReadAttemptNanos;
    private Long lastWriteAttemptNanos;

    LegFailback(
            final LoadBalanceConnection conn,
            final LoadBalanceSettings config,
            final SessionRoutingState sessionState) {
        this.conn = conn;
        this.config = config;
        this.sessionState = sessionState;
    }

    void beforeStatement(final RouteTarget target) {
        if (target == RouteTarget.TO_READ_ONLY) {
            tryReadLeg();
        }
        if (!sessionState.isTransactionActive()) {
            tryWriteLeg();
        }
    }

    void beforeReadPrepare() {
        tryReadLeg();
    }

    void beforePrepare() {
        if (!sessionState.isTransactionActive()) {
            tryWriteLeg();
        }
    }

    void afterTransactionEnd() {
        tryWriteLeg();
    }

    /**
     * Whether the attempt at {@code lastNanos} is less than {@code intervalMs} before {@code
     * nowNanos}. Both are {@code System.nanoTime()} readings: on the wall clock, one set back would
     * hold the failback until it caught up.
     */
    static boolean attemptedWithin(
            final Long lastNanos, final long nowNanos, final long intervalMs) {
        return lastNanos != null
                && nowNanos - lastNanos.longValue() < TimeUnit.MILLISECONDS.toNanos(intervalMs);
    }

    /** Outside a transaction only: when {@code shouldRouteToRw()} holds, reads go to RW anyway. */
    private void tryReadLeg() {
        final EndpointConnManager mgr = conn.getConnectionManager();
        if (!conn.isSessionInitialized() || sessionState.shouldRouteToRw() || mgr == null) {
            return;
        }
        if (!config.isReadFailbackEnabled()) {
            return;
        }

        final long now = System.nanoTime();
        if (attemptedWithin(lastReadAttemptNanos, now, config.getReadFailbackProbeIntervalMs())) {
            return;
        }
        if (!mgr.isReadDisplaced()) {
            lastReadAttemptNanos = Long.valueOf(now);
            return; // at home: nothing to restore, and no statement to scan for a cursor
        }
        if (hasOpenCursorOn(mgr.boundConnection(SessionLeg.RO))) {
            return; // retried on a later read, once the cursor is closed
        }
        lastReadAttemptNanos = Long.valueOf(now);

        try {
            final Endpoint restored = mgr.restoreRoIfRecovered();
            if (restored != null) {
                conn.legMovedHome(SessionLeg.RO, restored);
            }
        } catch (SQLException ignored) {
            // RO still unavailable; keep serving reads on RW and retry on a later read.
        }
    }

    private void tryWriteLeg() {
        final EndpointConnManager mgr = conn.getConnectionManager();
        if (!conn.isSessionInitialized() || mgr == null) {
            return;
        }
        if (!config.isWriteFailbackEnabled()) {
            return;
        }

        final long now = System.nanoTime();
        if (attemptedWithin(lastWriteAttemptNanos, now, config.getWriteFailbackProbeIntervalMs())) {
            return;
        }
        if (!mgr.isRwDisplaced()) {
            lastWriteAttemptNanos = Long.valueOf(now);
            return; // on the master: nothing to restore, and no statement to scan for a cursor
        }
        // Core ends the autocommit transaction an OID write or a CALL left open when the next
        // statement on that connection completes. Closing the connection first loses that work.
        if (mgr.isRwTxReportedOpen()) {
            return; // retried at a later trigger, once it has ended
        }
        if (hasOpenCursorOn(mgr.boundConnection(SessionLeg.RW))) {
            return; // retried at a later trigger, once the cursor is closed
        }
        lastWriteAttemptNanos = Long.valueOf(now);

        try {
            final Endpoint restored = mgr.restoreRwIfRecovered();
            if (restored != null) {
                conn.legMovedHome(SessionLeg.RW, restored);
            }
        } catch (SQLException ignored) {
            // Master still unavailable; keep writing on the current RW and retry at a later
            // boundary.
        }
    }

    /**
     * Whether a result set the application has not closed lives on {@code physical}. Failback
     * closes the connection it leaves, and every cursor on it with it.
     */
    private boolean hasOpenCursorOn(final Connection physical) {
        if (physical == null) {
            return false;
        }
        final List<Statement> open = conn.openStatementsSnapshot();
        for (Statement each : open) {
            Connection underCursor = null;
            if (each instanceof LBPreparedStatement) {
                underCursor = ((LBPreparedStatement) each).openCursorConnection();
            } else if (each instanceof LBStatement) {
                underCursor = ((LBStatement) each).openCursorConnection();
            } else if (each instanceof LBCallableStatement) {
                underCursor = ((LBCallableStatement) each).openCursorConnection();
            }
            if (underCursor == physical) {
                return true;
            }
        }

        return false;
    }
}
