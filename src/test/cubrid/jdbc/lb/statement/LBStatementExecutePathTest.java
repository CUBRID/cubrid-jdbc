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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.TestConfigHelper;
import cubrid.jdbc.lb.route.Router;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public final class LBStatementExecutePathTest {

    private LoadBalanceConnection connection;

    @Before
    public void setUp() {
        connection = new LoadBalanceConnection(TestConfigHelper.emptyConfig());
    }

    @Test
    public void assertExecuteQueryRoutesToReadOnlyForToRoHint() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);

        statement.executeQuery("SELECT /*+ TO_RO */ id FROM t");

        assertEquals(1, context.readOnlyCreateCount);
        assertEquals(0, context.masterCreateCount);
    }

    @Test
    public void assertExecuteQueryRoutesToReadOnlyForReadSql() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);

        ResultSet result = statement.executeQuery("SELECT * FROM t");

        assertNotNull(result);
        assertEquals(1, context.readOnlyCreateCount);
        assertEquals(0, context.masterCreateCount);
        assertTrue(context.calls.contains("executeQuery:SELECT * FROM t"));
    }

    // 02 §M2: re-executing one logical Statement must not accumulate physical
    // statements/ResultSets;
    // each new execute closes the previous physical statement, and close() releases the last.
    @Test
    public void assertRepeatedExecuteClosesPriorPhysicalStatement() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);

        statement.executeQuery("SELECT * FROM t");
        statement.executeQuery("SELECT * FROM t");
        statement.executeQuery("SELECT * FROM t");

        assertEquals("each execute creates one physical statement", 3, context.readOnlyCreateCount);
        assertEquals("each new execute closes the prior physical statement", 2, context.closeCount);

        statement.close();
        assertEquals("close() releases the last physical statement", 3, context.closeCount);
    }

    // 02 §M4: cancel() must reach the physical statement that is currently executing. The physical
    // statement is published BEFORE the execute call, so a cancel arriving mid-execution (here the
    // provider reentrantly cancels during executeQuery) cancels the in-flight statement rather than
    // being a no-op (lastExecStmt is only assigned after the call returns).
    @Test
    public void assertCancelReachesInFlightPhysicalStatement() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        context.cancelTarget = statement;
        statement.setStatementProvider(context);

        statement.executeQuery("SELECT * FROM t");

        assertEquals(
                "cancel during execution reaches the in-flight statement", 1, context.cancelCalls);
    }

    @Test
    public void assertExecuteUpdateRoutesToMasterForWriteSql() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);

        int result = statement.executeUpdate("UPDATE t SET v = 1");

        assertEquals(3, result);
        assertEquals(1, context.masterCreateCount);
        assertEquals(0, context.readOnlyCreateCount);
        assertTrue(context.calls.contains("executeUpdate:UPDATE t SET v = 1"));
    }

    @Test
    public void assertExecuteReturnsPhysicalBooleanResult() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        context.nextExecuteResult = true;
        statement.setStatementProvider(context);

        boolean result = statement.execute("SELECT 1");

        assertTrue(result);
        assertTrue(context.calls.contains("execute:SELECT 1"));
    }

    @Test
    public void assertAddBatchStoresSqlInOrder() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);
        statement.addBatch("SELECT 1");
        statement.addBatch("SELECT 2");

        statement.executeBatch();

        assertEquals(2, context.batchedSql.size());
        assertEquals("SELECT 1", context.batchedSql.get(0));
        assertEquals("SELECT 2", context.batchedSql.get(1));
    }

    @Test
    public void assertExecuteBatchUsesReadOnlyWhenAllReadSql() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);
        statement.addBatch("SELECT 1");
        statement.addBatch("SELECT 2");

        int[] result = statement.executeBatch();

        assertEquals(1, context.readOnlyCreateCount);
        assertEquals(0, context.masterCreateCount);
        assertEquals(2, result.length);
    }

    @Test
    public void assertExecuteBatchUsesMasterWhenAnyWriteSqlExists() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);
        statement.addBatch("SELECT 1");
        statement.addBatch("INSERT INTO t(id) VALUES (1)");

        statement.executeBatch();

        assertEquals(0, context.readOnlyCreateCount);
        assertEquals(1, context.masterCreateCount);
    }

    /**
     * heterogeneous targets in a batch (e.g. RO + RW) must be upgraded to Master because the batch
     * runs over a single physical connection.
     */
    @Test
    public void assertExecuteBatchUpgradesToMasterWhenTargetsMixed() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);
        statement.addBatch("SELECT /*+ TO_RO */ 1 FROM t");
        statement.addBatch("SELECT /*+ TO_RW */ 2 FROM t");

        statement.executeBatch();

        assertEquals(0, context.readOnlyCreateCount);
        assertEquals(1, context.masterCreateCount);
    }

    /**
     * A TO_RO-hinted read and a plain read both classify as READ targets, so a batch of them must
     * not be promoted to Master — the batch routes to RO because no element is a write.
     */
    @Test
    public void assertExecuteBatchKeepsReadOnlyWhenMixedReadTargets() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);
        statement.addBatch("SELECT /*+ TO_RO */ 1 FROM t");
        statement.addBatch("SELECT 2 FROM t");

        statement.executeBatch();

        assertEquals(1, context.readOnlyCreateCount);
        assertEquals(0, context.masterCreateCount);
    }

    @Test
    public void assertExecuteBatchKeepsReadOnlyWhenAllReadElements() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);
        statement.addBatch("SELECT 1 FROM t");
        statement.addBatch("SELECT 2 FROM t");

        statement.executeBatch();

        assertEquals(1, context.readOnlyCreateCount);
        assertEquals(0, context.masterCreateCount);
    }

    @Test
    public void assertExecuteWithAutoGeneratedKeysRoutesBySqlClassification() throws Exception {
        LBStatement readStatement = new LBStatement(connection);
        DelegationContext readContext = new DelegationContext();
        readStatement.setStatementProvider(readContext);
        readStatement.execute("SELECT 1", Statement.NO_GENERATED_KEYS);
        assertEquals(1, readContext.readOnlyCreateCount);
        assertEquals(0, readContext.masterCreateCount);

        LBStatement writeStatement = new LBStatement(connection);
        DelegationContext writeContext = new DelegationContext();
        writeStatement.setStatementProvider(writeContext);
        writeStatement.execute("UPDATE t SET v = 1", Statement.RETURN_GENERATED_KEYS);
        assertEquals(0, writeContext.readOnlyCreateCount);
        assertEquals(1, writeContext.masterCreateCount);
    }

    @Test
    public void assertExecuteWithColumnIndexesRoutesBySqlClassification() throws Exception {
        LBStatement readStatement = new LBStatement(connection);
        DelegationContext readContext = new DelegationContext();
        readStatement.setStatementProvider(readContext);
        readStatement.execute("SELECT 1", new int[] {1});
        assertEquals(1, readContext.readOnlyCreateCount);
        assertEquals(0, readContext.masterCreateCount);

        LBStatement writeStatement = new LBStatement(connection);
        DelegationContext writeContext = new DelegationContext();
        writeStatement.setStatementProvider(writeContext);
        writeStatement.execute("INSERT INTO t(id) VALUES (1)", new int[] {1});
        assertEquals(0, writeContext.readOnlyCreateCount);
        assertEquals(1, writeContext.masterCreateCount);
    }

    @Test
    public void assertExecuteWithColumnNamesRoutesBySqlClassification() throws Exception {
        LBStatement readStatement = new LBStatement(connection);
        DelegationContext readContext = new DelegationContext();
        readStatement.setStatementProvider(readContext);
        readStatement.execute("SELECT 1", new String[] {"id"});
        assertEquals(1, readContext.readOnlyCreateCount);
        assertEquals(0, readContext.masterCreateCount);

        LBStatement writeStatement = new LBStatement(connection);
        DelegationContext writeContext = new DelegationContext();
        writeStatement.setStatementProvider(writeContext);
        writeStatement.execute("DELETE FROM t WHERE id = 1", new String[] {"id"});
        assertEquals(0, writeContext.readOnlyCreateCount);
        assertEquals(1, writeContext.masterCreateCount);
    }

    @Test
    public void assertExecuteUpdateOverloadsRouteToMasterForWriteSql() throws Exception {
        LBStatement withGeneratedKeys = new LBStatement(connection);
        DelegationContext generatedKeysContext = new DelegationContext();
        withGeneratedKeys.setStatementProvider(generatedKeysContext);
        int first =
                withGeneratedKeys.executeUpdate(
                        "UPDATE t SET v = 1", Statement.RETURN_GENERATED_KEYS);
        assertEquals(3, first);
        assertEquals(0, generatedKeysContext.readOnlyCreateCount);
        assertEquals(1, generatedKeysContext.masterCreateCount);

        LBStatement withIndexes = new LBStatement(connection);
        DelegationContext indexesContext = new DelegationContext();
        withIndexes.setStatementProvider(indexesContext);
        int second = withIndexes.executeUpdate("INSERT INTO t(id) VALUES (1)", new int[] {1});
        assertEquals(3, second);
        assertEquals(0, indexesContext.readOnlyCreateCount);
        assertEquals(1, indexesContext.masterCreateCount);

        LBStatement withNames = new LBStatement(connection);
        DelegationContext namesContext = new DelegationContext();
        withNames.setStatementProvider(namesContext);
        int third = withNames.executeUpdate("DELETE FROM t WHERE id = 1", new String[] {"id"});
        assertEquals(3, third);
        assertEquals(0, namesContext.readOnlyCreateCount);
        assertEquals(1, namesContext.masterCreateCount);
    }

    @Test
    public void assertGetGeneratedKeysDelegatesToLastExecutedPhysicalStatement() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);
        statement.executeUpdate("INSERT INTO t(id) VALUES (1)");

        ResultSet generatedKeys = statement.getGeneratedKeys();

        assertNotNull(generatedKeys);
        assertTrue(context.calls.contains("getGeneratedKeys"));
    }

    @Test
    public void assertStatementPropertiesArePropagatedBeforeExecution() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);
        statement.setFetchSize(64);
        statement.setQueryTimeout(5);
        statement.setMaxRows(100);
        statement.setMaxFieldSize(256);
        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        statement.closeOnCompletion();

        statement.executeQuery("SELECT 1");

        assertTrue(context.indexOf("setFetchSize:64") < context.indexOf("executeQuery:SELECT 1"));
        assertTrue(context.indexOf("setQueryTimeout:5") < context.indexOf("executeQuery:SELECT 1"));
        assertTrue(context.indexOf("setMaxRows:100") < context.indexOf("executeQuery:SELECT 1"));
        assertTrue(context.calls.contains("closeOnCompletion"));
    }

    @Test
    public void assertCancelDelegatesToLastExecutionPhysicalStatement() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);

        statement.executeQuery("SELECT 1");
        statement.cancel();

        assertEquals(1, context.cancelCalls);
    }

    @Test
    public void assertStatementWarningsAndEscapeProcessingDelegateToPhysicalStatement()
            throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);

        statement.setEscapeProcessing(false);
        statement.executeQuery("SELECT 1");
        SQLWarning warning = statement.getWarnings();
        statement.clearWarnings();

        assertEquals(1, context.getWarningsCalls);
        assertEquals(1, context.clearWarningsCalls);
        assertEquals("statement warning", warning.getMessage());
        assertTrue(context.calls.contains("setEscapeProcessing:false"));
    }

    @Test
    public void assertExecuteInsertDelegatesToPhysicalStatementPaths() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);

        Object oid = statement.executeInsert("INSERT INTO t(id) VALUES (1)");

        assertNotNull(oid);
        assertTrue(context.calls.contains("executeInsert:INSERT INTO t(id) VALUES (1)"));
    }

    @Test
    public void assertStatementQueryPlanApisDelegateToPhysicalStatement() throws Exception {
        LBStatement statement = new LBStatement(connection);
        DelegationContext context = new DelegationContext();
        statement.setStatementProvider(context);

        statement.setQueryInfo(true);
        statement.setOnlyQueryPlan(true);
        String queryPlanBySql = statement.getQueryplan("SELECT 1");
        String queryPlan = statement.getQueryplan();

        assertEquals("queryplan:SELECT 1", queryPlanBySql);
        assertEquals("queryplan:last", queryPlan);
        assertTrue(context.calls.contains("setQueryInfo:true"));
        assertTrue(context.calls.contains("setOnlyQueryPlan:true"));
        assertTrue(context.calls.contains("getQueryplan:SELECT 1"));
        assertTrue(context.calls.contains("getQueryplan"));
    }

    private static final class DelegationContext implements LBStatement.PhysicalStmtProvider {

        private final List<String> calls = new ArrayList<String>();

        private final List<String> batchedSql = new ArrayList<String>();

        private int readOnlyCreateCount;

        private int masterCreateCount;

        private boolean nextExecuteResult;

        private int cancelCalls;

        // When set, the physical executeQuery reentrantly calls the logical Statement's cancel()
        // to simulate another thread cancelling an in-flight execution (02 §M4).
        private LBStatement cancelTarget;

        private int closeCount;

        private int getWarningsCalls;

        private int clearWarningsCalls;

        public Statement getStatement(final Router.RouteTarget target, final String rawSql)
                throws SQLException {
            if (target == Router.RouteTarget.TO_READ_ONLY) {
                readOnlyCreateCount++;
            } else {
                masterCreateCount++;
            }
            return new FakePhysicalStatement() {
                @Override
                protected Object dispatch(final String name, final Object[] args)
                        throws SQLException {
                    if ("executeQuery".equals(name)) {
                        calls.add("executeQuery:" + args[0]);
                        if (cancelTarget != null) {
                            cancelTarget.cancel();
                        }
                        return createResultSetProxy();
                    }
                    if ("executeUpdate".equals(name)) {
                        calls.add("executeUpdate:" + args[0]);
                        return Integer.valueOf(3);
                    }
                    if ("execute".equals(name)) {
                        calls.add("execute:" + args[0]);
                        return Boolean.valueOf(nextExecuteResult);
                    }
                    if ("executeInsert".equals(name)) {
                        calls.add("executeInsert:" + args[0]);
                        return Proxy.newProxyInstance(
                                LBStatementExecutePathTest.class.getClassLoader(),
                                new Class[] {cubrid.sql.CUBRIDOID.class},
                                new InvocationHandler() {
                                    public Object invoke(
                                            final Object p, final Method m, final Object[] a)
                                            throws Throwable {
                                        return null;
                                    }
                                });
                    }
                    if ("addBatch".equals(name)) {
                        batchedSql.add(String.valueOf(args[0]));
                        calls.add("addBatch:" + args[0]);
                        return null;
                    }
                    if ("executeBatch".equals(name)) {
                        calls.add("executeBatch");
                        int[] result = new int[batchedSql.size()];
                        for (int i = 0; i < result.length; i++) {
                            result[i] = 1;
                        }
                        return result;
                    }
                    if ("getGeneratedKeys".equals(name)) {
                        calls.add("getGeneratedKeys");
                        return createResultSetProxy();
                    }
                    if ("setFetchSize".equals(name)) {
                        calls.add("setFetchSize:" + args[0]);
                        return null;
                    }
                    if ("setQueryTimeout".equals(name)) {
                        calls.add("setQueryTimeout:" + args[0]);
                        return null;
                    }
                    if ("setMaxRows".equals(name)) {
                        calls.add("setMaxRows:" + args[0]);
                        return null;
                    }
                    if ("setMaxFieldSize".equals(name)) {
                        calls.add("setMaxFieldSize:" + args[0]);
                        return null;
                    }
                    if ("setFetchDirection".equals(name)) {
                        calls.add("setFetchDirection:" + args[0]);
                        return null;
                    }
                    if ("setPoolable".equals(name)) {
                        calls.add("setPoolable:" + args[0]);
                        return null;
                    }
                    if ("setEscapeProcessing".equals(name)) {
                        calls.add("setEscapeProcessing:" + args[0]);
                        return null;
                    }
                    if ("setQueryInfo".equals(name)) {
                        calls.add("setQueryInfo:" + args[0]);
                        return null;
                    }
                    if ("setOnlyQueryPlan".equals(name)) {
                        calls.add("setOnlyQueryPlan:" + args[0]);
                        return null;
                    }
                    if ("closeOnCompletion".equals(name)) {
                        calls.add("closeOnCompletion");
                        return null;
                    }
                    if ("getUpdateCount".equals(name)) {
                        return Integer.valueOf(3);
                    }
                    if ("getResultSet".equals(name)) {
                        return createResultSetProxy();
                    }
                    if ("getMoreResults".equals(name)) {
                        return Boolean.FALSE;
                    }
                    if ("close".equals(name)) {
                        closeCount++;
                        calls.add("close");
                        return null;
                    }
                    if ("cancel".equals(name)) {
                        cancelCalls++;
                        calls.add("cancel");
                        return null;
                    }
                    if ("getWarnings".equals(name)) {
                        getWarningsCalls++;
                        calls.add("getWarnings");
                        return new SQLWarning("statement warning");
                    }
                    if ("clearWarnings".equals(name)) {
                        clearWarningsCalls++;
                        calls.add("clearWarnings");
                        return null;
                    }
                    if ("getQueryplan".equals(name) && args != null && args.length == 1) {
                        calls.add("getQueryplan:" + args[0]);
                        return "queryplan:" + args[0];
                    }
                    if ("getQueryplan".equals(name) && (args == null || args.length == 0)) {
                        calls.add("getQueryplan");
                        return "queryplan:last";
                    }
                    return null;
                }
            };
        }

        private ResultSet createResultSetProxy() {
            return (ResultSet)
                    Proxy.newProxyInstance(
                            LBStatementExecutePathTest.class.getClassLoader(),
                            new Class[] {ResultSet.class},
                            new InvocationHandler() {
                                public Object invoke(
                                        final Object proxy,
                                        final Method method,
                                        final Object[] args)
                                        throws Throwable {
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

        private int indexOf(final String value) {
            for (int i = 0; i < calls.size(); i++) {
                if (value.equals(calls.get(i))) {
                    return i;
                }
            }
            return Integer.MAX_VALUE;
        }
    }
}
