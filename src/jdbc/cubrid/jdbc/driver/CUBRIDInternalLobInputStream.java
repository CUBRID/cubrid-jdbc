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

/**
 * A forward-only stream over an internal LOB value.
 *
 * <p>An internal LOB column arrives as a locator, not as content, so the payload is pulled from the
 * server in bounded chunks. The server read cursor is forward-only; a caller that needs to start at
 * an offset opens a stream and skips to it, which is what {@link CUBRIDBlob#getBytes} does. The
 * token is released on {@link #close()}, and also when the value has been read to its end.
 */
public class CUBRIDInternalLobInputStream extends InputStream {
    private static final int CHUNK_SIZE = 128 * 1024;

    private final UConnection uconn;
    private final byte[] locator;
    private final long totalLength;

    private long token = 0;
    private long delivered = 0;
    private byte[] chunk = null;
    private int chunkPos = 0;
    private int chunkLen = 0;
    private boolean closed = false;

    public CUBRIDInternalLobInputStream(UConnection uconn, byte[] locator, long totalLength) {
        this.uconn = uconn;
        this.locator = locator;
        this.totalLength = totalLength;
    }

    private void open() throws IOException {
        if (token > 0) {
            return;
        }
        long opened = uconn.lobStreamOpen(locator);
        if (opened <= 0) {
            throw new IOException("cannot open the internal LOB stream");
        }
        token = opened;
        chunk = new byte[CHUNK_SIZE];
    }

    /* Releases the server-side read cursor.  Safe to call more than once. */
    private void releaseToken() {
        if (token > 0) {
            uconn.lobStreamClose(token);
            token = 0;
        }
    }

    /* Pulls the next chunk.  Returns false once the value is exhausted. */
    private boolean fill() throws IOException {
        if (chunkPos < chunkLen) {
            return true;
        }
        if (delivered >= totalLength) {
            /* the value is spent: let the cursor go now rather than wait for a close() that a caller
             * reading to EOF has no reason to make */
            releaseToken();
            return false;
        }

        open();

        long remaining = totalLength - delivered;
        int want = remaining < CHUNK_SIZE ? (int) remaining : CHUNK_SIZE;
        int got = uconn.lobStreamRead(token, chunk, 0, want);
        if (got < 0) {
            throw new IOException("cannot read the internal LOB stream");
        }
        if (got == 0) {
            /* the server ran out earlier than the locator promised */
            delivered = totalLength;
            releaseToken();
            return false;
        }

        chunkPos = 0;
        chunkLen = got;
        delivered += got;
        return true;
    }

    @Override
    public int read() throws IOException {
        if (!fill()) {
            return -1;
        }
        return chunk[chunkPos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
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
        int buffered = chunkLen - chunkPos;
        long remaining = (totalLength - delivered) + buffered;
        return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        releaseToken();
        chunk = null;
        chunkPos = chunkLen = 0;
    }
}
