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

package cubrid.jdbc.lb.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.route.Router;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

public final class SessionPreparedStatementBehaviorTest {

    @Test
    public void assertSessionReadPrepareUsesBoundReadEndpointOnly() throws Exception {
        LBPreparedStatement statement =
                createSessionPreparedStatement("SELECT * FROM t WHERE id = ?");
        SessionPrepareProvider provider = new SessionPrepareProvider();
        statement.setPsProvider(provider);

        statement.prepareEagerly();

        // READ prepares only the bound read endpoint, not RW.
        assertTrue(provider.calls.contains("prepare:RO:SELECT * FROM t WHERE id = ?"));
        assertFalse(provider.calls.contains("prepare:RW:SELECT * FROM t WHERE id = ?"));
    }

    @Test
    public void assertReadMetaDataUsesBoundReadEndpoint() throws Exception {
        LBPreparedStatement statement = createSessionPreparedStatement("SELECT * FROM t");
        SessionPrepareProvider provider = new SessionPrepareProvider();
        statement.setPsProvider(provider);

        ResultSetMetaData resultSetMetaData = statement.getMetaData();

        // READ getMetaData resolves to the bound read endpoint, not RW-fixed.
        assertNotNull(resultSetMetaData);
        assertEquals(Router.RouteTarget.TO_READ_ONLY, provider.lastGetTarget);
        assertTrue(provider.calls.contains("get:TO_READ_ONLY:SELECT * FROM t"));
    }

    @Test
    public void assertWriteMetaDataUsesReadWriteEndpoint() throws Exception {
        LBPreparedStatement statement =
                createSessionPreparedStatement("INSERT INTO t(id) VALUES (?)");
        SessionPrepareProvider provider = new SessionPrepareProvider();
        statement.setPsProvider(provider);

        ResultSetMetaData resultSetMetaData = statement.getMetaData();

        // WRITE statements still resolve to RW.
        assertNotNull(resultSetMetaData);
        assertEquals(Router.RouteTarget.TO_READ_WRITE, provider.lastGetTarget);
        assertTrue(provider.calls.contains("get:TO_READ_WRITE:INSERT INTO t(id) VALUES (?)"));
    }

    @Test
    public void assertReadParameterMetaDataUsesBoundReadEndpoint() throws Exception {
        LBPreparedStatement statement =
                createSessionPreparedStatement("SELECT * FROM t WHERE id = ?");
        SessionPrepareProvider provider = new SessionPrepareProvider();
        statement.setPsProvider(provider);

        statement.getParameterMetaData();

        // READ getParameterMetaData resolves to the bound read endpoint — no eager RW
        // prepare side effect (cleanup follows from T2's physicalPsForMetadata change).
        assertEquals(Router.RouteTarget.TO_READ_ONLY, provider.lastGetTarget);
        assertFalse(provider.calls.contains("get:TO_READ_WRITE:SELECT * FROM t WHERE id = ?"));
    }

    private static LBPreparedStatement createSessionPreparedStatement(final String sql)
            throws Exception {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceConnection connection =
                new LoadBalanceConnection(LoadBalanceSettings.of(properties));
        return (LBPreparedStatement) connection.prepareStatement(sql);
    }

    static final class SessionPrepareProvider implements LBPreparedStatement.PhysicalPsProvider {

        final List<String> calls = new ArrayList<String>();

        Router.RouteTarget lastGetTarget;

        public PreparedStatement getPreparedStatement(
                final Router.RouteTarget target, final String rawSql) {
            lastGetTarget = target;
            calls.add("get:" + target + ":" + rawSql);
            return (PreparedStatement)
                    Proxy.newProxyInstance(
                            PreparedStatement.class.getClassLoader(),
                            new Class[] {PreparedStatement.class},
                            new InvocationHandler() {
                                public Object invoke(Object proxy, Method method, Object[] args)
                                        throws Throwable {
                                    if ("getMetaData".equals(method.getName())) {
                                        return Proxy.newProxyInstance(
                                                ResultSetMetaData.class.getClassLoader(),
                                                new Class[] {ResultSetMetaData.class},
                                                new InvocationHandler() {
                                                    public Object invoke(
                                                            Object p, Method m, Object[] a)
                                                            throws Throwable {
                                                        if (Integer.TYPE.equals(
                                                                m.getReturnType())) {
                                                            return Integer.valueOf(0);
                                                        }
                                                        if (Boolean.TYPE.equals(
                                                                m.getReturnType())) {
                                                            return Boolean.FALSE;
                                                        }
                                                        return null;
                                                    }
                                                });
                                    }
                                    if (Boolean.TYPE.equals(method.getReturnType())) {
                                        return Boolean.FALSE;
                                    }
                                    if (Integer.TYPE.equals(method.getReturnType())) {
                                        return Integer.valueOf(0);
                                    }
                                    return null;
                                }
                            });
        }

        public void prepareOnBoundReadEndpoint(final String rawSql) throws java.sql.SQLException {
            // READ: bound read endpoint only — no eager RW leg (dual prepare removed).
            calls.add("prepare:RO:" + rawSql);
        }

        public void prepareOnWriteEndpoint(final String rawSql) throws java.sql.SQLException {
            calls.add("prepare:RW:" + rawSql);
        }

        public void closePrepStmts() throws java.sql.SQLException {
            calls.add("closePreparedStatements");
        }
    }
}
