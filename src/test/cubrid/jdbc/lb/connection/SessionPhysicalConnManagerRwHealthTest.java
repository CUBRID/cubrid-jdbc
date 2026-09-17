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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * Verifies the {@code Connection.isValid()} pool health probe: it covers only the mandatory RW
 * physical connection, ignores RO/SO, and is <b>structural</b> — an open socket is reported alive,
 * so the pool discards a session only when it is genuinely unusable, not when the broker has merely
 * reclaimed the CAS behind an idle connection.
 */
public class SessionPhysicalConnManagerRwHealthTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final String RW_ID = "rwhealth:34000";
    private static final String RO_ID = "rohealth:34001";

    private final AtomicBoolean rwValid = new AtomicBoolean(true);
    private final AtomicBoolean rwClosed = new AtomicBoolean(false);
    private final AtomicBoolean rwIsClosedThrows = new AtomicBoolean(false);
    private final AtomicInteger rwIsValidCalls = new AtomicInteger();

    @After
    public void cleanup() {
        UUnreachableHostList.getInstance().remove(RW_ID);
        UUnreachableHostList.getInstance().remove(RO_ID);
    }

    @Test
    public void unboundSessionIsValid() {
        SessionPhysicalConnManager mgr = newManager(new AtomicBoolean(true));
        // No bindSession() yet -> nothing to invalidate, so the pool must not evict it.
        assertTrue(mgr.isBoundRwSocketOpen());
    }

    @Test
    public void boundRwAliveIsValid() throws SQLException {
        SessionPhysicalConnManager mgr = bound(new AtomicBoolean(true));
        try {
            assertTrue(mgr.isBoundRwSocketOpen());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    /**
     * A vendor {@code isValid()} of false must NOT make the pool discard the session, because it
     * does not mean what a pool assumes it means: the broker reclaims an idle CAS beyond {@code
     * MIN_NUM_APPL_SERVER} after {@code TIME_TO_KILL}, and from then on it answers "no CAS matches
     * your (pid, session id)" for a connection the driver will simply reconnect on the next
     * statement. Measured on a live cluster: 25 of 30 idle sessions reported invalid after 45s, a
     * statement on one of them succeeded, and its {@code isValid()} then flipped back to true. The
     * old behaviour turned that into a pool-wide discard-and-rebuild on the first request after any
     * quiet spell.
     */
    @Test
    public void rwReportingInvalidIsStillUsable() throws SQLException {
        SessionPhysicalConnManager mgr = bound(new AtomicBoolean(true));
        try {
            rwValid.set(false); // broker reclaimed the idle CAS; the socket is untouched
            assertTrue(mgr.isBoundRwSocketOpen());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    /**
     * The probe must not even ask. {@code isValid()} costs a fresh TCP connection to the broker per
     * call ({@code BrokerHandler.statusBroker}), and the pool validates on every borrow that
     * follows more than 500ms of idleness — so a 30-connection pool paid 30 extra broker
     * connections on the first request after a pause, for an answer that is then ignored.
     */
    @Test
    public void poolProbeNeverCallsIsValid() throws SQLException {
        SessionPhysicalConnManager mgr = bound(new AtomicBoolean(true));
        try {
            rwIsValidCalls.set(0);
            assertTrue(mgr.isBoundRwSocketOpen());
            assertEquals("isValid() round trips", 0, rwIsValidCalls.get());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void rwThrowingOnIsClosedIsNotValid() throws SQLException {
        SessionPhysicalConnManager mgr = bound(new AtomicBoolean(true));
        try {
            rwIsClosedThrows.set(true);
            assertFalse(mgr.isBoundRwSocketOpen());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void rwClosedIsNotValid() throws SQLException {
        SessionPhysicalConnManager mgr = bound(new AtomicBoolean(true));
        try {
            rwClosed.set(true);
            assertFalse(mgr.isBoundRwSocketOpen());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void roDownDoesNotInvalidateWhenRwAlive() throws SQLException {
        // RO down at bind time -> roOnRw fallback. RW is alive, so the connection stays valid:
        // a down RO must not cause the pool to discard an otherwise-usable connection.
        SessionPhysicalConnManager mgr = bound(new AtomicBoolean(false));
        try {
            assertTrue(mgr.isBoundRwSocketOpen());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    private SessionPhysicalConnManager bound(final AtomicBoolean roUp) throws SQLException {
        Endpoint rw = new Endpoint("rwhealth", 34000);
        Endpoint ro = new Endpoint("rohealth", 34001);
        EndpointTopology topo =
                new EndpointTopology(rw, Arrays.asList(ro), Collections.<Endpoint>emptyList());
        SessionPhysicalConnManager mgr = newManager(roUp);
        mgr.bindSession(rw, ro, topo);
        return mgr;
    }

    private SessionPhysicalConnManager newManager(final AtomicBoolean roUp) {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (url.contains("rohealth:34001")) {
                            if (!roUp.get()) {
                                throw new SQLException(
                                        "ro down", null, UErrorCode.ER_COMMUNICATION);
                            }
                            return aliveConn();
                        }
                        return rwConn();
                    }
                };

        return new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), config, factory);
    }

    private Connection rwConn() {
        return proxy(
                new InvocationHandler() {
                    public Object invoke(
                            final Object self, final Method method, final Object[] args)
                            throws SQLException {
                        String name = method.getName();
                        if ("isClosed".equals(name)) {
                            if (rwIsClosedThrows.get()) {
                                throw new SQLException("cannot tell");
                            }
                            return Boolean.valueOf(rwClosed.get());
                        }
                        if ("isValid".equals(name)) {
                            rwIsValidCalls.incrementAndGet();
                            return Boolean.valueOf(rwValid.get());
                        }
                        return defaultReturn(method);
                    }
                });
    }

    private static Connection aliveConn() {
        return proxy(
                new InvocationHandler() {
                    public Object invoke(
                            final Object self, final Method method, final Object[] args) {
                        String name = method.getName();
                        if ("isClosed".equals(name)) {
                            return Boolean.FALSE;
                        }
                        if ("isValid".equals(name)) {
                            return Boolean.TRUE;
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
