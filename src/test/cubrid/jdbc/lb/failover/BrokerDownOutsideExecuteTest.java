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

package cubrid.jdbc.lb.failover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

/**
 * A stopped broker reaches the LB layer through paths that are not an {@code execute}: describing a
 * prepared statement, taking the connection's {@code DatabaseMetaData} handle, reading a session
 * property, creating a LOB holder. Every one of those needs a physical connection, so every one of
 * them must go through {@link ExecuteFailoverHandler} — otherwise the application gets an exception
 * while a sibling endpoint is alive.
 *
 * <p>Each test stops one broker <b>after</b> the session bound (the physical connection opens fine
 * and only the later operation fails, which is what {@code cubrid broker off} looks like to an
 * already-bound session) and asserts what the caller sees.
 */
public final class BrokerDownOutsideExecuteTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";

    /**
     * The read broker is stopped, so the eager prepare in {@code prepareStatement} is deferred and
     * {@code getMetaData()} is the call that must prepare. It fails over to the sibling read
     * endpoint and answers, instead of throwing at the application.
     */
    @Test
    public void preparedStatementMetaDataFailsOverWhenTheBoundReadBrokerIsDown()
            throws SQLException {
        final List<String> prepared = new ArrayList<String>();
        LoadBalanceConnection conn =
                sessionOverTwoReadEndpoints(deadHostAwareFactory("ro1", prepared));

        PreparedStatement ps = conn.prepareStatement("SELECT 1");
        // The eager prepare hit the dead read broker and was deferred, not raised.
        assertEquals(Arrays.asList("ro1"), prepared);

        ResultSetMetaData meta = ps.getMetaData();

        assertNotNull(meta);
        // ro1 was dialed again by the metadata call, then the leg rebound to ro2 and re-prepared.
        assertEquals(Arrays.asList("ro1", "ro1", "ro2"), prepared);
        assertTrue(hasFailoverEvent(conn));
        ps.close();
        conn.close();
    }

    /** Same for the parameter metadata, which shares the physical prepare. */
    @Test
    public void parameterMetaDataFailsOverWhenTheBoundReadBrokerIsDown() throws SQLException {
        final List<String> prepared = new ArrayList<String>();
        LoadBalanceConnection conn =
                sessionOverTwoReadEndpoints(deadHostAwareFactory("ro1", prepared));

        PreparedStatement ps = conn.prepareStatement("SELECT 1");

        assertNotNull(ps.getParameterMetaData());
        assertTrue(prepared.contains("ro2"));
        assertTrue(hasFailoverEvent(conn));
        ps.close();
        conn.close();
    }

    /**
     * A failure that is not a broker failure must still reach the application untouched: the
     * metadata path may not turn a statement error into a failover.
     */
    @Test
    public void metaDataPropagatesAStatementErrorWithoutFailover() throws SQLException {
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        return connectionProxy(
                                new PrepareBehavior() {
                                    public PreparedStatement prepare() throws SQLException {
                                        throw new SQLException("syntax error", "42000", -493);
                                    }
                                });
                    }
                };

        LoadBalanceConnection conn = sessionOverTwoReadEndpoints(factory);

        try {
            conn.prepareStatement("SELECT 1");
            fail("Expected the statement error to propagate from the eager prepare");
        } catch (SQLException expected) {
            assertEquals(-493, expected.getErrorCode());
        }

        assertTrue(!hasFailoverEvent(conn));
        conn.close();
    }

    /**
     * The write broker is stopped. Taking the {@code DatabaseMetaData} handle rebinds the write leg
     * to the surviving write broker; the handler never replays a write target, so the original
     * failure is still reported and the <b>next</b> call succeeds on the new leg.
     */
    @Test
    public void databaseMetaDataRebindsTheWriteLegWhenTheWriteBrokerIsDown() throws SQLException {
        final List<String> asked = new ArrayList<String>();
        LoadBalanceConnection conn = sessionOverTwoWriteEndpoints(metaDataFactory("rw1", asked));

        try {
            conn.getMetaData().getDatabaseProductName();
            fail("Expected the original write-leg failure to be reported");
        } catch (SQLException expected) {
            assertEquals(UErrorCode.ER_COMMUNICATION, expected.getErrorCode());
        }

        assertTrue(hasFailoverEvent(conn));
        // The leg is rebound, so the retry the caller makes is served by rw2.
        assertNotNull(conn.getMetaData().getDatabaseProductName());
        assertTrue(asked.contains("rw2"));
        conn.close();
    }

    /**
     * A catalog query — the part of {@code DatabaseMetaData} that actually sends a request.
     * Wrapping only the handle acquisition left this uncovered (LB-Pending-Issues ISSUE-5); now the
     * query itself rebinds the write leg, and the cached handle is dropped so the retry re-resolves
     * on the surviving broker.
     */
    @Test
    public void catalogQueryRebindsTheWriteLegWhenTheWriteBrokerIsDown() throws SQLException {
        final List<String> asked = new ArrayList<String>();
        LoadBalanceConnection conn =
                sessionOverTwoWriteEndpoints(catalogQueryFactory("rw1", asked));

        try {
            conn.getMetaData().getTables(null, null, "t", null);
            fail("Expected the original write-leg failure to be reported");
        } catch (SQLException expected) {
            assertEquals(UErrorCode.ER_COMMUNICATION, expected.getErrorCode());
        }

        assertTrue(hasFailoverEvent(conn));
        assertEquals(Arrays.asList("rw1"), asked);

        conn.getMetaData().getTables(null, null, "t", null);

        assertEquals(Arrays.asList("rw1", "rw2"), asked);
        conn.close();
    }

    /** A session-property read is a physical command too, so it rebinds the write leg. */
    @Test
    public void isReadOnlyRebindsTheWriteLegWhenTheWriteBrokerIsDown() throws SQLException {
        final List<String> asked = new ArrayList<String>();
        LoadBalanceConnection conn = sessionOverTwoWriteEndpoints(isReadOnlyFactory("rw1", asked));

        try {
            conn.isReadOnly();
            fail("Expected the original write-leg failure to be reported");
        } catch (SQLException expected) {
            assertEquals(UErrorCode.ER_COMMUNICATION, expected.getErrorCode());
        }

        assertTrue(hasFailoverEvent(conn));
        conn.isReadOnly();
        assertTrue(asked.contains("rw2"));
        conn.close();
    }

    /**
     * A session property must not be the one thing that makes a logical connection unusable while a
     * surviving write broker is right there — that is what a pool's reset of a returned connection
     * does (LB-Pending-Issues ISSUE-6). The write leg is rebound and the property re-applied, so
     * the setter <b>succeeds</b>; unlike the other command paths there is no exception left for the
     * caller, because setting a property is idempotent.
     */
    @Test
    public void aSessionPropertySetterRebindsAndSucceedsWhenTheWriteBrokerIsDown()
            throws SQLException {
        final List<String> asked = new ArrayList<String>();
        LoadBalanceConnection conn =
                sessionOverTwoWriteEndpoints(commandFactory("rw1", asked, "setReadOnly"));

        conn.setReadOnly(true);

        // rw1 failed, the leg rebound to rw2 and took the property; the read leg is set too, as it
        // always is for a session property.
        assertEquals(Arrays.asList("rw1", "rw2"), asked.subList(0, 2));
        assertTrue("the read leg gets the property as well", asked.contains("ro1"));
        assertTrue(hasFailoverEvent(conn));
        // The setter returning normally is the contract: the callers revert the logical value only
        // when the write leg failed (LegOutcome.rwFailed), so a silent return means it was kept.
        conn.close();
    }

    /** A failure that is not a broker failure must still reach the caller, with nothing rebound. */
    @Test
    public void aSessionPropertySetterPropagatesANonBrokerFailure() throws SQLException {
        final List<String> asked = new ArrayList<String>();
        LoadBalanceConnection conn =
                sessionOverTwoWriteEndpoints(rejectingFactory("rw1", asked, "setReadOnly"));

        try {
            conn.setReadOnly(true);
            fail("Expected the rejection to propagate");
        } catch (SQLException expected) {
            assertEquals(-493, expected.getErrorCode());
        }

        assertEquals("rw1", asked.get(0));
        assertTrue("no rebind: rw2 was never dialed", !asked.contains("rw2"));
        assertTrue(!hasFailoverEvent(conn));
        conn.close();
    }

    /** {@code createNClob} is protected like its {@code createClob} sibling. */
    @Test
    public void createNClobRebindsTheWriteLegWhenTheWriteBrokerIsDown() throws SQLException {
        final List<String> asked = new ArrayList<String>();
        LoadBalanceConnection conn = sessionOverTwoWriteEndpoints(createNClobFactory("rw1", asked));

        try {
            conn.createNClob();
            fail("Expected the original write-leg failure to be reported");
        } catch (SQLException expected) {
            assertEquals(UErrorCode.ER_COMMUNICATION, expected.getErrorCode());
        }

        assertTrue(hasFailoverEvent(conn));
        conn.close();
    }

    /* ===== harness ===== */

    private static LoadBalanceConnection sessionOverTwoReadEndpoints(
            final JdbcConnectionFactory factory) throws SQLException {
        return session(
                factory,
                new EndpointTopology(
                        new Endpoint("rw", 33000),
                        Arrays.asList(new Endpoint("ro1", 33001), new Endpoint("ro2", 33001)),
                        Collections.<Endpoint>emptyList()));
    }

    private static LoadBalanceConnection sessionOverTwoWriteEndpoints(
            final JdbcConnectionFactory factory) throws SQLException {
        return session(
                factory,
                new EndpointTopology(
                        Arrays.asList(new Endpoint("rw1", 33000), new Endpoint("rw2", 33000)),
                        Arrays.asList(new Endpoint("ro1", 33001)),
                        Collections.<Endpoint>emptyList()));
    }

    private static LoadBalanceConnection session(
            final JdbcConnectionFactory factory, final EndpointTopology topology)
            throws SQLException {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings settings = LoadBalanceSettings.of(cfg);

        LoadBalanceConnection conn = new LoadBalanceConnection(settings);
        conn.getRuntimeMetrics().setEnabled(true);
        conn.setConnectionManager(
                new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), settings, factory));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topology);

        return conn;
    }

    private static boolean hasFailoverEvent(final LoadBalanceConnection conn) {
        for (String event : conn.getRuntimeMetrics().getEvents()) {
            if (event.contains("event=RUNTIME_FAILOVER")) {
                return true;
            }
        }

        return false;
    }

    /** Which host a physical connection belongs to, taken from the URL the factory was given. */
    private static String hostOf(final String url) {
        int start = url.indexOf("jdbc:cubrid:") + "jdbc:cubrid:".length();
        int end = url.indexOf(':', start);

        return end < 0 ? url.substring(start) : url.substring(start, end);
    }

    /** Prepare fails on {@code deadHost} and succeeds elsewhere; every prepare is recorded. */
    private static JdbcConnectionFactory deadHostAwareFactory(
            final String deadHost, final List<String> prepared) {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info) {
                final String host = hostOf(url);

                return connectionProxy(
                        new PrepareBehavior() {
                            public PreparedStatement prepare() throws SQLException {
                                prepared.add(host);
                                if (deadHost.equals(host)) {
                                    throw new SQLException(
                                            "comm fail", null, UErrorCode.ER_COMMUNICATION);
                                }

                                return preparedStatementProxy();
                            }
                        });
            }
        };
    }

    private static JdbcConnectionFactory metaDataFactory(
            final String deadHost, final List<String> asked) {
        return commandFactory(deadHost, asked, "getMetaData");
    }

    private static JdbcConnectionFactory isReadOnlyFactory(
            final String deadHost, final List<String> asked) {
        return commandFactory(deadHost, asked, "isReadOnly");
    }

    private static JdbcConnectionFactory createNClobFactory(
            final String deadHost, final List<String> asked) {
        return commandFactory(deadHost, asked, "createNClob");
    }

    /** One physical method is rejected on {@code deadHost} with a non-broker error. */
    private static JdbcConnectionFactory rejectingFactory(
            final String deadHost, final List<String> asked, final String failingMethod) {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info) {
                final String host = hostOf(url);

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
                                        if (failingMethod.equals(method.getName())) {
                                            asked.add(host);
                                            if (deadHost.equals(host)) {
                                                throw new SQLException("rejected", "42000", -493);
                                            }
                                        }

                                        return defaultAnswer(method);
                                    }
                                });
            }
        };
    }

    /** {@code DatabaseMetaData.getTables} fails on {@code deadHost} and works elsewhere. */
    private static JdbcConnectionFactory catalogQueryFactory(
            final String deadHost, final List<String> asked) {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info) {
                final String host = hostOf(url);

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
                                        if ("getMetaData".equals(method.getName())) {
                                            return catalogProxy(host, deadHost, asked);
                                        }

                                        return defaultAnswer(method);
                                    }
                                });
            }
        };
    }

    private static Object catalogProxy(
            final String host, final String deadHost, final List<String> asked) {
        return Proxy.newProxyInstance(
                java.sql.DatabaseMetaData.class.getClassLoader(),
                new Class[] {java.sql.DatabaseMetaData.class},
                new InvocationHandler() {
                    public Object invoke(
                            final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        if ("getTables".equals(method.getName())) {
                            asked.add(host);
                            if (deadHost.equals(host)) {
                                throw new SQLException(
                                        "comm fail", null, UErrorCode.ER_COMMUNICATION);
                            }
                        }

                        return defaultAnswer(method);
                    }
                });
    }

    /** One physical {@code Connection} method fails on {@code deadHost} and works elsewhere. */
    private static JdbcConnectionFactory commandFactory(
            final String deadHost, final List<String> asked, final String failingMethod) {
        return new JdbcConnectionFactory() {
            public Connection getConnection(final String url, final Properties info) {
                final String host = hostOf(url);

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
                                        if (failingMethod.equals(method.getName())) {
                                            asked.add(host);
                                            if (deadHost.equals(host)) {
                                                throw new SQLException(
                                                        "comm fail",
                                                        null,
                                                        UErrorCode.ER_COMMUNICATION);
                                            }
                                        }

                                        return defaultAnswer(method);
                                    }
                                });
            }
        };
    }

    private interface PrepareBehavior {
        PreparedStatement prepare() throws SQLException;
    }

    private static Connection connectionProxy(final PrepareBehavior onPrepare) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("prepareStatement".equals(method.getName())) {
                                    return onPrepare.prepare();
                                }

                                return defaultAnswer(method);
                            }
                        });
    }

    private static PreparedStatement preparedStatementProxy() {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("getMetaData".equals(method.getName())) {
                                    return metaDataProxy(ResultSetMetaData.class);
                                }
                                if ("getParameterMetaData".equals(method.getName())) {
                                    return metaDataProxy(java.sql.ParameterMetaData.class);
                                }

                                return defaultAnswer(method);
                            }
                        });
    }

    private static Object metaDataProxy(final Class<?> iface) {
        return Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class[] {iface},
                new InvocationHandler() {
                    public Object invoke(
                            final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        return defaultAnswer(method);
                    }
                });
    }

    /** Enough of a physical answer for the LB layer to keep going. */
    private static Object defaultAnswer(final Method method) {
        if ("isClosed".equals(method.getName())) {
            return Boolean.FALSE;
        }
        if ("getDatabaseProductName".equals(method.getName())) {
            return "CUBRID";
        }

        Class<?> rt = method.getReturnType();
        if (rt.equals(java.sql.DatabaseMetaData.class)) {
            return metaDataProxy(java.sql.DatabaseMetaData.class);
        }
        if (rt.equals(Boolean.TYPE)) {
            return Boolean.FALSE;
        }
        if (rt.equals(Integer.TYPE)) {
            return Integer.valueOf(0);
        }
        if (rt.equals(Long.TYPE)) {
            return Long.valueOf(0L);
        }

        return null;
    }
}
