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

import static org.junit.Assert.*;

import java.sql.SQLException;
import org.junit.Test;

public class BrokerEndpointTest {

    @Test
    public void assertParseValidHostPort() throws Exception {
        Endpoint ep = Endpoint.parse("rw:33000");
        assertEquals("rw", ep.getHost());
        assertEquals(33000, ep.getPort());
        assertEquals("rw:33000", ep.getId());
    }

    @Test
    public void assertParseIpAddress() throws Exception {
        Endpoint ep = Endpoint.parse("192.168.1.100:33000");
        assertEquals("192.168.1.100", ep.getHost());
        assertEquals(33000, ep.getPort());
    }

    @Test
    public void assertParseTrimmed() throws Exception {
        Endpoint ep = Endpoint.parse("  ro1:33001  ");
        assertEquals("ro1", ep.getHost());
        assertEquals(33001, ep.getPort());
    }

    @Test
    public void assertParseNullThrows() {
        try {
            Endpoint.parse(null);
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void assertParseEmptyThrows() {
        try {
            Endpoint.parse("");
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void assertParseNoColonThrows() {
        try {
            Endpoint.parse("badhostonly");
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("host:port"));
        }
    }

    @Test
    public void assertParseNonNumericPortThrows() {
        try {
            Endpoint.parse("rw:abc");
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("port"));
        }
    }

    @Test
    public void assertParsePortOutOfRangeThrows() {
        try {
            Endpoint.parse("rw:70000");
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("range"));
        }
    }

    @Test
    public void assertParsePortZeroThrows() {
        try {
            Endpoint.parse("rw:0");
            fail("Expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("range"));
        }
    }

    @Test
    public void assertEqualsAndHashCode() throws Exception {
        Endpoint a = Endpoint.parse("rw:33000");
        Endpoint b = new Endpoint("rw", 33000);
        Endpoint c = Endpoint.parse("other:33000");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
        assertFalse(a.equals(null));
    }

    @Test
    public void assertToString() throws Exception {
        Endpoint ep = Endpoint.parse("myhost:12345");
        assertEquals("myhost:12345", ep.toString());
    }
}
