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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * A logical commit/rollback must end the transaction on <b>both</b> physical legs, even when the
 * first one fails.
 *
 * <p>The RW leg used to run unguarded and first, so anything it threw skipped the RO leg. A skipped
 * rollback is the costly one: the read leg keeps an open transaction/snapshot on the slave, pinning
 * old versions there for the session's life. It also matters more since the pool health probe
 * became structural — a session whose RW leg is unreachable is no longer discarded, so it returns
 * to the pool carrying whatever the RO leg was left holding.
 */
public class SessionPhysicalConnManagerTxLegsTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final String RW_ID = "rwtx:34000";
    private static final String RO_ID = "rotx:34001";

    private final AtomicInteger roCommits = new AtomicInteger();
    private final AtomicInteger roRollbacks = new AtomicInteger();
    private final AtomicInteger roAutoCommits = new AtomicInteger();
    private final AtomicInteger rwAutoCommits = new AtomicInteger();
    private final SQLException roBoom = new SQLException("read leg is gone");
    private volatile boolean roFails;
    private final SQLException rwBoom = new SQLException("write leg is gone");
    private volatile boolean rwFails;

    @Test
    public void commitReachesTheReadLegWhenTheWriteLegFails() throws SQLException {
        SessionPhysicalConnManager mgr = bound();
        try {
            rwFails = true;
            try {
                mgr.commitPhyTx();
                fail("the write leg's failure must still reach the caller");
            } catch (SQLException propagated) {
                assertSame("the caller sees the write failure", rwBoom, propagated);
            }
            assertEquals("read leg committed", 1, roCommits.get());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void rollbackReachesTheReadLegWhenTheWriteLegFails() throws SQLException {
        SessionPhysicalConnManager mgr = bound();
        try {
            rwFails = true;
            try {
                mgr.rollbackPhyTx();
                fail("the write leg's failure must still reach the caller");
            } catch (SQLException propagated) {
                assertSame("the caller sees the write failure", rwBoom, propagated);
            }
            assertEquals("read leg rolled back", 1, roRollbacks.get());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    /**
     * {@code applyPhyAutoCommit} used to walk every {@code connsByEpId} entry and stop at the first
     * failure, so whichever leg the map happened to yield second was skipped. It now targets the
     * two session legs explicitly and, like commit/rollback, reaches the second one even when the
     * first failed: autocommit decides whether a leg starts a transaction on its next statement, so
     * leaving a leg on the old mode is the same class of leftover as a skipped rollback.
     *
     * <p>Both legs are made to fail on purpose: that is the one assertion the old hash order cannot
     * satisfy either way round. Under the old loop the first failure ended the walk, so whichever
     * leg came second stayed at zero attempts.
     */
    @Test
    public void autoCommitReachesBothLegsWhenTheWriteLegFails() throws SQLException {
        SessionPhysicalConnManager mgr = bound();
        try {
            rwAutoCommits.set(0);
            roAutoCommits.set(0);
            rwFails = true;
            roFails = true;
            try {
                mgr.applyPhyAutoCommit(false);
                fail("the write leg's failure must still reach the caller");
            } catch (SQLException propagated) {
                assertSame("the caller sees the write failure", rwBoom, propagated);
            }
            assertEquals("write leg attempted", 1, rwAutoCommits.get());
            assertEquals("read leg attempted", 1, roAutoCommits.get());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void bothLegsEndNormallyWhenNothingFails() throws SQLException {
        SessionPhysicalConnManager mgr = bound();
        try {
            mgr.commitPhyTx();
            mgr.rollbackPhyTx();
            assertEquals(1, roCommits.get());
            assertEquals(1, roRollbacks.get());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    private SessionPhysicalConnManager bound() throws SQLException {
        Endpoint rw = new Endpoint("rwtx", 34000);
        Endpoint ro = new Endpoint("rotx", 34001);
        EndpointTopology topo =
                new EndpointTopology(rw, Arrays.asList(ro), Collections.<Endpoint>emptyList());

        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        return url.contains(RO_ID) ? roConn() : rwConn();
                    }
                };

        SessionPhysicalConnManager mgr =
                new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), config, factory);
        mgr.bindSession(rw, ro, topo);
        return mgr;
    }

    private Connection rwConn() {
        return proxy(
                new InvocationHandler() {
                    public Object invoke(
                            final Object self, final Method method, final Object[] args)
                            throws SQLException {
                        String name = method.getName();
                        if ("setAutoCommit".equals(name)) {
                            rwAutoCommits.incrementAndGet();
                        }
                        if (rwFails
                                && ("commit".equals(name)
                                        || "rollback".equals(name)
                                        || "setAutoCommit".equals(name))) {
                            throw rwBoom;
                        }
                        return defaultReturn(method);
                    }
                });
    }

    private Connection roConn() {
        return proxy(
                new InvocationHandler() {
                    public Object invoke(
                            final Object self, final Method method, final Object[] args)
                            throws SQLException {
                        String name = method.getName();
                        if ("commit".equals(name)) {
                            roCommits.incrementAndGet();
                            return null;
                        }
                        if ("rollback".equals(name)) {
                            roRollbacks.incrementAndGet();
                            return null;
                        }
                        if ("setAutoCommit".equals(name)) {
                            roAutoCommits.incrementAndGet();
                            if (roFails) {
                                throw roBoom;
                            }
                            return null;
                        }
                        return defaultReturn(method);
                    }
                });
    }

    private static Connection proxy(final InvocationHandler handler) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(), new Class[] {Connection.class}, handler);
    }

    private static Object defaultReturn(final Method method) {
        Class<?> type = method.getReturnType();
        if (Boolean.TYPE.equals(type)) {
            return Boolean.FALSE;
        }
        if (Integer.TYPE.equals(type)) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
