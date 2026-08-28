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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.state.RuntimeMetrics;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/** Live metadata scenario test with one RW and two RO brokers. */
public final class LbDatabaseMetaDataIT {

    private static final String DEFAULT_BASE_URL = "jdbc:cubrid:localhost:30000:tdb:public::";
    private static final String DEFAULT_USER = "dba";
    private static final String DEFAULT_TABLE = "lb_metadata_it";

    private static String lbJdbcUrl;
    private static String jdbcUser;
    private static String jdbcPassword;
    private static String tableName;
    private static String rwEndpoint;
    private static List<String> roEndpoints;
    private static String databaseName;

    /** Bound on the per-connection catalog wait; the DDL lands in well under a second here. */
    private static final long CATALOG_WAIT_MS = 10000L;

    private static final long CATALOG_POLL_MS = 100L;

    @BeforeClass
    public static void setUpClass() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("lb.it.enabled"));
        Class.forName("cubrid.jdbc.driver.CUBRIDDriver");

        lbJdbcUrl = resolveLbJdbcUrl();
        jdbcUser = System.getProperty("lb.it.jdbc.user", DEFAULT_USER);
        jdbcPassword = System.getProperty("lb.it.jdbc.password", "");
        String tableRaw = System.getProperty("lb.it.table");
        tableName = isEffectivePropertyValue(tableRaw) ? tableRaw.trim() : DEFAULT_TABLE;

        resolveEndpointsFromTopology();
        Assume.assumeTrue(rwEndpoint != null && rwEndpoint.length() > 0);
        Assume.assumeTrue(roEndpoints.size() >= 2);
    }

    /**
     * Reads the broker layout off a live LB session.
     *
     * <p>This used to parse {@code cubrid_lb.conf}; that file-based configuration model was removed
     * in P2, so the endpoints now come from the same topology the driver itself resolved out of the
     * JDBC URL.
     */
    private static void resolveEndpointsFromTopology() throws Exception {
        LoadBalanceConnection probe = openLbConnection();
        try {
            databaseName = probe.getLbConfig().getDatabaseName();
            EndpointTopology topology = probe.getSessionTopology();
            rwEndpoint = topology.hasRwEndpoint() ? topology.getRwEndpoints().get(0).getId() : null;
            roEndpoints = new ArrayList<String>();
            for (Endpoint endpoint : topology.getRoEndpoints()) {
                roEndpoints.add(endpoint.getId());
            }
            for (Endpoint endpoint : topology.getReplEndpoints()) {
                roEndpoints.add(endpoint.getId());
            }
        } finally {
            probe.close();
        }
    }

    @Test
    public void metaDataRoutesToMasterAndReturnsProductAndDriverInfo() throws Exception {
        LoadBalanceConnection connection = openLbConnection();
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(metadata.getDatabaseProductName().length() > 0);
            assertTrue(metadata.getDatabaseProductVersion().length() > 0);
            assertTrue(metadata.getDriverVersion().length() > 0);
            assertTrue(metadata.getDatabaseMajorVersion() >= 0);
            assertTrue(metadata.getDatabaseMinorVersion() >= 0);
            assertTrue(metadata.getDriverMajorVersion() >= 0);
            assertTrue(metadata.getDefaultTransactionIsolation() >= 0);
            assertTrue(metadata.getDriverMinorVersion() >= 0);

            // Not a driver-version accessor: those two answer from this driver's own build
            // constant and never touch an endpoint, so they no longer represent the command path.
            // They declare no throws clause, so they could not report a bind failure anyway.
            assertTrue(metadata.getUserName().length() > 0);

            String event =
                    findLastEventContaining(connection.getRuntimeMetrics(), "sqlType=COMMAND");
            assertTrue(event.contains("command=Call: getUserName"));
            assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        } finally {
            connection.close();
        }
    }

    @Test
    public void tableColumnsMatchAcrossTheLbRwAndRoConnections() throws Exception {
        String rwJdbcUrl = buildDirectJdbcUrl(rwEndpoint);
        String ro1JdbcUrl = buildDirectJdbcUrl(roEndpoints.get(0));
        String ro2JdbcUrl = buildDirectJdbcUrl(roEndpoints.get(1));

        Connection rw = openDirectConnection(rwJdbcUrl);
        try {
            prepareFixtureTable(rw);
        } finally {
            rw.close();
        }

        LoadBalanceConnection lb = openLbConnection();
        Connection rwVerify = openDirectConnection(rwJdbcUrl);
        Connection ro1 = openDirectConnection(ro1JdbcUrl);
        Connection ro2 = openDirectConnection(ro2JdbcUrl);
        try {
            awaitTable(lb, tableName);
            awaitTable(rwVerify, tableName);
            awaitTable(ro1, tableName);
            awaitTable(ro2, tableName);

            assertEquals(3, countColumns(lb.getMetaData(), tableName));
            assertEquals(3, countColumns(rwVerify.getMetaData(), tableName));
            assertEquals(3, countColumns(ro1.getMetaData(), tableName));
            assertEquals(3, countColumns(ro2.getMetaData(), tableName));
        } finally {
            ro2.close();
            ro1.close();
            rwVerify.close();
            lb.close();
        }
    }

    private static LoadBalanceConnection openLbConnection() throws Exception {
        Connection connection = openDirectConnection(lbJdbcUrl);
        if (!(connection instanceof LoadBalanceConnection)) {
            throw new IllegalStateException(
                    "Expected LoadBalanceConnection but got " + connection.getClass().getName());
        }
        LoadBalanceConnection lbConnection = (LoadBalanceConnection) connection;
        lbConnection.getRuntimeMetrics().setEnabled(true);
        return lbConnection;
    }

    private static Connection openDirectConnection(final String jdbcUrl) throws Exception {
        Properties info = new Properties();
        info.setProperty("user", jdbcUser);
        info.setProperty("password", jdbcPassword);
        return DriverManager.getConnection(jdbcUrl, info);
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
        ItFixtures.awaitFixtureGoneFromReadEndpoints(lbJdbcUrl, jdbcUser, jdbcPassword, tableName);

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
            insert.setInt(2, 100);
            insert.setString(3, "lb_metadata_doc");
            insert.executeUpdate();
        } finally {
            insert.close();
        }

        ItFixtures.awaitFixtureOnReadEndpoints(lbJdbcUrl, jdbcUser, jdbcPassword, tableName);
    }

    /**
     * Waits for {@code targetTable} to appear in this connection's catalog.
     *
     * <p>The fixture wait that ran before this ({@code ItFixtures.awaitFixtureOnReadEndpoints})
     * confirms a row is <b>selectable through the load balancer</b>. This asserts something else:
     * that the table is listed in {@code getTables()} on <b>this particular direct connection</b>.
     * The gap between the two is small — measured at a few milliseconds on this cluster — but it is
     * real, and it made the assertion fail intermittently on whichever endpoint happened to be last
     * to apply the DDL. Polling for exactly what is asserted, on the connection it is asserted on,
     * removes the race whatever its size; a lasting failure still fails, just after the deadline.
     */
    private static void awaitTable(final Connection connection, final String targetTable)
            throws Exception {
        final long deadline = System.currentTimeMillis() + CATALOG_WAIT_MS;
        do {
            if (containsTable(connection.getMetaData(), targetTable)) {
                return;
            }
            Thread.sleep(CATALOG_POLL_MS);
        } while (System.currentTimeMillis() < deadline);

        assertTrue(
                targetTable + " never appeared in the catalog of " + connection,
                containsTable(connection.getMetaData(), targetTable));
    }

    private static boolean containsTable(final DatabaseMetaData metadata, final String targetTable)
            throws Exception {
        ResultSet tables = metadata.getTables(null, null, "%", new String[] {"TABLE"});
        try {
            while (tables.next()) {
                String table = tables.getString("TABLE_NAME");
                if (targetTable.equalsIgnoreCase(table)) {
                    return true;
                }
            }
        } finally {
            tables.close();
        }
        return false;
    }

    private static int countColumns(final DatabaseMetaData metadata, final String targetTable)
            throws Exception {
        ResultSet columns = metadata.getColumns(null, null, targetTable, null);
        int count = 0;
        try {
            while (columns.next()) {
                count++;
            }
        } finally {
            columns.close();
        }
        return count;
    }

    private static String resolveLbJdbcUrl() {
        String directUrl = System.getProperty("lb.it.jdbc.url");
        if (isEffectivePropertyValue(directUrl)) {
            return directUrl.trim();
        }

        String baseUrlRaw = System.getProperty("lb.it.jdbc.baseUrl");
        return isEffectivePropertyValue(baseUrlRaw) ? baseUrlRaw.trim() : DEFAULT_BASE_URL;
    }

    /**
     * Builds a plain single-broker URL for {@code host:port}.
     *
     * <p>The database name comes from the load balancer's own parsed settings rather than from
     * string surgery on the LB URL: the {@code loadbalance://} form carries a node list where a
     * legacy URL carries one {@code host:port}, so slicing it produced nonsense like {@code
     * jdbc:cubrid:192.168.2.197:30000:30000:33000,...}. Credentials travel in the {@link
     * java.util.Properties}, so the URL needs none.
     */
    private static String buildDirectJdbcUrl(final String endpoint) {
        int firstColon = endpoint.indexOf(':');
        if (firstColon <= 0 || firstColon == endpoint.length() - 1) {
            throw new IllegalArgumentException("Invalid endpoint: " + endpoint);
        }
        String host = endpoint.substring(0, firstColon).trim();
        String port = endpoint.substring(firstColon + 1).trim();

        return "jdbc:cubrid:" + host + ":" + port + ":" + databaseName + ":::";
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
}
