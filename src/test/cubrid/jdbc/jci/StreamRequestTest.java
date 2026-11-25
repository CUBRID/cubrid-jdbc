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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
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
        UStream stream = socket.getStream();
        byte[] buf = new byte[16];

        assertEquals(-1, stream.init(5, new byte[16]));
        assertNotImplemented(socket);
        assertEquals(-1, stream.sendData(buf, 0, buf.length));
        assertNotImplemented(socket);
        assertEquals(-1L, stream.end());
        assertNotImplemented(socket);
        assertEquals(-1, stream.abort());
        assertNotImplemented(socket);
        assertEquals(-1L, stream.readOpen(6, new byte[16], 0, (byte) 0, buf, new int[2]));
        assertNotImplemented(socket);
        assertEquals(-1, stream.read(1L, buf, 0, buf.length, (byte) 0, new int[1]));
        assertNotImplemented(socket);
        assertEquals(-1, stream.release(1L));
        assertNotImplemented(socket);

        assertEquals("no request byte may reach the CAS", 0, socket.sent().length);
        assertFalse("the connection stays open", socket.isClosed());
    }

    /** END's value is 64 bits: the rows a COPY loaded can pass INT_MAX. */
    @Test
    public void endReturnsTheWholeValue() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, 0);
        socket.replyNext(ByteBuffer.allocate(8).putLong(2_202_009_600L).array());

        assertEquals(2_202_009_600L, socket.getStream().end());
        assertEquals(UErrorCode.ER_NO_ERROR, socket.getRecentError().getErrorCode());
    }

    /**
     * The open returns the handle, and the first piece and its flags go into buf and reply. Of the
     * caller's flags only FLAG_RELEASE_AT_EOF goes out; FLAG_AUTO_COMMIT follows the connection.
     */
    @Test
    public void theOpenReturnsTheHandleAndTheFirstPiece() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, 0);
        socket.replyNext(
                ByteBuffer.allocate(8 + 4 + 4 + 2)
                        .putLong(77L)
                        .putInt(2)
                        .putInt(UStream.FLAG_RELEASED)
                        .put(new byte[] {5, 6})
                        .array());
        byte[] buf = new byte[4];
        int[] reply = new int[2];

        assertEquals(77L, socket.getStream().readOpen(6, new byte[4], 4, (byte) 0x07, buf, reply));
        assertArrayEquals(new byte[] {5, 6, 0, 0}, buf);
        assertEquals(2, reply[0]);
        assertEquals(UStream.FLAG_RELEASED, reply[1]);
        byte[] sent = socket.sent();
        assertEquals(
                "the flags byte ends the request",
                UStream.FLAG_RELEASE_AT_EOF | UStream.FLAG_AUTO_COMMIT,
                sent[sent.length - 1]);
    }

    /** A piece lands in the caller's buffer at its offset, and replyFlags gets the reply flags. */
    @Test
    public void aPieceFillsTheBufferAtItsOffset() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, 0);
        socket.replyNext(
                ByteBuffer.allocate(4 + 4 + 3)
                        .putInt(3)
                        .putInt(UStream.FLAG_EOF)
                        .put(new byte[] {1, 2, 3})
                        .array());
        byte[] buf = new byte[8];
        int[] replyFlags = new int[1];

        assertEquals(3, socket.getStream().read(7L, buf, 2, 4, (byte) 0, replyFlags));
        assertArrayEquals(new byte[] {0, 0, 1, 2, 3, 0, 0, 0}, buf);
        assertEquals(UStream.FLAG_EOF, replyFlags[0]);
    }

    /**
     * A reply that claims more bytes than were asked for is refused, and replyFlags stays as it
     * was.
     */
    @Test
    public void aPieceLongerThanAskedIsRefused() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, 0);
        socket.replyNext(
                ByteBuffer.allocate(4 + 4 + 5).putInt(5).putInt(0).put(new byte[5]).array());
        int[] replyFlags = {-1};

        assertEquals(-1, socket.getStream().read(7L, new byte[8], 0, 4, (byte) 0, replyFlags));
        assertEquals(UErrorCode.ER_COMMUNICATION, socket.getRecentError().getErrorCode());
        assertEquals(-1, replyFlags[0]);
    }

    /** A buffer range the call cannot use is refused before anything is sent. */
    @Test
    public void aRangeOutsideTheBufferIsRefusedBeforeSending() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, 0);
        UStream stream = socket.getStream();
        byte[] buf = new byte[8];

        assertEquals(-1, stream.read(7L, buf, Integer.MAX_VALUE, 1, (byte) 0, new int[1]));
        assertInvalidArgument(socket);
        assertEquals(-1, stream.sendData(null, 0, 0));
        assertInvalidArgument(socket);
        assertEquals(-1, stream.sendData(buf, 4, 5));
        assertInvalidArgument(socket);
        assertEquals(-1, stream.sendData(buf, Integer.MAX_VALUE, 1));
        assertInvalidArgument(socket);
        assertEquals(-1, stream.sendData(buf, 0, -1));
        assertInvalidArgument(socket);

        assertEquals("no request byte may reach the CAS", 0, socket.sent().length);
    }

    private static void assertInvalidArgument(final UConnection socket) {
        assertEquals(UErrorCode.ER_INVALID_ARGUMENT, socket.getRecentError().getErrorCode());
    }

    private static void assertNotImplemented(final UConnection socket) {
        UError error = socket.getRecentError();
        assertEquals(UErrorCode.ER_DBMS, error.getErrorCode());
        assertEquals(UErrorCode.CAS_ER_NOT_IMPLEMENTED, error.getJdbcErrorCode());
    }
}
