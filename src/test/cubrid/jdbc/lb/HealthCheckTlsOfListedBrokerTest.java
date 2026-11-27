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

import cubrid.jdbc.jci.SslMode;
import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.ConnectionTestAccess;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.failover.UnreachableEndpoints;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import org.junit.After;
import org.junit.Test;

/**
 * Core's health check takes a host off the JVM-wide unreachable list once it answers, connecting
 * the way it was last told to. Core tells it at its altHosts reconnect, which a leg never takes, so
 * a session that lists a broker tells it how its legs connect. Untold it connects in plain, and a
 * TLS-only broker that is back is refused every time.
 */
public final class HealthCheckTlsOfListedBrokerTest {

    private static final Endpoint NODE1 = new Endpoint("hctnode1", 33000);
    private static final Endpoint NODE2 = new Endpoint("hctnode2", 33000);
    private static final Endpoint NODE2_RO = new Endpoint("hctnode2", 33002);

    private LoadBalanceConnection conn;

    /** Binds a session whose master RW broker refuses the connect, with these leg properties. */
    private void bindWithTheMasterDown(final Properties legProperties) throws SQLException {
        EndpointTopology topo =
                new EndpointTopology(
                        Arrays.asList(NODE1, NODE2),
                        Arrays.asList(NODE2_RO),
                        Collections.<Endpoint>emptyList());
        LoadBalanceSettings settings = LbTestSettings.forTopology(topo);
        conn = new LoadBalanceConnection(settings);
        conn.setConnectionManager(
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        legProperties,
                        settings,
                        new JdbcConnectionFactory() {
                            public Connection getConnection(final String url, final Properties i)
                                    throws SQLException {
                                if (url.contains(":" + NODE1.getId() + ":")) {
                                    throw new SQLException(
                                            "down", null, UErrorCode.CAS_ER_FREE_SERVER);
                                }
                                return new FakePhysicalConnection();
                            }
                        }));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(topo);
    }

    @After
    public void tearDown() throws SQLException {
        if (conn != null) {
            conn.close();
        }
        UUnreachableHostList.getInstance().remove(NODE1.getId());
        UUnreachableHostList.getInstance().remove(NODE2.getId());
        UUnreachableHostList.getInstance().setUseSSL(false);
    }

    /** The mode the health check connects with. */
    private static SslMode healthCheckMode() {
        Object config = LbTestReflection.field(UUnreachableHostList.getInstance(), "sslConfig");
        return (SslMode) LbTestReflection.field(config, "sslMode");
    }

    @Test
    public void aSessionThatListsABrokerTellsTheHealthCheckItsLegsUseTls() throws SQLException {
        Properties tls = new Properties();
        tls.setProperty("useSSL", "true");

        bindWithTheMasterDown(tls);

        assertTrue(UUnreachableHostList.getInstance().contains(NODE1.getId()));
        assertEquals(SslMode.REQUIRED, healthCheckMode());
    }

    @Test
    public void anOptionTheUrlCarriesToTheLegIsReadToo() throws SQLException {
        LoadBalanceSettings settings =
                LbTestSettings.fromUrl(
                        "jdbc:cubrid:loadbalance://hctnode1:33000:33002,hctnode2:33000:33002"
                                + "/testdb:::?useSSL=true");

        new UnreachableEndpoints()
                .healthCheckConnectsAs(settings.buildPhysicalJdbcSpec(new Properties(), NODE1));

        assertEquals(SslMode.REQUIRED, healthCheckMode());
    }

    @Test
    public void aSessionOfPlainLegsTellsItPlain() throws SQLException {
        UUnreachableHostList.getInstance().setUseSSL(true); // as another pool left it

        bindWithTheMasterDown(new Properties());

        assertTrue(UUnreachableHostList.getInstance().contains(NODE1.getId()));
        assertEquals(SslMode.DISABLED, healthCheckMode());
    }

    @Test
    public void legSettingsThatCannotBeReadLeaveTheHealthCheckAsItWas() throws SQLException {
        Properties unreadable = new Properties();
        unreadable.setProperty("sslmode", "verify-ca"); // names no anchors

        bindWithTheMasterDown(unreadable);

        assertTrue(UUnreachableHostList.getInstance().contains(NODE1.getId()));
        assertEquals(SslMode.DISABLED, healthCheckMode());
    }
}
