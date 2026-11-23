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

package cubrid.jdbc.lb.statement;

import cubrid.jdbc.driver.CUBRIDResultSet;
import cubrid.jdbc.driver.CUBRIDStatement;
import cubrid.jdbc.jci.CUBRIDCommandType;
import cubrid.jdbc.jci.UBatchResult;
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UStatementCacheData;
import cubrid.jdbc.lb.LbExceptions;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.failover.ExecuteFailoverHandler;
import cubrid.jdbc.lb.failover.ExecuteFailoverHandler.SqlExecution;
import cubrid.jdbc.lb.route.RoutedSql;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.route.Router.RouteDecision;
import cubrid.jdbc.lb.route.Router.RouteTarget;
import cubrid.jdbc.lb.state.SessionRoutingState;
import cubrid.sql.CUBRIDOID;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Logical Statement that delegates SQL execution to a physical statement on the connection the
 * Router picks at execute time. Statement properties (maxRows, fetch size, vendor flags, …) are
 * held here and re-applied to every physical statement it creates, so a route change or a failover
 * rebind does not lose them.
 */
public class LBStatement extends CUBRIDStatement {
    private static final BooleanSupplier NO_RW_TRANSACTION =
            new BooleanSupplier() {
                public boolean getAsBoolean() {
                    return false;
                }
            };

    private final LoadBalanceConnection lbConnection;
    private final int resultSetType;
    private final int resultSetConcurrency;
    private final int resultSetHoldability;
    private boolean closed = false;
    private boolean escapeProcessing = true;
    private boolean queryInfo;
    private boolean onlyQueryPlan;
    private boolean fromCurTxn = true;
    private final List<String> batchedSql = new ArrayList<String>();
    private final List<Statement> phyStmts = new ArrayList<Statement>();
    private Statement lastExecStmt;
    // Published BEFORE the physical execute call (unlike lastExecStmt, which is set only after the
    // call returns) so a concurrent cancel() targets the in-flight physical statement.
    private volatile Statement currentExecStmt;
    private ResultSet lastRs;
    // The leg statements whose update count and generated keys are this statement's: core changes
    // them only when an execution succeeds, so a later failure, or a run on another leg, keeps
    // them.
    private Statement countStmt;
    private Statement keysStmt;
    // What those leg statements answered when this statement last ran. A failover or failback
    // closes the connection it leaves and core closes its statements, while core's own reconnect
    // keeps its statements and their results; these answer once the leg statement is closed.
    private int lastUpdateCount = -1;
    private ResultSet lastKeys;
    private PhysicalStmtProvider stmtProvider = new UnsupportedStmtProvider();
    // True while a READ the read-only broker refused runs again on RW (see executeWithFailover).
    private boolean rejectedReadRetry = false;
    // Takes the connection manager's lock, so the router asks only for a statement that would
    // otherwise leave RW.
    private final BooleanSupplier rwTransactionOpen;
    // Set for a prepared statement made while a session change that alters compilation pinned
    // reads to RW; see Router.decide.
    private boolean preparedUnderReadPin;

    /**
     * The holdability a statement asking for {@code requested} reports on {@code socket}, by core's
     * own rule.
     *
     * @param socket the socket of an open leg
     * @param requested the holdability asked for
     * @return the holdability core's statement would report
     */
    public static int holdabilityOn(final UConnection socket, final int requested) {
        return holdsCursorsOverCommit(socket, requested)
                ? ResultSet.HOLD_CURSORS_OVER_COMMIT
                : ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    public LBStatement(LoadBalanceConnection lbConnection) throws SQLException {
        this(lbConnection, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    public LBStatement(
            LoadBalanceConnection lbConnection, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        this(
                lbConnection,
                resultSetType,
                resultSetConcurrency,
                lbConnection.statementHoldability());
    }

    public LBStatement(
            LoadBalanceConnection lbConnection,
            int resultSetType,
            int resultSetConcurrency,
            int resultSetHoldability)
            throws SQLException {
        super(lbConnection, resultSetType, resultSetConcurrency);
        this.lbConnection = lbConnection;
        this.rwTransactionOpen =
                new BooleanSupplier() {
                    public boolean getAsBoolean() {
                        return lbConnection.isRwTxOpenInAutoCommit();
                    }
                };
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.resultSetHoldability = lbConnection.statementHoldability(resultSetHoldability);
        this.stmtProvider =
                lbConnection.createStatementProvider(
                        resultSetType, resultSetConcurrency, this.resultSetHoldability);
    }

    public ResultSet executeQuery(String sql) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final RouteDecision route = routeFor(executedSql);
        final RouteTarget target = route.getTarget();
        return stampProducer(
                executeWithFailover(
                        executedSql,
                        route,
                        new SqlExecution<ResultSet>() {
                            public ResultSet run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                ResultSet result = statement.executeQuery(executedSql);
                                recordExec(statement, result, false);
                                return result;
                            }
                        }),
                this);
    }

    public int executeUpdate(String sql) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final RouteDecision route = routeFor(executedSql);
        final RouteTarget target = route.getTarget();
        return executeWithFailover(
                        executedSql,
                        route,
                        new SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                int result = statement.executeUpdate(executedSql);
                                recordExec(statement, null, true);
                                return Integer.valueOf(result);
                            }
                        })
                .intValue();
    }

    public boolean execute(String sql) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final RouteDecision route = routeFor(executedSql);
        final RouteTarget target = route.getTarget();
        return executeWithFailover(
                        executedSql,
                        route,
                        new SqlExecution<Boolean>() {
                            public Boolean run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                boolean result = statement.execute(executedSql);
                                recordExec(
                                        statement, result ? statement.getResultSet() : null, true);
                                return Boolean.valueOf(result);
                            }
                        })
                .booleanValue();
    }

    public int[] executeBatch() throws SQLException {
        checkClosed();
        if (batchedSql.isEmpty()) {
            return new int[0];
        }

        try {
            return executeBatchWithFailover(
                    batchedSql,
                    new SqlExecution<int[]>() {
                        public int[] run() throws SQLException {
                            Statement statement =
                                    createPhyStmt(batchedSql.get(0), RouteTarget.TO_READ_WRITE);
                            // The leg's statement keeps a batch core kept after a failed request;
                            // this run sends this batch only.
                            statement.clearBatch();
                            for (int i = 0; i < batchedSql.size(); i++) {
                                statement.addBatch(batchedSql.get(i));
                            }
                            return statement.executeBatch();
                        }
                    });
        } finally {
            // JDBC convention (matching CUBRIDStatement.executeBatch): the batch is consumed by an
            // executeBatch() attempt regardless of outcome, so clear it even when the physical
            // execute throws (host down, batch error) to avoid re-running the same batch on the
            // next executeBatch().
            batchedSql.clear();
        }
    }

    public void addBatch(String sql) throws SQLException {
        checkClosed();
        batchedSql.add(sql); // core takes a null too and fails it at executeBatch
    }

    public void clearBatch() throws SQLException {
        checkClosed();
        batchedSql.clear();
    }

    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final int keys = autoGeneratedKeys;
        final RouteDecision route = routeFor(executedSql);
        final RouteTarget target = route.getTarget();
        return executeWithFailover(
                        executedSql,
                        route,
                        new SqlExecution<Boolean>() {
                            public Boolean run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                boolean result = statement.execute(executedSql, keys);
                                recordExec(
                                        statement, result ? statement.getResultSet() : null, true);
                                return Boolean.valueOf(result);
                            }
                        })
                .booleanValue();
    }

    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final int[] indexes = columnIndexes;
        final RouteDecision route = routeFor(executedSql);
        final RouteTarget target = route.getTarget();
        return executeWithFailover(
                        executedSql,
                        route,
                        new SqlExecution<Boolean>() {
                            public Boolean run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                boolean result = statement.execute(executedSql, indexes);
                                recordExec(
                                        statement, result ? statement.getResultSet() : null, true);
                                return Boolean.valueOf(result);
                            }
                        })
                .booleanValue();
    }

    public boolean execute(String sql, String[] columnNames) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final String[] names = columnNames;
        final RouteDecision route = routeFor(executedSql);
        final RouteTarget target = route.getTarget();
        return executeWithFailover(
                        executedSql,
                        route,
                        new SqlExecution<Boolean>() {
                            public Boolean run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                boolean result = statement.execute(executedSql, names);
                                recordExec(
                                        statement, result ? statement.getResultSet() : null, true);
                                return Boolean.valueOf(result);
                            }
                        })
                .booleanValue();
    }

    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final int keys = autoGeneratedKeys;
        final RouteDecision route = routeFor(executedSql);
        final RouteTarget target = route.getTarget();
        return executeWithFailover(
                        executedSql,
                        route,
                        new SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                int result = statement.executeUpdate(executedSql, keys);
                                recordExec(statement, null, true);
                                return Integer.valueOf(result);
                            }
                        })
                .intValue();
    }

    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final int[] indexes = columnIndexes;
        final RouteDecision route = routeFor(executedSql);
        final RouteTarget target = route.getTarget();
        return executeWithFailover(
                        executedSql,
                        route,
                        new SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                int result = statement.executeUpdate(executedSql, indexes);
                                recordExec(statement, null, true);
                                return Integer.valueOf(result);
                            }
                        })
                .intValue();
    }

    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final String[] names = columnNames;
        final RouteDecision route = routeFor(executedSql);
        final RouteTarget target = route.getTarget();
        return executeWithFailover(
                        executedSql,
                        route,
                        new SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                int result = statement.executeUpdate(executedSql, names);
                                recordExec(statement, null, true);
                                return Integer.valueOf(result);
                            }
                        })
                .intValue();
    }

    /* ===== result metadata =====*/
    /** The leg statement's own answer: core drops a result set that was closed or completed. */
    public ResultSet getResultSet() throws SQLException {
        checkClosed();
        if (!isOpen(lastExecStmt)) {
            return null; // nothing yet, or its leg was replaced and its cursor went with it
        }

        return stampProducer(lastExecStmt.getResultSet(), this);
    }

    public int getUpdateCount() throws SQLException {
        checkClosed();
        if (countStmt == null) {
            return -1; // core's before any execution
        }
        if (!isOpen(countStmt)) {
            return lastUpdateCount;
        }

        return countStmt.getUpdateCount();
    }

    public boolean getMoreResults() throws SQLException {
        checkClosed();
        if (lastExecStmt == null) {
            return false;
        }
        countStmt = lastExecStmt;
        if (!isOpen(lastExecStmt)) {
            // As core's getMoreResults on a statement whose results are done.
            lastRs = null;
            lastUpdateCount = -1;
            return false;
        }

        boolean result = lastExecStmt.getMoreResults();
        lastRs = result ? lastExecStmt.getResultSet() : null;
        lastUpdateCount = lastExecStmt.getUpdateCount();

        return result;
    }

    public boolean getMoreResults(int current) throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    /**
     * Before any update, core's own answer (an empty result set); after one, the leg statement's
     * keys as core makes them, with no producing statement.
     */
    public ResultSet getGeneratedKeys() throws SQLException {
        checkClosed();
        if (keysStmt == null) {
            return super.getGeneratedKeys();
        }
        if (!isOpen(keysStmt)) {
            return lastKeys != null ? lastKeys : super.getGeneratedKeys();
        }

        return keysStmt.getGeneratedKeys();
    }

    /* ===== property accessors =====*/
    // Core validates, stores and checks closed (close() sets is_closed); overridden only so every
    // inherited method stays an explicit decision.
    public void setMaxRows(int max) throws SQLException {
        super.setMaxRows(max);
    }

    public int getMaxRows() throws SQLException {
        return super.getMaxRows();
    }

    public void setQueryTimeout(int seconds) throws SQLException {
        super.setQueryTimeout(seconds);
    }

    public int getQueryTimeout() throws SQLException {
        return super.getQueryTimeout();
    }

    public void setFetchSize(int rows) throws SQLException {
        super.setFetchSize(rows);
    }

    public int getFetchSize() throws SQLException {
        return super.getFetchSize();
    }

    public void setFetchDirection(int direction) throws SQLException {
        super.setFetchDirection(direction);
    }

    public int getFetchDirection() throws SQLException {
        return super.getFetchDirection();
    }

    public void setMaxFieldSize(int max) throws SQLException {
        super.setMaxFieldSize(max);
    }

    public int getMaxFieldSize() throws SQLException {
        return super.getMaxFieldSize();
    }

    public void setEscapeProcessing(boolean enable) throws SQLException {
        checkClosed();
        this.escapeProcessing = enable;
    }

    public void setCursorName(String name) throws SQLException {
        checkClosed();
    }

    public int getResultSetType() throws SQLException {
        checkClosed();

        return resultSetType;
    }

    public int getResultSetConcurrency() throws SQLException {
        checkClosed();

        return resultSetConcurrency;
    }

    public int getResultSetHoldability() throws SQLException {
        checkClosed();

        return resultSetHoldability;
    }

    public void setPoolable(boolean poolable) throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    public boolean isPoolable() throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    public void closeOnCompletion() throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    public boolean isCloseOnCompletion() throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    /* ===== close warnings connection =====*/
    public void close() throws SQLException {
        try {
            closePhyStmts();
        } finally {
            // Mark closed and untrack even if closing a physical statement throws; otherwise a
            // failed close() would leave closed=false and leak this logical statement in the
            // connection's tracking set until an eventual re-close.
            lastExecStmt = null;
            currentExecStmt = null;
            lastRs = null;
            countStmt = null;
            keysStmt = null;
            lastKeys = null;
            closed = true;
            lbConnection.untrackStatement(this);
            // Core's own close, so the inherited core methods find a closed statement as on core.
            super.close();
        }
    }

    public boolean isClosed() throws SQLException {
        return closed;
    }

    public void cancel() throws SQLException {
        checkClosed();
        // Prefer the in-flight statement published at execute-start; fall back to the last
        // completed one when no execution is in progress.
        Statement target = currentExecStmt;
        if (target == null) {
            target = lastExecStmt;
        }
        if (target != null) {
            target.cancel();
        }
    }

    public SQLWarning getWarnings() throws SQLException {
        checkClosed();
        if (!isOpen(lastExecStmt)) {
            return null; // core's own answer is always null
        }

        return lastExecStmt.getWarnings();
    }

    public void clearWarnings() throws SQLException {
        checkClosed();
        if (isOpen(lastExecStmt)) {
            lastExecStmt.clearWarnings();
        }
    }

    public Connection getConnection() throws SQLException {
        checkClosed();

        return lbConnection;
    }

    /* ===== Wrapper =====*/
    public <T> T unwrap(Class<T> iface) throws SQLException {
        checkClosed();
        if (iface.isAssignableFrom(getClass())) {
            return iface.cast(this);
        }

        throw LbExceptions.cannotUnwrap(iface);
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return super.isWrapperFor(iface);
    }

    /* ===== Internal =====*/

    /**
     * Names {@code logical} as the producer of a physical result set about to cross to the
     * application.
     *
     * <p>LB does not wrap result sets: {@code executeQuery()} hands out the physical one, and
     * {@code LBResultSetIdentityTest} pins that identity. Left alone, the object the application
     * holds would report the physical statement from {@code getStatement()} and a physical
     * connection from {@code getStatement().getConnection()}, and committing through that would
     * reach one leg only. Stamping the logical statement closes that path without wrapping the
     * per-row path.
     *
     * <p>Applied where a result set is returned, not where it is created, so every exit is covered,
     * including {@code getResultSet()} reaching through to the physical statement. Setting it twice
     * is harmless.
     *
     * @param rs the physical result set being returned, may be null
     * @param logical the statement the application called
     * @return the same result set, for use in a return statement
     */
    static ResultSet stampProducer(final ResultSet rs, final Statement logical) {
        if (rs instanceof CUBRIDResultSet) {
            ((CUBRIDResultSet) rs).setReportedStatement(logical);
        }

        return rs;
    }

    protected void checkClosed() throws SQLException {
        if (closed) {
            throw LbExceptions.statementClosed();
        }
    }

    protected <T> T executeWithFailover(
            final String sql, final RouteDecision route, final SqlExecution<T> execution)
            throws SQLException {
        return executeWithFailover(sql, route, true, execution);
    }

    /**
     * Runs {@code execution} under failover. A READ the read leg cannot serve (a hidden write, or
     * LOB content) runs once more on RW ({@link LoadBalanceConnection#mayRetryRejectedReadOnRw}); a
     * statement that succeeds is reported to the connection, which tracks the session SETs and
     * variables it created.
     *
     * @param <T> the execution's result type
     * @param sql the statement as the application issued it
     * @param route the statement's routing decision, which the routing records name
     * @param retrySafe whether re-running {@code execution} is safe with respect to the statement's
     *     parameters; a consumed stream parameter cannot be replayed
     * @param execution the physical execution to run
     * @return the execution's result
     * @throws SQLException if the execution fails and cannot be recovered or retried
     */
    protected <T> T executeWithFailover(
            final String sql,
            final RouteDecision route,
            final boolean retrySafe,
            final SqlExecution<T> execution)
            throws SQLException {
        lbConnection.beginStatementRoute(route);
        try {
            return runStatement(sql, route.getTarget(), retrySafe, execution);
        } finally {
            lbConnection.endStatementRoute();
        }
    }

    private <T> T runStatement(
            final String sql,
            final RouteTarget target,
            final boolean retrySafe,
            final SqlExecution<T> execution)
            throws SQLException {
        final ExecuteFailoverHandler handler = lbConnection.getExecuteFailoverHandler();
        T result;
        RouteTarget ranOn = target;
        lbConnection.beforeSessionSet(sql);
        lbConnection.settleReadLegRollback(false);
        try {
            result = handler.executeWithFailover(lbConnection, target, sql, retrySafe, execution);
        } catch (SQLException failure) {
            final boolean runAgain =
                    retrySafe && lbConnection.mayRetryRejectedReadOnRw(target, sql, failure);
            lbConnection.settleReadLegRollback(!runAgain);
            if (!runAgain) {
                lbConnection.afterStatementFailed(sql);
                throw failure;
            }
            lbConnection.beginRejectedReadRetry(sql, failure);
            rejectedReadRetry = true;
            ranOn = RouteTarget.TO_READ_WRITE;
            try {
                result =
                        handler.executeWithFailover(
                                lbConnection, RouteTarget.TO_READ_WRITE, sql, retrySafe, execution);
            } finally {
                rejectedReadRetry = false;
                lbConnection.endRejectedReadRetry();
            }
        }
        lbConnection.settleReadLegRollback(false);
        lbConnection.afterStatementSucceeded(sql, ranOn);
        return result;
    }

    /**
     * Runs a {@code Statement} batch through the steps of {@link #executeWithFailover}.
     *
     * @param sqls the batch's statements, in order
     * @param execution the physical execution to run
     * @return the update counts
     * @throws SQLException if the batch fails and cannot be recovered or retried
     */
    int[] executeBatchWithFailover(final List<String> sqls, final SqlExecution<int[]> execution)
            throws SQLException {
        return runBatch(sqls, 0, execution);
    }

    /**
     * Runs a {@code PreparedStatement} batch through the steps of {@link #executeWithFailover}.
     *
     * @param sql the prepared statement, which every row of the batch runs
     * @param rows the number of rows in the batch
     * @param execution the physical execution to run
     * @return the update counts
     * @throws SQLException if the batch fails and cannot be recovered or retried
     */
    int[] executeBatchWithFailover(
            final String sql, final int rows, final SqlExecution<int[]> execution)
            throws SQLException {
        return runBatch(Collections.singletonList(sql), rows, execution);
    }

    /**
     * Core runs a batch in its one session, where a routine behind a view can write; the read leg
     * would refuse it and a batch is not run again, so every batch runs on RW. Under failover like
     * any write: the leg is rebound, and the batch runs again only when connecting failed, as core
     * sends it to the next host then. A prepared batch has one statement and {@code rows} rows that
     * all answer for it; {@code rows} is 0 otherwise.
     */
    private int[] runBatch(
            final List<String> sqls, final int rows, final SqlExecution<int[]> execution)
            throws SQLException {
        final RouteTarget target = RouteTarget.TO_READ_WRITE;
        for (String each : sqls) {
            lbConnection.beforeSessionSet(each);
        }
        lbConnection.settleReadLegRollback(false);
        final int[] counts;
        lbConnection.beginStatementRoute(
                Router.batch(RoutedSql.of(sqls.get(0), lbConnection.sqlClassifier())));
        try {
            counts =
                    lbConnection
                            .getExecuteFailoverHandler()
                            .executeWithFailover(
                                    lbConnection, target, sqls.get(0), true, execution);
        } catch (BatchUpdateException answered) {
            lbConnection.settleReadLegRollback(true);
            lbConnection.afterBatchAnswered(
                    rows > 0 ? Collections.nCopies(rows, sqls.get(0)) : sqls,
                    answered.getUpdateCounts(),
                    target);
            throw answered;
        } catch (SQLException failure) {
            lbConnection.settleReadLegRollback(true);
            throw failure;
        } finally {
            lbConnection.endStatementRoute();
        }
        lbConnection.settleReadLegRollback(false);
        // Core sends no batch for a prepared statement without parameters: no counts, nothing ran.
        if (counts.length > 0) {
            for (String each : sqls) {
                lbConnection.afterStatementSucceeded(each, target);
            }
        }
        return counts;
    }

    /**
     * The leg a physical statement is created on: RW while a refused read runs again, otherwise
     * {@code target}. The execution closures capture the target routed before the first attempt.
     */
    RouteTarget effectiveTarget(final RouteTarget target) {
        return rejectedReadRetry ? RouteTarget.TO_READ_WRITE : target;
    }

    /**
     * Failover for a physical call on this statement's leg that is not an execution - the metadata
     * calls. Not timed: see {@link ExecuteFailoverHandler#executeCommandWithFailover}.
     *
     * @param <T> the call's result type
     * @param label the call name, for logging
     * @param target the leg the call runs on
     * @param call the physical call to run
     * @return the call's result
     * @throws SQLException if the call fails and cannot be recovered/retried
     */
    protected <T> T commandWithFailover(
            final String label, final RouteTarget target, final SqlExecution<T> call)
            throws SQLException {
        return lbConnection
                .getExecuteFailoverHandler()
                .executeCommandWithFailover(lbConnection, target, label, true, call);
    }

    /** The leg statement an execution of {@code sql} runs on, with this statement's settings. */
    private Statement createPhyStmt(final String sql, final RouteTarget target)
            throws SQLException {
        Statement result = legStmt(sql, target);
        applyStmtProps(result);
        beginExecution(result);

        return result;
    }

    /**
     * This statement's physical statement on the leg {@code sql} routes to. The provider hands back
     * the one it already made there, as core runs every execution on its one statement.
     */
    private Statement legStmt(final String sql, final RouteTarget target) throws SQLException {
        Statement result = stmtProvider.getStatement(effectiveTarget(target), sql);
        for (Statement each : phyStmts) {
            if (each == result) {
                return result;
            }
        }
        // A new one means a leg was replaced: the statements on its connection closed with it.
        for (Iterator<Statement> held = phyStmts.iterator(); held.hasNext(); ) {
            if (held.next().isClosed()) {
                held.remove();
            }
        }
        phyStmts.add(result);

        return result;
    }

    protected void applyStmtProps(final Statement target) throws SQLException {
        target.setMaxRows(getMaxRows());
        target.setQueryTimeout(getQueryTimeout());
        target.setFetchSize(getFetchSize());

        // CUBRIDStatement.setFetchDirection throws for TYPE_FORWARD_ONLY (non-scrollable); mirror
        // CUBRIDConnection.createStatement() which uses TYPE_FORWARD_ONLY by default.
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY) {
            target.setFetchDirection(getFetchDirection());
        }
        target.setMaxFieldSize(getMaxFieldSize());
        target.setEscapeProcessing(escapeProcessing);
        applyVendorFlags(target);
        if (target instanceof CUBRIDStatement) {
            CUBRIDStatement cubridTarget = (CUBRIDStatement) target;
            cubridTarget.setQueryInfo(queryInfo);
            cubridTarget.setOnlyQueryPlan(onlyQueryPlan);
        }
    }

    public CUBRIDOID executeInsert(final String sql) throws SQLException {
        if (closed) {
            return super.executeInsert(sql); // what core does on a closed statement
        }

        final RouteDecision route = routeFor(sql);
        final RouteTarget target = route.getTarget();
        return executeWithFailover(
                sql,
                route,
                new SqlExecution<CUBRIDOID>() {
                    public CUBRIDOID run() throws SQLException {
                        return invokeExecInsert(createPhyStmt(sql, target), sql);
                    }
                });
    }

    private CUBRIDOID invokeExecInsert(final Statement statement, final String sql)
            throws SQLException {
        return requireCubrid(statement, "executeInsert").executeInsert(sql);
    }

    /** Not an execution: as in core, this statement's results and counts stay as they were. */
    public String getQueryplan(final String sql) throws SQLException {
        checkClosed();

        Statement statement = legStmt(sql, routeFor(sql).getTarget());

        String result;
        lbConnection.settleReadLegRollback(false);
        try {
            result = requireCubrid(statement, "getQueryplan").getQueryplan(sql);
        } finally {
            // Core rolls back after every plan request, failed or not.
            lbConnection.settleReadLegRollback(true);
        }

        return result;
    }

    public String getQueryplan() throws SQLException {
        checkClosed();
        if (lastExecStmt == null) {
            return super.getQueryplan(); // core's answer before any execution
        }

        lbConnection.settleReadLegRollback(false);
        try {
            return requireCubrid(lastExecStmt, "getQueryplan").getQueryplan();
        } finally {
            lbConnection.settleReadLegRollback(true);
        }
    }

    public byte getStatementType() {
        if (lastExecStmt instanceof CUBRIDStatement) {
            return ((CUBRIDStatement) lastExecStmt).getStatementType();
        }

        return CUBRIDCommandType.CUBRID_STMT_UNKNOWN;
    }

    /**
     * {@code CUBRIDStatement} declares the vendor flag setters and the transaction-origin accessors
     * without {@code throws SQLException}, and an override may not widen the inherited clause. The
     * real work stays in the {@code *Impl} methods so the checked signature is preserved for
     * internal callers, and the public entry points wrap any failure with {@link #unchecked}.
     */
    @Override
    public void setQueryInfo(final boolean value) {
        try {
            setQueryInfoChecked(value);
        } catch (SQLException failure) {
            throw unchecked(failure);
        }
    }

    void setQueryInfoChecked(final boolean value) throws SQLException {
        queryInfo = value;
        if (lastExecStmt instanceof CUBRIDStatement) {
            ((CUBRIDStatement) lastExecStmt).setQueryInfo(value);
        }
    }

    @Override
    public void setOnlyQueryPlan(final boolean value) {
        try {
            setOnlyQueryPlanChecked(value);
        } catch (SQLException failure) {
            throw unchecked(failure);
        }
    }

    void setOnlyQueryPlanChecked(final boolean value) throws SQLException {
        onlyQueryPlan = value;
        if (lastExecStmt instanceof CUBRIDStatement) {
            ((CUBRIDStatement) lastExecStmt).setOnlyQueryPlan(value);
        }
    }

    @Override
    public void setCurrentTransaction(final boolean isFromCurrentTransaction) {
        try {
            setCurrentTransactionChecked(isFromCurrentTransaction);
        } catch (SQLException failure) {
            throw unchecked(failure);
        }
    }

    void setCurrentTransactionChecked(final boolean isFromCurrentTransaction) throws SQLException {
        this.fromCurTxn = isFromCurrentTransaction;

        for (int i = 0; i < phyStmts.size(); i++) {
            Statement physical = phyStmts.get(i);
            if (physical instanceof CUBRIDStatement) {
                ((CUBRIDStatement) physical).setCurrentTransaction(isFromCurrentTransaction);
            }
        }
    }

    @Override
    public boolean isFromCurrentTransaction() {
        try {
            return isFromCurrentTransactionChecked();
        } catch (SQLException failure) {
            throw unchecked(failure);
        }
    }

    boolean isFromCurrentTransactionChecked() throws SQLException {
        if (lastExecStmt instanceof CUBRIDStatement) {
            return ((CUBRIDStatement) lastExecStmt).isFromCurrentTransaction();
        }

        return fromCurTxn;
    }

    /**
     * An execution on {@code stmt} succeeded: it now answers for the update count, and for the
     * generated keys when it was an update, as core's executeUpdate/execute remake them.
     */
    protected void recordExec(final Statement stmt, final ResultSet rs, final boolean update) {
        lastRs = rs;
        countStmt = stmt;
        if (update) {
            keysStmt = stmt;
        }
        // Read now, while the leg statement is open; the execution already succeeded, so a failed
        // read leaves the value unknown instead of failing it.
        try {
            lastUpdateCount = stmt.getUpdateCount();
            if (update) {
                lastKeys = stmt.getGeneratedKeys();
            }
        } catch (SQLException unknown) {
            lastUpdateCount = -1;
            if (update) {
                lastKeys = null;
            }
        }
    }

    private static boolean isOpen(final Statement stmt) throws SQLException {
        return stmt != null && !stmt.isClosed();
    }

    /**
     * An execution starts on {@code next}. Core completes the previous one first, closing its
     * result set even when this one then fails.
     */
    protected void beginExecution(final Statement next) {
        closeResultOfAnotherStatement(next);
        lastRs = null;
        lastExecStmt = next;
        // Published before the physical call runs so cancel() can reach it.
        currentExecStmt = next;
    }

    protected Statement getLastExecStmt() {
        return lastExecStmt;
    }

    /**
     * Core's own close on re-execute reaches the previous result set only when {@code next} is the
     * statement that produced it, on the same leg.
     */
    private void closeResultOfAnotherStatement(final Statement next) {
        if (lastRs == null || lastExecStmt == next) {
            return;
        }
        try {
            lastRs.close();
        } catch (SQLException ignored) {
            // the leg it came from is gone; nothing is left to close
        }
        lastRs = null;
    }

    /**
     * The physical connection under a result set this statement handed out and the application has
     * not closed, or null. Failback must not close that connection.
     *
     * @return the connection holding an open cursor, or null
     */
    public Connection openCursorConnection() {
        ResultSet rs = lastRs;
        Statement stmt = lastExecStmt;
        if (rs == null || stmt == null) {
            return null;
        }

        try {
            return rs.isClosed() ? null : stmt.getConnection();
        } catch (SQLException unreadable) {
            return null; // a result set that cannot answer cannot be read either
        }
    }

    protected void applyVendorFlags(final Statement target) throws SQLException {
        if (target instanceof CUBRIDStatement) {
            ((CUBRIDStatement) target).setCurrentTransaction(fromCurTxn);
        }
    }

    /**
     * Narrows a physical statement to the vendor type its extension methods are declared on. Every
     * physical statement a session executes on is created by a CUBRID physical connection, so this
     * holds in production; the throw guards a delegate that is not one.
     *
     * @param statement the physical statement to narrow
     * @param label the extension method being delegated, for the failure message
     * @return the same statement, typed for the vendor extension call
     * @throws SQLException if the delegate is not a CUBRID statement
     */
    private static CUBRIDStatement requireCubrid(final Statement statement, final String label)
            throws SQLException {
        if (!(statement instanceof CUBRIDStatement)) {
            throw LbExceptions.physicalDelegateFailed(label, null);
        }

        return (CUBRIDStatement) statement;
    }

    /**
     * An updatable result set writes through the statement that produced it, and a read-only broker
     * refuses the write, so such a statement runs on RW whatever its SQL.
     */
    boolean producesUpdatableResults() {
        return resultSetConcurrency == ResultSet.CONCUR_UPDATABLE;
    }

    private RouteDecision routeFor(final String sql) {
        return routeFor(RoutedSql.of(sql, lbConnection.sqlClassifier()));
    }

    /** The leg of a statement whose SQL is already read, as a prepared statement's is. */
    RouteDecision routeFor(final RoutedSql sql) {
        return Router.decide(
                sql,
                producesUpdatableResults(),
                preparedUnderReadPin,
                lbConnection.getSessionState(),
                rwTransactionOpen);
    }

    void markPreparedUnderReadPin() {
        preparedUnderReadPin = true;
    }

    /**
     * Whether {@code sql} runs on the read leg when nothing in the session holds it on RW: what the
     * router decides for a session with no transaction, pin or variable.
     */
    boolean mayRunOnReadLeg(final RoutedSql sql) {
        final SessionRoutingState free = new SessionRoutingState();
        free.setLastStatementOnReadLeg(true);
        return Router.decide(
                                sql,
                                producesUpdatableResults(),
                                preparedUnderReadPin,
                                free,
                                NO_RW_TRANSACTION)
                        .getTarget()
                == RouteTarget.TO_READ_ONLY;
    }

    private void closePhyStmts() throws SQLException {
        SQLException first = null;

        for (int i = phyStmts.size() - 1; i >= 0; i--) {
            try {
                Statement each = phyStmts.get(i);
                if (each != null && !each.isClosed()) {
                    each.close();
                }
            } catch (SQLException ex) {
                if (first == null) {
                    first = ex;
                }
            }
        }
        phyStmts.clear();
        if (first != null) {
            throw first;
        }
    }

    /* ===== inherited CUBRIDStatement surface that must not run =====*/

    /**
     * Wraps a checked failure for an inherited signature that declares no {@code throws} clause.
     */
    private static RuntimeException unchecked(final SQLException cause) {
        return new IllegalStateException(cause.getMessage(), cause);
    }

    /**
     * SHARD is not supported on a load-balanced session, so the shard accessors refuse rather than
     * report a meaningless id. {@code CUBRIDStatement} declares them without a {@code throws}
     * clause, so the refusal is unchecked. Do not unify them with the checked refusals elsewhere in
     * this class: the inherited signature decides which form is legal.
     */
    @Override
    public int getShardId() {
        throw unchecked(LbExceptions.notSupportedApi("getShardId()"));
    }

    @Override
    protected void setShardId(final int shardId) {
        throw unchecked(LbExceptions.notSupportedApi("setShardId(int)"));
    }

    /**
     * The socket-level execution helpers all dereference {@code u_stmt}, which is null on a logical
     * statement (see {@link CUBRIDStatement#CUBRIDStatement(cubrid.jdbc.driver.CUBRIDConnection)}).
     * Routing happens in this class instead, so reaching one of these means a public entry point
     * was left inherited: refuse loudly rather than throw a NullPointerException from a stack trace
     * that never mentions LB.
     */
    @Override
    protected void executeCore(final boolean all) throws SQLException {
        throw LbExceptions.notSupportedApi("executeCore(boolean)");
    }

    @Override
    protected void executeCoreInternal(final boolean all, final UStatementCacheData cacheData)
            throws SQLException {
        throw LbExceptions.notSupportedApi("executeCoreInternal(boolean, UStatementCacheData)");
    }

    @Override
    protected CUBRIDOID executeInsertCore() throws SQLException {
        throw LbExceptions.notSupportedApi("executeInsertCore()");
    }

    @Override
    protected void jdbc_cache_make(final boolean all) throws SQLException {
        throw LbExceptions.notSupportedApi("jdbc_cache_make(boolean)");
    }

    @Override
    protected int[] checkBatchResult(final UBatchResult batchResults) throws SQLException {
        throw LbExceptions.notSupportedApi("checkBatchResult(UBatchResult)");
    }

    /**
     * Meaningless here, so refused. The redirect exists for the physical statements LB hands out
     * unwrapped; a logical statement already reports the logical connection from {@code
     * getConnection()}. Accepting it would let a caller make this statement claim an owner it does
     * not have. {@code CUBRIDStatement} declares it without a {@code throws} clause, so the refusal
     * is unchecked.
     */
    @Override
    public void setReportedConnection(final Connection reported) {
        throw unchecked(LbExceptions.notSupportedApi("setReportedConnection(Connection)"));
    }

    public interface PhysicalStmtProvider {

        Statement getStatement(RouteTarget target, String rawSql) throws SQLException;
    }

    private static final class UnsupportedStmtProvider implements PhysicalStmtProvider {
        public Statement getStatement(final RouteTarget target, final String rawSql)
                throws SQLException {
            throw LbExceptions.delegationNotConfigured();
        }
    }
}
