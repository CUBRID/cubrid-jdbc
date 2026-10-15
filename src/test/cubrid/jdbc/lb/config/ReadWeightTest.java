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
import org.junit.Test;

public class ReadWeightTest {

    private static ParsedRoleTopology topologyOf(String url) throws SQLException {
        return ParsedRoleTopology.fromUrl(LoadBalanceUrlParser.parse(url));
    }

    @Test
    public void parsesAllThreeRoles() throws SQLException {
        ReadWeight w = ReadWeight.parse("slave:40,master:10,replica:50");

        assertEquals(40, w.weightOf(NodeRole.SLAVE));
        assertEquals(10, w.weightOf(NodeRole.MASTER));
        assertEquals(50, w.weightOf(NodeRole.REPLICA));
        assertEquals(100, w.total());
        assertTrue(w.hasReadTarget());
    }

    @Test
    public void unspecifiedRolesDefaultToZero() throws SQLException {
        ReadWeight w = ReadWeight.parse("slave:1");

        assertEquals(1, w.weightOf(NodeRole.SLAVE));
        assertEquals(0, w.weightOf(NodeRole.MASTER));
        assertEquals(0, w.weightOf(NodeRole.REPLICA));
    }

    @Test
    public void masterZeroIsAllowed() throws SQLException {
        ReadWeight w = ReadWeight.parse("slave:50,master:0,replica:50");

        assertEquals(0, w.weightOf(NodeRole.MASTER));
        assertEquals(50, w.weightOf(NodeRole.SLAVE));
        assertEquals(50, w.weightOf(NodeRole.REPLICA));
    }

    @Test
    public void roleLabelsAreCaseInsensitiveAndTrimmed() throws SQLException {
        ReadWeight w = ReadWeight.parse(" Slave : 2 , MASTER:3 ");

        assertEquals(2, w.weightOf(NodeRole.SLAVE));
        assertEquals(3, w.weightOf(NodeRole.MASTER));
    }

    @Test
    public void allZeroHasNoReadTarget() throws SQLException {
        assertFalse(ReadWeight.parse("master:0").hasReadTarget());
    }

    @Test
    public void defaultWithMasterSlaveReplicaAllOne() throws SQLException {
        ReadWeight w =
                ReadWeight.defaultsFor(topologyOf("jdbc:cubrid:loadbalance://n1,n2;replica=r1/db"));

        assertEquals(1, w.weightOf(NodeRole.MASTER));
        assertEquals(1, w.weightOf(NodeRole.SLAVE));
        assertEquals(1, w.weightOf(NodeRole.REPLICA));
    }

    @Test
    public void defaultWithoutReplicaExcludesReplica() throws SQLException {
        ReadWeight w = ReadWeight.defaultsFor(topologyOf("jdbc:cubrid:loadbalance://n1,n2/db"));

        assertEquals(1, w.weightOf(NodeRole.MASTER));
        assertEquals(1, w.weightOf(NodeRole.SLAVE));
        assertEquals(0, w.weightOf(NodeRole.REPLICA));
    }

    @Test
    public void defaultSingleHostIsMasterOnly() throws SQLException {
        ReadWeight w = ReadWeight.defaultsFor(topologyOf("jdbc:cubrid:loadbalance://n1/db"));

        assertEquals(1, w.weightOf(NodeRole.MASTER));
        assertEquals(0, w.weightOf(NodeRole.SLAVE));
        assertEquals(0, w.weightOf(NodeRole.REPLICA));
    }

    @Test
    public void resolveUsesDefaultsWhenSpecAbsent() throws SQLException {
        ParsedRoleTopology topo = topologyOf("jdbc:cubrid:loadbalance://n1,n2;replica=r1/db");

        assertEquals(1, ReadWeight.resolve(null, topo).weightOf(NodeRole.REPLICA));
        assertEquals(1, ReadWeight.resolve("   ", topo).weightOf(NodeRole.SLAVE));
    }

    @Test
    public void resolveParsesWhenSpecPresent() throws SQLException {
        ParsedRoleTopology topo = topologyOf("jdbc:cubrid:loadbalance://n1,n2;replica=r1/db");
        ReadWeight w = ReadWeight.resolve("slave:40,master:10,replica:50", topo);

        assertEquals(40, w.weightOf(NodeRole.SLAVE));
    }

    @Test
    public void rejectsNegativeWeight() {
        assertParseFails("slave:-1");
    }

    @Test
    public void rejectsNonNumericWeight() {
        assertParseFails("slave:x");
    }

    @Test
    public void rejectsUnknownRole() {
        assertParseFails("primary:1");
    }

    @Test
    public void rejectsDuplicateRole() {
        assertParseFails("slave:1,slave:2");
    }

    @Test
    public void rejectsMalformedEntry() {
        assertParseFails("slave");
        assertParseFails("slave:");
        assertParseFails(":1");
        assertParseFails("slave:1,,master:2");
    }

    private static void assertParseFails(String spec) {
        try {
            ReadWeight.parse(spec);
            fail("expected SQLException for readWeight: " + spec);
        } catch (SQLException expected) {
        }
    }
}
