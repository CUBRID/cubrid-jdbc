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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Vector;

/**
 * A connected socket with no server behind it, like {@code LegTransactionEndNotifyTest}'s: the CAS
 * protocol version and the CAS info of the last reply are what the test sets, requests are kept,
 * and the one reply there is is the one a test queues: a refusal or a body.
 */
public final class StreamSocket extends UConnection {

    private final ByteArrayOutputStream sent = new ByteArrayOutputStream();

    private int commits;

    private int rollbacks;

    /**
     * @param protocolVersion the protocol version the CAS reported at connect
     * @param additionalFlags the additional-flag byte of the CAS's last reply
     */
    public StreamSocket(final int protocolVersion, final int additionalFlags) throws IOException {
        this.protocolVersion = protocolVersion;
        errorHandler = new UError(this);
        brokerInfo = new byte[BROKER_INFO_SIZE];
        brokerInfo[BROKER_INFO_DBMS_TYPE] = DBMS_CUBRID;
        brokerInfo[BROKER_INFO_FUNCTION_FLAG] = CAS_RENEWED_ERROR_CODE;
        output = new DataOutputStream(sent);
        outBuffer = new UOutputBuffer(this);
        deferred_close_handle = new Vector<Integer>();
        report(additionalFlags);
    }

    /** The CAS info of the last reply: a transaction is open, with these additional flags. */
    public void report(final int additionalFlags) {
        casInfo = new byte[] {CAS_INFO_STATUS_ACTIVE, 0, 0, (byte) additionalFlags};
    }

    /** The CAS answers the next request with this server error, and the same CAS info. */
    public void refuseNextRequest(final int serverError, final String message) {
        byte[] text = message.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer reply = ByteBuffer.allocate(8 + 8 + text.length + 1);
        reply.putInt(8 + text.length + 1).put(casInfo);
        reply.putInt(UErrorCode.DBMS_ERROR_INDICATOR).putInt(serverError).put(text).put((byte) 0);
        input = new UTimedDataInputStream(new ByteArrayInputStream(reply.array()), "cas", 0);
    }

    /**
     * The CAS answers the next request with result code 0, this body after it, and the same CAS
     * info.
     */
    public void replyNext(final byte[] body) {
        ByteBuffer reply = ByteBuffer.allocate(8 + 4 + body.length);
        reply.putInt(4 + body.length).put(casInfo).putInt(0).put(body);
        input = new UTimedDataInputStream(new ByteArrayInputStream(reply.array()), "cas", 0);
    }

    /** The CAS is gone: the next request reads end of stream where its reply should be. */
    public void loseCas() {
        input = new UTimedDataInputStream(new ByteArrayInputStream(new byte[0]), "cas", 0);
    }

    /** Whether the next request reconnects first. */
    public boolean reconnects() {
        return needReconnection;
    }

    /** Every request sent so far, as it went on the wire. */
    public byte[] sent() {
        return sent.toByteArray();
    }

    public int commits() {
        return commits;
    }

    public int rollbacks() {
        return rollbacks;
    }

    @Override
    protected void checkReconnect() {}

    @Override
    public int getPrepStmtCacheSize() {
        return 0;
    }

    public void endTransaction(final boolean commit) {
        if (commit) {
            commits++;
        } else {
            rollbacks++;
        }
    }

    protected void closeInternal() {}

    public void setAutoCommit(final boolean autoCommit) {}

    public boolean getAutoCommit() {
        return true;
    }
}
