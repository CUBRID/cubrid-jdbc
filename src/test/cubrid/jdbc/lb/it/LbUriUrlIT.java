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
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import cubrid.jdbc.lb.config.NodeRole;
import cubrid.jdbc.lb.config.ReadWeight;
import cubrid.jdbc.lb.config.ResolvedRoleTopology;
import cubrid.jdbc.lb.config.ResolvedRoleTopology.ResolvedNode;
import cubrid.jdbc.lb.config.ResolvedRoleTopology.ResolvedReplica;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Scenario test for the URI {@code jdbc:cubrid:loadbalance://} URL format.
 *
 * <p>Target URL under test:
 *
 * <pre>{@code
 * jdbc:cubrid:loadbalance://node1,node2,node3;replica=rep1/testdb:dba:secret:
 *     ?rwPort=33000&roPort=33002&soPort=33004&readWeight=slave:40,master:10,replica:50
 * }</pre>
 *
 * <p>This yields the topology:
 *
 * <ul>
 *   <li>{@code node1} &rarr; MASTER (RW 33000, RO 33002)
 *   <li>{@code node2}, {@code node3} &rarr; SLAVE (RW 33000, RO 33002)
 *   <li>{@code rep1} &rarr; REPLICA (SO 33004)
 *   <li>read weights: slave=40, master=10, replica=50
 * </ul>
 *
 * <p>The test has two layers:
 *
 * <ol>
 *   <li><b>URL parsing</b> ({@link #urlParsesIntoExpectedTopologyAndWeights()}) &mdash; runs
 *       everywhere, needs no live broker. Confirms the new URL string is accepted and mapped to the
 *       expected topology / ports / weights / credentials.
 *   <li><b>Live connection</b> &mdash; gated by {@code -Dlb.it.enabled=true}. Opens a real {@link
 *       Connection} through {@link java.sql.DriverManager} and exercises basic read/write routing.
 *       Point it at real brokers with {@code -Dlb.it.jdbc.url=<your url>} (host names in the sample
 *       do not resolve) and provide credentials via the {@code user}/{@code password} URL fields or
 *       {@code -Dlb.it.jdbc.user}/{@code -Dlb.it.jdbc.password}.
 * </ol>
 */
public final class LbUriUrlIT {

    /**
     * The exact URL shape this test verifies; used verbatim for parsing, overridable for connect.
     */
    private static final String SAMPLE_URL =
            "jdbc:cubrid:loadbalance://node1,node2,node3;replica=rep1/testdb:dba:secret:"
                    + "?rwPort=33000&roPort=33002&soPort=33004"
                    + "&readWeight=slave:40,master:10,replica:50";

    private static final String DEFAULT_TABLE = "lb_uri_url_it";

    private static String jdbcUrl;
    private static String tableName;
    private static String jdbcUser;
    private static String jdbcPassword;

    @BeforeClass
    public static void setUpClass() throws Exception {
        Class.forName("cubrid.jdbc.driver.CUBRIDDriver");
        jdbcUrl = resolveJdbcUrl();
        String userRaw = System.getProperty("lb.it.jdbc.user");
        jdbcUser = isEffectivePropertyValue(userRaw) ? userRaw.trim() : "";
        String passwordRaw = System.getProperty("lb.it.jdbc.password");
        jdbcPassword = isEffectivePropertyValue(passwordRaw) ? passwordRaw.trim() : "";
        String tableRaw = System.getProperty("lb.it.table");
        tableName = isEffectivePropertyValue(tableRaw) ? tableRaw.trim() : DEFAULT_TABLE;
    }

    @Test
    public void urlParsesIntoExpectedTopologyAndWeights() throws Exception {
        LoadBalanceSettings config =
                LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(SAMPLE_URL));

        // credentials + database name from the "/testdb:dba:secret:" segment
        assertEquals("testdb", config.getDatabaseName());
        assertEquals("dba", config.getUrlUser());
        assertEquals("secret", config.getUrlPassword());

        ResolvedRoleTopology topology = config.getResolvedTopology();

        // node1 -> MASTER, with rwPort/roPort applied from the global options
        ResolvedNode master = topology.getMaster();
        assertEquals("node1", master.getHost());
        assertEquals(33000, master.getRw().getPort());
        assertEquals(33002, master.getRo().getPort());

        // node2, node3 -> SLAVE
        List<ResolvedNode> slaves = topology.getSlaves();
        assertEquals(2, slaves.size());
        assertEquals("node2", slaves.get(0).getHost());
        assertEquals("node3", slaves.get(1).getHost());
        assertEquals(33000, slaves.get(0).getRw().getPort());
        assertEquals(33002, slaves.get(0).getRo().getPort());

        // rep1 -> REPLICA, with soPort applied
        List<ResolvedReplica> replicas = topology.getReplicas();
        assertEquals(1, replicas.size());
        assertEquals("rep1", replicas.get(0).getHost());
        assertEquals(33004, replicas.get(0).getSo().getPort());

        // readWeight=slave:40,master:10,replica:50
        ReadWeight weight = config.getReadWeight();
        assertEquals(10, weight.weightOf(NodeRole.MASTER));
        assertEquals(40, weight.weightOf(NodeRole.SLAVE));
        assertEquals(50, weight.weightOf(NodeRole.REPLICA));
        assertEquals(100, weight.total());
        assertTrue(weight.hasReadTarget());
    }

    @Test
    public void connectsAndReturnsLoadBalanceConnection() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("lb.it.enabled"));
        LoadBalanceConnection connection = openLbConnection();
        try {
            assertTrue(connection.isValid(5));
            // The parsed config must be reachable from the live connection. The expected database
            // name comes from the URL this run was given, not from a literal: the operator supplies
            // -Dlb.it.jdbc.url, so a hard-coded "testdb" only ever passed on one particular cluster
            // and failed everywhere else on a name mismatch that says nothing about the driver.
            // Parser correctness itself is covered offline against a fixed URL constant.
            String expectedDatabase =
                    LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(jdbcUrl))
                            .getDatabaseName();
            assertTrue(expectedDatabase != null && expectedDatabase.length() > 0);
            assertEquals(expectedDatabase, connection.getLbConfig().getDatabaseName());
        } finally {
            connection.close();
        }
    }

    @Test
    public void nonTransactionSelectRoutesToReadOnly() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("lb.it.enabled"));
        LoadBalanceConnection connection = openLbConnection();
        try {
            prepareFixtureTable(connection);
            executeSelectById(connection, "SELECT id FROM " + tableName + " WHERE id = ?", 1);
            String event = lastSqlEvent(connection);
            assertTrue(event.contains("sqlType=READ"));
            assertTrue(event.contains("routeTarget=TO_READ_ONLY"));
        } finally {
            connection.close();
        }
    }

    @Test
    public void updateAlwaysRoutesToReadWrite() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("lb.it.enabled"));
        LoadBalanceConnection connection = openLbConnection();
        try {
            prepareFixtureTable(connection);
            PreparedStatement statement =
                    connection.prepareStatement("UPDATE " + tableName + " SET v = ? WHERE id = ?");
            try {
                statement.setInt(1, 42);
                statement.setInt(2, 1);
                statement.executeUpdate();
            } finally {
                statement.close();
            }
            String event = lastSqlEvent(connection);
            assertTrue(event.contains("sqlType=WRITE"));
            assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        } finally {
            connection.close();
        }
    }

    private static LoadBalanceConnection openLbConnection() throws Exception {
        Properties info = new Properties();
        if (jdbcUser.length() > 0) {
            info.setProperty("user", jdbcUser);
        }
        if (jdbcPassword.length() > 0) {
            info.setProperty("password", jdbcPassword);
        }

        Connection connection = java.sql.DriverManager.getConnection(jdbcUrl, info);
        if (!(connection instanceof LoadBalanceConnection)) {
            connection.close();
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
        // Same ordered handover the other scenario ITs do: the drop has to reach every read
        // endpoint before the create, or a read lands on a rung that still has the old table.
        ItFixtures.awaitFixtureGoneFromReadEndpoints(jdbcUrl, jdbcUser, jdbcPassword, tableName);

        statement = connection.createStatement();
        try {
            statement.executeUpdate("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, v INT)");
        } finally {
            statement.close();
        }

        PreparedStatement insert =
                connection.prepareStatement("INSERT INTO " + tableName + " (id, v) VALUES (?, ?)");
        try {
            insert.setInt(1, 1);
            insert.setInt(2, 10);
            insert.executeUpdate();
        } finally {
            insert.close();
        }

        ItFixtures.awaitFixtureOnReadEndpoints(jdbcUrl, jdbcUser, jdbcPassword, tableName);
    }

    private static void executeSelectById(
            final Connection connection, final String sql, final int id) throws Exception {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setInt(1, id);
            statement.executeQuery().close();
        } finally {
            statement.close();
        }
    }

    private static String lastSqlEvent(final LoadBalanceConnection connection) {
        List<String> events = connection.getRuntimeMetrics().getEvents();
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

    private static String resolveJdbcUrl() {
        String directUrl = System.getProperty("lb.it.jdbc.url");
        return isEffectivePropertyValue(directUrl) ? directUrl.trim() : SAMPLE_URL;
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
}
