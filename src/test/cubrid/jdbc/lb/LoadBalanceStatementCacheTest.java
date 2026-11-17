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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.After;
import org.junit.Test;

/**
 * {@code usePreparedStmtCache} on a loadbalance URL: LB keeps the one cache a core connection has,
 * reading the options itself, and the legs prepare without a cache of their own.
 */
public final class LoadBalanceStatementCacheTest {

    private static final String URL =
            "jdbc:cubrid:loadbalance://rw:33000,ro:1:33001/testdb:::?readWeight=slave:1";
    private static final String CACHE = "&usePreparedStmtCache=true&preparedStmtCacheSize=2";
    private static final String S = "SELECT a FROM t WHERE p = ?";

    private final List<String> prepared = Collections.synchronizedList(new ArrayList<String>());
    private LoadBalanceConnection conn;

    @After
    public void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    public void theSameSqlWhileCachedIsTheSameStatement() throws SQLException {
        conn = open(URL + CACHE);

        assertSame(conn.prepareStatement(S), conn.prepareStatement(S));
    }

    /** What the cache is for: a statement served from it is not prepared on the server again. */
    @Test
    public void aStatementServedFromTheCacheIsPreparedOnce() throws SQLException {
        conn = open(URL + CACHE);
        for (int i = 0; i < 5; i++) {
            PreparedStatement ps = conn.prepareStatement(S);
            ps.setInt(1, i);
            ps.executeQuery();
            ps.close();
        }

        assertEquals(Arrays.asList("ro|" + S), prepared);
    }

    /** As core: the evicted statement stays open, and the SQL gets a statement of its own. */
    @Test
    public void aStatementTheCacheEvictedIsNotReturnedAgain() throws SQLException {
        conn = open(URL + CACHE);
        PreparedStatement first = conn.prepareStatement(S);
        conn.prepareStatement("SELECT b FROM t");
        conn.prepareStatement("SELECT c FROM t");

        assertNotSame(first, conn.prepareStatement(S));
        assertFalse(first.isClosed());
    }

    @Test
    public void withoutTheOptionEachPrepareIsANewStatement() throws SQLException {
        conn = open(URL);

        assertNotSame(conn.prepareStatement(S), conn.prepareStatement(S));
    }

    private LoadBalanceConnection open(final String url) throws SQLException {
        LoadBalanceConnection c = new LoadBalanceConnection(LbTestSettings.fromUrl(url));
        c.setConnectionManager(
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        new Properties(),
                        LbTestSettings.fromUrl(url),
                        new Legs()));
        c.setSharedSelectorState(new SharedSelectorState());
        c.initSessionBindings(
                new EndpointTopology(
                        new Endpoint("rw", 33000),
                        Arrays.asList(new Endpoint("ro", 33001)),
                        Collections.<Endpoint>emptyList()));
        return c;
    }

    /** Legs with no statement cache of their own, recording each physical prepare. */
    private final class Legs implements JdbcConnectionFactory {
        public Connection getConnection(final String url, final Properties info) {
            final String host = url.indexOf(":ro:") >= 0 ? "ro" : "rw";
            return proxy(
                    Connection.class,
                    new InvocationHandler() {
                        private boolean closed;

                        public Object invoke(final Object p, final Method m, final Object[] a) {
                            final String name = m.getName();
                            if ("close".equals(name)) {
                                closed = true;
                                return null;
                            }
                            if ("isClosed".equals(name)) {
                                return Boolean.valueOf(closed);
                            }
                            if ("isValid".equals(name) || "getAutoCommit".equals(name)) {
                                return Boolean.TRUE;
                            }
                            if ("prepareStatement".equals(name)) {
                                prepared.add(host + "|" + a[0]);
                                return statement();
                            }
                            return defaultValue(m.getReturnType());
                        }
                    });
        }

        private PreparedStatement statement() {
            return proxy(
                    PreparedStatement.class,
                    new InvocationHandler() {
                        private boolean closed;

                        public Object invoke(final Object p, final Method m, final Object[] a) {
                            final String name = m.getName();
                            if ("close".equals(name)) {
                                closed = true;
                                return null;
                            }
                            if ("isClosed".equals(name)) {
                                return Boolean.valueOf(closed);
                            }
                            if ("executeQuery".equals(name)) {
                                return proxy(
                                        ResultSet.class,
                                        new InvocationHandler() {
                                            public Object invoke(
                                                    final Object rs,
                                                    final Method rm,
                                                    final Object[] ra) {
                                                return defaultValue(rm.getReturnType());
                                            }
                                        });
                            }
                            return defaultValue(m.getReturnType());
                        }
                    });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final InvocationHandler handler) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        new InvocationHandler() {
                            public Object invoke(final Object p, final Method m, final Object[] a)
                                    throws Throwable {
                                if ("hashCode".equals(m.getName())) {
                                    return Integer.valueOf(System.identityHashCode(p));
                                }
                                if ("equals".equals(m.getName())) {
                                    return Boolean.valueOf(p == a[0]);
                                }
                                return handler.invoke(p, m, a);
                            }
                        });
    }

    private static Object defaultValue(final Class<?> type) {
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
