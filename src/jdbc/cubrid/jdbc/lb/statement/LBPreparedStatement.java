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

import cubrid.jdbc.driver.CUBRIDPreparedStatement;
import cubrid.jdbc.jci.ReconnectPolicy;
import cubrid.jdbc.jci.UBatchResult;
import cubrid.jdbc.jci.UStatementCacheData;
import cubrid.jdbc.lb.LbExceptions;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.failover.ExecuteFailoverHandler;
import cubrid.jdbc.lb.failover.UnreachableEndpoints;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.sql.HintParser;
import cubrid.jdbc.lb.sql.HintSet;
import cubrid.jdbc.lb.sql.SqlClassification;
import cubrid.jdbc.lb.sql.SqlClassifier;
import cubrid.jdbc.lb.state.SessionRoutingState;
import cubrid.sql.CUBRIDOID;
import cubrid.sql.CUBRIDTimestamptz;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Logical PreparedStatement. Holds the SQL text and delegates to a physical PreparedStatement
 * chosen by the Router at execute time, preparing lazily on the bound endpoint.
 *
 * <p>The route target, the hint and the SQL classification are computed once at construction
 * ({@code SqlCaches}) so the execute path only combines them with the session state.
 */
public class LBPreparedStatement extends CUBRIDPreparedStatement {
    /**
     * The {@code Statement}-level half of this prepared statement.
     *
     * <p>{@code (CUBRIDPreparedStatement) ps} must work unmodified, so this class extends {@code
     * CUBRIDPreparedStatement}, and Java then forbids also extending {@link LBStatement}. The
     * shared load-balancing logic therefore arrives by composition. Being in the same package, this
     * class still reaches {@code LBStatement}'s protected hooks unchanged.
     */
    private final LBStatement statementDelegate;

    private final String sql;
    private final LoadBalanceConnection ownerConn;
    private final SessionRoutingState sessState;
    private final ParameterBinder parameterBinder;
    private final List<ParameterBinder> batchedBinders = new ArrayList<ParameterBinder>();
    private PhysicalPsProvider phyPsProvider = new UnsupportedPsProvider();
    private final SqlCaches sqlCaches;

    public LBPreparedStatement(LoadBalanceConnection lbConnection, String sql) {
        this(lbConnection, sql, new LBStatement(lbConnection));
    }

    public LBPreparedStatement(
            LoadBalanceConnection lbConnection,
            String sql,
            int resultSetType,
            int resultSetConcurrency) {
        this(lbConnection, sql, new LBStatement(lbConnection, resultSetType, resultSetConcurrency));
    }

    public LBPreparedStatement(
            LoadBalanceConnection lbConnection,
            String sql,
            int resultSetType,
            int resultSetConcurrency,
            int resultSetHoldability) {
        this(
                lbConnection,
                sql,
                new LBStatement(
                        lbConnection, resultSetType, resultSetConcurrency, resultSetHoldability));
    }

    /**
     * The one place the final fields are set; the public constructors differ only in how they build
     * the {@code Statement}-level delegate. The delegate is constructed as a {@code this(...)}
     * argument, so it is built before {@code super(lbConnection)} runs - safe because neither base
     * constructor touches the connection, they only read it.
     */
    private LBPreparedStatement(
            LoadBalanceConnection lbConnection, String sql, LBStatement statementDelegate) {
        super(lbConnection);
        this.statementDelegate = statementDelegate;
        this.sql = sql;
        this.ownerConn = lbConnection;
        this.sessState = lbConnection.getSessionState();
        this.parameterBinder = new ParameterBinder();
        this.sqlCaches = SqlCaches.compute(sql, lbConnection.sqlClassifier());
    }

    public String getSql() {
        return sql;
    }

    LoadBalanceConnection getOwnerConn() {
        return ownerConn;
    }

    SessionRoutingState getSessState() {
        return sessState;
    }

    ParameterBinder getParameterBinder() {
        return parameterBinder;
    }

    public void setPsProvider(PhysicalPsProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("PhysicalPsProvider must not be null");
        }
        this.phyPsProvider = provider;
    }

    public void prepareEagerly() throws SQLException {
        Router.RouteTarget target = resolveRouteTarget();
        if (Router.RouteTarget.TO_READ_ONLY == target) {
            prepareTolerantOfUnreachable(true);

            return;
        }

        // RW/UNKNOWN path: prepare on RW only to avoid replica schema lag failures.
        prepareTolerantOfUnreachable(false);
    }

    /**
     * Eager prepare runs outside {@link ExecuteFailoverHandler}, so if the bound endpoint is down
     * (e.g. the RO broker was stopped) it would throw here — before {@code executeQuery()} can fail
     * over. Swallow "host down" errors and let the first execute re-prepare through the failover
     * path; any other error still propagates as before.
     */
    private void prepareTolerantOfUnreachable(final boolean onReadLeg) throws SQLException {
        try {
            if (onReadLeg) {
                phyPsProvider.prepareOnBoundReadEndpoint(sql);
            } else {
                phyPsProvider.prepareOnWriteEndpoint(sql);
            }
        } catch (SQLException ex) {
            if (!ReconnectPolicy.isRetriableSqlException(ex)
                    && !UnreachableEndpoints.shouldMarkUnreachable(ex)) {
                throw ex;
            }
            // Deferred: the bound endpoint is unreachable; execute-time failover will re-prepare.
        }
    }

    /* ===== execute =====*/
    public ResultSet executeQuery() throws SQLException {
        statementDelegate.checkClosed();

        final Router.RouteTarget target = resolveRouteTarget();
        // Stamped with this prepared statement, not with statementDelegate: the delegate is an
        // implementation detail the application never received.
        return LBStatement.stampProducer(
                statementDelegate.executeWithFailover(
                        sql,
                        target,
                        !parameterBinder.hasNonReplayableParams(),
                        new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                            public ResultSet run() throws SQLException {
                                PreparedStatement physicalStatement = physicalPsWithParams(target);
                                ResultSet result = physicalStatement.executeQuery();
                                statementDelegate.recordExec(physicalStatement, result, -1);
                                return result;
                            }
                        }),
                this);
    }

    public int executeUpdate() throws SQLException {
        statementDelegate.checkClosed();

        final Router.RouteTarget target = resolveRouteTarget();
        return statementDelegate
                .executeWithFailover(
                        sql,
                        target,
                        !parameterBinder.hasNonReplayableParams(),
                        new ExecuteFailoverHandler.SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                PreparedStatement physicalStatement = physicalPsWithParams(target);
                                int n = physicalStatement.executeUpdate();
                                statementDelegate.recordExec(physicalStatement, null, n);
                                return Integer.valueOf(n);
                            }
                        })
                .intValue();
    }

    public boolean execute() throws SQLException {
        statementDelegate.checkClosed();

        final Router.RouteTarget target = resolveRouteTarget();
        return statementDelegate
                .executeWithFailover(
                        sql,
                        target,
                        !parameterBinder.hasNonReplayableParams(),
                        new ExecuteFailoverHandler.SqlExecution<Boolean>() {
                            public Boolean run() throws SQLException {
                                PreparedStatement physicalStatement = physicalPsWithParams(target);
                                boolean hasResult = physicalStatement.execute();
                                ResultSet rs = hasResult ? physicalStatement.getResultSet() : null;
                                int uc = hasResult ? -1 : physicalStatement.getUpdateCount();
                                statementDelegate.recordExec(physicalStatement, rs, uc);
                                return Boolean.valueOf(hasResult);
                            }
                        })
                .booleanValue();
    }

    public void addBatch() throws SQLException {
        statementDelegate.checkClosed();
        validateParamCountCompat();
        batchedBinders.add(parameterBinder.snapshot());
    }

    public int[] executeBatch() throws SQLException {
        statementDelegate.checkClosed();
        if (batchedBinders.isEmpty()) {
            return new int[0];
        }

        try {
            PreparedStatement physicalStatement = physicalPsForTarget(resolveRouteTarget());

            for (int i = 0; i < batchedBinders.size(); i++) {
                physicalStatement.clearParameters();
                batchedBinders.get(i).replay(physicalStatement);
                physicalStatement.addBatch();
            }

            int[] result = physicalStatement.executeBatch();
            statementDelegate.recordExec(physicalStatement, null, -1);

            return result;
        } finally {
            // JDBC convention: consume the batch on any executeBatch() attempt so a later call does
            // not silently re-run it after a failure.
            batchedBinders.clear();
        }
    }

    /**
     * {@code CUBRIDPreparedStatement} declares this without {@code throws SQLException} and an
     * override may not widen the inherited clause, so a closed-statement failure is reported
     * unchecked. See {@link LBStatement#setQueryInfo(boolean)} for the same constraint.
     */
    @Override
    public boolean hasResultSet() {
        try {
            statementDelegate.checkClosed();
        } catch (SQLException failure) {
            throw unchecked(failure);
        }

        Statement last = statementDelegate.getLastExecStmt();
        if (last instanceof CUBRIDPreparedStatement) {
            return ((CUBRIDPreparedStatement) last).hasResultSet();
        }

        return false;
    }

    public void clearBatch() throws SQLException {
        statementDelegate.checkClosed();
        batchedBinders.clear();
    }

    /* ===== metadata =====*/
    /**
     * Metadata runs through the failover handler like an execute, because reaching it means a
     * physical prepare: an eager prepare that hit a dead broker was deferred (see {@link
     * #prepareTolerantOfUnreachable}), so this call is the one that prepares. Unprotected, it threw
     * at the application while a sibling endpoint was alive. Retry is unconditionally safe here -
     * describing a statement has no side effect and consumes no parameter stream - so {@code
     * retrySafe} is {@code true}; a WRITE-target statement still only rebinds, because the handler
     * never replays {@code TO_READ_WRITE}.
     */
    public ResultSetMetaData getMetaData() throws SQLException {
        statementDelegate.checkClosed();

        final Router.RouteTarget target = resolveRouteTarget();

        return statementDelegate.commandWithFailover(
                "getMetaData",
                target,
                new ExecuteFailoverHandler.SqlExecution<ResultSetMetaData>() {
                    public ResultSetMetaData run() throws SQLException {
                        // Both steps inside the callback: after a rebind the physical statement of
                        // the failed endpoint must not be the one described.
                        return physicalPsForMetadata(target).getMetaData();
                    }
                });
    }

    public ParameterMetaData getParameterMetaData() throws SQLException {
        statementDelegate.checkClosed();

        final Router.RouteTarget target = resolveRouteTarget();

        return statementDelegate.commandWithFailover(
                "getParameterMetaData",
                target,
                new ExecuteFailoverHandler.SqlExecution<ParameterMetaData>() {
                    public ParameterMetaData run() throws SQLException {
                        return physicalPsForMetadata(target).getParameterMetaData();
                    }
                });
    }

    /* ===== parameter setters (stored for delegation at execute time) =====*/
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetNull(parameterIndex, sqlType);
    }

    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetNull(parameterIndex, sqlType, typeName);
    }

    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetBoolean(parameterIndex, x);
    }

    public void setByte(int parameterIndex, byte x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetByte(parameterIndex, x);
    }

    public void setShort(int parameterIndex, short x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetShort(parameterIndex, x);
    }

    public void setInt(int parameterIndex, int x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetInt(parameterIndex, x);
    }

    public void setLong(int parameterIndex, long x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetLong(parameterIndex, x);
    }

    public void setFloat(int parameterIndex, float x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetFloat(parameterIndex, x);
    }

    public void setDouble(int parameterIndex, double x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetDouble(parameterIndex, x);
    }

    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetBigDecimal(parameterIndex, x);
    }

    public void setString(int parameterIndex, String x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetString(parameterIndex, x);
    }

    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetBytes(parameterIndex, x);
    }

    public void setDate(int parameterIndex, Date x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetDate(parameterIndex, x);
    }

    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetDate(parameterIndex, x, cal);
    }

    public void setTime(int parameterIndex, Time x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetTime(parameterIndex, x);
    }

    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetTime(parameterIndex, x, cal);
    }

    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetTimestamp(parameterIndex, x);
    }

    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetTimestamp(parameterIndex, x, cal);
    }

    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetAsciiStream(parameterIndex, x, length);
    }

    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetAsciiStream(parameterIndex, x, length);
    }

    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetAsciiStream(parameterIndex, x);
    }

    public void setUnicodeStream(int parameterIndex, InputStream x, int length)
            throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetUnicodeStream(parameterIndex, x, length);
    }

    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetBinaryStream(parameterIndex, x, length);
    }

    public void setBinaryStream(int parameterIndex, InputStream x, long length)
            throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetBinaryStream(parameterIndex, x, length);
    }

    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetBinaryStream(parameterIndex, x);
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetObject(parameterIndex, x, targetSqlType);
    }

    public void setObject(int parameterIndex, Object x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetObject(parameterIndex, x);
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
            throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }

    public void setCharacterStream(int parameterIndex, Reader reader, int length)
            throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetCharacterStream(parameterIndex, reader, length);
    }

    public void setCharacterStream(int parameterIndex, Reader reader, long length)
            throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetCharacterStream(parameterIndex, reader, length);
    }

    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetCharacterStream(parameterIndex, reader);
    }

    public void setRef(int parameterIndex, Ref x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetRef(parameterIndex, x);
    }

    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetBlob(parameterIndex, x);
    }

    public void setBlob(int parameterIndex, InputStream inputStream, long length)
            throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetBlob(parameterIndex, inputStream, length);
    }

    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetBlob(parameterIndex, inputStream);
    }

    public void setClob(int parameterIndex, Clob x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetClob(parameterIndex, x);
    }

    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetClob(parameterIndex, reader, length);
    }

    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetClob(parameterIndex, reader);
    }

    public void setArray(int parameterIndex, Array x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetArray(parameterIndex, x);
    }

    public void setURL(int parameterIndex, URL x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetURL(parameterIndex, x);
    }

    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetRowId(parameterIndex, x);
    }

    public void setNString(int parameterIndex, String value) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetNString(parameterIndex, value);
    }

    public void setNCharacterStream(int parameterIndex, Reader value, long length)
            throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetNCharacterStream(parameterIndex, value, length);
    }

    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetNCharacterStream(parameterIndex, value);
    }

    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetNClob(parameterIndex, value);
    }

    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetNClob(parameterIndex, reader, length);
    }

    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetNClob(parameterIndex, reader);
    }

    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetSQLXML(parameterIndex, xmlObject);
    }

    public void setOID(final int parameterIndex, final CUBRIDOID value) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetOID(parameterIndex, value);
    }

    public void setCollection(final int parameterIndex, final Object[] value) throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetCollection(parameterIndex, value);
    }

    public void setTimestamptz(final int parameterIndex, final CUBRIDTimestamptz value)
            throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetTimestamptz(parameterIndex, value);
    }

    public void setTimestamptz(
            final int parameterIndex, final CUBRIDTimestamptz value, final Calendar cal)
            throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.recordSetTimestamptz(parameterIndex, value, cal);
    }

    public void clearParameters() throws SQLException {
        statementDelegate.checkClosed();
        parameterBinder.clear();
    }

    public CUBRIDOID executeInsert() throws SQLException {
        statementDelegate.checkClosed();

        PreparedStatement physicalStatement = physicalPsWithParams(resolveRouteTarget());

        CUBRIDOID oid = invokeExecInsert(physicalStatement);
        statementDelegate.recordExec(physicalStatement, null, -1);

        return oid;
    }

    @Override
    public void close() throws SQLException {
        if (statementDelegate.isClosed()) {
            return;
        }
        try {
            phyPsProvider.closePrepStmts();
        } finally {
            // Always close the Statement-level half so the closed flag is set even if closing the
            // physical prepared statements throws.
            try {
                statementDelegate.close();
            } finally {
                // The delegate untracks itself, and the connection tracked this prepared statement,
                // not the delegate - so untrack here too, or a closed PS stays in the connection's
                // open-statement set until the connection closes.
                ownerConn.untrackStatement(this);
            }
        }
    }

    /* ===== Statement-level surface: forwarded to the delegate =====*/

    /**
     * Everything below is the {@code Statement} half of this prepared statement. Extending {@code
     * CUBRIDPreparedStatement} costs the {@link LBStatement} inheritance that used to supply these,
     * so each one forwards to {@link #statementDelegate}. The bodies are one line on purpose: the
     * behaviour lives in {@code LBStatement} and must not fork. A missing forwarder would fall
     * through to {@code CUBRIDStatement}, which dereferences a null {@code u_stmt}; {@code
     * LBStatementCastTest} fails the build if one is left out.
     */
    @Override
    public ResultSet executeQuery(final String sql) throws SQLException {
        return LBStatement.stampProducer(statementDelegate.executeQuery(sql), this);
    }

    @Override
    public int executeUpdate(final String sql) throws SQLException {
        return statementDelegate.executeUpdate(sql);
    }

    @Override
    public int executeUpdate(final String sql, final int autoGeneratedKeys) throws SQLException {
        return statementDelegate.executeUpdate(sql, autoGeneratedKeys);
    }

    @Override
    public int executeUpdate(final String sql, final int[] columnIndexes) throws SQLException {
        return statementDelegate.executeUpdate(sql, columnIndexes);
    }

    @Override
    public int executeUpdate(final String sql, final String[] columnNames) throws SQLException {
        return statementDelegate.executeUpdate(sql, columnNames);
    }

    @Override
    public boolean execute(final String sql) throws SQLException {
        return statementDelegate.execute(sql);
    }

    @Override
    public boolean execute(final String sql, final int autoGeneratedKeys) throws SQLException {
        return statementDelegate.execute(sql, autoGeneratedKeys);
    }

    @Override
    public boolean execute(final String sql, final int[] columnIndexes) throws SQLException {
        return statementDelegate.execute(sql, columnIndexes);
    }

    @Override
    public boolean execute(final String sql, final String[] columnNames) throws SQLException {
        return statementDelegate.execute(sql, columnNames);
    }

    @Override
    public void addBatch(final String sql) throws SQLException {
        statementDelegate.addBatch(sql);
    }

    @Override
    public void cancel() throws SQLException {
        statementDelegate.cancel();
    }

    @Override
    public void clearWarnings() throws SQLException {
        statementDelegate.clearWarnings();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return statementDelegate.getWarnings();
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        statementDelegate.closeOnCompletion();
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return statementDelegate.isCloseOnCompletion();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return statementDelegate.getConnection();
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return statementDelegate.getFetchDirection();
    }

    @Override
    public void setFetchDirection(final int direction) throws SQLException {
        statementDelegate.setFetchDirection(direction);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return statementDelegate.getFetchSize();
    }

    @Override
    public void setFetchSize(final int rows) throws SQLException {
        statementDelegate.setFetchSize(rows);
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return LBStatement.stampProducer(statementDelegate.getGeneratedKeys(), this);
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return statementDelegate.getMaxFieldSize();
    }

    @Override
    public void setMaxFieldSize(final int max) throws SQLException {
        statementDelegate.setMaxFieldSize(max);
    }

    @Override
    public int getMaxRows() throws SQLException {
        return statementDelegate.getMaxRows();
    }

    @Override
    public void setMaxRows(final int max) throws SQLException {
        statementDelegate.setMaxRows(max);
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return statementDelegate.getMoreResults();
    }

    @Override
    public boolean getMoreResults(final int current) throws SQLException {
        return statementDelegate.getMoreResults(current);
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return statementDelegate.getQueryTimeout();
    }

    @Override
    public void setQueryTimeout(final int seconds) throws SQLException {
        statementDelegate.setQueryTimeout(seconds);
    }

    /**
     * Re-stamped: the delegate names itself as the producer, and the application never received the
     * delegate. See {@link LBStatement#stampProducer(ResultSet, java.sql.Statement)}.
     */
    @Override
    public ResultSet getResultSet() throws SQLException {
        return LBStatement.stampProducer(statementDelegate.getResultSet(), this);
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return statementDelegate.getResultSetConcurrency();
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return statementDelegate.getResultSetHoldability();
    }

    @Override
    public int getResultSetType() throws SQLException {
        return statementDelegate.getResultSetType();
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return statementDelegate.getUpdateCount();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return statementDelegate.isClosed();
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return statementDelegate.isPoolable();
    }

    @Override
    public void setPoolable(final boolean poolable) throws SQLException {
        statementDelegate.setPoolable(poolable);
    }

    @Override
    public void setCursorName(final String name) throws SQLException {
        statementDelegate.setCursorName(name);
    }

    @Override
    public void setEscapeProcessing(final boolean enable) throws SQLException {
        statementDelegate.setEscapeProcessing(enable);
    }

    @Override
    public CUBRIDOID executeInsert(final String sql) throws SQLException {
        return statementDelegate.executeInsert(sql);
    }

    @Override
    public String getQueryplan() throws SQLException {
        return statementDelegate.getQueryplan();
    }

    @Override
    public String getQueryplan(final String sql) throws SQLException {
        return statementDelegate.getQueryplan(sql);
    }

    /* ===== vendor flags: inherited signatures declare no throws clause =====*/

    /**
     * {@code CUBRIDStatement} declares these without {@code throws SQLException} and an override
     * may not widen the inherited clause, so the delegate's checked variants are called and any
     * failure is reported unchecked. Same constraint and same shape as {@link
     * LBStatement#setQueryInfo}.
     */
    @Override
    public byte getStatementType() {
        return statementDelegate.getStatementType();
    }

    @Override
    public void setQueryInfo(final boolean value) {
        try {
            statementDelegate.setQueryInfoChecked(value);
        } catch (SQLException failure) {
            throw unchecked(failure);
        }
    }

    @Override
    public void setOnlyQueryPlan(final boolean value) {
        try {
            statementDelegate.setOnlyQueryPlanChecked(value);
        } catch (SQLException failure) {
            throw unchecked(failure);
        }
    }

    @Override
    public void setCurrentTransaction(final boolean isFromCurrentTransaction) {
        try {
            statementDelegate.setCurrentTransactionChecked(isFromCurrentTransaction);
        } catch (SQLException failure) {
            throw unchecked(failure);
        }
    }

    @Override
    public boolean isFromCurrentTransaction() {
        try {
            return statementDelegate.isFromCurrentTransactionChecked();
        } catch (SQLException failure) {
            throw unchecked(failure);
        }
    }

    /**
     * SHARD is not supported on a load-balanced session. Mirrors {@link LBStatement#getShardId()}.
     */
    @Override
    public int getShardId() {
        throw unchecked(LbExceptions.notSupportedApi("getShardId()"));
    }

    @Override
    protected void setShardId(final int shardId) {
        throw unchecked(LbExceptions.notSupportedApi("setShardId(int)"));
    }

    /* ===== Wrapper: answered here, not forwarded =====*/

    /**
     * Answered against this object rather than forwarded: the delegate would report {@code
     * LBStatement}, so {@code unwrap(CUBRIDPreparedStatement.class)} would fail on the very class
     * it is supposed to succeed for.
     */
    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return iface.cast(this);
        }

        throw LbExceptions.invalidValue("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
    }

    /* ===== inherited socket-level surface that must not run =====*/

    /**
     * These dereference {@code u_stmt}/{@code error}, null on a logical prepared statement (see
     * {@link
     * cubrid.jdbc.driver.CUBRIDPreparedStatement#CUBRIDPreparedStatement(cubrid.jdbc.driver.CUBRIDConnection)}).
     * Reaching one means a public entry point was left inherited: refuse loudly rather than throw a
     * NullPointerException from a stack trace that never mentions LB.
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

    /** Closed-state check for inherited code paths; the logical state lives in the delegate. */
    @Override
    protected void checkIsOpen() throws SQLException {
        statementDelegate.checkClosed();
    }

    /**
     * No-op: bind errors are raised by the physical prepared statement at bind time. The inherited
     * version reads the {@code error} field of a socket statement this object does not own, and
     * reports through the package-private {@code CUBRIDConnection.createCUBRIDException}, which
     * dereferences a null {@code u_con} on a load-balanced connection.
     */
    @Override
    protected void checkBindError() throws SQLException {}

    /**
     * Same mapping as the inherited version, but reported through {@link LbExceptions}. The
     * inherited one goes through the package-private {@code
     * CUBRIDConnection.createCUBRIDException(int, String, Throwable)}, which dereferences a null
     * {@code u_con} on a load-balanced connection, giving an NPE instead of the intended
     * SQLException. That method cannot be overridden from this package, so its caller is.
     */
    @Override
    protected int checkSqlType(final SQLType targetSqlType) throws SQLException {
        if (targetSqlType == null) {
            throw LbExceptions.invalidValue("setObject requires a non-null targetSqlType");
        }

        Integer vendorTypeNumber = targetSqlType.getVendorTypeNumber();
        if (vendorTypeNumber == null) {
            throw LbExceptions.invalidValue(
                    "targetSqlType has no vendor type number: " + targetSqlType.getName());
        }

        return vendorTypeNumber.intValue();
    }

    /* ===== SQLType parameter setters =====*/

    /**
     * Resolved to the {@code int} overloads, which record into the parameter binder. Overridden
     * rather than inherited only so the coverage guard can stay a strict "nothing inherited" rule;
     * the body matches the inherited one.
     */
    @Override
    public void setObject(final int parameterIndex, final Object x, final SQLType targetSqlType)
            throws SQLException {
        setObject(parameterIndex, x, checkSqlType(targetSqlType));
    }

    @Override
    public void setObject(
            final int parameterIndex,
            final Object x,
            final SQLType targetSqlType,
            final int scaleOrLength)
            throws SQLException {
        setObject(parameterIndex, x, checkSqlType(targetSqlType), scaleOrLength);
    }

    private Router.RouteTarget resolveRouteTarget() {
        // Routing precedence lives in Router.decide(); this only supplies the per-instance caches
        // (hint and classification precomputed at prepare time) so the hot execute path avoids
        // re-running HintParser.parse() and SqlClassifier.classify().
        return Router.decide(sqlCaches.targetFromHint, sqlCaches.defaultTarget, sessState);
    }

    private PreparedStatement physicalPsWithParams(final Router.RouteTarget target)
            throws SQLException {
        validateParamCountCompat();

        PreparedStatement physicalStatement = physicalPsForTarget(target);
        parameterBinder.replay(physicalStatement);

        return physicalStatement;
    }

    private PreparedStatement physicalPsForTarget(final Router.RouteTarget target)
            throws SQLException {
        // Use the target the caller resolved before executeWithFailover rather than re-resolving:
        // the session routing state may have changed in between, and a second resolveRouteTarget()
        // could pick a different target than the failover recovery context was built from, marking
        // the wrong endpoint role on failure.
        PreparedStatement ps = phyPsProvider.getPreparedStatement(target, sql);
        // The physical PS comes from the session cache, so statement attributes (queryTimeout,
        // maxRows, fetchSize, maxFieldSize, escapeProcessing, closeOnCompletion) are re-applied on
        // every execution: another logical PS sharing the endpoint may have changed them.
        // applyStmtProps also invokes applyVendorFlags.
        statementDelegate.applyStmtProps(ps);
        // Publish before executeQuery/executeUpdate/execute/executeBatch runs so cancel() can reach
        // the in-flight physical statement rather than the previous one.
        statementDelegate.publishExecStmt(ps);

        return ps;
    }

    private PreparedStatement physicalPsForMetadata(final Router.RouteTarget target)
            throws SQLException {
        PreparedStatement ps = phyPsProvider.getPreparedStatement(target, sql);
        statementDelegate.applyStmtProps(ps);

        return ps;
    }

    private void validateParamCountCompat() throws SQLException {
        int recordedMaxParameterIndex = parameterBinder.getMaxParameterIndex();
        if (recordedMaxParameterIndex > sqlCaches.expectedParameterCount) {
            throw LbExceptions.invalidValue(
                    "Parameter mismatch: max index "
                            + recordedMaxParameterIndex
                            + " exceeds placeholder count "
                            + sqlCaches.expectedParameterCount);
        }
    }

    private static int countParamPlaceholders(final String value) {
        if (value == null || value.length() == 0) {
            return 0;
        }

        int result = 0;

        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '?') {
                result++;
            }
        }

        return result;
    }

    private static final class SqlCaches {
        final Router.RouteTarget targetFromHint;

        final Router.RouteTarget defaultTarget;

        final int expectedParameterCount;

        private SqlCaches(
                final Router.RouteTarget targetFromHint,
                final Router.RouteTarget defaultTarget,
                final int expectedParameterCount) {
            this.targetFromHint = targetFromHint;
            this.defaultTarget = defaultTarget;
            this.expectedParameterCount = expectedParameterCount;
        }

        static SqlCaches compute(final String sql, final SqlClassifier classifier) {
            final HintSet hints = HintParser.parse(sql);

            final Router.RouteTarget targetFromHint =
                    hints.hasTargetHint() ? Router.mapTargetHint(hints.getTargetHint()) : null;
            final SqlClassification classification = classifier.classify(sql);

            final Router.RouteTarget defaultTarget =
                    SqlClassification.READ == classification
                            ? Router.RouteTarget.TO_READ_ONLY
                            : Router.RouteTarget.TO_READ_WRITE;
            return new SqlCaches(targetFromHint, defaultTarget, countParamPlaceholders(sql));
        }
    }

    private CUBRIDOID invokeExecInsert(final PreparedStatement statement) throws SQLException {
        if (!(statement instanceof CUBRIDPreparedStatement)) {
            throw LbExceptions.physicalDelegateFailed("executeInsert", null);
        }

        return ((CUBRIDPreparedStatement) statement).executeInsert();
    }

    /**
     * Wraps a checked failure for an inherited signature that declares no {@code throws} clause.
     */
    private static RuntimeException unchecked(final SQLException cause) {
        return new IllegalStateException(cause.getMessage(), cause);
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

    public interface PhysicalPsProvider {

        PreparedStatement getPreparedStatement(Router.RouteTarget target, String rawSql)
                throws SQLException;

        /**
         * Eager prepare on the session's bound READ endpoint - one endpoint, not several. A {@code
         * roOnRw} or RW-only session reaches the RW broker here only because its read endpoint
         * <em>is</em> the RW endpoint.
         *
         * @param rawSql the statement text to prepare
         * @throws SQLException if the prepare fails
         */
        void prepareOnBoundReadEndpoint(String rawSql) throws SQLException;

        /**
         * Eager prepare on the session's write endpoint, for a WRITE/UNKNOWN statement.
         *
         * @param rawSql the statement text to prepare
         * @throws SQLException if the prepare fails
         */
        void prepareOnWriteEndpoint(String rawSql) throws SQLException;

        void closePrepStmts() throws SQLException;
    }

    private static final class UnsupportedPsProvider implements PhysicalPsProvider {
        public PreparedStatement getPreparedStatement(
                final Router.RouteTarget target, final String rawSql) throws SQLException {
            throw LbExceptions.delegationNotConfigured();
        }

        public void prepareOnBoundReadEndpoint(final String rawSql) throws SQLException {}

        public void prepareOnWriteEndpoint(final String rawSql) throws SQLException {}

        public void closePrepStmts() throws SQLException {}
    }
}
