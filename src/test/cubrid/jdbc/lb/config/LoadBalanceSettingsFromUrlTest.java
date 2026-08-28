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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.Properties;
import org.junit.Test;

public class LoadBalanceSettingsFromUrlTest {

    private static LoadBalanceSettings configOf(String url) throws SQLException {
        return LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));
    }

    @Test
    public void mapsRolesPortsAndExplicitReadWeight() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2,node3;replica=rep1/testdb:dba:secret:"
                                + "?rwPort=33000&roPort=33002&soPort=33004"
                                + "&readWeight=slave:40,master:10,replica:50");

        assertTrue(c.isUrlDerived());

        // roles + ports
        ResolvedRoleTopology t = c.getResolvedTopology();
        assertEquals("node1:33000", t.getMaster().getRw().getId());
        assertEquals("node1:33002", t.getMaster().getRo().getId());
        assertEquals(2, t.getSlaves().size());
        assertEquals("node2:33000", t.getSlaves().get(0).getRw().getId());
        assertEquals("node3:33002", t.getSlaves().get(1).getRo().getId());
        assertEquals("rep1:33004", t.getReplicas().get(0).getSo().getId());

        // readWeight
        ReadWeight w = c.getReadWeight();
        assertEquals(40, w.weightOf(NodeRole.SLAVE));
        assertEquals(10, w.weightOf(NodeRole.MASTER));
        assertEquals(50, w.weightOf(NodeRole.REPLICA));

        // credentials + db
        assertEquals("testdb", c.getDatabaseName());
        assertEquals("dba", c.getUrlUser());
        assertEquals("secret", c.getUrlPassword());
        assertEquals("", c.getUrlVariant());
    }

    @Test
    public void appliesDefaultReadWeightWhenAbsent() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2;replica=rep1/testdb?rwPort=33000&roPort=33002&soPort=33004");

        ReadWeight w = c.getReadWeight();
        assertEquals(1, w.weightOf(NodeRole.MASTER));
        assertEquals(1, w.weightOf(NodeRole.SLAVE));
        assertEquals(1, w.weightOf(NodeRole.REPLICA));
    }

    @Test
    public void pinsSection52FixedDefaults() throws SQLException {
        LoadBalanceSettings c =
                configOf("jdbc:cubrid:loadbalance://node1,node2/testdb?rwPort=33000&roPort=33002");

        assertEquals(LoadBalanceSettings.DISTRIBUTION_MODE_SESSION, c.getDistributionMode());
        assertTrue(c.isRoPhysicalFailoverToRw());
        assertTrue(c.isRuntimeFailoverEnabled());
        assertTrue(c.isRuntimeFailoverRetryOnce());
        assertEquals(1, c.getRuntimeFailoverMaxAttempts());
        assertEquals(8192, c.getSqlClassifyCacheMaxEntries());
    }

    @Test
    public void retainsCommonOptionsAndDropsModeMismatchedOnes() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2/testdb"
                                + "?rwPort=33000&roPort=33002&charSet=utf-8&altHosts=h9:1");

        assertTrue(c.getPropagatedOptions().containsKey("charSet"));
        // altHosts is classic-only -> ignored in loadbalance mode
        assertFalse(c.getPropagatedOptions().containsKey("altHosts"));
    }

    @Test
    public void variantIsCarriedThrough() throws SQLException {
        LoadBalanceSettings c =
                configOf("jdbc:cubrid-mysql:loadbalance://node1/testdb?rwPort=33000&roPort=33002");

        assertEquals("-mysql", c.getUrlVariant());
    }

    @Test
    public void legacyPropertyConfigIsNotUrlDerived() {
        LoadBalanceSettings c = LoadBalanceSettings.of(new Properties());

        assertFalse(c.isUrlDerived());
        assertNull(c.getResolvedTopology());
        assertNull(c.getReadWeight());
    }

    @Test
    public void propagatesPortResolutionErrors() {
        // an invalid global port value fails resolution -> the error surfaces through
        // LoadBalanceSettings (omitted ports now default, so a bad value is the resolution error).
        try {
            configOf("jdbc:cubrid:loadbalance://node1,node2/testdb?rwPort=notaport");
            fail("expected SQLException for invalid rwPort value");
        } catch (SQLException expected) {
        }
    }
}
