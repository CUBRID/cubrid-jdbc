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

package cubrid.jdbc.lb.connection;

import cubrid.jdbc.lb.LbExceptions;
import cubrid.jdbc.lb.SessionLeg;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.failover.PhysicalRecoveryContext;
import cubrid.jdbc.lb.failover.PhysicalRecoveryResult;
import cubrid.jdbc.lb.statement.PreparedSql;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public final class SimpleEndpointConnManager implements EndpointConnManager {

    private final Map<String, Integer> acquireCounts = new HashMap<String, Integer>();

    private final Map<String, Integer> releaseCounts = new HashMap<String, Integer>();

    private Endpoint sessionRwEndpoint;

    private Endpoint sessionRoEndpoint;

    private boolean readOnRw;

    private final Map<String, PreparedStatement> preparedStatements =
            new HashMap<String, PreparedStatement>();

    private final Map<String, Connection> physicalConnections = new HashMap<String, Connection>();

    private int closePreparedStatementsCallCount;

    private int applyPhysicalAutoCommitCallCount;

    private boolean failNextApplyPhyAutoCommit;

    private int commitPhysicalTransactionCallCount;

    private int rollbackPhysicalTransactionCallCount;

    private String logicalJdbcUrl;

    public synchronized void setLogicalJdbcUrl(final String logicalJdbcUrl) {
        this.logicalJdbcUrl = logicalJdbcUrl;
    }

    public synchronized String getLogicalJdbcUrl() {
        return logicalJdbcUrl;
    }

    public synchronized void bindSession(final Endpoint rwEndpoint, final Endpoint roEndpoint)
            throws SQLException {
        bindSession(rwEndpoint, roEndpoint, null);
    }

    public synchronized void bindSession(
            final Endpoint rwEndpoint, final Endpoint roEndpoint, final EndpointTopology topology)
            throws SQLException {
        if (rwEndpoint == null) {
            throw LbExceptions.invalidValue("RW endpoint must not be null");
        }
        if (roEndpoint == null) {
            throw LbExceptions.invalidValue("RO endpoint must not be null");
        }
        acquire(rwEndpoint);
        acquire(roEndpoint);
        sessionRwEndpoint = rwEndpoint;
        sessionRoEndpoint = roEndpoint;
    }

    public synchronized void bindSessionRwOnly(
            final Endpoint rwEndpoint, final EndpointTopology topology) throws SQLException {
        if (rwEndpoint == null) {
            throw LbExceptions.invalidValue("RW endpoint must not be null");
        }
        acquire(rwEndpoint);
        sessionRwEndpoint = rwEndpoint;
        sessionRoEndpoint = rwEndpoint;
    }

    public synchronized void bindSessionWithReadTarget(
            final Endpoint masterRw,
            final Endpoint readEndpoint,
            final boolean readReusesRw,
            final EndpointTopology topology)
            throws SQLException {
        if (readReusesRw) {
            bindSessionRwOnly(masterRw, topology);
        } else {
            bindSession(masterRw, readEndpoint, topology);
        }
        readOnRw = readReusesRw;
    }

    public synchronized boolean isReadOnRwConnection() {
        return readOnRw;
    }

    public synchronized Endpoint getSessionEndpoint(final SessionLeg role) {
        if (role == null) {
            throw new IllegalArgumentException("SessionLeg must not be null");
        }
        switch (role) {
            case RW:
                return sessionRwEndpoint;
            case RO:
                return sessionRoEndpoint;
            default:
                throw new IllegalStateException("Unsupported endpoint role: " + role);
        }
    }

    public synchronized void acquire(final Endpoint endpoint) throws SQLException {
        if (endpoint == null) {
            throw LbExceptions.invalidValue("Endpoint must not be null");
        }
        String endpointId = endpoint.getId();
        Integer current = acquireCounts.get(endpointId);
        acquireCounts.put(
                endpointId, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
    }

    public synchronized void release(final Endpoint endpoint) {
        if (endpoint == null) {
            return;
        }
        String endpointId = endpoint.getId();
        Integer current = releaseCounts.get(endpointId);
        releaseCounts.put(
                endpointId, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
    }

    public synchronized void releaseBindings() {
        release(sessionRwEndpoint);
        release(sessionRoEndpoint);
        sessionRwEndpoint = null;
        sessionRoEndpoint = null;
    }

    public synchronized void releasePreparedStatements() {
        preparedStatements.clear();
        closePreparedStatementsCallCount++;
    }

    public synchronized void releasePhysicalConnections() {
        releaseBindings();

        acquireCounts.clear();
        physicalConnections.clear();
    }

    public synchronized PreparedStatement prepareStatement(
            final Endpoint endpoint, final String sql) throws SQLException {
        if (endpoint == null) {
            throw LbExceptions.invalidValue("Endpoint must not be null for prepare");
        }
        if (sql == null) {
            throw LbExceptions.invalidValue("SQL must not be null for prepare");
        }
        String key = endpoint.getId() + "|" + PreparedSql.normalizeForCacheKey(sql);
        PreparedStatement cached = preparedStatements.get(key);
        if (cached != null) {
            return cached;
        }
        PreparedStatement created = createStubPreparedStatement();
        preparedStatements.put(key, created);
        return created;
    }

    public synchronized PreparedStatement prepareStatement(
            final Endpoint endpoint, final String sql, final String ownerId) throws SQLException {
        return prepareStatement(endpoint, sql);
    }

    public synchronized PreparedStatement prepareStatement(
            final Endpoint endpoint, final String sql, final String ownerId, final int autoGenKeys)
            throws SQLException {
        return prepareStatement(endpoint, sql);
    }

    public synchronized void closePrepStmts() throws SQLException {
        preparedStatements.clear();
        closePreparedStatementsCallCount++;
    }

    public synchronized void closePrepForOwner(final String ownerId) throws SQLException {
        closePrepStmts();
    }

    public synchronized Connection getPhyConn(final Endpoint endpoint) throws SQLException {
        if (endpoint == null) {
            throw LbExceptions.invalidValue(
                    "Endpoint must not be null for physical connection lookup");
        }
        Connection result = physicalConnections.get(endpoint.getId());
        if (result == null) {
            throw LbExceptions.physicalNotBound(endpoint.getId());
        }
        return result;
    }

    public synchronized void setPhysicalConnection(
            final Endpoint endpoint, final Connection connection) throws SQLException {
        if (endpoint == null) {
            throw LbExceptions.invalidValue("Endpoint must not be null");
        }
        if (connection == null) {
            throw LbExceptions.invalidValue("Connection must not be null");
        }
        physicalConnections.put(endpoint.getId(), connection);
    }

    public synchronized void applyPhyAutoCommit(final boolean autoCommit) throws SQLException {
        applyPhysicalAutoCommitCallCount++;
        if (failNextApplyPhyAutoCommit) {
            failNextApplyPhyAutoCommit = false;
            throw new SQLException("simulated applyPhyAutoCommit failure");
        }
    }

    public synchronized void failNextApplyPhyAutoCommit() {
        this.failNextApplyPhyAutoCommit = true;
    }

    public synchronized void commitPhyTx() throws SQLException {
        commitPhysicalTransactionCallCount++;
    }

    public synchronized void rollbackPhyTx() throws SQLException {
        rollbackPhysicalTransactionCallCount++;
    }

    public synchronized String getRoFallbackReason() {
        return "NONE";
    }

    public synchronized int getAcquireCount(final String endpointId) {
        Integer value = acquireCounts.get(endpointId);
        return value == null ? 0 : value.intValue();
    }

    public synchronized int getReleaseCount(final String endpointId) {
        Integer value = releaseCounts.get(endpointId);
        return value == null ? 0 : value.intValue();
    }

    public synchronized int getPreparedStatementCount() {
        return preparedStatements.size();
    }

    public synchronized int getClosePreparedStatementsCallCount() {
        return closePreparedStatementsCallCount;
    }

    public synchronized int getApplyPhysicalAutoCommitCallCount() {
        return applyPhysicalAutoCommitCallCount;
    }

    public synchronized int getCommitPhysicalTransactionCallCount() {
        return commitPhysicalTransactionCallCount;
    }

    public synchronized int getRollbackPhysicalTransactionCallCount() {
        return rollbackPhysicalTransactionCallCount;
    }

    // Session-state re-application / physical recovery are SessionPhysicalConnManager concerns;
    // this simple manager keeps the pre-promotion behavior of a non-physical manager.
    public void setSessionStateApplier(final SessionStateApplier applier) {}

    public void setLogContext(final String context) {}

    public PhysicalRecoveryResult recoverRw(final PhysicalRecoveryContext ctx) throws SQLException {
        throw LbExceptions.internalState(
                "physical recovery is not supported in the current configuration");
    }

    public PhysicalRecoveryResult recoverRo(final PhysicalRecoveryContext ctx) throws SQLException {
        throw LbExceptions.internalState(
                "physical recovery is not supported in the current configuration");
    }

    public boolean isBoundRwSocketOpen() {
        return true;
    }

    public Endpoint restoreRoIfRecovered() throws SQLException {
        return null;
    }

    public Endpoint restoreRwIfRecovered() throws SQLException {
        return null;
    }

    private PreparedStatement createStubPreparedStatement() {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new InvocationHandler() {
                            public Object invoke(Object proxy, Method method, Object[] args)
                                    throws Throwable {
                                if (Boolean.TYPE.equals(method.getReturnType())) {
                                    return Boolean.FALSE;
                                }
                                if (Integer.TYPE.equals(method.getReturnType())) {
                                    return Integer.valueOf(0);
                                }
                                if (Long.TYPE.equals(method.getReturnType())) {
                                    return Long.valueOf(0L);
                                }
                                if (Float.TYPE.equals(method.getReturnType())) {
                                    return Float.valueOf(0.0f);
                                }
                                if (Double.TYPE.equals(method.getReturnType())) {
                                    return Double.valueOf(0.0d);
                                }
                                return null;
                            }
                        });
    }
}
