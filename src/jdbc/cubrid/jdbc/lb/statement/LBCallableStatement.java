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

import cubrid.jdbc.driver.CUBRIDCallableStatement;
import cubrid.jdbc.jci.UBatchResult;
import cubrid.jdbc.jci.UStatementCacheData;
import cubrid.jdbc.lb.LbExceptions;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.failover.ExecuteFailoverHandler.SqlExecution;
import cubrid.jdbc.lb.route.RoutedSql;
import cubrid.jdbc.lb.sql.SessionSetStatement;
import cubrid.sql.CUBRIDOID;
import cubrid.sql.CUBRIDTimestamptz;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
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
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The CallableStatement of a load-balanced connection: one physical statement on the write leg,
 * with every call passed to it, so the OUT-parameter API behaves as core's does.
 *
 * <p>It exists so LB sees a {@code CALL} run. A login or an {@code INTO} variable changes the
 * session when the statement runs, not when it is prepared, and only a statement LB sees run can be
 * followed on the read leg.
 *
 * <p>A failover or a failback replaces the write leg, and the physical statement closes with the
 * leg it was prepared on. Core replaces only the socket and prepares its statement again at the
 * next run; this one prepares the CALL again on the write leg of that run, with the values, OUT
 * registrations and settings it was given. Until then the closed statement is asked nothing: what
 * is set is kept, and what the last run left is refused as core refuses a closed statement.
 *
 * <p>It extends core's class so {@code (CUBRIDCallableStatement) cs} keeps working, and for that
 * overrides everything it inherits: the base holds no socket statement here.
 */
public class LBCallableStatement extends CUBRIDCallableStatement {

    /** The session's write leg, as this statement needs it; the connection supplies it. */
    public interface WriteLeg {
        /**
         * Whether {@code leg} is still the session's write leg. Asks no server.
         *
         * @param leg a physical connection
         * @return whether it is the write leg
         */
        boolean is(Connection leg);

        /**
         * The write leg, for the command that is running.
         *
         * @return the write leg
         * @throws SQLException if the session cannot open it
         */
        Connection now() throws SQLException;

        /**
         * The write leg a run of the CALL goes to, by the step every statement takes before it
         * runs: the failback is attempted, and the route is logged and counted. A statement that
         * {@code ranBefore} first has what that run left open in autocommit committed, as core
         * commits it at the next run, so that it does not hold the failback.
         *
         * @param ranBefore whether this statement ran before
         * @return the write leg
         * @throws SQLException if the commit or the leg fails
         */
        Connection forRun(boolean ranBefore) throws SQLException;

        /**
         * Prepares the CALL on {@code leg}, as the application's prepareCall did.
         *
         * @param leg the write leg
         * @return the physical statement
         * @throws SQLException if the prepare fails
         */
        CallableStatement prepare(Connection leg) throws SQLException;

        /**
         * Runs {@code command} as prepareCall runs, rebinding a leg whose broker is gone.
         *
         * @param <T> the command's result type
         * @param command the command
         * @return the command's result
         * @throws SQLException if the command fails
         */
        <T> T asCommand(SqlExecution<T> command) throws SQLException;
    }

    /** A statement setting, made again on the statement prepared after the leg was replaced. */
    private interface Setting {
        void on(CallableStatement target) throws SQLException;
    }

    private final LoadBalanceConnection lbConnection;
    private final String sql;
    private final WriteLeg writeLeg;
    // The physical statement, the write leg it was prepared on, and whether it has been given
    // everything this statement was.
    private CallableStatement call;
    private Connection leg;
    private boolean inStep = true;
    // Whether the physical statement has run: in autocommit a CALL leaves its transaction open.
    private boolean ran;

    /** Whether the last run took SQL, which statementDelegate ran and answers for. */
    private boolean lastRanSql;
    // What a statement prepared again is given: IN values, OUT registrations, settings by name.
    private final ParameterBinder parameterBinder;
    private final Map<String, Setting> settings = new LinkedHashMap<String, Setting>();
    // A login given its values with ?: the other legs run it with them.
    private final boolean valuesGoToOtherLegs;
    // Runs nothing itself: it is the execute path this statement's runs go through.
    private final LBStatement statementDelegate;

    /**
     * Prepares the CALL on the write leg.
     *
     * @param lbConnection the connection the application prepared it on
     * @param sql the CALL as the application wrote it
     * @param writeLeg the session's write leg and how to prepare on it
     * @throws SQLException if the connection is closed or the prepare fails
     */
    public LBCallableStatement(
            final LoadBalanceConnection lbConnection, final String sql, final WriteLeg writeLeg)
            throws SQLException {
        super(lbConnection);
        this.lbConnection = lbConnection;
        this.sql = sql;
        this.writeLeg = writeLeg;
        // Core's prepareCall statement is forward-only, read-only and closes its cursors at commit,
        // and runs SQL as itself.
        this.statementDelegate =
                new LBStatement(
                        lbConnection,
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY,
                        ResultSet.CLOSE_CURSORS_AT_COMMIT);
        this.parameterBinder =
                new ParameterBinder(
                        new ParameterBinder.BindCheck() {
                            public PreparedStatement statement() {
                                final CallableStatement attached = attached();
                                inStep &= attached != null; // kept only, until the next run
                                return attached;
                            }
                        });
        this.valuesGoToOtherLegs = SessionSetStatement.parse(sql).getBoundKey() != null;
        this.leg = writeLeg.now();
        this.call = writeLeg.prepare(leg);
    }

    /** The physical statement as core's class, for the methods JDBC does not declare. */
    private CUBRIDCallableStatement vendor() {
        return (CUBRIDCallableStatement) call;
    }

    /**
     * The physical statement to ask now: null while the leg it was prepared on is gone, and the
     * closed one, for core's refusal, once this statement is closed.
     */
    private CallableStatement attached() {
        return closed() || writeLeg.is(leg) ? call : null;
    }

    /** Closed here or with its connection, as core counts a statement closed. */
    private boolean closed() {
        try {
            return super.isClosed();
        } catch (SQLException unreadable) {
            return true;
        }
    }

    /** As {@link #attached()}, but prepared again, as prepareCall would, if its leg is gone. */
    private CallableStatement prepared() throws SQLException {
        final CallableStatement attached = attached();
        if (attached != null) {
            return attached;
        }

        return writeLeg.asCommand(
                new SqlExecution<CallableStatement>() {
                    public CallableStatement run() throws SQLException {
                        return onWriteLeg(false);
                    }
                });
    }

    /**
     * The physical statement on the write leg of the command that is running: the one held, or the
     * CALL prepared again there with what this statement was given.
     *
     * @param forRun whether the command is a run of the CALL, which is routed as a statement is
     */
    private CallableStatement onWriteLeg(final boolean forRun) throws SQLException {
        if (closed()) {
            return call; // core's refusal
        }
        if (forRun && valuesGoToOtherLegs) {
            statementDelegate.runsWith(parameterBinder.runOf(sql, true));
        }
        final Connection now = forRun ? writeLeg.forRun(ran) : writeLeg.now();
        if (now != leg || !inStep) {
            inStep = false; // prepared again if this does not finish
            ran = false;
            try {
                call.close(); // its leg may live on in another role
            } catch (SQLException alreadyClosed) {
                // closed with its leg
            }
            call = writeLeg.prepare(now);
            leg = now;
            parameterBinder.replay(call);
            for (Setting each : settings.values()) {
                each.on(call);
            }
            inStep = true;
        }

        return call;
    }

    /** Makes a setting on the physical statement, where core checks it, and keeps it. */
    private void keep(final String name, final Setting setting) throws SQLException {
        setting.on(prepared());
        settings.put(name, setting);
    }

    /** Core's refusal of a closed statement, from the physical one this closed. */
    private void checkOpen() throws SQLException {
        if (closed()) {
            call.getConnection();
        }
    }

    private static RuntimeException unchecked(final SQLException cause) {
        return new IllegalStateException(cause.getMessage(), cause);
    }

    /* ===== execution ===== */

    /**
     * Runs the CALL through the execute path every statement takes: open statements are prepared
     * before a login, the login or the INTO variable is recorded after it, and the run is routed
     * and counted. It is never run again: a stored procedure is not idempotent.
     */
    private <T> T runCall(final SqlExecution<T> execution) throws SQLException {
        lastRanSql = false;
        final T result =
                statementDelegate.executeWithFailover(
                        sql,
                        statementDelegate.routeFor(RoutedSql.of(sql, lbConnection.sqlClassifier())),
                        false,
                        execution);
        ran = true;

        return result;
    }

    @Override
    public boolean execute() throws SQLException {
        return runCall(
                        new SqlExecution<Boolean>() {
                            public Boolean run() throws SQLException {
                                return Boolean.valueOf(onWriteLeg(true).execute());
                            }
                        })
                .booleanValue();
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        return LBStatement.stampProducer(
                runCall(
                        new SqlExecution<ResultSet>() {
                            public ResultSet run() throws SQLException {
                                return onWriteLeg(true).executeQuery();
                            }
                        }),
                this);
    }

    @Override
    public int executeUpdate() throws SQLException {
        return runCall(
                        new SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                return Integer.valueOf(onWriteLeg(true).executeUpdate());
                            }
                        })
                .intValue();
    }

    @Override
    public CUBRIDOID executeInsert() throws SQLException {
        return runCall(
                new SqlExecution<CUBRIDOID>() {
                    public CUBRIDOID run() throws SQLException {
                        onWriteLeg(true);
                        return vendor().executeInsert();
                    }
                });
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        return LBStatement.stampProducer(
                lastRanSql ? statementDelegate.getResultSet() : call.getResultSet(), this);
    }

    @Override
    /** Core's keys result names no statement, so neither does this one. */
    public ResultSet getGeneratedKeys() throws SQLException {
        return lastRanSql ? statementDelegate.getGeneratedKeys() : call.getGeneratedKeys();
    }

    /**
     * See {@link LBStatement#openCursorConnection()}: a result set of the CALL or of SQL this
     * statement ran.
     *
     * @return the connection holding an open cursor, or null
     */
    public Connection openCursorConnection() {
        final Connection underSql = statementDelegate.openCursorConnection();
        if (underSql != null) {
            return underSql;
        }
        try {
            // Not call.getConnection(): the CALL reports this connection as its own.
            final ResultSet rs = call.getResultSet();
            return rs == null || rs.isClosed() ? null : leg;
        } catch (SQLException closedOrGone) {
            return null; // a result set that cannot answer cannot be read either
        }
    }

    @Override
    public void close() throws SQLException {
        final boolean legGone = attached() == null;
        is_closed = true; // core's own closed flag, which its isClosed() reads
        try {
            call.close();
        } catch (SQLException failure) {
            // Core can refuse to close a statement whose connection is closed. The application
            // closed no connection when LB replaced the leg.
            if (!legGone) {
                throw failure;
            }
        } finally {
            statementDelegate.close();
            lbConnection.untrackStatement(this);
        }
    }

    /* ===== answered here ===== */

    @Override
    public Connection getConnection() throws SQLException {
        checkOpen();
        return lbConnection;
    }

    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
        checkOpen();
        if (iface.isAssignableFrom(getClass())) {
            return iface.cast(this);
        }

        throw LbExceptions.cannotUnwrap(iface);
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) throws SQLException {
        checkOpen();
        return iface.isAssignableFrom(getClass());
    }

    /* ===== refused, as on the other statements of a load-balanced connection ===== */

    @Override
    public int getShardId() {
        throw unchecked(LbExceptions.notSupportedApi("getShardId()"));
    }

    @Override
    protected void setShardId(final int shardId) {
        throw unchecked(LbExceptions.notSupportedApi("setShardId(int)"));
    }

    @Override
    public void setReportedConnection(final Connection reported) {
        throw unchecked(LbExceptions.notSupportedApi("setReportedConnection(Connection)"));
    }

    /* ===== core's socket-level internals: the physical statement runs its own ===== */

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

    @Override
    protected void checkBindError() throws SQLException {}

    /* ===== java.sql.CallableStatement: OUT parameters and named parameters ===== */

    @Override
    public boolean wasNull() throws SQLException {
        return call.wasNull();
    }

    @Override
    public int getInt(final int index) throws SQLException {
        return call.getInt(index);
    }

    @Override
    public String getString(final int index) throws SQLException {
        return call.getString(index);
    }

    @Override
    public boolean getBoolean(final int index) throws SQLException {
        return call.getBoolean(index);
    }

    @Override
    public byte getByte(final int index) throws SQLException {
        return call.getByte(index);
    }

    @Override
    public short getShort(final int index) throws SQLException {
        return call.getShort(index);
    }

    @Override
    public long getLong(final int index) throws SQLException {
        return call.getLong(index);
    }

    @Override
    public float getFloat(final int index) throws SQLException {
        return call.getFloat(index);
    }

    @Override
    public double getDouble(final int index) throws SQLException {
        return call.getDouble(index);
    }

    @Override
    public byte[] getBytes(final int index) throws SQLException {
        return call.getBytes(index);
    }

    @Override
    public Date getDate(final int index) throws SQLException {
        return call.getDate(index);
    }

    @Override
    public Time getTime(final int index) throws SQLException {
        return call.getTime(index);
    }

    @Override
    public Timestamp getTimestamp(final int index) throws SQLException {
        return call.getTimestamp(index);
    }

    @Override
    public Object getObject(final int index) throws SQLException {
        return call.getObject(index);
    }

    @Override
    public BigDecimal getBigDecimal(final int index) throws SQLException {
        return call.getBigDecimal(index);
    }

    @Override
    public BigDecimal getBigDecimal(final int index, final int scale) throws SQLException {
        return call.getBigDecimal(index, scale);
    }

    @Override
    public Ref getRef(final int i) throws SQLException {
        return call.getRef(i);
    }

    @Override
    public Blob getBlob(final int index) throws SQLException {
        return call.getBlob(index);
    }

    @Override
    public Clob getClob(final int index) throws SQLException {
        return call.getClob(index);
    }

    @Override
    public Array getArray(final int i) throws SQLException {
        return call.getArray(i);
    }

    @Override
    public Date getDate(final int index, final Calendar cal) throws SQLException {
        return call.getDate(index, cal);
    }

    @Override
    public Time getTime(final int index, final Calendar cal) throws SQLException {
        return call.getTime(index, cal);
    }

    @Override
    public Timestamp getTimestamp(final int index, final Calendar cal) throws SQLException {
        return call.getTimestamp(index, cal);
    }

    @Override
    public URL getURL(final int index) throws SQLException {
        return call.getURL(index);
    }

    @Override
    public void setURL(final String pName, final URL val) throws SQLException {
        call.setURL(pName, val);
    }

    @Override
    public void setNull(final String pName, final int sqlType) throws SQLException {
        call.setNull(pName, sqlType);
    }

    @Override
    public void setBoolean(final String pName, final boolean x) throws SQLException {
        call.setBoolean(pName, x);
    }

    @Override
    public void setByte(final String pName, final byte x) throws SQLException {
        call.setByte(pName, x);
    }

    @Override
    public void setShort(final String pName, final short x) throws SQLException {
        call.setShort(pName, x);
    }

    @Override
    public void setInt(final String pName, final int x) throws SQLException {
        call.setInt(pName, x);
    }

    @Override
    public void setLong(final String pName, final long x) throws SQLException {
        call.setLong(pName, x);
    }

    @Override
    public void setFloat(final String pName, final float x) throws SQLException {
        call.setFloat(pName, x);
    }

    @Override
    public void setDouble(final String pName, final double x) throws SQLException {
        call.setDouble(pName, x);
    }

    @Override
    public void setBigDecimal(final String pName, final BigDecimal x) throws SQLException {
        call.setBigDecimal(pName, x);
    }

    @Override
    public void setString(final String pName, final String x) throws SQLException {
        call.setString(pName, x);
    }

    @Override
    public void setBytes(final String pName, final byte[] x) throws SQLException {
        call.setBytes(pName, x);
    }

    @Override
    public void setDate(final String pName, final Date x) throws SQLException {
        call.setDate(pName, x);
    }

    @Override
    public void setTime(final String pName, final Time x) throws SQLException {
        call.setTime(pName, x);
    }

    @Override
    public void setTimestamp(final String pName, final Timestamp x) throws SQLException {
        call.setTimestamp(pName, x);
    }

    @Override
    public void setTimestamptz(final String pName, final CUBRIDTimestamptz x) throws SQLException {
        vendor().setTimestamptz(pName, x);
    }

    @Override
    public void setAsciiStream(final String pName, final InputStream x, final int length)
            throws SQLException {
        call.setAsciiStream(pName, x, length);
    }

    @Override
    public void setBinaryStream(final String pName, final InputStream x, final int length)
            throws SQLException {
        call.setBinaryStream(pName, x, length);
    }

    @Override
    public void setObject(
            final String pName, final Object x, final int targetSqlType, final int scale)
            throws SQLException {
        call.setObject(pName, x, targetSqlType, scale);
    }

    @Override
    public void setObject(final String pName, final Object x, final int targetSqlType)
            throws SQLException {
        call.setObject(pName, x, targetSqlType);
    }

    @Override
    public void setObject(final String pName, final Object x) throws SQLException {
        call.setObject(pName, x);
    }

    @Override
    public void setCharacterStream(final String pName, final Reader reader, final int length)
            throws SQLException {
        call.setCharacterStream(pName, reader, length);
    }

    @Override
    public void setDate(final String pName, final Date x, final Calendar cal) throws SQLException {
        call.setDate(pName, x, cal);
    }

    @Override
    public void setTime(final String pName, final Time x, final Calendar cal) throws SQLException {
        call.setTime(pName, x, cal);
    }

    @Override
    public void setTimestamp(final String pName, final Timestamp x, final Calendar cal)
            throws SQLException {
        call.setTimestamp(pName, x, cal);
    }

    @Override
    public void setTimestamptz(final String pName, final CUBRIDTimestamptz x, final Calendar cal)
            throws SQLException {
        vendor().setTimestamptz(pName, x, cal);
    }

    @Override
    public void setNull(final String pName, final int sqlType, final String typeName)
            throws SQLException {
        call.setNull(pName, sqlType, typeName);
    }

    @Override
    public String getString(final String pName) throws SQLException {
        return call.getString(pName);
    }

    @Override
    public boolean getBoolean(final String pName) throws SQLException {
        return call.getBoolean(pName);
    }

    @Override
    public byte getByte(final String pName) throws SQLException {
        return call.getByte(pName);
    }

    @Override
    public short getShort(final String pName) throws SQLException {
        return call.getShort(pName);
    }

    @Override
    public int getInt(final String pName) throws SQLException {
        return call.getInt(pName);
    }

    @Override
    public long getLong(final String pName) throws SQLException {
        return call.getLong(pName);
    }

    @Override
    public float getFloat(final String pName) throws SQLException {
        return call.getFloat(pName);
    }

    @Override
    public double getDouble(final String pName) throws SQLException {
        return call.getDouble(pName);
    }

    @Override
    public byte[] getBytes(final String pName) throws SQLException {
        return call.getBytes(pName);
    }

    @Override
    public Date getDate(final String pName) throws SQLException {
        return call.getDate(pName);
    }

    @Override
    public Time getTime(final String pName) throws SQLException {
        return call.getTime(pName);
    }

    @Override
    public Timestamp getTimestamp(final String pName) throws SQLException {
        return call.getTimestamp(pName);
    }

    @Override
    public Object getObject(final String pName) throws SQLException {
        return call.getObject(pName);
    }

    @Override
    public Object getObject(final int i, final Map<String, Class<?>> map) throws SQLException {
        return call.getObject(i, map);
    }

    @Override
    public Object getObject(final String pName, final Map<String, Class<?>> map)
            throws SQLException {
        return call.getObject(pName, map);
    }

    @Override
    public BigDecimal getBigDecimal(final String pName) throws SQLException {
        return call.getBigDecimal(pName);
    }

    @Override
    public Ref getRef(final String pName) throws SQLException {
        return call.getRef(pName);
    }

    @Override
    public Blob getBlob(final String pName) throws SQLException {
        return call.getBlob(pName);
    }

    @Override
    public Clob getClob(final String pName) throws SQLException {
        return call.getClob(pName);
    }

    @Override
    public Array getArray(final String pName) throws SQLException {
        return call.getArray(pName);
    }

    @Override
    public Date getDate(final String pName, final Calendar cal) throws SQLException {
        return call.getDate(pName, cal);
    }

    @Override
    public Time getTime(final String pName, final Calendar cal) throws SQLException {
        return call.getTime(pName, cal);
    }

    @Override
    public Timestamp getTimestamp(final String pName, final Calendar cal) throws SQLException {
        return call.getTimestamp(pName, cal);
    }

    @Override
    public URL getURL(final String pName) throws SQLException {
        return call.getURL(pName);
    }

    @Override
    public void registerOutParameter(final int index, final int sqlType) throws SQLException {
        parameterBinder.recordRegisterOutParameter(index, sqlType);
    }

    @Override
    public void registerOutParameter(final int index, final int sqlType, final int scale)
            throws SQLException {
        parameterBinder.recordRegisterOutParameter(index, sqlType, scale);
    }

    @Override
    public void registerOutParameter(final int index, final int sqlType, final String typeName)
            throws SQLException {
        parameterBinder.recordRegisterOutParameter(index, sqlType, typeName);
    }

    @Override
    public void registerOutParameter(final String pName, final int sqlType) throws SQLException {
        call.registerOutParameter(pName, sqlType);
    }

    @Override
    public void registerOutParameter(final String pName, final int sqlType, final int scale)
            throws SQLException {
        call.registerOutParameter(pName, sqlType, scale);
    }

    @Override
    public void registerOutParameter(final String pName, final int sqlType, final String typeName)
            throws SQLException {
        call.registerOutParameter(pName, sqlType, typeName);
    }

    @Override
    public int[] executeBatch() throws SQLException {
        return call.executeBatch();
    }

    @Override
    public void addBatch() throws SQLException {
        call.addBatch();
    }

    @Override
    public Reader getCharacterStream(final int parameterIndex) throws SQLException {
        return call.getCharacterStream(parameterIndex);
    }

    @Override
    public Reader getCharacterStream(final String parameterName) throws SQLException {
        return call.getCharacterStream(parameterName);
    }

    @Override
    public Reader getNCharacterStream(final int parameterIndex) throws SQLException {
        return call.getNCharacterStream(parameterIndex);
    }

    @Override
    public Reader getNCharacterStream(final String parameterName) throws SQLException {
        return call.getNCharacterStream(parameterName);
    }

    @Override
    public NClob getNClob(final int parameterIndex) throws SQLException {
        return call.getNClob(parameterIndex);
    }

    @Override
    public NClob getNClob(final String parameterName) throws SQLException {
        return call.getNClob(parameterName);
    }

    @Override
    public String getNString(final int parameterIndex) throws SQLException {
        return call.getNString(parameterIndex);
    }

    @Override
    public String getNString(final String parameterName) throws SQLException {
        return call.getNString(parameterName);
    }

    @Override
    public RowId getRowId(final int parameterIndex) throws SQLException {
        return call.getRowId(parameterIndex);
    }

    @Override
    public RowId getRowId(final String parameterName) throws SQLException {
        return call.getRowId(parameterName);
    }

    @Override
    public SQLXML getSQLXML(final int parameterIndex) throws SQLException {
        return call.getSQLXML(parameterIndex);
    }

    @Override
    public SQLXML getSQLXML(final String parameterName) throws SQLException {
        return call.getSQLXML(parameterName);
    }

    @Override
    public void setAsciiStream(final String parameterName, final InputStream x)
            throws SQLException {
        call.setAsciiStream(parameterName, x);
    }

    @Override
    public void setAsciiStream(final String parameterName, final InputStream x, final long length)
            throws SQLException {
        call.setAsciiStream(parameterName, x, length);
    }

    @Override
    public void setBinaryStream(final String parameterName, final InputStream x)
            throws SQLException {
        call.setBinaryStream(parameterName, x);
    }

    @Override
    public void setBinaryStream(final String parameterName, final InputStream x, final long length)
            throws SQLException {
        call.setBinaryStream(parameterName, x, length);
    }

    @Override
    public void setBlob(final String parameterName, final Blob x) throws SQLException {
        call.setBlob(parameterName, x);
    }

    @Override
    public void setBlob(final String parameterName, final InputStream inputStream)
            throws SQLException {
        call.setBlob(parameterName, inputStream);
    }

    @Override
    public void setBlob(
            final String parameterName, final InputStream inputStream, final long length)
            throws SQLException {
        call.setBlob(parameterName, inputStream, length);
    }

    @Override
    public void setCharacterStream(final String parameterName, final Reader reader)
            throws SQLException {
        call.setCharacterStream(parameterName, reader);
    }

    @Override
    public void setCharacterStream(
            final String parameterName, final Reader reader, final long length)
            throws SQLException {
        call.setCharacterStream(parameterName, reader, length);
    }

    @Override
    public void setClob(final String parameterName, final Clob x) throws SQLException {
        call.setClob(parameterName, x);
    }

    @Override
    public void setClob(final String parameterName, final Reader reader) throws SQLException {
        call.setClob(parameterName, reader);
    }

    @Override
    public void setClob(final String parameterName, final Reader reader, final long length)
            throws SQLException {
        call.setClob(parameterName, reader, length);
    }

    @Override
    public void setNCharacterStream(final String parameterName, final Reader value)
            throws SQLException {
        call.setNCharacterStream(parameterName, value);
    }

    @Override
    public void setNCharacterStream(
            final String parameterName, final Reader value, final long length) throws SQLException {
        call.setNCharacterStream(parameterName, value, length);
    }

    @Override
    public void setNClob(final String parameterName, final NClob value) throws SQLException {
        call.setNClob(parameterName, value);
    }

    @Override
    public void setNClob(final String parameterName, final Reader reader) throws SQLException {
        call.setNClob(parameterName, reader);
    }

    @Override
    public void setNClob(final String parameterName, final Reader reader, final long length)
            throws SQLException {
        call.setNClob(parameterName, reader, length);
    }

    @Override
    public void setNString(final String parameterName, final String value) throws SQLException {
        call.setNString(parameterName, value);
    }

    @Override
    public void setRowId(final String parameterName, final RowId x) throws SQLException {
        call.setRowId(parameterName, x);
    }

    @Override
    public void setSQLXML(final String parameterName, final SQLXML xmlObject) throws SQLException {
        call.setSQLXML(parameterName, xmlObject);
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        call.closeOnCompletion();
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return call.isCloseOnCompletion();
    }

    @Override
    public <T> T getObject(final int parameterIndex, final Class<T> type) throws SQLException {
        return call.getObject(parameterIndex, type);
    }

    @Override
    public <T> T getObject(final String parameterName, final Class<T> type) throws SQLException {
        return call.getObject(parameterName, type);
    }

    // Core resolves the SQLType forms to the int forms above, which keep the registration.
    @Override
    public void registerOutParameter(final int parameterIndex, final SQLType sqlType)
            throws SQLException {
        super.registerOutParameter(parameterIndex, sqlType);
    }

    @Override
    public void registerOutParameter(
            final int parameterIndex, final SQLType sqlType, final int scale) throws SQLException {
        super.registerOutParameter(parameterIndex, sqlType, scale);
    }

    @Override
    public void registerOutParameter(
            final int parameterIndex, final SQLType sqlType, final String typeName)
            throws SQLException {
        super.registerOutParameter(parameterIndex, sqlType, typeName);
    }

    /* ===== java.sql.PreparedStatement ===== */

    // The Statement forms that take SQL run as an LB statement, as on LBPreparedStatement: given
    // to the physical call they would run on RW unseen, a SET among them.

    /**
     * The statement a Statement form runs its SQL on, which then answers the result getters. Core
     * refuses it once this statement is closed, with its connection too.
     */
    private LBStatement sqlRun() throws SQLException {
        if (closed()) {
            throw LbExceptions.statementClosed();
        }
        lastRanSql = true;
        return statementDelegate;
    }

    @Override
    public ResultSet executeQuery(final String sql) throws SQLException {
        return LBStatement.stampProducer(sqlRun().executeQuery(sql), this);
    }

    @Override
    public int executeUpdate(final String sql) throws SQLException {
        return sqlRun().executeUpdate(sql);
    }

    @Override
    public int executeUpdate(final String sql, final int autoGeneratedKeys) throws SQLException {
        return sqlRun().executeUpdate(sql, autoGeneratedKeys);
    }

    @Override
    public int executeUpdate(final String sql, final int[] columnIndexes) throws SQLException {
        return sqlRun().executeUpdate(sql, columnIndexes);
    }

    @Override
    public int executeUpdate(final String sql, final String[] columnNames) throws SQLException {
        return sqlRun().executeUpdate(sql, columnNames);
    }

    @Override
    public boolean execute(final String sql) throws SQLException {
        return sqlRun().execute(sql);
    }

    @Override
    public boolean execute(final String sql, final int autoGeneratedKeys) throws SQLException {
        return sqlRun().execute(sql, autoGeneratedKeys);
    }

    @Override
    public boolean execute(final String sql, final int[] columnIndexes) throws SQLException {
        return sqlRun().execute(sql, columnIndexes);
    }

    @Override
    public boolean execute(final String sql, final String[] columnNames) throws SQLException {
        return sqlRun().execute(sql, columnNames);
    }

    @Override
    public CUBRIDOID executeInsert(final String sql) throws SQLException {
        return sqlRun().executeInsert(sql);
    }

    @Override
    public void setNull(final int parameterIndex, final int sqlType) throws SQLException {
        parameterBinder.recordSetNull(parameterIndex, sqlType);
    }

    @Override
    public void setBoolean(final int parameterIndex, final boolean x) throws SQLException {
        parameterBinder.recordSetBoolean(parameterIndex, x);
    }

    @Override
    public void setByte(final int parameterIndex, final byte x) throws SQLException {
        parameterBinder.recordSetByte(parameterIndex, x);
    }

    @Override
    public void setShort(final int parameterIndex, final short x) throws SQLException {
        parameterBinder.recordSetShort(parameterIndex, x);
    }

    @Override
    public void setInt(final int parameterIndex, final int x) throws SQLException {
        parameterBinder.recordSetInt(parameterIndex, x);
    }

    @Override
    public void setLong(final int parameterIndex, final long x) throws SQLException {
        parameterBinder.recordSetLong(parameterIndex, x);
    }

    @Override
    public void setFloat(final int parameterIndex, final float x) throws SQLException {
        parameterBinder.recordSetFloat(parameterIndex, x);
    }

    @Override
    public void setDouble(final int parameterIndex, final double x) throws SQLException {
        parameterBinder.recordSetDouble(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(final int parameterIndex, final BigDecimal x) throws SQLException {
        parameterBinder.recordSetBigDecimal(parameterIndex, x);
    }

    @Override
    public void setString(final int parameterIndex, final String x) throws SQLException {
        parameterBinder.recordSetString(parameterIndex, x);
    }

    @Override
    public void setBytes(final int parameterIndex, final byte[] x) throws SQLException {
        parameterBinder.recordSetBytes(parameterIndex, x);
    }

    @Override
    public void setDate(final int parameterIndex, final Date x) throws SQLException {
        parameterBinder.recordSetDate(parameterIndex, x);
    }

    @Override
    public void setTime(final int parameterIndex, final Time x) throws SQLException {
        parameterBinder.recordSetTime(parameterIndex, x);
    }

    @Override
    public void setTimestamp(final int parameterIndex, final Timestamp x) throws SQLException {
        parameterBinder.recordSetTimestamp(parameterIndex, x);
    }

    @Override
    public void setTimestamptz(final int parameterIndex, final CUBRIDTimestamptz x)
            throws SQLException {
        parameterBinder.recordSetTimestamptz(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(final int parameterIndex, final InputStream x, final int length)
            throws SQLException {
        parameterBinder.recordSetAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setUnicodeStream(final int parameterIndex, final InputStream x, final int length)
            throws SQLException {
        call.setUnicodeStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(final int parameterIndex, final InputStream x, final int length)
            throws SQLException {
        parameterBinder.recordSetBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void clearParameters() throws SQLException {
        final CallableStatement attached = attached();
        if (attached != null) {
            attached.clearParameters();
        }
        inStep &= attached != null;
        parameterBinder.clear();
    }

    @Override
    public void setObject(
            final int parameterIndex, final Object x, final int targetSqlType, final int scale)
            throws SQLException {
        parameterBinder.recordSetObject(parameterIndex, x, targetSqlType, scale);
    }

    @Override
    public void setObject(final int parameterIndex, final Object x, final int targetSqlType)
            throws SQLException {
        parameterBinder.recordSetObject(parameterIndex, x, targetSqlType);
    }

    @Override
    public void setObject(final int parameterIndex, final Object x) throws SQLException {
        parameterBinder.recordSetObject(parameterIndex, x);
    }

    @Override
    public void setCharacterStream(final int parameterIndex, final Reader reader, final int length)
            throws SQLException {
        parameterBinder.recordSetCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setRef(final int i, final Ref x) throws SQLException {
        call.setRef(i, x);
    }

    @Override
    public void setBlob(final int parameterIndex, final Blob x) throws SQLException {
        parameterBinder.recordSetBlob(parameterIndex, x);
    }

    @Override
    public void setClob(final int parameterIndex, final Clob x) throws SQLException {
        parameterBinder.recordSetClob(parameterIndex, x);
    }

    // Core writes the stream to a LOB the connection makes and binds it through the form above.
    @Override
    public void setBlob(final int parameterIndex, final InputStream inputStream)
            throws SQLException {
        super.setBlob(parameterIndex, inputStream);
    }

    @Override
    public void setBlob(final int parameterIndex, final InputStream inputStream, final long length)
            throws SQLException {
        super.setBlob(parameterIndex, inputStream, length);
    }

    @Override
    public void setClob(final int parameterIndex, final Reader reader) throws SQLException {
        super.setClob(parameterIndex, reader);
    }

    @Override
    public void setClob(final int parameterIndex, final Reader reader, final long length)
            throws SQLException {
        super.setClob(parameterIndex, reader, length);
    }

    @Override
    public void setArray(final int i, final Array x) throws SQLException {
        call.setArray(i, x);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return prepared().getMetaData();
    }

    @Override
    public void setDate(final int parameterIndex, final Date x, final Calendar cal)
            throws SQLException {
        parameterBinder.recordSetDate(parameterIndex, x, cal);
    }

    @Override
    public void setTime(final int parameterIndex, final Time x, final Calendar cal)
            throws SQLException {
        parameterBinder.recordSetTime(parameterIndex, x, cal);
    }

    @Override
    public void setTimestamp(final int parameterIndex, final Timestamp x, final Calendar cal)
            throws SQLException {
        parameterBinder.recordSetTimestamp(parameterIndex, x, cal);
    }

    @Override
    public void setTimestamptz(
            final int parameterIndex, final CUBRIDTimestamptz x, final Calendar cal)
            throws SQLException {
        parameterBinder.recordSetTimestamptz(parameterIndex, x, cal);
    }

    @Override
    public void setNull(final int paramIndex, final int sqlType, final String typeName)
            throws SQLException {
        parameterBinder.recordSetNull(paramIndex, sqlType, typeName);
    }

    @Override
    public void clearBatch() throws SQLException {
        final CallableStatement attached = attached();
        if (attached != null) {
            attached.clearBatch();
        }
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return prepared().getParameterMetaData();
    }

    @Override
    public void setURL(final int index, final URL x) throws SQLException {
        call.setURL(index, x);
    }

    @Override
    public void setOID(final int parameterIndex, final CUBRIDOID x) throws SQLException {
        parameterBinder.recordSetOID(parameterIndex, x);
    }

    @Override
    public void setCollection(final int parameterIndex, final Object[] x) throws SQLException {
        parameterBinder.recordSetCollection(parameterIndex, x);
    }

    @Override
    public boolean hasResultSet() {
        return vendor().hasResultSet();
    }

    @Override
    public void setBinaryStream(final int parameterIndex, final InputStream x) throws SQLException {
        call.setBinaryStream(parameterIndex, x);
    }

    @Override
    public void setBinaryStream(final int parameterIndex, final InputStream x, final long length)
            throws SQLException {
        call.setBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void setAsciiStream(final int parameterIndex, final InputStream x) throws SQLException {
        call.setAsciiStream(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(final int parameterIndex, final InputStream x, final long length)
            throws SQLException {
        call.setAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setCharacterStream(final int parameterIndex, final Reader reader)
            throws SQLException {
        call.setCharacterStream(parameterIndex, reader);
    }

    @Override
    public void setCharacterStream(final int parameterIndex, final Reader reader, final long length)
            throws SQLException {
        call.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setNCharacterStream(final int parameterIndex, final Reader value)
            throws SQLException {
        call.setNCharacterStream(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(final int parameterIndex, final Reader value, final long length)
            throws SQLException {
        call.setNCharacterStream(parameterIndex, value, length);
    }

    @Override
    public void setNClob(final int parameterIndex, final NClob value) throws SQLException {
        call.setNClob(parameterIndex, value);
    }

    @Override
    public void setNClob(final int parameterIndex, final Reader reader) throws SQLException {
        call.setNClob(parameterIndex, reader);
    }

    @Override
    public void setNClob(final int parameterIndex, final Reader reader, final long length)
            throws SQLException {
        call.setNClob(parameterIndex, reader, length);
    }

    @Override
    public void setNString(final int parameterIndex, final String value) throws SQLException {
        call.setNString(parameterIndex, value);
    }

    @Override
    public void setRowId(final int parameterIndex, final RowId x) throws SQLException {
        call.setRowId(parameterIndex, x);
    }

    @Override
    public void setSQLXML(final int parameterIndex, final SQLXML xmlObject) throws SQLException {
        call.setSQLXML(parameterIndex, xmlObject);
    }

    @Override
    public void setObject(final int parameterIndex, final Object x, final SQLType targetSqlType)
            throws SQLException {
        super.setObject(parameterIndex, x, targetSqlType);
    }

    @Override
    public void setObject(
            final int parameterIndex,
            final Object x,
            final SQLType targetSqlType,
            final int scaleOrLength)
            throws SQLException {
        super.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }

    /* ===== java.sql.Statement ===== */

    @Override
    public int getMaxFieldSize() throws SQLException {
        return prepared().getMaxFieldSize();
    }

    @Override
    public void setMaxFieldSize(final int max) throws SQLException {
        statementDelegate.setMaxFieldSize(max);
        keep(
                "maxFieldSize",
                new Setting() {
                    public void on(final CallableStatement target) throws SQLException {
                        target.setMaxFieldSize(max);
                    }
                });
    }

    @Override
    public int getMaxRows() throws SQLException {
        return prepared().getMaxRows();
    }

    @Override
    public void setMaxRows(final int max) throws SQLException {
        statementDelegate.setMaxRows(max);
        keep(
                "maxRows",
                new Setting() {
                    public void on(final CallableStatement target) throws SQLException {
                        target.setMaxRows(max);
                    }
                });
    }

    @Override
    public void setEscapeProcessing(final boolean enable) throws SQLException {
        final CallableStatement attached = attached();
        if (attached != null) {
            attached.setEscapeProcessing(enable);
        }
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return prepared().getQueryTimeout();
    }

    @Override
    public void setQueryTimeout(final int seconds) throws SQLException {
        statementDelegate.setQueryTimeout(seconds);
        keep(
                "queryTimeout",
                new Setting() {
                    public void on(final CallableStatement target) throws SQLException {
                        target.setQueryTimeout(seconds);
                    }
                });
    }

    @Override
    public void cancel() throws SQLException {
        if (lastRanSql) {
            statementDelegate.cancel();
            return;
        }
        final CallableStatement attached = attached();
        if (attached != null) {
            attached.cancel();
        }
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        final CallableStatement attached = attached();
        return attached == null ? null : attached.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        final CallableStatement attached = attached();
        if (attached != null) {
            attached.clearWarnings();
        }
    }

    @Override
    public void setCursorName(final String name) throws SQLException {
        final CallableStatement attached = attached();
        if (attached != null) {
            attached.setCursorName(name);
        }
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return lastRanSql ? statementDelegate.getUpdateCount() : call.getUpdateCount();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return lastRanSql ? statementDelegate.getMoreResults() : call.getMoreResults();
    }

    @Override
    public void setFetchDirection(final int direction) throws SQLException {
        statementDelegate.setFetchDirection(direction);
        keep(
                "fetchDirection",
                new Setting() {
                    public void on(final CallableStatement target) throws SQLException {
                        target.setFetchDirection(direction);
                    }
                });
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return prepared().getFetchDirection();
    }

    @Override
    public void setFetchSize(final int rows) throws SQLException {
        statementDelegate.setFetchSize(rows);
        keep(
                "fetchSize",
                new Setting() {
                    public void on(final CallableStatement target) throws SQLException {
                        target.setFetchSize(rows);
                    }
                });
    }

    @Override
    public int getFetchSize() throws SQLException {
        return prepared().getFetchSize();
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return prepared().getResultSetConcurrency();
    }

    @Override
    public int getResultSetType() throws SQLException {
        return prepared().getResultSetType();
    }

    @Override
    public void addBatch(final String sql) throws SQLException {
        final CallableStatement attached = attached();
        if (attached != null) {
            attached.addBatch(sql);
        }
    }

    @Override
    public boolean getMoreResults(final int current) throws SQLException {
        return lastRanSql
                ? statementDelegate.getMoreResults(current)
                : call.getMoreResults(current);
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return prepared().getResultSetHoldability();
    }

    @Override
    public byte getStatementType() {
        return vendor().getStatementType();
    }

    @Override
    public String getQueryplan(final String sql) throws SQLException {
        return ((CUBRIDCallableStatement) prepared()).getQueryplan(sql);
    }

    @Override
    public String getQueryplan() throws SQLException {
        return vendor().getQueryplan();
    }

    @Override
    public void setQueryInfo(final boolean value) {
        statementDelegate.setQueryInfo(value);
        vendor().setQueryInfo(value);
        settings.put(
                "queryInfo",
                new Setting() {
                    public void on(final CallableStatement target) {
                        ((CUBRIDCallableStatement) target).setQueryInfo(value);
                    }
                });
    }

    @Override
    public void setOnlyQueryPlan(final boolean value) {
        statementDelegate.setOnlyQueryPlan(value);
        vendor().setOnlyQueryPlan(value);
        settings.put(
                "onlyQueryPlan",
                new Setting() {
                    public void on(final CallableStatement target) {
                        ((CUBRIDCallableStatement) target).setOnlyQueryPlan(value);
                    }
                });
    }

    @Override
    public boolean isClosed() throws SQLException {
        return super.isClosed();
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return call.isPoolable();
    }

    @Override
    public void setPoolable(final boolean poolable) throws SQLException {
        call.setPoolable(poolable);
    }

    @Override
    public void setCurrentTransaction(final boolean is_from_current_transaction) {
        vendor().setCurrentTransaction(is_from_current_transaction);
    }

    @Override
    public boolean isFromCurrentTransaction() {
        return vendor().isFromCurrentTransaction();
    }
}
