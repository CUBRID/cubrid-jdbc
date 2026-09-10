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

package cubrid.jdbc.lb.config;

import static org.junit.Assert.*;

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.state.CurrentReadEndpointHolder;
import cubrid.jdbc.lb.state.SessionRoutingState;
import cubrid.jdbc.lb.state.SharedSelectorState;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import cubrid.jdbc.lb.statement.LBStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.Test;

public class ConnectionChecklistTest {

    private static LoadBalanceSettings emptyConfig() {
        return new LoadBalanceSettings(new Properties());
    }

    private static LoadBalanceSettings configWithMode(String mode) {
        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, mode);
        return new LoadBalanceSettings(p);
    }

    private static LoadBalanceConnection newConn() {
        return new LoadBalanceConnection(emptyConfig());
    }

    private static LoadBalanceConnection newConn(String distMode) {
        return new LoadBalanceConnection(configWithMode(distMode));
    }

    @Test
    public void assertCL01_instanceOfConnection() {
        LoadBalanceConnection conn = newConn();
        assertTrue(conn instanceof Connection);
    }

    @Test
    public void assertCL02_createStatement() throws Exception {
        LoadBalanceConnection conn = newConn();
        Statement stmt = conn.createStatement();
        assertNotNull(stmt);
        assertTrue(stmt instanceof LBStatement);
        assertFalse(stmt.isClosed());
    }

    @Test
    public void assertCL03_prepareStatement() throws Exception {
        LoadBalanceConnection conn = newConn();
        PreparedStatement ps = conn.prepareStatement("SELECT 1");
        assertNotNull(ps);
        assertTrue(ps instanceof LBPreparedStatement);
        assertEquals("SELECT 1", ((LBPreparedStatement) ps).getSql());
    }

    @Test
    public void assertCL04_setAutoCommitFalseTransitionsToTransaction() throws Exception {
        LoadBalanceConnection conn = newConn();
        assertTrue(conn.getAutoCommit());

        conn.setAutoCommit(false);

        assertFalse(conn.getAutoCommit());
        SessionRoutingState state = conn.getSessionState();
        assertTrue(state.isTransactionActive());
        assertTrue(state.shouldRouteToRw());
    }

    @Test
    public void assertCL04_setAutoCommitFalseIdempotent() throws Exception {
        LoadBalanceConnection conn = newConn();
        conn.setAutoCommit(false);
        conn.setAutoCommit(false);
        assertTrue(conn.getSessionState().isTransactionActive());
    }

    // 04 §C-1: a manual-commit session re-arms after commit/rollback (CUBRID implicitly begins the
    // next transaction), so it stays transaction-pinned rather than resetting to inactive.
    @Test
    public void assertCL05_manualCommitReArmsTransactionState() throws Exception {
        LoadBalanceConnection conn = newConn();
        conn.setAutoCommit(false);
        assertTrue(conn.getSessionState().isTransactionActive());

        conn.commit();

        assertTrue(conn.getSessionState().isTransactionActive());
    }

    @Test
    public void assertCL05_manualRollbackReArmsTransactionState() throws Exception {
        LoadBalanceConnection conn = newConn();
        conn.setAutoCommit(false);
        assertTrue(conn.getSessionState().shouldRouteToRw());

        conn.rollback();

        assertTrue(conn.getSessionState().isTransactionActive());
    }

    @Test
    public void assertCL05_commitInAutoCommitThrows() throws Exception {
        LoadBalanceConnection conn = newConn();
        try {
            conn.commit();
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("auto-commit"));
        }
    }

    @Test
    public void assertCL05_rollbackInAutoCommitThrows() throws Exception {
        LoadBalanceConnection conn = newConn();
        try {
            conn.rollback();
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("auto-commit"));
        }
    }

    @Test
    public void assertCL05_setAutoCommitTrueImplicitlyCommits() throws Exception {
        LoadBalanceConnection conn = newConn();
        conn.setAutoCommit(false);
        assertTrue(conn.getSessionState().isTransactionActive());
        assertTrue(conn.getSessionState().shouldRouteToRw());

        conn.setAutoCommit(true);

        assertTrue(conn.getAutoCommit());
        assertFalse(conn.getSessionState().isTransactionActive());
    }

    @Test
    public void assertCL06_closeResetsSessionState() throws Exception {
        LoadBalanceConnection conn = newConn();
        conn.setAutoCommit(false);

        conn.close();

        assertTrue(conn.isClosed());
        assertTrue(conn.getSessionState().isAutoCommit());
        assertFalse(conn.getSessionState().isTransactionActive());
        assertFalse(conn.getSessionState().shouldRouteToRw());
    }

    @Test
    public void assertCL06_closeClearsAffinity() throws Exception {
        LoadBalanceConnection conn = newConn();
        Endpoint ep = new Endpoint("ro1", 33000);
        conn.getBoundReadEndpoint().setCurrentRoEndpoint(ep);
        assertTrue(conn.getBoundReadEndpoint().isBound());

        conn.close();

        assertFalse(conn.getBoundReadEndpoint().isBound());
    }

    @Test
    public void assertCL06_closeClosesChildStatements() throws Exception {
        LoadBalanceConnection conn = newConn();
        Statement stmt1 = conn.createStatement();
        Statement stmt2 = conn.createStatement();
        PreparedStatement ps1 = conn.prepareStatement("SELECT ?");

        assertFalse(stmt1.isClosed());
        assertFalse(stmt2.isClosed());
        assertFalse(ps1.isClosed());

        conn.close();

        assertTrue(stmt1.isClosed());
        assertTrue(stmt2.isClosed());
        assertTrue(ps1.isClosed());
    }

    @Test
    public void assertCL06_closeIdempotent() throws Exception {
        LoadBalanceConnection conn = newConn();
        conn.close();
        conn.close();
        assertTrue(conn.isClosed());
    }

    @Test
    public void assertCL06_statementTrackingCount() throws Exception {
        LoadBalanceConnection conn = newConn();
        assertEquals(0, conn.getOpenStatementCount());

        conn.createStatement();
        conn.prepareStatement("SELECT 1");
        assertEquals(2, conn.getOpenStatementCount());

        conn.close();
        assertEquals(0, conn.getOpenStatementCount());
    }

    // 01 §M-3: closing a statement must untrack it, so a long-lived connection does not accumulate
    // closed statement references.
    @Test
    public void assertStatementCloseUntracksFromOpenSet() throws Exception {
        LoadBalanceConnection conn = newConn();

        Statement s1 = conn.createStatement();
        PreparedStatement p1 = conn.prepareStatement("SELECT 1");
        assertEquals(2, conn.getOpenStatementCount());

        s1.close();
        assertEquals(1, conn.getOpenStatementCount());
        p1.close();
        assertEquals(0, conn.getOpenStatementCount());

        // Repeated create/close must not accumulate.
        for (int i = 0; i < 5; i++) {
            conn.createStatement().close();
        }
        assertEquals(0, conn.getOpenStatementCount());

        conn.close();
    }

    @Test
    public void assertCL07_getAutoCommitOnClosedThrows() throws Exception {
        LoadBalanceConnection conn = newConn();
        conn.close();
        try {
            conn.getAutoCommit();
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("closed"));
        }
    }

    @Test
    public void assertCL07_setAutoCommitOnClosedThrows() throws Exception {
        LoadBalanceConnection conn = newConn();
        conn.close();
        try {
            conn.setAutoCommit(false);
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("closed"));
        }
    }

    @Test
    public void assertCL07_commitOnClosedThrows() throws Exception {
        LoadBalanceConnection conn = newConn();
        conn.close();
        try {
            conn.commit();
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("closed"));
        }
    }

    @Test
    public void assertCL07_createStatementOnClosedThrows() throws Exception {
        LoadBalanceConnection conn = newConn();
        conn.close();
        try {
            conn.createStatement();
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("closed"));
        }
    }

    @Test
    public void assertCL07_prepareStatementOnClosedThrows() throws Exception {
        LoadBalanceConnection conn = newConn();
        conn.close();
        try {
            conn.prepareStatement("SELECT 1");
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("closed"));
        }
    }

    @Test
    public void assertCL07_transactionIsolationConsistency() throws Exception {
        LoadBalanceConnection conn = newConn();
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, conn.getTransactionIsolation());
        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, conn.getTransactionIsolation());
    }

    @Test
    public void assertCL07_isValidReflectsClosedState() throws Exception {
        LoadBalanceConnection conn = newConn();
        assertTrue(conn.isValid(0));
        conn.close();
        assertFalse(conn.isValid(0));
    }

    @Test
    public void assertCL08_sessionModeFixesRoAtConnectionTime() throws Exception {
        LoadBalanceConnection conn = newConn("session");
        assertTrue(conn.isSessionDistributionMode());

        Endpoint ep = new Endpoint("ro-slave1", 33000);
        CurrentReadEndpointHolder tracker = conn.getBoundReadEndpoint();
        tracker.setCurrentRoEndpoint(ep);

        assertTrue(tracker.isBound());
        assertEquals("ro-slave1", tracker.getCurrentRoEndpoint().getHost());
        assertEquals(33000, tracker.getCurrentRoEndpoint().getPort());
    }

    @Test
    public void assertCL08_sessionModeAffinityPersistsAcrossExecutes() throws Exception {
        LoadBalanceConnection conn = newConn("session");
        Endpoint ep = new Endpoint("ro-slave2", 33001);
        conn.getBoundReadEndpoint().setCurrentRoEndpoint(ep);

        assertEquals(ep, conn.getBoundReadEndpoint().getCurrentRoEndpoint());
        assertEquals(ep, conn.getBoundReadEndpoint().getCurrentRoEndpoint());
    }

    @Test
    public void assertCL10_sharedStateAcrossConnections() throws Exception {
        SharedSelectorState shared = new SharedSelectorState();

        LoadBalanceConnection conn1 = newConn("session");
        conn1.setSharedSelectorState(shared);

        LoadBalanceConnection conn2 = newConn("session");
        conn2.setSharedSelectorState(shared);

        assertSame(conn1.getSharedSelectorState(), conn2.getSharedSelectorState());

        int idx1 = shared.nextGroupRoundRobinIndex("read", 3);
        int idx2 = shared.nextGroupRoundRobinIndex("read", 3);
        assertEquals(0, idx1);
        assertEquals(1, idx2);
    }

    @Test
    public void assertCL10_sharedStateSurvivesConnectionClose() throws Exception {
        SharedSelectorState shared = new SharedSelectorState();

        LoadBalanceConnection conn1 = newConn("session");
        conn1.setSharedSelectorState(shared);
        shared.nextGroupRoundRobinIndex("read", 3);
        conn1.close();

        // The shared counter survives conn1.close(); a fresh connection continues the sequence.
        LoadBalanceConnection conn2 = newConn("session");
        conn2.setSharedSelectorState(shared);
        int idx = shared.nextGroupRoundRobinIndex("read", 3);
        assertEquals(1, idx);
    }

    /**
     * CL10 pins that the selector state is POOL-scoped, not per-connection. It used to check the
     * weight-deficit map; that selector was removed in 2026-08-11, so it now checks the live home
     * counts that took its place — the counter that actually has to be shared, since role selection
     * balances the pool's population against readWeight.
     */
    @Test
    public void assertCL10_liveHomeCountsShared() throws Exception {
        SharedSelectorState shared = new SharedSelectorState();
        shared.acquireIndexByPopulation(new String[] {"role:slave"}, new int[] {1});
        shared.acquireIndexByPopulation(new String[] {"role:slave"}, new int[] {1});

        LoadBalanceConnection conn1 = newConn();
        conn1.setSharedSelectorState(shared);

        LoadBalanceConnection conn2 = newConn();
        conn2.setSharedSelectorState(shared);

        assertEquals(2, conn1.getSharedSelectorState().liveHomeCount("role:slave"));
        assertEquals(2, conn2.getSharedSelectorState().liveHomeCount("role:slave"));

        conn1.getSharedSelectorState().releaseHome("role:slave");
        assertEquals(
                "a release on one connection is seen by the other",
                1,
                conn2.getSharedSelectorState().liveHomeCount("role:slave"));
    }

    @Test
    public void assertCL10_roundRobinContinuity() throws Exception {
        SharedSelectorState shared = new SharedSelectorState();
        assertEquals(0, shared.nextGroupRoundRobinIndex("read", 2));
        assertEquals(1, shared.nextGroupRoundRobinIndex("read", 2));
        assertEquals(0, shared.nextGroupRoundRobinIndex("read", 2));
        assertEquals(1, shared.nextGroupRoundRobinIndex("read", 2));
    }

    @Test
    public void assertTransactionCycleFullSequence() throws Exception {
        LoadBalanceConnection conn = newConn();
        SessionRoutingState state = conn.getSessionState();

        assertTrue(conn.getAutoCommit());
        assertFalse(state.isTransactionActive());
        assertFalse(state.shouldRouteToRw());

        conn.setAutoCommit(false);
        assertTrue(state.isTransactionActive());
        assertTrue(state.shouldRouteToRw());

        conn.commit();
        assertTrue("manual-commit re-arms after commit (04 §C-1)", state.isTransactionActive());

        conn.setAutoCommit(false);
        assertTrue(state.isTransactionActive());

        conn.rollback();
        assertTrue("manual-commit re-arms after rollback (04 §C-1)", state.isTransactionActive());

        conn.setAutoCommit(true);
        assertTrue(conn.getAutoCommit());
        assertFalse("switch to autocommit clears the transaction pin", state.isTransactionActive());
    }

    @Test
    public void assertSharedSelectorStateDefaultNull() throws Exception {
        LoadBalanceConnection conn = newConn();
        assertNull(conn.getSharedSelectorState());
    }

    @Test
    public void assertDefaultDistributionModeIsSession() throws Exception {
        LoadBalanceConnection conn = newConn();
        assertTrue(conn.isSessionDistributionMode());
    }
}
