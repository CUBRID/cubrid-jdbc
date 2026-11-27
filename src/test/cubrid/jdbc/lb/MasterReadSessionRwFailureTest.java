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

import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * A master-read session reads on its write connection. When the master's RW broker goes away, a
 * read that fails there moves the write leg, as core's altHosts reconnect moves its one connection.
 */
public final class MasterReadSessionRwFailureTest {

    private static final Endpoint NODE1 = new Endpoint("mrnode1", 33000);
    private static final Endpoint NODE2 = new Endpoint("mrnode2", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("mrnode2", 33002);

    private final Set<String> down = new HashSet<String>();
    private final List<Leg> legs = new ArrayList<Leg>();
    private final List<String> dials = new ArrayList<String>();
    private LoadBalanceConnection conn;

    private void open() throws SQLException {
        LoadBalanceSettings settings =
                LbTestSettings.with(
                        LbTestSettings.fromUrl(
                                "jdbc:cubrid:loadbalance://mrnode1:33000,mrnode2:33000:33002"
                                        + "/testdb:::?readWeight=master:1"),
                        "readFailbackProbeIntervalMs",
                        Integer.valueOf(0));
        conn = new LoadBalanceConnection(settings);
        conn.setConnectionManager(
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        new Properties(),
                        settings,
                        new JdbcConnectionFactory() {
                            public Connection getConnection(final String url, final Properties i)
                                    throws SQLException {
                                for (Endpoint each : Arrays.asList(NODE1, NODE2, NODE2_RO)) {
                                    if (url.contains(":" + each.getId() + ":")) {
                                        dials.add(each.getId());
                                        if (down.contains(each.getId())) {
                                            throw new SQLException(
                                                    "down", null, UErrorCode.ER_CONNECTION);
                                        }
                                        Leg leg = new Leg(each);
                                        legs.add(leg);
                                        return leg;
                                    }
                                }
                                throw new IllegalStateException(url);
                            }
                        }));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(settings.buildEndpointTopology());
    }

    @After
    public void tearDown() throws SQLException {
        if (conn != null) {
            conn.close();
        }
        for (Endpoint each : Arrays.asList(NODE1, NODE2, NODE2_RO)) {
            UUnreachableHostList.getInstance().remove(each.getId());
        }
    }

    @Test
    public void aReadThatFailsOnTheWriteConnectionMovesTheWriteLeg() throws SQLException {
        open();
        down.add(NODE1.getId());
        down.add(NODE2_RO.getId());

        conn.createStatement().executeQuery("SELECT a FROM t");

        assertEquals(NODE2, conn.getCurrentRwEndpoint());
    }

    @Test
    public void aWriteAfterSuchAReadDialsNoDeadMaster() throws SQLException {
        open();
        down.add(NODE1.getId());
        Statement st = conn.createStatement();
        st.executeQuery("SELECT a FROM t"); // the read moves to the slave's read broker
        dials.clear();

        st.executeUpdate("UPDATE t SET a = 1");

        assertFalse(dials.toString(), dials.contains(NODE1.getId()));
        assertEquals(NODE2, conn.getCurrentRwEndpoint());
    }

    /**
     * With no read broker reachable when the write leg moved, reads ride the new write connection.
     * The read leg leaving it when a read broker returns leaves it to the write leg, session and
     * all.
     */
    @Test
    public void aReadFailbackLeavesTheWriteConnectionToTheWriteLeg() throws SQLException {
        open();
        down.add(NODE1.getId());
        down.add(NODE2_RO.getId());
        Statement st = conn.createStatement();
        try {
            st.executeUpdate("UPDATE t SET a = 1"); // moves the write leg to node2
        } catch (SQLException maybeApplied) {
            // not run again on node2: it may have been applied
        }
        st.execute("SET @y = 7");
        down.remove(NODE2_RO.getId());
        UUnreachableHostList.getInstance().remove(NODE2_RO.getId());

        st.executeQuery("SELECT a FROM t"); // the read leg climbs to node2's read broker
        st.executeQuery("SELECT @y");

        List<Leg> writeLegs = legsTo(NODE2);
        assertEquals(1, writeLegs.size());
        assertFalse(writeLegs.get(0).closed);
        assertEquals(Arrays.asList("SET @y = 7", "SELECT @y"), writeLegs.get(0).ran);
    }

    private List<Leg> legsTo(final Endpoint node) {
        List<Leg> found = new ArrayList<Leg>();
        for (Leg each : legs) {
            if (each.node.equals(node)) {
                found.add(each);
            }
        }
        return found;
    }

    /** A leg whose statements fail while its node is down, as a broker that went away. */
    private final class Leg extends FakePhysicalConnection {
        private final Endpoint node;
        private final List<String> ran = new ArrayList<String>();
        private final UConnection socket =
                new UConnection() {
                    public void endTransaction(final boolean type) {}

                    protected void closeInternal() {}

                    public void setAutoCommit(final boolean autoCommit) {}

                    public boolean getAutoCommit() {
                        return true;
                    }
                };
        private boolean closed;

        Leg(final Endpoint node) {
            this.node = node;
            socket.casIp = node.getHost();
            socket.casPort =
                    Integer.parseInt(node.getId().substring(node.getId().indexOf(':') + 1));
        }

        @Override
        public UConnection getUConnection() {
            return socket;
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
                return statement();
            }
            return null;
        }

        private Statement statement() {
            return (Statement)
                    Proxy.newProxyInstance(
                            Statement.class.getClassLoader(),
                            new Class<?>[] {Statement.class},
                            new InvocationHandler() {
                                private boolean statementClosed;

                                public Object invoke(
                                        final Object p, final Method m, final Object[] a)
                                        throws SQLException {
                                    String name = m.getName();
                                    if ("isClosed".equals(name)) {
                                        return Boolean.valueOf(closed || statementClosed);
                                    }
                                    if ("close".equals(name)) {
                                        statementClosed = true;
                                    }
                                    if ("getConnection".equals(name)) {
                                        return Leg.this;
                                    }
                                    if (name.startsWith("execute")) {
                                        if (down.contains(node.getId())) {
                                            throw new SQLException(
                                                    "broker gone",
                                                    null,
                                                    UErrorCode.ER_COMMUNICATION);
                                        }
                                        ran.add((String) a[0]);
                                    }
                                    Class<?> returns = m.getReturnType();
                                    if (returns == Boolean.TYPE) {
                                        return Boolean.FALSE;
                                    }
                                    return returns == Integer.TYPE ? Integer.valueOf(0) : null;
                                }
                            });
        }
    }
}
