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

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.SessionLeg;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.failover.PhysicalRecoveryContext;
import cubrid.jdbc.lb.failover.PhysicalRecoveryResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Session physical JDBC bindings: RW/RO (and failover), prepareStatement, transaction propagation,
 * and standalone physical commands.
 *
 * <p>Implementations: {@link SessionPhysicalConnManager}. {@link #bindSession} may try alternate
 * brokers from {@link cubrid.jdbc.lb.config.EndpointTopology} on connect failure.
 */
public interface EndpointConnManager extends LoadBalanceConnection.PhysicalResourceReleaser {

    /** Applies the owning session's state (autoCommit/isolation/schema/...) to a physical conn. */
    interface SessionStateApplier {
        void applyTo(Connection physical) throws SQLException;
    }

    /**
     * Registers the hook that re-applies the logical session state to every freshly opened physical
     * connection; set by the owning {@link LoadBalanceConnection}. Managers without a
     * re-application concern may ignore it.
     *
     * @param applier the session-state applier to invoke on each new physical connection
     */
    void setSessionStateApplier(SessionStateApplier applier);

    /**
     * Registers the identifier this manager's log records are attributed to ({@code conn#7}), set
     * by the owning {@link LoadBalanceConnection}. A manager built directly in a unit test never
     * gets one and its records render with the no-context marker.
     *
     * @param context the connection context for log records; may be {@code null}
     */
    void setLogContext(String context);

    void bindSession(Endpoint rwEndpoint, Endpoint roEndpoint) throws SQLException;

    void bindSession(Endpoint rwEndpoint, Endpoint roEndpoint, EndpointTopology topology)
            throws SQLException;

    /**
     * Binds a session with only an RW endpoint when no RO broker is configured. A single physical
     * connection is opened and serves both read and write SQL permanently (no RO physical
     * connection, no RO self-healing).
     *
     * @param rwEndpoint the RW broker endpoint serving both reads and writes
     * @param topology the endpoint topology used for failover
     * @throws SQLException if the physical connection cannot be opened
     */
    void bindSessionRwOnly(Endpoint rwEndpoint, EndpointTopology topology) throws SQLException;

    /**
     * Role-model session binding. Writes always run on {@code masterRw}; this logical connection's
     * reads run on {@code readEndpoint} (the single read target chosen once by readWeight).
     *
     * <p>When the chosen read target is the master ({@code readReusesRw} — roOnRw), no separate
     * read connection is opened: the RW physical connection serves both read and write, so the
     * logical connection holds <b>one</b> physical connection. Otherwise a separate RO/SO physical
     * connection is opened for {@code readEndpoint}, giving two physical connections.
     *
     * @param masterRw the master RW broker endpoint that serves writes
     * @param readEndpoint the read target chosen by readWeight
     * @param readReusesRw true when reads run on the RW physical connection (roOnRw)
     * @param topology the endpoint topology used for failover
     * @throws SQLException if a physical connection cannot be opened
     */
    void bindSessionWithReadTarget(
            Endpoint masterRw,
            Endpoint readEndpoint,
            boolean readReusesRw,
            EndpointTopology topology)
            throws SQLException;

    Endpoint getSessionEndpoint(SessionLeg role);

    /**
     * Whether reads currently run on the RW physical connection instead of one of their own - the
     * {@code readReusesRw} state of {@link #bindSessionWithReadTarget}, which failover can also
     * enter when every read endpoint is down. Exported as an operator gauge.
     *
     * @return whether reads currently run on the RW physical connection
     */
    boolean isReadOnRwConnection();

    void releaseBindings();

    PreparedStatement prepareStatement(Endpoint endpoint, String sql) throws SQLException;

    /**
     * Prepare owned by a specific logical PreparedStatement (ownerId), so its physical PS is not
     * shared with other logical PS of the same SQL.
     *
     * @param endpoint the endpoint to prepare on
     * @param sql the SQL to prepare
     * @param ownerId the owning logical PreparedStatement's id
     * @return the physical prepared statement
     * @throws SQLException if the prepare fails
     */
    PreparedStatement prepareStatement(Endpoint endpoint, String sql, String ownerId)
            throws SQLException;

    /**
     * Owner-scoped prepare that also carries the JDBC generated-keys flag to the physical prepare
     * so RETURN_GENERATED_KEYS is honored instead of silently dropped.
     *
     * @param endpoint the endpoint to prepare on
     * @param sql the SQL to prepare
     * @param ownerId the owning logical PreparedStatement's id
     * @param autoGeneratedKeys one of the {@code Statement.RETURN_GENERATED_KEYS} constants
     * @return the physical prepared statement
     * @throws SQLException if the prepare fails
     */
    PreparedStatement prepareStatement(
            Endpoint endpoint, String sql, String ownerId, int autoGeneratedKeys)
            throws SQLException;

    void closePrepStmts() throws SQLException;

    /**
     * Close only the physical prepared statements owned by one logical PreparedStatement.
     *
     * @param ownerId the owning logical PreparedStatement's id
     * @throws SQLException if a physical close fails
     */
    void closePrepForOwner(String ownerId) throws SQLException;

    Connection getPhyConn(Endpoint endpoint) throws SQLException;

    void applyPhyAutoCommit(boolean autoCommit) throws SQLException;

    void commitPhyTx() throws SQLException;

    void rollbackPhyTx() throws SQLException;

    String getRoFallbackReason();

    String getLogicalJdbcUrl();

    /**
     * Runtime failover of the bound RW physical connection. Managers that do not support physical
     * recovery throw {@code internalState}.
     *
     * @param ctx the recovery context describing the failed endpoint and candidates
     * @return the recovery outcome
     * @throws SQLException if recovery is unsupported or every candidate fails
     */
    PhysicalRecoveryResult recoverRw(PhysicalRecoveryContext ctx) throws SQLException;

    /**
     * Runtime failover of the bound RO physical connection (may fall back to roOnRw). Managers that
     * do not support physical recovery throw {@code internalState}.
     *
     * @param ctx the recovery context describing the failed endpoint and candidates
     * @return the recovery outcome
     * @throws SQLException if recovery is unsupported or every candidate fails
     */
    PhysicalRecoveryResult recoverRo(PhysicalRecoveryContext ctx) throws SQLException;

    /**
     * Pool health probe: whether this session's mandatory RW physical connection is still an open
     * socket. <b>Structural, not a liveness check</b>: it makes no network round trip, so a down
     * broker still reports {@code true} and the failover machinery is what heals that. Managers
     * with no physical connection of their own return {@code true}.
     *
     * @return whether the bound RW physical connection is still open
     */
    boolean isBoundRwSocketOpen();

    /**
     * Lazy RO failback: reconnect to the recovered home read endpoint when reads have been
     * displaced (e.g. roOnRw). Returns the restored endpoint, or {@code null} when nothing changed.
     * Managers without failback return {@code null}.
     *
     * @return the restored read endpoint, or {@code null} when nothing changed
     * @throws SQLException if the reconnect fails
     */
    Endpoint restoreRoIfRecovered() throws SQLException;

    /**
     * Lazy RW failback: rebind the RW leg to the master RW endpoint when it has been displaced onto
     * a sibling RW broker and the master has recovered. Returns the restored endpoint, or {@code
     * null} when nothing changed. Managers without failback return {@code null}.
     *
     * @return the restored RW endpoint, or {@code null} when nothing changed
     * @throws SQLException if the reconnect fails
     */
    Endpoint restoreRwIfRecovered() throws SQLException;
}
