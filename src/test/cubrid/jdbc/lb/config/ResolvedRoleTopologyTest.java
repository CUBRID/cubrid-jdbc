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
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.config.LoadBalanceUrlParser.ParsedUrl;
import cubrid.jdbc.lb.config.ResolvedRoleTopology.ResolvedNode;
import java.sql.SQLException;
import org.junit.Test;

public class ResolvedRoleTopologyTest {

    private static ResolvedRoleTopology resolveOf(String url) throws SQLException {
        ParsedUrl parsed = LoadBalanceUrlParser.parse(url);
        return ResolvedRoleTopology.resolve(
                ParsedRoleTopology.fromUrl(parsed), parsed.getOptions());
    }

    @Test
    public void resolvesGlobalPortsForAllNodes() throws SQLException {
        ResolvedRoleTopology t =
                resolveOf(
                        "jdbc:cubrid:loadbalance://node1,node2,node3;replica=rep1/testdb"
                                + "?rwPort=33000&roPort=33002&soPort=33004");

        assertEquals("node1:33000", t.getMaster().getRw().getId());
        assertEquals("node1:33002", t.getMaster().getRo().getId());

        assertEquals(2, t.getSlaves().size());
        assertEquals("node2:33000", t.getSlaves().get(0).getRw().getId());
        assertEquals("node2:33002", t.getSlaves().get(0).getRo().getId());
        assertEquals("node3:33000", t.getSlaves().get(1).getRw().getId());
        assertEquals("node3:33002", t.getSlaves().get(1).getRo().getId());

        assertEquals(1, t.getReplicas().size());
        assertEquals("rep1:33004", t.getReplicas().get(0).getSo().getId());
    }

    @Test
    public void resolvesInlinePortsPerNode() throws SQLException {
        ResolvedRoleTopology t =
                resolveOf(
                        "jdbc:cubrid:loadbalance://node1:33000:33002,node2:34000:34002,node3:35000:35002"
                                + ";replica=rep1:33004/testdb");

        assertEquals("node1:33000", t.getMaster().getRw().getId());
        assertEquals("node1:33002", t.getMaster().getRo().getId());
        assertEquals("node2:34000", t.getSlaves().get(0).getRw().getId());
        assertEquals("node2:34002", t.getSlaves().get(0).getRo().getId());
        assertEquals("node3:35000", t.getSlaves().get(1).getRw().getId());
        assertEquals("node3:35002", t.getSlaves().get(1).getRo().getId());
        assertEquals("rep1:33004", t.getReplicas().get(0).getSo().getId());
    }

    @Test
    public void inlinePortTakesPrecedenceOverGlobal() throws SQLException {
        ResolvedRoleTopology t =
                resolveOf(
                        "jdbc:cubrid:loadbalance://node1:33000:33002,node2/testdb"
                                + "?rwPort=9000&roPort=9002");

        ResolvedNode master = t.getMaster();
        assertEquals("node1:33000", master.getRw().getId()); // inline wins
        assertEquals("node1:33002", master.getRo().getId()); // inline wins

        ResolvedNode slave = t.getSlaves().get(0);
        assertEquals("node2:9000", slave.getRw().getId()); // falls back to global
        assertEquals("node2:9002", slave.getRo().getId());
    }

    @Test
    public void missingRwPortForSlaveUsesDefault() throws SQLException {
        ResolvedRoleTopology t =
                resolveOf(
                        "jdbc:cubrid:loadbalance://node1:33000:33002,node2:34000:34002,node3"
                                + ";replica=rep1:33004/testdb"
                                + "?roPort=33002&soPort=33004&readWeight=slave:50,master:0,replica:50");

        // node3: no inline ports, no global rwPort → RW defaults to 30000; RO from global roPort.
        assertEquals("node3:30000", t.getSlaves().get(1).getRw().getId());
        assertEquals("node3:33002", t.getSlaves().get(1).getRo().getId());
    }

    @Test
    public void missingRoPortForMasterUsesDefault() throws SQLException {
        ResolvedRoleTopology t =
                resolveOf("jdbc:cubrid:loadbalance://node1,node2/testdb?rwPort=33000");

        // no inline RO, no global roPort → RO defaults to 33000.
        assertEquals("node1:33000", t.getMaster().getRw().getId());
        assertEquals("node1:33000", t.getMaster().getRo().getId());
    }

    @Test
    public void missingSoPortForReplicaUsesDefault() throws SQLException {
        ResolvedRoleTopology t =
                resolveOf(
                        "jdbc:cubrid:loadbalance://node1;replica=rep1/testdb?rwPort=33000&roPort=33002");

        // rep1: no inline SO port, no global soPort → SO defaults to 36000.
        assertEquals("rep1:36000", t.getReplicas().get(0).getSo().getId());
    }

    @Test
    public void invalidGlobalPortIsError() {
        assertResolveFails("jdbc:cubrid:loadbalance://node1/testdb?rwPort=notaport&roPort=33002");
        assertResolveFails("jdbc:cubrid:loadbalance://node1/testdb?rwPort=70000&roPort=33002");
    }

    @Test
    public void resolvesSingleNodeWithGlobalRoPort() throws SQLException {
        ResolvedRoleTopology t = resolveOf("jdbc:cubrid://localhost:33000/demodb?roPort=33002");

        assertEquals("localhost:33000", t.getMaster().getRw().getId());
        assertEquals("localhost:33002", t.getMaster().getRo().getId());
        assertEquals(0, t.getSlaves().size());
        assertEquals(0, t.getReplicas().size());
    }

    private static void assertResolveFails(String url) {
        try {
            resolveOf(url);
            fail("expected SQLException for URL: " + url);
        } catch (SQLException expected) {
        }
    }
}
