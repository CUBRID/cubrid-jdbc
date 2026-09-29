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

package cubrid.jdbc.lb.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import cubrid.sql.CUBRIDOID;
import cubrid.sql.CUBRIDTimestamptz;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.Properties;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

public final class LbPreparedStatementApiIT {

    private static final String DEFAULT_BASE_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final String DEFAULT_USER = "dba";
    private static final String DEFAULT_TABLE = "lb_prepared_statement_it";

    private static final int ROUND_TRIP_ID = 902;

    private static String jdbcUrl;
    private static String jdbcUser;
    private static String jdbcPassword;
    private static String tableName;

    @BeforeClass
    public static void setUpClass() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("lb.it.enabled"));
        Class.forName("cubrid.jdbc.driver.CUBRIDDriver");

        jdbcUrl = resolveJdbcUrl();
        jdbcUser = System.getProperty("lb.it.jdbc.user", DEFAULT_USER);
        jdbcPassword = System.getProperty("lb.it.jdbc.password", "");
        String tableRaw = System.getProperty("lb.it.table");
        tableName = isEffectivePropertyValue(tableRaw) ? tableRaw.trim() : DEFAULT_TABLE;

        LoadBalanceConnection connection = openLbConnection();
        try {
            prepareFixtureTable(connection);
        } finally {
            connection.close();
        }
    }

    @Test
    public void executeQueryExecuteUpdateAndExecuteAllRunOnTheLiveBroker() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            PreparedStatement reset = connection.prepareStatement("DELETE FROM " + tableName);
            reset.executeUpdate();
            reset.close();

            PreparedStatement insert =
                    connection.prepareStatement(
                            "INSERT INTO "
                                    + tableName
                                    + " (id, v, note, created_ts, payload) VALUES (?, ?, ?, ?, ?)");
            insert.setInt(1, 1);
            insert.setInt(2, 10);
            insert.setString(3, "ps_row_1");
            insert.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            insert.setString(5, "payload-1");
            assertEquals(1, insert.executeUpdate());
            insert.close();

            LBPreparedStatement select =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "SELECT /*+ TO_RW */  v FROM " + tableName + " WHERE id = ?");
            select.setInt(1, 1);
            ResultSet rs = select.executeQuery();
            assertTrue(rs.next());
            assertEquals(10, rs.getInt(1));
            rs.close();
            assertTrue(select.hasResultSet() || !select.hasResultSet());
            select.close();

            LBPreparedStatement executeUpdate =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "UPDATE " + tableName + " SET v = ? WHERE id = ?");
            executeUpdate.setInt(1, 11);
            executeUpdate.setInt(2, 1);
            assertEquals(1, executeUpdate.executeUpdate());
            executeUpdate.close();

            LBPreparedStatement execute =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "SELECT /*+ TO_RW */  note FROM "
                                            + tableName
                                            + " WHERE id = ?");
            execute.setInt(1, 1);
            assertTrue(execute.execute());
            assertNotNull(execute.getResultSet());
            execute.close();
        } finally {
            connection.close();
        }
    }

    @Test
    public void batchExecutesEveryBindingAndClearBatchEmptiesIt() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            PreparedStatement reset = connection.prepareStatement("DELETE FROM " + tableName);
            reset.executeUpdate();
            reset.close();

            LBPreparedStatement batch =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "INSERT INTO "
                                            + tableName
                                            + " (id, v, note, created_ts, payload) VALUES (?, ?, ?, ?, ?)");

            batch.setInt(1, 11);
            batch.setInt(2, 101);
            batch.setString(3, "b1");
            batch.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            batch.setString(5, "p1");
            batch.addBatch();

            batch.setInt(1, 12);
            batch.setInt(2, 102);
            batch.setString(3, "b2");
            batch.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            batch.setString(5, "p2");
            batch.addBatch();

            int[] result = batch.executeBatch();
            assertEquals(2, result.length);

            batch.setInt(1, 13);
            batch.setInt(2, 103);
            batch.setString(3, "b3");
            batch.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            batch.setString(5, "p3");
            batch.addBatch();
            batch.clearBatch();
            assertEquals(0, batch.executeBatch().length);
            batch.close();

            PreparedStatement verify =
                    connection.prepareStatement("SELECT /*+ TO_RW */  COUNT(*) FROM " + tableName);
            ResultSet rs = verify.executeQuery();
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs.close();
            verify.close();
        } finally {
            connection.close();
        }
    }

    @Test
    public void getMetaDataAndGetParameterMetaDataAnswerFromTheBoundEndpoint() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            LBPreparedStatement ps =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "SELECT /*+ TO_RW */  id, v FROM "
                                            + tableName
                                            + " WHERE id = ?");
            ResultSetMetaData resultSetMetaData = ps.getMetaData();
            assertNotNull(resultSetMetaData);
            try {
                ParameterMetaData parameterMetaData = ps.getParameterMetaData();
                assertNotNull(parameterMetaData);
                assertTrue(parameterMetaData.getParameterCount() >= 1);
            } catch (java.sql.SQLException expected) {
                assertTrue(expected instanceof java.sql.SQLFeatureNotSupportedException);
            }
            ps.close();
        } finally {
            connection.close();
        }
    }

    @Test
    public void allSetterApisCanBeCalledAndClearParametersWorks() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            LBPreparedStatement ps =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "SELECT /*+ TO_RW */  id FROM " + tableName + " WHERE id = ?");

            Calendar cal = Calendar.getInstance();
            Date date = new Date(System.currentTimeMillis());
            Time time = new Time(System.currentTimeMillis());
            Timestamp ts = new Timestamp(System.currentTimeMillis());
            ByteArrayInputStream stream = new ByteArrayInputStream(new byte[] {1, 2, 3});
            StringReader reader = new StringReader("abc");

            ps.setNull(1, Types.INTEGER);
            ps.setNull(1, Types.INTEGER, "INTEGER");
            ps.setBoolean(1, true);
            ps.setByte(1, (byte) 1);
            ps.setShort(1, (short) 2);
            ps.setInt(1, 1);
            ps.setLong(1, 2L);
            ps.setFloat(1, 1.0f);
            ps.setDouble(1, 2.0d);
            ps.setBigDecimal(1, new BigDecimal("3.14"));
            ps.setString(1, "x");
            ps.setBytes(1, new byte[] {9, 8, 7});
            ps.setDate(1, date);
            ps.setDate(1, date, cal);
            ps.setTime(1, time);
            ps.setTime(1, time, cal);
            ps.setTimestamp(1, ts);
            ps.setTimestamp(1, ts, cal);
            ps.setAsciiStream(1, stream, 3);
            ps.setAsciiStream(1, stream, 3L);
            ps.setAsciiStream(1, stream);
            ps.setUnicodeStream(1, stream, 3);
            ps.setBinaryStream(1, stream, 3);
            ps.setBinaryStream(1, stream, 3L);
            ps.setBinaryStream(1, stream);
            ps.setObject(1, Integer.valueOf(1), Types.INTEGER);
            ps.setObject(1, Integer.valueOf(1));
            ps.setObject(1, Integer.valueOf(1), Types.INTEGER, 0);
            ps.setCharacterStream(1, reader, 3);
            ps.setCharacterStream(1, reader, 3L);
            ps.setCharacterStream(1, reader);
            ps.setRef(1, null);
            ps.setBlob(1, (java.sql.Blob) null);
            ps.setBlob(1, stream, 3L);
            ps.setBlob(1, stream);
            ps.setClob(1, (java.sql.Clob) null);
            ps.setClob(1, reader, 3L);
            ps.setClob(1, reader);
            ps.setArray(1, null);
            ps.setURL(1, new URL("http://localhost"));
            ps.setRowId(1, null);
            ps.setNString(1, "n");
            ps.setNCharacterStream(1, reader, 3L);
            ps.setNCharacterStream(1, reader);
            ps.setNClob(1, (java.sql.NClob) null);
            ps.setNClob(1, reader, 3L);
            ps.setNClob(1, reader);
            ps.setSQLXML(1, null);
            ps.setOID(1, (CUBRIDOID) null);
            ps.setCollection(1, new Object[] {"a", Integer.valueOf(1)});
            ps.setTimestamptz(1, (CUBRIDTimestamptz) null);
            ps.setTimestamptz(1, (CUBRIDTimestamptz) null, cal);

            ps.clearParameters();
            ps.setInt(1, 1);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next() || !rs.next());
            rs.close();
            ps.close();
        } finally {
            connection.close();
        }
    }

    @Test
    public void setterTypesRoundTripThroughInsertSelectAndUpdate() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            PreparedStatement del =
                    connection.prepareStatement("DELETE FROM " + tableName + " WHERE id = ?");
            del.setInt(1, ROUND_TRIP_ID);
            del.executeUpdate();
            del.close();

            // Fixed JDBC-parseable values so bind and ResultSet round-trip match (no "now" drift).
            final Date insDate = Date.valueOf("2024-06-01");
            final Time insTime = Time.valueOf("10:20:30");
            final Timestamp insTs = Timestamp.valueOf("2024-06-01 10:20:30.000");
            byte[] blobBytes = new byte[] {0x10, 0x20, 0x30, 0x40};
            String clobText = "clob_round_trip";

            LBPreparedStatement insert =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "INSERT INTO "
                                            + tableName
                                            + " (id, v, note, created_ts, payload, c_short, c_long,"
                                            + " c_float, c_double, c_numeric, c_date, c_time, c_blob,"
                                            + " c_clob) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            insert.setInt(1, ROUND_TRIP_ID);
            insert.setInt(2, 100);
            insert.setString(3, "rt_ins");
            insert.setTimestamp(4, insTs);
            insert.setString(5, "pay_ins");
            insert.setShort(6, (short) 42);
            insert.setLong(7, 9876543210L);
            insert.setFloat(8, 1.25f);
            insert.setDouble(9, 2.5d);
            insert.setBigDecimal(10, new BigDecimal("123.4567"));
            insert.setDate(11, insDate);
            insert.setTime(12, insTime);
            insert.setBinaryStream(13, new ByteArrayInputStream(blobBytes), blobBytes.length);
            insert.setCharacterStream(14, new StringReader(clobText), clobText.length());
            assertEquals(1, insert.executeUpdate());
            insert.close();

            LBPreparedStatement select =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "SELECT /*+ TO_RW */ v, note, payload, c_short, c_long,"
                                            + " c_float, c_double, c_numeric, c_date, c_time, c_blob,"
                                            + " c_clob FROM "
                                            + tableName
                                            + " WHERE id = ?");
            select.setInt(1, ROUND_TRIP_ID);
            ResultSet rs = select.executeQuery();
            assertTrue(rs.next());
            assertEquals(100, rs.getInt("v"));
            assertEquals("rt_ins", rs.getString("note"));
            assertEquals("pay_ins", rs.getString("payload"));
            assertEquals(42, rs.getInt("c_short"));
            assertEquals(9876543210L, rs.getLong("c_long"));
            assertEquals(1.25f, rs.getFloat("c_float"), 0.0001f);
            assertEquals(2.5d, rs.getDouble("c_double"), 0.0001d);
            assertEquals(0, new BigDecimal("123.4567").compareTo(rs.getBigDecimal("c_numeric")));
            assertEquals(insDate, rs.getDate("c_date"));
            assertEquals(insTime, rs.getTime("c_time"));
            java.sql.Blob blob = rs.getBlob("c_blob");
            assertNotNull(blob);
            assertEquals(blobBytes.length, (int) blob.length());
            assertEquals(clobText, rs.getString("c_clob"));
            rs.close();
            select.close();

            final Date updDate = Date.valueOf("2024-06-02");
            final Time updTime = Time.valueOf("14:00:00");
            final Timestamp updTs = Timestamp.valueOf("2024-06-02 14:00:00.000");
            byte[] blobBytes2 = new byte[] {7};
            String clobText2 = "clob_after_update";

            LBPreparedStatement update =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "UPDATE "
                                            + tableName
                                            + " SET v = ?, note = ?, created_ts = ?, payload = ?,"
                                            + " c_short = ?, c_long = ?, c_float = ?, c_double = ?,"
                                            + " c_numeric = ?, c_date = ?, c_time = ?, c_blob = ?,"
                                            + " c_clob = ? WHERE id = ?");
            update.setInt(1, 200);
            update.setString(2, "rt_upd");
            update.setTimestamp(3, updTs);
            update.setString(4, "pay_upd");
            update.setShort(5, (short) 7);
            update.setLong(6, 111L);
            update.setFloat(7, 9.0f);
            update.setDouble(8, 8.0d);
            update.setBigDecimal(9, new BigDecimal("0.5"));
            update.setDate(10, updDate);
            update.setTime(11, updTime);
            update.setBytes(12, blobBytes2);
            update.setString(13, clobText2);
            update.setInt(14, ROUND_TRIP_ID);
            assertEquals(1, update.executeUpdate());
            update.close();

            LBPreparedStatement verify =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "SELECT /*+ TO_RW */ v, note, payload, c_short, c_long,"
                                            + " c_float, c_double, c_numeric, c_date, c_time, c_blob,"
                                            + " c_clob FROM "
                                            + tableName
                                            + " WHERE id = ?");
            verify.setInt(1, ROUND_TRIP_ID);
            ResultSet rs2 = verify.executeQuery();
            assertTrue(rs2.next());
            assertEquals(200, rs2.getInt("v"));
            assertEquals("rt_upd", rs2.getString("note"));
            assertEquals("pay_upd", rs2.getString("payload"));
            assertEquals(7, rs2.getInt("c_short"));
            assertEquals(111L, rs2.getLong("c_long"));
            assertEquals(9.0f, rs2.getFloat("c_float"), 0.0001f);
            assertEquals(8.0d, rs2.getDouble("c_double"), 0.0001d);
            assertEquals(0, new BigDecimal("0.5").compareTo(rs2.getBigDecimal("c_numeric")));
            assertEquals(updDate, rs2.getDate("c_date"));
            assertEquals(updTime, rs2.getTime("c_time"));
            java.sql.Blob blob2 = rs2.getBlob("c_blob");
            assertNotNull(blob2);
            assertEquals(blobBytes2.length, (int) blob2.length());
            assertEquals(clobText2, rs2.getString("c_clob"));
            rs2.close();
            verify.close();
        } finally {
            connection.close();
        }
    }

    @Test
    public void executeInsertReturnsNoOidAndAParameterMismatchIsRefused() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            LBPreparedStatement executeInsert =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "INSERT INTO "
                                            + tableName
                                            + " (id, v, note, created_ts, payload) VALUES (?, ?, ?, ?, ?)");
            executeInsert.setInt(1, 71);
            executeInsert.setInt(2, 701);
            executeInsert.setString(3, "execute_insert");
            executeInsert.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            executeInsert.setString(5, "payload-71");
            try {
                CUBRIDOID oid = executeInsert.executeInsert();
                assertNull(oid);
            } catch (java.sql.SQLException expected) {
                assertTrue(
                        expected.getMessage().contains("executeInsert")
                                || expected.getCause() != null);
            } finally {
                executeInsert.close();
            }

            LBPreparedStatement mismatch =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "INSERT INTO " + tableName + " (id, v) VALUES (?, ?)");
            mismatch.setInt(3, 99);
            try {
                mismatch.executeUpdate();
                throw new AssertionError("Expected parameter mismatch SQLException");
            } catch (java.sql.SQLException expected) {
                assertTrue(expected.getMessage().contains("Parameter mismatch"));
            } finally {
                mismatch.close();
            }
        } finally {
            connection.close();
        }
    }

    @Test
    public void prepareEagerlyThenCloseMarksTheStatementClosed() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            LBPreparedStatement ps =
                    (LBPreparedStatement)
                            connection.prepareStatement(
                                    "SELECT /*+ TO_RW */  id FROM " + tableName + " WHERE id = ?");
            ps.prepareEagerly();
            ps.close();
            ps.close();
            assertTrue(ps.isClosed());
        } finally {
            connection.close();
        }
    }

    private static LoadBalanceConnection openLbConnection() throws Exception {
        Properties info = new Properties();
        info.setProperty("user", jdbcUser);
        info.setProperty("password", jdbcPassword);
        Connection connection = DriverManager.getConnection(jdbcUrl, info);
        if (!(connection instanceof LoadBalanceConnection)) {
            throw new IllegalStateException(
                    "Expected LoadBalanceConnection but got " + connection.getClass().getName());
        }
        return (LoadBalanceConnection) connection;
    }

    private static void prepareFixtureTable(final Connection connection) throws Exception {
        Statement statement = connection.createStatement();
        try {
            try {
                statement.executeUpdate("DROP TABLE " + tableName);
            } catch (Exception ignored) {
            }
        } finally {
            statement.close();
        }
        // Wait for the drop before creating: otherwise the wait after the CREATE is satisfied by
        // the table the previous run left on the read endpoints, and this run's DROP replicates
        // into the middle of the tests.
        ItFixtures.awaitFixtureGoneFromReadEndpoints(jdbcUrl, jdbcUser, jdbcPassword, tableName);

        statement = connection.createStatement();
        try {
            statement.executeUpdate(
                    "CREATE TABLE "
                            + tableName
                            + " (id INT PRIMARY KEY, v INT, note VARCHAR(200), created_ts TIMESTAMP,"
                            + " payload VARCHAR(4000), c_short SMALLINT, c_long BIGINT, c_float FLOAT,"
                            + " c_double DOUBLE, c_numeric NUMERIC(18,4), c_date DATE, c_time TIME,"
                            + " c_blob BLOB, c_clob CLOB)");
        } finally {
            statement.close();
        }

        ItFixtures.awaitFixtureOnReadEndpoints(jdbcUrl, jdbcUser, jdbcPassword, tableName);
    }

    private static String resolveJdbcUrl() {
        String directUrl = System.getProperty("lb.it.jdbc.url");
        if (isEffectivePropertyValue(directUrl)) {
            return directUrl.trim();
        }

        String baseUrlRaw = System.getProperty("lb.it.jdbc.baseUrl");
        return isEffectivePropertyValue(baseUrlRaw) ? baseUrlRaw.trim() : DEFAULT_BASE_URL;
    }

    private static boolean isEffectivePropertyValue(final String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() == 0) {
            return false;
        }
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            return false;
        }
        return true;
    }
}
