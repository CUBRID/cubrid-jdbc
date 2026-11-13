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

package cubrid.jdbc.lb.connection;

import static org.junit.Assert.assertEquals;

import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.lb.FakePhysicalConnection;
import cubrid.jdbc.lb.LbTestAccess;
import cubrid.jdbc.lb.LbTestSettings;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Core reconnects a leg underneath LB and LB is not told.
 *
 * <p>{@code UConnection.checkReconnect()} runs before every request, and when the socket is gone
 * {@code reconnectWorker()} opens a new one behind the same {@code Connection} object. What core
 * restores there is what a core connection gets back, so LB adds nothing. Re-applying the session
 * state ran {@code setTransactionIsolation}, which commits first in core, inside the application's
 * transaction.
 */
public final class CoreSilentReconnectStateTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final Endpoint RW = new Endpoint("rw", 33000);

    @Test
    public void coreReconnectingTheLegLeavesTheSessionStateToCore() throws SQLException {
        IdentityConnection physical = new IdentityConnection();
        LoadBalanceConnection conn = session(physical);
        int afterBind = physical.stateApplications.get();

        // Core reconnected: same Connection object, new CAS behind it.
        physical.casProcessId(4242);
        conn.isReadOnly();

        assertEquals(afterBind, physical.stateApplications.get());
        conn.close();
    }

    @Test
    public void coreReconnectingTheLegInATransactionDoesNotCommitIt() throws SQLException {
        IdentityConnection physical = new IdentityConnection();
        LoadBalanceConnection conn = session(physical);
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        conn.setAutoCommit(false);
        int isolationCalls = physical.isolationCalls.get();

        physical.casProcessId(4242);
        conn.isReadOnly();

        assertEquals(
                "setTransactionIsolation commits first in core",
                isolationCalls,
                physical.isolationCalls.get());
        conn.close();
    }

    /* ===== harness ===== */

    private static LoadBalanceConnection session(final Connection physical) throws SQLException {
        LoadBalanceSettings settings =
                LbTestSettings.forBinding(new Endpoint("rw", 33000), new Endpoint("ro1", 33001));

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        return physical;
                    }
                };

        LoadBalanceConnection conn = LbTestAccess.newLoadBalanceConnection(settings);
        LbTestAccess.setConnectionManager(
                conn,
                new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), settings, factory));
        LbTestAccess.setSharedSelectorState(conn, new SharedSelectorState());
        LbTestAccess.initSessionBindings(
                conn,
                new EndpointTopology(
                        RW,
                        Arrays.asList(new Endpoint("ro1", 33001)),
                        Collections.<Endpoint>emptyList()));

        return conn;
    }

    /**
     * A physical connection that reports a CAS identity and counts how often LB configured it.
     * {@code setAutoCommit} is the first call {@code applySessionStateTo} makes, so counting it
     * counts the applications.
     */
    private static class IdentityConnection extends FakePhysicalConnection {

        private final AtomicInteger stateApplications = new AtomicInteger();
        private final AtomicInteger isolationCalls = new AtomicInteger();
        private final FakeUConnection u = new FakeUConnection();

        private void casProcessId(final int id) {
            u.casProcessId = id;
        }

        @Override
        public UConnection getUConnection() throws SQLException {
            return u;
        }

        @Override
        protected Object dispatch(final String name, final Object[] args) throws SQLException {
            if ("setAutoCommit".equals(name)) {
                stateApplications.incrementAndGet();
            }
            if ("setTransactionIsolation".equals(name)) {
                isolationCalls.incrementAndGet();
            }
            if ("isClosed".equals(name)) {
                return Boolean.FALSE;
            }
            if ("isReadOnly".equals(name)) {
                return Boolean.FALSE;
            }

            return null;
        }
    }

    /** Carries a CAS identity and nothing else; the abstract surface is four methods. */
    private static final class FakeUConnection extends UConnection {

        private FakeUConnection() {
            casIp = "rw";
            casPort = 33000;
            casId = 1;
            casProcessId = 1;
        }

        public void endTransaction(final boolean type) {}

        protected void closeInternal() {}

        public void setAutoCommit(final boolean autoCommit) {}

        public boolean getAutoCommit() {
            return true;
        }
    }
}
