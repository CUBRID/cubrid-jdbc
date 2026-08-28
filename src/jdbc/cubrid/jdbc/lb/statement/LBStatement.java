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
import cubrid.jdbc.jci.UStatementCacheData;
import cubrid.jdbc.lb.LbExceptions;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.failover.ExecuteFailoverHandler;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.sql.SqlClassification;
import cubrid.sql.CUBRIDOID;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Logical Statement that delegates SQL execution to a physical statement on the connection the
 * Router picks at execute time. Statement properties (maxRows, fetch size, vendor flags, …) are
 * held here and re-applied to every physical statement it creates, so a route change or a failover
 * rebind does not lose them.
 */
public class LBStatement extends CUBRIDStatement {
    private final LoadBalanceConnection lbConnection;
    private final int resultSetType;
    private final int resultSetConcurrency;
    private final int resultSetHoldability;
    private boolean closed = false;
    private int maxRows = 0;
    private int queryTimeout = 0;
    private int fetchSize = 0;
    private int fetchDirection = ResultSet.FETCH_FORWARD;
    private int maxFieldSize = 0;
    private boolean escapeProcessing = true;
    private boolean queryInfo;
    private boolean onlyQueryPlan;
    private boolean closeOnCompletion = false;
    private boolean fromCurTxn = true;
    private final List<String> batchedSql = new ArrayList<String>();
    private final List<Statement> phyStmts = new ArrayList<Statement>();
    private Statement lastExecStmt;
    // Published BEFORE the physical execute call (unlike lastExecStmt, which is set only after the
    // call returns) so a concurrent cancel() targets the in-flight physical statement.
    private volatile Statement currentExecStmt;
    private ResultSet lastRs;
    private int lastUpdCnt = -1;
    private PhysicalStmtProvider stmtProvider = new UnsupportedStmtProvider();

    public LBStatement(LoadBalanceConnection lbConnection) {
        this(
                lbConnection,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }

    public LBStatement(
            LoadBalanceConnection lbConnection, int resultSetType, int resultSetConcurrency) {
        this(lbConnection, resultSetType, resultSetConcurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }

    public LBStatement(
            LoadBalanceConnection lbConnection,
            int resultSetType,
            int resultSetConcurrency,
            int resultSetHoldability) {
        super(lbConnection);
        this.lbConnection = lbConnection;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.resultSetHoldability = resultSetHoldability;
        this.stmtProvider =
                lbConnection.createStatementProvider(
                        resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    public void setStatementProvider(final PhysicalStmtProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("PhysicalStmtProvider must not be null");
        }
        this.stmtProvider = provider;
    }

    public ResultSet executeQuery(String sql) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final Router.RouteTarget target = routeFor(executedSql);
        return stampProducer(
                executeWithFailover(
                        executedSql,
                        target,
                        new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                            public ResultSet run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                ResultSet result = statement.executeQuery(executedSql);
                                lastExecStmt = statement;
                                lastRs = result;
                                lastUpdCnt = -1;
                                return result;
                            }
                        }),
                this);
    }

    public int executeUpdate(String sql) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final Router.RouteTarget target = routeFor(executedSql);
        return executeWithFailover(
                        executedSql,
                        target,
                        new ExecuteFailoverHandler.SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                int result = statement.executeUpdate(executedSql);
                                lastExecStmt = statement;
                                lastRs = null;
                                lastUpdCnt = result;
                                return Integer.valueOf(result);
                            }
                        })
                .intValue();
    }

    public boolean execute(String sql) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final Router.RouteTarget target = routeFor(executedSql);
        return executeWithFailover(
                        executedSql,
                        target,
                        new ExecuteFailoverHandler.SqlExecution<Boolean>() {
                            public Boolean run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                boolean result = statement.execute(executedSql);
                                lastExecStmt = statement;
                                lastRs = result ? statement.getResultSet() : null;
                                lastUpdCnt = result ? -1 : statement.getUpdateCount();
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

        Router.RouteTarget target = routeForBatch();

        try {
            Statement statement = createPhyStmt(batchedSql.get(0), target);

            for (int i = 0; i < batchedSql.size(); i++) {
                statement.addBatch(batchedSql.get(i));
            }

            int[] result = statement.executeBatch();
            lastExecStmt = statement;
            lastRs = null;
            lastUpdCnt = -1;

            return result;
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
        if (sql == null) {
            throw LbExceptions.invalidValue("addBatch(String) requires a non-null SQL string");
        }
        batchedSql.add(sql);
    }

    public void clearBatch() throws SQLException {
        checkClosed();
        batchedSql.clear();
    }

    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final int keys = autoGeneratedKeys;
        final Router.RouteTarget target = routeFor(executedSql);
        return executeWithFailover(
                        executedSql,
                        target,
                        new ExecuteFailoverHandler.SqlExecution<Boolean>() {
                            public Boolean run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                boolean result = statement.execute(executedSql, keys);
                                lastExecStmt = statement;
                                lastRs = result ? statement.getResultSet() : null;
                                lastUpdCnt = result ? -1 : statement.getUpdateCount();
                                return Boolean.valueOf(result);
                            }
                        })
                .booleanValue();
    }

    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final int[] indexes = columnIndexes;
        final Router.RouteTarget target = routeFor(executedSql);
        return executeWithFailover(
                        executedSql,
                        target,
                        new ExecuteFailoverHandler.SqlExecution<Boolean>() {
                            public Boolean run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                boolean result = statement.execute(executedSql, indexes);
                                lastExecStmt = statement;
                                lastRs = result ? statement.getResultSet() : null;
                                lastUpdCnt = result ? -1 : statement.getUpdateCount();
                                return Boolean.valueOf(result);
                            }
                        })
                .booleanValue();
    }

    public boolean execute(String sql, String[] columnNames) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final String[] names = columnNames;
        final Router.RouteTarget target = routeFor(executedSql);
        return executeWithFailover(
                        executedSql,
                        target,
                        new ExecuteFailoverHandler.SqlExecution<Boolean>() {
                            public Boolean run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                boolean result = statement.execute(executedSql, names);
                                lastExecStmt = statement;
                                lastRs = result ? statement.getResultSet() : null;
                                lastUpdCnt = result ? -1 : statement.getUpdateCount();
                                return Boolean.valueOf(result);
                            }
                        })
                .booleanValue();
    }

    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final int keys = autoGeneratedKeys;
        final Router.RouteTarget target = routeFor(executedSql);
        return executeWithFailover(
                        executedSql,
                        target,
                        new ExecuteFailoverHandler.SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                int result = statement.executeUpdate(executedSql, keys);
                                lastExecStmt = statement;
                                lastRs = null;
                                lastUpdCnt = result;
                                return Integer.valueOf(result);
                            }
                        })
                .intValue();
    }

    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final int[] indexes = columnIndexes;
        final Router.RouteTarget target = routeFor(executedSql);
        return executeWithFailover(
                        executedSql,
                        target,
                        new ExecuteFailoverHandler.SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                int result = statement.executeUpdate(executedSql, indexes);
                                lastExecStmt = statement;
                                lastRs = null;
                                lastUpdCnt = result;
                                return Integer.valueOf(result);
                            }
                        })
                .intValue();
    }

    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        checkClosed();

        final String executedSql = sql;
        final String[] names = columnNames;
        final Router.RouteTarget target = routeFor(executedSql);
        return executeWithFailover(
                        executedSql,
                        target,
                        new ExecuteFailoverHandler.SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                Statement statement = createPhyStmt(executedSql, target);
                                int result = statement.executeUpdate(executedSql, names);
                                lastExecStmt = statement;
                                lastRs = null;
                                lastUpdCnt = result;
                                return Integer.valueOf(result);
                            }
                        })
                .intValue();
    }

    /* ===== result metadata =====*/
    public ResultSet getResultSet() throws SQLException {
        checkClosed();
        if (lastRs != null) {
            return stampProducer(lastRs, this);
        }

        if (lastExecStmt == null) {
            return null;
        }

        return stampProducer(lastExecStmt.getResultSet(), this);
    }

    public int getUpdateCount() throws SQLException {
        checkClosed();
        if (lastExecStmt == null) {
            return lastUpdCnt;
        }

        return lastExecStmt.getUpdateCount();
    }

    public boolean getMoreResults() throws SQLException {
        checkClosed();
        if (lastExecStmt == null) {
            return false;
        }

        boolean result = lastExecStmt.getMoreResults();
        lastRs = result ? lastExecStmt.getResultSet() : null;

        return result;
    }

    public boolean getMoreResults(int current) throws SQLException {
        checkClosed();
        if (lastExecStmt == null) {
            return false;
        }

        boolean result = lastExecStmt.getMoreResults(current);
        lastRs = result ? lastExecStmt.getResultSet() : null;

        return result;
    }

    public ResultSet getGeneratedKeys() throws SQLException {
        checkClosed();
        if (lastExecStmt == null) {
            throw LbExceptions.noExecutionHistory(
                    "getGeneratedKeys() requires the statement to be executed first");
        }

        return stampProducer(lastExecStmt.getGeneratedKeys(), this);
    }

    /* ===== property accessors =====*/
    public void setMaxRows(int max) throws SQLException {
        checkClosed();
        this.maxRows = max;
    }

    public int getMaxRows() throws SQLException {
        checkClosed();

        return maxRows;
    }

    public void setQueryTimeout(int seconds) throws SQLException {
        checkClosed();
        this.queryTimeout = seconds;
    }

    public int getQueryTimeout() throws SQLException {
        checkClosed();

        return queryTimeout;
    }

    public void setFetchSize(int rows) throws SQLException {
        checkClosed();
        this.fetchSize = rows;
    }

    public int getFetchSize() throws SQLException {
        checkClosed();

        return fetchSize;
    }

    public void setFetchDirection(int direction) throws SQLException {
        checkClosed();
        this.fetchDirection = direction;
    }

    public int getFetchDirection() throws SQLException {
        checkClosed();

        return fetchDirection;
    }

    public void setMaxFieldSize(int max) throws SQLException {
        checkClosed();
        this.maxFieldSize = max;
    }

    public int getMaxFieldSize() throws SQLException {
        checkClosed();

        return maxFieldSize;
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
        checkClosed();

        throw LbExceptions.notSupported(new UnsupportedOperationException());
    }

    public boolean isPoolable() throws SQLException {
        checkClosed();

        throw LbExceptions.notSupported(new UnsupportedOperationException());
    }

    public void closeOnCompletion() throws SQLException {
        checkClosed();
        this.closeOnCompletion = true;
    }

    public boolean isCloseOnCompletion() throws SQLException {
        checkClosed();

        return closeOnCompletion;
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
            lastUpdCnt = -1;
            closed = true;
            lbConnection.untrackStatement(this);
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
        if (lastExecStmt == null) {
            return null;
        }

        return lastExecStmt.getWarnings();
    }

    public void clearWarnings() throws SQLException {
        checkClosed();
        if (lastExecStmt != null) {
            lastExecStmt.clearWarnings();
        }
    }

    public Connection getConnection() throws SQLException {
        checkClosed();

        return lbConnection;
    }

    /* ===== Wrapper =====*/
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return iface.cast(this);
        }

        throw LbExceptions.invalidValue("Cannot unwrap to " + iface.getName());
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
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
            final String sql,
            final Router.RouteTarget target,
            final ExecuteFailoverHandler.SqlExecution<T> execution)
            throws SQLException {
        return executeWithFailover(sql, target, true, execution);
    }

    protected <T> T executeWithFailover(
            final String sql,
            final Router.RouteTarget target,
            final boolean retrySafe,
            final ExecuteFailoverHandler.SqlExecution<T> execution)
            throws SQLException {
        return lbConnection
                .getExecuteFailoverHandler()
                .executeWithFailover(lbConnection, target, sql, retrySafe, execution);
    }

    private Statement createPhyStmt(final String sql, final Router.RouteTarget target)
            throws SQLException {
        // JDBC: a new execution implicitly closes the previous ResultSet. Also release the previous
        // physical statement (and any dead statement left by a failed failover attempt) so repeated
        // execute() on one logical Statement does not accumulate server handles / open ResultSets
        // until close().
        closePhyStmtsQuietly();
        lastRs = null;
        lastExecStmt = null;
        currentExecStmt = null;
        lastUpdCnt = -1;

        Statement result = stmtProvider.getStatement(target, sql);
        applyStmtProps(result);
        phyStmts.add(result);
        // Publish before executeQuery/executeUpdate/execute runs so cancel() can reach it.
        publishExecStmt(result);

        return result;
    }

    /** Quietly close and forget all currently tracked physical statements. */
    private void closePhyStmtsQuietly() {
        for (int i = phyStmts.size() - 1; i >= 0; i--) {
            try {
                Statement each = phyStmts.get(i);
                if (each != null && !each.isClosed()) {
                    each.close();
                }
            } catch (SQLException ignored) {
            }
        }
        phyStmts.clear();
    }

    protected void applyStmtProps(final Statement target) throws SQLException {
        target.setMaxRows(maxRows);
        target.setQueryTimeout(queryTimeout);
        target.setFetchSize(fetchSize);

        // CUBRIDStatement.setFetchDirection throws for TYPE_FORWARD_ONLY (non-scrollable); mirror
        // CUBRIDConnection.createStatement() which uses TYPE_FORWARD_ONLY by default.
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY) {
            target.setFetchDirection(fetchDirection);
        }
        target.setMaxFieldSize(maxFieldSize);
        target.setEscapeProcessing(escapeProcessing);
        if (closeOnCompletion) {
            target.closeOnCompletion();
        }
        applyVendorFlags(target);
        if (target instanceof CUBRIDStatement) {
            CUBRIDStatement cubridTarget = (CUBRIDStatement) target;
            cubridTarget.setQueryInfo(queryInfo);
            cubridTarget.setOnlyQueryPlan(onlyQueryPlan);
        }
    }

    public CUBRIDOID executeInsert(final String sql) throws SQLException {
        checkClosed();

        Statement statement = createPhyStmt(sql, routeFor(sql));

        CUBRIDOID result = invokeExecInsert(statement, sql);
        lastExecStmt = statement;
        lastRs = null;
        lastUpdCnt = -1;

        return result;
    }

    private CUBRIDOID invokeExecInsert(final Statement statement, final String sql)
            throws SQLException {
        return requireCubrid(statement, "executeInsert").executeInsert(sql);
    }

    public String getQueryplan(final String sql) throws SQLException {
        checkClosed();

        Statement statement = createPhyStmt(sql, routeFor(sql));

        String result = requireCubrid(statement, "getQueryplan").getQueryplan(sql);
        lastExecStmt = statement;
        lastRs = null;
        lastUpdCnt = -1;

        return result;
    }

    public String getQueryplan() throws SQLException {
        checkClosed();
        if (lastExecStmt == null) {
            throw LbExceptions.noExecutionHistory(
                    "getQueryplan() requires the statement to be executed first");
        }

        return requireCubrid(lastExecStmt, "getQueryplan").getQueryplan();
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
        checkClosed();
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
        checkClosed();
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
        checkClosed();
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
        checkClosed();
        if (lastExecStmt instanceof CUBRIDStatement) {
            return ((CUBRIDStatement) lastExecStmt).isFromCurrentTransaction();
        }

        return fromCurTxn;
    }

    protected void recordExec(final Statement stmt, final ResultSet rs, final int updateCount) {
        lastExecStmt = stmt;
        lastRs = rs;
        lastUpdCnt = updateCount;
    }

    protected Statement getLastExecStmt() {
        return lastExecStmt;
    }

    /**
     * Publishes the physical statement about to execute, so a concurrent cancel() can reach it.
     *
     * @param stmt the physical statement about to execute
     */
    protected void publishExecStmt(final Statement stmt) {
        this.currentExecStmt = stmt;
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

    private Router.RouteTarget routeFor(final String sql) {
        return Router.decide(
                        sql,
                        lbConnection.getSessionState(),
                        new Router.DefaultRouteAdvisor() {
                            public Router.RouteTarget defaultTarget(final String rawSql) {
                                SqlClassification classification =
                                        lbConnection.sqlClassifier().classify(rawSql);
                                if (SqlClassification.READ == classification) {
                                    return Router.RouteTarget.TO_READ_ONLY;
                                }

                                return Router.RouteTarget.TO_READ_WRITE;
                            }
                        })
                .getTarget();
    }

    private Router.RouteTarget routeForBatch() {
        // A batch routes to RW if any statement is a write; otherwise it routes to the read target
        // (TO_READ_ONLY) rather than promoting to RW.
        for (int i = 0; i < batchedSql.size(); i++) {
            if (routeFor(batchedSql.get(i)) == Router.RouteTarget.TO_READ_WRITE) {
                return Router.RouteTarget.TO_READ_WRITE;
            }
        }

        return Router.RouteTarget.TO_READ_ONLY;
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
    public void setReportedConnection(final java.sql.Connection reported) {
        throw unchecked(LbExceptions.notSupportedApi("setReportedConnection(Connection)"));
    }

    public interface PhysicalStmtProvider {

        Statement getStatement(Router.RouteTarget target, String rawSql) throws SQLException;
    }

    private static final class UnsupportedStmtProvider implements PhysicalStmtProvider {
        public Statement getStatement(final Router.RouteTarget target, final String rawSql)
                throws SQLException {
            throw LbExceptions.delegationNotConfigured();
        }
    }
}
