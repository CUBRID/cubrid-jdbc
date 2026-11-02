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
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Core completes every statement of its one connection when a transaction ends there. This
 * session's statements are spread over two legs, so a leg's commit or rollback runs core's own on
 * the other legs too, when core has that end as well.
 */
public final class LegTransactionEndTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";

    private final Leg rwLeg = new Leg("rw", 33000);
    private final Leg roLeg = new Leg("ro1", 33001);
    private SessionPhysicalConnManager manager;
    private LoadBalanceConnection conn;

    @Before
    public void setUp() throws SQLException {
        Endpoint rw = new Endpoint("rw", 33000);
        Endpoint ro1 = new Endpoint("ro1", 33001);
        EndpointTopology topo =
                new EndpointTopology(rw, Arrays.asList(ro1), Collections.<Endpoint>emptyList());
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        return url.contains("ro1:33001") ? roLeg : rwLeg;
                    }
                };
        LoadBalanceSettings settings = LbTestSettings.forTopology(topo);
        conn = new LoadBalanceConnection(settings);
        manager =
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), settings, factory);
        conn.setConnectionManager(manager);
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topo);
    }

    @After
    public void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    public void theWriteLegEndingATransactionEndsItOnTheReadLegToo() {
        conn.legTransactionEnding(rwLeg, true);
        conn.legTransactionEnding(rwLeg, false);

        assertEquals(Arrays.asList("commit", "rollback"), roLeg.ends);
        assertEquals(Collections.<String>emptyList(), rwLeg.ends);
    }

    /** A read that completes commits in core, which closes the write leg's cursors as well. */
    @Test
    public void aReadLegCommitEndsTheTransactionOnTheWriteLegToo() {
        conn.legTransactionEnding(roLeg, true);

        assertEquals(Arrays.asList("commit"), rwLeg.ends);
    }

    /** LB may run the read again elsewhere, so its rollback waits for the statement's outcome. */
    @Test
    public void aReadLegRollbackIsPassedOnOnlyWhenItsFailureReachesTheApplication() {
        conn.legTransactionEnding(roLeg, false);
        assertEquals(Collections.<String>emptyList(), rwLeg.ends);
        conn.settleReadLegRollback(false);
        conn.settleReadLegRollback(true);
        assertEquals(Collections.<String>emptyList(), rwLeg.ends);

        conn.legTransactionEnding(roLeg, false);
        conn.settleReadLegRollback(true);

        assertEquals(Arrays.asList("rollback"), rwLeg.ends);
    }

    /** LB opens, closes and ends legs under the manager's lock; core has no such end. */
    @Test
    public void aReadLegCommitLbCausesStaysOnItsLeg() {
        synchronized (manager) {
            conn.legTransactionEnding(roLeg, true);
        }

        assertEquals(Collections.<String>emptyList(), rwLeg.ends);
    }

    /** Waiting could deadlock: the other thread may hold this leg's peer while it waits for it. */
    @Test(timeout = 10000)
    public void anEndIsNotPassedOnWhileAnotherThreadPassesOne() throws Exception {
        final ReentrantLock lock = (ReentrantLock) logicalField("legEndLock");
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Thread other =
                new Thread() {
                    public void run() {
                        lock.lock();
                        try {
                            held.countDown();
                            release.await();
                        } catch (InterruptedException ignored) {
                            // the test is over
                        } finally {
                            lock.unlock();
                        }
                    }
                };
        other.start();
        held.await();

        conn.legTransactionEnding(roLeg, true);
        release.countDown();
        other.join();

        assertEquals(Collections.<String>emptyList(), rwLeg.ends);
    }

    @Test
    public void aReadFailingOnTheReadLegEndsTheWriteLegsTransaction() throws SQLException {
        roLeg.failQueries(-494);
        try {
            conn.createStatement().executeQuery("SELECT no_such FROM t");
            fail("the read leg's failure must reach the caller");
        } catch (SQLException expected) {
            assertEquals(-494, expected.getErrorCode());
        }

        assertEquals(Arrays.asList("SELECT no_such FROM t"), roLeg.queries);
        assertEquals(Arrays.asList("rollback"), rwLeg.ends);
    }

    /** Without autocommit a failure does not roll back, so a read leg's rollback is LB's own. */
    @Test
    public void aReadLegRollbackOutsideAutocommitIsNotHeld() throws SQLException {
        conn.setAutoCommit(false);

        conn.legTransactionEnding(roLeg, false);
        conn.settleReadLegRollback(true);

        assertEquals(Collections.<String>emptyList(), rwLeg.ends);
    }

    /** One held from before, such as the read leg's part of rollback(), is not the statement's. */
    @Test
    public void aRollbackHeldFromBeforeAStatementIsNotPassedOnByIt() throws SQLException {
        conn.legTransactionEnding(roLeg, false);
        rwLeg.failQueries(-670);
        try {
            conn.createStatement().executeQuery("SELECT /*+ TO_RW */ a FROM t");
            fail("the write leg's failure must reach the caller");
        } catch (SQLException expected) {
            assertEquals(-670, expected.getErrorCode());
        }

        assertEquals(Collections.<String>emptyList(), rwLeg.ends);
    }

    /** A read the read-only broker refused runs again on RW, and did not fail in core. */
    @Test
    public void aReadRunAgainOnRwDoesNotEndTheWriteLegsTransaction() throws SQLException {
        roLeg.failQueries(-581);

        conn.createStatement().executeQuery("SELECT a FROM v");

        assertEquals(Arrays.asList("SELECT a FROM v"), roLeg.queries);
        assertEquals(Arrays.asList("SELECT a FROM v"), rwLeg.queries);
        assertEquals(Collections.<String>emptyList(), rwLeg.ends);
    }

    /** Core's close completes a statement whose result is still open, which commits. */
    @Test
    public void closingAPreparedReadWithItsResultOpenEndsTheWriteLegsTransaction()
            throws SQLException {
        conn.prepareStatement("SELECT a FROM t").close();

        assertEquals(Arrays.asList("commit"), rwLeg.ends);
    }

    private Object logicalField(final String name) throws Exception {
        Field field = LoadBalanceConnection.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(conn);
    }

    /** A leg connected to one broker, recording the ends it is told to run. */
    private static final class Leg extends FakePhysicalConnection {

        private final List<String> ends = new ArrayList<String>();
        private final List<String> queries = new ArrayList<String>();
        private int queryFailure;

        private final UConnection socket =
                new UConnection() {
                    public void endTransaction(final boolean type) {}

                    protected void closeInternal() {}

                    public void setAutoCommit(final boolean autoCommit) {}

                    public boolean getAutoCommit() {
                        return true;
                    }
                };

        private Leg(final String host, final int port) {
            socket.casIp = host;
            socket.casPort = port;
        }

        /** Queries fail with {@code code}, after rolling back as core does on a failure. */
        private void failQueries(final int code) {
            queryFailure = code;
        }

        @Override
        public UConnection getUConnection() {
            return socket;
        }

        @Override
        protected Object dispatch(final String name, final Object[] args) {
            if ("commit".equals(name) || "rollback".equals(name)) {
                ends.add(name);
            }
            if ("createStatement".equals(name)) {
                return statement(Statement.class);
            }
            if ("prepareStatement".equals(name)) {
                return statement(PreparedStatement.class);
            }

            return null;
        }

        /**
         * A statement that ends its transaction as core does: a failed query rolls back, and a
         * prepared one closes with its result still open, which commits.
         */
        private Object statement(final Class<?> type) {
            return Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[] {type},
                    new InvocationHandler() {
                        public Object invoke(final Object p, final Method m, final Object[] a)
                                throws SQLException {
                            if ("close".equals(m.getName()) && type == PreparedStatement.class) {
                                owner().legTransactionEnding(Leg.this, true);
                            }
                            if ("executeQuery".equals(m.getName())) {
                                queries.add((String) a[0]);
                                if (queryFailure != 0) {
                                    owner().legTransactionEnding(Leg.this, false);
                                    throw new SQLException("query failed", null, queryFailure);
                                }
                            }
                            return zero(m.getReturnType());
                        }
                    });
        }

        private LoadBalanceConnection owner() {
            return (LoadBalanceConnection) socket.getCUBRIDConnection();
        }

        private static Object zero(final Class<?> type) {
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
