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
import static org.junit.Assert.assertSame;

import cubrid.jdbc.lb.SessionLeg;
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
import org.junit.Test;

public class SessionPhysicalConnManagerRoleBindTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";

    @Test
    public void masterReadReusesRwAsSinglePhysicalConnection() throws SQLException {
        Endpoint masterRw = new Endpoint("node1", 33000);
        EndpointTopology topo =
                new EndpointTopology(
                        masterRw,
                        Collections.<Endpoint>emptyList(),
                        Collections.<Endpoint>emptyList());
        SessionPhysicalConnManager mgr = newManager();

        try {
            // read target = master: the read endpoint is the master RW broker itself.
            mgr.bindSessionWithReadTarget(masterRw, masterRw, true, topo);

            assertEquals(masterRw, mgr.getSessionEndpoint(SessionLeg.RW));
            assertEquals(masterRw, mgr.getSessionEndpoint(SessionLeg.RO));
            assertSame(
                    "master read must reuse the single RW physical connection",
                    mgr.getPhyConn(masterRw),
                    mgr.getPhyConn(mgr.getSessionEndpoint(SessionLeg.RO)));
            // Not a failover — intentional master-read reuse reports no RO fallback.
            assertEquals("NONE", mgr.getRoFallbackReason());
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    @Test
    public void slaveReadOpensSeparatePhysicalConnection() throws SQLException {
        Endpoint masterRw = new Endpoint("node1", 33000);
        Endpoint slaveRo = new Endpoint("node2", 33002);
        EndpointTopology topo =
                new EndpointTopology(
                        masterRw, Arrays.asList(slaveRo), Collections.<Endpoint>emptyList());
        SessionPhysicalConnManager mgr = newManager();

        try {
            mgr.bindSessionWithReadTarget(masterRw, slaveRo, false, topo);

            assertEquals(masterRw, mgr.getSessionEndpoint(SessionLeg.RW));
            assertEquals(slaveRo, mgr.getSessionEndpoint(SessionLeg.RO));
            assertNotSame(
                    "slave read must use a separate RO physical connection",
                    mgr.getPhyConn(masterRw),
                    mgr.getPhyConn(slaveRo));
        } finally {
            mgr.releasePhysicalConnections();
        }
    }

    private static SessionPhysicalConnManager newManager() {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
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
