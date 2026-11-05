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

import java.sql.SQLException;
import java.util.Properties;
import org.junit.Test;

public class JdbcEndpointConnectionFactoryTest {

    private static final String LB_BASE =
            "jdbc:cubrid:loadbalance://master-broker:33000:33001/testdb:public::";

    @Test
    public void physicalUrlReplacesHostPortForRwMasterEndpoint() throws SQLException {
        Endpoint master = new Endpoint("master-broker", 33000);
        JdbcPhyConnSpec spec = spec(LB_BASE, null, master);
        assertEquals("jdbc:cubrid:master-broker:33000:testdb:public::", spec.getJdbcUrl());
    }

    @Test
    public void physicalUrlReplacesHostPortForRoEndpoint() throws SQLException {
        Endpoint ro = new Endpoint("slave-ro", 33001);
        JdbcPhyConnSpec spec = spec(LB_BASE, null, ro);
        assertEquals("jdbc:cubrid:slave-ro:33001:testdb:public::", spec.getJdbcUrl());
    }

    @Test
    public void connectionPropertiesReflectUserAndPasswordFromClientInfo() throws SQLException {
        Properties info = new Properties();
        info.setProperty("user", "appuser");
        info.setProperty("password", "secret");
        JdbcPhyConnSpec spec = spec(LB_BASE, info, new Endpoint("h", 1));
        Properties p = spec.getConnectionProperties();
        assertEquals("appuser", p.getProperty("user"));
        assertEquals("secret", p.getProperty("password"));
        assertTrue(spec.getJdbcUrl().contains("appuser"));
        // Password is carried in the connection Properties only; it must not leak into the URL
        // string (which can surface in logs / exception messages).
        assertFalse(spec.getJdbcUrl().contains("secret"));
    }

    /**
     * The broker must get the credentials a classic connection would send: as given - a password
     * may begin or end with a space - and from {@code info} whenever it has the key, even empty.
     */
    @Test
    public void credentialsFollowTheClassicRules() throws SQLException {
        Properties info = new Properties();

        info.setProperty("password", " spaced ");
        assertEquals(" spaced ", physicalPassword(info));

        info.setProperty("password", "");
        assertEquals(
                "an empty password in info still wins over the URL's", "", physicalPassword(info));

        info.remove("password");
        assertEquals("urlpass", physicalPassword(info));
    }

    /**
     * The LB-only options configure the LB layer. A physical broker has no use for them, and they
     * would only surface again in CAS logs and exception messages that echo the URL.
     */
    @Test
    public void loadBalanceOnlyOptionsDoNotReachPhysicalBrokerUrls() throws SQLException {
        String out =
                spec(
                                "jdbc:cubrid:loadbalance://192.168.2.196:30000:33000,192.168.2.197:30000"
                                        + "/testdb:dba:pw:?charSet=utf-8&readWeight=master:1"
                                        + "&lbLogSql=off&lbLogDistIntervalSec=60"
                                        + "&metricsEnabled=true&metricsIntervalSec=15",
                                null,
                                new Endpoint("192.168.2.196", 30000))
                        .getJdbcUrl();

        assertFalse(out.contains("readWeight"));
        assertFalse(out.contains("lbLogSql"));
        assertFalse(out.contains("lbLogDistIntervalSec"));
        assertFalse(out.contains("metricsEnabled"));
        assertFalse(out.contains("metricsIntervalSec"));
        assertTrue("unrelated options still propagate", out.contains("charSet=utf-8"));
    }

    private static String physicalPassword(final Properties info) throws SQLException {
        return spec("jdbc:cubrid:loadbalance://h:1/testdb:dba:urlpass:", info, new Endpoint("h", 1))
                .getConnectionProperties()
                .getProperty("password");
    }

    private static JdbcPhyConnSpec spec(
            final String loadBalanceUrl, final Properties info, final Endpoint endpoint)
            throws SQLException {
        LoadBalanceSettings config =
                LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(loadBalanceUrl));
        return JdbcEndpointConnSpecFactory.forEndpoint(config, info, endpoint);
    }
}
