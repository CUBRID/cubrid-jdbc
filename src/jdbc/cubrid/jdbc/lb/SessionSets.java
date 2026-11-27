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

import cubrid.jdbc.driver.CUBRIDConnection;
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.lb.connection.EndpointConnManager;
import cubrid.jdbc.lb.log.LbLog;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.route.Router.RouteTarget;
import cubrid.jdbc.lb.sql.SessionSetStatement;
import cubrid.jdbc.lb.sql.SqlFacts;
import cubrid.jdbc.lb.state.SessionRoutingState;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Logger;

/**
 * The SQL {@code SET}s a session ran, and what LB does so that every leg has them. A SET runs on
 * the leg it was routed to; the ones of the session are run on the read leg now and on every leg
 * opened later, and one the read leg cannot be given keeps reads on RW. The variables an {@code
 * INTO} created are recorded here too, as is the leg a following {@code ROW_COUNT()} reads.
 *
 * <p>The connection calls the steps from its execute path and owns nothing of this state.
 */
final class SessionSets {
    // The connection's logger, so the records read as before.
    private static final Logger LOGGER = Logger.getLogger(LoadBalanceConnection.class.getName());
    private final LoadBalanceConnection conn;
    private final SessionRoutingState sessionState;
    private final String logContext;
    // SQL SETs re-run on every leg opened later (replay key -> statement), in the order they last
    // ran, so a newer SET of the same setting replaces the older one.
    private final Map<String, Replayed> sessionSets = new LinkedHashMap<String, Replayed>();
    // The server session each leg was in when it last got the session SETs; see
    // restoreSessionSetsAfterReconnect.
    private final Map<Connection, byte[]> legSessions =
            Collections.synchronizedMap(new WeakHashMap<Connection, byte[]>());

    SessionSets(
            final LoadBalanceConnection conn,
            final SessionRoutingState sessionState,
            final String logContext) {
        this.conn = conn;
        this.sessionState = sessionState;
        this.logContext = logContext;
    }

    private EndpointConnManager connMgr() {
        return conn.getConnectionManager();
    }

    /**
     * Runs the kept SETs on a leg just opened and remembers the server session it is in.
     *
     * @param physical the leg
     * @throws SQLException if a SET fails there
     */
    void applyTo(final Connection physical) throws SQLException {
        runSessionSets(physical, sessionSetsSnapshot());
        rememberLegSession(physical, sessionOf(physical));
    }

    /**
     * The isolation level SQL SET this session ran last, or null when a setter replaced it or none
     * ran.
     */
    String isolationSql() {
        synchronized (sessionSets) {
            final Replayed bySql = sessionSets.get(SessionSetStatement.ISOLATION_LEVEL_KEY);
            return bySql == null ? null : bySql.sql;
        }
    }

    /**
     * Core reconnects a leg by itself and tells no one. When the CAS could not keep the server
     * session over it, that leg alone has none of the SETs LB ran on it, and its statements would
     * run under other settings than the other leg's. A read leg on its own connection is closed, to
     * reopen with the session state; the write leg runs the SETs again, once it holds no
     * transaction they would run inside.
     *
     * @param target the leg a statement is about to run on, or just ran on
     * @return whether a read leg was closed, so a read that just ran on it should run again
     */
    boolean restoreSessionSetsAfterReconnect(final RouteTarget target) {
        final List<Replayed> sets = sessionSetsSnapshot();
        if (sets.isEmpty() || connMgr() == null || !conn.isSessionInitialized()) {
            return false; // nothing a new session would lack
        }
        final Connection rw = connMgr().boundConnection(SessionLeg.RW);
        final Connection leg =
                target == RouteTarget.TO_READ_ONLY ? connMgr().boundConnection(SessionLeg.RO) : rw;
        final byte[] session = leg == null ? null : sessionOf(leg);
        final byte[] applied = leg == null ? null : legSessions.get(leg);
        if (session == null || applied == null || Arrays.equals(session, applied)) {
            return false;
        }
        if (leg != rw) {
            try {
                leg.close();
            } catch (SQLException alreadyGone) {
                // A leg that is closed is replaced by the next read on it either way.
            }
            LbLog.warn(
                    LOGGER,
                    logContext,
                    "LB SESSION RENEWED [RO]: core reconnected the read leg into a new server"
                            + " session, which has none of the session SETs; the leg is closed and"
                            + " reopens with them");
            return true;
        }
        if (connMgr().isRwTxOpenOnServer()) {
            return false;
        }
        // Remembered whether or not the SETs run: a SET the server now refuses is refused again.
        rememberLegSession(rw, session);
        try {
            runSessionSets(rw, sets);
            LbLog.warn(
                    LOGGER,
                    logContext,
                    "LB SESSION RENEWED [RW]: core reconnected the write leg into a new server"
                            + " session; the session SETs ran on it again | sets="
                            + sets.size());
        } catch (SQLException failed) {
            LbLog.warn(
                    LOGGER,
                    logContext,
                    "LB SESSION RENEWED [RW]: core reconnected the write leg into a new server"
                            + " session and running the session SETs on it again failed; it runs"
                            + " without them | cause: "
                            + conn.causeOf(failed));
        }
        return false;
    }

    private void rememberLegSession(final Connection leg, final byte[] session) {
        if (session != null) {
            legSessions.put(leg, session);
        }
    }

    /** The server session {@code leg} is in, or null when it has no core connection to ask. */
    private static byte[] sessionOf(final Connection leg) {
        try {
            final UConnection socket =
                    leg instanceof CUBRIDConnection
                            ? ((CUBRIDConnection) leg).getUConnection()
                            : null;
            return socket == null ? null : socket.getSessionId();
        } catch (SQLException unreadable) {
            return null;
        }
    }

    /**
     * Core prepared every open statement on its one session before a session {@code SET}, and the
     * server keeps what a statement was compiled under. One with no physical statement yet on a leg
     * it can run on is prepared there now, before the SET reaches that leg, or its first run there
     * would compile under the new setting.
     *
     * @param sql the statement about to run
     */
    void beforeSessionSet(final String sql) {
        if (!SqlFacts.of(sql).sessionSet().changesSession()) {
            return;
        }
        for (Statement each : conn.openStatementsSnapshot()) {
            if (each instanceof LBPreparedStatement) {
                final LBPreparedStatement prepared = (LBPreparedStatement) each;
                prepared.prepareOnWriteLeg();
                prepared.prepareOnReadLeg();
            }
        }
    }

    /**
     * Records what a statement that just succeeded changed in its session. A SQL {@code SET} ran
     * only on the leg it was routed to (RW): a session-scoped one is replayed on the read leg now
     * and on every leg opened later; one the read leg cannot be given keeps reads on RW until a
     * later statement undoes it or the connection closes. Variables an {@code INTO/TO} clause
     * created on RW are remembered so reads naming them follow, until a {@code DROP VARIABLE}
     * removes them. Its leg is where a following {@code ROW_COUNT()} runs.
     *
     * @param sql the statement as the application issued it
     * @param ranOn the leg it ran on
     */
    void afterStatementSucceeded(final String sql, final RouteTarget ranOn) {
        afterStatementSucceeded(sql, ranOn, null);
    }

    /**
     * {@link #afterStatementSucceeded(String, Router.RouteTarget)} for a statement that took values
     * from {@code ?}. A login or a lock timeout that did runs on the other legs with them, as one
     * written out runs from its text.
     *
     * @param sql the statement as the application issued it
     * @param ranOn the leg it ran on
     * @param withValues the statement with the values of this run, or null when it kept none
     */
    void afterStatementSucceeded(
            final String sql,
            final RouteTarget ranOn,
            final LoadBalanceConnection.BoundRun withValues) {
        sessionState.setLastStatementOnReadLeg(ranOn == RouteTarget.TO_READ_ONLY);
        final SqlFacts facts = SqlFacts.of(sql);
        if (ranOn == RouteTarget.TO_READ_WRITE) {
            sessionState.addSessionVariables(facts.assignedVariables());
            sessionState.removeSessionVariables(facts.droppedVariables());
        }
        final SessionSetStatement set = facts.sessionSet();
        if (!set.changesSession()) {
            return;
        }
        final String boundKey = withValues == null ? null : set.getBoundKey();
        if (boundKey != null) {
            replaySessionSet(
                    sql, Collections.singletonMap(boundKey, new Replayed(sql, withValues)));
        } else {
            if (!set.getReplay().isEmpty()) {
                final Map<String, Replayed> written = new LinkedHashMap<String, Replayed>();
                for (Map.Entry<String, String> each : set.getReplay().entrySet()) {
                    written.put(each.getKey(), new Replayed(each.getValue(), null));
                }
                replaySessionSet(sql, written);
            }
            pinReads(sql, set.getPins(), "a session change the read leg does not have ran on RW");
        }
        unpinReads(set.getUnpins(), conn.sqlForLog(sql));
        if (!set.getNonSessionParams().isEmpty()) {
            LbLog.warn(
                    LOGGER,
                    logContext,
                    "LB SESSION SET: "
                            + set.getNonSessionParams()
                            + " is not a session parameter; it changed the RW node only and read"
                            + " nodes keep their own value, so reads are not pinned"
                            + conn.sqlForLog(sql));
        }
    }

    /**
     * Records what a statement that failed on RW may have changed. CUBRID keeps the SETs and
     * variables a multi-statement string made before the failing statement; which ones is not
     * known, so each setting the string touches keeps reads on RW until a later statement sets it
     * again, and each variable it assigns is read on RW.
     *
     * @param sql the statement as the application issued it
     */
    void afterStatementFailed(final String sql) {
        final SqlFacts facts = SqlFacts.of(sql);
        pinReads(
                sql,
                facts.sessionSet().getPinsIfFailed(),
                "a statement string with a session change failed; the SETs before the failing"
                        + " statement may have run on RW");
        // Likewise a variable an INTO before the failing statement created. Such a string runs on
        // RW: an INTO sends it there.
        sessionState.addSessionVariables(facts.assignedIfFailed());
    }

    /**
     * Records a batch the server answered with a {@link java.sql.BatchUpdateException}. Each entry
     * whose count is not {@link Statement#EXECUTE_FAILED} changed the session as it would have
     * alone, and is the statement a following {@code ROW_COUNT()} reports on; a batch in which
     * every entry failed leaves that with the statement before it, as on core.
     *
     * @param sqls the batch entries, in order
     * @param counts the update counts the server answered, possibly {@code null}
     * @param ranOn the leg the batch ran on
     */
    void afterBatchAnswered(final List<String> sqls, final int[] counts, final RouteTarget ranOn) {
        final int answered = counts == null ? 0 : Math.min(sqls.size(), counts.length);
        for (int i = 0; i < answered; i++) {
            if (counts[i] == Statement.EXECUTE_FAILED) {
                afterStatementFailed(sqls.get(i));
            } else {
                afterStatementSucceeded(sqls.get(i), ranOn);
            }
        }
    }

    private void replaySessionSet(final String sql, final Map<String, Replayed> replay) {
        synchronized (sessionSets) {
            for (Map.Entry<String, Replayed> each : replay.entrySet()) {
                sessionSets.remove(each.getKey());
                sessionSets.put(each.getKey(), each.getValue());
            }
        }
        if (connMgr() != null && conn.isSessionInitialized()) {
            final Collection<Replayed> statements = replay.values();
            try {
                connMgr()
                        .applyToOpenReadLeg(
                                "session SET",
                                new EndpointConnManager.SessionStateApplier() {
                                    public void applyTo(final Connection physical)
                                            throws SQLException {
                                        runSessionSets(physical, statements);
                                    }
                                });
            } catch (SQLException rejected) {
                // A reopened leg would reject it again: keep reads where the SET took effect.
                pinReads(
                        sql,
                        replay.keySet(),
                        "the read leg rejected a SET that ran on RW ("
                                + conn.causeOf(rejected)
                                + ")");
                return;
            }
        }
        // The read leg has these settings now, or gets them when it opens.
        unpinReads(replay.keySet(), conn.sqlForLog(sql));
    }

    private void pinReads(final String sql, final Collection<String> reasons, final String what) {
        if (reasons.isEmpty() || sessionState.pinReadsToRw(reasons).isEmpty()) {
            return;
        }
        LbLog.warn(
                LOGGER,
                logContext,
                "LB SESSION PIN: "
                        + what
                        + "; this session's reads stay on RW until a later statement undoes it or"
                        + " the connection closes | reasons="
                        + sessionState.getReadPinReasons()
                        + conn.sqlForLog(sql));
    }

    private void unpinReads(final Collection<String> reasons, final String cause) {
        if (!sessionState.unpinReads(reasons)) {
            return;
        }
        LbLog.info(
                LOGGER,
                logContext,
                "LB SESSION UNPIN: the last change that kept reads on RW was undone; reads are"
                        + " distributed again"
                        + cause);
    }

    /**
     * A JDBC setter now owns {@code key}'s setting on both legs: drop the SQL {@code SET} of it, or
     * a leg opened later would get the older SQL value on top of the setter's, and release a pin a
     * bound SQL value of it left.
     */
    void forgetSessionSet(final String key) {
        synchronized (sessionSets) {
            sessionSets.remove(key);
        }
        unpinReads(Collections.singleton(key), " | by=a JDBC setter");
    }

    private List<Replayed> sessionSetsSnapshot() {
        synchronized (sessionSets) {
            return new ArrayList<Replayed>(sessionSets.values());
        }
    }

    private static void runSessionSets(
            final Connection physical, final Collection<Replayed> statements) throws SQLException {
        for (Replayed each : statements) {
            if (each.withValues != null) {
                each.withValues.on(physical);
                continue;
            }
            Statement statement = physical.createStatement();
            try {
                statement.execute(each.sql);
            } finally {
                statement.close();
            }
        }
    }

    /** A session SET kept for the other legs: its text, or the statement with a run's values. */
    private static final class Replayed {
        private final String sql;
        private final LoadBalanceConnection.BoundRun withValues;

        Replayed(final String sql, final LoadBalanceConnection.BoundRun withValues) {
            this.sql = sql;
            this.withValues = withValues;
        }
    }
}
