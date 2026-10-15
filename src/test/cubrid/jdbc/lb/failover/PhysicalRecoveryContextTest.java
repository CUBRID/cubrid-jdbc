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

package cubrid.jdbc.lb.failover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.SessionLeg;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import cubrid.jdbc.lb.route.Router;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;

public class PhysicalRecoveryContextTest {

    @Test
    public void failedLegMapsFromRouteTarget() {
        assertEquals(SessionLeg.RW, SessionLeg.fromRouteTarget(Router.RouteTarget.TO_READ_WRITE));
        assertEquals(SessionLeg.RO, SessionLeg.fromRouteTarget(Router.RouteTarget.TO_READ_ONLY));
    }

    @Test
    public void contextExposesFieldsAndDefensiveExcludeCopy() {
        Endpoint failed = new Endpoint("ro-bad", 33000);
        EndpointTopology topology =
                new EndpointTopology(
                        new Endpoint("rw", 33000),
                        Arrays.asList(failed, new Endpoint("ro-ok", 33001)),
                        Arrays.asList(new Endpoint("repl", 33002)));

        HashSet<String> exclude = new HashSet<String>();
        exclude.add("ro-bad:33000");

        PhysicalRecoveryContext ctx =
                new PhysicalRecoveryContext(
                        SessionLeg.RO,
                        failed,
                        Router.RouteTarget.TO_READ_ONLY,
                        topology,
                        exclude,
                        false);

        assertEquals(SessionLeg.RO, ctx.getFailedLeg());
        assertSame(failed, ctx.getFailedEndpoint());
        assertEquals(Router.RouteTarget.TO_READ_ONLY, ctx.getRouteTarget());
        assertSame(topology, ctx.getTopology());
        assertFalse(ctx.isTxActive());
        assertEquals(1, ctx.getExcludeEndpointIds().size());
        assertTrue(ctx.getExcludeEndpointIds().contains("ro-bad:33000"));

        exclude.add("other:1");
        assertEquals(1, ctx.getExcludeEndpointIds().size());
    }

    @Test
    public void resultCarriesBoundEndpointAndFallbackReason() {
        Endpoint bound = new Endpoint("rw", 33000);
        PhysicalRecoveryResult result =
                new PhysicalRecoveryResult(
                        bound,
                        SessionLeg.RW,
                        SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON);
        assertSame(bound, result.getBoundEndpoint());
        assertEquals(SessionLeg.RW, result.getBoundLeg());
        assertEquals(
                SessionPhysicalConnManager.RO_PHYSICAL_FAILOVER_REASON, result.getFallbackReason());
    }
}
