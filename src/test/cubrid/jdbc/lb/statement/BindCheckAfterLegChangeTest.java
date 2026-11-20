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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.lb.FakePhysicalConnection;
import cubrid.jdbc.lb.LbTestAccess;
import cubrid.jdbc.lb.LbTestSettings;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Core checks a setter and {@code addBatch()} in the statement it prepared, whatever the state of
 * its connection. LB checks them on a prepared physical statement, and replacing a leg closes that
 * one: the first setter after it prepares again, so the row core refuses is refused here too.
 */
public final class BindCheckAfterLegChangeTest {
    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final String INSERT = "INSERT INTO t(a, b) VALUES (?, ?)";
    private static final int ER_SEMANTIC = -493;

    private Broker broker;
    private LoadBalanceConnection conn;

    @Before
    public void setUp() throws SQLException {
        final Endpoint rw = new Endpoint("rw", 33000);
        final Endpoint ro = new Endpoint("ro", 33001);
        broker = new Broker();
        conn = LbTestAccess.newLoadBalanceConnection(LbTestSettings.forBinding(rw, ro));
        LbTestAccess.setConnectionManager(
                conn,
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), LbTestSettings.forBinding(rw, ro), broker));
        LbTestAccess.setSharedSelectorState(conn, new SharedSelectorState());
        LbTestAccess.initSessionBindings(
                conn,
                new EndpointTopology(rw, Arrays.asList(ro), Collections.<Endpoint>emptyList()));
    }

    @After
    public void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    public void addBatchAfterTheLegWasReplacedRefusesAnUnboundRow() throws SQLException {
        PreparedStatement ps = conn.prepareStatement(INSERT);
        broker.closePrepared();

        ps.setInt(1, 1);
        try {
            ps.addBatch();
            fail("core refuses a row with an unbound parameter");
        } catch (SQLException refused) {
            assertEquals(UErrorCode.ER_NOT_BIND, refused.getErrorCode());
        }
        ps.setInt(1, 2);
        ps.setInt(2, 2);
        ps.addBatch();

        assertArrayEquals(new int[] {1}, ps.executeBatch());
        assertEquals("prepared again once, by the first setter", 2, broker.prepares);
    }

    /** A setter reports what core's setter does; the execution prepares and reports the rest. */
    @Test
    public void prepareThatFailsAtASetterIsLeftToTheExecution() throws SQLException {
        PreparedStatement ps = conn.prepareStatement(INSERT);
        broker.closePrepared();
        broker.prepareFailure = ER_SEMANTIC;

        ps.setInt(1, 1);
        ps.setInt(2, 1);
        ps.addBatch();

        assertEquals("tried once, not at every setter", 2, broker.prepares);
        try {
            ps.executeBatch();
            fail("the execution reports the prepare failure");
        } catch (SQLException reported) {
            assertEquals(ER_SEMANTIC, reported.getErrorCode());
        }
    }

    /** The legs' broker: it counts prepares and can close what was prepared, as a new leg does. */
    private static final class Broker implements JdbcConnectionFactory {
        private final List<Prepared> open = new ArrayList<Prepared>();
        int prepares;
        int prepareFailure;

        public Connection getConnection(final String url, final Properties info) {
            return new FakePhysicalConnection() {
                @Override
                protected Object dispatch(final String name, final Object[] args)
                        throws SQLException {
                    if ("getAutoCommit".equals(name)) {
                        return Boolean.TRUE;
                    }
                    if (!"prepareStatement".equals(name)) {
                        return null;
                    }
                    prepares++;
                    if (prepareFailure != 0) {
                        throw new SQLException("prepare failed", null, prepareFailure);
                    }
                    final Prepared prepared = new Prepared((String) args[0]);
                    open.add(prepared);
                    return prepared;
                }
            };
        }

        void closePrepared() {
            for (Prepared each : open) {
                each.closed = true;
            }
            open.clear();
        }
    }

    /** Refuses a batch row with a parameter left unbound, as core's statement does. */
    private static final class Prepared extends FakePhysicalPreparedStatement {
        private final int parameters;
        private final Set<Object> bound = new HashSet<Object>();
        private int rows;
        boolean closed;

        Prepared(final String sql) {
            parameters = sql.length() - sql.replace("?", "").length();
        }

        @Override
        protected Object dispatch(final String name, final Object[] args) throws SQLException {
            if ("isClosed".equals(name)) {
                return Boolean.valueOf(closed);
            }
            if ("clearParameters".equals(name)) {
                bound.clear();
            } else if (name.startsWith("set") && args.length >= 2) {
                bound.add(args[0]);
            } else if ("addBatch".equals(name)) {
                if (bound.size() < parameters) {
                    throw new SQLException("not all bound", null, UErrorCode.ER_NOT_BIND);
                }
                bound.clear();
                rows++;
            } else if ("clearBatch".equals(name)) {
                rows = 0;
            } else if ("executeBatch".equals(name)) {
                final int[] counts = new int[rows];
                Arrays.fill(counts, 1);
                rows = 0;
                return counts;
            }
            return null;
        }
    }
}
