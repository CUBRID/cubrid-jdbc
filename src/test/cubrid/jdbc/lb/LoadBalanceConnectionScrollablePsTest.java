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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

/**
 * A logical PreparedStatement opened with a result-set type, concurrency or holdability must carry
 * them to the physical prepare. Otherwise the physical statement is FORWARD_ONLY while the logical
 * one is scrollable, and applying the logical fetch direction to it fails the way the base driver
 * does ({@code non_scrollable_statement}) - every execute of a scrollable PreparedStatement.
 */
public class LoadBalanceConnectionScrollablePsTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final String SQL = "SELECT a FROM t";

    @Test
    public void scrollInsensitivePreparedStatementExecutes() throws SQLException {
        List<String> prepares = new ArrayList<String>();
        LoadBalanceConnection conn = newConnection(prepares);

        PreparedStatement ps =
                conn.prepareStatement(
                        SQL, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        ps.executeQuery();

        assertEquals(
                Arrays.asList(
                        prepareOf(
                                ResultSet.TYPE_SCROLL_INSENSITIVE,
                                ResultSet.CONCUR_READ_ONLY,
                                ResultSet.HOLD_CURSORS_OVER_COMMIT)),
                prepares);
        conn.close();
    }

    @Test
    public void holdabilityIsCarriedToThePhysicalPrepare() throws SQLException {
        List<String> prepares = new ArrayList<String>();
        LoadBalanceConnection conn = newConnection(prepares);

        PreparedStatement ps =
                conn.prepareStatement(
                        SQL,
                        ResultSet.TYPE_SCROLL_INSENSITIVE,
                        ResultSet.CONCUR_READ_ONLY,
                        ResultSet.CLOSE_CURSORS_AT_COMMIT);
        ps.executeQuery();

        assertEquals(
                Arrays.asList(
                        prepareOf(
                                ResultSet.TYPE_SCROLL_INSENSITIVE,
                                ResultSet.CONCUR_READ_ONLY,
                                ResultSet.CLOSE_CURSORS_AT_COMMIT)),
                prepares);
        conn.close();
    }

    @Test
    public void unsupportedHoldabilityFallsBackToConnectionDefault() throws SQLException {
        List<String> prepares = new ArrayList<String>();
        LoadBalanceConnection conn = newConnection(prepares);

        // The 3-arg form defaults the logical holdability to HOLD_CURSORS_OVER_COMMIT, which the
        // base driver refuses together with SCROLL_SENSITIVE; the base 3-arg form does not.
        PreparedStatement ps =
                conn.prepareStatement(
                        SQL, ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY);
        ps.executeQuery();

        assertEquals(
                Arrays.asList(
                        prepareOf(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY)),
                prepares);
        conn.close();
    }

    @Test
    public void defaultPreparedStatementKeepsTheSingleArgPrepare() throws SQLException {
        List<String> prepares = new ArrayList<String>();
        LoadBalanceConnection conn = newConnection(prepares);

        conn.prepareStatement(SQL).executeQuery();

        assertEquals(Arrays.asList("prepare()"), prepares);
        conn.close();
    }

    /* ===== harness ===== */

    private static String prepareOf(final int... args) {
        return "prepare" + Arrays.toString(args).replace('[', '(').replace(']', ')');
    }

    private static LoadBalanceConnection newConnection(final List<String> prepares)
            throws SQLException {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        return connectionProxy(prepares);
                    }
                };

        LoadBalanceConnection conn = new LoadBalanceConnection(config);
        conn.setConnectionManager(
                new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), config, factory));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(
                new EndpointTopology(
                        new Endpoint("rw", 33000),
                        Arrays.asList(new Endpoint("ro", 33001)),
                        Collections.<Endpoint>emptyList()));
        return conn;
    }

    /**
     * A physical connection whose prepareStatement mirrors the base driver: only a non-FORWARD_ONLY
     * type makes the statement scrollable, and the 4-arg form refuses HOLD_CURSORS_OVER_COMMIT with
     * SCROLL_SENSITIVE.
     */
    private static Connection connectionProxy(final List<String> prepares) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("prepareStatement".equals(method.getName())) {
                                    return prepare(args, prepares);
                                }
                                return defaultValue(method);
                            }
                        });
    }

    private static PreparedStatement prepare(final Object[] args, final List<String> prepares)
            throws SQLException {
        int[] rsArgs = new int[args.length - 1];
        for (int i = 1; i < args.length; i++) {
            rsArgs[i - 1] = ((Integer) args[i]).intValue();
        }
        if (rsArgs.length == 3
                && rsArgs[2] == ResultSet.HOLD_CURSORS_OVER_COMMIT
                && rsArgs[0] == ResultSet.TYPE_SCROLL_SENSITIVE) {
            throw new SQLFeatureNotSupportedException("not supported");
        }
        prepares.add(prepareOf(rsArgs));

        final boolean scrollable = rsArgs.length >= 2 && rsArgs[0] != ResultSet.TYPE_FORWARD_ONLY;
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("setFetchDirection".equals(method.getName()) && !scrollable) {
                                    throw new SQLException("non_scrollable_statement");
                                }
                                if ("executeQuery".equals(method.getName())) {
                                    return resultSetProxy();
                                }
                                return defaultValue(method);
                            }
                        });
    }

    private static ResultSet resultSetProxy() {
        return (ResultSet)
                Proxy.newProxyInstance(
                        ResultSet.class.getClassLoader(),
                        new Class[] {ResultSet.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                return defaultValue(method);
                            }
                        });
    }

    private static Object defaultValue(final Method method) {
        Class<?> rt = method.getReturnType();
        if (rt.equals(Boolean.TYPE)) {
            return Boolean.FALSE;
        }
        if (rt.equals(Integer.TYPE)) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
