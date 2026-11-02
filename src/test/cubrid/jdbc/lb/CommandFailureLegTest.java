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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A malformed reply (-21019) is core's to report: core reconnects for one itself where it does and
 * keeps its connection and session, so the leg stays. A lost connection still rebinds it.
 */
public final class CommandFailureLegTest {

    private static final Endpoint NODE1 = new Endpoint("cmdnode1", 33000);
    private static final Endpoint NODE2 = new Endpoint("cmdnode2", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("cmdnode2", 33002);

    private final List<Leg> opened = new ArrayList<Leg>();
    private int failure;
    private LoadBalanceConnection conn;

    @Before
    public void setUp() throws SQLException {
        EndpointTopology topo =
                new EndpointTopology(
                        Arrays.asList(NODE1, NODE2),
                        Arrays.asList(NODE2_RO),
                        Collections.<Endpoint>emptyList());
        LoadBalanceSettings settings = LbTestSettings.forTopology(topo);
        conn = new LoadBalanceConnection(settings);
        conn.setConnectionManager(
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        new Properties(),
                        settings,
                        new JdbcConnectionFactory() {
                            public Connection getConnection(final String url, final Properties i) {
                                Leg leg = new Leg();
                                opened.add(leg);
                                return leg;
                            }
                        }));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topo);
    }

    @After
    public void tearDown() throws SQLException {
        conn.close();
        UUnreachableHostList.getInstance().remove(NODE1.getId());
        UUnreachableHostList.getInstance().remove(NODE2.getId());
    }

    @Test
    public void aMalformedReplyToALobCommandKeepsTheLeg() throws SQLException {
        failure = UErrorCode.ER_ILLEGAL_DATA_SIZE;
        final int legs = opened.size();

        assertFails(
                new Call() {
                    public void run() throws SQLException {
                        conn.lobNew(99);
                    }
                });

        assertEquals(NODE1, conn.getCurrentRwEndpoint());
        assertEquals(legs, opened.size());
    }

    @Test
    public void aMalformedReplyToPrepareCallKeepsTheLeg() throws SQLException {
        failure = UErrorCode.ER_ILLEGAL_DATA_SIZE;
        final int legs = opened.size();

        assertFails(
                new Call() {
                    public void run() throws SQLException {
                        conn.prepareCall("CALL p()");
                    }
                });

        assertEquals(NODE1, conn.getCurrentRwEndpoint());
        assertEquals(legs, opened.size());
    }

    @Test
    public void aLostConnectionOnALobCommandRebindsTheLeg() throws SQLException {
        failure = UErrorCode.ER_COMMUNICATION;
        final int legs = opened.size();

        assertFails(
                new Call() {
                    public void run() throws SQLException {
                        conn.lobNew(99);
                    }
                });

        assertTrue(opened.size() > legs);
    }

    private interface Call {
        void run() throws SQLException;
    }

    private void assertFails(final Call call) {
        try {
            call.run();
            fail("the command's failure must reach the caller");
        } catch (SQLException expected) {
            assertEquals(failure, expected.getErrorCode());
        }
    }

    /** The first leg fails lobNew and prepareCall with the test's code. */
    private final class Leg extends FakePhysicalConnection {
        private final UConnection socket =
                new UConnection() {
                    public void endTransaction(final boolean type) {}

                    protected void closeInternal() {}

                    public void setAutoCommit(final boolean autoCommit) {}

                    public boolean getAutoCommit() {
                        return true;
                    }
                };

        @Override
        public UConnection getUConnection() {
            return socket;
        }

        @Override
        protected Object dispatch(final String name, final Object[] args) throws SQLException {
            if (("lobNew".equals(name) || "prepareCall".equals(name))
                    && this == opened.get(0)
                    && failure != 0) {
                throw new SQLException("command failed", null, failure);
            }
            return null;
        }
    }
}
