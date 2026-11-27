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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.StreamSocket;
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UFunctionCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /**
     * A statement refused with -1220 while a stream is open did nothing; rolling back would drop
     * the stream the application is still feeding.
     */
    @Test
    public void autoRollbackWaitsWhileAStreamIsOpen() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, STREAM_OPEN);
        CUBRIDConnection conn = new CUBRIDConnection(socket, "jdbc:cubrid:stream", "dba");

        conn.autoRollback();
        assertEquals(0, socket.rollbacks());

        socket.report(0);
        conn.autoRollback();
        assertEquals(1, socket.rollbacks());
    }

    /** A release CAS (PROTOCOL_V12) sends the stream bit as 1 in every reply. */
    @Test
    public void autoCommitAndRollbackRunOnAnOlderCas() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V12, 0xFF);
        CUBRIDConnection conn = new CUBRIDConnection(socket, "jdbc:cubrid:stream", "dba");

        conn.autoCommit();
        assertEquals(1, socket.commits());
        conn.autoRollback();
        assertEquals(1, socket.rollbacks());
    }

    /**
     * A statement that meets a lost CAS while a stream is open gets no reply. The stream went with
     * the CAS, so the statement is rolled back as any other, and that rollback is what lets a
     * connection whose CAS is gone reconnect.
     */
    @Test
    public void aStatementWithNoReplyIsRolledBack() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, STREAM_OPEN);
        CUBRIDConnection conn = new CUBRIDConnection(socket, "jdbc:cubrid:stream", "dba");
        socket.loseCas();

        try {
            conn.prepareStatement("SELECT 1");
            fail("the CAS is gone");
        } catch (SQLException expected) {
            assertEquals(UErrorCode.ER_ILLEGAL_DATA_SIZE, expected.getErrorCode());
        }
        assertFalse(socket.isStreamOpen());
        assertEquals(1, socket.rollbacks());
    }

    /**
     * A null chunk is an SQLException with the invalid-argument code, not a NullPointerException.
     */
    @Test
    public void aNullChunkIsAnInvalidArgument() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, STREAM_OPEN);
        CUBRIDConnection conn = new CUBRIDConnection(socket, "jdbc:cubrid:stream", "dba");

        try {
            conn.streamData(null);
            fail("a null chunk is refused");
        } catch (SQLException expected) {
            assertEquals(UErrorCode.ER_INVALID_ARGUMENT, expected.getErrorCode());
        }
        assertEquals("nothing was sent", 0, socket.sent().length);
    }

    /**
     * A stream the application left open would hold its transaction and a COPY's lock for the next
     * user of the connection, so closing the connection aborts it first.
     */
    @Test
    public void closingAConnectionAbortsAnAbandonedStream() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, STREAM_OPEN);
        CUBRIDConnection conn = new CUBRIDConnection(socket, "jdbc:cubrid:stream", "dba");

        conn.close();
        assertEquals("the abort was sent", UFunctionCode.STREAM_ABORT.getCode(), socket.sent()[8]);
    }

    /**
     * A pool that keeps the physical connection checks it with isValid () before lending it, so a
     * connection whose upload was left open is not valid: the CAS refuses its statements.
     */
    @Test
    public void aConnectionWithAnUploadOpenIsNotValid() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, STREAM_OPEN);
        CUBRIDConnection conn = new CUBRIDConnection(socket, "jdbc:cubrid:stream", "dba");

        assertFalse(conn.isValid(1));
        assertEquals("nothing was sent", 0, socket.sent().length);
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

    /**
     * An upload opened in a transaction, a download's, that ends out of one was rolled back: the
     * download was released while the upload was open, and the CAS paid its deferred commit as a
     * rollback. The driver cleans up as its own rollback does, and completing a statement there
     * commits nothing.
     */
    @Test
    public void givingUpAnUploadOpenedInATransactionRollsItBack() throws Exception {
        StreamSocket socket = new StreamSocket(UConnection.PROTOCOL_V13, 0);
        CUBRIDConnection conn = new CUBRIDConnection(socket, "jdbc:cubrid:stream", "dba");
        RecordingOwner owner = new RecordingOwner(conn);
        CUBRIDStatement open = statementLeftOpen(conn);

        socket.replyNext(new byte[0], true, STREAM_OPEN);
        conn.streamInit(5, new byte[16]);
        socket.replyNext(new byte[0], false, 0);
        conn.streamAbort();

        assertEquals(Collections.singletonList("rollback"), owner.ends);
        assertTrue(open.completed);
        assertEquals("the CAS ended the transaction", 0, socket.commits() + socket.rollbacks());
    }

    /* A statement whose execute left it to complete, as one with a result set open does. */
    private static CUBRIDStatement statementLeftOpen(final CUBRIDConnection conn)
            throws SQLException {
        CUBRIDStatement stmt =
                (CUBRIDStatement)
                        conn.createStatement(
                                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        stmt.completed = false;
        return stmt;
    }

    /** The connection that owns the leg's value objects: records the ends the leg reports. */
    private static final class RecordingOwner extends CUBRIDConnection {

        private final List<String> ends = new ArrayList<String>();

        private RecordingOwner(final CUBRIDConnection leg) throws SQLException {
            super("jdbc:cubrid:owner", "dba");
            leg.getUConnection().setCUBRIDConnection(this);
        }

        @Override
        protected void legTransactionEnding(final CUBRIDConnection leg, final boolean commit) {
            ends.add(commit ? "commit" : "rollback");
        }
    }
}
