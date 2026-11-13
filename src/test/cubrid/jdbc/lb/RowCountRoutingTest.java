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

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.route.Router.RouteTarget;
import cubrid.jdbc.lb.state.SharedSelectorState;
import cubrid.jdbc.lb.statement.FakePhysicalPreparedStatement;
import cubrid.jdbc.lb.statement.FakePhysicalStatement;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import cubrid.jdbc.lb.statement.LBStatement;
import cubrid.jdbc.lb.statement.StatementTestAccess;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * {@code ROW_COUNT()} answers for the statement before it in its own server session, and each leg
 * is a session of its own. Core runs both statements in one session, so LB sends the read to the
 * leg the previous statement ran on.
 */
public final class RowCountRoutingTest {
    private static final RouteTarget RW = RouteTarget.TO_READ_WRITE;
    private static final RouteTarget RO = RouteTarget.TO_READ_ONLY;

    @Test
    public void rowCountRunsWhereThePreviousStatementRan() throws SQLException {
        final List<RouteTarget> targets = new ArrayList<RouteTarget>();
        LBStatement statement = recordingStatement(createBoundSessionConnection(), targets);

        statement.executeUpdate("UPDATE t SET v = v + 1");
        statement.executeQuery("SELECT ROW_COUNT()");
        statement.executeQuery("SELECT v FROM t");
        statement.executeQuery("SELECT ROW_COUNT()");
        statement.executeInsert("INSERT INTO t VALUES (1)");
        statement.executeQuery("SELECT ROW_COUNT()");

        assertEquals(Arrays.asList(RW, RW, RO, RO, RW, RW), targets);
    }

    /** Core leaves {@code ROW_COUNT()} as it was when a statement fails before it runs. */
    @Test
    public void aFailedStatementDoesNotMoveRowCount() throws SQLException {
        final List<RouteTarget> targets = new ArrayList<RouteTarget>();
        LBStatement statement = recordingStatement(createBoundSessionConnection(), targets);

        statement.executeUpdate("UPDATE t SET v = v + 1");
        try {
            statement.executeQuery("SELECT nope FROM t");
            fail("expected the read leg's semantic error");
        } catch (SQLException expected) {
        }
        statement.executeQuery("SELECT ROW_COUNT()");

        assertEquals(Arrays.asList(RW, RO, RW), targets);
    }

    @Test
    public void preparedRowCountFollowsThePreviousStatementAtEachExecution() throws SQLException {
        LoadBalanceConnection connection = createBoundSessionConnection();
        LBStatement statement = recordingStatement(connection, new ArrayList<RouteTarget>());
        LBPreparedStatement rowCount = new LBPreparedStatement(connection, "SELECT ROW_COUNT()");
        final List<RouteTarget> targets = new ArrayList<RouteTarget>();
        rowCount.setPsProvider(
                new LBPreparedStatement.PhysicalPsProvider() {
                    public PreparedStatement getPreparedStatement(
                            final RouteTarget target, final String rawSql) {
                        targets.add(target);
                        return new FakePhysicalPreparedStatement();
                    }

                    public void prepareOnBoundReadEndpoint(final String rawSql) {}

                    public void prepareOnWriteEndpoint(final String rawSql) {}

                    public void closePrepStmts() {}
                });

        statement.executeUpdate("UPDATE t SET v = v + 1");
        rowCount.executeQuery();
        statement.executeQuery("SELECT v FROM t");
        rowCount.executeQuery();

        assertEquals(Arrays.asList(RW, RO), targets);
    }

    private static LBStatement recordingStatement(
            final LoadBalanceConnection connection, final List<RouteTarget> targets)
            throws SQLException {
        LBStatement statement = (LBStatement) connection.createStatement();
        StatementTestAccess.setStatementProvider(
                statement,
                new LBStatement.PhysicalStmtProvider() {
                    public Statement getStatement(final RouteTarget target, final String rawSql) {
                        targets.add(target);
                        return new FakePhysicalStatement() {
                            @Override
                            protected Object dispatch(final String name, final Object[] args)
                                    throws SQLException {
                                if (rawSql.contains("nope")) {
                                    throw new SQLException("nope is not defined", null, -494);
                                }
                                return null;
                            }
                        };
                    }
                });
        return statement;
    }

    private static LoadBalanceConnection createBoundSessionConnection() throws SQLException {
        LoadBalanceConnection connection = new LoadBalanceConnection(LbTestSettings.defaults());
        connection.setConnectionManager(new SimpleEndpointConnManager());
        connection.setSharedSelectorState(new SharedSelectorState());
        List<Endpoint> ro = new ArrayList<Endpoint>();
        ro.add(new Endpoint("ro1", 33000));
        connection.initSessionBindings(new EndpointTopology(new Endpoint("rw", 33000), ro, null));
        return connection;
    }
}
