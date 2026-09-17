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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;

public class UnreachableEndpointsTest {

    private static final String BAD_RW = "bad-rw:33000";
    private static final String OK_RW = "ok-rw:33000";

    @After
    public void tearDown() {
        UUnreachableHostList.getInstance().remove(BAD_RW);
        UUnreachableHostList.getInstance().remove(OK_RW);
    }

    @Test
    public void collectUnreachableEndpointIdsFromTopology() {
        UUnreachableHostList.getInstance().add(BAD_RW);

        EndpointTopology topology =
                new EndpointTopology(
                        Arrays.asList(new Endpoint("bad-rw", 33000), new Endpoint("ok-rw", 33000)),
                        Arrays.asList(new Endpoint("ro", 33001)),
                        Collections.<Endpoint>emptyList());

        UnreachableEndpoints filter = new UnreachableEndpoints();
        assertTrue(filter.isUnreachable(new Endpoint("bad-rw", 33000)));
        assertFalse(filter.isUnreachable(new Endpoint("ok-rw", 33000)));
        assertEquals(1, filter.collectUnreachableEndpointIds(topology).size());
        assertTrue(filter.collectUnreachableEndpointIds(topology).contains(BAD_RW));
    }

    @Test
    public void shouldMarkUnreachableMatchesCoreAltHostsErrnos() {
        assertTrue(
                UnreachableEndpoints.shouldMarkUnreachable(
                        new SQLException("comm", null, UErrorCode.ER_COMMUNICATION)));
        assertTrue(
                UnreachableEndpoints.shouldMarkUnreachable(
                        new SQLException("conn", null, UErrorCode.ER_CONNECTION)));
        assertTrue(
                UnreachableEndpoints.shouldMarkUnreachable(
                        new SQLException("timeout", null, UErrorCode.ER_TIMEOUT)));
        assertTrue(
                UnreachableEndpoints.shouldMarkUnreachable(
                        new SQLException("free", null, UErrorCode.CAS_ER_FREE_SERVER)));
        assertFalse(
                UnreachableEndpoints.shouldMarkUnreachable(new SQLException("syntax", null, -493)));
    }

    @Test
    public void markUnreachableRegistersEndpointInSharedList() {
        UnreachableEndpoints filter = new UnreachableEndpoints();
        Endpoint bad = new Endpoint("bad-rw", 33000);

        filter.markUnreachable(bad, new SQLException("comm", null, UErrorCode.ER_COMMUNICATION));
        assertTrue(filter.isUnreachable(bad));

        filter.markReachable(bad);
        assertFalse(filter.isUnreachable(bad));
    }

    @Test
    public void markUnreachableIgnoresNonConnectionFailures() {
        UnreachableEndpoints filter = new UnreachableEndpoints();
        Endpoint bad = new Endpoint("bad-rw", 33000);

        filter.markUnreachable(bad, new SQLException("syntax", null, -493));
        assertFalse(filter.isUnreachable(bad));
    }

    /**
     * Handshake rejections mean "the broker is alive but its CAS cannot attach to a DB server that
     * is valid for this access mode" — an HA promotion moved the standby it was using, or the
     * standby is lagging past {@code ha_delay_limit}. They must count as unreachable, otherwise the
     * failover gate never fires and every statement on that leg keeps failing against the same
     * endpoint.
     */
    @Test
    public void handshakeRejectionsCountAsUnreachable() {
        int[] codes = {
            -743, // ER_NET_SERVER_HAND_SHAKE (umbrella)
            -1139, // ER_NET_HS_INCOMPAT_RW_MODE  (RO/SO reached a now-active server)
            -1140, // ER_NET_HS_HA_REPL_DELAY     (standby/replica lagging)
            -1141, // ER_NET_HS_HA_REPLICA_ONLY
            -1142, // ER_NET_HS_REMOTE_DISABLED
        };
        for (int i = 0; i < codes.length; i++) {
            assertTrue(
                    "errno " + codes[i] + " must mark the endpoint unreachable",
                    UnreachableEndpoints.shouldMarkUnreachable(
                            new SQLException("handshake", null, codes[i])));
        }

        UnreachableEndpoints filter = new UnreachableEndpoints();
        Endpoint replica = new Endpoint("replica-so", 36000);
        filter.markUnreachable(replica, new SQLException("delayed", null, -743));
        assertTrue(filter.isUnreachable(replica));
    }

    /**
     * Server capacity pressure is NOT a handshake rejection: marking it would push every session
     * off that node JVM-wide (writes off the master included) for a condition that usually clears
     * in seconds.
     */
    @Test
    public void clientsExceededIsNotUnreachable() {
        assertFalse(
                "max_clients pressure must stay a plain SQL error",
                UnreachableEndpoints.shouldMarkUnreachable(
                        new SQLException("clients exceeded", null, -669)));
    }

    @Test
    public void ioExceptionCauseMarksUnreachable() {
        assertTrue(
                UnreachableEndpoints.shouldMarkUnreachable(
                        new SQLException("wrap", new SocketException("reset"))));
    }
}
