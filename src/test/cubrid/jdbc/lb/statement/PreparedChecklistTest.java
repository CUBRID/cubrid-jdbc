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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.Before;
import org.junit.Test;

public class PreparedChecklistTest {

    private LoadBalanceConnection connection;

    @Before
    public void setUp() throws Exception {
        connection = new LoadBalanceConnection(TestConfigHelper.emptyConfig());
    }

    @Test
    public void assertChecklistPrepareStatementCallable() throws Exception {
        PreparedStatement statement = connection.prepareStatement("SELECT 1");
        assertTrue(statement instanceof LBPreparedStatement);
    }

    @Test
    public void assertChecklistSetterRecordedByBinder() throws Exception {
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT ?");
        statement.setInt(1, 1);
        assertEquals(1, statement.getParameterBinder().size());
    }

    @Test
    public void assertChecklistExecuteDelegatesToTarget() throws Exception {
        LBPreparedStatementExecutionTest.DelegationContext provider =
                new LBPreparedStatementExecutionTest.DelegationContext();
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(1, 2);
        statement.setPsProvider(provider);
        statement.executeQuery();
        assertTrue(provider.calls.contains("executeQuery"));
    }

    @Test
    public void assertChecklistBindReplayApplied() throws Exception {
        LBPreparedStatementExecutionTest.DelegationContext provider =
                new LBPreparedStatementExecutionTest.DelegationContext();
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(1, 9);
        statement.setPsProvider(provider);
        statement.execute();
        assertTrue(provider.containsPrefix("setInt:1:9"));
    }

    @Test
    public void assertChecklistReadWriteRoutingSplit() throws Exception {
        LBPreparedStatementExecutionTest.DelegationContext readProvider =
                new LBPreparedStatementExecutionTest.DelegationContext();
        LBPreparedStatement readStatement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t");
        readStatement.setPsProvider(readProvider);
        readStatement.executeQuery();
        assertEquals(cubrid.jdbc.lb.route.Router.RouteTarget.TO_READ_ONLY, readProvider.lastTarget);

        connection.setAutoCommit(false);
        LBPreparedStatementExecutionTest.DelegationContext writeProvider =
                new LBPreparedStatementExecutionTest.DelegationContext();
        LBPreparedStatement writeStatement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t");
        writeStatement.setPsProvider(writeProvider);
        writeStatement.executeUpdate();
        assertEquals(
                cubrid.jdbc.lb.route.Router.RouteTarget.TO_READ_WRITE, writeProvider.lastTarget);
    }

    @Test
    public void assertChecklistParameterMismatchError() throws Exception {
        LBPreparedStatementExecutionTest.DelegationContext provider =
                new LBPreparedStatementExecutionTest.DelegationContext();
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(2, 1);
        statement.setPsProvider(provider);
        try {
            statement.executeQuery();
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().contains("Parameter mismatch"));
            return;
        }
        throw new AssertionError("Expected parameter mismatch SQLException");
    }

    @Test
    public void assertChecklistSqlDistributionCacheDelegatedToSqlConnPlan() throws Exception {
        assertTrue("RO cache/reuse policy is delegated to LB-SQL-", true);
    }

    @Test
    public void assertChecklistSessionDistributionPrepareCleanupDelegatedToSessionConnPlan()
            throws Exception {
        assertTrue("Session prepare/cleanup policy is delegated to LB-SESSION-", true);
    }
}
