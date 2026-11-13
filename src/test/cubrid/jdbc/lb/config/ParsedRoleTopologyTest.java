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

import cubrid.jdbc.lb.config.LoadBalanceUrlParser.ParsedUrl;
import java.sql.SQLException;
import java.util.Arrays;
import org.junit.Test;

public class ParsedRoleTopologyTest {

    private static ParsedRoleTopology topologyOf(String url) throws SQLException {
        return ParsedRoleTopology.fromUrl(LoadBalanceUrlParser.parse(url));
    }

    @Test
    public void assignsFirstHostAsMasterRestAsSlavesAndReplicaList() throws SQLException {
        ParsedRoleTopology t =
                topologyOf("jdbc:cubrid:loadbalance://node1,node2,node3;replica=rep1/testdb");

        assertEquals("node1", t.getMasterHost());
        assertEquals(Arrays.asList("node2", "node3"), t.getSlaveHosts());
        assertEquals(Arrays.asList("rep1"), t.getReplicaHosts());

        assertTrue(t.hasSlaves());
        assertTrue(t.hasReplicas());
    }

    @Test
    public void singleHostIsMasterOnly() throws SQLException {
        ParsedRoleTopology t = topologyOf("jdbc:cubrid:loadbalance://node1/testdb");

        assertEquals("node1", t.getMasterHost());
        assertFalse(t.hasSlaves());
        assertFalse(t.hasReplicas());
        assertTrue(t.getSlaveHosts().isEmpty());
        assertTrue(t.getReplicaHosts().isEmpty());
    }

    @Test
    public void hostsWithoutReplicaYieldNoReplicas() throws SQLException {
        ParsedRoleTopology t = topologyOf("jdbc:cubrid:loadbalance://node1,node2/testdb");

        assertEquals("node1", t.getMasterHost());
        assertEquals(Arrays.asList("node2"), t.getSlaveHosts());
        assertFalse(t.hasReplicas());
    }

    @Test
    public void multipleReplicasPreservedInOrder() throws SQLException {
        ParsedRoleTopology t =
                topologyOf("jdbc:cubrid:loadbalance://node1,node2;replica=rep1,rep2,rep3/testdb");

        assertEquals(Arrays.asList("node2"), t.getSlaveHosts());
        assertEquals(Arrays.asList("rep1", "rep2", "rep3"), t.getReplicaHosts());
    }

    /**
     * Inline-port info from the parser is carried through to the role nodes (resolved later, T4).
     */
    @Test
    public void carriesInlinePortTokensThrough() throws SQLException {
        ParsedRoleTopology t =
                topologyOf(
                        "jdbc:cubrid:loadbalance://node1:33000:33002,node2:34000:34002"
                                + ";replica=rep1:33004/testdb");

        assertEquals(33000, t.getMaster().getRwPort());
        assertEquals(33002, t.getMaster().getRoPort());
        assertEquals(34000, t.getSlaves().get(0).getRwPort());
        assertEquals(33004, t.getReplicas().get(0).getSoPort());
    }

    @Test
    public void fromNullParsedUrlThrows() {
        try {
            ParsedRoleTopology.fromUrl((ParsedUrl) null);
            fail("expected SQLException for null parsed URL");
        } catch (SQLException expected) {
        }
    }

    @Test
    public void masterRoleMapsToRwGroupAndReusesRw() {
        assertEquals(ReadBrokerKind.RW, NodeRole.MASTER.readGroup());
        assertTrue(NodeRole.MASTER.reusesRwForRead());
        assertEquals("master", NodeRole.MASTER.label());
    }

    @Test
    public void slaveRoleMapsToRoGroupWithoutRwReuse() {
        assertEquals(ReadBrokerKind.RO, NodeRole.SLAVE.readGroup());
        assertFalse(NodeRole.SLAVE.reusesRwForRead());
        assertEquals("slave", NodeRole.SLAVE.label());
    }

    @Test
    public void replicaRoleMapsToReplGroupWithoutRwReuse() {
        assertEquals(ReadBrokerKind.REPL, NodeRole.REPLICA.readGroup());
        assertFalse(NodeRole.REPLICA.reusesRwForRead());
        assertEquals("replica", NodeRole.REPLICA.label());
    }
}
