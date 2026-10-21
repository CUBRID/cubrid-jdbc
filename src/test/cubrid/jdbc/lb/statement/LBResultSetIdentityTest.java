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

import static org.junit.Assert.assertSame;

import cubrid.jdbc.driver.CUBRIDResultSet;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.TestConfigHelper;
import cubrid.jdbc.lb.route.Router;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.Before;
import org.junit.Test;

/**
 * LB hands back the physical driver's {@code ResultSet} object itself — it never wraps or replaces
 * it.
 *
 * <p>This is what lets an existing application keep code that casts to the driver's own metadata
 * classes ({@code (CUBRIDResultSetMetaData) rs.getMetaData()}) and survive the move to a
 * loadbalance:// URL unchanged: the metadata comes from the real {@code CUBRIDResultSet} that the
 * bound leg produced, so the cast still matches. Wrapping a ResultSet — for per-row metrics, for
 * fetch instrumentation — would break every such application at runtime with a ClassCastException
 * that no compiler flags and that only shows up on the code path that casts. These tests pin the
 * identity so that change cannot land unnoticed.
 */
public final class LBResultSetIdentityTest {

    private LoadBalanceConnection connection;
    private ResultSet physicalRs;

    @Before
    public void setUp() {
        // A real CUBRIDResultSet rather than a proxy: identity is only half the contract now — the
        // object must also name the logical statement as its producer, and only the real class
        // carries that field. Constructing it with no socket is safe here; nothing reads one.
        connection = new LoadBalanceConnection(TestConfigHelper.emptyConfig());
        physicalRs = new CUBRIDResultSet(null, null);
    }

    @Test
    public void statementExecuteQueryReturnsThePhysicalResultSet() throws Exception {
        LBStatement statement = new LBStatement(connection);
        statement.setStatementProvider(stmtProvider());

        assertSame(
                "LB must not wrap the physical ResultSet",
                physicalRs,
                statement.executeQuery("SELECT * FROM t"));
    }

    @Test
    public void statementGetResultSetReturnsThePhysicalResultSet() throws Exception {
        LBStatement statement = new LBStatement(connection);
        statement.setStatementProvider(stmtProvider());

        statement.execute("SELECT * FROM t");

        assertSame("LB must not wrap the physical ResultSet", physicalRs, statement.getResultSet());
    }

    @Test
    public void statementGetGeneratedKeysReturnsThePhysicalResultSet() throws Exception {
        LBStatement statement = new LBStatement(connection);
        statement.setStatementProvider(stmtProvider());

        statement.executeUpdate("INSERT INTO t(id) VALUES (1)");

        assertSame(
                "LB must not wrap the physical ResultSet",
                physicalRs,
                statement.getGeneratedKeys());
    }

    @Test
    public void preparedExecuteQueryReturnsThePhysicalResultSet() throws Exception {
        LBPreparedStatement statement = new LBPreparedStatement(connection, "SELECT * FROM t");
        statement.setPsProvider(psProvider());

        assertSame("LB must not wrap the physical ResultSet", physicalRs, statement.executeQuery());
    }

    /**
     * The other half of the identity contract. LB hands out the physical ResultSet, so without a
     * redirect {@code rs.getStatement()} would name the physical statement — an object the
     * application never asked for, whose {@code getConnection()} is a physical connection. {@code
     * rs.getStatement().getConnection().commit()} would then reach one leg only, with no exception
     * anywhere. That is worse than a ClassCastException, which at least fails loudly.
     */
    @Test
    public void statementExecuteQueryResultSetNamesTheLogicalStatement() throws Exception {
        LBStatement statement = new LBStatement(connection);
        statement.setStatementProvider(stmtProvider());

        ResultSet rs = statement.executeQuery("SELECT * FROM t");

        assertSame(physicalRs, rs);
        assertSame(
                "rs.getStatement() must be the statement the application called",
                statement,
                rs.getStatement());
        assertSame(
                "and through it, the logical connection — not a physical leg",
                connection,
                rs.getStatement().getConnection());
    }

    @Test
    public void statementGetResultSetNamesTheLogicalStatement() throws Exception {
        LBStatement statement = new LBStatement(connection);
        statement.setStatementProvider(stmtProvider());

        statement.execute("SELECT * FROM t");

        assertSame(statement, statement.getResultSet().getStatement());
    }

    @Test
    public void statementGetGeneratedKeysNamesTheLogicalStatement() throws Exception {
        LBStatement statement = new LBStatement(connection);
        statement.setStatementProvider(stmtProvider());

        statement.executeUpdate("INSERT INTO t(id) VALUES (1)");

        assertSame(statement, statement.getGeneratedKeys().getStatement());
    }

    /**
     * The prepared statement must name <em>itself</em>, not its {@code LBStatement} delegate. The
     * delegate serves the Statement-level half of the API and is an implementation detail the
     * application never received, so a result set naming it would be as wrong as naming the
     * physical statement.
     */
    @Test
    public void preparedResultSetNamesThePreparedStatementNotTheDelegate() throws Exception {
        LBPreparedStatement statement = new LBPreparedStatement(connection, "SELECT * FROM t");
        statement.setPsProvider(psProvider());

        ResultSet rs = statement.executeQuery();

        assertSame(physicalRs, rs);
        assertSame(statement, rs.getStatement());
        assertSame(statement, statement.getResultSet().getStatement());
        assertSame(connection, rs.getStatement().getConnection());
    }

    /**
     * With no redirect set, the physical producer is reported — so the change is additive and a
     * classic (non-LB) connection is unaffected.
     */
    @Test
    public void withoutARedirectThePhysicalProducerIsReported() throws Exception {
        CUBRIDResultSet rs = new CUBRIDResultSet(null, null);

        assertSame(null, rs.getStatement());

        LBStatement statement = new LBStatement(connection);
        rs.setReportedStatement(statement);
        assertSame(statement, rs.getStatement());

        rs.setReportedStatement(null);
        assertSame(null, rs.getStatement());
    }

    /**
     * A physical ResultSet that is not a {@code CUBRIDResultSet} — a test double, another driver
     * behind a delegating layer — carries no producer field, so the stamp must degrade to a no-op
     * rather than fail. Identity is still the contract.
     */
    @Test
    public void aNonCubridResultSetIsReturnedUnchanged() throws Exception {
        physicalRs = resultSetProxy();
        LBStatement statement = new LBStatement(connection);
        statement.setStatementProvider(stmtProvider());

        assertSame(physicalRs, statement.executeQuery("SELECT * FROM t"));
    }

    private LBStatement.PhysicalStmtProvider stmtProvider() {
        return new LBStatement.PhysicalStmtProvider() {
            public Statement getStatement(final Router.RouteTarget target, final String rawSql) {
                return (Statement) proxy(new Class[] {Statement.class}, statementHandler());
            }
        };
    }

    private LBPreparedStatement.PhysicalPsProvider psProvider() {
        return new LBPreparedStatement.PhysicalPsProvider() {
            public PreparedStatement getPreparedStatement(
                    final Router.RouteTarget target, final String rawSql) {
                return (PreparedStatement)
                        proxy(new Class[] {PreparedStatement.class}, statementHandler());
            }

            public void prepareOnBoundReadEndpoint(final String rawSql) {}

            public void prepareOnWriteEndpoint(final String rawSql) {}

            public void closePrepStmts() {}
        };
    }

    private InvocationHandler statementHandler() {
        return new InvocationHandler() {
            public Object invoke(final Object self, final Method method, final Object[] args)
                    throws SQLException {
                String name = method.getName();
                if ("executeQuery".equals(name)
                        || "getResultSet".equals(name)
                        || "getGeneratedKeys".equals(name)) {
                    return physicalRs;
                }
                if ("execute".equals(name)) {
                    return Boolean.TRUE;
                }
                if ("executeUpdate".equals(name)) {
                    return Integer.valueOf(1);
                }
                return defaultReturn(method);
            }
        };
    }

    private ResultSet resultSetProxy() {
        return (ResultSet)
                proxy(
                        new Class[] {ResultSet.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object self, final Method method, final Object[] args) {
                                return defaultReturn(method);
                            }
                        });
    }

    private static Object proxy(final Class<?>[] interfaces, final InvocationHandler handler) {
        return Proxy.newProxyInstance(
                LBResultSetIdentityTest.class.getClassLoader(), interfaces, handler);
    }

    private static Object defaultReturn(final Method method) {
        Class<?> type = method.getReturnType();
        if (Boolean.TYPE.equals(type)) {
            return Boolean.FALSE;
        }
        if (Integer.TYPE.equals(type)) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
