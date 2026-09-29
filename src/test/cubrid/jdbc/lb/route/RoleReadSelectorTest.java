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

package cubrid.jdbc.lb.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import cubrid.jdbc.lb.config.NodeRole;
import cubrid.jdbc.lb.config.ReadWeight;
import cubrid.jdbc.lb.config.ResolvedRoleTopology;
import cubrid.jdbc.lb.route.RoleReadSelector.ReadTarget;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class RoleReadSelectorTest {

    private final RoleReadSelector selector = new RoleReadSelector();

    private static LoadBalanceSettings configOf(String url) throws SQLException {
        return LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));
    }

    private static int count(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        return v != null ? v.intValue() : 0;
    }

    private Map<String, Integer> runEndpoints(
            ResolvedRoleTopology topo, ReadWeight w, SharedSelectorState s, int times) {
        Map<String, Integer> tally = new HashMap<String, Integer>();
        for (int i = 0; i < times; i++) {
            ReadTarget t = selector.select(topo, w, s);
            String id = t.getEndpoint().getId();
            tally.put(id, Integer.valueOf(count(tally, id) + 1));
        }

        return tally;
    }

    private Map<String, Integer> runRoles(
            ResolvedRoleTopology topo, ReadWeight w, SharedSelectorState s, int times) {
        Map<String, Integer> tally = new HashMap<String, Integer>();
        for (int i = 0; i < times; i++) {
            String role = selector.select(topo, w, s).getRole().label();
            tally.put(role, Integer.valueOf(count(tally, role) + 1));
        }

        return tally;
    }

    /**
     * Worked example: master + slave{node2,node3} + replica{rep1}, readWeight default (1:1:1), pool
     * 9 → master 3, slave 3 (node2×2, node3×1), replica 3 (rep1×3).
     */
    @Test
    public void defaultEqualWeightsPoolOf9() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2,node3;replica=rep1/db"
                                + "?rwPort=33000&roPort=33002&soPort=33004");
        SharedSelectorState s = new SharedSelectorState();

        Map<String, Integer> roles = runRoles(c.getResolvedTopology(), c.getReadWeight(), s, 9);
        assertEquals(3, count(roles, "master"));
        assertEquals(3, count(roles, "slave"));
        assertEquals(3, count(roles, "replica"));

        s.resetAll();
        Map<String, Integer> eps = runEndpoints(c.getResolvedTopology(), c.getReadWeight(), s, 9);
        assertEquals(3, count(eps, "node1:33000")); // master, RW reuse
        assertEquals(2, count(eps, "node2:33002")); // slave RO
        assertEquals(1, count(eps, "node3:33002")); // slave RO
        assertEquals(3, count(eps, "rep1:33004")); // replica SO
    }

    /**
     * readWeight=slave:40,master:10,replica:50, pool 10 → master 1, slave 4 (node2×2, node3×2),
     * replica 5 (rep1×5).
     */
    @Test
    public void weightedPoolOf10() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2,node3;replica=rep1/db"
                                + "?rwPort=33000&roPort=33002&soPort=33004"
                                + "&readWeight=slave:40,master:10,replica:50");
        SharedSelectorState s = new SharedSelectorState();

        Map<String, Integer> roles = runRoles(c.getResolvedTopology(), c.getReadWeight(), s, 10);
        assertEquals(1, count(roles, "master"));
        assertEquals(4, count(roles, "slave"));
        assertEquals(5, count(roles, "replica"));

        s.resetAll();
        Map<String, Integer> eps = runEndpoints(c.getResolvedTopology(), c.getReadWeight(), s, 10);
        assertEquals(1, count(eps, "node1:33000"));
        assertEquals(2, count(eps, "node2:33002"));
        assertEquals(2, count(eps, "node3:33002"));
        assertEquals(5, count(eps, "rep1:33004"));
    }

    @Test
    public void masterZeroExcludesMasterFromReads() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2;replica=rep1/db"
                                + "?rwPort=33000&roPort=33002&soPort=33004"
                                + "&readWeight=slave:50,master:0,replica:50");
        SharedSelectorState s = new SharedSelectorState();

        Map<String, Integer> roles = runRoles(c.getResolvedTopology(), c.getReadWeight(), s, 10);
        assertEquals(0, count(roles, "master"));
        assertEquals(5, count(roles, "slave"));
        assertEquals(5, count(roles, "replica"));
    }

    @Test
    public void roleWithoutNodesIsIgnored() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2/db"
                                + "?rwPort=33000&roPort=33002&readWeight=slave:1,replica:5");
        SharedSelectorState s = new SharedSelectorState();

        Map<String, Integer> roles = runRoles(c.getResolvedTopology(), c.getReadWeight(), s, 6);
        assertEquals(6, count(roles, "slave")); // only slave is eligible
        assertEquals(0, count(roles, "replica"));
        assertEquals(0, count(roles, "master"));
    }

    @Test
    public void singleHostFallsBackToMasterRw() throws SQLException {
        LoadBalanceSettings c =
                configOf("jdbc:cubrid:loadbalance://node1/db?rwPort=33000&roPort=33002");
        SharedSelectorState s = new SharedSelectorState();

        Map<String, Integer> eps = runEndpoints(c.getResolvedTopology(), c.getReadWeight(), s, 5);
        assertEquals(5, count(eps, "node1:33000"));

        ReadTarget t = selector.select(c.getResolvedTopology(), c.getReadWeight(), s);
        assertEquals(NodeRole.MASTER, t.getRole());
        assertTrue(t.reusesRw());
    }
}
