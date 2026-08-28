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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.TestConfigHelper;
import cubrid.jdbc.lb.route.Router;
import cubrid.sql.CUBRIDOID;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class LBPreparedStatementExecutionTest {

    private LoadBalanceConnection connection;

    @Before
    public void setUp() throws Exception {
        connection = new LoadBalanceConnection(TestConfigHelper.emptyConfig());
    }

    @Test
    public void assertExecuteQueryDelegatesAndReplaysRecordedParameters() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement)
                        connection.prepareStatement("SELECT * FROM t WHERE id = ? AND name = ?");
        statement.setInt(1, 7);
        statement.setString(2, "neo");
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        ResultSet result = statement.executeQuery();

        assertNotNull(result);
        assertEquals(Router.RouteTarget.TO_READ_ONLY, context.lastTarget);
        assertTrue(context.calls.contains("setInt:1:7"));
        assertTrue(context.calls.contains("setString:2:neo"));
        assertTrue(context.calls.contains("executeQuery"));
    }

    @Test
    public void assertExecuteUpdateUsesMasterWhenTransactionActive() throws Exception {
        connection.setAutoCommit(false);
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t");
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        int updateCount = statement.executeUpdate();

        assertEquals(1, updateCount);
        assertEquals(Router.RouteTarget.TO_READ_WRITE, context.lastTarget);
        assertTrue(context.calls.contains("executeUpdate"));
    }

    @Test
    public void assertReplayFailureIsPropagated() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(1, 11);
        DelegationContext context = new DelegationContext();
        context.failOnSetInt = true;
        statement.setPsProvider(context);
        try {
            statement.execute();
            fail("Expected SQLException from replay failure");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().contains("boom-setInt"));
        }
    }

    @Test
    public void assertClearParametersMakesReplayEmpty() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(1, 99);
        statement.clearParameters();
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        boolean executed = statement.execute();

        assertFalse(executed);
        assertFalse(context.containsPrefix("setInt:"));
        assertTrue(context.calls.contains("execute"));
    }

    @Test
    public void assertPreparedAddBatchStoresParameterSnapshots() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement)
                        connection.prepareStatement("SELECT * FROM t WHERE id = ? AND name = ?");
        statement.setInt(1, 1);
        statement.setString(2, "a");
        statement.addBatch();
        statement.setInt(1, 2);
        statement.setString(2, "b");
        statement.addBatch();
        statement.clearParameters();
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.executeBatch();

        assertTrue(context.calls.contains("clearParameters"));
        assertTrue(context.calls.contains("addBatch"));
        assertEquals(2, context.countCall("addBatch"));
        assertEquals(1, context.countCall("executeBatch"));
        assertTrue(context.calls.contains("setInt:1:1"));
        assertTrue(context.calls.contains("setString:2:a"));
        assertTrue(context.calls.contains("setInt:1:2"));
        assertTrue(context.calls.contains("setString:2:b"));
    }

    @Test
    public void assertPreparedExecuteBatchDelegatesToBoundPhysicalStatement() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(1, 7);
        statement.addBatch();
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        int[] result = statement.executeBatch();

        assertEquals(1, result.length);
        assertEquals(Router.RouteTarget.TO_READ_ONLY, context.lastTarget);
        assertTrue(context.calls.contains("executeBatch"));
    }

    @Test
    public void assertClearParametersResetsBinderState() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(1, 3);
        statement.clearParameters();
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.execute();

        assertFalse(context.containsPrefix("setInt:"));
        assertTrue(context.calls.contains("execute"));
    }

    @Test
    public void assertCommonParameterSettersAreReplayedToPhysicalStatement() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement)
                        connection.prepareStatement(
                                "SELECT * FROM t WHERE id = ? AND score = ? AND active = ? AND dt = ?");
        statement.setLong(1, 12L);
        statement.setDouble(2, 3.5d);
        statement.setBoolean(3, true);
        statement.setDate(4, new java.sql.Date(0L));
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.execute();

        assertTrue(context.calls.contains("setLong:1:12"));
        assertTrue(context.calls.contains("setDouble:2:3.5"));
        assertTrue(context.calls.contains("setBoolean:3:true"));
        assertTrue(context.containsPrefix("setDate:4:"));
    }

    @Test
    public void assertP0SettersAreReplayedToPhysicalStatement() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement)
                        connection.prepareStatement(
                                "SELECT * FROM t WHERE ts = ? AND tm = ? AND dt = ? AND n = ? AND b = ?");
        Calendar calendar = Calendar.getInstance();
        statement.setTimestamp(1, new java.sql.Timestamp(1L));
        statement.setTime(2, new java.sql.Time(2L));
        statement.setDate(3, new java.sql.Date(3L), calendar);
        statement.setBigDecimal(4, new java.math.BigDecimal("12.5"));
        statement.setBytes(5, new byte[] {1, 2, 3});
        statement.setCharacterStream(1, new java.io.StringReader("v"));
        statement.setBinaryStream(2, new java.io.ByteArrayInputStream(new byte[] {7}));
        statement.setAsciiStream(3, new java.io.ByteArrayInputStream(new byte[] {8}));
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.execute();

        assertTrue(context.containsPrefix("setCharacterStream:1:"));
        assertTrue(context.containsPrefix("setBinaryStream:2:"));
        assertTrue(context.containsPrefix("setAsciiStream:3:"));
        assertTrue(context.calls.contains("setBigDecimal:4:12.5"));
        assertTrue(context.calls.contains("setBytes:5:[1, 2, 3]"));
    }

    @Test
    public void assertP0SettersAreReplayedInBatchSnapshots() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setTimestamp(1, new java.sql.Timestamp(10L));
        statement.addBatch();
        statement.clearParameters();
        statement.setTimestamp(1, new java.sql.Timestamp(20L));
        statement.addBatch();
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.executeBatch();

        assertEquals(2, context.countCall("addBatch"));
        assertTrue(context.containsPrefix("setTimestamp:1:"));
    }

    @Test
    public void assertClearParametersDropsP0SetterReplay() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setTimestamp(1, new java.sql.Timestamp(9L));
        statement.setBigDecimal(1, new java.math.BigDecimal("9.9"));
        statement.clearParameters();
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.execute();

        assertFalse(context.containsPrefix("setTimestamp:"));
        assertFalse(context.containsPrefix("setBigDecimal:"));
    }

    @Test
    public void assertP1StreamAndLobSettersAreReplayed() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setNull(1, java.sql.Types.VARCHAR, "VARCHAR");
        statement.setByte(1, (byte) 1);
        statement.setShort(1, (short) 2);
        statement.setFloat(1, 1.5f);
        statement.setURL(1, new java.net.URL("http://localhost"));
        statement.setBlob(
                1,
                (java.sql.Blob)
                        Proxy.newProxyInstance(
                                java.sql.Blob.class.getClassLoader(),
                                new Class[] {java.sql.Blob.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        return null;
                                    }
                                }));
        statement.setBlob(1, new java.io.ByteArrayInputStream(new byte[] {1}), 1L);
        statement.setBlob(1, new java.io.ByteArrayInputStream(new byte[] {2}));
        statement.setClob(
                1,
                (java.sql.Clob)
                        Proxy.newProxyInstance(
                                java.sql.Clob.class.getClassLoader(),
                                new Class[] {java.sql.Clob.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        return null;
                                    }
                                }));
        statement.setClob(1, new java.io.StringReader("a"), 1L);
        statement.setClob(1, new java.io.StringReader("b"));
        statement.setAsciiStream(1, new java.io.ByteArrayInputStream(new byte[] {1}), 1);
        statement.setAsciiStream(1, new java.io.ByteArrayInputStream(new byte[] {2}), 2L);
        statement.setBinaryStream(1, new java.io.ByteArrayInputStream(new byte[] {3}), 3);
        statement.setBinaryStream(1, new java.io.ByteArrayInputStream(new byte[] {4}), 4L);
        statement.setCharacterStream(1, new java.io.StringReader("c"), 1);
        statement.setCharacterStream(1, new java.io.StringReader("d"), 2L);
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.execute();

        assertTrue(context.containsPrefix("setCharacterStreamLenLong:1:"));
    }

    @Test
    public void assertP1OverloadedSettersKeepMethodSignatureOnReplay() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setAsciiStream(1, new java.io.ByteArrayInputStream(new byte[] {1}), 1);
        statement.setAsciiStream(1, new java.io.ByteArrayInputStream(new byte[] {2}), 2L);
        statement.setBinaryStream(1, new java.io.ByteArrayInputStream(new byte[] {3}), 3);
        statement.setBinaryStream(1, new java.io.ByteArrayInputStream(new byte[] {4}), 4L);
        statement.setCharacterStream(1, new java.io.StringReader("x"), 1);
        statement.setCharacterStream(1, new java.io.StringReader("y"), 2L);
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.execute();

        assertEquals(0, context.countPrefix("setAsciiStreamLenInt:1:"));
        assertEquals(0, context.countPrefix("setAsciiStreamLenLong:1:"));
        assertEquals(0, context.countPrefix("setBinaryStreamLenInt:1:"));
        assertEquals(0, context.countPrefix("setBinaryStreamLenLong:1:"));
        assertEquals(0, context.countPrefix("setCharacterStreamLenInt:1:"));
        assertEquals(1, context.countPrefix("setCharacterStreamLenLong:1:"));
    }

    @Test
    public void assertP1SettersDoNotBreakExistingP0Paths() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(1, 1);
        statement.setBigDecimal(1, new java.math.BigDecimal("1.0"));
        statement.setAsciiStream(1, new java.io.ByteArrayInputStream(new byte[] {1}), 1);
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.executeBatch();

        assertEquals(0, context.countCall("executeBatch"));
        statement.addBatch();
        statement.executeBatch();
        assertEquals(1, context.countCall("executeBatch"));
        assertTrue(context.containsPrefix("setAsciiStreamLenInt:1:"));
    }

    @Test
    public void assertP2AdvancedTypeSettersAreReplayed() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setRef(
                1,
                (java.sql.Ref)
                        Proxy.newProxyInstance(
                                java.sql.Ref.class.getClassLoader(),
                                new Class[] {java.sql.Ref.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        return null;
                                    }
                                }));
        statement.setArray(
                1,
                (java.sql.Array)
                        Proxy.newProxyInstance(
                                java.sql.Array.class.getClassLoader(),
                                new Class[] {java.sql.Array.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        return null;
                                    }
                                }));
        statement.setRowId(
                1,
                (java.sql.RowId)
                        Proxy.newProxyInstance(
                                java.sql.RowId.class.getClassLoader(),
                                new Class[] {java.sql.RowId.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        return null;
                                    }
                                }));
        statement.setNString(1, "n");
        statement.setNCharacterStream(1, new java.io.StringReader("a"), 1L);
        statement.setNCharacterStream(1, new java.io.StringReader("b"));
        statement.setNClob(
                1,
                (java.sql.NClob)
                        Proxy.newProxyInstance(
                                java.sql.NClob.class.getClassLoader(),
                                new Class[] {java.sql.NClob.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        return null;
                                    }
                                }));
        statement.setNClob(1, new java.io.StringReader("c"), 1L);
        statement.setNClob(1, new java.io.StringReader("d"));
        statement.setSQLXML(
                1,
                (java.sql.SQLXML)
                        Proxy.newProxyInstance(
                                java.sql.SQLXML.class.getClassLoader(),
                                new Class[] {java.sql.SQLXML.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        return null;
                                    }
                                }));
        statement.setUnicodeStream(1, new java.io.ByteArrayInputStream(new byte[] {1}), 1);
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.execute();

        assertTrue(context.containsPrefix("setUnicodeStream:1:"));
    }

    @Test
    public void assertP2SettersRemainCompatibleWithP0P1Paths() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(1, 1);
        statement.setNString(1, "n");
        statement.setAsciiStream(1, new java.io.ByteArrayInputStream(new byte[] {1}), 1);
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.addBatch();
        int[] result = statement.executeBatch();

        assertEquals(1, result.length);
        assertTrue(context.containsPrefix("setAsciiStreamLenInt:1:"));
    }

    @Test
    public void assertDeprecatedUnicodeStreamPathKeepsStableContract() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setUnicodeStream(1, new java.io.ByteArrayInputStream(new byte[] {1, 2}), 2);
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.execute();

        assertEquals(1, context.countPrefix("setUnicodeStream:1:"));
    }

    @Test
    public void assertPreparedStatementSetOidDelegatesToPhysicalPreparedStatement()
            throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        CUBRIDOID oid =
                (CUBRIDOID)
                        Proxy.newProxyInstance(
                                CUBRIDOID.class.getClassLoader(),
                                new Class[] {CUBRIDOID.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        return null;
                                    }
                                });
        statement.setOID(1, oid);
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.execute();

        assertTrue(context.containsPrefix("setOID:1:"));
    }

    @Test
    public void assertExecuteInsertDelegatesToPhysicalStatementPaths() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("INSERT INTO t(id) VALUES (?)");
        statement.setInt(1, 1);
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        CUBRIDOID actual = statement.executeInsert();

        assertNotNull(actual);
        assertTrue(context.calls.contains("executeInsert"));
    }

    @Test
    public void assertPreparedStatementSetCollectionDelegatesOrReplaysCorrectly() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setCollection(1, new Object[] {"a", Integer.valueOf(1)});
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.execute();

        assertTrue(context.containsPrefix("setCollection:1:"));
    }

    @Test
    public void assertPreparedStatementSetTimestamptzDelegatesOrReplaysCorrectly()
            throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setTimestamptz(1, null);
        statement.setTimestamptz(1, null, Calendar.getInstance());
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);

        statement.execute();

        assertEquals(0, context.countPrefix("setTimestamptz:1:"));
        assertTrue(context.containsPrefix("setTimestamptzCal:1:"));
    }

    @Test
    public void assertExecuteOnClosedPreparedStatementThrows() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT 1");
        statement.close();
        try {
            statement.executeQuery();
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().contains("closed"));
        }
    }

    @Test
    public void assertGetMetaDataDelegatesToPhysicalPreparedStatement() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t");
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);
        ResultSetMetaData metaData = statement.getMetaData();
        assertNotNull(metaData);
        assertTrue(context.calls.contains("getMetaData"));
    }

    @Test
    public void assertGetParameterMetaDataDelegatesToPhysicalPreparedStatement() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);
        ParameterMetaData parameterMetaData = statement.getParameterMetaData();
        assertNotNull(parameterMetaData);
        assertTrue(context.calls.contains("getParameterMetaData"));
    }

    @Test
    public void assertParameterMismatchThrowsExplicitSQLException() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(2, 5);
        DelegationContext context = new DelegationContext();
        statement.setPsProvider(context);
        try {
            statement.executeQuery();
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().contains("Parameter mismatch"));
            assertFalse(context.calls.contains("executeQuery"));
        }
    }

    static final class DelegationContext implements LBPreparedStatement.PhysicalPsProvider {

        Router.RouteTarget lastTarget;

        final List<String> calls = new ArrayList<String>();

        boolean failOnSetInt;

        public PreparedStatement getPreparedStatement(
                final Router.RouteTarget target, final String rawSql) throws SQLException {
            this.lastTarget = target;
            return new FakePhysicalPreparedStatement() {
                @Override
                protected Object dispatch(final String name, final Object[] args)
                        throws SQLException {
                    if ("setInt".equals(name)) {
                        if (failOnSetInt) {
                            throw new SQLException("boom-setInt");
                        }
                        calls.add("setInt:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setString".equals(name)) {
                        calls.add("setString:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setNull".equals(name) && args.length == 3) {
                        calls.add("setNullEx:" + args[0] + ":" + args[1] + ":" + args[2]);
                        return null;
                    }
                    if ("setNull".equals(name)) {
                        calls.add("setNull:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setByte".equals(name)) {
                        calls.add("setByte:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setShort".equals(name)) {
                        calls.add("setShort:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setLong".equals(name)) {
                        calls.add("setLong:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setFloat".equals(name)) {
                        calls.add("setFloat:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setDouble".equals(name)) {
                        calls.add("setDouble:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setBoolean".equals(name)) {
                        calls.add("setBoolean:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setDate".equals(name)) {
                        if (args.length == 3) {
                            calls.add("setDateCal:" + args[0] + ":" + args[1] + ":" + args[2]);
                            return null;
                        }
                        calls.add("setDate:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setTime".equals(name)) {
                        calls.add("setTime:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setTimestamp".equals(name)) {
                        calls.add("setTimestamp:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setBigDecimal".equals(name)) {
                        calls.add("setBigDecimal:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setURL".equals(name)) {
                        calls.add("setURL:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setRef".equals(name)) {
                        calls.add("setRef:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setArray".equals(name)) {
                        calls.add("setArray:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setRowId".equals(name)) {
                        calls.add("setRowId:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setBlob".equals(name)
                            && args.length == 2
                            && args[1] instanceof java.sql.Blob) {
                        calls.add("setBlobObj:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setBlob".equals(name) && args.length == 3) {
                        calls.add("setBlobLen:" + args[0] + ":" + args[1] + ":" + args[2]);
                        return null;
                    }
                    if ("setBlob".equals(name)
                            && args.length == 2
                            && args[1] instanceof java.io.InputStream) {
                        calls.add("setBlob:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setClob".equals(name)
                            && args.length == 2
                            && args[1] instanceof java.sql.Clob) {
                        calls.add("setClobObj:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setClob".equals(name) && args.length == 3) {
                        calls.add("setClobLen:" + args[0] + ":" + args[1] + ":" + args[2]);
                        return null;
                    }
                    if ("setClob".equals(name)
                            && args.length == 2
                            && args[1] instanceof java.io.Reader) {
                        calls.add("setClob:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setBytes".equals(name)) {
                        calls.add(
                                "setBytes:" + args[0] + ":" + toByteArrayLiteral((byte[]) args[1]));
                        return null;
                    }
                    if ("setCharacterStream".equals(name)) {
                        if (args.length == 3 && args[2] instanceof Integer) {
                            calls.add(
                                    "setCharacterStreamLenInt:"
                                            + args[0]
                                            + ":"
                                            + args[1]
                                            + ":"
                                            + args[2]);
                            return null;
                        }
                        if (args.length == 3 && args[2] instanceof Long) {
                            calls.add(
                                    "setCharacterStreamLenLong:"
                                            + args[0]
                                            + ":"
                                            + args[1]
                                            + ":"
                                            + args[2]);
                            return null;
                        }
                        calls.add("setCharacterStream:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setBinaryStream".equals(name)) {
                        if (args.length == 3 && args[2] instanceof Integer) {
                            calls.add(
                                    "setBinaryStreamLenInt:"
                                            + args[0]
                                            + ":"
                                            + args[1]
                                            + ":"
                                            + args[2]);
                            return null;
                        }
                        if (args.length == 3 && args[2] instanceof Long) {
                            calls.add(
                                    "setBinaryStreamLenLong:"
                                            + args[0]
                                            + ":"
                                            + args[1]
                                            + ":"
                                            + args[2]);
                            return null;
                        }
                        calls.add("setBinaryStream:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setAsciiStream".equals(name)) {
                        if (args.length == 3 && args[2] instanceof Integer) {
                            calls.add(
                                    "setAsciiStreamLenInt:"
                                            + args[0]
                                            + ":"
                                            + args[1]
                                            + ":"
                                            + args[2]);
                            return null;
                        }
                        if (args.length == 3 && args[2] instanceof Long) {
                            calls.add(
                                    "setAsciiStreamLenLong:"
                                            + args[0]
                                            + ":"
                                            + args[1]
                                            + ":"
                                            + args[2]);
                            return null;
                        }
                        calls.add("setAsciiStream:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setUnicodeStream".equals(name)) {
                        calls.add("setUnicodeStream:" + args[0] + ":" + args[1] + ":" + args[2]);
                        return null;
                    }
                    if ("setNString".equals(name)) {
                        calls.add("setNString:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setNCharacterStream".equals(name) && args.length == 3) {
                        calls.add(
                                "setNCharacterStreamLenLong:"
                                        + args[0]
                                        + ":"
                                        + args[1]
                                        + ":"
                                        + args[2]);
                        return null;
                    }
                    if ("setNCharacterStream".equals(name) && args.length == 2) {
                        calls.add("setNCharacterStream:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setNClob".equals(name)
                            && args.length == 2
                            && args[1] instanceof java.sql.NClob) {
                        calls.add("setNClobObj:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setNClob".equals(name) && args.length == 3) {
                        calls.add("setNClobLenLong:" + args[0] + ":" + args[1] + ":" + args[2]);
                        return null;
                    }
                    if ("setNClob".equals(name)
                            && args.length == 2
                            && args[1] instanceof java.io.Reader) {
                        calls.add("setNClob:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setSQLXML".equals(name)) {
                        calls.add("setSQLXML:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setOID".equals(name)) {
                        calls.add("setOID:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setCollection".equals(name)) {
                        calls.add("setCollection:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setTimestamptz".equals(name) && args.length == 2) {
                        calls.add("setTimestamptz:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    if ("setTimestamptz".equals(name) && args.length == 3) {
                        calls.add("setTimestamptzCal:" + args[0] + ":" + args[1] + ":" + args[2]);
                        return null;
                    }
                    if ("clearParameters".equals(name)) {
                        calls.add("clearParameters");
                        return null;
                    }
                    if ("addBatch".equals(name)) {
                        calls.add("addBatch");
                        return null;
                    }
                    if ("executeBatch".equals(name)) {
                        calls.add("executeBatch");
                        return new int[] {1};
                    }
                    if ("executeQuery".equals(name)) {
                        calls.add("executeQuery");
                        return Proxy.newProxyInstance(
                                ResultSet.class.getClassLoader(),
                                new Class[] {ResultSet.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        if (Boolean.TYPE.equals(m.getReturnType())) {
                                            return Boolean.FALSE;
                                        }
                                        if (Integer.TYPE.equals(m.getReturnType())) {
                                            return Integer.valueOf(0);
                                        }
                                        return null;
                                    }
                                });
                    }
                    if ("executeUpdate".equals(name)) {
                        calls.add("executeUpdate");
                        return Integer.valueOf(1);
                    }
                    if ("execute".equals(name)) {
                        calls.add("execute");
                        return Boolean.FALSE;
                    }
                    if ("executeInsert".equals(name)) {
                        calls.add("executeInsert");
                        return Proxy.newProxyInstance(
                                CUBRIDOID.class.getClassLoader(),
                                new Class[] {CUBRIDOID.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        return null;
                                    }
                                });
                    }
                    if ("getMetaData".equals(name)) {
                        calls.add("getMetaData");
                        return Proxy.newProxyInstance(
                                ResultSetMetaData.class.getClassLoader(),
                                new Class[] {ResultSetMetaData.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        if (Boolean.TYPE.equals(m.getReturnType())) {
                                            return Boolean.FALSE;
                                        }
                                        if (Integer.TYPE.equals(m.getReturnType())) {
                                            return Integer.valueOf(0);
                                        }
                                        return null;
                                    }
                                });
                    }
                    if ("getParameterMetaData".equals(name)) {
                        calls.add("getParameterMetaData");
                        return Proxy.newProxyInstance(
                                ParameterMetaData.class.getClassLoader(),
                                new Class[] {ParameterMetaData.class},
                                new InvocationHandler() {
                                    public Object invoke(Object p, Method m, Object[] a)
                                            throws Throwable {
                                        if (Boolean.TYPE.equals(m.getReturnType())) {
                                            return Boolean.FALSE;
                                        }
                                        if (Integer.TYPE.equals(m.getReturnType())) {
                                            return Integer.valueOf(0);
                                        }
                                        return null;
                                    }
                                });
                    }
                    return null;
                }
            };
        }

        public void prepareOnBoundReadEndpoint(final String rawSql) throws SQLException {
            calls.add("prepareOnBoundReadEndpoint:" + rawSql);
        }

        public void prepareOnWriteEndpoint(final String rawSql) throws SQLException {
            calls.add("prepareOnWriteEndpoint:" + rawSql);
        }

        public void closePrepStmts() throws SQLException {
            calls.add("closePrepStmts");
        }

        boolean containsPrefix(final String prefix) {
            for (int i = 0; i < calls.size(); i++) {
                if (calls.get(i).startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        int countCall(final String call) {
            int result = 0;
            for (int i = 0; i < calls.size(); i++) {
                if (call.equals(calls.get(i))) {
                    result++;
                }
            }
            return result;
        }

        int countPrefix(final String prefix) {
            int result = 0;
            for (int i = 0; i < calls.size(); i++) {
                if (calls.get(i).startsWith(prefix)) {
                    result++;
                }
            }
            return result;
        }

        private String toByteArrayLiteral(final byte[] value) {
            if (null == value) {
                return "null";
            }
            StringBuilder result = new StringBuilder();
            result.append('[');
            for (int i = 0; i < value.length; i++) {
                if (i > 0) {
                    result.append(", ");
                }
                result.append(value[i]);
            }
            result.append(']');
            return result.toString();
        }
    }
}
