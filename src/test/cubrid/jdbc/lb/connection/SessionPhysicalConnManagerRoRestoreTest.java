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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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
import org.junit.Test;

public class SessionPhysicalConnManagerRoRestoreTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final String RW_ID = "rwlazy:34000";
    private static final String RO_ID = "rolazy:34001";

    @Test
    public void restoresRoWhenEndpointRecovers() throws SQLException {
        Endpoint rw = new Endpoint("rwlazy", 34000);
        Endpoint ro = new Endpoint("rolazy", 34001);
        EndpointTopology topo =
                new EndpointTopology(rw, Arrays.asList(ro), Collections.<Endpoint>emptyList());

        final AtomicBoolean roUp = new AtomicBoolean(false);
        SessionPhysicalConnManager mgr = newManager(roUp);

        try {
            // Bind while RO is down -> reads fall back to the RW physical connection.
            mgr.bindSession(rw, ro, topo);
            assertEquals(
                    SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON,
                    mgr.getRoFallbackReason());
            assertSame(
                    "RO role should share the RW connection during fallback",
                    mgr.getPhyConn(rw),
                    mgr.getPhyConn(ro));

            // RO endpoint is still unreachable -> no restoration.
            assertNull(mgr.restoreRoIfRecovered());
            assertEquals(
                    SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON,
                    mgr.getRoFallbackReason());

            // Simulate broker recovery: RO connectable again and cleared from the unreachable list
            // (BrokerHealthCheck does the latter in production).
            roUp.set(true);
            UUnreachableHostList.getInstance().remove(RO_ID);

            Endpoint restored = mgr.restoreRoIfRecovered();
            assertEquals(ro, restored);
            assertEquals("NONE", mgr.getRoFallbackReason());
            assertNotSame(
                    "RO should now use a dedicated physical connection",
                    mgr.getPhyConn(rw),
                    mgr.getPhyConn(ro));
        } finally {
            mgr.releasePhysicalConnections();
            UUnreachableHostList.getInstance().remove(RW_ID);
            UUnreachableHostList.getInstance().remove(RO_ID);
        }
    }

    @Test
    public void restoreIsNoOpWhenNotInFallback() throws SQLException {
        Endpoint rw = new Endpoint("rwlazy", 34000);
        Endpoint ro = new Endpoint("rolazy", 34001);
        EndpointTopology topo =
                new EndpointTopology(rw, Arrays.asList(ro), Collections.<Endpoint>emptyList());

        SessionPhysicalConnManager mgr = newManager(new AtomicBoolean(true));
        try {
            mgr.bindSession(rw, ro, topo);
            assertEquals("NONE", mgr.getRoFallbackReason());
            assertNull(mgr.restoreRoIfRecovered());
        } finally {
            mgr.releasePhysicalConnections();
            UUnreachableHostList.getInstance().remove(RW_ID);
            UUnreachableHostList.getInstance().remove(RO_ID);
        }
    }

    private static SessionPhysicalConnManager newManager(final AtomicBoolean roUp) {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (url.contains("rolazy:34001") && !roUp.get()) {
                            throw new SQLException("ro down", null, UErrorCode.ER_COMMUNICATION);
                        }
                        return connectionProxy();
                    }
                };

        return new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), config, factory);
    }

    private static Connection connectionProxy() {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                String name = method.getName();
                                if ("isClosed".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                if ("close".equals(name) || "setAutoCommit".equals(name)) {
                                    return null;
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                if (rt.equals(Integer.TYPE)) {
                                    return Integer.valueOf(0);
                                }
                                return null;
                            }
                        });
    }
}
