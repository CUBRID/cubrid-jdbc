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

import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import cubrid.jdbc.lb.config.NodeRole;
import cubrid.jdbc.lb.config.ReadWeight;
import cubrid.jdbc.lb.config.ResolvedRoleTopology;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * readWeight must survive <b>biased connection churn</b> — the pool replacing some of its
 * connections but not others.
 *
 * <p>Offline reproduction of a live-cluster failure (HikariFailoverRecoveryLbTest, 1 master + 1
 * slave + 1 replica, {@code readWeight=slave:40,master:10,replica:50}, pool 30): after two broker
 * off/on cycles the pool's home distribution had moved from 12/3/15 to 8/12/10 and never came back,
 * so the recovery phase failed on a 90s timeout. The homes matched the bindings at every poll, so
 * failover and failback were correct; what drifted was the assignment itself.
 *
 * <p>The bias is structural. A master-home session reads on the RW connection, so a read broker
 * going down never costs it its place, while slave/replica-home sessions are the ones the pool
 * discards and recreates. Balancing only the stream of picks re-draws a home on each replacement
 * with P(master) = the master weight, and a session that drew master never churns again - an
 * absorbing state. Balancing the live population makes the same churn self-correcting.
 */
public class RoleReadSelectorChurnTest {

    private static final String URL =
            "jdbc:cubrid:loadbalance://node1,node2;replica=rep1/db"
                    + "?rwPort=33000&roPort=33002&soPort=33004"
                    + "&readWeight=slave:40,master:10,replica:50";

    private static final int POOL = 30;

    private final RoleReadSelector selector = new RoleReadSelector();

    /**
     * One pooled connection: the role it holds, so the test can close exactly the ones it wants.
     */
    private final List<NodeRole> pool = new ArrayList<NodeRole>();

    private static LoadBalanceSettings configOf(String url) throws SQLException {
        return LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));
    }

    private void bind(
            ResolvedRoleTopology topo, ReadWeight weights, SharedSelectorState state, int count) {
        for (int i = 0; i < count; i++) {
            pool.add(selector.select(topo, weights, state).getRole());
        }
    }

    /**
     * Closes every pooled connection whose home is NOT the master, as a read-broker outage does.
     */
    private int closeNonMasterHomes(SharedSelectorState state) {
        int closed = 0;
        for (int i = pool.size() - 1; i >= 0; i--) {
            if (pool.get(i) != NodeRole.MASTER) {
                RoleReadSelector.release(pool.remove(i), state);
                closed++;
            }
        }

        return closed;
    }

    private int homeCount(NodeRole role) {
        int n = 0;
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i) == role) {
                n++;
            }
        }

        return n;
    }

    private void assertPoolMatchesWeights(String when) {
        assertEquals(when + ": pool size", POOL, pool.size());
        assertEquals(when + ": slave homes", 12, homeCount(NodeRole.SLAVE));
        assertEquals(when + ": master homes", 3, homeCount(NodeRole.MASTER));
        assertEquals(when + ": replica homes", 15, homeCount(NodeRole.REPLICA));
    }

    /**
     * Five rounds of "every read-leg connection is replaced, master-home ones survive". The ratio
     * must be the declared one after each round, not merely on average — the drift this reproduces
     * was monotone, so a per-round assertion is what pins it.
     */
    @Test
    public void biasedChurnKeepsTheDeclaredRatio() throws SQLException {
        LoadBalanceSettings c = configOf(URL);
        ResolvedRoleTopology topo = c.getResolvedTopology();
        ReadWeight weights = c.getReadWeight();
        SharedSelectorState state = new SharedSelectorState();

        bind(topo, weights, state, POOL);
        assertPoolMatchesWeights("warm pool");

        for (int round = 1; round <= 5; round++) {
            final int replaced = closeNonMasterHomes(state);
            bind(topo, weights, state, replaced);
            assertPoolMatchesWeights("after churn round " + round);
        }
    }

    /** Releasing every connection must leave no residue, or the next pool starts off-balance. */
    @Test
    public void drainingThePoolClearsTheLiveCounts() throws SQLException {
        LoadBalanceSettings c = configOf(URL);
        SharedSelectorState state = new SharedSelectorState();

        bind(c.getResolvedTopology(), c.getReadWeight(), state, POOL);
        while (!pool.isEmpty()) {
            RoleReadSelector.release(pool.remove(pool.size() - 1), state);
        }

        assertEquals("no live home left", 0, state.getLiveHomeCounts().size());

        bind(c.getResolvedTopology(), c.getReadWeight(), state, POOL);
        assertPoolMatchesWeights("pool refilled from empty");
    }

    /** An unmatched release must not push a count negative, which would over-select that role. */
    @Test
    public void releaseWithoutSelectIsHarmless() throws SQLException {
        LoadBalanceSettings c = configOf(URL);
        SharedSelectorState state = new SharedSelectorState();

        RoleReadSelector.release(NodeRole.REPLICA, state);
        assertEquals(0, state.liveHomeCount("role:replica"));

        bind(c.getResolvedTopology(), c.getReadWeight(), state, POOL);
        assertPoolMatchesWeights("pool built after a stray release");
    }
}
