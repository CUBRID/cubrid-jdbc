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

package cubrid.jdbc.lb.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import cubrid.jdbc.lb.SessionLeg;
import cubrid.jdbc.lb.config.Endpoint;
import org.junit.Test;

public final class SimpleBrokerConnectionManagerTest {

    @Test
    public void assertBindSessionEndpointsAcquiresBothEndpoints() throws Exception {
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        Endpoint master = new Endpoint("rw", 33000);
        Endpoint ro = new Endpoint("ro1", 33000);

        manager.bindSession(master, ro);

        assertEquals(master, manager.getSessionEndpoint(SessionLeg.RW));
        assertEquals(ro, manager.getSessionEndpoint(SessionLeg.RO));
        assertEquals(1, manager.getAcquireCount(master.getId()));
        assertEquals(1, manager.getAcquireCount(ro.getId()));
    }

    @Test
    public void assertReleaseBindingsReleasesBothEndpoints() throws Exception {
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        Endpoint master = new Endpoint("rw", 33000);
        Endpoint ro = new Endpoint("ro1", 33000);
        manager.bindSession(master, ro);

        manager.releaseBindings();

        assertEquals(1, manager.getReleaseCount(master.getId()));
        assertEquals(1, manager.getReleaseCount(ro.getId()));
        assertNull(manager.getSessionEndpoint(SessionLeg.RW));
        assertNull(manager.getSessionEndpoint(SessionLeg.RO));
    }

    @Test
    public void assertReleasePhysicalConnectionsClearsAcquireState() throws Exception {
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        Endpoint master = new Endpoint("rw", 33000);
        Endpoint ro = new Endpoint("ro1", 33000);
        manager.bindSession(master, ro);

        manager.releasePhysicalConnections();

        assertEquals(0, manager.getAcquireCount(master.getId()));
        assertEquals(0, manager.getAcquireCount(ro.getId()));
        assertEquals(1, manager.getReleaseCount(master.getId()));
        assertEquals(1, manager.getReleaseCount(ro.getId()));
    }
}
