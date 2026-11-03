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
import java.nio.ByteBuffer;
import java.sql.SQLException;

/**
 * A forward-only stream over an internal LOB value.
 *
 * <p>An internal LOB column arrives as a locator, not as content, so the payload is pulled from the
 * server in bounded chunks over a download (STREAM_READ_*). The open brings the first chunk and the
 * server skips to the start offset. The server releases the handle when the value is read to its
 * end; {@link #close()} releases one that is still open.
 */
public class CUBRIDInternalLobInputStream extends InputStream {
    private static final int CHUNK_SIZE = 128 * 1024;
    /* STREAM_KIND_INTERNAL_LOB_READ; config: version (int), 0 (int), start offset (long), locator */
    private static final int STREAM_KIND_INTERNAL_LOB_READ = 4;
    private static final int READ_CONFIG_VERSION = 1;

    private final UConnection uconn;
    private final byte[] locator;
    private final long totalLength;
    private final long startOffset;
    /* Absolute byte position (exclusive) at which this stream stops; totalLength when unbounded. Lets
     * getBinaryStream(pos, length) hand back a stream exactly `length` units long, per JDBC 4.0. */
    private final long endLimit;

    private long handle = 0;
    private boolean opened = false;
    private boolean eof = false;
    private long delivered = 0;
    private byte[] chunk = null;
    private int chunkPos = 0;
    private int chunkLen = 0;
    private boolean closed = false;

    public CUBRIDInternalLobInputStream(UConnection uconn, byte[] locator, long totalLength) {
        this(uconn, locator, totalLength, 0, -1);
    }

    public CUBRIDInternalLobInputStream(
            UConnection uconn, byte[] locator, long totalLength, long startOffset) {
        this(uconn, locator, totalLength, startOffset, -1);
    }

    /* startOffset positions the server-side cursor, so the bytes ahead of it never cross the network.
     * length < 0 means read to the end; otherwise the stream delivers at most `length` bytes. */
    public CUBRIDInternalLobInputStream(
            UConnection uconn, byte[] locator, long totalLength, long startOffset, long length) {
        this.uconn = uconn;
        this.locator = locator;
        this.totalLength = totalLength;
        this.startOffset = startOffset;
        this.delivered = startOffset;
        if (length < 0) {
            this.endLimit = totalLength;
        } else {
            long end = startOffset + length;
            this.endLimit = (end < totalLength) ? end : totalLength;
        }
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("stream is closed");
        }
    }

    private byte[] readConfig() {
        ByteBuffer config = ByteBuffer.allocate(4 + 4 + 8 + locator.length);
        config.putInt(READ_CONFIG_VERSION).putInt(0).putLong(startOffset).put(locator);
        return config.array();
    }

    /* The server's error (code and message) rides along as the cause, so the Clob/Blob can rethrow it as is. */
    private IOException serverFailure(String what) {
        SQLException cause = new CUBRIDException(uconn.getRecentError());
        return new IOException(what + ": " + cause.getMessage(), cause);
    }

    /* Releases a handle the server has not released.  Safe to call more than once. */
    private void releaseHandle() {
        if (handle > 0) {
            uconn.streamRelease(handle);
            handle = 0;
        }
    }

    /* Pulls the next chunk.  Returns false once the value is exhausted. */
    private boolean fill() throws IOException {
        if (chunkPos < chunkLen) {
            return true;
        }
        if (delivered >= endLimit || eof) {
            /* the value (or the requested window) is spent: let the handle go now rather than wait for a
             * close() that a caller reading to the end has no reason to make */
            releaseHandle();
            return false;
        }

        long remaining = endLimit - delivered;
        int want = remaining < CHUNK_SIZE ? (int) remaining : CHUNK_SIZE;
        int[] reply = new int[2];
        int got;
        int flags;
        if (!opened) {
            chunk = new byte[CHUNK_SIZE];
            long opening =
                    uconn.streamReadOpen(
                            STREAM_KIND_INTERNAL_LOB_READ,
                            readConfig(),
                            want,
                            UConnection.STREAM_FLAG_RELEASE_AT_EOF,
                            chunk,
                            reply);
            if (opening < 0) {
                throw serverFailure("cannot open the internal LOB stream");
            }
            opened = true;
            handle = opening;
            got = reply[0];
            flags = reply[1];
        } else {
            /* a failed read leaves the handle to close(): released already, unless the request never ran */
            got =
                    uconn.streamRead(
                            handle, chunk, 0, want, UConnection.STREAM_FLAG_RELEASE_AT_EOF, reply);
            if (got < 0) {
                throw serverFailure("cannot read the internal LOB stream");
            }
            flags = reply[0];
        }
        if ((flags & UConnection.STREAM_FLAG_RELEASED) != 0) {
            handle = 0;
        }
        if ((flags & UConnection.STREAM_FLAG_EOF) != 0) {
            eof = true;
        }
        if (got == 0) {
            /* eof, or the server ran out earlier than the locator promised */
            delivered = totalLength;
            releaseHandle();
            return false;
        }

        chunkPos = 0;
        chunkLen = got;
        delivered += got;
        return true;
    }

    @Override
    public int read() throws IOException {
        checkOpen();
        if (!fill()) {
            return -1;
        }
        return chunk[chunkPos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        checkOpen();
        if (b == null) {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        if (!fill()) {
            return -1;
        }

        int available = chunkLen - chunkPos;
        int copied = len < available ? len : available;
        System.arraycopy(chunk, chunkPos, b, off, copied);
        chunkPos += copied;
        return copied;
    }

    @Override
    public long skip(long n) throws IOException {
        checkOpen();
        long skipped = 0;

        while (skipped < n) {
            if (!fill()) {
                break;
            }
            long available = chunkLen - chunkPos;
            long step = (n - skipped) < available ? (n - skipped) : available;
            chunkPos += (int) step;
            skipped += step;
        }
        return skipped;
    }

    @Override
    public int available() {
        if (closed) {
            return 0;
        }
        int buffered = chunkLen - chunkPos;
        long remaining = (endLimit - delivered) + buffered;
        if (remaining < 0) {
            remaining = 0;
        }
        return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        releaseHandle();
        chunk = null;
        chunkPos = chunkLen = 0;
    }
}
