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

import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * A connection in autocommit that prepares, runs and closes a PreparedStatement each time returns
 * to the primary in core at the second one. Here the prepare opens a transaction on the write leg,
 * which holds the failback attempted when the statement runs, so the failback is attempted before
 * the prepare.
 */
public final class PreparedOnlyFailbackTest {

    private static final Endpoint NODE1 = new Endpoint("pofnode1", 33000);
    private static final Endpoint NODE2 = new Endpoint("pofnode2", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("pofnode2", 33002);
    private static final String INSERT = "INSERT INTO t (v) VALUES (?)";

    private final Set<String> down = new HashSet<String>();
    private final List<Leg> legs = new ArrayList<Leg>();
    private LoadBalanceConnection conn;

    /** Opens with the master broker down, so the write leg is on the other node. */
    private void open(final boolean readBrokerDown) throws SQLException {
        EndpointTopology topo =
                new EndpointTopology(
                        Arrays.asList(NODE1, NODE2),
                        Arrays.asList(NODE2_RO),
                        Collections.<Endpoint>emptyList());
        LoadBalanceSettings settings =
                LbTestSettings.with(
                        LbTestSettings.forTopology(topo),
                        "writeFailbackProbeIntervalMs",
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
                                        if (down.contains(each.getId())) {
                                            throw new SQLException(
                                                    "down", null, UErrorCode.CAS_ER_FREE_SERVER);
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
        down.add(NODE1.getId());
        UUnreachableHostList.getInstance().add(NODE1.getId());
        if (readBrokerDown) {
            down.add(NODE2_RO.getId());
        }
        conn.initSessionBindings(topo);
    }

    private void masterComesBack() {
        down.remove(NODE1.getId());
        UUnreachableHostList.getInstance().remove(NODE1.getId());
    }

    @After
    public void tearDown() throws SQLException {
        conn.close();
        for (Endpoint each : Arrays.asList(NODE1, NODE2, NODE2_RO)) {
            UUnreachableHostList.getInstance().remove(each.getId());
        }
    }

    /** What the legs to {@code node} were asked, in order. */
    private List<String> asked(final Endpoint node) {
        List<String> all = new ArrayList<String>();
        for (Leg each : legs) {
            if (each.node.equals(node)) {
                all.addAll(each.asked);
            }
        }
        return all;
    }

    @Test
    public void prepareStatementAttemptsTheFailbackBeforeItPrepares() throws SQLException {
        open(false);
        masterComesBack();

        conn.prepareStatement(INSERT);

        assertEquals(NODE1, conn.getCurrentRwEndpoint());
        assertEquals(Collections.<String>emptyList(), asked(NODE2));
        assertEquals(Arrays.asList("prepareStatement"), asked(NODE1));
    }

    @Test
    public void aStatementPreparedRunAndClosedEachTimeReturnsAtTheNextPrepare()
            throws SQLException {
        open(false);
        PreparedStatement first = conn.prepareStatement(INSERT);
        first.setInt(1, 1);
        first.executeUpdate();
        first.close();
        masterComesBack();

        PreparedStatement second = conn.prepareStatement(INSERT);
        second.setInt(1, 2);
        second.executeUpdate();

        assertEquals(Arrays.asList("prepareStatement", "execute", "close"), asked(NODE2));
        assertEquals(Arrays.asList("prepareStatement", "execute"), asked(NODE1));
    }

    @Test
    public void aReadPreparedOnTheWriteLegReturnsAtItsPrepare() throws SQLException {
        open(true); // no read broker: the write leg runs the reads
        masterComesBack();

        conn.prepareStatement("SELECT v FROM t WHERE v > ?");

        assertEquals(NODE1, conn.getCurrentRwEndpoint());
        assertEquals(Collections.<String>emptyList(), asked(NODE2));
        assertEquals(Arrays.asList("prepareStatement"), asked(NODE1));
    }

    @Test
    public void aPrepareLeavesTheWriteLegWhereAnOpenTransactionHoldsIt() throws SQLException {
        open(false);
        conn.prepareStatement(INSERT); // its prepare opens a transaction, and it has not run
        masterComesBack();

        conn.prepareStatement("UPDATE t SET v = ?");

        assertEquals(NODE2, conn.getCurrentRwEndpoint());
        assertEquals(Arrays.asList("prepareStatement", "prepareStatement"), asked(NODE2));
    }

    @Test
    public void aPrepareInManualCommitAttemptsNoFailback() throws SQLException {
        open(false);
        conn.setAutoCommit(false);
        masterComesBack();

        conn.prepareStatement(INSERT);

        assertEquals(NODE2, conn.getCurrentRwEndpoint());
        assertEquals(Arrays.asList("prepareStatement"), asked(NODE2));
    }

    /**
     * A leg whose socket reports the transaction a prepare opens in autocommit, until the
     * statement's run, a commit or a rollback ends it.
     */
    private final class Leg extends FakePhysicalConnection {
        private final Endpoint node;
        private final List<String> asked = new ArrayList<String>();
        private final Socket socket = new Socket();
        private boolean closed;

        Leg(final Endpoint node) {
            this.node = node;
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
            if ("commit".equals(name) || "rollback".equals(name)) {
                socket.transactionOpen(false);
                return null;
            }
            if ("prepareStatement".equals(name)) {
                asked.add(name);
                socket.transactionOpen(true);
                return statement();
            }
            return null;
        }

        private Object statement() {
            return Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    new InvocationHandler() {
                        private boolean statementClosed;

                        public Object invoke(final Object p, final Method m, final Object[] a) {
                            String name = m.getName();
                            if ("isClosed".equals(name)) {
                                return Boolean.valueOf(closed || statementClosed);
                            }
                            if (name.startsWith("execute")) {
                                asked.add("execute");
                                socket.transactionOpen(false); // autocommit ends it with the run
                            }
                            if ("close".equals(name) && !closed && !statementClosed) {
                                statementClosed = true;
                                asked.add("close");
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

    /** Carries a transaction report; the rest is four methods. */
    private static final class Socket extends UConnection {

        private Socket() {
            casIp = "rw";
            casPort = 33000;
            casId = 1;
            casProcessId = 1;
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
