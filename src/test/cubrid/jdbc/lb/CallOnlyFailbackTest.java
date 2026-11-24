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
import cubrid.jdbc.lb.metrics.MetricsTestAccess;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
 * A connection in autocommit that runs only CALLs returns to the primary in core: the next run
 * first commits what the last one left open, and core leaves the alternative host at that commit.
 * Here a CALL takes the step every statement takes before it runs, where the failback is attempted,
 * and what would hold that failback - the transaction of the last run, or of a prepare - is out of
 * its way first.
 */
public final class CallOnlyFailbackTest {

    private static final Endpoint NODE1 = new Endpoint("cofnode1", 33000);
    private static final Endpoint NODE2 = new Endpoint("cofnode2", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("cofnode2", 33002);
    private static final String CALL = "CALL p(?, ?)";

    private final Set<String> down = new HashSet<String>();
    private final List<Leg> legs = new ArrayList<Leg>();
    private LoadBalanceConnection conn;

    private void open(final boolean masterDown) throws SQLException {
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
        if (masterDown) {
            down.add(NODE1.getId());
            UUnreachableHostList.getInstance().add(NODE1.getId());
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
        UUnreachableHostList.getInstance().remove(NODE1.getId());
        UUnreachableHostList.getInstance().remove(NODE2.getId());
    }

    /** What the write leg to {@code node} was asked, in order. */
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
    public void aCallRunAttemptsTheFailback() throws SQLException {
        open(true);
        CallableStatement cs = conn.prepareCall(CALL);
        conn.commit(); // ends what the prepare opened
        masterComesBack();

        cs.execute();

        assertEquals(NODE1, conn.getCurrentRwEndpoint());
        assertEquals(Arrays.asList("prepareCall", "commit"), asked(NODE2));
        assertEquals(Arrays.asList("prepareCall", "execute"), asked(NODE1));
    }

    @Test
    public void aStatementRunAgainFirstCommitsWhatItsLastRunLeftOpen() throws SQLException {
        open(true);
        CallableStatement cs = conn.prepareCall(CALL);
        cs.execute();
        masterComesBack();

        cs.execute();

        assertEquals(Arrays.asList("prepareCall", "execute", "commit"), asked(NODE2));
        assertEquals(NODE1, conn.getCurrentRwEndpoint());
        assertEquals(Arrays.asList("prepareCall", "execute"), asked(NODE1));
    }

    @Test
    public void prepareCallAttemptsTheFailbackBeforeItPrepares() throws SQLException {
        open(true);
        masterComesBack();

        conn.prepareCall(CALL);

        assertEquals(NODE1, conn.getCurrentRwEndpoint());
        assertEquals(Collections.<String>emptyList(), asked(NODE2));
        assertEquals(Arrays.asList("prepareCall"), asked(NODE1));
    }

    @Test
    public void aStatementPreparedRunAndClosedEachTimeReturnsAtTheNextPrepare()
            throws SQLException {
        open(true);
        CallableStatement first = conn.prepareCall(CALL);
        first.execute();
        first.close();
        masterComesBack();

        CallableStatement second = conn.prepareCall(CALL);
        second.execute();

        assertEquals(Arrays.asList("prepareCall", "execute", "close"), asked(NODE2));
        assertEquals(Arrays.asList("prepareCall", "execute"), asked(NODE1));
    }

    @Test
    public void theFirstRunOfAStatementCommitsNothing() throws SQLException {
        open(true);
        CallableStatement cs = conn.prepareCall(CALL);
        masterComesBack();

        cs.execute();

        // What the prepare opened holds the failback, as an open transaction does.
        assertEquals(Arrays.asList("prepareCall", "execute"), asked(NODE2));
        assertEquals(NODE2, conn.getCurrentRwEndpoint());
    }

    @Test
    public void noCommitIsSentWhileTheWriteLegIsOnTheMaster() throws SQLException {
        open(false);
        CallableStatement cs = conn.prepareCall(CALL);

        cs.execute();
        cs.execute();

        assertEquals(Arrays.asList("prepareCall", "execute", "execute"), asked(NODE1));
    }

    @Test
    public void noCommitIsSentInManualCommit() throws SQLException {
        open(true);
        conn.setAutoCommit(false);
        CallableStatement cs = conn.prepareCall(CALL);
        cs.execute();
        masterComesBack();

        cs.execute();

        assertEquals(Arrays.asList("prepareCall", "execute", "execute"), asked(NODE2));
        assertEquals(NODE2, conn.getCurrentRwEndpoint());
    }

    @Test
    public void aCallRunIsCountedOnTheWriteEndpoint() throws SQLException {
        open(false);
        conn.getRuntimeMetrics().setEnabled(true);
        CallableStatement cs = conn.prepareCall(CALL);
        final long before =
                MetricsTestAccess.getEndpointCount(conn.getRuntimeMetrics(), NODE1.getId());

        cs.execute();

        assertEquals(
                before + 1,
                MetricsTestAccess.getEndpointCount(conn.getRuntimeMetrics(), NODE1.getId()));
    }

    /**
     * A write leg whose socket reports the transaction a prepare or a CALL leaves open in
     * autocommit, until a commit or the statement's close ends it.
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
                asked.add(name);
                socket.transactionOpen(false);
                return null;
            }
            if ("createStatement".equals(name)) {
                return statement(Statement.class);
            }
            if ("prepareCall".equals(name)) {
                asked.add(name);
                socket.transactionOpen(true);
                return statement(CallableStatement.class);
            }
            return null;
        }

        private Object statement(final Class<?> type) {
            final boolean call = type == CallableStatement.class;
            return Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[] {type},
                    new InvocationHandler() {
                        public Object invoke(final Object p, final Method m, final Object[] a) {
                            String name = m.getName();
                            if ("isClosed".equals(name)) {
                                return Boolean.valueOf(closed);
                            }
                            if (call && name.startsWith("execute")) {
                                asked.add("execute");
                                socket.transactionOpen(true);
                            }
                            if (call && "close".equals(name) && !closed) {
                                asked.add("close");
                                socket.transactionOpen(false); // core commits at close
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
