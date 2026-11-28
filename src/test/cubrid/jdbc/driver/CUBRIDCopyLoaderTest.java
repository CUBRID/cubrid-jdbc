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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.StreamSocket;
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * What {@link CUBRIDCopyLoader} sends and gives up, on a connection with no server behind it: the
 * test records the statement and the stream calls, and sets whether an upload is open.
 */
public final class CUBRIDCopyLoaderTest {

    private static final int STREAM_OPEN = 0x08;

    private static final String SQL = "COPY t FROM STDIN WITH (FORMAT CSV)";

    /** Records the loader's calls. The statement opens an upload unless a test says otherwise. */
    private static final class Recording extends CUBRIDConnection {

        final StreamSocket socket;

        final List<String> calls = new ArrayList<String>();

        final ByteArrayOutputStream sent = new ByteArrayOutputStream();

        boolean statementOpensUpload = true;

        /** thrown by the next streamData (); the server ended the upload unless driverRefused */
        SQLException refuseChunk;

        boolean driverRefused;

        SQLException refuseAbort;

        Recording() throws IOException {
            this(new StreamSocket(UConnection.PROTOCOL_V13, 0));
        }

        private Recording(StreamSocket socket) {
            super(socket, "jdbc:cubrid:copy", "dba");
            this.socket = socket;
        }

        @Override
        void executeForStream(String sql) {
            calls.add("execute " + sql);
            if (statementOpensUpload) {
                socket.report(STREAM_OPEN);
            }
        }

        @Override
        public synchronized int streamData(byte[] data, int start, int len) throws SQLException {
            calls.add("data " + len);
            if (refuseChunk != null) {
                SQLException e = refuseChunk;
                refuseChunk = null;
                if (!driverRefused) {
                    socket.report(0);
                }
                throw e;
            }
            sent.write(data, start, len);
            return 0;
        }

        @Override
        public synchronized long streamEnd() {
            calls.add("end");
            socket.report(0);
            return 7;
        }

        @Override
        public synchronized int streamAbort() throws SQLException {
            calls.add("abort");
            socket.report(0);
            if (refuseAbort != null) {
                throw refuseAbort;
            }
            return 0;
        }
    }

    /**
     * Gives the scripted pieces one read each, at most what the read asks for: a string is its
     * bytes, an Integer 0 a read of 0, a Throwable is thrown.
     */
    private static final class Scripted extends InputStream {

        private final Object[] pieces;

        private int next;

        private byte[] rest;

        int reads;

        Scripted(Object... pieces) {
            this.pieces = pieces;
        }

        @Override
        public int read() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            reads++;
            if (rest == null) {
                if (next == pieces.length) {
                    return -1;
                }
                Object piece = pieces[next++];
                if (piece instanceof IOException) {
                    throw (IOException) piece;
                }
                if (piece instanceof RuntimeException) {
                    throw (RuntimeException) piece;
                }
                if (piece instanceof Error) {
                    throw (Error) piece;
                }
                if (piece instanceof Integer) {
                    return (Integer) piece;
                }
                rest = ((String) piece).getBytes(StandardCharsets.UTF_8);
            }
            int n = Math.min(len, rest.length);
            System.arraycopy(rest, 0, b, off, n);
            rest = n == rest.length ? null : Arrays.copyOfRange(rest, n, rest.length);
            return n;
        }
    }

    /** Gives one char per read, as a slow or short-reading Reader does. */
    private static final class Trickle extends Reader {

        private final String text;

        private int at;

        Trickle(String text) {
            this.text = text;
        }

        @Override
        public int read(char[] cbuf, int off, int len) {
            if (at == text.length()) {
                return -1;
            }
            cbuf[off] = text.charAt(at++);
            return 1;
        }

        @Override
        public void close() {}
    }

    private static List<String> calls(String... calls) {
        return Arrays.asList(calls);
    }

    private static byte[] hex(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    /** A chunk is a round trip, so short reads are gathered into a full buffer before it goes. */
    @Test
    public void aChunkIsAFullBufferNotOneRead() throws Exception {
        Recording conn = new Recording();
        Scripted source = new Scripted("1,a\n", "2,", "b\n3", ",c\n");

        long rows = conn.getCopyLoader().copyIn(SQL, source, 5);

        assertEquals(7, rows);
        assertEquals(calls("execute " + SQL, "data 5", "data 5", "data 2", "end"), conn.calls);
        assertEquals("1,a\n2,b\n3,c\n", conn.sent.toString("UTF-8"));
    }

    /** Only -1 ends the source; a read of 0 is a source with nothing yet. */
    @Test
    public void aReadOfZeroIsNotTheEnd() throws Exception {
        Recording conn = new Recording();
        Scripted source = new Scripted("1,a\n", 0, "2,b\n");

        conn.getCopyLoader().copyIn(SQL, source);

        assertEquals("1,a\n2,b\n", conn.sent.toString("UTF-8"));
        assertEquals(calls("execute " + SQL, "data 8", "end"), conn.calls);
    }

    /** Ending the upload would load the rows sent so far, so a failed source gives it up. */
    @Test
    public void aFailedSourceGivesTheUploadUpAndIsTheCause() throws Exception {
        Recording conn = new Recording();
        IOException failure = new IOException("the source failed");

        try {
            conn.getCopyLoader().copyIn(SQL, new Scripted("1,a\n", failure), 4);
            fail("the source failed");
        } catch (SQLException expected) {
            assertEquals(CUBRIDJDBCErrorCode.ioexception_in_stream, expected.getErrorCode());
            assertSame(failure, expected.getCause());
        }
        assertEquals(calls("execute " + SQL, "data 4", "abort"), conn.calls);
    }

    @Test
    public void anyOtherFailureOfTheSourceGivesTheUploadUpAndIsThrownAsItIs() throws Exception {
        Throwable[] failures = {new IllegalStateException("source"), new Error("source")};
        for (Throwable failure : failures) {
            Recording conn = new Recording();
            try {
                conn.getCopyLoader().copyIn(SQL, new Scripted("1,a\n", failure), 4);
                fail("the source failed");
            } catch (RuntimeException | Error expected) {
                assertSame(failure, expected);
            }
            assertEquals(calls("execute " + SQL, "data 4", "abort"), conn.calls);
        }
    }

    @Test
    public void aFailedAbortIsAttachedToTheReason() throws Exception {
        Recording conn = new Recording();
        conn.refuseAbort = new SQLException("connection lost");

        try {
            conn.getCopyLoader().copyIn(SQL, new Scripted(new IOException("source")));
            fail("the source failed");
        } catch (SQLException expected) {
            assertEquals(CUBRIDJDBCErrorCode.ioexception_in_stream, expected.getErrorCode());
            assertSame(conn.refuseAbort, expected.getSuppressed()[0]);
        }
    }

    /** The server ends the upload when it refuses a chunk; giving it up again would fail. */
    @Test
    public void aChunkTheServerRefusedIsNotGivenUpAgain() throws Exception {
        Recording conn = new Recording();
        SQLException refusal = new SQLException("bad row", null, -1371);
        conn.refuseChunk = refusal;

        try {
            conn.getCopyLoader().copyIn(SQL, new Scripted("x\n"));
            fail("the server refused the chunk");
        } catch (SQLException expected) {
            assertSame(refusal, expected);
        }
        assertEquals(calls("execute " + SQL, "data 2"), conn.calls);
    }

    /** A chunk the driver refused never reached the server, so the upload is still open. */
    @Test
    public void aChunkTheDriverRefusedGivesTheUploadUp() throws Exception {
        Recording conn = new Recording();
        SQLException refusal =
                new SQLException(
                        "refused before it was sent", null, UErrorCode.ER_INVALID_ARGUMENT);
        conn.refuseChunk = refusal;
        conn.driverRefused = true;

        try {
            conn.getCopyLoader().copyIn(SQL, new Scripted("x\n"));
            fail("the driver refused the chunk");
        } catch (SQLException expected) {
            assertSame(refusal, expected);
        }
        assertEquals(calls("execute " + SQL, "data 2", "abort"), conn.calls);
    }

    @Test
    public void aSourceThatReturnsMoreThanAskedIsAFailedSource() throws Exception {
        Recording conn = new Recording();
        InputStream tooLong =
                new InputStream() {
                    @Override
                    public int read() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        return len + 1;
                    }
                };

        try {
            conn.getCopyLoader().copyIn(SQL, tooLong, 2);
            fail("the source returned more than it was asked for");
        } catch (SQLException expected) {
            assertEquals(CUBRIDJDBCErrorCode.ioexception_in_stream, expected.getErrorCode());
        }
        assertEquals(calls("execute " + SQL, "abort"), conn.calls);
    }

    /** A statement that opened no upload has run; the source is left as it was. */
    @Test
    public void aStatementThatOpensNoUploadLeavesTheSourceUnread() throws Exception {
        Recording conn = new Recording();
        conn.statementOpensUpload = false;
        Scripted source = new Scripted("1,a\n");

        try {
            conn.getCopyLoader().copyIn("DELETE FROM t", source);
            fail("DELETE opens no upload");
        } catch (SQLException expected) {
            assertEquals(CUBRIDJDBCErrorCode.invalid_value, expected.getErrorCode());
        }
        assertEquals(calls("execute DELETE FROM t"), conn.calls);
        assertEquals(0, source.reads);
    }

    @Test
    public void invalidArgumentsRunNothing() throws Exception {
        Recording conn = new Recording();
        CUBRIDCopyLoader loader = conn.getCopyLoader();
        Object[][] bytes = {{null, new Scripted(), 1}, {SQL, null, 1}, {SQL, new Scripted(), 0}};
        Object[][] chars = {{null, new Trickle(""), 1}, {SQL, null, 1}, {SQL, new Trickle(""), -1}};

        for (Object[] c : bytes) {
            try {
                loader.copyIn((String) c[0], (InputStream) c[1], (Integer) c[2]);
                fail(Arrays.toString(c) + " must be refused");
            } catch (SQLException expected) {
                assertEquals(CUBRIDJDBCErrorCode.invalid_value, expected.getErrorCode());
            }
        }
        for (Object[] c : chars) {
            try {
                loader.copyIn((String) c[0], (Reader) c[1], (Integer) c[2]);
                fail(Arrays.toString(c) + " must be refused");
            } catch (SQLException expected) {
                assertEquals(CUBRIDJDBCErrorCode.invalid_value, expected.getErrorCode());
            }
        }
        assertEquals(calls(), conn.calls);
    }

    @Test
    public void anEmptySourceEndsWithNoChunk() throws Exception {
        Recording conn = new Recording();

        conn.getCopyLoader().copyIn(SQL, new Scripted());
        conn.getCopyLoader().copyIn(SQL, new Trickle(""));

        assertEquals(calls("execute " + SQL, "end", "execute " + SQL, "end"), conn.calls);
    }

    /** The halves of a surrogate pair arrive in two reads and are encoded as one character. */
    @Test
    public void aSurrogatePairAcrossReadsStaysWhole() throws Exception {
        for (int bufferSize = 1; bufferSize <= 7; bufferSize++) {
            Recording conn = new Recording();

            conn.getCopyLoader().copyIn(SQL, new Trickle("x😀y\n"), bufferSize);

            assertArrayEquals(
                    "bufferSize " + bufferSize, hex("78F09F9880790A"), conn.sent.toByteArray());
        }
    }

    @Test
    public void aLoneSurrogateIsAQuestionMark() throws Exception {
        Recording conn = new Recording();

        conn.getCopyLoader().copyIn(SQL, new Trickle("a\uD83D"), 1);
        conn.getCopyLoader().copyIn(SQL, new Trickle("\uDE00b\uD83Dc"), 1);

        assertArrayEquals(hex("613F" + "3F623F63"), conn.sent.toByteArray());
    }

    /** COPY stores the bytes it gets, so text is encoded the way setString () encodes it. */
    @Test
    public void aReaderIsEncodedWithTheConnectionsCharset() throws Exception {
        Recording conn = new Recording();
        conn.getCopyLoader().copyIn(SQL, new Trickle("한글\n"));
        assertArrayEquals(hex("ED959CEAB8800A"), conn.sent.toByteArray());

        Recording euckr = new Recording();
        euckr.socket.useCharset("EUC-KR");
        euckr.getCopyLoader().copyIn(SQL, new Trickle("한글\n"));
        assertArrayEquals(hex("C7D1B1DB0A"), euckr.sent.toByteArray());
    }

    /** A Reader's chunks are full byte buffers too, however few chars each read gives. */
    @Test
    public void aReaderChunkIsAFullBuffer() throws Exception {
        Recording conn = new Recording();

        conn.getCopyLoader().copyIn(SQL, new Trickle("1,aaaa\n2,bbbb\n3,cc\n"), 8);

        assertEquals(calls("execute " + SQL, "data 8", "data 8", "data 3", "end"), conn.calls);
        assertEquals("1,aaaa\n2,bbbb\n3,cc\n", conn.sent.toString("UTF-8"));
    }

    /** A source can fail when it is read past its end, so the loader stops at the first -1. */
    @Test
    public void aSourceIsNotReadAfterItsEnd() throws Exception {
        Recording conn = new Recording();
        InputStream once =
                new InputStream() {
                    int reads;

                    @Override
                    public int read() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        switch (reads++) {
                            case 0:
                                b[off] = '1';
                                b[off + 1] = '\n';
                                return 2;
                            case 1:
                                return -1;
                            default:
                                throw new IllegalStateException("read after the end");
                        }
                    }
                };

        conn.getCopyLoader().copyIn(SQL, once, 4);

        assertEquals(calls("execute " + SQL, "data 2", "end"), conn.calls);
    }

    /** A pair that does not fit in what is left of a chunk goes whole into the next one. */
    @Test
    public void aSurrogatePairAtAChunkBoundaryGoesWholeToTheNextChunk() throws Exception {
        String text = "abcde\uD83D\uDE00f\n";
        for (Reader source : new Reader[] {new StringReader(text), new Trickle(text)}) {
            Recording conn = new Recording();

            conn.getCopyLoader().copyIn(SQL, source, 6);

            assertEquals(calls("execute " + SQL, "data 5", "data 6", "end"), conn.calls);
            assertArrayEquals(hex("6162636465F09F9880660A"), conn.sent.toByteArray());
        }
    }

    /**
     * JDK 8's single-byte encoders report a pair met at the last byte of a chunk as underflow, not
     * overflow, and leave it unread; the text after it still goes.
     */
    @Test(timeout = 10000)
    public void aSingleByteCharsetKeepsTheTextAfterAPairAtTheLastByte() throws Exception {
        String text = "abc\uD83D\uDE00def\n";
        for (Reader source : new Reader[] {new StringReader(text), new Trickle(text)}) {
            Recording conn = new Recording();
            conn.socket.useCharset("windows-1252");

            conn.getCopyLoader().copyIn(SQL, source, 4);

            assertArrayEquals(hex("6162633F6465660A"), conn.sent.toByteArray());
        }
    }

    @Test
    public void aFailedReaderGivesTheUploadUpAndIsTheCause() throws Exception {
        Recording conn = new Recording();
        IOException failure = new IOException("the reader failed");
        Reader failing =
                new Reader() {
                    boolean gave;

                    @Override
                    public int read(char[] cbuf, int off, int len) throws IOException {
                        if (gave) {
                            throw failure;
                        }
                        gave = true;
                        "1,a\n2,b\n".getChars(0, 8, cbuf, off);
                        return 8;
                    }

                    @Override
                    public void close() {}
                };

        try {
            conn.getCopyLoader().copyIn(SQL, failing, 6);
            fail("the reader failed");
        } catch (SQLException expected) {
            assertEquals(CUBRIDJDBCErrorCode.ioexception_in_stream, expected.getErrorCode());
            assertSame(failure, expected.getCause());
        }
        assertEquals(calls("execute " + SQL, "data 6", "abort"), conn.calls);
    }

    @Test
    public void aReaderThatReturnsMoreThanAskedIsAFailedSource() throws Exception {
        Recording conn = new Recording();
        Reader tooLong =
                new Reader() {
                    @Override
                    public int read(char[] cbuf, int off, int len) {
                        return len + 1;
                    }

                    @Override
                    public void close() {}
                };

        try {
            conn.getCopyLoader().copyIn(SQL, tooLong);
            fail("the reader returned more than it was asked for");
        } catch (SQLException expected) {
            assertEquals(CUBRIDJDBCErrorCode.ioexception_in_stream, expected.getErrorCode());
        }
        assertEquals(calls("execute " + SQL, "abort"), conn.calls);
    }

    @Test
    public void aReaderReadOfZeroIsNotTheEnd() throws Exception {
        Recording conn = new Recording();
        Reader zeroOnce =
                new Reader() {
                    int reads;

                    @Override
                    public int read(char[] cbuf, int off, int len) {
                        switch (reads++) {
                            case 0:
                                "1,a\n".getChars(0, 4, cbuf, off);
                                return 4;
                            case 1:
                                return 0;
                            case 2:
                                "2,b\n".getChars(0, 4, cbuf, off);
                                return 4;
                            default:
                                return -1;
                        }
                    }

                    @Override
                    public void close() {}
                };

        conn.getCopyLoader().copyIn(SQL, zeroOnce);

        assertEquals("1,a\n2,b\n", conn.sent.toString("UTF-8"));
    }

    /** A loader outlives the connection it was made on; it refuses once that is closed. */
    @Test
    public void aClosedConnectionIsRefusedBeforeAnything() throws Exception {
        Recording conn = new Recording();
        CUBRIDCopyLoader loader = conn.getCopyLoader();
        conn.close();

        try {
            loader.copyIn(SQL, new Scripted("1,a\n"));
            fail("the connection is closed");
        } catch (SQLException expected) {
            assertEquals(CUBRIDJDBCErrorCode.connection_closed, expected.getErrorCode());
        }
        try {
            loader.copyIn(SQL, new Trickle("1,a\n"));
            fail("the connection is closed");
        } catch (SQLException expected) {
            assertEquals(CUBRIDJDBCErrorCode.connection_closed, expected.getErrorCode());
        }
        assertEquals(calls(), conn.calls);
    }
}
