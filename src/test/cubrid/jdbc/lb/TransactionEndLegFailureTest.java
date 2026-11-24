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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Core ends a transaction on its one connection, so commit() fails only when that connection does.
 * The write leg holds the session's transaction; a read leg that cannot end its part is dropped
 * instead of failing a commit that has happened.
 */
public final class TransactionEndLegFailureTest {

    private static final Endpoint RW = new Endpoint("rwhost", 33000);
    private static final Endpoint RO = new Endpoint("rohost", 33001);

    private final List<Leg> opened = new ArrayList<Leg>();
    private boolean roDead;
    private LoadBalanceConnection conn;

    @Before
    public void setUp() throws SQLException {
        LoadBalanceSettings settings = LbTestSettings.forBinding(RW, RO);
        conn = new LoadBalanceConnection(settings);
        conn.setConnectionManager(
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        new Properties(),
                        settings,
                        new JdbcConnectionFactory() {
                            public Connection getConnection(final String url, final Properties i) {
                                Leg leg = new Leg(url.contains("rohost"));
                                opened.add(leg);
                                return leg.proxy;
                            }
                        }));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(
                new EndpointTopology(RW, Arrays.asList(RO), Collections.<Endpoint>emptyList()));
        conn.setAutoCommit(false);
        conn.createStatement().executeUpdate("INSERT INTO t VALUES (1)");
        roDead = true;
    }

    @After
    public void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    public void aCommitTheWriteLegMadeSucceedsWhenTheReadLegCannotEndItsPart() throws SQLException {
        conn.commit();

        assertTheWriteLegStaysAndTheReadLegIsDropped();
    }

    @Test
    public void aRollbackTheWriteLegMadeSucceedsWhenTheReadLegCannotEndItsPart()
            throws SQLException {
        conn.rollback();

        assertTheWriteLegStaysAndTheReadLegIsDropped();
    }

    private void assertTheWriteLegStaysAndTheReadLegIsDropped() {
        assertEquals(RW, conn.getCurrentRwEndpoint());
        assertEquals(2, opened.size());
        assertFalse(opened.get(0).closed);
        assertTrue(opened.get(1).closed);
    }

    /** A physical leg; the read one fails to end a transaction once its broker is gone. */
    private final class Leg implements InvocationHandler {
        private final boolean readLeg;
        private final Connection proxy;
        private boolean closed;

        private Leg(final boolean readLeg) {
            this.readLeg = readLeg;
            proxy =
                    (Connection)
                            Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    this);
        }

        public Object invoke(final Object p, final Method m, final Object[] a) throws SQLException {
            String name = m.getName();
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return Boolean.valueOf(closed);
            }
            if (("commit".equals(name) || "rollback".equals(name)) && readLeg && roDead) {
                throw new SQLException("broker gone", null, UErrorCode.ER_COMMUNICATION);
            }
            if ("createStatement".equals(name)) {
                return Proxy.newProxyInstance(
                        Statement.class.getClassLoader(),
                        new Class<?>[] {Statement.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object p2, final Method m2, final Object[] a2) {
                                return zero(m2.getReturnType());
                            }
                        });
            }
            return zero(m.getReturnType());
        }
    }

    private static Object zero(final Class<?> type) {
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
