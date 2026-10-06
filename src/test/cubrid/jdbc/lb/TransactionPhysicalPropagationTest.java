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

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

public final class TransactionPhysicalPropagationTest {

    @Test
    public void initSessionBindingsAppliesLogicalAutoCommitToPhysical() throws SQLException {
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        LoadBalanceConnection connection = createSessionConnection();
        connection.setConnectionManager(manager);
        connection.setSharedSelectorState(new SharedSelectorState());
        connection.setAutoCommit(false);
        connection.initSessionBindings(createTopology());
        assertEquals(1, manager.getApplyPhysicalAutoCommitCallCount());
    }

    @Test
    public void setAutoCommitAndCommitPropagateWhenSessionBound() throws SQLException {
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        LoadBalanceConnection connection = createBoundSessionConnection(manager);
        assertEquals(1, manager.getApplyPhysicalAutoCommitCallCount());
        connection.setAutoCommit(false);
        assertEquals(2, manager.getApplyPhysicalAutoCommitCallCount());
        connection.commit();
        assertEquals(1, manager.getCommitPhysicalTransactionCallCount());
    }

    @Test
    public void rollbackPropagatesWhenSessionBound() throws SQLException {
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        LoadBalanceConnection connection = createBoundSessionConnection(manager);
        connection.setAutoCommit(false);
        connection.rollback();
        assertEquals(1, manager.getRollbackPhysicalTransactionCallCount());
    }

    @Test
    public void setAutoCommitTrueWithOpenTransactionCommitsPhysicallyFirst() throws SQLException {
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        LoadBalanceConnection connection = createBoundSessionConnection(manager);
        connection.setAutoCommit(false);
        connection.setAutoCommit(true);
        assertEquals(1, manager.getCommitPhysicalTransactionCallCount());
        assertEquals(3, manager.getApplyPhysicalAutoCommitCallCount());
    }

    /**
     * m-10: when {@code setAutoCommit(true)} commits a live transaction physically but the
     * subsequent autocommit-mode propagation fails, the logical state must already reflect the
     * commit (autocommit on, no active tx) — never left manual+txActive over committed work.
     */
    @Test
    public void setAutoCommitTrueReflectsCommitEvenIfAutoCommitPropagationFails()
            throws SQLException {
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        LoadBalanceConnection connection = createBoundSessionConnection(manager);
        connection.setAutoCommit(false); // open a manual transaction

        manager.failNextApplyPhyAutoCommit();
        try {
            connection.setAutoCommit(true);
            fail("expected the simulated applyPhyAutoCommit failure to propagate");
        } catch (SQLException expected) {
        }

        // Physical commit ran; logical state reflects it despite the later propagation failure.
        assertEquals(1, manager.getCommitPhysicalTransactionCallCount());
        assertTrue(connection.getAutoCommit());

        // The session is not stuck manual+txActive: a rollback is now rejected as autocommit-only,
        // rather than appearing to undo the already-committed work.
        try {
            connection.rollback();
            fail("expected rollback() to be rejected in autocommit mode");
        } catch (SQLException expected) {
        }
    }

    private static LoadBalanceConnection createSessionConnection() {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        return new LoadBalanceConnection(LoadBalanceSettings.of(properties));
    }

    private static LoadBalanceConnection createBoundSessionConnection(
            final SimpleEndpointConnManager manager) throws SQLException {
        LoadBalanceConnection connection = createSessionConnection();
        connection.setConnectionManager(manager);
        connection.setSharedSelectorState(new SharedSelectorState());
        connection.initSessionBindings(createTopology());
        return connection;
    }

    private static EndpointTopology createTopology() {
        List<Endpoint> ro = new ArrayList<Endpoint>();
        ro.add(new Endpoint("ro1", 33000));
        return new EndpointTopology(new Endpoint("rw", 33000), ro, null);
    }
}
