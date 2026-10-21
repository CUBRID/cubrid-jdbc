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

import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.metrics.RuntimeMetrics;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Live scenario integration test using real CUBRID endpoints.
 *
 * <p>Enable with: {@code -Dlb.it.enabled=true}. URL can be supplied directly via {@code
 * -Dlb.it.jdbc.url=...}; if omitted, {@code -Dlb.it.jdbc.baseUrl=...} is used.
 */
public final class LbRoutingDecisionIT {

    private static final String DEFAULT_BASE_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final String DEFAULT_USER = "dba";
    private static final String DEFAULT_TABLE = "lb_scenario_it";

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
    public void nonTransactionSelectRoutesToReadOnly() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            executeSelect(connection, "SELECT id FROM " + tableName + " WHERE id = ?");
            String event = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(event.contains("sqlType=READ"));
            assertTrue(event.contains("routeTarget=TO_READ_ONLY"));
        } finally {
            connection.close();
        }
    }

    @Test
    public void transactionReadFallsBackToMaster() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            connection.setAutoCommit(false);
            executeSelect(connection, "SELECT id FROM " + tableName + " WHERE id = ?");
            String event = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(event.contains("sqlType=READ"));
            assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
            connection.commit();
        } finally {
            connection.close();
        }
    }

    @Test
    public void hintToMasterForcesMasterRoute() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            executeSelect(
                    connection, "SELECT /*+ TO_RW */  id FROM " + tableName + " WHERE id = ?");
            String event = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(event.contains("sqlType=READ"));
            assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        } finally {
            connection.close();
        }
    }

    @Test
    public void lobFunctionReadUsesMasterOnly() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            executeSelect(
                    connection, "SELECT CLOB_TO_CHAR(doc) FROM " + tableName + " WHERE id = ?");
            String event = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(event.contains("sqlType=UNKNOWN"));
            assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        } finally {
            connection.close();
        }
    }

    @Test
    public void hintToSlaveRoutesReadToReadOnly() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            executeSelect(
                    connection, "SELECT /*+ TO_RO */  id FROM " + tableName + " WHERE id = ?");
            String event = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(event.contains("sqlType=READ"));
            assertTrue(event.contains("routeTarget=TO_READ_ONLY"));
        } finally {
            connection.close();
        }
    }

    @Test
    public void writeStatementAlwaysUsesMaster() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("UPDATE " + tableName + " SET v = ? WHERE id = ?");
            try {
                statement.setInt(1, 11);
                statement.setInt(2, 1);
                statement.executeUpdate();
            } finally {
                statement.close();
            }

            String event = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(event.contains("sqlType=WRITE"));
            assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        } finally {
            connection.close();
        }
    }

    @Test
    public void transactionBoundaryReturnsReadToReadOnlyAfterCommit() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            executeSelect(connection, "SELECT id FROM " + tableName + " WHERE id = ?");
            String first = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(first.contains("routeTarget=TO_READ_ONLY"));

            connection.setAutoCommit(false);
            executeSelect(connection, "SELECT id FROM " + tableName + " WHERE id = ?");
            String second = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(second.contains("routeTarget=TO_READ_WRITE"));

            connection.commit();
            connection.setAutoCommit(true);
            executeSelect(connection, "SELECT id FROM " + tableName + " WHERE id = ?");
            String third = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(third.contains("routeTarget=TO_READ_ONLY"));
        } finally {
            connection.close();
        }
    }

    @Test
    public void metadataCallsRouteToMasterCommandPath() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            DatabaseMetaData dbmd = connection.getMetaData();
            // Not a driver-version accessor: those two answer from this driver's own build
            // constant and never touch an endpoint, so they no longer represent the command path.
            // They declare no throws clause, so they could not report a bind failure anyway.
            assertTrue(dbmd.getDatabaseProductName().length() > 0);

            String event =
                    findLastEventContaining(connection.getRuntimeMetrics(), "sqlType=COMMAND");
            assertTrue(event.contains("command=Call: getDatabaseProductName"));
            assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        } finally {
            connection.close();
        }
    }

    @Test
    public void mixedFlowEmitsReadMasterAndCommandMetrics() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            executeSelect(connection, "SELECT id FROM " + tableName + " WHERE id = ?");
            executeSelect(
                    connection, "SELECT /*+ TO_RW */  id FROM " + tableName + " WHERE id = ?");
            connection.getMetaData().getUserName();

            List<String> events = connection.getRuntimeMetrics().getEvents();
            assertTrue(containsEvent(events, "sqlType=READ,routeTarget=TO_READ_ONLY"));
            assertTrue(containsEvent(events, "sqlType=READ,routeTarget=TO_READ_WRITE"));
            assertTrue(containsEvent(events, "sqlType=COMMAND,command=Call: getUserName"));
        } finally {
            connection.close();
        }
    }

    /**
     * The stateful hint trio was removed in 2026-08-11 — the tokens are unrecognized now, so a read
     * carrying one still routes read-only and the session keeps no pin.
     */
    @Test
    public void removedStatefulHintsDoNotPinTheSession() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            executeSelect(
                    connection,
                    "SELECT /*+ AFTER_WRITE_TO_RW */ id FROM " + tableName + " WHERE id = ?");
            executeSelect(
                    connection,
                    "SELECT /*+ STICKY_TO_RW */ id FROM " + tableName + " WHERE id = ?");
            executeSelect(connection, "SELECT id FROM " + tableName + " WHERE id = ?");
            String event = lastSqlEvent(connection.getRuntimeMetrics());
            assertTrue(event.contains("sqlType=READ"));
            assertTrue(event.contains("routeTarget=TO_READ_ONLY"));
        } finally {
            connection.close();
        }
    }

    /** Read-after-write is per statement now: TO_RW binds its own read, the next one is free. */
    @Test
    public void toRwHintAppliesToItsOwnStatementOnly() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            executeSelect(connection, "SELECT /*+ TO_RW */ id FROM " + tableName + " WHERE id = ?");
            assertTrue(
                    lastSqlEvent(connection.getRuntimeMetrics())
                            .contains("routeTarget=TO_READ_WRITE"));

            executeSelect(connection, "SELECT id FROM " + tableName + " WHERE id = ?");
            assertTrue(
                    lastSqlEvent(connection.getRuntimeMetrics())
                            .contains("routeTarget=TO_READ_ONLY"));
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
                    "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, v INT, doc CLOB)");
        } finally {
            statement.close();
        }

        PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO " + tableName + " (id, v, doc) VALUES (?, ?, ?)");
        try {
            insert.setInt(1, 1);
            insert.setInt(2, 10);
            insert.setString(3, "lb_scenario_doc");
            insert.executeUpdate();
        } finally {
            insert.close();
        }

        ItFixtures.awaitFixtureOnReadEndpoints(jdbcUrl, jdbcUser, jdbcPassword, tableName);
    }

    private static void executeSelect(final Connection connection, final String sql)
            throws Exception {
        executeSelectWithId(connection, sql, 1);
    }

    private static void executeSelectWithId(
            final Connection connection, final String sql, final int id) throws Exception {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setInt(1, id);
            statement.executeQuery().close();
        } finally {
            statement.close();
        }
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
        // Ant may pass unresolved placeholders literally (e.g. "${lb.it.jdbc.url}").
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            return false;
        }
        return true;
    }

    private static String lastSqlEvent(final RuntimeMetrics metrics) {
        List<String> events = metrics.getEvents();
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

    private static String findLastEventContaining(
            final RuntimeMetrics metrics, final String needle) {
        List<String> events = metrics.getEvents();
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).contains(needle)) {
                return events.get(i);
            }
        }
        throw new IllegalStateException("No runtime event found for: " + needle);
    }

    private static boolean containsEvent(final List<String> events, final String partial) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).contains(partial)) {
                return true;
            }
        }
        return false;
    }
}
