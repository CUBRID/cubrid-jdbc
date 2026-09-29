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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.state.SharedSelectorStateRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end regression for the URI {@code loadbalance://} model, tying together URL parsing → role
 * topology → readWeight selection → physical session binding → routing ({@link
 * LoadBalanceConnection#openFromSettings}). Distribution ratios per the worked examples in the
 * load-balance and URL specs.
 */
public class LoadBalanceUriIntegrationTest {

    private final JdbcConnectionFactory factory =
            new JdbcConnectionFactory() {
                public Connection getConnection(final String url, final Properties info) {
                    return connectionProxy();
                }
            };

    @Before
    public void setUp() {
        SharedSelectorStateRegistry.clearForTests();
    }

    private LoadBalanceConnection openLb(final String url) throws SQLException {
        LoadBalanceSettings config = LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));
        return (LoadBalanceConnection)
                LoadBalanceConnection.openFromSettings(url, new Properties(), config, factory);
    }

    private static int count(final Map<String, Integer> m, final String k) {
        Integer v = m.get(k);
        return v != null ? v.intValue() : 0;
    }

    /**
     * Worked example: master + slave{node2,node3} + replica{rep1}, default readWeight (1:1:1), a
     * pool of 9 logical connections → read bound to master(RW reuse) ×3, node2 RO ×2, node3 RO ×1,
     * rep1 SO ×3. The pool shares selector state because every connection uses the same URL.
     */
    @Test
    public void poolOf9MatchesSpecWorkedExample() throws SQLException {
        String url =
                "jdbc:cubrid:loadbalance://node1,node2,node3;replica=rep1/db"
                        + "?rwPort=33000&roPort=33002&soPort=33004";

        Map<String, Integer> readEps = new HashMap<String, Integer>();
        for (int i = 0; i < 9; i++) {
            LoadBalanceConnection conn = openLb(url);
            // Write is always fixed to the master RW.
            assertEquals("node1:33000", conn.getCurrentRwEndpoint().getId());
            String roId = conn.getCurrentRoEndpoint().getId();
            readEps.put(roId, Integer.valueOf(count(readEps, roId) + 1));
        }

        assertEquals(3, count(readEps, "node1:33000")); // master read = RW reuse (roOnRw)
        assertEquals(2, count(readEps, "node2:33002")); // slave RO
        assertEquals(1, count(readEps, "node3:33002")); // slave RO
        assertEquals(3, count(readEps, "rep1:33004")); // replica SO
    }

    /**
     * The declared ratio must survive the pool replacing its read-leg connections while the
     * master-home ones stay — end-to-end over real {@link LoadBalanceConnection} open/close, so it
     * covers the wiring (selection takes a home slot, {@code close()} gives it back) and not just
     * the selector.
     *
     * <p>Reproduces a live-cluster failure: with selection balancing only the stream of picks, a
     * master-home session (whose read rides on the RW connection, so a read broker outage never
     * costs it its place) accumulates every round, and the pool's read distribution drifts toward
     * the master permanently — measured 3/30 → 12/30 master over two broker cycles.
     */
    @Test
    public void biasedPoolChurnKeepsTheReadWeightRatio() throws SQLException {
        String url =
                "jdbc:cubrid:loadbalance://node1,node2;replica=rep1/db"
                        + "?rwPort=33000&roPort=33002&soPort=33004"
                        + "&readWeight=slave:40,master:10,replica:50";

        Map<String, LoadBalanceConnection> pool = new HashMap<String, LoadBalanceConnection>();
        for (int i = 0; i < 30; i++) {
            LoadBalanceConnection conn = openLb(url);
            pool.put("c" + i, conn);
        }
        assertReadDistribution(pool, "warm pool");

        for (int round = 1; round <= 5; round++) {
            // A read-broker outage discards the sessions reading on a read broker; the master-home
            // ones read on the RW connection and survive.
            int replaced = 0;
            for (String key : new java.util.ArrayList<String>(pool.keySet())) {
                if (!"node1:33000".equals(pool.get(key).getCurrentRoEndpoint().getId())) {
                    pool.remove(key).close();
                    replaced++;
                }
            }
            for (int i = 0; i < replaced; i++) {
                pool.put("r" + round + "-" + i, openLb(url));
            }
            assertReadDistribution(pool, "after churn round " + round);
        }

        for (LoadBalanceConnection conn : pool.values()) {
            conn.close();
        }
    }

    private static void assertReadDistribution(
            final Map<String, LoadBalanceConnection> pool, final String when) throws SQLException {
        Map<String, Integer> eps = new HashMap<String, Integer>();
        for (LoadBalanceConnection conn : pool.values()) {
            String id = conn.getCurrentRoEndpoint().getId();
            eps.put(id, Integer.valueOf(count(eps, id) + 1));
        }

        assertEquals(when + ": pool size", 30, pool.size());
        assertEquals(when + ": slave RO", 12, count(eps, "node2:33002"));
        assertEquals(when + ": master RW reuse", 3, count(eps, "node1:33000"));
        assertEquals(when + ": replica SO", 15, count(eps, "rep1:33004"));
    }

    @Test
    public void writeRoutesToMasterReadRoutesToBoundEndpoint() throws SQLException {
        LoadBalanceConnection conn =
                openLb(
                        "jdbc:cubrid:loadbalance://node1,node2/db"
                                + "?rwPort=33000&roPort=33002&readWeight=slave:1,master:0");

        assertEquals(
                "node1:33000", conn.endpointForTarget(Router.RouteTarget.TO_READ_WRITE).getId());
        assertEquals(
                "node2:33002", conn.endpointForTarget(Router.RouteTarget.TO_READ_ONLY).getId());
    }

    @Test
    public void masterZeroNeverBindsReadToMaster() throws SQLException {
        String url =
                "jdbc:cubrid:loadbalance://node1,node2;replica=rep1/db"
                        + "?rwPort=33000&roPort=33002&soPort=33004&readWeight=slave:50,master:0,replica:50";

        for (int i = 0; i < 10; i++) {
            LoadBalanceConnection conn = openLb(url);
            assertEquals("node1:33000", conn.getCurrentRwEndpoint().getId());
            String roId = conn.getCurrentRoEndpoint().getId();
            assertFalse("master must not serve reads when master:0", "node1:33000".equals(roId));
        }
    }

    /**
     * A read (TO_READ_ONLY) routes to the session's bound read (RO) slot. Even with a replica
     * configured, master:1 binds the read to the master RW, so the read resolves to it — no
     * separate replica connection is opened.
     */
    @Test
    public void readRoutesToBoundMasterReadWhenRoOnRw() throws SQLException {
        LoadBalanceConnection conn =
                openLb(
                        "jdbc:cubrid:loadbalance://node1,node2;replica=rep1/db"
                                + "?rwPort=33000&roPort=33002&soPort=33004"
                                + "&readWeight=master:1,slave:0,replica:0");

        assertEquals("node1:33000", conn.getCurrentRoEndpoint().getId()); // bound read = master RW
        assertEquals(
                "node1:33000", conn.endpointForTarget(Router.RouteTarget.TO_READ_ONLY).getId());
    }

    @Test
    public void readRoutesToBoundSlaveRead() throws SQLException {
        LoadBalanceConnection conn =
                openLb(
                        "jdbc:cubrid:loadbalance://node1,node2/db"
                                + "?rwPort=33000&roPort=33002&readWeight=slave:1,master:0");

        assertEquals("node2:33002", conn.getCurrentRoEndpoint().getId());
        assertEquals(
                "node2:33002", conn.endpointForTarget(Router.RouteTarget.TO_READ_ONLY).getId());
    }

    /** O-3: a read (TO_READ_ONLY) target inside an active transaction resolves to the master RW. */
    @Test
    public void readInsideTransactionResolvesToRw() throws SQLException {
        LoadBalanceConnection conn =
                openLb(
                        "jdbc:cubrid:loadbalance://node1,node2;replica=rep1/db"
                                + "?rwPort=33000&roPort=33002&soPort=33004");
        conn.setAutoCommit(false); // transaction active -> non-master target invalid

        assertEquals(
                "node1:33000", conn.endpointForTarget(Router.RouteTarget.TO_READ_ONLY).getId());
    }

    /**
     * 01 §M-8: if a post-bind step in initSessionBindings fails after physical connections are
     * opened, openFromSettings must close the half-built connection so the opened brokers are not
     * leaked (the caller never receives the connection, so it can never close it). Here both RW and
     * RO opens succeed (applier isolation calls #1,#2) and the post-bind applyTxnIsolation (#3)
     * fails.
     */
    @Test
    public void openFromSettingsClosesPhysicalConnectionsWhenPostBindApplyFails()
            throws SQLException {
        final AtomicInteger isolationCalls = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        JdbcConnectionFactory failingFactory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        return (Connection)
                                Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class[] {Connection.class},
                                        new InvocationHandler() {
                                            public Object invoke(
                                                    final Object proxy,
                                                    final Method method,
                                                    final Object[] args)
                                                    throws Throwable {
                                                String name = method.getName();
                                                if ("setTransactionIsolation".equals(name)) {
                                                    // #1,#2 = per-conn applier at open (succeed);
                                                    // #3 = post-bind applyTxnIsolation (fail).
                                                    if (isolationCalls.incrementAndGet() > 2) {
                                                        throw new SQLException(
                                                                "post-bind isolation apply failed");
                                                    }
                                                    return null;
                                                }
                                                if ("close".equals(name)) {
                                                    closes.incrementAndGet();
                                                    return null;
                                                }
                                                if ("isClosed".equals(name)) {
                                                    return Boolean.FALSE;
                                                }
                                                Class<?> rt = method.getReturnType();
                                                if (rt.equals(Boolean.TYPE)) {
                                                    return Boolean.FALSE;
                                                }
                                                if (rt.equals(Integer.TYPE)) {
                                                    return Integer.valueOf(0);
                                                }
                                                return null;
                                            }
                                        });
                    }
                };

        // slave:1,master:0 forces the RO binding onto node2 (distinct from the node1 RW), so two
        // physical connections are opened before the post-bind failure.
        String url =
                "jdbc:cubrid:loadbalance://node1,node2/testdb"
                        + "?rwPort=33000&roPort=33002&readWeight=slave:1,master:0";
        LoadBalanceSettings config = LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));

        try {
            LoadBalanceConnection.openFromSettings(url, new Properties(), config, failingFactory);
            fail("expected the post-bind apply failure to propagate");
        } catch (SQLException expected) {
        }

        assertTrue(
                "physical connections opened during bind must be closed on init failure",
                closes.get() >= 1);
    }

    private static Connection connectionProxy() {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                String name = method.getName();
                                if ("isClosed".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                if ("close".equals(name) || "setAutoCommit".equals(name)) {
                                    return null;
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                if (rt.equals(Integer.TYPE)) {
                                    return Integer.valueOf(0);
                                }
                                return null;
                            }
                        });
    }
}
