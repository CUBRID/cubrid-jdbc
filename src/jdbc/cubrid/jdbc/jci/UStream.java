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

import java.io.IOException;

/*
 * The byte-stream requests of PROTOCOL_V13 (UFunctionCode.STREAM_*). An upload, opened by a
 * statement (COPY ... FROM STDIN) or by init (), takes sendData () until end () or abort (). A
 * download is opened by readOpen (), read until eof and then released. Each request holds the
 * connection's lock and leaves its error in the connection's error handler, as the requests of
 * UConnection do; a request that fails returns -1.
 */
public final class UStream {

    /* The core bounds one download read by this, whatever was asked. */
    public static final int READ_MAX_CHUNK = 1024 * 1024;

    /* The flags byte of INIT and the download requests, and the flags download replies carry. */
    public static final byte FLAG_RELEASE_AT_EOF = 0x01;
    public static final byte FLAG_AUTO_COMMIT = 0x02;
    public static final int FLAG_EOF = 0x01;
    public static final int FLAG_RELEASED = 0x02;

    /* What a request sends after its function code. */
    private interface Arguments {
        void write(UOutputBuffer out) throws IOException;
    }

    /* What a request returns, read from a reply whose result code is not negative. */
    private interface Result {
        long read(UInputBuffer in) throws UJciException;
    }

    private static final Arguments NO_ARGUMENTS = out -> {};

    private final UConnection conn;

    UStream(UConnection conn) {
        this.conn = conn;
    }

    // UFunctionCode.STREAM_INIT: opens an upload of a kind the driver opens itself
    public int init(final int streamKind, final byte[] config) {
        boolean valid = config != null;
        return (int)
                request(
                        UFunctionCode.STREAM_INIT,
                        valid,
                        out -> {
                            out.addInt(streamKind);
                            out.addBytes(config);
                            /* no FINAL flag and no first chunk: this only opens */
                            out.addByte(conn.getAutoCommit() ? FLAG_AUTO_COMMIT : (byte) 0);
                            out.addBytes(new byte[0]);
                        },
                        UInputBuffer::getResCode);
    }

    // UFunctionCode.STREAM_SEND_DATA
    public int sendData(final byte[] data, final int start, final int len) {
        boolean valid =
                data != null
                        && start >= 0
                        && len >= 0
                        && start <= data.length
                        && len <= data.length - start;
        return (int)
                request(
                        UFunctionCode.STREAM_SEND_DATA,
                        valid,
                        out -> out.addBytes(data, start, len),
                        UInputBuffer::getResCode);
    }

    /* Returns the 64-bit value the kind defines: the rows loaded for COPY, the upload token for
     * internal-LOB (kind 1). */
    // UFunctionCode.STREAM_END
    public long end() {
        return request(UFunctionCode.STREAM_END, NO_ARGUMENTS, UInputBuffer::readLong);
    }

    // UFunctionCode.STREAM_ABORT
    public int abort() {
        return (int) request(UFunctionCode.STREAM_ABORT, NO_ARGUMENTS, UInputBuffer::getResCode);
    }

    /* The open may bring the first piece of at most firstMax bytes into buf. reply[0] gets its
     * length, reply[1] the reply flags. Returns the handle, or 0 once the server released it. */
    // UFunctionCode.STREAM_READ_OPEN
    public long readOpen(
            final int streamKind,
            final byte[] config,
            final int firstMax,
            final byte flags,
            final byte[] buf,
            final int[] reply) {
        boolean valid =
                config != null
                        && firstMax >= 0
                        && (firstMax == 0 || (buf != null && firstMax <= buf.length))
                        && reply != null
                        && reply.length >= 2;
        return request(
                UFunctionCode.STREAM_READ_OPEN,
                valid,
                out -> {
                    out.addInt(streamKind);
                    out.addBytes(config);
                    out.addInt(firstMax);
                    out.addByte(readFlags(flags));
                },
                in -> {
                    long handle = in.readLong();
                    readPiece(in, buf, 0, firstMax, reply);
                    return handle;
                });
    }

    /* Reads at most max bytes into buf at offset; replyFlags[0] gets the reply flags. Returns the
     * bytes read. */
    // UFunctionCode.STREAM_READ
    public int read(
            final long handle,
            final byte[] buf,
            final int offset,
            final int max,
            final byte flags,
            final int[] replyFlags) {
        boolean valid =
                handle > 0
                        && buf != null
                        && max > 0
                        && max <= READ_MAX_CHUNK
                        && offset >= 0
                        && offset <= buf.length
                        && max <= buf.length - offset
                        && replyFlags != null
                        && replyFlags.length >= 1;
        return (int)
                request(
                        UFunctionCode.STREAM_READ,
                        valid,
                        out -> {
                            out.addLong(handle);
                            out.addInt(max);
                            out.addByte(readFlags(flags));
                        },
                        in -> {
                            int[] reply = new int[2];
                            readPiece(in, buf, offset, max, reply);
                            replyFlags[0] = reply[1];
                            return reply[0];
                        });
    }

    /* A handle the server no longer has -- released at eof, on a failed read or with its
     * transaction -- is not an error. */
    // UFunctionCode.STREAM_RELEASE
    public int release(final long handle) {
        return (int)
                request(
                        UFunctionCode.STREAM_RELEASE,
                        out -> {
                            out.addLong(handle);
                            /* in auto-commit, the release that unties the transaction commits it */
                            out.addByte(conn.getAutoCommit() ? (byte) 1 : (byte) 0);
                        },
                        UInputBuffer::getResCode);
    }

    /* The caller asks only for FLAG_RELEASE_AT_EOF. FLAG_AUTO_COMMIT follows the connection, so in
     * auto-commit the release that unties the transaction commits it. */
    private byte readFlags(byte flags) {
        flags &= FLAG_RELEASE_AT_EOF;
        return conn.getAutoCommit() ? (byte) (flags | FLAG_AUTO_COMMIT) : flags;
    }

    /* A piece of a download reply: its length, the reply flags, then the bytes. */
    private void readPiece(UInputBuffer in, byte[] buf, int offset, int max, int[] reply)
            throws UJciException {
        int nread = in.readInt();
        int flags = in.readInt();
        if (nread < 0 || nread > max) {
            throw conn.createJciException(UErrorCode.ER_COMMUNICATION);
        }
        if (nread > 0) {
            in.readBytes(buf, offset, nread);
        }
        reply[0] = nread;
        reply[1] = flags;
    }

    private long request(UFunctionCode code, Arguments args, Result result) {
        return request(code, true, args, result);
    }

    private long request(UFunctionCode code, boolean valid, Arguments args, Result result) {
        synchronized (conn) {
            conn.errorHandler = new UError(conn);
            if (conn.isClosed) {
                conn.errorHandler.setErrorCode(UErrorCode.ER_IS_CLOSED);
                return -1;
            }
            if (!valid) {
                conn.errorHandler.setErrorCode(UErrorCode.ER_INVALID_ARGUMENT);
                return -1;
            }
            try {
                conn.setBeginTime();
                conn.checkReconnect();
                if (conn.errorHandler.getErrorCode() != UErrorCode.ER_NO_ERROR) return -1;
                /* an older CAS closes the connection on a function code it does not know */
                if (conn.brokerProtocolVersion() < UConnection.PROTOCOL_V13
                        || conn.casPredatesStreams()) {
                    conn.errorHandler.setDBError(
                            UErrorCode.CAS_ER_NOT_IMPLEMENTED,
                            UErrorCode.codeToCASMessage(UErrorCode.CAS_ER_NOT_IMPLEMENTED));
                    return -1;
                }

                conn.outBuffer.newRequest(conn.output, code);
                args.write(conn.outBuffer);
                /* a reply with a negative result code throws in UInputBuffer */
                return result.read(conn.send_recv_msg());
            } catch (UJciException e) {
                conn.logException(e);
                e.toUError(conn.errorHandler);
                if (e.getJciError() != UErrorCode.ER_DBMS) {
                    /* no usable reply: the CAS and its stream may be gone, so start over */
                    conn.clientSocketClose();
                }
            } catch (IOException e) {
                conn.logException(e);
                conn.errorHandler.setErrorCode(UErrorCode.ER_COMMUNICATION);
                conn.clientSocketClose();
            } catch (Exception e) {
                conn.logException(e);
                conn.errorHandler.setErrorCode(UErrorCode.ER_UNKNOWN);
            }
            return -1;
        }
    }
}
