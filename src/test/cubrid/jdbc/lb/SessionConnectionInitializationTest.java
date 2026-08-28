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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
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

public final class SessionConnectionInitializationTest {

    @Test
    public void assertInitSessionBindingsWithRwAndSelectedRo() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        connection.setSharedSelectorState(new SharedSelectorState());
        EndpointTopology topology = createTopology("rw-host", 33000, "ro-a", 33100, "ro-b", 33101);

        connection.initSessionBindings(topology);

        assertTrue(connection.isSessionInitialized());
        assertEquals("rw-host", connection.getCurrentEp(SessionLeg.RW).getHost());
        assertNotNull(connection.getCurrentEp(SessionLeg.RO));
        assertTrue(connection.getBoundReadEndpoint().isBound());
        assertSame(
                connection.getCurrentEp(SessionLeg.RO),
                connection.getBoundReadEndpoint().getCurrentRoEndpoint());
    }

    @Test
    public void assertSelectReadEndpointInSessionModeReusesSameEndpoint() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        connection.setSharedSelectorState(new SharedSelectorState());
        EndpointTopology topology = createTopology("rw-host", 33000, "ro-a", 33100, "ro-b", 33101);

        Endpoint first = connection.resolveReadEndpoint(topology);
        Endpoint second = connection.resolveReadEndpoint(topology);

        assertNotNull(first);
        assertSame(first, second);
        assertTrue(connection.isSessionInitialized());
    }

    @Test
    public void assertInitSessionBindingsWithoutRwThrowsSQLException() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        List<Endpoint> ro = new ArrayList<Endpoint>();
        ro.add(new Endpoint("ro-a", 33100));
        EndpointTopology topology = new EndpointTopology((Endpoint) null, ro, null);
        try {
            connection.initSessionBindings(topology);
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().contains("RW endpoint"));
        }
    }

    @Test
    public void assertInitSessionBindingsWithoutRoBindsRwOnly() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        connection.setSharedSelectorState(new SharedSelectorState());
        EndpointTopology topology =
                new EndpointTopology(
                        new Endpoint("rw-host", 33000), new ArrayList<Endpoint>(), null);

        connection.initSessionBindings(topology);

        // No RO broker configured: permanent RW-only. Both roles resolve to the RW endpoint.
        assertTrue(connection.isSessionInitialized());
        Endpoint rw = connection.getCurrentEp(SessionLeg.RW);
        Endpoint ro = connection.getCurrentEp(SessionLeg.RO);
        assertNotNull(rw);
        assertEquals("rw-host", rw.getHost());
        assertSame(rw, ro);
    }

    @Test
    public void assertCloseClearsSessionInitializationState() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        connection.setSharedSelectorState(new SharedSelectorState());
        EndpointTopology topology = createTopology("rw-host", 33000, "ro-a", 33100);
        connection.initSessionBindings(topology);
        assertTrue(connection.isSessionInitialized());

        connection.close();

        assertFalse(connection.isSessionInitialized());
        assertEquals(null, connection.getCurrentEp(SessionLeg.RW));
        assertEquals(null, connection.getCurrentEp(SessionLeg.RO));
        assertFalse(connection.getBoundReadEndpoint().isBound());
    }

    @Test
    public void assertTransactionActiveUsesMasterAndReturnsToSessionRoAfterCommit()
            throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        connection.setSharedSelectorState(new SharedSelectorState());
        EndpointTopology topology = createTopology("rw-host", 33000, "ro-a", 33100, "ro-b", 33101);

        Endpoint sessionRo = connection.resolveReadEndpoint(topology);
        connection.setAutoCommit(false);
        Endpoint duringTransaction = connection.resolveReadEndpoint(topology);
        connection.commit();
        // CR-6/04 §C-1: manual-commit re-arms after commit, so reads stay RW-pinned until
        // auto-commit is restored; only then does the session return to the pinned RO.
        Endpoint afterCommitStillManual = connection.resolveReadEndpoint(topology);
        connection.setAutoCommit(true);
        Endpoint afterAutoCommitRestored = connection.resolveReadEndpoint(topology);

        assertEquals("rw-host", duringTransaction.getHost());
        assertEquals("rw-host", afterCommitStillManual.getHost());
        assertSame(sessionRo, afterAutoCommitRestored);
    }

    private static LoadBalanceConnection createSessionConnection() {
        Properties props = new Properties();
        props.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceConnection connection = new LoadBalanceConnection(LoadBalanceSettings.of(props));
        connection.setConnectionManager(new SimpleEndpointConnManager());
        return connection;
    }

    private static EndpointTopology createTopology(
            final String rwHost, final int rwPort, final String roHost1, final int roPort1) {
        List<Endpoint> ro = new ArrayList<Endpoint>();
        ro.add(new Endpoint(roHost1, roPort1));
        return new EndpointTopology(new Endpoint(rwHost, rwPort), ro, null);
    }

    private static EndpointTopology createTopology(
            final String rwHost,
            final int rwPort,
            final String roHost1,
            final int roPort1,
            final String roHost2,
            final int roPort2) {
        List<Endpoint> ro = new ArrayList<Endpoint>();
        ro.add(new Endpoint(roHost1, roPort1));
        ro.add(new Endpoint(roHost2, roPort2));
        return new EndpointTopology(new Endpoint(rwHost, rwPort), ro, null);
    }
}
