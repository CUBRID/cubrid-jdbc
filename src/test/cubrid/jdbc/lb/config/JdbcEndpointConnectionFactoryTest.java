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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.Properties;
import org.junit.Test;

public class JdbcEndpointConnectionFactoryTest {

    private static final String LOGICAL_BASE = "jdbc:cubrid:localhost:30000:testdb:public::";

    @Test
    public void physicalUrlReplacesHostPortForRwMasterEndpoint() throws SQLException {
        Endpoint master = new Endpoint("master-broker", 33000);
        JdbcPhyConnSpec spec = JdbcEndpointConnSpecFactory.forEndpoint(LOGICAL_BASE, null, master);
        assertEquals("jdbc:cubrid:master-broker:33000:testdb:public::", spec.getJdbcUrl());
    }

    @Test
    public void physicalUrlReplacesHostPortForRoEndpoint() throws SQLException {
        Endpoint ro = new Endpoint("slave-ro", 33001);
        JdbcPhyConnSpec spec = JdbcEndpointConnSpecFactory.forEndpoint(LOGICAL_BASE, null, ro);
        assertEquals("jdbc:cubrid:slave-ro:33001:testdb:public::", spec.getJdbcUrl());
    }

    @Test
    public void connectionPropertiesReflectUserAndPasswordFromClientInfo() throws SQLException {
        Properties info = new Properties();
        info.setProperty("user", "appuser");
        info.setProperty("password", "secret");
        JdbcPhyConnSpec spec =
                JdbcEndpointConnSpecFactory.forEndpoint(LOGICAL_BASE, info, new Endpoint("h", 1));
        Properties p = spec.getConnectionProperties();
        assertEquals("appuser", p.getProperty("user"));
        assertEquals("secret", p.getProperty("password"));
        assertTrue(spec.getJdbcUrl().contains("appuser"));
        // Password is carried in the connection Properties only; it must not leak into the URL
        // string (which can surface in logs / exception messages).
        assertFalse(spec.getJdbcUrl().contains("secret"));
    }

    @Test
    public void stripsLbBootstrapParametersFromQueryString() throws SQLException {
        String url =
                "jdbc:cubrid:localhost:30000:testdb:public::?haLbMode=true&haLbConfig=/tmp/x&altHosts=foo";
        JdbcPhyConnSpec spec =
                JdbcEndpointConnSpecFactory.forEndpoint(url, null, new Endpoint("x", 9));
        String out = spec.getJdbcUrl();
        assertFalse(out.contains("altHosts"));
        assertFalse(out.contains("haLbMode"));
        assertFalse(out.contains("haLbConfig"));
    }

    /**
     * The {@code lbLog*} options configure the LB layer's own log file. A physical broker has no
     * use for them, and propagating them would put a filesystem path on every physical URL — the
     * string that surfaces in CAS logs and exception messages.
     */
    @Test
    public void fileLoggingOptionsDoNotReachPhysicalBrokerUrls() throws SQLException {
        String url =
                "jdbc:cubrid:loadbalance://192.168.2.196:30000:33000,192.168.2.197:30000"
                        + "/testdb:dba:pw:?charSet=utf-8"
                        + "&lbLogFile=/var/log/cubrid_lb.log&lbLogLevel=FINE&lbLogToConsole=false";
        LoadBalanceSettings config = LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));

        JdbcPhyConnSpec spec =
                JdbcEndpointConnSpecFactory.forEndpointFromConfig(
                        config, null, new Endpoint("192.168.2.196", 30000));
        String out = spec.getJdbcUrl();

        assertFalse(out.contains("lbLogFile"));
        assertFalse(out.contains("/var/log/cubrid_lb.log"));
        assertFalse(out.contains("lbLogLevel"));
        assertFalse(out.contains("lbLogToConsole"));
        assertTrue("unrelated options still propagate", out.contains("charSet=utf-8"));
    }

    @Test
    public void stripLbQueryHelperRemovesCubridLbPrefix() {
        assertEquals("", JdbcEndpointConnSpecFactory.stripLbBootstrapQuery(null));
        assertEquals("", JdbcEndpointConnSpecFactory.stripLbBootstrapQuery(""));
        assertEquals(
                "?keep=1",
                JdbcEndpointConnSpecFactory.stripLbBootstrapQuery("?keep=1&haLbMode=true"));
        assertEquals(
                "?other=1",
                JdbcEndpointConnSpecFactory.stripLbBootstrapQuery(
                        "?cubrid.lb.distribution.mode=session&other=1"));
    }

    @Test
    public void loadBalanceSettingsPhysicalCredentialOverride() throws SQLException {
        Properties raw = new Properties();
        raw.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        raw.setProperty(LoadBalanceSettings.KEY_PHYSICAL_JDBC_USER, "physUser");
        raw.setProperty(LoadBalanceSettings.KEY_PHYSICAL_JDBC_PASSWORD, "physPass");
        LoadBalanceSettings cfg = LoadBalanceSettings.of(raw);
        JdbcPhyConnSpec spec =
                cfg.buildPhysicalJdbcSpec(LOGICAL_BASE, new Properties(), new Endpoint("h", 2));
        assertEquals("physUser", spec.getConnectionProperties().getProperty("user"));
        assertEquals("physPass", spec.getConnectionProperties().getProperty("password"));
    }

    // 05 M3: cubrid.lb.* keys configure the (now fixed-default) LB layer and are read nowhere.
    // They must not leak onto the physical broker URL, while ordinary options still propagate.
    @Test
    public void cubridLbOptionsExcludedFromPhysicalUrl() throws SQLException {
        String url =
                "jdbc:cubrid:loadbalance://node1,node2/testdb"
                        + "?rwPort=33000&roPort=33002"
                        + "&cubrid.lb.runtime.failover.enabled=false&charSet=utf8";
        LoadBalanceSettings cfg = LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));

        JdbcPhyConnSpec spec =
                cfg.buildPhysicalJdbcSpec(url, new Properties(), new Endpoint("node1", 33000));
        String out = spec.getJdbcUrl();

        assertFalse(out.contains("cubrid.lb."));
        assertTrue(out.contains("charSet=utf8"));
    }

    @Test
    public void invalidUrlThrows() throws SQLException {
        try {
            JdbcEndpointConnSpecFactory.forEndpoint("jdbc:other:foo", null, new Endpoint("h", 1));
            fail("expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("Invalid"));
        }
    }
}
