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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.jci.UConnection;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Core reconnects a leg underneath LB and LB is not told.
 *
 * <p>{@code UConnection.checkReconnect()} runs before every request, and when the socket is gone
 * {@code reconnectWorker()} opens a new one behind the same {@code Connection} object. What core
 * restores there is what a core connection gets back, and LB does not apply the session properties
 * again: that ran {@code setTransactionIsolation}, which commits first in core, inside the
 * application's transaction.
 *
 * <p>A reconnect the CAS could not keep the server session over leaves that leg alone without the
 * SQL {@code SET}s LB ran on it, so its statements and the other leg's would run under different
 * settings. The session id core holds tells: a read leg in a new session is replaced, and the write
 * leg runs the SETs again once it holds no transaction.
 */
public final class CoreSilentReconnectStateTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final Endpoint RW = new Endpoint("rw", 33000);
    private static final String SET = "SET TIMEZONE '+03:00'";

    @Test
    public void coreReconnectingTheLegLeavesTheSessionStateToCore() throws SQLException {
        IdentityConnection physical = new IdentityConnection();
        LoadBalanceConnection conn = session(physical);
        int afterBind = physical.stateApplications.get();

        // Core reconnected: same Connection object, new CAS behind it.
        physical.casProcessId(4242);
        conn.isReadOnly();

        assertEquals(afterBind, physical.stateApplications.get());
        conn.close();
    }

    @Test
    public void coreReconnectingTheLegInATransactionDoesNotCommitIt() throws SQLException {
        IdentityConnection physical = new IdentityConnection();
        LoadBalanceConnection conn = session(physical);
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        conn.setAutoCommit(false);
        int isolationCalls = physical.isolationCalls.get();

        physical.casProcessId(4242);
        conn.isReadOnly();

        assertEquals(
                "setTransactionIsolation commits first in core",
                isolationCalls,
                physical.isolationCalls.get());
        conn.close();
    }

    @Test
    public void aWriteLegInANewSessionRunsTheSessionSetsAgain() throws SQLException {
        Legs legs = new Legs();
        LoadBalanceConnection conn = session(legs);
        Statement st = conn.createStatement();
        st.execute(SET);
        Leg rw = legs.last("rw");

        rw.u.renewSession();
        st.executeUpdate("UPDATE t SET a = 1");

        assertEquals(Arrays.asList(SET, SET, "UPDATE t SET a = 1"), rw.ran);
        st.executeUpdate("UPDATE t SET a = 2");
        assertEquals(
                "once",
                Arrays.asList(SET, SET, "UPDATE t SET a = 1", "UPDATE t SET a = 2"),
                rw.ran);
        conn.close();
    }

    @Test
    public void aWriteLegInANewSessionWaitsForItsTransactionToEnd() throws SQLException {
        Legs legs = new Legs();
        LoadBalanceConnection conn = session(legs);
        Statement st = conn.createStatement();
        st.execute(SET);
        Leg rw = legs.last("rw");

        rw.u.transactionOpen(true);
        rw.u.renewSession();
        st.executeUpdate("UPDATE t SET a = 1");
        assertEquals(
                "not inside the transaction", Arrays.asList(SET, "UPDATE t SET a = 1"), rw.ran);

        rw.u.transactionOpen(false);
        st.executeUpdate("UPDATE t SET a = 2");
        assertEquals(Arrays.asList(SET, "UPDATE t SET a = 1", SET, "UPDATE t SET a = 2"), rw.ran);
        conn.close();
    }

    @Test
    public void aReadLegInANewSessionIsReplacedBeforeTheNextRead() throws SQLException {
        Legs legs = new Legs();
        LoadBalanceConnection conn = session(legs);
        Statement st = conn.createStatement();
        st.execute(SET);
        st.executeQuery("SELECT a FROM t");
        Leg first = legs.last("ro1");

        first.u.renewSession();
        st.executeQuery("SELECT b FROM t");

        Leg second = legs.last("ro1");
        assertTrue(first.closed);
        assertNotSame(first, second);
        assertEquals(Arrays.asList(SET, "SELECT a FROM t"), first.ran);
        assertEquals(Arrays.asList(SET, "SELECT b FROM t"), second.ran);
        conn.close();
    }

    @Test
    public void aReadThatCoreReconnectedIntoANewSessionRunsAgainOnANewReadLeg()
            throws SQLException {
        Legs legs = new Legs();
        LoadBalanceConnection conn = session(legs);
        Statement st = conn.createStatement();
        st.execute(SET);
        st.executeQuery("SELECT a FROM t");
        Leg first = legs.last("ro1");

        first.renewsDuring = "SELECT b FROM t";
        st.executeQuery("SELECT b FROM t");

        Leg second = legs.last("ro1");
        assertTrue(first.closed);
        assertNotSame(first, second);
        assertEquals(Arrays.asList(SET, "SELECT a FROM t", "SELECT b FROM t"), first.ran);
        assertEquals(Arrays.asList(SET, "SELECT b FROM t"), second.ran);
        conn.close();
    }

    @Test
    public void aPreparedReadThatCoreReconnectedIntoANewSessionIsPreparedAgain()
            throws SQLException {
        Legs legs = new Legs();
        LoadBalanceConnection conn = session(legs);
        conn.createStatement().execute(SET);
        PreparedStatement ps = conn.prepareStatement("SELECT a FROM t WHERE a = ?");
        ps.setInt(1, 1);
        ps.executeQuery();
        Leg first = legs.last("ro1");

        first.renewsDuring = "SELECT a FROM t WHERE a = ?";
        ps.executeQuery();

        Leg second = legs.last("ro1");
        assertNotSame(first, second);
        assertEquals(Arrays.asList(SET, "SELECT a FROM t WHERE a = ?"), second.ran);
        conn.close();
    }

    @Test
    public void aSessionWithNoSessionSetIsLeftAloneInANewSession() throws SQLException {
        Legs legs = new Legs();
        LoadBalanceConnection conn = session(legs);
        Statement st = conn.createStatement();
        st.executeQuery("SELECT a FROM t");
        Leg ro = legs.last("ro1");
        Leg rw = legs.last("rw");

        ro.u.renewSession();
        rw.u.renewSession();
        st.executeQuery("SELECT b FROM t");
        st.executeUpdate("UPDATE t SET a = 1");

        assertFalse(ro.closed);
        assertSame(ro, legs.last("ro1"));
        assertEquals(Arrays.asList("SELECT a FROM t", "SELECT b FROM t"), ro.ran);
        assertEquals(Arrays.asList("UPDATE t SET a = 1"), rw.ran);
        conn.close();
    }

    @Test
    public void aLegCoreReconnectedIntoTheSameSessionIsLeftAlone() throws SQLException {
        Legs legs = new Legs();
        LoadBalanceConnection conn = session(legs);
        Statement st = conn.createStatement();
        st.execute(SET);
        st.executeQuery("SELECT a FROM t");
        Leg ro = legs.last("ro1");
        Leg rw = legs.last("rw");

        // The CAS kept the session (cubrid broker reset, a CAS handed to another client).
        ro.u.casProcessId = 4242;
        rw.u.casProcessId = 4243;
        st.executeQuery("SELECT b FROM t");
        st.executeUpdate("UPDATE t SET a = 1");

        assertFalse(ro.closed);
        assertEquals(Arrays.asList(SET, "SELECT a FROM t", "SELECT b FROM t"), ro.ran);
        assertEquals(Arrays.asList(SET, "UPDATE t SET a = 1"), rw.ran);
        conn.close();
    }

    /* ===== harness ===== */

    private static LoadBalanceConnection session(final Connection physical) throws SQLException {
        return session(
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        return physical;
                    }
                });
    }

    private static LoadBalanceConnection session(final JdbcConnectionFactory factory)
            throws SQLException {
        LoadBalanceSettings settings =
                LbTestSettings.forBinding(new Endpoint("rw", 33000), new Endpoint("ro1", 33001));

        LoadBalanceConnection conn = LbTestAccess.newLoadBalanceConnection(settings);
        LbTestAccess.setConnectionManager(
                conn,
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), settings, factory));
        LbTestAccess.setSharedSelectorState(conn, new SharedSelectorState());
        LbTestAccess.initSessionBindings(
                conn,
                new EndpointTopology(
                        RW,
                        Arrays.asList(new Endpoint("ro1", 33001)),
                        Collections.<Endpoint>emptyList()));

        return conn;
    }

    /**
     * A physical connection that reports a CAS identity and counts how often LB configured it.
     * {@code setAutoCommit} is the first call {@code applySessionStateTo} makes, so counting it
     * counts the applications.
     */
    private static class IdentityConnection extends FakePhysicalConnection {

        private final AtomicInteger stateApplications = new AtomicInteger();
        private final AtomicInteger isolationCalls = new AtomicInteger();
        private final FakeUConnection u = new FakeUConnection();

        private void casProcessId(final int id) {
            u.casProcessId = id;
        }

        @Override
        public UConnection getUConnection() throws SQLException {
            return u;
        }

        @Override
        protected Object dispatch(final String name, final Object[] args) throws SQLException {
            if ("setAutoCommit".equals(name)) {
                stateApplications.incrementAndGet();
            }
            if ("setTransactionIsolation".equals(name)) {
                isolationCalls.incrementAndGet();
            }
            if ("isClosed".equals(name)) {
                return Boolean.FALSE;
            }
            if ("isReadOnly".equals(name)) {
                return Boolean.FALSE;
            }

            return null;
        }
    }

    /** Opens a {@link Leg} per physical connection LB asks for. */
    private static final class Legs implements JdbcConnectionFactory {
        private final List<Leg> opened = new ArrayList<Leg>();

        public Connection getConnection(final String url, final Properties info) {
            Leg leg = new Leg(url.indexOf(":ro1:") >= 0 ? "ro1" : "rw");
            opened.add(leg);
            return leg;
        }

        /** The most recently opened leg to {@code host}. */
        Leg last(final String host) {
            for (int i = opened.size() - 1; i >= 0; i--) {
                if (opened.get(i).host.equals(host)) {
                    return opened.get(i);
                }
            }
            throw new AssertionError("no leg to " + host);
        }
    }

    /** A leg that records the statements it ran, in a server session core can renew. */
    private static final class Leg extends FakePhysicalConnection {
        private final String host;
        private final List<String> ran = new ArrayList<String>();
        private final FakeUConnection u = new FakeUConnection();
        private boolean closed;
        // Core reconnects into a new session while this statement runs.
        private String renewsDuring;

        Leg(final String host) {
            this.host = host;
        }

        @Override
        public UConnection getUConnection() {
            return u;
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
            return null;
        }

        private Object statement(final Class<?> type, final String prepared) {
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
                            if (name.startsWith("execute")) {
                                String sql = prepared != null ? prepared : (String) a[0];
                                ran.add(sql);
                                if (sql.equals(renewsDuring)) {
                                    renewsDuring = null;
                                    u.renewSession();
                                }
                                if ("executeQuery".equals(name)) {
                                    return resultSet();
                                }
                                if ("executeUpdate".equals(name)) {
                                    return Integer.valueOf(0);
                                }
                                return Boolean.valueOf(sql.startsWith("SELECT"));
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

    /** Carries a CAS identity, a session and a transaction report; the rest is four methods. */
    private static final class FakeUConnection extends UConnection {

        private FakeUConnection() {
            casIp = "rw";
            casPort = 33000;
            casId = 1;
            casProcessId = 1;
        }

        /** As a reconnect the CAS could not keep the session over: the server gave a new one. */
        private void renewSession() {
            sessionId = sessionId.clone();
            sessionId[11]++;
        }

        private void transactionOpen(final boolean open) {
            setCASInfo(
                    new byte[] {open ? CAS_INFO_STATUS_ACTIVE : CAS_INFO_STATUS_INACTIVE, 0, 0, 0});
        }

        public void endTransaction(final boolean type) {}

        protected void closeInternal() {}

        public void setAutoCommit(final boolean autoCommit) {}

        public boolean getAutoCommit() {
            return true;
        }
    }
}
