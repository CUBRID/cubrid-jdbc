/*
 * Copyright (C) 2008 Search Solution Corporation.
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

import cubrid.jdbc.jci.UConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Loads a byte or character source into a table through COPY ... FROM STDIN.
 *
 * <p>The connection already exposes the upload itself -- {@link
 * CUBRIDConnection#streamData(byte[])} and {@link CUBRIDConnection#streamEnd()} -- but using it
 * means running the statement, cutting the source into chunks and ending the upload by hand. {@code
 * copyIn ()} does those three in one call and returns the number of rows loaded.
 *
 * <p>A byte source is sent untouched, in the format the statement's {@code WITH (FORMAT ...)}
 * names. A character source is encoded with the connection's {@code charSet}, as {@code setString
 * ()} encodes a value, because COPY stores the bytes it receives without converting them. A lone
 * surrogate, or a character the {@code charSet} cannot encode, is sent as the charset's
 * replacement, {@code '?'} for UTF-8 and EUC-KR.
 *
 * <p>{@code copyIn ()} takes a COPY ... FROM STDIN statement. Another statement runs as {@code
 * executeUpdate ()} runs it, committed under auto-commit, and then {@code copyIn ()} fails with
 * -21120 before it reads the source.
 *
 * <p>The source is the caller's: {@code copyIn ()} reads it to its end and does not close it. While
 * {@code copyIn ()} runs, no other thread may use the connection: the upload has no handle of its
 * own, so a statement run in between can end it or open another one.
 *
 * <pre>
 * CUBRIDCopyLoader loader = ((CUBRIDConnection) conn).getCopyLoader();
 * long rows = loader.copyIn("COPY t FROM STDIN WITH (FORMAT CSV)", in);
 * </pre>
 */
public final class CUBRIDCopyLoader {

    /*
     * Bytes in each chunk. Every chunk is a synchronous round trip, so a load takes at least the
     * chunk count times the round-trip time; the default is large for that reason, and a chunk is
     * a full buffer, not what one read of the source returned.
     */
    static final int DEFAULT_BUFFER_SIZE = 1024 * 1024;

    /* chars asked of a Reader per read; the encoder fills the chunk from them */
    private static final int CHARS_PER_READ = 8192;

    private final CUBRIDConnection con;

    CUBRIDCopyLoader(CUBRIDConnection con) {
        this.con = con;
    }

    /**
     * Loads a byte source in 1 MiB chunks.
     *
     * @param sql a COPY ... FROM STDIN statement
     * @param source the payload, in the format the statement names
     * @return rows loaded, as the server counted them
     */
    public long copyIn(String sql, InputStream source) throws SQLException {
        return copyIn(sql, source, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Loads a byte source.
     *
     * @param sql a COPY ... FROM STDIN statement
     * @param source the payload, in the format the statement names
     * @param bufferSize bytes in each chunk
     * @return rows loaded, as the server counted them
     */
    public long copyIn(String sql, InputStream source, int bufferSize) throws SQLException {
        check(sql, source, bufferSize);

        /* before the statement runs, so a failure to allocate leaves no upload open */
        byte[] chunk = new byte[bufferSize];

        return load(
                sql,
                () -> {
                    int len;
                    do {
                        len = fill(source, chunk);
                        if (len > 0) {
                            con.streamData(chunk, 0, len);
                        }
                    } while (len
                            == chunk.length); /* a chunk filled short ends at the source's end */
                });
    }

    /**
     * Loads a character source in 1 MiB chunks, encoded with the connection's {@code charSet}.
     *
     * @param sql a COPY ... FROM STDIN statement
     * @param source the payload as text, in the format the statement names
     * @return rows loaded, as the server counted them
     */
    public long copyIn(String sql, Reader source) throws SQLException {
        return copyIn(sql, source, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Loads a character source, encoded with the connection's {@code charSet}.
     *
     * @param sql a COPY ... FROM STDIN statement
     * @param source the payload as text, in the format the statement names
     * @param bufferSize bytes in each chunk, raised to what two characters can take
     * @return rows loaded, as the server counted them
     */
    public long copyIn(String sql, Reader source, int bufferSize) throws SQLException {
        check(sql, source, bufferSize);

        CharsetEncoder encoder = encoder();

        /* An empty chunk takes any one character or surrogate pair. Both buffers
         * before the statement runs, as above. */
        int least = 2 * (int) Math.ceil(encoder.maxBytesPerChar());
        ByteBuffer chunk = ByteBuffer.allocate(Math.max(bufferSize, least));
        CharBuffer chars = CharBuffer.allocate(CHARS_PER_READ);

        return load(
                sql,
                () -> {
                    boolean end = false;
                    while (!end) {
                        end = read(source, chars) < 0;
                        ((Buffer) chars).flip();
                        encodeAll(encoder, chars, chunk);
                        if (end) {
                            /* nothing follows a high surrogate still left, which becomes the replacement */
                            room(chunk, least);
                            encoder.encode(chars, chunk, true);
                            room(chunk, least);
                            while (encoder.flush(chunk).isOverflow()) {
                                send(chunk);
                            }
                        }
                        chars.compact();
                    }
                    send(chunk);
                });
    }

    private interface Sender {
        void sendAll() throws IOException, SQLException;
    }

    private void check(String sql, Object source, int bufferSize) throws SQLException {
        if (sql == null || source == null || bufferSize <= 0) {
            throw con.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (con.isClosed()) {
            throw con.createCUBRIDException(CUBRIDJDBCErrorCode.connection_closed, null);
        }
    }

    private CharsetEncoder encoder() throws SQLException {
        try {
            return Charset.forName(con.u_con.getCharset())
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            /* a charSet Java does not know, or one it can only decode */
            CUBRIDException err =
                    con.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
            err.initCause(e);
            throw err;
        }
    }

    /* Runs the statement, sends the source and ends the upload. */
    private long load(String sql, Sender sender) throws SQLException {
        open(sql);

        try {
            sender.sendAll();
        } catch (IOException e) {
            /* The source failed, not the server. Ending the upload would load
             * the rows sent so far, so it is given up instead. */
            CUBRIDException err =
                    con.createCUBRIDException(CUBRIDJDBCErrorCode.ioexception_in_stream, null);
            err.initCause(e);
            abort(err);
            throw err;
        } catch (SQLException e) {
            /* A chunk the server refused ended the upload there; a call the
             * driver refused before sending it did not. */
            if (uploadOpen()) {
                abort(e);
            }
            throw e;
        } catch (RuntimeException | Error e) {
            abort(e);
            throw e;
        }

        return con.streamEnd();
    }

    /* A statement that opened no upload is not COPY ... FROM STDIN; the source is left unread. */
    private void open(String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.executeUpdate(sql);
        }

        if (!uploadOpen()) {
            throw con.createCUBRIDException(
                    CUBRIDJDBCErrorCode.invalid_value,
                    " - the statement opened no upload; copyIn () takes COPY ... FROM STDIN",
                    null);
        }
    }

    /* whether the server still holds the upload; false once the connection is gone */
    private boolean uploadOpen() {
        UConnection u = con.u_con;
        return u != null && u.isStreamOpen();
    }

    /* Fills the chunk unless the source ends first; a read of 0 is not the end. */
    private static int fill(InputStream source, byte[] chunk) throws IOException {
        int len = 0;
        while (len < chunk.length) {
            int asked = chunk.length - len;
            int n = source.read(chunk, len, asked);
            if (n < 0) {
                break;
            }
            if (n > asked) {
                throw new IOException("the source returned " + n + " bytes for " + asked);
            }
            len += n;
        }
        return len;
    }

    /* Reads into the room the buffer has, without the copy Reader.read (CharBuffer) makes;
     * -1 at the end of the source. */
    private static int read(Reader source, CharBuffer chars) throws IOException {
        int asked = chars.remaining();
        int n = source.read(chars.array(), chars.arrayOffset() + chars.position(), asked);
        if (n > asked) {
            throw new IOException("the source returned " + n + " chars for " + asked);
        }
        if (n > 0) {
            ((Buffer) chars).position(chars.position() + n);
        }
        return n;
    }

    /*
     * Encodes all of chars but a high surrogate at its end, which waits for its low half. The
     * chunk goes whenever the next character does not fit: an encoder reports that as overflow,
     * or as underflow with the character left unread (the single-byte encoders of JDK 8 do).
     */
    private void encodeAll(CharsetEncoder encoder, CharBuffer chars, ByteBuffer chunk)
            throws SQLException {
        while (true) {
            encoder.encode(chars, chunk, false);
            int left = chars.remaining();
            if (left == 0
                    || (left == 1 && Character.isHighSurrogate(chars.get(chars.position())))) {
                return;
            }
            if (chunk.position() == 0) {
                throw new IllegalStateException(
                        encoder.charset() + " encodes nothing into an empty chunk");
            }
            send(chunk);
        }
    }

    /* Sends the chunk unless it has room for at least bytes more. */
    private void room(ByteBuffer chunk, int bytes) throws SQLException {
        if (chunk.remaining() < bytes) {
            send(chunk);
        }
    }

    /*
     * The casts to Buffer keep flip (), clear () and position (int) Buffer's own: JDK 9 and later
     * declare covariant ones in CharBuffer and ByteBuffer, which a jar built there for Java 8
     * without --release would link and not find on Java 8.
     */
    private void send(ByteBuffer chunk) throws SQLException {
        if (chunk.position() > 0) {
            con.streamData(chunk.array(), chunk.arrayOffset(), chunk.position());
            ((Buffer) chunk).clear();
        }
    }

    /* Gives the upload up. A failure to do so is attached to the reason the
     * caller is given, not thrown in its place. */
    private void abort(Throwable reason) {
        try {
            con.streamAbort();
        } catch (SQLException e) {
            reason.addSuppressed(e);
        }
    }
}
