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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.state.SharedSelectorStateRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end wiring of the URI {@code loadbalance://} URL into a {@link LoadBalanceConnection}:
 * write fixed to master RW, reads bound once by readWeight, master read reuses RW (roOnRw), single
 * host → RW-only, and physical per-broker URLs built in the classic colon-delimited form.
 */
public class LoadBalanceConnectionUriUrlTest {

    private final List<String> requestedUrls = new ArrayList<String>();
    private final List<Properties> requestedProps = new ArrayList<Properties>();

    private final JdbcConnectionFactory factory =
            new JdbcConnectionFactory() {
                public Connection getConnection(final String url, final Properties info) {
                    requestedUrls.add(url);
                    requestedProps.add(info);
                    return connectionProxy();
                }
            };

    @Before
    public void setUp() {
        SharedSelectorStateRegistry.clearForTests();
        requestedUrls.clear();
        requestedProps.clear();
    }

    private LoadBalanceConnection openLb(final String url) throws SQLException {
        LoadBalanceSettings config = LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));
        return (LoadBalanceConnection)
                LoadBalanceConnection.openFromSettings(url, new Properties(), config, factory);
    }

    @Test
    public void writeFixedToMasterAndReadBoundToSlave() throws SQLException {
        LoadBalanceConnection conn =
                openLb(
                        "jdbc:cubrid:loadbalance://node1,node2/db"
                                + "?rwPort=33000&roPort=33002&readWeight=slave:1,master:0");

        assertEquals("node1:33000", conn.getCurrentRwEndpoint().getId());
        assertEquals("node2:33002", conn.getCurrentRoEndpoint().getId());

        // Physical per-broker URLs are the classic colon-delimited form (no LB topology options).
        assertTrue(
                requestedUrls.toString(), requestedUrls.contains("jdbc:cubrid:node1:33000:db:::"));
        assertTrue(
                requestedUrls.toString(), requestedUrls.contains("jdbc:cubrid:node2:33002:db:::"));
    }

    @Test
    public void masterReadReusesRwAsSinglePhysicalConnection() throws SQLException {
        LoadBalanceConnection conn =
                openLb(
                        "jdbc:cubrid:loadbalance://node1,node2/db"
                                + "?rwPort=33000&roPort=33002&readWeight=master:1,slave:0");

        assertEquals("node1:33000", conn.getCurrentRwEndpoint().getId());
        assertEquals("node1:33000", conn.getCurrentRoEndpoint().getId());
        assertSame(
                conn.getConnectionManager().getPhyConn(conn.getCurrentRwEndpoint()),
                conn.getConnectionManager().getPhyConn(conn.getCurrentRoEndpoint()));
        // Only the master RW connection is opened — no separate RO/SO connection.
        assertEquals(requestedUrls.toString(), 1, requestedUrls.size());
    }

    @Test
    public void singleHostBindsRwOnly() throws SQLException {
        LoadBalanceConnection conn =
                openLb("jdbc:cubrid:loadbalance://node1/db?rwPort=33000&roPort=33002");

        assertEquals("node1:33000", conn.getCurrentRwEndpoint().getId());
        assertEquals("node1:33000", conn.getCurrentRoEndpoint().getId());
        assertEquals(requestedUrls.toString(), 1, requestedUrls.size());
    }

    @Test
    public void credentialsAndCommonOptionsPropagateToPhysicalUrl() throws SQLException {
        openLb(
                "jdbc:cubrid:loadbalance://node1/db:dba:secret:"
                        + "?rwPort=33000&roPort=33002&charSet=utf-8");

        // User and common options propagate into the physical URL; the password does NOT — it is
        // carried in the connection Properties so it cannot leak via logged/echoed URL strings.
        assertEquals(
                requestedUrls.toString(),
                "jdbc:cubrid:node1:33000:db:dba::?charSet=utf-8",
                requestedUrls.get(0));
        assertEquals("secret", requestedProps.get(0).getProperty("password"));
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
