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

import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

/**
 * Core completes every statement of its one connection when a transaction ends there. This
 * session's statements are spread over two legs, so the write leg's commit or rollback runs core's
 * own on the read leg too. A read leg's own end stays there: some exist only in LB.
 */
public final class LegTransactionEndTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";

    @Test
    public void theWriteLegEndingATransactionEndsItOnTheReadLegToo() throws SQLException {
        Endpoint rw = new Endpoint("rw", 33000);
        Endpoint ro1 = new Endpoint("ro1", 33001);
        EndpointTopology topo =
                new EndpointTopology(rw, Arrays.asList(ro1), Collections.<Endpoint>emptyList());
        final Leg rwLeg = new Leg("rw", 33000);
        final Leg roLeg = new Leg("ro1", 33001);
        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        return url.contains("ro1:33001") ? roLeg : rwLeg;
                    }
                };
        LoadBalanceSettings settings = LbTestSettings.forTopology(topo);
        LoadBalanceConnection conn = new LoadBalanceConnection(settings);
        conn.setConnectionManager(
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        LOGICAL_URL, new Properties(), settings, factory));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topo);

        conn.legTransactionEnding(rwLeg, true);
        conn.legTransactionEnding(rwLeg, false);
        conn.legTransactionEnding(roLeg, false);

        assertEquals(Arrays.asList("commit", "rollback"), roLeg.ends);
        assertEquals(Collections.<String>emptyList(), rwLeg.ends);
        conn.close();
    }

    /** A leg connected to one broker, recording the ends it is told to run. */
    private static final class Leg extends FakePhysicalConnection {

        private final List<String> ends = new ArrayList<String>();

        private final UConnection socket =
                new UConnection() {
                    public void endTransaction(final boolean type) {}

                    protected void closeInternal() {}

                    public void setAutoCommit(final boolean autoCommit) {}

                    public boolean getAutoCommit() {
                        return true;
                    }
                };

        private Leg(final String host, final int port) {
            socket.casIp = host;
            socket.casPort = port;
        }

        @Override
        public UConnection getUConnection() {
            return socket;
        }

        @Override
        protected Object dispatch(final String name, final Object[] args) {
            if ("commit".equals(name) || "rollback".equals(name)) {
                ends.add(name);
            }

            return null;
        }
    }
}
