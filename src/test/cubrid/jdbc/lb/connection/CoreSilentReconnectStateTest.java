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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.lb.FakePhysicalConnection;
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
 * {@code reconnectWorker()} opens a new one — the {@code Connection} object the LB layer holds is
 * unchanged, so LB's own re-application chokepoint ({@code openConnection} &rarr; {@code
 * applySessionStateTo}) is never reached. Core restores isolation and lock timeout; {@code
 * autoCommit} rides on every execute. What nobody restores is {@code casChangeMode}, which is CAS
 * state set by a request (LB-Pending-Issues ISSUE-7).
 *
 * <p>The broker assigns a fresh {@code casId}/{@code casProcessId} on every connect, so LB compares
 * that tuple against the one recorded when it last applied the state. These tests drive that
 * comparison: a changed identity must re-apply, an unchanged one must not.
 */
public final class CoreSilentReconnectStateTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final Endpoint RW = new Endpoint("rw", 33000);

    @Test
    public void sessionStateIsReAppliedAfterCoreReconnectsTheLegUnderLb() throws SQLException {
        IdentityConnection physical = new IdentityConnection();
        LoadBalanceConnection conn = session(physical);
        int afterBind = physical.stateApplications.get();
        assertTrue("binding applies the session state once", afterBind >= 1);

        // Core reconnected: same Connection object, new CAS behind it.
        physical.casProcessId(4242);

        // Any physical access goes through ensureConn, which is where the identity is compared.
        conn.isReadOnly();

        assertEquals(
                "a new CAS must have the session state re-applied",
                afterBind + 1,
                physical.stateApplications.get());
        conn.close();
    }

    @Test
    public void anUnchangedCasDoesNotReApplyTheSessionState() throws SQLException {
        IdentityConnection physical = new IdentityConnection();
        LoadBalanceConnection conn = session(physical);
        int afterBind = physical.stateApplications.get();

        conn.isReadOnly();
        conn.isReadOnly();

        assertEquals(
                "the same CAS must not be re-configured on every call",
                afterBind,
                physical.stateApplications.get());
        conn.close();
    }

    @Test
    public void aConnectionWithoutAReadableCasIdentityIsLeftAlone() throws SQLException {
        // A physical connection whose UConnection cannot be read (a stub, or a proxy in another
        // test): the check must skip rather than re-apply on every call.
        IdentityConnection physical =
                new IdentityConnection() {
                    @Override
                    public UConnection getUConnection() throws SQLException {
                        return null;
                    }
                };
        LoadBalanceConnection conn = session(physical);
        int afterBind = physical.stateApplications.get();

        conn.isReadOnly();

        assertEquals(afterBind, physical.stateApplications.get());
        conn.close();
    }

    /* ===== harness ===== */

    private static LoadBalanceConnection session(final Connection physical) throws SQLException {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings settings = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        return physical;
                    }
                };

        LoadBalanceConnection conn = new LoadBalanceConnection(settings);
        conn.setConnectionManager(
                new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), settings, factory));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(
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
