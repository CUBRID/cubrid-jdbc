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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.TestConfigHelper;
import cubrid.jdbc.lb.route.Router;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public final class LBStatementAffinityCommandTest {

    private LoadBalanceConnection connection;

    @Before
    public void setUp() {
        connection = new LoadBalanceConnection(TestConfigHelper.emptyConfig());
    }

    @Test
    public void assertGetGeneratedKeysUsesLastPhysicalStatementAffinity() throws Exception {
        LBStatement statement = new LBStatement(connection);
        AffinityContext context = new AffinityContext();
        statement.setStatementProvider(context);

        statement.executeUpdate("INSERT INTO t(id) VALUES (1)");
        statement.executeUpdate("INSERT INTO t(id) VALUES (2)");
        statement.getGeneratedKeys();

        assertTrue(context.calls.contains("stmt#2:getGeneratedKeys"));
    }

    @Test
    public void assertGetMoreResultsUsesLastPhysicalStatementAffinity() throws Exception {
        LBStatement statement = new LBStatement(connection);
        AffinityContext context = new AffinityContext();
        context.nextExecuteResult = true;
        context.nextMoreResultsResult = true;
        statement.setStatementProvider(context);

        statement.execute("SELECT 1");
        boolean actual = statement.getMoreResults();

        assertTrue(actual);
        assertTrue(context.calls.contains("stmt#1:getMoreResults"));
    }

    @Test
    public void assertGetQueryplanWithoutSqlUsesLastPhysicalStatementAffinity() throws Exception {
        LBStatement statement = new LBStatement(connection);
        AffinityContext context = new AffinityContext();
        statement.setStatementProvider(context);

        String bySql = statement.getQueryplan("SELECT 1");
        String byLast = statement.getQueryplan();

        assertEquals("qp:stmt#1:sql", bySql);
        assertEquals("qp:stmt#1:last", byLast);
    }

    @Test
    public void assertResultSetOperationsStayOnReturnedPhysicalResultSet() throws Exception {
        LBStatement statement = new LBStatement(connection);
        AffinityContext context = new AffinityContext();
        statement.setStatementProvider(context);

        statement.executeQuery("SELECT * FROM t");
        ResultSet resultSet = statement.getResultSet();
        resultSet.next();
        resultSet.updateRow();

        assertTrue(context.calls.contains("rs#1:next"));
        assertTrue(context.calls.contains("rs#1:updateRow"));
    }

    @Test
    public void assertCloseClosesAllCreatedPhysicalStatements() throws Exception {
        LBStatement statement = new LBStatement(connection);
        AffinityContext context = new AffinityContext();
        statement.setStatementProvider(context);

        statement.executeQuery("SELECT 1");
        statement.executeUpdate("UPDATE t SET v = 1");
        statement.close();

        assertEquals(2, context.closedStatements);
    }

    private static final class AffinityContext implements LBStatement.PhysicalStmtProvider {

        private final List<String> calls = new ArrayList<String>();

        private int statementSeq;

        private int closedStatements;

        private boolean nextExecuteResult;

        private boolean nextMoreResultsResult;

        public Statement getStatement(final Router.RouteTarget target, final String rawSql)
                throws SQLException {
            final int statementId = ++statementSeq;
            return new FakePhysicalStatement() {
                private ResultSet resultSet = createResultSetProxy(statementId);

                @Override
                protected Object dispatch(final String name, final Object[] args)
                        throws SQLException {
                    if ("executeQuery".equals(name)) {
                        calls.add("stmt#" + statementId + ":executeQuery");
                        resultSet = createResultSetProxy(statementId);
                        return resultSet;
                    }
                    if ("executeUpdate".equals(name)) {
                        calls.add("stmt#" + statementId + ":executeUpdate");
                        return Integer.valueOf(1);
                    }
                    if ("execute".equals(name)) {
                        calls.add("stmt#" + statementId + ":execute");
                        resultSet = createResultSetProxy(statementId);
                        return Boolean.valueOf(nextExecuteResult);
                    }
                    if ("getResultSet".equals(name)) {
                        calls.add("stmt#" + statementId + ":getResultSet");
                        return resultSet;
                    }
                    if ("getGeneratedKeys".equals(name)) {
                        calls.add("stmt#" + statementId + ":getGeneratedKeys");
                        return createResultSetProxy(statementId);
                    }
                    if ("getMoreResults".equals(name)) {
                        calls.add("stmt#" + statementId + ":getMoreResults");
                        return Boolean.valueOf(nextMoreResultsResult);
                    }
                    if ("getQueryplan".equals(name) && args != null && args.length == 1) {
                        calls.add("stmt#" + statementId + ":getQueryplan(sql)");
                        return "qp:stmt#" + statementId + ":sql";
                    }
                    if ("getQueryplan".equals(name) && (args == null || args.length == 0)) {
                        calls.add("stmt#" + statementId + ":getQueryplan()");
                        return "qp:stmt#" + statementId + ":last";
                    }
                    if ("close".equals(name)) {
                        closedStatements++;
                        calls.add("stmt#" + statementId + ":close");
                        return null;
                    }
                    return null;
                }
            };
        }

        private ResultSet createResultSetProxy(final int statementId) {
            return (ResultSet)
                    Proxy.newProxyInstance(
                            LBStatementAffinityCommandTest.class.getClassLoader(),
                            new Class[] {ResultSet.class},
                            new InvocationHandler() {
                                public Object invoke(
                                        final Object proxy,
                                        final Method method,
                                        final Object[] args)
                                        throws Throwable {
                                    String name = method.getName();
                                    if ("next".equals(name)) {
                                        calls.add("rs#" + statementId + ":next");
                                        return Boolean.FALSE;
                                    }
                                    if ("updateRow".equals(name)) {
                                        calls.add("rs#" + statementId + ":updateRow");
                                        return null;
                                    }
                                    if ("close".equals(name)) {
                                        calls.add("rs#" + statementId + ":close");
                                        return null;
                                    }
                                    if (Boolean.TYPE.equals(method.getReturnType())) {
                                        return Boolean.FALSE;
                                    }
                                    if (Integer.TYPE.equals(method.getReturnType())) {
                                        return Integer.valueOf(0);
                                    }
                                    if (Long.TYPE.equals(method.getReturnType())) {
                                        return Long.valueOf(0L);
                                    }
                                    return null;
                                }
                            });
        }
    }
}
