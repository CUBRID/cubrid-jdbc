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
import java.sql.SQLException;

/**
 * Physical-connection stand-in for the LB logical-connection tests.
 *
 * <p>The LB layer casts the physical legs it drives to {@link CUBRIDConnection} to reach the CUBRID
 * extension methods, so a stand-in has to be one. {@link java.lang.reflect.Proxy} implements
 * interfaces and not classes, which is why these tests subclass instead of proxying — the same
 * reason {@code FakePhysicalStatement} does in the statement tests.
 *
 * <p>Subclassing {@code CUBRIDConnection} carries the override obligation its logical constructor
 * documents: the inherited bodies dereference a {@code u_con} this object does not have. Every
 * public method is therefore overridden here and routed to {@link #dispatch}, which is the single
 * hook a test overrides — the same shape the {@code InvocationHandler} these tests used to pass to
 * {@code Proxy} had. A test that wants one call to behave differently may instead override that
 * method directly, which a proxy could not offer.
 *
 * <p>{@code unwrap} and {@code isWrapperFor} are deliberately left inherited: their bodies read
 * only {@code is_closed}, so they work on this object and answer more faithfully than a stub would.
 * {@code toString} is overridden because its inherited body reports the CAS address off {@code
 * u_con}, which would fail whenever a test or a log statement prints a leg.
 */
public class FakePhysicalConnection extends CUBRIDConnection {

    public FakePhysicalConnection() {
        super("jdbc:cubrid:fake:33000:fake:::", "fake");
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
     * The bridge for the inherited signatures that cannot throw {@link SQLException} — an override
     * may not widen the clause, so a checked failure from {@link #dispatch} is wrapped here.
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

    @Override
    public String toString() {
        return getClass().getName() + "(fake)";
    }

    @Override
    public cubrid.jdbc.driver.CUBRIDConnectionKey Login(final byte[] a0)
            throws java.sql.SQLException {
        return (cubrid.jdbc.driver.CUBRIDConnectionKey) dispatch("Login", new Object[] {a0});
    }

    @Override
    public cubrid.jdbc.driver.CUBRIDConnectionKey Login(final java.lang.String a0)
            throws java.sql.SQLException {
        return (cubrid.jdbc.driver.CUBRIDConnectionKey) dispatch("Login", new Object[] {a0});
    }

    @Override
    public void Logout() {
        dispatchQuiet("Logout", NO_ARGS);
    }

    @Override
    public void SetSignedConnection() {
        dispatchQuiet("SetSignedConnection", NO_ARGS);
    }

    @Override
    public void abort(final java.util.concurrent.Executor a0) throws java.sql.SQLException {
        dispatch("abort", new Object[] {a0});
    }

    @Override
    public void addOutResultSet(final cubrid.jdbc.driver.CUBRIDOutResultSet a0) {
        dispatchQuiet("addOutResultSet", new Object[] {a0});
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
    public void commit() throws java.sql.SQLException {
        dispatch("commit", NO_ARGS);
    }

    @Override
    public java.sql.Array createArrayOf(final java.lang.String a0, final java.lang.Object[] a1)
            throws java.sql.SQLException {
        return (java.sql.Array) dispatch("createArrayOf", new Object[] {a0, a1});
    }

    @Override
    public java.sql.Blob createBlob() throws java.sql.SQLException {
        return (java.sql.Blob) dispatch("createBlob", NO_ARGS);
    }

    @Override
    public java.sql.Clob createClob() throws java.sql.SQLException {
        return (java.sql.Clob) dispatch("createClob", NO_ARGS);
    }

    @Override
    public java.sql.NClob createNClob() throws java.sql.SQLException {
        return (java.sql.NClob) dispatch("createNClob", NO_ARGS);
    }

    @Override
    public java.sql.SQLXML createSQLXML() throws java.sql.SQLException {
        return (java.sql.SQLXML) dispatch("createSQLXML", NO_ARGS);
    }

    @Override
    public java.sql.Statement createStatement() throws java.sql.SQLException {
        return (java.sql.Statement) dispatch("createStatement", NO_ARGS);
    }

    @Override
    public java.sql.Statement createStatement(final int a0, final int a1)
            throws java.sql.SQLException {
        return (java.sql.Statement) dispatch("createStatement", new Object[] {a0, a1});
    }

    @Override
    public java.sql.Statement createStatement(final int a0, final int a1, final int a2)
            throws java.sql.SQLException {
        return (java.sql.Statement) dispatch("createStatement", new Object[] {a0, a1, a2});
    }

    @Override
    public java.sql.Struct createStruct(final java.lang.String a0, final java.lang.Object[] a1)
            throws java.sql.SQLException {
        return (java.sql.Struct) dispatch("createStruct", new Object[] {a0, a1});
    }

    @Override
    public boolean getAutoCommit() throws java.sql.SQLException {
        return asBoolean(dispatch("getAutoCommit", NO_ARGS));
    }

    @Override
    public java.lang.String getCatalog() throws java.sql.SQLException {
        return (java.lang.String) dispatch("getCatalog", NO_ARGS);
    }

    @Override
    public java.util.Properties getClientInfo() throws java.sql.SQLException {
        return (java.util.Properties) dispatch("getClientInfo", NO_ARGS);
    }

    @Override
    public java.lang.String getClientInfo(final java.lang.String a0) throws java.sql.SQLException {
        return (java.lang.String) dispatch("getClientInfo", new Object[] {a0});
    }

    @Override
    public int getHoldability() throws java.sql.SQLException {
        return asInt(dispatch("getHoldability", NO_ARGS));
    }

    @Override
    public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException {
        return (java.sql.DatabaseMetaData) dispatch("getMetaData", NO_ARGS);
    }

    @Override
    public int getNetworkTimeout() throws java.sql.SQLException {
        return asInt(dispatch("getNetworkTimeout", NO_ARGS));
    }

    @Override
    public java.lang.String getSchema() throws java.sql.SQLException {
        return (java.lang.String) dispatch("getSchema", NO_ARGS);
    }

    @Override
    public int getShardId() {
        return asInt(dispatchQuiet("getShardId", NO_ARGS));
    }

    @Override
    public cubrid.jdbc.driver.CUBRIDShardMetaData getShardMetaData() throws java.sql.SQLException {
        return (cubrid.jdbc.driver.CUBRIDShardMetaData) dispatch("getShardMetaData", NO_ARGS);
    }

    @Override
    public int getTransactionIsolation() throws java.sql.SQLException {
        return asInt(dispatch("getTransactionIsolation", NO_ARGS));
    }

    @Override
    @SuppressWarnings("unchecked")
    public java.util.Map<java.lang.String, java.lang.Class<?>> getTypeMap()
            throws java.sql.SQLException {
        return (java.util.Map<java.lang.String, java.lang.Class<?>>)
                dispatch("getTypeMap", NO_ARGS);
    }

    @Override
    public cubrid.jdbc.jci.UConnection getUConnection() throws java.sql.SQLException {
        return (cubrid.jdbc.jci.UConnection) dispatch("getUConnection", NO_ARGS);
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        return (java.sql.SQLWarning) dispatch("getWarnings", NO_ARGS);
    }

    @Override
    public boolean isClosed() throws java.sql.SQLException {
        return asBoolean(dispatch("isClosed", NO_ARGS));
    }

    @Override
    public boolean isReadOnly() throws java.sql.SQLException {
        return asBoolean(dispatch("isReadOnly", NO_ARGS));
    }

    @Override
    public boolean isShard() {
        return asBoolean(dispatchQuiet("isShard", NO_ARGS));
    }

    @Override
    public boolean isValid(final int a0) throws java.sql.SQLException {
        return asBoolean(dispatch("isValid", new Object[] {a0}));
    }

    @Override
    public byte[] lobNew(final int a0) throws java.sql.SQLException {
        return (byte[]) dispatch("lobNew", new Object[] {a0});
    }

    @Override
    public int lobRead(final byte[] a0, final long a1, final byte[] a2, final int a3, final int a4)
            throws java.sql.SQLException {
        return asInt(dispatch("lobRead", new Object[] {a0, a1, a2, a3, a4}));
    }

    @Override
    public int lobWrite(final byte[] a0, final long a1, final byte[] a2, final int a3, final int a4)
            throws java.sql.SQLException {
        return asInt(dispatch("lobWrite", new Object[] {a0, a1, a2, a3, a4}));
    }

    @Override
    public java.lang.String nativeSQL(final java.lang.String a0) throws java.sql.SQLException {
        return (java.lang.String) dispatch("nativeSQL", new Object[] {a0});
    }

    @Override
    public java.sql.CallableStatement prepareCall(final java.lang.String a0)
            throws java.sql.SQLException {
        return (java.sql.CallableStatement) dispatch("prepareCall", new Object[] {a0});
    }

    @Override
    public java.sql.CallableStatement prepareCall(
            final java.lang.String a0, final int a1, final int a2) throws java.sql.SQLException {
        return (java.sql.CallableStatement) dispatch("prepareCall", new Object[] {a0, a1, a2});
    }

    @Override
    public java.sql.CallableStatement prepareCall(
            final java.lang.String a0, final int a1, final int a2, final int a3)
            throws java.sql.SQLException {
        return (java.sql.CallableStatement) dispatch("prepareCall", new Object[] {a0, a1, a2, a3});
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(final java.lang.String a0)
            throws java.sql.SQLException {
        return (java.sql.PreparedStatement) dispatch("prepareStatement", new Object[] {a0});
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(final java.lang.String a0, final int[] a1)
            throws java.sql.SQLException {
        return (java.sql.PreparedStatement) dispatch("prepareStatement", new Object[] {a0, a1});
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(
            final java.lang.String a0, final java.lang.String[] a1) throws java.sql.SQLException {
        return (java.sql.PreparedStatement) dispatch("prepareStatement", new Object[] {a0, a1});
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(final java.lang.String a0, final int a1)
            throws java.sql.SQLException {
        return (java.sql.PreparedStatement) dispatch("prepareStatement", new Object[] {a0, a1});
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(
            final java.lang.String a0, final int a1, final int a2) throws java.sql.SQLException {
        return (java.sql.PreparedStatement) dispatch("prepareStatement", new Object[] {a0, a1, a2});
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(
            final java.lang.String a0, final int a1, final int a2, final int a3)
            throws java.sql.SQLException {
        return (java.sql.PreparedStatement)
                dispatch("prepareStatement", new Object[] {a0, a1, a2, a3});
    }

    @Override
    public void releaseSavepoint(final java.sql.Savepoint a0) throws java.sql.SQLException {
        dispatch("releaseSavepoint", new Object[] {a0});
    }

    @Override
    public void rollback() throws java.sql.SQLException {
        dispatch("rollback", NO_ARGS);
    }

    @Override
    public void rollback(final java.sql.Savepoint a0) throws java.sql.SQLException {
        dispatch("rollback", new Object[] {a0});
    }

    @Override
    public void setAutoCommit(final boolean a0) throws java.sql.SQLException {
        dispatch("setAutoCommit", new Object[] {a0});
    }

    @Override
    public void setAutoGeneratedKeys(final boolean a0) {
        dispatchQuiet("setAutoGeneratedKeys", new Object[] {a0});
    }

    @Override
    public int setCASChangeMode(final int a0) throws java.sql.SQLException {
        return asInt(dispatch("setCASChangeMode", new Object[] {a0}));
    }

    @Override
    public void setCatalog(final java.lang.String a0) throws java.sql.SQLException {
        dispatch("setCatalog", new Object[] {a0});
    }

    @Override
    public void setCharset(final java.lang.String a0) throws java.io.UnsupportedEncodingException {
        dispatchQuiet("setCharset", new Object[] {a0});
    }

    @Override
    public void setClientInfo(final java.util.Properties a0)
            throws java.sql.SQLClientInfoException {
        dispatchQuiet("setClientInfo", new Object[] {a0});
    }

    @Override
    public void setClientInfo(final java.lang.String a0, final java.lang.String a1)
            throws java.sql.SQLClientInfoException {
        dispatchQuiet("setClientInfo", new Object[] {a0, a1});
    }

    @Override
    public void setHoldability(final int a0) throws java.sql.SQLException {
        dispatch("setHoldability", new Object[] {a0});
    }

    @Override
    public void setLockTimeout(final int a0) throws java.sql.SQLException {
        dispatch("setLockTimeout", new Object[] {a0});
    }

    @Override
    public void setNetworkTimeout(final java.util.concurrent.Executor a0, final int a1)
            throws java.sql.SQLException {
        dispatch("setNetworkTimeout", new Object[] {a0, a1});
    }

    @Override
    public void setReadOnly(final boolean a0) throws java.sql.SQLException {
        dispatch("setReadOnly", new Object[] {a0});
    }

    @Override
    public java.sql.Savepoint setSavepoint() throws java.sql.SQLException {
        return (java.sql.Savepoint) dispatch("setSavepoint", NO_ARGS);
    }

    @Override
    public java.sql.Savepoint setSavepoint(final java.lang.String a0) throws java.sql.SQLException {
        return (java.sql.Savepoint) dispatch("setSavepoint", new Object[] {a0});
    }

    @Override
    public void setSchema(final java.lang.String a0) throws java.sql.SQLException {
        dispatch("setSchema", new Object[] {a0});
    }

    @Override
    public void setTransactionIsolation(final int a0) throws java.sql.SQLException {
        dispatch("setTransactionIsolation", new Object[] {a0});
    }

    @Override
    public void setTypeMap(final java.util.Map<java.lang.String, java.lang.Class<?>> a0)
            throws java.sql.SQLException {
        dispatch("setTypeMap", new Object[] {a0});
    }
}
