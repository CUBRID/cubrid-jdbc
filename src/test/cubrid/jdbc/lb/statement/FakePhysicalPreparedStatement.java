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

import cubrid.jdbc.driver.CUBRIDConnection;
import cubrid.jdbc.driver.CUBRIDPreparedStatement;
import java.sql.SQLException;

/**
 * Physical prepared-statement stand-in for the LB statement tests. The {@link
 * FakePhysicalStatement} rationale applies verbatim; this one extends {@link
 * CUBRIDPreparedStatement} so the CUBRID-only parameter setters ({@code setOID}, {@code
 * setCollection}, {@code setTimestamptz}) reach it too.
 */
public class FakePhysicalPreparedStatement extends CUBRIDPreparedStatement {

    public FakePhysicalPreparedStatement() {
        super((CUBRIDConnection) null);
    }

    private static final Object[] NO_ARGS = new Object[0];

    /**
     * Every override funnels here. Subclasses answer the calls they care about and return {@code
     * null} for the rest, which the primitive coercions below read as the type's zero value.
     */
    protected Object dispatch(final String name, final Object[] args) throws SQLException {
        return null;
    }

    /**
     * The bridge for the inherited signatures that declare no {@code throws} clause — an override
     * may not widen it, so a checked failure from {@link #dispatch} is wrapped here.
     */
    private Object dispatchQuiet(final String name, final Object[] args) {
        try {
            return dispatch(name, args);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private static boolean asBoolean(final Object value) {
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : false;
    }

    private static int asInt(final Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static byte asByte(final Object value) {
        return value instanceof Number ? ((Number) value).byteValue() : (byte) 0;
    }

    @Override
    public void addBatch() throws java.sql.SQLException {
        dispatch("addBatch", NO_ARGS);
    }

    @Override
    public void addBatch(final java.lang.String a0) throws java.sql.SQLException {
        dispatch("addBatch", new Object[] {a0});
    }

    @Override
    public void cancel() throws java.sql.SQLException {
        dispatch("cancel", NO_ARGS);
    }

    @Override
    public void clearBatch() throws java.sql.SQLException {
        dispatch("clearBatch", NO_ARGS);
    }

    @Override
    public void clearParameters() throws java.sql.SQLException {
        dispatch("clearParameters", NO_ARGS);
    }

    @Override
    public void clearWarnings() throws java.sql.SQLException {
        dispatch("clearWarnings", NO_ARGS);
    }

    @Override
    public void close() throws java.sql.SQLException {
        dispatch("close", NO_ARGS);
    }

    @Override
    public void closeOnCompletion() throws java.sql.SQLException {
        dispatch("closeOnCompletion", NO_ARGS);
    }

    @Override
    public boolean execute() throws java.sql.SQLException {
        return asBoolean(dispatch("execute", NO_ARGS));
    }

    @Override
    public int[] executeBatch() throws java.sql.SQLException {
        return (int[]) dispatch("executeBatch", NO_ARGS);
    }

    @Override
    public cubrid.sql.CUBRIDOID executeInsert() throws java.sql.SQLException {
        return (cubrid.sql.CUBRIDOID) dispatch("executeInsert", NO_ARGS);
    }

    @Override
    public cubrid.sql.CUBRIDOID executeInsert(final java.lang.String a0)
            throws java.sql.SQLException {
        return (cubrid.sql.CUBRIDOID) dispatch("executeInsert", new Object[] {a0});
    }

    @Override
    public java.sql.ResultSet executeQuery() throws java.sql.SQLException {
        return (java.sql.ResultSet) dispatch("executeQuery", NO_ARGS);
    }

    @Override
    public java.sql.ResultSet executeQuery(final java.lang.String a0) throws java.sql.SQLException {
        return (java.sql.ResultSet) dispatch("executeQuery", new Object[] {a0});
    }

    @Override
    public int executeUpdate() throws java.sql.SQLException {
        return asInt(dispatch("executeUpdate", NO_ARGS));
    }

    @Override
    public int executeUpdate(final java.lang.String a0) throws java.sql.SQLException {
        return asInt(dispatch("executeUpdate", new Object[] {a0}));
    }

    @Override
    public int executeUpdate(final java.lang.String a0, final int[] a1)
            throws java.sql.SQLException {
        return asInt(dispatch("executeUpdate", new Object[] {a0, a1}));
    }

    @Override
    public int executeUpdate(final java.lang.String a0, final java.lang.String[] a1)
            throws java.sql.SQLException {
        return asInt(dispatch("executeUpdate", new Object[] {a0, a1}));
    }

    @Override
    public int executeUpdate(final java.lang.String a0, final int a1) throws java.sql.SQLException {
        return asInt(dispatch("executeUpdate", new Object[] {a0, a1}));
    }

    @Override
    public boolean execute(final java.lang.String a0) throws java.sql.SQLException {
        return asBoolean(dispatch("execute", new Object[] {a0}));
    }

    @Override
    public boolean execute(final java.lang.String a0, final int[] a1) throws java.sql.SQLException {
        return asBoolean(dispatch("execute", new Object[] {a0, a1}));
    }

    @Override
    public boolean execute(final java.lang.String a0, final java.lang.String[] a1)
            throws java.sql.SQLException {
        return asBoolean(dispatch("execute", new Object[] {a0, a1}));
    }

    @Override
    public boolean execute(final java.lang.String a0, final int a1) throws java.sql.SQLException {
        return asBoolean(dispatch("execute", new Object[] {a0, a1}));
    }

    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
        return (java.sql.Connection) dispatch("getConnection", NO_ARGS);
    }

    @Override
    public int getFetchDirection() throws java.sql.SQLException {
        return asInt(dispatch("getFetchDirection", NO_ARGS));
    }

    @Override
    public int getFetchSize() throws java.sql.SQLException {
        return asInt(dispatch("getFetchSize", NO_ARGS));
    }

    @Override
    public java.sql.ResultSet getGeneratedKeys() throws java.sql.SQLException {
        return (java.sql.ResultSet) dispatch("getGeneratedKeys", NO_ARGS);
    }

    @Override
    public int getMaxFieldSize() throws java.sql.SQLException {
        return asInt(dispatch("getMaxFieldSize", NO_ARGS));
    }

    @Override
    public int getMaxRows() throws java.sql.SQLException {
        return asInt(dispatch("getMaxRows", NO_ARGS));
    }

    @Override
    public java.sql.ResultSetMetaData getMetaData() throws java.sql.SQLException {
        return (java.sql.ResultSetMetaData) dispatch("getMetaData", NO_ARGS);
    }

    @Override
    public boolean getMoreResults() throws java.sql.SQLException {
        return asBoolean(dispatch("getMoreResults", NO_ARGS));
    }

    @Override
    public boolean getMoreResults(final int a0) throws java.sql.SQLException {
        return asBoolean(dispatch("getMoreResults", new Object[] {a0}));
    }

    @Override
    public java.sql.ParameterMetaData getParameterMetaData() throws java.sql.SQLException {
        return (java.sql.ParameterMetaData) dispatch("getParameterMetaData", NO_ARGS);
    }

    @Override
    public int getQueryTimeout() throws java.sql.SQLException {
        return asInt(dispatch("getQueryTimeout", NO_ARGS));
    }

    @Override
    public java.lang.String getQueryplan() throws java.sql.SQLException {
        return (java.lang.String) dispatch("getQueryplan", NO_ARGS);
    }

    @Override
    public java.lang.String getQueryplan(final java.lang.String a0) throws java.sql.SQLException {
        return (java.lang.String) dispatch("getQueryplan", new Object[] {a0});
    }

    @Override
    public java.sql.ResultSet getResultSet() throws java.sql.SQLException {
        return (java.sql.ResultSet) dispatch("getResultSet", NO_ARGS);
    }

    @Override
    public int getResultSetConcurrency() throws java.sql.SQLException {
        return asInt(dispatch("getResultSetConcurrency", NO_ARGS));
    }

    @Override
    public int getResultSetHoldability() throws java.sql.SQLException {
        return asInt(dispatch("getResultSetHoldability", NO_ARGS));
    }

    @Override
    public int getResultSetType() throws java.sql.SQLException {
        return asInt(dispatch("getResultSetType", NO_ARGS));
    }

    @Override
    public int getShardId() {
        return asInt(dispatchQuiet("getShardId", NO_ARGS));
    }

    @Override
    public byte getStatementType() {
        return asByte(dispatchQuiet("getStatementType", NO_ARGS));
    }

    @Override
    public int getUpdateCount() throws java.sql.SQLException {
        return asInt(dispatch("getUpdateCount", NO_ARGS));
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        return (java.sql.SQLWarning) dispatch("getWarnings", NO_ARGS);
    }

    @Override
    public boolean hasResultSet() {
        return asBoolean(dispatchQuiet("hasResultSet", NO_ARGS));
    }

    @Override
    public boolean isCloseOnCompletion() throws java.sql.SQLException {
        return asBoolean(dispatch("isCloseOnCompletion", NO_ARGS));
    }

    @Override
    public boolean isClosed() throws java.sql.SQLException {
        return asBoolean(dispatch("isClosed", NO_ARGS));
    }

    @Override
    public boolean isFromCurrentTransaction() {
        return asBoolean(dispatchQuiet("isFromCurrentTransaction", NO_ARGS));
    }

    @Override
    public boolean isPoolable() throws java.sql.SQLException {
        return asBoolean(dispatch("isPoolable", NO_ARGS));
    }

    @Override
    public void setArray(final int a0, final java.sql.Array a1) throws java.sql.SQLException {
        dispatch("setArray", new Object[] {a0, a1});
    }

    @Override
    public void setAsciiStream(final int a0, final java.io.InputStream a1)
            throws java.sql.SQLException {
        dispatch("setAsciiStream", new Object[] {a0, a1});
    }

    @Override
    public void setAsciiStream(final int a0, final java.io.InputStream a1, final int a2)
            throws java.sql.SQLException {
        dispatch("setAsciiStream", new Object[] {a0, a1, a2});
    }

    @Override
    public void setAsciiStream(final int a0, final java.io.InputStream a1, final long a2)
            throws java.sql.SQLException {
        dispatch("setAsciiStream", new Object[] {a0, a1, a2});
    }

    @Override
    public void setBigDecimal(final int a0, final java.math.BigDecimal a1)
            throws java.sql.SQLException {
        dispatch("setBigDecimal", new Object[] {a0, a1});
    }

    @Override
    public void setBinaryStream(final int a0, final java.io.InputStream a1)
            throws java.sql.SQLException {
        dispatch("setBinaryStream", new Object[] {a0, a1});
    }

    @Override
    public void setBinaryStream(final int a0, final java.io.InputStream a1, final int a2)
            throws java.sql.SQLException {
        dispatch("setBinaryStream", new Object[] {a0, a1, a2});
    }

    @Override
    public void setBinaryStream(final int a0, final java.io.InputStream a1, final long a2)
            throws java.sql.SQLException {
        dispatch("setBinaryStream", new Object[] {a0, a1, a2});
    }

    @Override
    public void setBlob(final int a0, final java.io.InputStream a1) throws java.sql.SQLException {
        dispatch("setBlob", new Object[] {a0, a1});
    }

    @Override
    public void setBlob(final int a0, final java.io.InputStream a1, final long a2)
            throws java.sql.SQLException {
        dispatch("setBlob", new Object[] {a0, a1, a2});
    }

    @Override
    public void setBlob(final int a0, final java.sql.Blob a1) throws java.sql.SQLException {
        dispatch("setBlob", new Object[] {a0, a1});
    }

    @Override
    public void setBoolean(final int a0, final boolean a1) throws java.sql.SQLException {
        dispatch("setBoolean", new Object[] {a0, a1});
    }

    @Override
    public void setBytes(final int a0, final byte[] a1) throws java.sql.SQLException {
        dispatch("setBytes", new Object[] {a0, a1});
    }

    @Override
    public void setByte(final int a0, final byte a1) throws java.sql.SQLException {
        dispatch("setByte", new Object[] {a0, a1});
    }

    @Override
    public void setCharacterStream(final int a0, final java.io.Reader a1)
            throws java.sql.SQLException {
        dispatch("setCharacterStream", new Object[] {a0, a1});
    }

    @Override
    public void setCharacterStream(final int a0, final java.io.Reader a1, final int a2)
            throws java.sql.SQLException {
        dispatch("setCharacterStream", new Object[] {a0, a1, a2});
    }

    @Override
    public void setCharacterStream(final int a0, final java.io.Reader a1, final long a2)
            throws java.sql.SQLException {
        dispatch("setCharacterStream", new Object[] {a0, a1, a2});
    }

    @Override
    public void setClob(final int a0, final java.io.Reader a1) throws java.sql.SQLException {
        dispatch("setClob", new Object[] {a0, a1});
    }

    @Override
    public void setClob(final int a0, final java.io.Reader a1, final long a2)
            throws java.sql.SQLException {
        dispatch("setClob", new Object[] {a0, a1, a2});
    }

    @Override
    public void setClob(final int a0, final java.sql.Clob a1) throws java.sql.SQLException {
        dispatch("setClob", new Object[] {a0, a1});
    }

    @Override
    public void setCollection(final int a0, final java.lang.Object[] a1)
            throws java.sql.SQLException {
        dispatch("setCollection", new Object[] {a0, a1});
    }

    @Override
    public void setCurrentTransaction(final boolean a0) {
        dispatchQuiet("setCurrentTransaction", new Object[] {a0});
    }

    @Override
    public void setCursorName(final java.lang.String a0) throws java.sql.SQLException {
        dispatch("setCursorName", new Object[] {a0});
    }

    @Override
    public void setDate(final int a0, final java.sql.Date a1) throws java.sql.SQLException {
        dispatch("setDate", new Object[] {a0, a1});
    }

    @Override
    public void setDate(final int a0, final java.sql.Date a1, final java.util.Calendar a2)
            throws java.sql.SQLException {
        dispatch("setDate", new Object[] {a0, a1, a2});
    }

    @Override
    public void setDouble(final int a0, final double a1) throws java.sql.SQLException {
        dispatch("setDouble", new Object[] {a0, a1});
    }

    @Override
    public void setEscapeProcessing(final boolean a0) throws java.sql.SQLException {
        dispatch("setEscapeProcessing", new Object[] {a0});
    }

    @Override
    public void setFetchDirection(final int a0) throws java.sql.SQLException {
        dispatch("setFetchDirection", new Object[] {a0});
    }

    @Override
    public void setFetchSize(final int a0) throws java.sql.SQLException {
        dispatch("setFetchSize", new Object[] {a0});
    }

    @Override
    public void setFloat(final int a0, final float a1) throws java.sql.SQLException {
        dispatch("setFloat", new Object[] {a0, a1});
    }

    @Override
    public void setInt(final int a0, final int a1) throws java.sql.SQLException {
        dispatch("setInt", new Object[] {a0, a1});
    }

    @Override
    public void setLong(final int a0, final long a1) throws java.sql.SQLException {
        dispatch("setLong", new Object[] {a0, a1});
    }

    @Override
    public void setMaxFieldSize(final int a0) throws java.sql.SQLException {
        dispatch("setMaxFieldSize", new Object[] {a0});
    }

    @Override
    public void setMaxRows(final int a0) throws java.sql.SQLException {
        dispatch("setMaxRows", new Object[] {a0});
    }

    @Override
    public void setNCharacterStream(final int a0, final java.io.Reader a1)
            throws java.sql.SQLException {
        dispatch("setNCharacterStream", new Object[] {a0, a1});
    }

    @Override
    public void setNCharacterStream(final int a0, final java.io.Reader a1, final long a2)
            throws java.sql.SQLException {
        dispatch("setNCharacterStream", new Object[] {a0, a1, a2});
    }

    @Override
    public void setNClob(final int a0, final java.io.Reader a1) throws java.sql.SQLException {
        dispatch("setNClob", new Object[] {a0, a1});
    }

    @Override
    public void setNClob(final int a0, final java.io.Reader a1, final long a2)
            throws java.sql.SQLException {
        dispatch("setNClob", new Object[] {a0, a1, a2});
    }

    @Override
    public void setNClob(final int a0, final java.sql.NClob a1) throws java.sql.SQLException {
        dispatch("setNClob", new Object[] {a0, a1});
    }

    @Override
    public void setNString(final int a0, final java.lang.String a1) throws java.sql.SQLException {
        dispatch("setNString", new Object[] {a0, a1});
    }

    @Override
    public void setNull(final int a0, final int a1) throws java.sql.SQLException {
        dispatch("setNull", new Object[] {a0, a1});
    }

    @Override
    public void setNull(final int a0, final int a1, final java.lang.String a2)
            throws java.sql.SQLException {
        dispatch("setNull", new Object[] {a0, a1, a2});
    }

    @Override
    public void setOID(final int a0, final cubrid.sql.CUBRIDOID a1) throws java.sql.SQLException {
        dispatch("setOID", new Object[] {a0, a1});
    }

    @Override
    public void setObject(final int a0, final java.lang.Object a1) throws java.sql.SQLException {
        dispatch("setObject", new Object[] {a0, a1});
    }

    @Override
    public void setObject(final int a0, final java.lang.Object a1, final int a2)
            throws java.sql.SQLException {
        dispatch("setObject", new Object[] {a0, a1, a2});
    }

    @Override
    public void setObject(final int a0, final java.lang.Object a1, final int a2, final int a3)
            throws java.sql.SQLException {
        dispatch("setObject", new Object[] {a0, a1, a2, a3});
    }

    @Override
    public void setObject(final int a0, final java.lang.Object a1, final java.sql.SQLType a2)
            throws java.sql.SQLException {
        dispatch("setObject", new Object[] {a0, a1, a2});
    }

    @Override
    public void setObject(
            final int a0, final java.lang.Object a1, final java.sql.SQLType a2, final int a3)
            throws java.sql.SQLException {
        dispatch("setObject", new Object[] {a0, a1, a2, a3});
    }

    @Override
    public void setOnlyQueryPlan(final boolean a0) {
        dispatchQuiet("setOnlyQueryPlan", new Object[] {a0});
    }

    @Override
    public void setPoolable(final boolean a0) throws java.sql.SQLException {
        dispatch("setPoolable", new Object[] {a0});
    }

    @Override
    public void setQueryInfo(final boolean a0) {
        dispatchQuiet("setQueryInfo", new Object[] {a0});
    }

    @Override
    public void setQueryTimeout(final int a0) throws java.sql.SQLException {
        dispatch("setQueryTimeout", new Object[] {a0});
    }

    @Override
    public void setRef(final int a0, final java.sql.Ref a1) throws java.sql.SQLException {
        dispatch("setRef", new Object[] {a0, a1});
    }

    @Override
    public void setRowId(final int a0, final java.sql.RowId a1) throws java.sql.SQLException {
        dispatch("setRowId", new Object[] {a0, a1});
    }

    @Override
    public void setSQLXML(final int a0, final java.sql.SQLXML a1) throws java.sql.SQLException {
        dispatch("setSQLXML", new Object[] {a0, a1});
    }

    @Override
    public void setShort(final int a0, final short a1) throws java.sql.SQLException {
        dispatch("setShort", new Object[] {a0, a1});
    }

    @Override
    public void setString(final int a0, final java.lang.String a1) throws java.sql.SQLException {
        dispatch("setString", new Object[] {a0, a1});
    }

    @Override
    public void setTimestamptz(final int a0, final cubrid.sql.CUBRIDTimestamptz a1)
            throws java.sql.SQLException {
        dispatch("setTimestamptz", new Object[] {a0, a1});
    }

    @Override
    public void setTimestamptz(
            final int a0, final cubrid.sql.CUBRIDTimestamptz a1, final java.util.Calendar a2)
            throws java.sql.SQLException {
        dispatch("setTimestamptz", new Object[] {a0, a1, a2});
    }

    @Override
    public void setTimestamp(final int a0, final java.sql.Timestamp a1)
            throws java.sql.SQLException {
        dispatch("setTimestamp", new Object[] {a0, a1});
    }

    @Override
    public void setTimestamp(final int a0, final java.sql.Timestamp a1, final java.util.Calendar a2)
            throws java.sql.SQLException {
        dispatch("setTimestamp", new Object[] {a0, a1, a2});
    }

    @Override
    public void setTime(final int a0, final java.sql.Time a1) throws java.sql.SQLException {
        dispatch("setTime", new Object[] {a0, a1});
    }

    @Override
    public void setTime(final int a0, final java.sql.Time a1, final java.util.Calendar a2)
            throws java.sql.SQLException {
        dispatch("setTime", new Object[] {a0, a1, a2});
    }

    @Override
    public void setURL(final int a0, final java.net.URL a1) throws java.sql.SQLException {
        dispatch("setURL", new Object[] {a0, a1});
    }

    @Override
    public void setUnicodeStream(final int a0, final java.io.InputStream a1, final int a2)
            throws java.sql.SQLException {
        dispatch("setUnicodeStream", new Object[] {a0, a1, a2});
    }
}
