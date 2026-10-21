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

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Shared setup helper for the live scenario ITs.
 *
 * <p>Every scenario IT builds its fixture table with {@code DROP TABLE} + {@code CREATE TABLE} on
 * the master and then reads it back through the load balancer, which spreads reads over the slave
 * and replica endpoints. HA replication is asynchronous, so a read that lands on a rung which has
 * not applied the DDL yet fails with {@code Unknown class "<table>"} — a race the test cannot retry
 * away, because the fixture is re-created on every run. This helper blocks until the table is
 * actually visible on every read endpoint of the topology, so the tests that follow are
 * deterministic.
 */
final class ItFixtures {

    /** Cap on the wait; override with {@code -Dlb.it.fixture.waitMs=...}. */
    private static final long DEFAULT_WAIT_MS = 20000L;

    private static final long POLL_INTERVAL_MS = 200L;

    /** Enough concurrent sessions for the weighted balancer to reach all three roles. */
    private static final int MIN_BATCH = 6;

    /** Bounded so a wide topology cannot open more sessions than the brokers have CAS for. */
    private static final int MAX_BATCH = 12;

    private ItFixtures() {}

    private static int batchSize(final int readEndpointCount) {
        int size = readEndpointCount * 3;
        if (size < MIN_BATCH) {
            size = MIN_BATCH;
        }
        return size > MAX_BATCH ? MAX_BATCH : size;
    }

    private static void closeQuietly(final List<LoadBalanceConnection> connections) {
        for (int i = 0; i < connections.size(); i++) {
            try {
                connections.get(i).close();
            } catch (Exception ignored) {
                // a probe session failing to close must not mask the wait result
            }
        }
    }

    /**
     * Blocks until {@code tableName} can be selected from on every read endpoint the load balancer
     * routes to.
     *
     * <p>Each round opens a BATCH of sessions and holds them all open before closing any. Read
     * homes are handed out to balance the live session population against {@code readWeight}, so a
     * lone session opened and closed in a loop is a degenerate sample: the balancer hands the
     * single free slot to the same most-underweight role every time and never probes the others.
     * Holding a batch is what makes it spread.
     *
     * <p>The endpoint credited is the one the runtime metrics report as having executed the
     * statement, so a fallback onto another leg cannot mark the intended rung verified.
     */
    static void awaitFixtureOnReadEndpoints(
            final String jdbcUrl, final String user, final String password, final String tableName)
            throws Exception {
        final long waitMs = Long.getLong("lb.it.fixture.waitMs", DEFAULT_WAIT_MS).longValue();
        final long deadline = System.currentTimeMillis() + waitMs;
        final String probe = "SELECT id FROM " + tableName + " WHERE id = ?";

        Set<String> expected = null;
        Set<String> verified = new HashSet<String>();
        Set<String> homes = new HashSet<String>();
        String lastFailure = null;

        while (true) {
            List<LoadBalanceConnection> batch = new ArrayList<LoadBalanceConnection>();
            try {
                int size = expected == null ? MIN_BATCH : batchSize(expected.size());
                for (int i = 0; i < size; i++) {
                    LoadBalanceConnection connection = open(jdbcUrl, user, password);
                    batch.add(connection);

                    if (expected == null) {
                        expected = readEndpointIds(connection.getSessionTopology());
                        if (expected.isEmpty()) {
                            return; // no separate read endpoint: the master already has the table
                        }
                        size = batchSize(expected.size());
                    }
                    if (connection.getCurrentRoEndpoint() != null) {
                        homes.add(connection.getCurrentRoEndpoint().getId());
                    }

                    try {
                        select(connection, probe);
                        String endpointId = lastEndpointId(connection);
                        if (endpointId != null) {
                            verified.add(endpointId);
                        }
                    } catch (Exception notReplicatedYet) {
                        lastFailure = String.valueOf(notReplicatedYet.getMessage());
                    }

                    if (verified.containsAll(expected)) {
                        break;
                    }
                }
            } finally {
                closeQuietly(batch);
            }

            if (expected != null && verified.containsAll(expected)) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException(
                        "fixture table "
                                + tableName
                                + " is not visible on every read endpoint after "
                                + waitMs
                                + "ms; verified="
                                + verified
                                + " expected="
                                + expected
                                + " homesBound="
                                + homes
                                + " lastFailure="
                                + lastFailure);
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
    }

    /**
     * Blocks until {@code tableName} is gone from every read endpoint.
     *
     * <p>Call this between the {@code DROP} and the {@code CREATE}, or the wait that follows the
     * CREATE proves nothing on a re-run: its probe is satisfied by the table the *previous* run
     * left behind, so it returns at once and the DROP then replicates into the middle of the tests,
     * which fail with {@code Unknown class}. Waiting for the drop first makes the whole fixture
     * handover ordered — gone everywhere, then present everywhere.
     *
     * <p>Any probe failure counts as gone. A transient error mistaken for a drop only means this
     * wait ends early; the wait after the CREATE still has to see the new table on every endpoint.
     *
     * @param jdbcUrl the load-balance URL the tests use
     * @param user the database user
     * @param password the database password
     * @param tableName the fixture table being replaced
     * @throws Exception if the table is still readable somewhere when the deadline passes
     */
    static void awaitFixtureGoneFromReadEndpoints(
            final String jdbcUrl, final String user, final String password, final String tableName)
            throws Exception {
        final long waitMs = Long.getLong("lb.it.fixture.waitMs", DEFAULT_WAIT_MS).longValue();
        final long deadline = System.currentTimeMillis() + waitMs;
        final String probe = "SELECT id FROM " + tableName + " WHERE id = ?";

        Set<String> expected = null;
        Set<String> gone = new HashSet<String>();
        Set<String> stillReadable = new HashSet<String>();

        while (true) {
            List<LoadBalanceConnection> batch = new ArrayList<LoadBalanceConnection>();
            stillReadable.clear();
            try {
                int size = expected == null ? MIN_BATCH : batchSize(expected.size());
                for (int i = 0; i < size; i++) {
                    LoadBalanceConnection connection = open(jdbcUrl, user, password);
                    batch.add(connection);

                    if (expected == null) {
                        expected = readEndpointIds(connection.getSessionTopology());
                        if (expected.isEmpty()) {
                            return; // no separate read endpoint: the master drop is the whole story
                        }
                        size = batchSize(expected.size());
                    }
                    // A failing probe records no routing event, so the read leg this session bound
                    // is what attributes the result.
                    if (connection.getCurrentRoEndpoint() == null) {
                        continue;
                    }
                    String endpointId = connection.getCurrentRoEndpoint().getId();
                    try {
                        select(connection, probe);
                        stillReadable.add(endpointId);
                    } catch (Exception alreadyDropped) {
                        gone.add(endpointId);
                    }

                    if (gone.containsAll(expected)) {
                        break;
                    }
                }
            } finally {
                closeQuietly(batch);
            }

            if (expected != null && gone.containsAll(expected)) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException(
                        "fixture table "
                                + tableName
                                + " is still readable after "
                                + waitMs
                                + "ms; gone="
                                + gone
                                + " stillReadable="
                                + stillReadable
                                + " expected="
                                + expected);
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
    }

    private static LoadBalanceConnection open(
            final String jdbcUrl, final String user, final String password) throws Exception {
        Properties info = new Properties();
        info.setProperty("user", user);
        info.setProperty("password", password);
        Connection connection = DriverManager.getConnection(jdbcUrl, info);
        if (!(connection instanceof LoadBalanceConnection)) {
            connection.close();
            throw new IllegalStateException(
                    "Expected LoadBalanceConnection but got " + connection.getClass().getName());
        }
        LoadBalanceConnection lbConnection = (LoadBalanceConnection) connection;
        lbConnection.getRuntimeMetrics().setEnabled(true);
        return lbConnection;
    }

    private static void select(final Connection connection, final String sql) throws Exception {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setInt(1, 1);
            statement.executeQuery().close();
        } finally {
            statement.close();
        }
    }

    private static Set<String> readEndpointIds(final EndpointTopology topology) {
        Set<String> ids = new HashSet<String>();
        if (topology == null) {
            return ids;
        }
        List<Endpoint> endpoints = new ArrayList<Endpoint>();
        endpoints.addAll(topology.getRoEndpoints());
        endpoints.addAll(topology.getReplEndpoints());
        for (int i = 0; i < endpoints.size(); i++) {
            ids.add(endpoints.get(i).getId());
        }
        return ids;
    }

    private static String lastEndpointId(final LoadBalanceConnection connection) {
        List<String> events = connection.getRuntimeMetrics().getEvents();
        for (int i = events.size() - 1; i >= 0; i--) {
            String event = events.get(i);
            if (!event.contains("sqlType=READ")) {
                continue;
            }
            int start = event.indexOf("endpointId=");
            if (start < 0) {
                return null;
            }
            start += "endpointId=".length();
            int end = event.indexOf(',', start);
            return end < 0 ? event.substring(start) : event.substring(start, end);
        }
        return null;
    }
}
