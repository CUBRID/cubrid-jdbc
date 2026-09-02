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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.metrics.RuntimeMetrics;
import cubrid.jdbc.lb.state.SharedSelectorState;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

public final class RuntimeChecklistTest {

    @Test
    public void assertRC01SessionModePreparesMasterAndSlavePhysicalBindings() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        connection.setConnectionManager(manager);
        connection.setSharedSelectorState(new SharedSelectorState());

        connection.initSessionBindings(createTopology());

        assertNotNull(connection.getCurrentEp(SessionLeg.RW));
        assertNotNull(connection.getCurrentEp(SessionLeg.RO));
        assertTrue(manager.getAcquireCount(connection.getCurrentEp(SessionLeg.RW).getId()) > 0);
        assertTrue(manager.getAcquireCount(connection.getCurrentEp(SessionLeg.RO).getId()) > 0);
    }

    @Test
    public void assertRC03TransactionMasterLockAndReturnToRo() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        Endpoint pinnedRo = connection.resolveReadEndpoint(createTopology());
        connection.setAutoCommit(false);
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(1, 1);
        statement.executeQuery();
        connection.commit();
        // CR-6/04 §C-1: manual-commit re-arms after commit, so reads stay RW-pinned until
        // auto-commit is restored; only then does the session return to the pinned RO.
        Endpoint afterCommitStillManual = connection.resolveReadEndpoint(createTopology());
        connection.setAutoCommit(true);
        Endpoint afterAutoCommitRestored = connection.resolveReadEndpoint(createTopology());

        assertEquals("rw", afterCommitStillManual.getHost());
        assertSame(pinnedRo, afterAutoCommitRestored);
    }

    @Test
    public void assertRC05CloseOrderCleanupAndRelease() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        SimpleEndpointConnManager manager =
                (SimpleEndpointConnManager) connection.getConnectionManager();
        connection.prepareStatement("SELECT * FROM t");
        String masterId = connection.getCurrentEp(SessionLeg.RW).getId();
        String roId = connection.getCurrentEp(SessionLeg.RO).getId();

        connection.close();

        assertTrue(manager.getClosePreparedStatementsCallCount() > 0);
        assertTrue(manager.getReleaseCount(masterId) > 0);
        assertTrue(manager.getReleaseCount(roId) > 0);
    }

    @Test
    public void assertRC08ObservabilityLogKeysAreRecorded() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        connection.setAutoCommit(false);
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(1, 1);
        statement.executeQuery();
        RuntimeMetrics metrics = connection.getRuntimeMetrics();
        List<String> events = metrics.getEvents();
        assertTrue(!events.isEmpty());
        String event = events.get(0);
        assertTrue(event.contains("connectionId="));
        assertTrue(event.contains("sqlType="));
        assertTrue(event.contains("routeTarget="));
        assertTrue(event.contains("endpointId="));
        assertTrue(event.contains("fallbackReason="));
        assertTrue(event.contains("txActive="));
        assertTrue(event.contains("distributionMode="));
    }

    @Test
    public void assertRC09MetricsCountersIncrease() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        LBPreparedStatement read =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        read.setInt(1, 1);
        read.executeQuery();
        read.executeQuery();
        connection.setAutoCommit(false);
        LBPreparedStatement fallback =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        fallback.setInt(1, 2);
        fallback.executeQuery();
        connection
                .createPsProvider()
                .getPreparedStatement(
                        cubrid.jdbc.lb.route.Router.RouteTarget.TO_READ_ONLY, "SELECT * FROM t");
        RuntimeMetrics metrics = connection.getRuntimeMetrics();

        assertTrue(metrics.getRoSelectionCount() > 0);
        assertTrue(metrics.getRwFallbackCount() > 0);
        assertTrue(metrics.getEndpointCount("rw:33000") > 0);
        assertTrue(
                metrics.getEndpointCount("ro1:33000") > 0
                        || metrics.getEndpointCount("ro2:33000") > 0);
    }

    private static LoadBalanceConnection createBoundSessionConnection() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        connection.setConnectionManager(new SimpleEndpointConnManager());
        connection.setSharedSelectorState(new SharedSelectorState());
        connection.initSessionBindings(createTopology());
        connection.getRuntimeMetrics().setEnabled(true);
        return connection;
    }

    private static LoadBalanceConnection createSessionConnection() {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        return new LoadBalanceConnection(LoadBalanceSettings.of(properties));
    }

    private static EndpointTopology createTopology() {
        List<Endpoint> ro = new ArrayList<Endpoint>();
        ro.add(new Endpoint("ro1", 33000));
        ro.add(new Endpoint("ro2", 33000));
        return new EndpointTopology(new Endpoint("rw", 33000), ro, null);
    }
}
