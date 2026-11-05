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
import static org.junit.Assert.assertSame;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.util.Arrays;
import org.junit.Test;

/**
 * A session binds as its URL says: master RW from the first host, the read leg from {@code
 * readWeight}. The topology it is handed supplies the failover candidates, not the binding.
 */
public final class UrlTopologyBindingTest {

    private static final Endpoint RW = new Endpoint("rw", 33000);
    private static final Endpoint RO1 = new Endpoint("ro1", 33000);
    private static final Endpoint RO2 = new Endpoint("ro2", 33000);

    @Test
    public void theReadLegIsTheUrlsReadTargetNotTheTopologysFirstRo() throws Exception {
        LoadBalanceConnection conn = connection(LbTestSettings.forBinding(RW, RO2));
        try {
            conn.initSessionBindings(new EndpointTopology(RW, Arrays.asList(RO1, RO2), null));

            assertEquals(RW, conn.getCurrentEp(SessionLeg.RW));
            assertEquals(RO2, conn.getCurrentEp(SessionLeg.RO));
        } finally {
            conn.close();
        }
    }

    @Test
    public void theRwLegIsTheUrlsFirstHostNotTheTopologysFirstRw() throws Exception {
        LoadBalanceConnection conn = connection(LbTestSettings.forBinding(RW, RO1));
        try {
            conn.initSessionBindings(
                    new EndpointTopology(
                            Arrays.asList(new Endpoint("other", 33000), RW),
                            Arrays.asList(RO1),
                            null));

            assertEquals(RW, conn.getCurrentEp(SessionLeg.RW));
        } finally {
            conn.close();
        }
    }

    @Test
    public void aUrlWithoutAReadRoleBindsRwOnlyWhateverTheTopologyLists() throws Exception {
        LoadBalanceConnection conn = connection(LbTestSettings.forBinding(RW, null));
        try {
            conn.initSessionBindings(new EndpointTopology(RW, Arrays.asList(RO1), null));

            Endpoint rw = conn.getCurrentEp(SessionLeg.RW);
            assertEquals(RW, rw);
            assertSame(rw, conn.getCurrentEp(SessionLeg.RO));
        } finally {
            conn.close();
        }
    }

    private static LoadBalanceConnection connection(final LoadBalanceSettings settings) {
        LoadBalanceConnection conn = new LoadBalanceConnection(settings);
        conn.setConnectionManager(new SimpleEndpointConnManager());
        conn.setSharedSelectorState(new SharedSelectorState());
        return conn;
    }
}
