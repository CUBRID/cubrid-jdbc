/*
 * Copyright (C) 2008 Search Solution Corporation.
 * Copyright (c) 2016 CUBRID Corporation.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * - Neither the name of the <ORGANIZATION> nor the names of its contributors may be used to endorse
 * or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package cubrid.jdbc.jci;

/**
 * A socket-less {@link UConnection} for LB tests. Only the protocol answers are faked, by a {@link
 * Node}; the core CUBRIDConnection above it and LB's use of it run unchanged, so a test sees core's
 * own closed checks, statement completion at commit, holdability and ownership.
 */
public class KitUConnection extends UConnection {

    /** The server side of one broker endpoint. */
    public interface Node {
        String host();

        int port();

        /**
         * One request on {@code c}. Returns 0, or the core error code it fails with; a failure that
         * loses the socket also calls {@link #lose()} on {@code c}.
         */
        int request(KitUConnection c, String what);
    }

    private static final java.util.concurrent.atomic.AtomicLong SESSIONS =
            new java.util.concurrent.atomic.AtomicLong();

    private final Node node;
    private byte[] sessionId;
    private boolean needReconnection;
    private boolean autoCommit = true;
    private boolean active;
    private int isolation = CUBRIDIsolationLevel.TRAN_READ_COMMITTED;
    private int networkTimeout;

    public KitUConnection(final Node node) {
        this.node = node;
        this.sessionId = newSessionId();
        errorHandler = new UError(this);
    }

    private static byte[] newSessionId() {
        return java.nio.ByteBuffer.allocate(8).putLong(SESSIONS.incrementAndGet()).array();
    }

    /** A core error with {@code code}, as core reports one. */
    public static UError error(final int code) {
        final UError e = new UError((UConnection) null);
        e.setErrorCode(code);
        return e;
    }

    public Node node() {
        return node;
    }

    /**
     * The socket is lost, as core's clientSocketClose() leaves it: the connection stays open and
     * the next request reconnects, in a new server session. Any open transaction is gone.
     */
    public void dropSocket() {
        needReconnection = true;
        active = false;
    }

    /** A network timeout: core closes the connection for good. */
    public void lose() {
        isClosed = true;
        active = false;
    }

    /** Whether the server holds an open transaction for this connection. */
    public void setActive(final boolean open) {
        active = open;
    }

    /** One request; the error, if any, becomes this connection's recent error. */
    public int send(final String what) {
        errorHandler = new UError(this);
        if (needReconnection) {
            final int refused = node.request(this, "RECONNECT");
            if (refused != 0) {
                errorHandler.setErrorCode(refused);
                return refused;
            }
            needReconnection = false;
            sessionId = newSessionId();
        }
        final int code = node.request(this, what);
        if (code != 0) {
            errorHandler.setErrorCode(code);
        }
        return code;
    }

    @Override
    public void endTransaction(final boolean commit) {
        errorHandler = new UError(this);
        if (!active) {
            return; // core sends nothing for a CAS with no transaction open
        }
        send(commit ? "COMMIT" : "ROLLBACK");
        active = false;
    }

    @Override
    protected void closeInternal() {
        isClosed = true;
    }

    @Override
    public synchronized void close() {
        if (!isClosed) {
            node.request(this, "CLOSE");
            isClosed = true;
        }
    }

    @Override
    public void setAutoCommit(final boolean on) {
        autoCommit = on;
    }

    @Override
    public boolean getAutoCommit() {
        return autoCommit;
    }

    @Override
    public UError getRecentError() {
        return errorHandler;
    }

    @Override
    public boolean isClosed() {
        return isClosed;
    }

    @Override
    public boolean isValid(final int timeout) {
        return !isClosed && send("CHECK_CAS") == 0;
    }

    @Override
    public synchronized void setIsolationLevel(final int level) {
        if (send("SET_ISOLATION " + level) == 0) {
            isolation = level;
        }
    }

    @Override
    public synchronized int getIsolationLevel() {
        return isolation;
    }

    @Override
    public synchronized void setLockTimeout(final int timeout) {
        send("SET_LOCK_TIMEOUT " + timeout);
    }

    @Override
    public synchronized int setCASChangeMode(final int mode) {
        send("SET_CAS_CHANGE_MODE " + mode);
        return mode;
    }

    @Override
    public void setCharset(final String name) {}

    @Override
    public void setNetworkTimeout(final int milliseconds) {
        networkTimeout = milliseconds;
    }

    @Override
    public int getNetworkTimeout() {
        return networkTimeout;
    }

    @Override
    public String getCasIp() {
        return node.host();
    }

    @Override
    public int getCasPort() {
        return node.port();
    }

    @Override
    public int getCasProcessId() {
        return 1;
    }

    @Override
    public int getCasId() {
        return 1;
    }

    @Override
    public byte[] getSessionId() {
        return sessionId.clone();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public byte getCASInfoStatus() {
        return active ? CAS_INFO_STATUS_ACTIVE : CAS_INFO_STATUS_INACTIVE;
    }

    @Override
    public boolean supportHoldableResult() {
        return true;
    }

    @Override
    public boolean brokerInfoStatementPooling() {
        return true;
    }

    @Override
    public int getPrepStmtCacheSize() {
        return 1;
    }

    @Override
    public boolean isPrepStmtCache(final String sql) {
        return false;
    }

    @Override
    public boolean isConnectedToProxy() {
        return false;
    }

    @Override
    public boolean isConnectedToGateway() {
        return false;
    }

    @Override
    public boolean isConnectedToCubrid() {
        return true;
    }

    @Override
    public int getQueryTimeout() {
        return 0;
    }

    @Override
    public int getShardId() {
        return 0;
    }

    @Override
    public boolean getLogSlowQuery() {
        return false;
    }

    @Override
    public void setBeginTime() {}

    @Override
    public synchronized void turnOnAutoCommitBySelf() {}

    @Override
    public synchronized boolean supportSchema() {
        return false;
    }

    @Override
    public int brokerProtocolVersion() {
        return PROTOCOL_V13;
    }

    @Override
    public void logException(final Throwable t) {}

    @Override
    public synchronized void savepoint(final byte mode, final String name) {
        send("SAVEPOINT " + mode + " " + name);
    }
}
