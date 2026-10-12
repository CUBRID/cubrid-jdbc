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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Uploads a BLOB/CLOB bind value through the connection's stream session; {@link #finish()} ends
 * the stream and hands back the upload token a statement binds.
 *
 * <p>A value read from an InputStream or Reader is sent as it is read. A Blob or Clob made by
 * {@link CUBRIDConnection#createBlob()} or {@link CUBRIDConnection#createClob()} is held until it
 * is bound (up to one chunk in memory, the rest in a temporary file): the application fills it with
 * other statements in between, and the server admits only the stream's own requests while it is
 * open.
 */
class CUBRIDInternalLobUpload {
    static final long MAX_LENGTH = 0x100000000L;
    private static final int STREAM_KIND = 1;
    private static final int CHUNK_SIZE = 1024 * 1024;

    private final CUBRIDConnection conn;
    private final boolean blob;
    private byte[] chunk = null;
    private int filled = 0;
    private long sent = 0; // bytes out of the chunk: sent, or spilled while held
    private boolean started = false;
    private boolean finished = false;
    private boolean aborted = false;
    private long session = 0;
    private long token = 0;
    private final boolean held;
    private File spill = null;
    private Spill spillRef = null;

    /* the spill of a held value dropped without being bound or freed, deleted after it is collected */
    private static final ReferenceQueue<CUBRIDInternalLobUpload> DROPPED = new ReferenceQueue<>();
    private static final Set<Spill> SPILLS = Collections.synchronizedSet(new HashSet<Spill>());

    private static final class Spill extends PhantomReference<CUBRIDInternalLobUpload> {
        private final File file;

        Spill(CUBRIDInternalLobUpload owner, File file) {
            super(owner, DROPPED);
            this.file = file;
        }
    }

    CUBRIDInternalLobUpload(CUBRIDConnection conn, boolean blob) {
        this(conn, blob, false);
    }

    /* held: a createBlob ()/createClob () value, sent only when it is bound */
    CUBRIDInternalLobUpload(CUBRIDConnection conn, boolean blob, boolean held) {
        this.conn = conn;
        this.blob = blob;
        this.held = held;
    }

    long length() {
        return sent + filled;
    }

    boolean isBlob() {
        return blob;
    }

    /* The upload a BLOB/CLOB parameter binds: a value made by createBlob ()/createClob () sends what
     * it holds, any other value is streamed now. */
    static CUBRIDInternalLobUpload of(CUBRIDConnection conn, Object value) throws SQLException {
        CUBRIDInternalLobUpload made = null;
        if (value instanceof CUBRIDBlob) {
            made = ((CUBRIDBlob) value).getUpload();
        } else if (value instanceof CUBRIDClob) {
            made = ((CUBRIDClob) value).getUpload();
        }
        if (made != null) {
            if (made.conn != conn) {
                /* its token means something only in the session of the connection that made it */
                throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
            }
            if (value instanceof CUBRIDClob) {
                ((CUBRIDClob) value).finishUpload();
            }
            made.finish();
            return made;
        }
        if (value instanceof CUBRIDClob) {
            /* the stored bytes as they are, not decoded and encoded again */
            return fromStream(conn, false, ((CUBRIDClob) value).getAsciiStream(), -1);
        }
        if (value instanceof Clob) {
            return fromReader(conn, ((Clob) value).getCharacterStream(), -1);
        }
        return fromStream(conn, true, ((Blob) value).getBinaryStream(), -1);
    }

    /* Streams in as one value and returns it finished; length < 0 reads to the end. */
    static CUBRIDInternalLobUpload fromStream(
            CUBRIDConnection conn, boolean blob, InputStream in, long length) throws SQLException {
        CUBRIDInternalLobUpload upload = new CUBRIDInternalLobUpload(conn, blob);
        byte[] buf = new byte[CHUNK_SIZE];
        long read = 0;
        boolean done = false;
        try {
            while (length < 0 || read < length) {
                int n =
                        in.read(
                                buf,
                                0,
                                length < 0
                                        ? buf.length
                                        : (int) Math.min(buf.length, length - read));
                if (n < 0) {
                    break;
                }
                upload.write(buf, 0, n);
                read += n;
            }
            if (length >= 0 && read != length) {
                throw new IOException("the stream ended before its length");
            }
            upload.finish();
            done = true;
        } catch (IOException e) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.ioexception_in_stream, e);
        } finally {
            /* any failure, a RuntimeException from the caller's stream too, must free the session */
            if (!done) {
                upload.abort();
            }
        }
        return upload;
    }

    /* Like fromStream (), encoding the characters with the connection charset. */
    static CUBRIDInternalLobUpload fromReader(CUBRIDConnection conn, Reader reader, long length)
            throws SQLException {
        CUBRIDInternalLobUpload upload = new CUBRIDInternalLobUpload(conn, false);
        Writer w =
                new OutputStreamWriter(
                        upload.outputStream(), Charset.forName(conn.getUConnection().getCharset()));
        char[] buf = new char[CHUNK_SIZE / 4];
        long read = 0;
        boolean done = false;
        try {
            while (length < 0 || read < length) {
                int n =
                        reader.read(
                                buf,
                                0,
                                length < 0
                                        ? buf.length
                                        : (int) Math.min(buf.length, length - read));
                if (n < 0) {
                    break;
                }
                w.write(buf, 0, n);
                read += n;
            }
            if (length >= 0 && read != length) {
                throw new IOException("the reader ended before its length");
            }
            /* also encodes a dangling high surrogate, and finishes the upload */
            w.close();
            done = true;
        } catch (IOException e) {
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            }
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.ioexception_in_stream, e);
        } finally {
            if (!done) {
                upload.abort();
            }
        }
        return upload;
    }

    boolean isFinished() {
        return finished;
    }

    synchronized void write(byte[] b, int off, int len) throws SQLException {
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (finished) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null);
        }
        if (length() > MAX_LENGTH - len) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (!held) {
            start();
        }
        while (len > 0) {
            if (held && (chunk == null || filled + len > chunk.length)) {
                /* grown as it fills, so many small held values stay small */
                long size = Math.max(filled + (long) len, chunk == null ? 0 : 2L * chunk.length);
                chunk =
                        Arrays.copyOf(
                                chunk == null ? new byte[0] : chunk,
                                (int) Math.min(CHUNK_SIZE, size));
            }
            int n = Math.min(len, chunk.length - filled);
            System.arraycopy(b, off, chunk, filled, n);
            filled += n;
            off += n;
            len -= n;
            if (filled == CHUNK_SIZE) {
                if (held) {
                    spillChunk();
                } else {
                    sendChunk();
                }
            }
        }
    }

    /* Ends the stream session and returns the upload token; later calls return the same token. */
    synchronized long finish() throws SQLException {
        if (aborted) {
            /* a failed or freed value has no upload to bind */
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (finished) {
            return token;
        }
        /* allocated before the stream opens, so nothing but a send can fail with it open */
        byte[] buf = spill != null ? new byte[CHUNK_SIZE] : null;
        start();
        boolean done = false;
        try {
            if (spill != null) {
                sendSpill(buf);
            }
            if (filled > 0) {
                sendChunk();
            }
            synchronized (conn) {
                checkSession();
                token = conn.streamEndResult();
            }
            done = true;
        } finally {
            if (!done) {
                abort();
            }
        }
        finished = true;
        chunk = null;
        return token;
    }

    synchronized void abort() {
        if (finished) {
            return;
        }
        synchronized (conn) {
            if (started && conn.getStreamSessionCount() == session) {
                try {
                    conn.streamAbort();
                } catch (SQLException ignored) {
                    // the caller reports the error that made it give up
                }
            }
        }
        dropSpill();
        aborted = true;
        finished = true;
        chunk = null;
    }

    OutputStream outputStream() {
        return new OutputStream() {
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            public void write(byte[] b, int off, int len) throws IOException {
                try {
                    CUBRIDInternalLobUpload.this.write(b, off, len);
                } catch (SQLException e) {
                    throw new IOException(e);
                }
            }

            /* closing the stream finishes the value, freeing the connection's stream session */
            public void close() throws IOException {
                try {
                    finish();
                } catch (SQLException e) {
                    throw new IOException(e);
                }
            }
        };
    }

    private void start() throws SQLException {
        if (started) {
            return;
        }
        /* the length is unknown until the value is finished */
        byte[] config =
                ByteBuffer.allocate(20).putInt(blob ? 0 : 1).putLong(-1).putLong(-1).array();
        synchronized (conn) {
            conn.streamInit(STREAM_KIND, config);
            session = conn.getStreamSessionCount();
        }
        if (chunk == null) {
            /* a held value keeps the bytes it has not spilled */
            chunk = new byte[CHUNK_SIZE];
        }
        started = true;
    }

    /* A transaction end drops the session; once another one opens, the bytes would go into it. */
    private void checkSession() throws SQLException {
        if (conn.getStreamSessionCount() != session) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null);
        }
    }

    private void sendChunk() throws SQLException {
        try {
            synchronized (conn) {
                checkSession();
                conn.streamData(chunk, 0, filled);
            }
        } catch (SQLException e) {
            abort();
            throw e;
        }
        sent += filled;
        filled = 0;
    }

    private void spillChunk() throws SQLException {
        try {
            if (spill == null) {
                for (Reference<?> r = DROPPED.poll(); r != null; r = DROPPED.poll()) {
                    SPILLS.remove(r);
                    ((Spill) r).file.delete();
                }
                /* owner-only on POSIX, unlike File.createTempFile */
                spill = Files.createTempFile("cubrid_lob", null).toFile();
                spillRef = new Spill(this, spill);
                SPILLS.add(spillRef);
            }
            /* opened per chunk, so a value waiting to be bound holds no file handle */
            try (OutputStream out = new FileOutputStream(spill, true)) {
                out.write(chunk, 0, filled);
            }
        } catch (IOException e) {
            abort();
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.ioexception_in_stream, e);
        }
        sent += filled;
        filled = 0;
    }

    /* sends what a held value spilled, ahead of the bytes still in the chunk */
    private void sendSpill(byte[] buf) throws SQLException {
        try (InputStream in = new FileInputStream(spill)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (conn) {
                    checkSession();
                    conn.streamData(buf, 0, n);
                }
            }
        } catch (IOException e) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.ioexception_in_stream, e);
        }
        dropSpill();
    }

    private void dropSpill() {
        if (spill != null) {
            SPILLS.remove(spillRef);
            spillRef.clear();
            spill.delete();
            spill = null;
            spillRef = null;
        }
    }
}
