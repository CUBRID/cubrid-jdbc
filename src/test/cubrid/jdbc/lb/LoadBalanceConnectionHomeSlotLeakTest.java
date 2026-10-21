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

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import org.junit.Test;

/**
 * A bind that loses a race to {@code close()} must not leave a weighted-home slot taken in the
 * pool-shared selector state.
 *
 * <p>{@code RoleReadSelector.select()} takes the slot before the session can publish it into {@code
 * homeReadRole}. A {@code close()} landing in that window finds {@code homeReadRole} still null -
 * the bind cleared it on entry - so it releases nothing, and its own {@code closed} guard stops it
 * running again. The slot would stay taken for the pool's whole life and that role would be
 * under-selected forever.
 *
 * <p>The window is reproduced deterministically by closing the connection from inside {@code
 * acquireIndexByPopulation} — the instant after the slot is taken and before the bind publishes it.
 */
public class LoadBalanceConnectionHomeSlotLeakTest {

    private static final String LB_URL =
            "jdbc:cubrid:loadbalance://node1,node2;replica=rep1/testdb:dba::"
                    + "?rwPort=33000&roPort=33002&soPort=33004"
                    + "&readWeight=slave:40,master:10,replica:50";

    @Test
    public void assertCloseRacingBindLeavesNoHomeSlotTaken() throws Exception {
        LoadBalanceSettings config =
                LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(LB_URL));
        final LoadBalanceConnection conn = new LoadBalanceConnection(config);

        // Closes the connection exactly between "slot taken" and "session owns the slot".
        SharedSelectorState racing =
                new SharedSelectorState() {
                    @Override
                    public synchronized int acquireIndexByPopulation(
                            final String[] keys, final int[] weights) {
                        final int idx = super.acquireIndexByPopulation(keys, weights);
                        try {
                            conn.close(); // the pool-eviction close() this guards against
                        } catch (SQLException ex) {
                            throw new IllegalStateException(ex);
                        }
                        return idx;
                    }
                };
        conn.setSharedSelectorState(racing);
        // The physical bind must succeed, or the finally-release masks the leak.
        conn.setConnectionManager(new SimpleEndpointConnManager());

        boolean refused = false;
        try {
            conn.initSessionBindings(topology());
        } catch (SQLException expected) {
            refused = true;
        }

        // The leak itself is the point, so assert it first: a slot still held here biases every
        // later selection this pool makes, for as long as the pool lives.
        assertEquals(
                "the slot select() took must be given back when the bind loses to close()",
                0,
                totalHomes(racing));
        assertTrue("a bind that lost to close() must not complete", refused);
    }

    private static int totalHomes(final SharedSelectorState st) {
        int n = 0;
        for (Map.Entry<String, Integer> e : st.getLiveHomeCounts().entrySet()) {
            n += e.getValue().intValue();
        }
        return n;
    }

    private static EndpointTopology topology() {
        return new EndpointTopology(
                Arrays.asList(new Endpoint("node1", 33000)),
                Arrays.asList(new Endpoint("node2", 33002), new Endpoint("rep1", 33004)),
                Arrays.asList(new Endpoint("rep1", 33004)));
    }
}
