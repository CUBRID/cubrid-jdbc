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

package cubrid.jdbc.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.StreamSocket;
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UFunctionCode;
import java.sql.SQLException;
import org.junit.Test;

/**
 * What an open stream means for the connection on top of it: the statement that opened it is not
 * finished, and the CAS's refusals reach the application with the server's code.
 */
public final class StreamConnectionTest {

    private static final int STREAM_OPEN = 0x08;

    private static final int ER_STREAM_INVALID_USE = -1220;

    @Test
    public void autoCommitWaitsWhileAStreamIsOpen() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, STREAM_OPEN);
        CUBRIDConnection conn = new CUBRIDConnection(socket, "jdbc:cubrid:stream", "dba");

        conn.autoCommit();
        assertEquals(
                "the CAS commits at END; a commit now would end the stream", 0, socket.commits());

        socket.report(0);
        conn.autoCommit();
        assertEquals(1, socket.commits());
    }

    /** A release CAS (PROTOCOL_V12) sends the stream bit as 1 in every reply. */
    @Test
    public void autoCommitCommitsOnAnOlderCas() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V12, 0xFF);
        CUBRIDConnection conn = new CUBRIDConnection(socket, "jdbc:cubrid:stream", "dba");

        conn.autoCommit();
        assertEquals(1, socket.commits());
    }

    /** The CAS refuses a second open while an upload is open, and leaves the upload as it was. */
    @Test
    public void anInvalidUseOfAStreamSurfacesWithTheServerCode() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, STREAM_OPEN);
        CUBRIDConnection conn = new CUBRIDConnection(socket, "jdbc:cubrid:stream", "dba");
        socket.refuseNextRequest(
                ER_STREAM_INVALID_USE,
                "Invalid use of a stream: a stream session is already active on this connection");

        try {
            conn.streamInit(5, new byte[16]);
            fail("the CAS refused the open");
        } catch (SQLException expected) {
            assertEquals(ER_STREAM_INVALID_USE, expected.getErrorCode());
        }
        assertEquals("the open was sent", UFunctionCode.STREAM_INIT.getCode(), socket.sent()[8]);
        assertTrue("the upload is still open", socket.isStreamOpen());
    }
}
