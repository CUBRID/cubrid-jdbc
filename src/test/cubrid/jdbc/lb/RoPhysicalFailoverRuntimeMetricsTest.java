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

import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import cubrid.jdbc.lb.state.RuntimeMetrics;
import cubrid.jdbc.lb.state.SharedSelectorState;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

public final class RoPhysicalFailoverRuntimeMetricsTest {

    @Test
    public void readOnlyExecuteRecordsRoPhysicalFailoverMetrics() throws SQLException {
        final List<String> openOrder = new ArrayList<String>();
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (url.contains("ro1")) {
                            openOrder.add("ro");
                            throw new SQLException("ro unreachable");
                        }
                        openOrder.add("rw");
                        return newConnectionProxy();
                    }
                };

        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        cfg.setProperty(LoadBalanceSettings.KEY_RO_PHYSICAL_FAILOVER_TO_RW, "true");
        LoadBalanceSettings loadBalanceSettings = LoadBalanceSettings.of(cfg);

        SessionPhysicalConnManager pool =
                new SessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        new Properties(),
                        loadBalanceSettings,
                        factory);

        LoadBalanceConnection conn = new LoadBalanceConnection(loadBalanceSettings);
        conn.getRuntimeMetrics().setEnabled(true);
        conn.setConnectionManager(pool);
        conn.setSharedSelectorState(new SharedSelectorState());
        List<Endpoint> roList = new ArrayList<Endpoint>();
        roList.add(new Endpoint("ro1", 33000));
        EndpointTopology topology = new EndpointTopology(new Endpoint("rw", 33000), roList, null);
        conn.initSessionBindings(topology);

        assertTrue(openOrder.contains("rw"));
        assertTrue(openOrder.contains("ro"));

        LBPreparedStatement ps = (LBPreparedStatement) conn.prepareStatement("SELECT 1");
        ps.executeQuery();

        RuntimeMetrics metrics = conn.getRuntimeMetrics();
        assertTrue(metrics.getRwFallbackCount() >= 1);
        boolean found = false;
        for (String event : metrics.getEvents()) {
            if (event.contains("fallbackReason=RO_PHYSICAL_FAILOVER")) {
                found = true;
                break;
            }
        }
        assertTrue(found);
        conn.close();
    }

    private static Connection newConnectionProxy() {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("close".equals(method.getName())) {
                                    return null;
                                }
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.FALSE;
                                }
                                if ("prepareStatement".equals(method.getName())) {
                                    return createPreparedStatementProxy();
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

    private static PreparedStatement createPreparedStatementProxy() {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("executeQuery".equals(method.getName())) {
                                    return Proxy.newProxyInstance(
                                            ResultSet.class.getClassLoader(),
                                            new Class[] {ResultSet.class},
                                            new InvocationHandler() {
                                                public Object invoke(
                                                        final Object p,
                                                        final Method m,
                                                        final Object[] a) {
                                                    return null;
                                                }
                                            });
                                }
                                if ("close".equals(method.getName())) {
                                    return null;
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                return null;
                            }
                        });
    }
}
