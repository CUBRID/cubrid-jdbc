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
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

public final class RuntimePreparedProviderIntegrationTest {

    @Test
    public void assertExecuteDelegatesToReadOnlyInSessionMode() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        connection.setConnectionManager(manager);
        connection.setSharedSelectorState(new SharedSelectorState());
        connection.initSessionBindings(createTopology());

        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t WHERE id = ?");
        statement.setInt(1, 1);
        statement.executeQuery();

        // a READ prepares only the bound read endpoint (no eager RW leg).
        assertEquals(1, manager.getPreparedStatementCount());
        assertEquals(0, manager.getClosePreparedStatementsCallCount());
    }

    @Test
    public void assertTransactionReadFallsBackToMasterPath() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        connection.setConnectionManager(manager);
        connection.setSharedSelectorState(new SharedSelectorState());
        connection.initSessionBindings(createTopology());

        connection.setAutoCommit(false);
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t");
        statement.execute();
        connection.commit();

        assertTrue(manager.getAcquireCount("rw:33000") > 0);
    }

    @Test
    public void assertCloseCleansPreparedStatementsViaProvider() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        connection.setConnectionManager(manager);
        connection.setSharedSelectorState(new SharedSelectorState());
        connection.initSessionBindings(createTopology());
        LBPreparedStatement statement =
                (LBPreparedStatement) connection.prepareStatement("SELECT * FROM t");
        statement.execute();
        connection.close();

        assertTrue(manager.getClosePreparedStatementsCallCount() > 0);
    }

    @Test
    public void assertWritePrepareUsesMasterOnlyInSessionMode() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        connection.setConnectionManager(manager);
        connection.setSharedSelectorState(new SharedSelectorState());
        connection.initSessionBindings(createTopology());

        LBPreparedStatement statement =
                (LBPreparedStatement)
                        connection.prepareStatement("UPDATE t SET c = ? WHERE id = ?");
        statement.setInt(1, 10);
        statement.setInt(2, 1);
        statement.executeUpdate();

        assertEquals(1, manager.getPreparedStatementCount());
    }

    private static LoadBalanceConnection createSessionConnection() {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        return new LoadBalanceConnection(LoadBalanceSettings.of(properties));
    }

    private static EndpointTopology createTopology() {
        List<Endpoint> roEndpoints = new ArrayList<Endpoint>();
        roEndpoints.add(new Endpoint("ro1", 33000));
        roEndpoints.add(new Endpoint("ro2", 33000));
        return new EndpointTopology(new Endpoint("rw", 33000), roEndpoints, null);
    }
}
