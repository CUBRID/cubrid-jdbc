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

package cubrid.jdbc.lb.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.FakePhysicalConnection;
import cubrid.jdbc.lb.LbTestAccess;
import cubrid.jdbc.lb.LbTestSettings;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A login or a lock timeout given its values with {@code ?} changes the session it runs on, and the
 * read leg is another session. Its text alone cannot be run there, so it used to keep the session's
 * reads on RW until the connection closed: an application that binds its login never sends a
 * literal one. It runs on the read leg with the values of that run instead, there and on every leg
 * opened later.
 *
 * <p>{@code SET OPTIMIZATION COST} changes the cost functions of the CAS process it runs in, for
 * every later client of it, and no result. It is reported, as a server-wide parameter is, and pins
 * nothing.
 */
public final class BoundSessionSetReplayTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final Endpoint RW = new Endpoint("rw", 33000);
    private static final String LOGIN = "CALL login(?, ?) ON CLASS db_user";
    private static final String LOCK_TIMEOUT = "SET TRANSACTION LOCK TIMEOUT ?";
    private static final String READ = "SELECT a FROM t";

    private final List<Leg> opened = new ArrayList<Leg>();
    private LoadBalanceConnection conn;

    @Before
    public void setUp() throws SQLException {
        LoadBalanceSettings settings =
                LbTestSettings.forBinding(new Endpoint("rw", 33000), new Endpoint("ro1", 33001));
        conn = LbTestAccess.newLoadBalanceConnection(settings);
        LbTestAccess.setConnectionManager(
                conn,
                new SessionPhysicalConnManager(
                        LOGICAL_URL,
                        new Properties(),
                        settings,
                        new JdbcConnectionFactory() {
                            public Connection getConnection(final String url, final Properties i) {
                                Leg leg = new Leg(url.indexOf(":ro1:") >= 0 ? "ro1" : "rw");
                                opened.add(leg);
                                return leg;
                            }
                        }));
        LbTestAccess.setSharedSelectorState(conn, new SharedSelectorState());
        LbTestAccess.initSessionBindings(
                conn,
                new EndpointTopology(
                        RW,
                        Arrays.asList(new Endpoint("ro1", 33001)),
                        Collections.<Endpoint>emptyList()));
    }

    @After
    public void tearDown() throws SQLException {
        conn.close();
    }

    /** The most recently opened leg to {@code host}. */
    private Leg leg(final String host) {
        for (int i = opened.size() - 1; i >= 0; i--) {
            if (opened.get(i).host.equals(host)) {
                return opened.get(i);
            }
        }
        throw new AssertionError("no leg to " + host);
    }

    private void read() throws SQLException {
        conn.createStatement().executeQuery(READ);
    }

    private void login(final String user, final String password) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(LOGIN);
        ps.setString(1, user);
        ps.setString(2, password);
        ps.execute();
        ps.close();
    }

    @Test
    public void aLoginGivenItsValuesRunsOnTheReadLegWithThem() throws SQLException {
        login("public", "pw");
        read();

        assertEquals(Arrays.asList(LOGIN + " [public, pw]"), leg("rw").ran);
        assertEquals(Arrays.asList(LOGIN + " [public, pw]", READ), leg("ro1").ran);
    }

    @Test
    public void aLockTimeoutGivenItsValueRunsOnTheReadLegWithIt() throws SQLException {
        PreparedStatement ps = conn.prepareStatement(LOCK_TIMEOUT);
        ps.setInt(1, 7);
        ps.execute();
        read();

        assertEquals(Arrays.asList(LOCK_TIMEOUT + " [7]"), leg("rw").ran);
        assertEquals(Arrays.asList(LOCK_TIMEOUT + " [7]", READ), leg("ro1").ran);
    }

    @Test
    public void aLoginGivenItsValuesThroughACallableStatementDoesTheSame() throws SQLException {
        CallableStatement cs = conn.prepareCall(LOGIN);
        cs.setString(1, "public");
        cs.setString(2, "pw");
        cs.execute();
        read();

        assertEquals(Arrays.asList(LOGIN + " [public, pw]"), leg("rw").ran);
        assertEquals(Arrays.asList(LOGIN + " [public, pw]", READ), leg("ro1").ran);
    }

    @Test
    public void aReadLegOpenedLaterGetsTheValuesOfTheLastRun() throws SQLException {
        PreparedStatement ps = conn.prepareStatement(LOGIN);
        ps.setString(1, "public");
        ps.setString(2, "pw");
        ps.execute();
        ps.setString(1, "u2");
        ps.setString(2, "pw2");
        ps.execute();
        ps.setString(1, "set and not run"); // what a run was given is kept, not what is set now
        final Leg first = leg("ro1");
        first.close();

        read();

        assertTrue(leg("ro1") != first);
        assertEquals(Arrays.asList(LOGIN + " [u2, pw2]", READ), leg("ro1").ran);
    }

    @Test
    public void aSettingTheReadLegIsNotGivenWithValuesStillPins() throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SET NAMES ?");
        ps.setString(1, "utf8");
        ps.execute();
        read();

        assertEquals(Arrays.asList("SET NAMES ? [utf8]", READ), leg("rw").ran);
        assertEquals(Collections.<String>emptyList(), leg("ro1").ran);
    }

    @Test
    public void aBoundSettingAmongSeveralStatementsStillPins() throws SQLException {
        PreparedStatement ps = conn.prepareStatement(LOCK_TIMEOUT + "; SET NAMES utf8");
        ps.setInt(1, 7);
        ps.execute();
        read();

        assertTrue(leg("rw").ran.contains(READ));
    }

    @Test
    public void optimizationCostChangesTheNodeItRanOnAndPinsNothing() throws SQLException {
        conn.createStatement().execute("SET OPTIMIZATION COST 'iscan' '0'");
        read();

        assertEquals(Arrays.asList("SET OPTIMIZATION COST 'iscan' '0'"), leg("rw").ran);
        assertEquals(Arrays.asList(READ), leg("ro1").ran);
    }

    /** A leg that records the statements it ran, a bound one with the values it was given. */
    private static final class Leg extends FakePhysicalConnection {
        private final String host;
        private final List<String> ran = new ArrayList<String>();
        private boolean closed;

        Leg(final String host) {
            this.host = host;
        }

        @Override
        protected Object dispatch(final String name, final Object[] args) {
            if ("isClosed".equals(name)) {
                return Boolean.valueOf(closed);
            }
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("getAutoCommit".equals(name)) {
                return Boolean.TRUE;
            }
            if ("createStatement".equals(name)) {
                return statement(Statement.class, null);
            }
            if ("prepareStatement".equals(name)) {
                return statement(PreparedStatement.class, (String) args[0]);
            }
            if ("prepareCall".equals(name)) {
                return statement(CallableStatement.class, (String) args[0]);
            }
            return null;
        }

        private Object statement(final Class<?> type, final String prepared) {
            final List<Object> values = new ArrayList<Object>();
            return Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[] {type},
                    new InvocationHandler() {
                        public Object invoke(final Object proxy, final Method m, final Object[] a) {
                            String name = m.getName();
                            if ("hashCode".equals(name)) {
                                return Integer.valueOf(System.identityHashCode(proxy));
                            }
                            if ("equals".equals(name)) {
                                return Boolean.valueOf(proxy == a[0]);
                            }
                            if ("getConnection".equals(name)) {
                                return Leg.this;
                            }
                            if ("isClosed".equals(name)) {
                                return Boolean.valueOf(closed);
                            }
                            if ("clearParameters".equals(name)) {
                                values.clear();
                            }
                            if (prepared != null && name.startsWith("set") && a.length == 2) {
                                final int index = ((Integer) a[0]).intValue();
                                while (values.size() < index) {
                                    values.add(null);
                                }
                                values.set(index - 1, a[1]);
                            }
                            if (name.startsWith("execute")) {
                                ran.add(prepared != null ? prepared + " " + values : (String) a[0]);
                                if ("executeQuery".equals(name)) {
                                    return resultSet();
                                }
                                if ("executeUpdate".equals(name)) {
                                    return Integer.valueOf(0);
                                }
                                return Boolean.FALSE;
                            }
                            return defaultFor(m.getReturnType());
                        }
                    });
        }

        private static ResultSet resultSet() {
            return (ResultSet)
                    Proxy.newProxyInstance(
                            ResultSet.class.getClassLoader(),
                            new Class<?>[] {ResultSet.class},
                            new InvocationHandler() {
                                public Object invoke(
                                        final Object rs, final Method m, final Object[] a) {
                                    return defaultFor(m.getReturnType());
                                }
                            });
        }

        private static Object defaultFor(final Class<?> type) {
            if (type == Boolean.TYPE) {
                return Boolean.FALSE;
            }
            if (type == Integer.TYPE) {
                return Integer.valueOf(0);
            }
            if (type == Long.TYPE) {
                return Long.valueOf(0L);
            }
            return null;
        }
    }
}
