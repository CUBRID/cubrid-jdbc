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

package cubrid.jdbc.jci;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which CAS the stream calls may talk to, and which replies say a stream is open. */
public final class StreamRequestTest {

    /** A release CAS (PROTOCOL_V12) sends every additional-flag bit above its own masks as 1. */
    @Test
    public void theStreamOpenBitCountsFromProtocolV13On() throws Exception {
        assertFalse(new StreamSocket(UConnection.PROTOCOL_V12, 0xFF).isStreamOpen());
        assertTrue(new StreamSocket(UConnection.PROTOCOL_V13, 0xFF).isStreamOpen());
        assertFalse(new StreamSocket(UConnection.PROTOCOL_V13, 0x07).isStreamOpen());
    }

    /** A CAS older than PROTOCOL_V13 closes the connection on a function code it does not know. */
    @Test
    public void anOlderCasIsSentNoStreamRequest() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V12, 0xFF);
        byte[] buf = new byte[16];

        assertEquals(-1, socket.streamInit(5, new byte[16]));
        assertNotImplemented(socket);
        assertEquals(-1, socket.streamSendData(buf, 0, buf.length));
        assertNotImplemented(socket);
        assertEquals(-1L, socket.streamEnd());
        assertNotImplemented(socket);
        assertEquals(-1, socket.streamAbort());
        assertNotImplemented(socket);
        assertEquals(-1L, socket.streamReadOpen(6, new byte[16], 0, (byte) 0, buf, new int[2]));
        assertNotImplemented(socket);
        assertEquals(-1, socket.streamRead(1L, buf, 0, buf.length, (byte) 0, new int[1]));
        assertNotImplemented(socket);
        assertEquals(-1, socket.streamRelease(1L));
        assertNotImplemented(socket);

        assertEquals("no request byte may reach the CAS", 0, socket.sent().length);
        assertFalse("the connection stays open", socket.isClosed());
    }

    private static void assertNotImplemented(final UConnection socket) {
        UError error = socket.getRecentError();
        assertEquals(UErrorCode.ER_DBMS, error.getErrorCode());
        assertEquals(UErrorCode.CAS_ER_NOT_IMPLEMENTED, error.getJdbcErrorCode());
    }
}
