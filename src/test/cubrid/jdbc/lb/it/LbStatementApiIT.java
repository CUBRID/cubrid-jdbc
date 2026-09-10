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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.metrics.RuntimeMetrics;
import cubrid.jdbc.lb.statement.LBStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

public final class LbStatementApiIT {

    private static final String DEFAULT_BASE_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final String DEFAULT_USER = "dba";
    private static final String DEFAULT_TABLE = "lb_statement_it";

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
    public void executeQueryReturnsRowsAndTheToRwHintRoutesItToMaster() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        Statement statement = connection.createStatement();
        try {
            statement.executeUpdate("DELETE FROM " + tableName);
            statement.executeUpdate(
                    "INSERT INTO " + tableName + " (id, v, note) VALUES (1, 10, 'a')");
            statement.executeUpdate(
                    "INSERT INTO " + tableName + " (id, v, note) VALUES (2, 20, 'b')");

            Thread.sleep(1000);
            ResultSet rs =
                    statement.executeQuery(
                            "SELECT /*+ TO_RW */  id, v FROM " + tableName + " ORDER BY id ASC");
            int rowCount = 0;
            int sum = 0;
            while (rs.next()) {
                rowCount++;
                sum += rs.getInt("v");
            }
            rs.close();

            assertEquals(2, rowCount);
            assertEquals(30, sum);
            String event = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(event.contains("sqlType=READ"));
            assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        } finally {
            statement.close();
            connection.close();
        }
    }

    @Test
    public void executeExposesResultSetAndUpdateCountThroughAccessors() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        Statement statement = connection.createStatement();
        try {
            statement.executeUpdate("DELETE FROM " + tableName);
            statement.executeUpdate(
                    "INSERT INTO " + tableName + " (id, v, note) VALUES (10, 1, 'x')");

            boolean selectResult =
                    statement.execute(
                            "SELECT /*+ TO_RW */  v FROM " + tableName + " WHERE id = 10");
            assertTrue(selectResult);
            ResultSet rs = statement.getResultSet();
            assertNotNull(rs);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            rs.close();
            try {
                statement.getMoreResults();
            } catch (java.sql.SQLException expected) {
                assertTrue(expected instanceof java.sql.SQLFeatureNotSupportedException);
            }
            try {
                statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                throw new AssertionError(
                        "Expected SQLException because physical getMoreResults(int) is unsupported");
            } catch (java.sql.SQLException expected) {
                assertTrue(expected instanceof java.sql.SQLFeatureNotSupportedException);
            }

            boolean updateResult =
                    statement.execute("UPDATE " + tableName + " SET v = 11 WHERE id = 10");
            assertFalse(updateResult);
            assertEquals(1, statement.getUpdateCount());

            String event = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(event.contains("sqlType=WRITE"));
            assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        } finally {
            statement.close();
            connection.close();
        }
    }

    @Test
    public void batchExecutesEveryStatementAndClearBatchEmptiesIt() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        Statement statement = connection.createStatement();
        try {
            statement.executeUpdate("DELETE FROM " + tableName);

            statement.addBatch("INSERT INTO " + tableName + " (id, v, note) VALUES (21, 1, 'b1')");
            statement.addBatch("INSERT INTO " + tableName + " (id, v, note) VALUES (22, 2, 'b2')");
            statement.addBatch("UPDATE " + tableName + " SET v = 3 WHERE id = 21");
            int[] batchResult = statement.executeBatch();
            assertEquals(3, batchResult.length);

            statement.addBatch("INSERT INTO " + tableName + " (id, v, note) VALUES (23, 9, 'x')");
            statement.clearBatch();
            int[] emptyResult = statement.executeBatch();
            assertEquals(0, emptyResult.length);

            Thread.sleep(1000);
            ResultSet rs =
                    statement.executeQuery("SELECT /*+ TO_RW */  COUNT(*) FROM " + tableName);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs.close();
        } finally {
            statement.close();
            connection.close();
        }
    }

    @Test
    public void executeOverloadsAcceptGeneratedKeyArguments() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        Statement statement = connection.createStatement();
        try {
            statement.executeUpdate("DELETE FROM " + tableName);
            statement.executeUpdate(
                    "INSERT INTO " + tableName + " (id, v, note) VALUES (31, 1, 'g')");

            int updatedByKeys =
                    statement.executeUpdate(
                            "UPDATE " + tableName + " SET v = 2 WHERE id = 31",
                            Statement.RETURN_GENERATED_KEYS);
            assertEquals(1, updatedByKeys);

            int updatedByIndexes =
                    statement.executeUpdate(
                            "UPDATE " + tableName + " SET v = 3 WHERE id = 31", new int[] {1});
            assertEquals(1, updatedByIndexes);

            int updatedByNames =
                    statement.executeUpdate(
                            "UPDATE " + tableName + " SET v = 4 WHERE id = 31",
                            new String[] {"id"});
            assertEquals(1, updatedByNames);

            boolean executeByKeys =
                    statement.execute(
                            "UPDATE " + tableName + " SET v = 5 WHERE id = 31",
                            Statement.RETURN_GENERATED_KEYS);
            assertFalse(executeByKeys);
            assertEquals(1, statement.getUpdateCount());

            boolean executeByIndexes =
                    statement.execute(
                            "UPDATE " + tableName + " SET v = 6 WHERE id = 31", new int[] {1});
            assertFalse(executeByIndexes);
            assertEquals(1, statement.getUpdateCount());

            boolean executeByNames =
                    statement.execute(
                            "UPDATE " + tableName + " SET v = 7 WHERE id = 31",
                            new String[] {"id"});
            assertFalse(executeByNames);
            assertEquals(1, statement.getUpdateCount());

            ResultSet generatedKeys = statement.getGeneratedKeys();
            assertNotNull(generatedKeys);
            generatedKeys.close();
        } finally {
            statement.close();
            connection.close();
        }
    }

    @Test
    public void statementPropertiesRoundTripAndStateApisReportThem() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        Statement statement =
                connection.createStatement(
                        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        try {
            statement.executeUpdate("DELETE FROM " + tableName);
            statement.executeUpdate(
                    "INSERT INTO " + tableName + " (id, v, note) VALUES (41, 1, 'p')");
            statement.executeUpdate(
                    "INSERT INTO " + tableName + " (id, v, note) VALUES (42, 2, 'p')");

            Thread.sleep(1000);

            statement.setMaxRows(1);
            statement.setQueryTimeout(3);
            statement.setFetchSize(1);
            statement.setFetchDirection(ResultSet.FETCH_REVERSE);
            statement.setEscapeProcessing(false);
            statement.setCursorName("lb_statement_cursor");
            statement.setMaxFieldSize(128);
            statement.closeOnCompletion();

            assertEquals(1, statement.getMaxRows());
            assertEquals(3, statement.getQueryTimeout());
            assertEquals(1, statement.getFetchSize());
            assertEquals(ResultSet.FETCH_REVERSE, statement.getFetchDirection());
            assertEquals(128, statement.getMaxFieldSize());
            assertTrue(statement.isCloseOnCompletion());
            assertNullSafeWarning(statement.getWarnings());
            statement.clearWarnings();

            try {
                statement.executeQuery("SELECT id FROM " + tableName + " ORDER BY id ASC");
                throw new AssertionError(
                        "Expected SQLException because physical closeOnCompletion is unsupported");
            } catch (java.sql.SQLException expected) {
                assertTrue(expected instanceof java.sql.SQLFeatureNotSupportedException);
            }

            Statement plainStatement =
                    connection.createStatement(
                            ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
            plainStatement.setMaxRows(1);
            plainStatement.setQueryTimeout(3);
            plainStatement.setFetchSize(1);
            plainStatement.setFetchDirection(ResultSet.FETCH_REVERSE);
            plainStatement.setEscapeProcessing(false);
            plainStatement.setCursorName("lb_statement_cursor_2");
            plainStatement.setMaxFieldSize(128);

            ResultSet rs =
                    plainStatement.executeQuery(
                            "SELECT /*+ TO_RW */  id FROM " + tableName + " ORDER BY id ASC");
            int count = 0;
            while (rs.next()) {
                count++;
            }
            rs.close();
            assertEquals(1, count);
            plainStatement.close();

            try {
                statement.setPoolable(true);
                throw new AssertionError("Expected SQLException from setPoolable");
            } catch (Exception expected) {
                assertTrue(expected instanceof java.sql.SQLException);
            }
            try {
                statement.isPoolable();
                throw new AssertionError("Expected SQLException from isPoolable");
            } catch (Exception expected) {
                assertTrue(expected instanceof java.sql.SQLException);
            }

            statement.cancel();
        } finally {
            statement.close();
            connection.close();
        }
    }

    @Test
    public void vendorStatementApisReachTheLiveBroker() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        LBStatement statement = (LBStatement) connection.createStatement();
        try {
            statement.executeUpdate("DELETE FROM " + tableName);
            statement.executeUpdate(
                    "INSERT INTO " + tableName + " (id, v, note) VALUES (51, 5, 'q')");

            statement.setCurrentTransaction(false);
            assertFalse(statement.isFromCurrentTransaction());
            statement.setCurrentTransaction(true);
            assertTrue(statement.isFromCurrentTransaction());

            statement.setQueryInfo(true);
            statement.setOnlyQueryPlan(true);
            String queryPlanBySql =
                    statement.getQueryplan("SELECT id FROM " + tableName + " WHERE id = 51");
            assertNotNull(queryPlanBySql);

            statement.executeQuery("SELECT id FROM " + tableName + " WHERE id = 51").close();
            String queryPlanByLast = statement.getQueryplan();
            assertNotNull(queryPlanByLast);

            byte statementType = statement.getStatementType();
            assertTrue(statementType >= 0);
        } finally {
            statement.close();
            connection.close();
        }
    }

    private static void assertNullSafeWarning(final java.sql.SQLWarning warning) {
        // getWarnings() may be null if physical statement has no warnings.
        if (warning != null) {
            assertNotNull(warning.getMessage());
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
        LoadBalanceConnection lbConnection = (LoadBalanceConnection) connection;
        lbConnection.getRuntimeMetrics().setEnabled(true);
        return lbConnection;
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
                    "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, v INT, note VARCHAR(64))");
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

    private static String lastSqlEvent(final RuntimeMetrics metrics) {
        java.util.List<String> events = metrics.getEvents();
        for (int i = events.size() - 1; i >= 0; i--) {
            String event = events.get(i);
            if (event.contains("sqlType=READ")
                    || event.contains("sqlType=WRITE")
                    || event.contains("sqlType=UNKNOWN")) {
                return event;
            }
        }
        throw new IllegalStateException("No SQL runtime event recorded");
    }
}
