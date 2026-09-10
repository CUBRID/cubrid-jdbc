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

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

/**
 * The physical prepared-statement cache is keyed by {@code endpointId|owner|sql}, and that is the
 * whole of LB's re-prepare mechanism: there is no "prepared endpoint" recorded on a logical
 * statement and no explicit re-prepare path. A statement whose route changes (a transaction pins a
 * read to RW, a failover moves the read leg) simply misses the cache and is prepared on the
 * endpoint it is about to run on.
 *
 * <p>Which means a refactor that drops the endpoint from the key — a plausible "let's share the
 * cache" change — would break it silently: the statement would execute on a connection that never
 * prepared it. These tests pin the property so that cannot land unnoticed (LB-Pending-Issues
 * ISSUE-3).
 */
public final class PrepCacheEndpointScopeTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final Endpoint RW = new Endpoint("rwnode", 34000);
    private static final Endpoint RO = new Endpoint("ronode", 34001);
    private static final String SQL = "SELECT a FROM t";

    @Test
    public void theSameSqlOnTwoEndpointsPreparesOnBoth() throws SQLException {
        final List<String> prepared = new ArrayList<String>();
        SessionPhysicalConnManager mgr = manager(prepared);
        mgr.bindSession(RW, RO, topology());

        PreparedStatement onRead = mgr.prepareStatement(RO, SQL);
        PreparedStatement onWrite = mgr.prepareStatement(RW, SQL);

        assertEquals(Arrays.asList("ronode", "rwnode"), prepared);
        assertNotSame(
                "one physical statement per endpoint: sharing one would execute a statement on a"
                        + " connection that never prepared it",
                onRead,
                onWrite);
        mgr.releasePhysicalConnections();
    }

    @Test
    public void theSameSqlOnOneEndpointIsPreparedOnce() throws SQLException {
        final List<String> prepared = new ArrayList<String>();
        SessionPhysicalConnManager mgr = manager(prepared);
        mgr.bindSession(RW, RO, topology());

        PreparedStatement first = mgr.prepareStatement(RO, SQL);
        PreparedStatement second = mgr.prepareStatement(RO, SQL);

        assertEquals(Arrays.asList("ronode"), prepared);
        assertSame("re-executing the same statement must hit the cache", first, second);
        mgr.releasePhysicalConnections();
    }

    /* ===== harness ===== */

    private static EndpointTopology topology() {
        return new EndpointTopology(RW, Arrays.asList(RO), Collections.<Endpoint>emptyList());
    }

    private static SessionPhysicalConnManager manager(final List<String> prepared) {
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        return connectionProxy(hostOf(url), prepared);
                    }
                };

        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");

        return new SessionPhysicalConnManager(
                LOGICAL_URL, new Properties(), LoadBalanceSettings.of(cfg), factory);
    }

    private static String hostOf(final String url) {
        int start = url.indexOf("jdbc:cubrid:") + "jdbc:cubrid:".length();
        int end = url.indexOf(':', start);

        return end < 0 ? url.substring(start) : url.substring(start, end);
    }

    private static Connection connectionProxy(final String host, final List<String> prepared) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("prepareStatement".equals(method.getName())) {
                                    prepared.add(host);

                                    return preparedStatementProxy();
                                }
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.FALSE;
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

    private static PreparedStatement preparedStatementProxy() {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
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
