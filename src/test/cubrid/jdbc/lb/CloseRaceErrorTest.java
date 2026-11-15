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
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.CUBRIDJDBCErrorCode;
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.route.Router.RouteTarget;
import cubrid.jdbc.lb.state.SharedSelectorState;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import cubrid.jdbc.lb.statement.LBStatement;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import org.junit.Before;
import org.junit.Test;

/**
 * A statement that passed its closed check when another thread closed the connection reports the
 * closed connection, as core does, not the legs close() released.
 */
public final class CloseRaceErrorTest {

    private static final Endpoint NODE1 = new Endpoint("racenode1", 33000);
    private static final Endpoint NODE2 = new Endpoint("racenode2", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("racenode2", 33002);

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
                                return new Leg();
                            }
                        }));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topo);
    }

    @Test
    public void aStatementRacingCloseGetsTheClosedConnectionError() throws Exception {
        LBStatement.PhysicalStmtProvider provider =
                conn.createStatementProvider(
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY,
                        ResultSet.HOLD_CURSORS_OVER_COMMIT);
        conn.close();

        for (RouteTarget target : RouteTarget.values()) {
            try {
                provider.getStatement(target, "SELECT 1");
                fail(target + ": expected the closed connection");
            } catch (SQLException e) {
                assertEquals(
                        target.toString(), CUBRIDJDBCErrorCode.connection_closed, e.getErrorCode());
            }
        }
    }

    @Test
    public void aPreparedStatementRacingCloseGetsTheClosedConnectionError() throws Exception {
        Method create =
                LoadBalanceConnection.class.getDeclaredMethod(
                        "createPsProvider", int.class, int.class, int.class, int.class);
        create.setAccessible(true);
        LBPreparedStatement.PhysicalPsProvider provider =
                (LBPreparedStatement.PhysicalPsProvider)
                        create.invoke(
                                conn,
                                Statement.NO_GENERATED_KEYS,
                                ResultSet.TYPE_FORWARD_ONLY,
                                ResultSet.CONCUR_READ_ONLY,
                                ResultSet.HOLD_CURSORS_OVER_COMMIT);
        conn.close();

        for (RouteTarget target : RouteTarget.values()) {
            try {
                provider.getPreparedStatement(target, "SELECT 1");
                fail(target + ": expected the closed connection");
            } catch (SQLException e) {
                assertEquals(
                        target.toString(), CUBRIDJDBCErrorCode.connection_closed, e.getErrorCode());
            }
        }
    }

    private static final class Leg extends FakePhysicalConnection {
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
    }
}
