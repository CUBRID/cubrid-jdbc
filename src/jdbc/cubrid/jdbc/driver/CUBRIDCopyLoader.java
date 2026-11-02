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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Loads a stream into a table through COPY ... FROM STDIN.
 *
 * <p>The connection already exposes the transport itself -- {@link
 * CUBRIDConnection#streamData(byte[])} and {@link CUBRIDConnection#streamEnd()}
 * -- but using it means running the statement, cutting the payload into chunks
 * and closing the stream by hand. This does those three in one call, so a
 * caller hands over a stream and gets back the number of rows loaded.
 *
 * <p>The payload is opaque here: the format is whatever the statement's
 * {@code WITH (FORMAT ...)} says, and the bytes are passed through untouched.
 *
 * <pre>
 * CUBRIDCopyLoader loader = ((CUBRIDConnection) conn).getCopyLoader();
 * long rows = loader.copyIn("COPY t FROM STDIN WITH (FORMAT CSV)", in);
 * </pre>
 */
public class CUBRIDCopyLoader {

    /**
     * Bytes read from the source before each send.
     *
     * <p>Every chunk is a synchronous round trip, so throughput over a remote
     * link is bounded by round-trip time times chunk count. The default is
     * large for that reason.
     */
    public static final int DEFAULT_BUFFER_SIZE = 1024 * 1024;

    private final CUBRIDConnection con;

    CUBRIDCopyLoader(CUBRIDConnection con) {
        this.con = con;
    }

    /** Loads already-encoded bytes, reading the source in default-sized chunks. */
    public long copyIn(String sql, InputStream from) throws SQLException {
        return copyIn(sql, from, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Loads already-encoded bytes.
     *
     * @param sql a COPY ... FROM STDIN statement
     * @param from the payload, in the format the statement names
     * @param bufferSize bytes read from {@code from} before each send
     * @return rows loaded, as the server counted them
     */
    public long copyIn(String sql, InputStream from, int bufferSize) throws SQLException {
        if (sql == null || from == null) {
            throw con.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (bufferSize <= 0) {
            throw con.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }

        /* before open (): failing to allocate must not leave a stream open */
        byte[] buffer = new byte[bufferSize];

        open(sql);

        try {
            int len;
            while ((len = from.read(buffer)) > 0) {
                con.streamData(buffer, 0, len);
            }
        } catch (IOException e) {
            /* The source failed, not the server. Ending the stream would load
             * the rows sent so far, so abort it instead, then report the read. */
            CUBRIDException err =
                    con.createCUBRIDException(CUBRIDJDBCErrorCode.ioexception_in_stream, e);
            abort(err);
            throw err;
        } catch (SQLException e) {
            /* from streamData (): the server refused the chunk or the
             * connection failed, and either way the stream is already gone */
            throw e;
        } catch (Throwable e) {
            /* Anything else the source throws, Errors included, aborts the
             * stream the same way and reaches the caller as it is. */
            abort(e);
            throw e;
        }

        return con.streamEnd();
    }

    /** Loads CSV text, encoded as UTF-8. */
    public long copyIn(String sql, Reader from) throws SQLException {
        return copyIn(sql, from, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Loads CSV text, encoded as UTF-8.
     *
     * @param bufferSize characters read from {@code from} before each send
     */
    public long copyIn(String sql, Reader from, int bufferSize) throws SQLException {
        if (sql == null || from == null) {
            throw con.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        /* the buffer below holds one char more */
        if (bufferSize <= 0 || bufferSize == Integer.MAX_VALUE) {
            throw con.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }

        /* One char more than a read asks for: a high surrogate that ends a
         * read is held there for its low half, because encoding the two
         * halves apart turns each into '?'. Allocated before open (), so a
         * failure to allocate leaves no stream open. */
        char[] buffer = new char[bufferSize + 1];
        int held = 0;

        open(sql);

        try {
            int len;
            while ((len = from.read(buffer, held, bufferSize)) > 0) {
                int end = held + len;
                held = Character.isHighSurrogate(buffer[end - 1]) ? 1 : 0;
                byte[] encoded = new String(buffer, 0, end - held).getBytes("UTF-8");
                if (encoded.length > 0) {
                    con.streamData(encoded, 0, encoded.length);
                }
                if (held > 0) {
                    buffer[0] = buffer[end - 1];
                }
            }
            /* No low half follows the end of the input; encode the high
             * surrogate alone, as encoding the whole text would. */
            if (held > 0) {
                byte[] encoded = new String(buffer, 0, held).getBytes("UTF-8");
                con.streamData(encoded, 0, encoded.length);
            }
        } catch (UnsupportedEncodingException e) {
            CUBRIDException err = con.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, e);
            abort(err);
            throw err;
        } catch (IOException e) {
            CUBRIDException err =
                    con.createCUBRIDException(CUBRIDJDBCErrorCode.ioexception_in_stream, e);
            abort(err);
            throw err;
        } catch (SQLException e) {
            throw e;
        } catch (Throwable e) {
            abort(e);
            throw e;
        }

        return con.streamEnd();
    }

    private void open(String sql) throws SQLException {
        Statement st = con.createStatement();
        try {
            st.executeUpdate(sql);
        } finally {
            st.close();
        }
    }

    /* Gives up the stream instead of ending it. If the abort fails too --
     * the connection lost, most likely -- that failure is attached to the
     * reason the caller is given rather than thrown in its place. */
    private void abort(Throwable reason) {
        try {
            con.streamAbort();
        } catch (SQLException e) {
            reason.addSuppressed(e);
        }
    }
}
