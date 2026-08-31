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

package cubrid.jdbc.lb;

import cubrid.jdbc.driver.CUBRIDConnection;
import cubrid.jdbc.driver.CUBRIDConnectionKey;
import cubrid.jdbc.driver.CUBRIDDriver;
import cubrid.jdbc.driver.CUBRIDOutResultSet;
import cubrid.jdbc.driver.CUBRIDShardMetaData;
import cubrid.jdbc.driver.CUBRIDStatement;
import cubrid.jdbc.jci.ReconnectPolicy;
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.NodeRole;
import cubrid.jdbc.lb.config.ResolvedRoleTopology;
import cubrid.jdbc.lb.connection.EndpointConnManager;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import cubrid.jdbc.lb.failover.ExecuteFailoverHandler;
import cubrid.jdbc.lb.failover.PhysicalRecoveryContext;
import cubrid.jdbc.lb.failover.PhysicalRecoveryResult;
import cubrid.jdbc.lb.failover.UnreachableEndpoints;
import cubrid.jdbc.lb.log.LbDistLog;
import cubrid.jdbc.lb.log.LbFileLogging;
import cubrid.jdbc.lb.log.LbLog;
import cubrid.jdbc.lb.log.LbLogDedup;
import cubrid.jdbc.lb.log.LbTopologyLog;
import cubrid.jdbc.lb.metrics.MetricsExporters;
import cubrid.jdbc.lb.route.RoleReadSelector;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.sql.SqlClassifier;
import cubrid.jdbc.lb.sql.SqlClassifierRegistry;
import cubrid.jdbc.lb.state.BindingView;
import cubrid.jdbc.lb.state.CurrentReadEndpointHolder;
import cubrid.jdbc.lb.state.MetricsRegistry;
import cubrid.jdbc.lb.state.RecoveryBackoff;
import cubrid.jdbc.lb.state.RecoveryBackoffRegistry;
import cubrid.jdbc.lb.state.RuntimeMetrics;
import cubrid.jdbc.lb.state.SessionRoutingState;
import cubrid.jdbc.lb.state.SharedSelectorState;
import cubrid.jdbc.lb.state.SharedSelectorStateRegistry;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import cubrid.jdbc.lb.statement.LBStatement;
import java.io.UnsupportedEncodingException;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Logical JDBC {@link java.sql.Connection} for HA load balancing.
 *
 * <p>Applications use one connection; internally {@link cubrid.jdbc.lb.route.Router} and {@link
 * cubrid.jdbc.lb.connection.EndpointConnManager} route SQL to session-bound RW/RO physical brokers.
 *
 * <p>Driver entry for a URI {@code loadbalance://} URL: {@link #openFromSettings(String,
 * Properties, LoadBalanceSettings)}. Tests may use {@link
 * #LoadBalanceConnection(LoadBalanceSettings)} directly.
 *
 * <p>Public integration: {@link #setSharedSelectorState}, {@link #initSessionBindings}, JDBC {@link
 * cubrid.jdbc.lb.statement.LBStatement} / {@link cubrid.jdbc.lb.statement.LBPreparedStatement}.
 *
 * <p>It also owns the session-level state that routing needs: the weighted read home slot taken
 * from the pool-shared selector, the currently bound RW/read endpoints, transaction state, the JDBC
 * session properties re-applied to every fresh physical connection, and the routing metrics.
 */
public class LoadBalanceConnection extends CUBRIDConnection {
    private static final AtomicLong CONNECTION_SEQUENCE = new AtomicLong(1L);
    // Monotonic id source for per-logical-PreparedStatement prep-cache ownership.
    private static final AtomicLong PS_OWNER_SEQ = new AtomicLong(0L);
    private final LoadBalanceSettings config;
    private final SqlClassifier sqlClassifier;
    private final SessionRoutingState sessionState;
    private final CurrentReadEndpointHolder boundReadEndpoint;
    private final RoleReadSelector roleReadSelector = new RoleReadSelector();
    private final List<Statement> openStatements = new ArrayList<Statement>();

    /**
     * Stored-procedure OUT result sets registered against this session. Populated because this
     * session owns the value objects its legs produce; see {@code claimValueObjectOwnership}.
     */
    private final List<CUBRIDOutResultSet> openOutResultSets = new ArrayList<CUBRIDOutResultSet>();

    private final long connectionId = CONNECTION_SEQUENCE.getAndIncrement();
    private final RoutingMetricsRecorder routingMetrics;
    private EndpointConnManager connMgr = null;
    private PhysicalResourceReleaser physicalResourceReleaser = new NoOpPhysicalResourceReleaser();
    private SharedSelectorState selState = null;
    // The role whose weighted-home slot this session holds in selState, so close() can give it
    // back. volatile for the same reason as `closed`: close()/abort() may run on another thread.
    private volatile NodeRole homeReadRole = null;
    private Endpoint currentRwEndpoint = null;
    private Endpoint currentRoEndpoint = null;
    // The endpoint actually selected for the most recent physical prepare/statement, so failover
    // recovers the endpoint that was really used, not a recomputed preview.
    private Endpoint lastExecEndpoint = null;
    private EndpointTopology sessionTopology = null;
    private final Object recoveryLock = new Object();
    private boolean sessionInitialized;
    private long lastReadFailbackAttemptMs = 0L;
    private long lastRwFailbackAttemptMs = 0L;
    // volatile: close()/abort() may run on a pool-eviction or abort() thread other than the one
    // using the connection, so the closed flag must be visible across threads.
    private volatile boolean closed = false;
    private int transactionIsolation = Connection.TRANSACTION_READ_COMMITTED;
    private int holdability = ResultSet.HOLD_CURSORS_OVER_COMMIT;
    private int networkTimeout;
    private boolean readOnly = false;
    private String catalog = null;
    private String schema = null;
    private Integer lockTimeout = null;
    private String charset = null;

    private DatabaseMetaData rwMetaForCmd = null;
    private Integer casChangeMode = null;
    private static final Logger LOGGER = Logger.getLogger(LoadBalanceConnection.class.getName());
    private ExecuteFailoverHandler executeFailoverHandler = new ExecuteFailoverHandler();
    // True when this connection's RuntimeMetrics was registered for export; drives unregister
    // (flush of final counts) at close().
    private boolean metricsRegistered = false;
    // Wall-clock birth of this logical connection, for the lifetime reported at close(). A pool
    // discarding healthy connections shows up as a population of very short lifetimes, which cannot
    // be seen from the close record alone.
    private final long createdAtMs = System.currentTimeMillis();

    public interface PhysicalResourceReleaser {

        void releasePreparedStatements();

        void releasePhysicalConnections();
    }

    private static final class NoOpPhysicalResourceReleaser implements PhysicalResourceReleaser {
        public void releasePreparedStatements() {}

        public void releasePhysicalConnections() {}
    }

    /**
     * Builds a connection that reports no URL. For an in-memory configuration there is no URL the
     * application wrote — the topology is supplied directly — so there is nothing honest to report;
     * {@link #openFromSettings} uses the URL-carrying form.
     *
     * @param config the parsed load-balance settings
     */
    public LoadBalanceConnection(LoadBalanceSettings config) {
        this(null, null, config);
    }

    /**
     * @param userUrl the URL the application wrote, reported by the inherited {@code
     *     CUBRIDConnection} as this connection's URL. Masked, because that field is readable and a
     *     URI URL can carry an inline password.
     * @param info the connection properties, consulted only for the effective {@code user} when
     *     masking; may be {@code null}
     * @param config the parsed load-balance settings
     */
    public LoadBalanceConnection(
            final String userUrl, final Properties info, final LoadBalanceSettings config) {
        // super() must come first, so the config null check moves into the argument helper.
        super(CUBRIDDriver.maskUriUrlPassword(userUrl, info), userOf(config));
        this.config = config;
        // Before anything that can log: the handler must be in place for this connection's own
        // binding/failure records, and install() is a no-op after the first connection.
        LbFileLogging.install(config.getLbLogConfig());
        this.sqlClassifier = SqlClassifierRegistry.forConfig(config);
        this.sessionState = new SessionRoutingState();
        this.routingMetrics =
                new RoutingMetricsRecorder(connectionId, config, sessionState, sqlClassifier);
        this.boundReadEndpoint = new CurrentReadEndpointHolder();
        maybeStartMetrics(config);
        LbLog.fine(LOGGER, LbLog.conn(connectionId), "LB CONN: opened");
    }

    /**
     * Switches on recording for this connection and registers it so a consumer can read it without
     * borrowing the connection back, then starts the requested exporters (idempotent - the first
     * connection wins per JVM).
     *
     * <p>Two consumers ask for this and need different amounts of it:
     *
     * <ul>
     *   <li>{@code metricsEnabled} wants everything: counters, the per-statement event log, and an
     *       exporter (a Prometheus endpoint or a CSV thread).
     *   <li>{@code lbLogDistIntervalSec} wants only the counters. It reports distribution totals to
     *       the log, so the event log is dead weight and an exporter would be a side effect nobody
     *       asked for - no port should open and no CSV file appear because a log option was set.
     * </ul>
     *
     * <p>Registration matters as much as the counters: without it the aggregation APIs cannot see
     * this connection, and the distribution records would not read "all zero" - they would not be
     * written at all, because {@code aggregateBindingByEndpoint()} and friends return empty maps.
     *
     * <p>{@code MetricsExporters.configure} still gets the metrics config unchanged and checks
     * {@code isEnabled()} itself, so the DIST path cannot start an exporter by accident.
     */
    private void maybeStartMetrics(final LoadBalanceSettings config) {
        final boolean forExport = config.getMetricsConfig().isEnabled();
        final boolean forDistLog = config.getLbLogConfig().getDistIntervalSec() > 0;
        if (!forExport && !forDistLog) {
            return;
        }
        routingMetrics.runtimeMetrics().setEnabled(true);
        // setEnabled turns events on too; the DIST-only path switches them back off so it does not
        // pay for a per-statement string nobody reads.
        routingMetrics.runtimeMetrics().setEventsEnabled(forExport);
        MetricsRegistry.register(
                routingMetrics.runtimeMetrics(),
                buildEndpointRoleMap(config),
                new BindingView() {
                    // Read lazily at export time: the endpoint is not yet bound at construction and
                    // can move on failover/failback.
                    public String boundReadEndpointId() {
                        Endpoint ep = currentRoEndpoint;
                        return ep == null ? null : ep.getId();
                    }

                    public boolean readsOnRwConnection() {
                        EndpointConnManager mgr = connMgr;
                        return mgr != null && mgr.isReadOnRwConnection();
                    }
                });
        MetricsExporters.configure(config.getMetricsConfig());
        metricsRegistered = true;
    }

    /**
     * endpoint id (host:port) → role, for folding per-endpoint counts into master/slave/replica.
     */
    private static Map<String, String> buildEndpointRoleMap(final LoadBalanceSettings config) {
        Map<String, String> roles = new HashMap<String, String>();
        ResolvedRoleTopology topology = config.getResolvedTopology();
        if (topology == null) {
            return roles;
        }
        ResolvedRoleTopology.ResolvedNode master = topology.getMaster();
        if (master != null) {
            putRole(roles, master.getRw(), "master");
            putRole(roles, master.getRo(), "master");
        }
        List<ResolvedRoleTopology.ResolvedNode> slaves = topology.getSlaves();
        for (int i = 0; i < slaves.size(); i++) {
            putRole(roles, slaves.get(i).getRw(), "slave");
            putRole(roles, slaves.get(i).getRo(), "slave");
        }
        List<ResolvedRoleTopology.ResolvedReplica> replicas = topology.getReplicas();
        for (int i = 0; i < replicas.size(); i++) {
            putRole(roles, replicas.get(i).getSo(), "replica");
        }
        return roles;
    }

    private static void putRole(
            final Map<String, String> roles, final Endpoint endpoint, final String role) {
        if (endpoint != null) {
            roles.put(endpoint.getId(), role);
        }
    }

    /**
     * The SQL READ/WRITE classifier bound to this connection's configuration.
     *
     * @return the SQL classifier for this connection
     */
    public SqlClassifier sqlClassifier() {
        return sqlClassifier;
    }

    /**
     * Open a load-balanced connection from a URI {@code loadbalance://} URL. The role topology /
     * weights are already parsed into the in-memory settings ({@link LoadBalanceSettings#fromUrl}).
     *
     * @param url the original user-written URL, used for logging and error messages
     * @param info the connection properties passed to {@code DriverManager}
     * @param config the LB settings parsed from {@code url}
     * @return the load-balanced connection
     * @throws SQLException if the session cannot be bound to an endpoint
     */
    public static Connection openFromSettings(
            final String url, final Properties info, final LoadBalanceSettings config)
            throws SQLException {
        return openFromSettings(url, info, config, SessionPhysicalConnManager.DRIVER_MANAGER);
    }

    static Connection openFromSettings(
            final String url,
            final Properties info,
            final LoadBalanceSettings config,
            final JdbcConnectionFactory connectionFactory)
            throws SQLException {
        if (url == null) {
            throw LbExceptions.internalState("missing connection URL");
        }
        if (config == null) {
            throw LbExceptions.internalState("missing load balance configuration");
        }

        LoadBalanceConnection connection = new LoadBalanceConnection(url, info, config);
        // After the constructor, because that is where file logging is installed — declaring the
        // configuration before the handler exists would send it to the console only. Writes nothing
        // after the first connection of a given URL.
        LbTopologyLog.declare(url, info, config);
        LbDistLog.configure(config);
        // Pool-scoped selector state keyed by the logical URL (query stripped), so distinct pools
        // stay isolated and connections to the same URL share one ratio state.
        connection.setSharedSelectorState(
                SharedSelectorStateRegistry.getOrCreate(sharedStateKeyForUrl(url)));
        // Pool-shared recovery-probe backoff, same key scope: single-prober election and
        // markReachable propagation only work if all sessions of the pool share one schedule.
        RecoveryBackoff backoff =
                RecoveryBackoffRegistry.getOrCreate(
                        sharedStateKeyForUrl(url), config.getRecoveryProbeIntervalMs());
        connection.setConnectionManager(
                new SessionPhysicalConnManager(
                        url, LbProps.copy(info), config, connectionFactory, backoff));
        if (connection.isSessionDistributionMode()) {
            EndpointTopology topology = config.buildEndpointTopology();
            if (topology.hasRwEndpoint()) {
                // initSessionBindings opens physical connections and then applies session state
                // (isolation/schema/...). If a post-bind step throws, the bound physical
                // connections
                // would leak, because this half-built LoadBalanceConnection is never returned and
                // the caller never close()s it. Close it here before propagating.
                try {
                    connection.initSessionBindings(topology);
                } catch (SQLException e) {
                    try {
                        connection.close();
                    } catch (SQLException ignored) {
                        // best-effort cleanup; surface the original failure
                    }
                    throw e;
                }
            }
        }

        return connection;
    }

    private static String sharedStateKeyForUrl(final String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    public LoadBalanceSettings getLbConfig() {
        return config;
    }

    public SessionRoutingState getSessionState() {
        return sessionState;
    }

    public CurrentReadEndpointHolder getBoundReadEndpoint() {
        return boundReadEndpoint;
    }

    public void setSharedSelectorState(SharedSelectorState shared) {
        this.selState = shared;
    }

    public SharedSelectorState getSharedSelectorState() {
        return selState;
    }

    public void setPhysicalResourceReleaser(final PhysicalResourceReleaser lifecycle) {
        if (lifecycle == null) {
            throw new IllegalArgumentException("PhysicalResourceReleaser must not be null");
        }
        this.physicalResourceReleaser = lifecycle;
    }

    public void setConnectionManager(final EndpointConnManager connectionManager) {
        if (connectionManager == null) {
            throw new IllegalArgumentException("EndpointConnManager must not be null");
        }
        this.connMgr = connectionManager;
        setPhysicalResourceReleaser(connectionManager);
        // The manager logs bind/failback/probe records but has no identity of its own; give it this
        // session's so its lines carry the same conn# column as the connection's own.
        connectionManager.setLogContext(LbLog.conn(connectionId));
        connectionManager.setSessionStateApplier(
                new EndpointConnManager.SessionStateApplier() {
                    public void applyTo(final Connection physical) throws SQLException {
                        applySessionStateTo(physical);
                    }
                });
    }

    public EndpointConnManager getConnectionManager() {
        return connMgr;
    }

    private EndpointConnManager requireConnMgr() throws SQLException {
        if (connMgr == null) {
            throw LbExceptions.internalState("connection manager is not initialized");
        }

        return connMgr;
    }

    public long getConnectionId() {
        return connectionId;
    }

    public RuntimeMetrics getRuntimeMetrics() {
        return routingMetrics.runtimeMetrics();
    }

    /**
     * Records the wall-clock latency of a just-completed physical execution against the endpoint it
     * ran on ({@link #lastExecEndpoint}, set at prepare/resolve time). Read vs write is taken from
     * the route target. No-op unless metrics recording is enabled. Called by {@link
     * ExecuteFailoverHandler}.
     *
     * @param target the route target the execution ran under
     * @param nanos the measured execution latency in nanoseconds
     */
    public void recordExecLatency(final Router.RouteTarget target, final long nanos) {
        RuntimeMetrics metrics = routingMetrics.runtimeMetrics();
        if (!metrics.isEnabled()) {
            return;
        }
        Endpoint ep = lastExecEndpoint;
        if (ep == null) {
            return;
        }
        metrics.recordLatency(ep.getId(), target == Router.RouteTarget.TO_READ_WRITE, nanos);
    }

    public ExecuteFailoverHandler getExecuteFailoverHandler() {
        return executeFailoverHandler;
    }

    public Endpoint endpointForTarget(final Router.RouteTarget target) throws SQLException {
        return selectionForTarget(target).getEndpoint();
    }

    /**
     * The endpoint to recover after a failed execution. Prefers the endpoint actually selected for
     * the most recent physical prepare/statement (captured in {@link #lastExecEndpoint}), falling
     * back to the routing preview {@link #endpointForTarget} when no execution endpoint was
     * captured.
     *
     * @param target the route target used for the preview fallback
     * @return the endpoint to recover
     * @throws SQLException if the fallback route cannot be resolved
     */
    public Endpoint resolveFailedExecEndpoint(final Router.RouteTarget target) throws SQLException {
        Endpoint captured = lastExecEndpoint;
        if (captured != null) {
            return captured;
        }
        return endpointForTarget(target);
    }

    public PhysicalRecoveryContext buildRecoveryCtx(
            final Router.RouteTarget target, final Endpoint failedEp) throws SQLException {
        if (failedEp == null) {
            throw new IllegalArgumentException("failedEp must not be null");
        }

        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }

        EndpointTopology topology = sessionTopology;
        if (topology == null) {
            topology = config.buildEndpointTopology();
        }

        SessionLeg failedLeg = SessionLeg.fromRouteTarget(target);

        return new PhysicalRecoveryContext(
                failedLeg,
                failedEp,
                target,
                topology,
                // ctx.excludeEndpointIds carries only caller-specific hard excludes (none here).
                // The JCI unreachable set must NOT be merged in as a hard exclude: recoverRw and
                // recoverRo already fold it in softly via softExcludedIds(), and hard-excluding it
                // here would undo the backoff-bypass probe.
                Collections.<String>emptySet(),
                sessionState.isTransactionActive());
    }

    /**
     * Resolves the endpoint one statement must run on, and records the routing decision.
     *
     * <p>Aligns both legs first (read restore, then RW failback) so the selection sees the current
     * binding, selects for the target, then emits the metrics and log records. The
     * PreparedStatement and Statement providers share this: they must route identically, and a
     * divergence here would show up only as skewed distribution.
     *
     * @param target the route target for this statement
     * @param rawSql the SQL as the caller wrote it, for the routing records
     * @return the endpoint the statement must run on
     * @throws SQLException if aligning the legs or selecting an endpoint fails
     */
    private Endpoint routeStatement(final Router.RouteTarget target, final String rawSql)
            throws SQLException {
        maybeRestoreRoBinding(target);
        failbackRwIfStatementAllows(target);
        EndpointSelection endpointSelection = selectionForTarget(target);

        Endpoint endpoint = endpointSelection.getEndpoint();
        lastExecEndpoint = endpoint;
        routingMetrics.recordStatementRoute(
                rawSql, target, endpoint, endpointSelection.getFallbackReason());
        routingMetrics.logRoute(rawSql, target, endpoint, endpointSelection.getFallbackReason());
        // Rides here rather than inside logRoute: that record is FINE and this one is INFO, so
        // gating it on the routing record's level would silence it at the default level.
        LbDistLog.onStatementRouted();
        return endpoint;
    }

    public LBPreparedStatement.PhysicalPsProvider createPsProvider() {
        return createPsProvider(Statement.NO_GENERATED_KEYS);
    }

    public LBPreparedStatement.PhysicalPsProvider createPsProvider(final int autoGeneratedKeys) {
        // Unique owner id per logical PreparedStatement (one provider per PS), so the manager
        // caches
        // this PS's physical statements under its own key and close() releases only them. The
        // generated-keys flag is captured here and carried to every physical prepare, so
        // RETURN_GENERATED_KEYS is honored.
        final String ownerId = "ps-" + PS_OWNER_SEQ.incrementAndGet();
        return new LBPreparedStatement.PhysicalPsProvider() {
            public PreparedStatement getPreparedStatement(
                    final Router.RouteTarget target, final String rawSql) throws SQLException {
                Endpoint endpoint = routeStatement(target, rawSql);
                return requireConnMgr()
                        .prepareStatement(endpoint, rawSql, ownerId, autoGeneratedKeys);
            }

            public void prepareOnBoundReadEndpoint(final String rawSql) throws SQLException {
                if (!sessionInitialized || currentRoEndpoint == null) {
                    return;
                }

                // Align the read binding BEFORE the eager prepare: if a displaced session fails
                // back between prepare time and execute time, the eager prepare lands on the old
                // endpoint and execute re-prepares on the new one - the same SQL prepared on two
                // brokers. Probing first (a throttled no-op when not displaced) keeps it to one.
                maybeRestoreRoBinding(Router.RouteTarget.TO_READ_ONLY);

                // READ: prepare only the bound read endpoint. There is no eager RW leg;
                // WRITE/UNKNOWN still prepare RW via prepareOnWriteEndpoint. A roOnRw / RW-only
                // session has currentRoEndpoint == the RW endpoint, so reads still prepare on RW.
                requireConnMgr()
                        .prepareStatement(currentRoEndpoint, rawSql, ownerId, autoGeneratedKeys);
            }

            public void prepareOnWriteEndpoint(final String rawSql) throws SQLException {
                if (!sessionInitialized || currentRwEndpoint == null) {
                    return;
                }
                requireConnMgr()
                        .prepareStatement(currentRwEndpoint, rawSql, ownerId, autoGeneratedKeys);
            }

            public void closePrepStmts() throws SQLException {
                if (connMgr == null) {
                    return;
                }
                // Logical PS close: release only this PS's physical statements, not the whole
                // session cache (which would kill other open PS/ResultSets).
                connMgr.closePrepForOwner(ownerId);
            }
        };
    }

    public LBStatement.PhysicalStmtProvider createStatementProvider(
            final int resultSetType,
            final int resultSetConcurrency,
            final int resultSetHoldability) {
        return new LBStatement.PhysicalStmtProvider() {
            public Statement getStatement(final Router.RouteTarget target, final String rawSql)
                    throws SQLException {
                Endpoint endpoint = routeStatement(target, rawSql);
                Connection physical = requireConnMgr().getPhyConn(endpoint);

                try {
                    return physical.createStatement(
                            resultSetType, resultSetConcurrency, resultSetHoldability);
                } catch (SQLException ex) {
                    // The base driver refuses an unsupported (type/concurrency, holdability)
                    // combination with SQLFeatureNotSupportedException, the JDBC type that means
                    // "this driver does not implement this"; only then degrade to the 2-arg form
                    // (connection default holdability). Any other SQLException (a broker being
                    // down, say) is the real cause and must not be masked by a second
                    // createStatement call that would surface a different, misleading error.
                    if (!(ex instanceof SQLFeatureNotSupportedException)) {
                        throw ex;
                    }
                    return physical.createStatement(resultSetType, resultSetConcurrency);
                }
            }
        };
    }

    /**
     * The endpoint a read would run on right now: the bound read endpoint, or the <b>RW</b>
     * endpoint when the session is transaction-pinned ({@code shouldRouteToRw()}). Binds the
     * session first if it is not bound yet.
     *
     * <p>Despite the name, this does not select an RO endpoint: the weighted read choice is made
     * once at binding time by {@link #initSessionBindings}, and a pinned session legitimately
     * answers with the write endpoint. It reads the same state the statement path uses, which is
     * what makes it useful to a test.
     *
     * <p>Package-private on purpose: a test observation point, not part of the connection's API.
     * Production read routing goes through the statement providers and {@code selectionForTarget}.
     *
     * @param topology the topology to bind with, when the session is not bound yet
     * @return the endpoint a read would be routed to
     * @throws SQLException if the connection is closed or the session cannot be bound
     */
    Endpoint resolveReadEndpoint(final EndpointTopology topology) throws SQLException {
        checkClosed();
        if (!sessionInitialized) {
            initSessionBindings(topology);
        }

        if (sessionState.shouldRouteToRw()) {
            return currentRwEndpoint;
        }

        return boundReadEndpoint.getCurrentRoEndpoint();
    }

    public void initSessionBindings(final EndpointTopology topology) throws SQLException {
        checkClosed();
        if (topology == null) {
            throw LbExceptions.internalState(
                    "missing endpoint topology for session initialization");
        }

        if (!topology.hasRwEndpoint()) {
            throw LbExceptions.topologyInvalid("Session distribution requires an RW endpoint");
        }

        EndpointConnManager mgr = requireConnMgr();

        if (config.isUrlDerived()) {
            bindFromUrlTopology(mgr, topology);
        } else {
            bindFromExplicitTopology(mgr, topology);
        }

        boundReadEndpoint.setCurrentRoEndpoint(currentRoEndpoint);
        sessionTopology = topology;
        sessionInitialized = true;
        mgr.applyPhyAutoCommit(sessionState.isAutoCommit());
        if (tryMaterializeBothLegs()) {
            applyTxnIsolation().rethrow();
            applySchema().rethrow();
            applyLockTimeout().rethrow();
            applyCasChangeMode().rethrow();
            applyHoldability().rethrow();
        }
    }

    /**
     * Role-based session binding for a URI {@code loadbalance://} URL:
     *
     * <ol>
     *   <li>RW is fixed to the master (node1) — all writes go there, never distributed;
     *   <li>when no read role exists (single host, no replica) the session is permanent RW-only;
     *       otherwise the read target is chosen once by {@code readWeight};
     *   <li>the read physical connection is bound via {@link
     *       EndpointConnManager#bindSessionWithReadTarget}: a master read reuses the RW connection
     *       (roOnRw), a slave/replica read opens a separate RO/SO connection.
     * </ol>
     */
    private void bindFromUrlTopology(final EndpointConnManager mgr, final EndpointTopology topology)
            throws SQLException {
        ResolvedRoleTopology resolved = config.getResolvedTopology();
        Endpoint masterRw = resolved.getMaster().getRw();

        // A rebind re-runs selection, so give back the slot the previous bind took first, or this
        // one session would count twice against the pool's live population.
        releaseHomeRole();

        boolean hasReadRole = !resolved.getSlaves().isEmpty() || !resolved.getReplicas().isEmpty();
        if (!hasReadRole) {
            // RO-unset: permanent RW-only (one physical connection serves read and write).
            mgr.bindSessionWithReadTarget(masterRw, masterRw, true, topology);
            currentRwEndpoint = mgr.getSessionEndpoint(SessionLeg.RW);
            if (currentRwEndpoint == null) {
                currentRwEndpoint = masterRw;
            }
            currentRoEndpoint = currentRwEndpoint;
            return;
        }

        RoleReadSelector.ReadTarget readTarget =
                roleReadSelector.select(resolved, config.getReadWeight(), selState);
        Endpoint readEndpoint = readTarget.getEndpoint();
        // select() already took the slot; hold the role so close() (or the next rebind) returns it.
        // Publishing it under recoveryLock - the lock releaseHomeRole() takes - and re-reading
        // `closed` inside makes taking the slot and owning it one step as far as close() can tell.
        // Without the re-read, a close() landing between select() and this assignment finds
        // homeReadRole still null, releases nothing, and never runs again because of its own
        // `closed` guard, so the slot stays taken for the pool's life and that role is
        // under-selected forever.
        final boolean abandoned;
        synchronized (recoveryLock) {
            abandoned = closed;
            if (!abandoned) {
                homeReadRole = readTarget.getRole();
            }
        }
        if (abandoned) {
            RoleReadSelector.release(readTarget.getRole(), selState);
            throw LbExceptions.connectionClosed();
        }

        boolean bound = false;
        try {
            mgr.bindSessionWithReadTarget(masterRw, readEndpoint, readTarget.reusesRw(), topology);
            bound = true;
        } finally {
            // A session that never came up holds no home: leaving the slot taken would shrink the
            // role's live share for the rest of the pool's life, exactly the drift this balancing
            // exists to prevent.
            if (!bound) {
                releaseHomeRole();
            }
        }
        currentRwEndpoint = mgr.getSessionEndpoint(SessionLeg.RW);
        currentRoEndpoint = mgr.getSessionEndpoint(SessionLeg.RO);
        if (currentRwEndpoint == null) {
            currentRwEndpoint = masterRw;
        }
        if (currentRoEndpoint == null) {
            currentRoEndpoint = readEndpoint;
        }
    }

    /**
     * Returns this session's weighted-home slot to the pool-shared selector state, so the next
     * connection the pool creates can be assigned to the role this one vacated. Idempotent: the
     * role is cleared, so a close after a rebind (or a double close) releases nothing twice.
     */
    private void releaseHomeRole() {
        final NodeRole held;
        // Take-and-clear under the lock: close() (possibly a pool-eviction thread) and a rebind can
        // race, and two releases of one slot would leave the role permanently over-selected.
        synchronized (recoveryLock) {
            held = homeReadRole;
            homeReadRole = null;
        }
        if (held == null) {
            return;
        }
        RoleReadSelector.release(held, selState);
    }

    /**
     * Topology-driven session binding for a non-URL config (test/in-memory scenarios): RW is the
     * topology's RW endpoint, and the read binds to the first RO endpoint, or RW-only when none.
     * There is no selection algorithm — the role-based readWeight distribution is the URL path
     * ({@link #bindFromUrlTopology}).
     */
    private void bindFromExplicitTopology(
            final EndpointConnManager mgr, final EndpointTopology topology) throws SQLException {
        Endpoint rwEndpoint = topology.getRwEndpoints().get(0);

        if (topology.hasRoEndpoints()) {
            Endpoint roEndpoint = topology.getRoEndpoints().get(0);
            mgr.bindSession(rwEndpoint, roEndpoint, topology);
            currentRwEndpoint = mgr.getSessionEndpoint(SessionLeg.RW);
            currentRoEndpoint = mgr.getSessionEndpoint(SessionLeg.RO);

            if (currentRwEndpoint == null) {
                currentRwEndpoint = rwEndpoint;
            }

            if (currentRoEndpoint == null) {
                currentRoEndpoint = roEndpoint;
            }
        } else {
            // No RO broker configured: permanent RW-only. Reads and writes both run on RW.
            mgr.bindSessionRwOnly(rwEndpoint, topology);
            currentRwEndpoint = mgr.getSessionEndpoint(SessionLeg.RW);

            if (currentRwEndpoint == null) {
                currentRwEndpoint = rwEndpoint;
            }

            currentRoEndpoint = currentRwEndpoint;
        }
    }

    public Endpoint getCurrentRwEndpoint() {
        return currentRwEndpoint;
    }

    public Endpoint getCurrentRoEndpoint() {
        return currentRoEndpoint;
    }

    public EndpointTopology getSessionTopology() {
        return sessionTopology;
    }

    public PhysicalRecoveryResult recoverPhyBinding(
            final PhysicalRecoveryContext ctx, final SQLException originalEx) throws SQLException {
        checkClosed();
        if (ctx == null) {
            throw new IllegalArgumentException("PhysicalRecoveryContext must not be null");
        }

        if (originalEx == null) {
            throw new IllegalArgumentException("originalEx must not be null");
        }

        if (!config.isRuntimeFailoverEnabled()) {
            throw originalEx;
        }

        synchronized (recoveryLock) {
            if (ctx.isTxActive() || sessionState.isTransactionActive()) {
                throw originalEx;
            }

            EndpointConnManager mgr = requireConnMgr();
            PhysicalRecoveryResult result;

            switch (ctx.getFailedLeg()) {
                case RW:
                    result = mgr.recoverRw(ctx);
                    currentRwEndpoint = result.getBoundEndpoint();
                    break;
                case RO:
                    result = mgr.recoverRo(ctx);
                    applyRecoveredEndpoint(result);
                    break;
                default:
                    throw originalEx;
            }

            logFailover(ctx, result, originalEx);

            // The prior physical connection (and its DatabaseMetaData) is gone; drop the cached
            // metadata so the next getMetaData() rebinds to the recovered connection.
            invalidateMetaDataCache();

            routingMetrics.recordFailoverRecovery(ctx, result);
            return result;
        }
    }

    /**
     * Records the failover cause and the newly-bound endpoint, so a RW rebind onto a non-master
     * node is directly observable rather than silent (RW failback returns it to the master once
     * that recovers).
     *
     * <p>Landing on a <em>different</em> node is a degraded state — the write leg is off the
     * master, or the read leg has left its weighted home — and stays at WARNING. Reconnecting to
     * the <em>same</em> node restores exactly the previous placement, so it is INFO: it belongs in
     * the record of what happened, but it is not a condition anyone has to act on.
     *
     * <p>Collapsed per transition ({@code role|from|to}): the record is written per connection, so
     * one broker outage otherwise writes one identical line per pooled session. See {@link
     * LbLogDedup}.
     */
    private void logFailover(
            final PhysicalRecoveryContext ctx,
            final PhysicalRecoveryResult result,
            final SQLException cause) {
        Endpoint failed = ctx.getFailedEndpoint();
        Endpoint bound = result.getBoundEndpoint();
        String failedId = failed == null ? "?" : failed.getId();
        String boundId = bound == null ? "?" : bound.getId();
        boolean movedNode = !boundId.equals(failedId);

        String message =
                "LB FAILOVER ["
                        + ctx.getFailedLeg()
                        + "] failed="
                        + failedId
                        + " -> new="
                        + boundId
                        + (movedNode
                                ? "  <== REBOUND to a DIFFERENT node"
                                : "  (reconnected to the same node)")
                        + " | cause: "
                        + LbLog.cause(cause);

        if (movedNode) {
            LbLogDedup.warn(
                    LOGGER,
                    LbLog.conn(connectionId),
                    "FAILOVER|" + ctx.getFailedLeg() + "|" + failedId + "|" + boundId,
                    message);
        } else {
            LbLog.info(LOGGER, LbLog.conn(connectionId), message);
        }
    }

    private void applyRecoveredEndpoint(final PhysicalRecoveryResult result) {
        if (result.getBoundLeg() == SessionLeg.RO) {
            currentRoEndpoint = result.getBoundEndpoint();
            boundReadEndpoint.setCurrentRoEndpoint(currentRoEndpoint);
        } else if (result.getBoundLeg() == SessionLeg.RW) {
            currentRwEndpoint = result.getBoundEndpoint();
        }
    }

    public Endpoint getCurrentEp(final SessionLeg leg) {
        if (leg == null) {
            throw new IllegalArgumentException("SessionLeg must not be null");
        }

        switch (leg) {
            case RW:
                return currentRwEndpoint;
            case RO:
                return currentRoEndpoint;
            default:
                throw new IllegalStateException("Unsupported session leg: " + leg);
        }
    }

    public boolean isSessionInitialized() {
        return sessionInitialized;
    }

    public boolean isSessionDistributionMode() {
        return LoadBalanceSettings.DISTRIBUTION_MODE_SESSION.equals(config.getDistributionMode());
    }

    /**
     * Runs a write-endpoint command through the failover handler.
     *
     * <p>{@link #getPhysicalConnForCmd} resolves a leg but never recovers one, so commands that go
     * straight to it - {@code prepareCall}, the LOB trio, {@code createBlob}/{@code createClob} -
     * failed outright when the write broker was down, even though the session could have rebound to
     * a surviving one. Routing them here rebinds the leg on a broker failure.
     *
     * <p>It does not re-run the command. The handler never retries {@code TO_READ_WRITE}, because
     * the server may already have applied the work before the connection dropped, and a {@code
     * CALL} is not idempotent. The leg is recovered and the original failure is reported, leaving
     * the retry decision to the caller.
     *
     * @param label the command name, for the routing log
     * @param command what to run against the write leg
     * @param <T> the command's result type
     * @return whatever the command returned
     * @throws SQLException if the command failed, after the leg was recovered
     */
    private <T> T runRwCommand(
            final String label, final ExecuteFailoverHandler.SqlExecution<T> command)
            throws SQLException {
        T result =
                getExecuteFailoverHandler()
                        .executeCommandWithFailover(
                                this, Router.RouteTarget.TO_READ_WRITE, label, false, command);
        recordRwCmd(label);

        return result;
    }

    Connection getPhysicalConnForCmd(final SessionLeg leg) throws SQLException {
        checkClosed();
        if (!sessionInitialized) {
            throw LbExceptions.sessionNotInitialized(null);
        }

        Endpoint endpoint = getCurrentEp(leg);
        if (endpoint == null) {
            throw LbExceptions.sessionNotInitialized("role=" + leg);
        }

        return requireConnMgr().getPhyConn(endpoint);
    }

    /* ===== Connection: transaction API =====*/
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkClosed();
        if (!sessionState.isAutoCommit() && autoCommit && sessionState.isTransactionActive()) {
            // JDBC: switching to autocommit during a live transaction commits it. Reflect the
            // commit in logical state as soon as the physical commit succeeds, before propagating
            // the autocommit mode below: if that propagation throws, the session must not be left
            // manual+txActive over an already-committed transaction (a later rollback() would then
            // appear to undo committed work).
            propagatePhyCommit();
            sessionState.setAutoCommit(true);
            propagatePhyAutoCommit(true);
            logTxBoundary("autoCommit=true during a live transaction (implicit commit)");
            return;
        }
        propagatePhyAutoCommit(autoCommit);
        sessionState.setAutoCommit(autoCommit);
        logTxBoundary("autoCommit=" + autoCommit);
    }

    public boolean getAutoCommit() throws SQLException {
        checkClosed();

        return sessionState.isAutoCommit();
    }

    public void commit() throws SQLException {
        checkClosed();
        if (sessionState.isAutoCommit()) {
            throw LbExceptions.txAutocommitOnly();
        }
        propagatePhyCommit();
        sessionState.onTransactionBoundary();
        logTxBoundary("commit");
        // Transaction boundary: nothing is in flight, so this is the safe point to move a displaced
        // RW leg back to the master. Manual-commit sessions re-arm transactionActive here, so this
        // hook — not the statement path — is what covers them (a pooled connection is reset with
        // rollback() on return, which lands here too).
        attemptRwFailback();
    }

    public void rollback() throws SQLException {
        checkClosed();
        if (sessionState.isAutoCommit()) {
            throw LbExceptions.txAutocommitOnly();
        }
        propagatePhyRollback();
        sessionState.onTransactionBoundary();
        logTxBoundary("rollback");
        attemptRwFailback(); // same transaction boundary as commit(); see the comment there
    }

    /**
     * Records a transaction-boundary event together with the routing state it leaves behind.
     *
     * <p>The pin is the point. A manual-commit session re-arms {@code transactionActive} on every
     * commit, because CUBRID implicitly begins the next transaction, so it stays pinned to RW and
     * every later SELECT goes to the write node. That is correct, and it is also the most common
     * reason a read is seen on the master. With the per-statement routing records, {@code
     * txPinnedToRw=true} is what answers "why did this SELECT go to RW".
     */
    private void logTxBoundary(final String event) {
        LbLog.fine(
                LOGGER,
                LbLog.conn(connectionId),
                "LB TX: "
                        + event
                        + " | autoCommit="
                        + sessionState.isAutoCommit()
                        + " txPinnedToRw="
                        + sessionState.shouldRouteToRw());
    }

    /**
     * Makes sure both legs have a live physical connection, <b>opening one where it is missing</b>,
     * and reports whether that succeeded. Not a pure query: {@code getPhyConn} materializes a bound
     * endpoint that has no socket yet ({@code ensureConn}), which is the point - a session property
     * can only be pushed onto a connection that exists.
     *
     * <p>False means do not try: the session is unbound, an endpoint is missing, or a leg could not
     * be opened. The failure is swallowed rather than propagated, because binding has already
     * succeeded and {@link #applySessionStateTo} re-applies the properties when the manager next
     * opens a leg.
     *
     * @return whether both legs now have a usable physical connection
     */
    private boolean tryMaterializeBothLegs() {
        if (!isSessionInitialized()) {
            return false;
        }

        try {
            if (currentRwEndpoint == null || currentRoEndpoint == null) {
                return false;
            }

            EndpointConnManager mgr = requireConnMgr();
            mgr.getPhyConn(currentRwEndpoint);
            mgr.getPhyConn(currentRoEndpoint);

            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    private void propagatePhyAutoCommit(final boolean autoCommit) throws SQLException {
        if (!isSessionInitialized()) {
            return;
        }
        requireConnMgr().applyPhyAutoCommit(autoCommit);
    }

    private void propagatePhyCommit() throws SQLException {
        if (!isSessionInitialized()) {
            return;
        }
        requireConnMgr().commitPhyTx();
    }

    private void propagatePhyRollback() throws SQLException {
        if (!isSessionInitialized()) {
            return;
        }
        requireConnMgr().rollbackPhyTx();
    }

    public void close() throws SQLException {
        if (closed) {
            return;
        }
        // Publish closed before releasing, so a concurrent user thread's checkClosed() sees it and
        // does not start new work against half-released state. abort() delegates here.
        closed = true;
        // Logged before the release work and with the endpoints still readable: a pool discarding
        // healthy connections shows up as many very short lifetimes on particular endpoints, and
        // both facts are gone once the bindings are cleared below.
        LbLog.fine(
                LOGGER,
                LbLog.conn(connectionId),
                "LB CONN: closed after "
                        + (System.currentTimeMillis() - createdAtMs)
                        + "ms"
                        + " | rw="
                        + (currentRwEndpoint == null ? "?" : currentRwEndpoint.getId())
                        + " read="
                        + (currentRoEndpoint == null ? "?" : currentRoEndpoint.getId()));
        // Before any physical release can throw: a leaked home slot silently biases every future
        // selection for this pool, and the `closed` guard means this runs exactly once.
        releaseHomeRole();
        if (metricsRegistered) {
            // Fold this connection's final counts into the registry's cumulative tally so a
            // maxLifetime eviction does not lose them.
            MetricsRegistry.unregister(routingMetrics.runtimeMetrics());
            metricsRegistered = false;
        }
        closeOpenStatements();
        // Before the legs go: an OUT result set still holds a socket statement on one of them.
        closeOutResultSets();
        releasePreparedStatements();
        releasePhysicalConnections();
        sessionState.reset();
        boundReadEndpoint.clear();
        // Endpoint/init fields are otherwise mutated under recoveryLock (recoverPhyBinding); guard
        // them here too so the lock's invariant holds.
        synchronized (recoveryLock) {
            currentRwEndpoint = null;
            currentRoEndpoint = null;
            sessionTopology = null;
            sessionInitialized = false;
        }
    }

    public boolean isClosed() throws SQLException {
        return closed;
    }

    private void closeOpenStatements() {
        // Snapshot-and-clear under the list monitor so a concurrent trackStatement() (app thread)
        // cannot corrupt the list or trigger ConcurrentModificationException while close()/abort()
        // (possibly another thread) drains it. Close outside the lock: each stmt.close() calls back
        // into untrackStatement(), and the list is already cleared so that remove is a no-op.
        List<Statement> snapshot;
        synchronized (openStatements) {
            snapshot = new ArrayList<Statement>(openStatements);
            openStatements.clear();
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            try {
                Statement stmt = snapshot.get(i);
                if (!stmt.isClosed()) {
                    stmt.close();
                }
            } catch (SQLException ignored) {
            }
        }
    }

    private void releasePreparedStatements() {
        physicalResourceReleaser.releasePreparedStatements();
    }

    private void releasePhysicalConnections() {
        physicalResourceReleaser.releasePhysicalConnections();
    }

    public void trackStatement(Statement stmt) {
        synchronized (openStatements) {
            openStatements.add(stmt);
        }
    }

    /**
     * Removes a statement from the open-statement set on its close() so long-lived connections do
     * not accumulate closed statement references (and their SQL/parameter state) until connection
     * close. No-op if already untracked.
     *
     * @param stmt the statement to untrack
     */
    public void untrackStatement(Statement stmt) {
        synchronized (openStatements) {
            openStatements.remove(stmt);
        }
    }

    public int getOpenStatementCount() {
        synchronized (openStatements) {
            return openStatements.size();
        }
    }

    public Statement createStatement() throws SQLException {
        checkClosed();

        LBStatement stmt = new LBStatement(this);
        trackStatement(stmt);

        return stmt;
    }

    public Statement createStatement(int resultSetType, int resultSetConcurrency)
            throws SQLException {
        checkClosed();

        LBStatement stmt = new LBStatement(this, resultSetType, resultSetConcurrency);
        trackStatement(stmt);

        return stmt;
    }

    public Statement createStatement(
            int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        checkClosed();

        LBStatement stmt =
                new LBStatement(this, resultSetType, resultSetConcurrency, resultSetHoldability);
        trackStatement(stmt);

        return stmt;
    }

    /**
     * Shared tail for the {@code prepareStatement} overloads: injects the physical-statement
     * provider, primes the session prepare, and tracks the statement for close-time cleanup.
     */
    private PreparedStatement newPs(
            final LBPreparedStatement ps, final LBPreparedStatement.PhysicalPsProvider provider)
            throws SQLException {
        ps.setPsProvider(provider);
        ps.prepareEagerly();
        trackStatement(ps);

        return ps;
    }

    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkClosed();

        return newPs(new LBPreparedStatement(this, sql), createPsProvider());
    }

    public PreparedStatement prepareStatement(
            String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkClosed();

        return newPs(
                new LBPreparedStatement(this, sql, resultSetType, resultSetConcurrency),
                createPsProvider());
    }

    public PreparedStatement prepareStatement(
            String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        checkClosed();

        return newPs(
                new LBPreparedStatement(
                        this, sql, resultSetType, resultSetConcurrency, resultSetHoldability),
                createPsProvider());
    }

    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
            throws SQLException {
        checkClosed();

        return newPs(new LBPreparedStatement(this, sql), createPsProvider(autoGeneratedKeys));
    }

    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        checkClosed();

        return newPs(new LBPreparedStatement(this, sql), createPsProvider());
    }

    public PreparedStatement prepareStatement(String sql, String[] columnNames)
            throws SQLException {
        checkClosed();

        return newPs(new LBPreparedStatement(this, sql), createPsProvider());
    }

    /* ===== Connection: client info API (unsupported, as in the core driver) =====*/
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        SQLClientInfoException clientEx = new SQLClientInfoException();
        clientEx.initCause(new java.lang.UnsupportedOperationException());
        throw clientEx;
    }

    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        SQLClientInfoException clientEx = new SQLClientInfoException();
        clientEx.initCause(new java.lang.UnsupportedOperationException());
        throw clientEx;
    }

    public String getClientInfo(String name) throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    public Properties getClientInfo() throws SQLException {
        throw new SQLException(new java.lang.UnsupportedOperationException());
    }

    /* ===== Connection: basic property API =====*/
    public void setTransactionIsolation(int level) throws SQLException {
        checkClosed();
        final int previous = this.transactionIsolation;
        this.transactionIsolation = level;
        LegOutcome outcome = applyTxnIsolation();
        if (outcome.rwFailed()) {
            this.transactionIsolation = previous;
        }
        outcome.rethrow();
        if (isSessionInitialized()) {
            recordRwCmd("setTransactionIsolation");
        }
    }

    public int getTransactionIsolation() throws SQLException {
        checkClosed();
        if (isSessionInitialized()) {
            int delegated =
                    runRwCommand(
                                    "getTransactionIsolation",
                                    new ExecuteFailoverHandler.SqlExecution<Integer>() {
                                        public Integer run() throws SQLException {
                                            return Integer.valueOf(
                                                    getPhysicalConnForCmd(SessionLeg.RW)
                                                            .getTransactionIsolation());
                                        }
                                    })
                            .intValue();
            transactionIsolation = delegated;

            return delegated;
        }

        return transactionIsolation;
    }

    public void setLockTimeout(final int timeout) throws SQLException {
        checkClosed();
        final Integer previous = this.lockTimeout;
        this.lockTimeout = Integer.valueOf(timeout);
        LegOutcome outcome = applyLockTimeout();
        if (outcome.rwFailed()) {
            this.lockTimeout = previous;
        }
        outcome.rethrow();
        if (!isSessionInitialized()) {
            return;
        }
        recordRwCmd("setLockTimeout");
    }

    /**
     * Sets the CAS change mode on both session legs. Applications typically call this on every pool
     * checkout, so the read leg is attempted even when the write leg failed - the read leg must not
     * be collateral damage of a write-leg failure (the same reason as {@link #forEachSessPhyConn},
     * which this mirrors; the RW leg's return value is why it is written out rather than
     * delegated). The logical value is kept only when the write leg took it, so a failed call is
     * not replayed onto connections opened later by {@link #applySessionStateTo}; a read-leg-only
     * failure keeps it, so the value is restored on the next read leg the session binds.
     *
     * <p>Only the write leg's previous mode is returned: there is one return slot for two legs, and
     * the write leg is the authoritative one.
     */
    public int setCASChangeMode(final int mode) throws SQLException {
        checkClosed();
        if (!isSessionInitialized()) {
            this.casChangeMode = Integer.valueOf(mode);
            return 0;
        }

        Connection rwPhysical = null;
        SQLException rwFailure = null;
        int previousMode = 0;
        try {
            rwPhysical = getPhysicalConnForCmd(SessionLeg.RW);
            previousMode = extConn(rwPhysical, "setCASChangeMode").setCASChangeMode(mode);
        } catch (SQLException failure) {
            rwFailure = failure;
        }

        SQLException roFailure = null;
        try {
            Connection roPhysical = getPhysicalConnForCmd(SessionLeg.RO);
            if (rwPhysical != roPhysical) {
                extConn(roPhysical, "setCASChangeMode").setCASChangeMode(mode);
            }
        } catch (SQLException failure) {
            roFailure = failure;
            if (rwFailure != null) {
                LbLog.warn(
                        LOGGER,
                        LbLog.conn(connectionId),
                        "LB SESSION PROP [RO]: setCASChangeMode failed on the read leg while the"
                                + " write leg was already failing; reporting the write failure |"
                                + " cause: "
                                + LbLog.cause(failure));
            }
        }

        if (rwFailure == null) {
            this.casChangeMode = Integer.valueOf(mode);
        } else {
            throw rwFailure;
        }
        if (roFailure != null) {
            throw roFailure;
        }

        recordRwCmd("setCASChangeMode");
        return previousMode;
    }

    /**
     * {@code CUBRIDConnection} declares this without {@code SQLException}, so the override cannot
     * widen the clause and a physical failure surfaces unchecked. The message and cause survive on
     * the wrapped exception.
     */
    @Override
    public void setCharset(final String charsetName) throws UnsupportedEncodingException {
        try {
            applyCharset(charsetName);
        } catch (SQLException wrapped) {
            throw unchecked(wrapped);
        }
    }

    private void applyCharset(final String charsetName)
            throws SQLException, UnsupportedEncodingException {
        checkClosed();
        // Remembered before any leg is touched, so a call made before the session bound anything is
        // deferred rather than silently dropped: applySessionStateTo replays it when a leg opens.
        String previous = charset;
        charset = charsetName;
        if (!isSessionInitialized()) {
            return;
        }

        Connection rwPhysical = getPhysicalConnForCmd(SessionLeg.RW);
        try {
            extConn(rwPhysical, "setCharset").setCharset(charsetName);
        } catch (SQLException failure) {
            charset = previous;
            throw failure;
        } catch (UnsupportedEncodingException failure) {
            charset = previous;
            throw failure;
        }

        Connection roPhysical = getPhysicalConnForCmd(SessionLeg.RO);
        if (rwPhysical != roPhysical) {
            extConn(roPhysical, "setCharset").setCharset(charsetName);
        }
        recordRwCmd("setCharset");
    }

    public CUBRIDConnectionKey Login(final String signedData) throws SQLException {
        checkClosed();
        // Refuse rather than return null. The key is the whole point of the call, so a null answer
        // surfaces later as a NullPointerException in the caller, where nothing says which call
        // failed. Unlike setCharset this cannot be deferred: the key comes from the server.
        if (!isSessionInitialized()) {
            throw LbExceptions.physicalNotBound(
                    "Login(String) requires a bound session; execute a statement first");
        }

        Connection rwPhysical = getPhysicalConnForCmd(SessionLeg.RW);

        CUBRIDConnectionKey result = extConn(rwPhysical, "Login(String)").Login(signedData);

        Connection roPhysical = getPhysicalConnForCmd(SessionLeg.RO);
        if (rwPhysical != roPhysical) {
            extConn(roPhysical, "Login(String)").Login(signedData);
        }
        recordRwCmd("Login(String)");
        return result;
    }

    public CUBRIDConnectionKey Login(final byte[] signedData) throws SQLException {
        checkClosed();
        // Refuse rather than return null. The key is the whole point of the call, so a null answer
        // surfaces later as a NullPointerException in the caller, where nothing says which call
        // failed. Unlike setCharset this cannot be deferred: the key comes from the server.
        if (!isSessionInitialized()) {
            throw LbExceptions.physicalNotBound(
                    "Login(byte[]) requires a bound session; execute a statement first");
        }

        Connection rwPhysical = getPhysicalConnForCmd(SessionLeg.RW);

        CUBRIDConnectionKey result = extConn(rwPhysical, "Login(byte[])").Login(signedData);

        Connection roPhysical = getPhysicalConnForCmd(SessionLeg.RO);
        if (rwPhysical != roPhysical) {
            extConn(roPhysical, "Login(byte[])").Login(signedData);
        }
        recordRwCmd("Login(byte[])");
        return result;
    }

    @Override
    public void Logout() {
        try {
            logoutOnLegs();
        } catch (SQLException wrapped) {
            throw unchecked(wrapped);
        }
    }

    private void logoutOnLegs() throws SQLException {
        checkClosed();
        if (!isSessionInitialized()) {
            return;
        }
        forEachSessPhyConn(
                        new PhyConnAction() {
                            public void apply(Connection physical) throws SQLException {
                                extConn(physical, "Logout").Logout();
                            }
                        })
                .rethrow();
        recordRwCmd("Logout");
    }

    @Override
    public void SetSignedConnection() {
        try {
            setSignedConnectionOnLegs();
        } catch (SQLException wrapped) {
            throw unchecked(wrapped);
        }
    }

    private void setSignedConnectionOnLegs() throws SQLException {
        checkClosed();
        if (!isSessionInitialized()) {
            return;
        }
        forEachSessPhyConn(
                        new PhyConnAction() {
                            public void apply(Connection physical) throws SQLException {
                                extConn(physical, "SetSignedConnection").SetSignedConnection();
                            }
                        })
                .rethrow();
        recordRwCmd("SetSignedConnection");
    }

    public void setReadOnly(final boolean readOnly) throws SQLException {
        checkClosed();
        final boolean previous = this.readOnly;
        this.readOnly = readOnly;
        if (!isSessionInitialized()) {
            return;
        }
        LegOutcome outcome =
                forEachSessPhyConn(
                        new PhyConnAction() {
                            public void apply(Connection physical) throws SQLException {
                                physical.setReadOnly(readOnly);
                            }
                        });
        if (outcome.rwFailed()) {
            this.readOnly = previous;
        }
        outcome.rethrow();
        recordRwCmd("setReadOnly");
    }

    public boolean isReadOnly() throws SQLException {
        checkClosed();
        if (isSessionInitialized()) {
            boolean delegated =
                    runRwCommand(
                                    "isReadOnly",
                                    new ExecuteFailoverHandler.SqlExecution<Boolean>() {
                                        public Boolean run() throws SQLException {
                                            return Boolean.valueOf(
                                                    getPhysicalConnForCmd(SessionLeg.RW)
                                                            .isReadOnly());
                                        }
                                    })
                            .booleanValue();
            readOnly = delegated;

            return delegated;
        }

        return readOnly;
    }

    public void setCatalog(final String catalog) throws SQLException {
        checkClosed();
        final String previous = this.catalog;
        this.catalog = catalog;
        if (!isSessionInitialized()) {
            return;
        }
        LegOutcome outcome =
                forEachSessPhyConn(
                        new PhyConnAction() {
                            public void apply(Connection physical) throws SQLException {
                                physical.setCatalog(catalog);
                            }
                        });
        if (outcome.rwFailed()) {
            this.catalog = previous;
        }
        outcome.rethrow();
        recordRwCmd("setCatalog");
    }

    public String getCatalog() throws SQLException {
        checkClosed();
        if (isSessionInitialized()) {
            String delegated =
                    runRwCommand(
                            "getCatalog",
                            new ExecuteFailoverHandler.SqlExecution<String>() {
                                public String run() throws SQLException {
                                    return getPhysicalConnForCmd(SessionLeg.RW).getCatalog();
                                }
                            });
            catalog = delegated;

            return delegated;
        }

        return catalog;
    }

    public void setSchema(String schema) throws SQLException {
        checkClosed();
        final String previous = this.schema;
        this.schema = schema;
        LegOutcome outcome = applySchema();
        if (outcome.rwFailed()) {
            this.schema = previous;
        }
        outcome.rethrow();
        if (isSessionInitialized()) {
            recordRwCmd("setSchema");
        }
    }

    public String getSchema() throws SQLException {
        checkClosed();
        if (isSessionInitialized()) {
            String delegated =
                    runRwCommand(
                            "getSchema",
                            new ExecuteFailoverHandler.SqlExecution<String>() {
                                public String run() throws SQLException {
                                    return getPhysicalConnForCmd(SessionLeg.RW).getSchema();
                                }
                            });
            schema = delegated;

            return delegated;
        }

        return schema;
    }

    public int getHoldability() throws SQLException {
        checkClosed();
        if (isSessionInitialized()) {
            int delegated =
                    runRwCommand(
                                    "getHoldability",
                                    new ExecuteFailoverHandler.SqlExecution<Integer>() {
                                        public Integer run() throws SQLException {
                                            return Integer.valueOf(
                                                    getPhysicalConnForCmd(SessionLeg.RW)
                                                            .getHoldability());
                                        }
                                    })
                            .intValue();
            holdability = delegated;

            return delegated;
        }

        return holdability;
    }

    public void setHoldability(int holdability) throws SQLException {
        checkClosed();
        final int previous = this.holdability;
        this.holdability = holdability;
        LegOutcome outcome = applyHoldability();
        if (outcome.rwFailed()) {
            this.holdability = previous;
        }
        outcome.rethrow();
        if (isSessionInitialized()) {
            recordRwCmd("setHoldability");
        }
    }

    public SQLWarning getWarnings() throws SQLException {
        checkClosed();
        if (isSessionInitialized()) {
            return runRwCommand(
                    "getWarnings",
                    new ExecuteFailoverHandler.SqlExecution<SQLWarning>() {
                        public SQLWarning run() throws SQLException {
                            return getPhysicalConnForCmd(SessionLeg.RW).getWarnings();
                        }
                    });
        }

        return null;
    }

    public void clearWarnings() throws SQLException {
        checkClosed();
        if (isSessionInitialized()) {
            runRwCommand(
                    "clearWarnings",
                    new ExecuteFailoverHandler.SqlExecution<SQLWarning>() {
                        public SQLWarning run() throws SQLException {
                            getPhysicalConnForCmd(SessionLeg.RW).clearWarnings();
                            return null;
                        }
                    });
        }
    }

    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkClosed();

        throw LbExceptions.notSupported();
    }

    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        checkClosed();

        throw LbExceptions.notSupported();
    }

    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        checkClosed();
        final int previous = networkTimeout;
        networkTimeout = milliseconds;
        LegOutcome outcome = applyNetTimeout();
        if (outcome.rwFailed()) {
            networkTimeout = previous;
        }
        outcome.rethrow();
        if (isSessionInitialized()) {
            recordRwCmd("setNetworkTimeout");
        }
    }

    public int getNetworkTimeout() throws SQLException {
        checkClosed();
        if (isSessionInitialized()) {
            int delegated =
                    runRwCommand(
                                    "getNetworkTimeout",
                                    new ExecuteFailoverHandler.SqlExecution<Integer>() {
                                        public Integer run() throws SQLException {
                                            return Integer.valueOf(
                                                    getPhysicalConnForCmd(SessionLeg.RW)
                                                            .getNetworkTimeout());
                                        }
                                    })
                            .intValue();
            networkTimeout = delegated;

            return delegated;
        }

        return networkTimeout;
    }

    /**
     * Connection-level {@code DatabaseMetaData} is fixed to the RW endpoint (via {@link
     * #rwMetaData()}). This is deliberate and distinct from the spec decision that
     * <em>statement-level</em> {@code ResultSetMetaData} is served from the bound read endpoint: a
     * single logical connection exposes one stable metadata view, and pinning it to RW avoids it
     * shifting as the read binding fails over between replicas.
     */
    public DatabaseMetaData getMetaData() throws SQLException {
        checkClosed();

        return new LBDatabaseMetaData(this);
    }

    public String nativeSQL(String sql) throws SQLException {
        checkClosed();

        return sql;
    }

    /* ===== Connection: unsupported deferred API =====*/
    /**
     * A {@code CALL} runs on the write endpoint, and LB does not wrap the statement it returns: the
     * physical {@code CallableStatement} is handed out as is, so the OUT-parameter API and the
     * vendor casts on it keep working.
     *
     * <p>What is fixed up is the owner it reports. Left alone, {@code cs.getConnection()} is the
     * physical leg, and an application walking back through it could commit or close that leg while
     * the session knows nothing. The statement is stamped to report this session instead.
     *
     * <p>Execution still happens outside {@link ExecuteFailoverHandler}, because it happens on the
     * returned object; only the prepare is covered. That is deliberate: a stored procedure is not
     * idempotent, so LB must not replay a {@code CALL} it saw fail.
     */
    public CallableStatement prepareCall(final String sql) throws SQLException {
        checkClosed();

        return reportSelfAsOwner(
                runRwCommand(
                        "prepareCall",
                        new ExecuteFailoverHandler.SqlExecution<CallableStatement>() {
                            public CallableStatement run() throws SQLException {
                                return getPhysicalConnForCmd(SessionLeg.RW).prepareCall(sql);
                            }
                        }));
    }

    /**
     * Name this session as the owner of a physical statement handed out unwrapped, so {@code
     * getConnection()} on it cannot be used to change one leg behind the session's back.
     *
     * @param statement the physical statement about to cross to the application
     * @param <T> the statement type
     * @return the same statement, for use in a return statement
     */
    private <T extends Statement> T reportSelfAsOwner(final T statement) {
        if (statement instanceof CUBRIDStatement) {
            ((CUBRIDStatement) statement).setReportedConnection(this);
        }

        return statement;
    }

    public CallableStatement prepareCall(
            final String sql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        checkClosed();

        return reportSelfAsOwner(
                runRwCommand(
                        "prepareCall(resultSetType,resultSetConcurrency)",
                        new ExecuteFailoverHandler.SqlExecution<CallableStatement>() {
                            public CallableStatement run() throws SQLException {
                                return getPhysicalConnForCmd(SessionLeg.RW)
                                        .prepareCall(sql, resultSetType, resultSetConcurrency);
                            }
                        }));
    }

    public CallableStatement prepareCall(
            final String sql,
            final int resultSetType,
            final int resultSetConcurrency,
            final int resultSetHoldability)
            throws SQLException {
        checkClosed();

        return reportSelfAsOwner(
                runRwCommand(
                        "prepareCall(resultSetType,resultSetConcurrency,resultSetHoldability)",
                        new ExecuteFailoverHandler.SqlExecution<CallableStatement>() {
                            public CallableStatement run() throws SQLException {
                                return getPhysicalConnForCmd(SessionLeg.RW)
                                        .prepareCall(
                                                sql,
                                                resultSetType,
                                                resultSetConcurrency,
                                                resultSetHoldability);
                            }
                        }));
    }

    public Savepoint setSavepoint() throws SQLException {
        checkClosed();

        Savepoint result = getPhysicalConnForCmd(SessionLeg.RW).setSavepoint();
        recordRwCmd("setSavepoint");
        return result;
    }

    public Savepoint setSavepoint(String name) throws SQLException {
        checkClosed();

        Savepoint result = getPhysicalConnForCmd(SessionLeg.RW).setSavepoint(name);
        recordRwCmd("setSavepoint(name)");
        return result;
    }

    public void rollback(Savepoint savepoint) throws SQLException {
        checkClosed();
        getPhysicalConnForCmd(SessionLeg.RW).rollback(savepoint);
        recordRwCmd("rollback(savepoint)");
    }

    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        checkClosed();
        getPhysicalConnForCmd(SessionLeg.RW).releaseSavepoint(savepoint);
        recordRwCmd("releaseSavepoint");
    }

    public Clob createClob() throws SQLException {
        checkClosed();

        return runRwCommand(
                "createClob",
                new ExecuteFailoverHandler.SqlExecution<Clob>() {
                    public Clob run() throws SQLException {
                        return getPhysicalConnForCmd(SessionLeg.RW).createClob();
                    }
                });
    }

    public Blob createBlob() throws SQLException {
        checkClosed();

        return runRwCommand(
                "createBlob",
                new ExecuteFailoverHandler.SqlExecution<Blob>() {
                    public Blob run() throws SQLException {
                        return getPhysicalConnForCmd(SessionLeg.RW).createBlob();
                    }
                });
    }

    public byte[] lobNew(final int lobType) throws SQLException {
        checkClosed();

        return runRwCommand(
                "lobNew",
                new ExecuteFailoverHandler.SqlExecution<byte[]>() {
                    public byte[] run() throws SQLException {
                        return extConn(getPhysicalConnForCmd(SessionLeg.RW), "lobNew")
                                .lobNew(lobType);
                    }
                });
    }

    public int lobWrite(
            final byte[] packedLobHandle,
            final long offset,
            final byte[] buf,
            final int start,
            final int len)
            throws SQLException {
        checkClosed();

        return runRwCommand(
                        "lobWrite",
                        new ExecuteFailoverHandler.SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                return Integer.valueOf(
                                        extConn(getPhysicalConnForCmd(SessionLeg.RW), "lobWrite")
                                                .lobWrite(
                                                        packedLobHandle, offset, buf, start, len));
                            }
                        })
                .intValue();
    }

    public int lobRead(
            final byte[] packedLobHandle,
            final long offset,
            final byte[] buf,
            final int start,
            final int len)
            throws SQLException {
        checkClosed();

        return runRwCommand(
                        "lobRead",
                        new ExecuteFailoverHandler.SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                return Integer.valueOf(
                                        extConn(getPhysicalConnForCmd(SessionLeg.RW), "lobRead")
                                                .lobRead(packedLobHandle, offset, buf, start, len));
                            }
                        })
                .intValue();
    }

    public NClob createNClob() throws SQLException {
        checkClosed();

        return runRwCommand(
                "createNClob",
                new ExecuteFailoverHandler.SqlExecution<NClob>() {
                    public NClob run() throws SQLException {
                        return getPhysicalConnForCmd(SessionLeg.RW).createNClob();
                    }
                });
    }

    public SQLXML createSQLXML() throws SQLException {
        checkClosed();

        return runRwCommand(
                "createSQLXML",
                new ExecuteFailoverHandler.SqlExecution<SQLXML>() {
                    public SQLXML run() throws SQLException {
                        return getPhysicalConnForCmd(SessionLeg.RW).createSQLXML();
                    }
                });
    }

    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw LbExceptions.invalidValue("isValid timeout must not be negative");
        }
        if (closed) {
            return false;
        }

        // Pool health probe. The logical connection is valid as long as its mandatory RW physical
        // connection is still an open socket; RO/SO are optional (reads fall back to RW and the RO
        // binding self-heals lazily), so a down RO must not make the pool discard this connection.
        //
        // A down RW broker does NOT make this false: the probe is structural, so an unreachable
        // broker still leaves an open socket and the session stays in the pool. The vendor
        // isValid()
        // answers CAS ownership rather than usability, and trusting it discarded whole pools of
        // healthy connections after an idle spell (see isBoundRwSocketOpen). Recovery is the
        // failover machinery's job. False is reserved for a session that is genuinely unusable: its
        // write socket was closed behind LB's back.
        //
        if (connMgr != null) {
            // Opt-in (write.failback.on.validate): the pool probes idle and borrowed connections,
            // so this is a chance for a displaced RW leg to self-correct without waiting for
            // traffic. Gated on no active transaction, because the application may also call
            // isValid() mid-transaction.
            if (config.isWriteFailbackOnValidate() && !sessionState.isTransactionActive()) {
                attemptRwFailback();
            }
            return connMgr.isBoundRwSocketOpen();
        }

        return true;
    }

    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        checkClosed();

        throw LbExceptions.notSupported();
    }

    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        checkClosed();

        throw LbExceptions.notSupported();
    }

    /* ===== CUBRIDConnection API with no single-leg answer =====*/
    /**
     * The write leg's physical {@code UConnection}. {@code CUBRIDConnection} exposes one socket and
     * this session has two, so the write leg is the answer: it is the leg that exists for every
     * session, and it is the one an OID or a driver-internal caller means by "this connection".
     */
    public UConnection getUConnection() throws SQLException {
        checkClosed();
        return extConn(getPhysicalConnForCmd(SessionLeg.RW), "getUConnection").getUConnection();
    }

    @Override
    public void setAutoGeneratedKeys(final boolean isGeneratedKeys) {
        try {
            applyAutoGeneratedKeys(isGeneratedKeys);
        } catch (SQLException wrapped) {
            throw unchecked(wrapped);
        }
    }

    private void applyAutoGeneratedKeys(final boolean isGeneratedKeys) throws SQLException {
        checkClosed();
        forEachSessPhyConn(
                        new PhyConnAction() {
                            public void apply(Connection physical) throws SQLException {
                                extConn(physical, "setAutoGeneratedKeys")
                                        .setAutoGeneratedKeys(isGeneratedKeys);
                            }
                        })
                .rethrow();
    }

    /* ===== inherited from CUBRIDConnection: nothing may be left inherited =====*/
    /**
     * This class extends {@code CUBRIDConnection} so an application that casts to the driver's own
     * connection class keeps working on a {@code loadbalance://} URL:
     *
     * <pre>
     *   CUBRIDConnection c = (CUBRIDConnection) conn;
     *   c.setLockTimeout(1000);
     * </pre>
     *
     * <p>The inherited {@code u_con} is null: this session owns a write leg and a read leg and
     * rebinds them on failover, so there is no single socket to name. Every inherited member must
     * therefore be overridden. One left inherited either dereferences that null socket ({@code
     * addStatement} reads {@code u_con.getQueryTimeout()}, {@code prepare} enters {@code
     * synchronized (u_con)}) or acts on inherited state this class does not maintain - {@code
     * is_closed} stays false forever, because closing is tracked by {@code closed}, so an inherited
     * {@code checkIsOpen()} would pass on a closed connection. {@code
     * LoadBalanceConnectionCastTest} fails the build on a missing override.
     *
     * <p>These particular members are driver-internal plumbing: {@code CUBRIDStatement} and {@code
     * CUBRIDResultSet} call them on the physical connection that created them, never on this one,
     * so refusing is the correct answer. {@code finalize()} is the exception - the garbage
     * collector calls it, so it must not throw.
     */
    /**
     * {@code CUBRIDConnection.toString()} reports the CAS address by dereferencing {@code u_con}
     * four times, so leaving it inherited is a guaranteed NullPointerException - and {@code
     * toString()} is reached by loggers, debuggers and IDE inspection rather than by a deliberate
     * call. This reports the two legs instead, and never throws.
     */
    @Override
    public String toString() {
        Endpoint rw = currentRwEndpoint;
        Endpoint ro = boundReadEndpoint == null ? null : boundReadEndpoint.getCurrentRoEndpoint();

        return getClass().getName()
                + "(id="
                + connectionId
                + ", rw="
                + (rw == null ? "unbound" : rw.getId())
                + ", read="
                + (ro == null ? "unbound" : ro.getId())
                + ")";
    }

    @Override
    protected void finalize() {
        try {
            close();
        } catch (Exception ignored) {
            // best effort, as in CUBRIDConnection.finalize()
        }
    }

    @Override
    protected void clear() throws SQLException {
        throw LbExceptions.notSupportedApi("clear()");
    }

    @Override
    protected void autoCommit() throws SQLException {
        throw LbExceptions.notSupportedApi("autoCommit()");
    }

    @Override
    protected void autoRollback() throws SQLException {
        throw LbExceptions.notSupportedApi("autoRollback()");
    }

    @Override
    protected void addStatement(Statement s) throws SQLException {
        throw LbExceptions.notSupportedApi("addStatement()");
    }

    @Override
    protected PreparedStatement prepare(
            String sql,
            int resultSetType,
            int resultSetConcurrency,
            int resultSetHoldability,
            int autoGeneratedKeys)
            throws SQLException {
        throw LbExceptions.notSupportedApi("prepare()");
    }

    /**
     * Wraps a checked failure for an override whose inherited clause has no {@code SQLException}.
     */
    private static RuntimeException unchecked(final SQLException cause) {
        return new IllegalStateException(cause.getMessage(), cause);
    }

    private static String userOf(final LoadBalanceSettings config) {
        requireConfig(config);
        return config.getUrlUser();
    }

    private static void requireConfig(final LoadBalanceSettings config) {
        if (config == null) {
            throw new IllegalArgumentException("LoadBalanceSettings must not be null");
        }
    }

    /* ===== CUBRID SHARD / driver-internal API: not supported =====*/
    /**
     * CUBRID SHARD APIs and the driver-internal OUT-ResultSet hook, refused explicitly. A
     * load-balance session spans several brokers of one HA cluster and has no shard behind it, so
     * there is no shard id, shard metadata or per-shard connection to report; {@code
     * addOutResultSet} is plumbing that {@link CUBRIDOutResultSet} only calls on the physical
     * connection it was created from. They are declared rather than absent, so a caster gets a
     * named "not supported" error instead of a {@code NoSuchMethodError}.
     *
     * <p>The refusals come in two forms, and the difference is not a choice: an override cannot
     * widen the inherited {@code throws} clause. {@code CUBRIDConnection} declares {@code
     * getShardMetaData()} with {@code SQLException} and {@code isShard()} / {@code getShardId()} /
     * {@code addOutResultSet()} without it, so only the first can refuse with a checked exception.
     * The rest go through {@link #unchecked(SQLException)}, which keeps the original as the cause.
     * The rule is the same for all four: refuse with the widest form the inherited signature
     * allows. Do not unify them by making {@code getShardMetaData()} unchecked; that would take
     * away the one refusal a caller can still catch as {@code SQLException}.
     */
    @Override
    public boolean isShard() {
        throw unchecked(LbExceptions.notSupportedApi("isShard()"));
    }

    @Override
    public int getShardId() {
        throw unchecked(LbExceptions.notSupportedApi("getShardId()"));
    }

    @Override
    public CUBRIDShardMetaData getShardMetaData() throws SQLException {
        throw LbExceptions.notSupportedApi("getShardMetaData()");
    }

    /**
     * Registers a stored-procedure OUT result set for close-time cleanup.
     *
     * <p>This used to refuse, because {@link CUBRIDOutResultSet} only called it on the physical
     * connection that produced it. That stopped holding once this session claimed value-object
     * ownership of its legs (see {@code claimValueObjectOwnership}): the OUT result set now
     * registers here, so refusing would break every stored procedure that returns one. It is
     * tracked and closed with the connection, as {@code CUBRIDConnection} does with its own list.
     *
     * @param rs the OUT result set to track
     */
    @Override
    public void addOutResultSet(CUBRIDOutResultSet rs) {
        if (rs == null) {
            return;
        }
        synchronized (openOutResultSets) {
            openOutResultSets.add(rs);
        }
    }

    /**
     * Close and forget every tracked OUT result set. Snapshot-and-clear under the list monitor for
     * the same reason {@code closeOpenStatements} does it: a close may run on another thread than
     * the one still registering.
     */
    private void closeOutResultSets() {
        List<CUBRIDOutResultSet> snapshot;
        synchronized (openOutResultSets) {
            snapshot = new ArrayList<CUBRIDOutResultSet>(openOutResultSets);
            openOutResultSets.clear();
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            try {
                snapshot.get(i).close();
            } catch (SQLException ignored) {
                // best-effort: the connection is closing either way
            }
        }
    }

    public void abort(Executor executor) throws SQLException {
        // abort() may be invoked from a thread other than the one using the connection. close()
        // publishes closed=true (volatile) before best-effort release, so in-flight callers observe
        // the close and stop issuing new work.
        close();
    }

    /* ===== Wrapper =====*/
    public <T> T unwrap(Class<T> iface) throws SQLException {
        checkClosed();
        if (iface == null) {
            throw LbExceptions.notSupported();
        }
        if (iface.isAssignableFrom(getClass())) {
            return iface.cast(this);
        }

        throw LbExceptions.notSupported();
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        checkClosed();
        if (iface == null) {
            return false;
        }

        return iface.isAssignableFrom(getClass());
    }

    /* ===== Internal =====*/
    private void checkClosed() throws SQLException {
        if (closed) {
            throw LbExceptions.connectionClosed();
        }
    }

    /**
     * Serve one {@link LBDatabaseMetaData} call: resolve the current write endpoint's metadata and
     * record the routing decision. Exists so the metadata subclass needs a single entry point here
     * instead of widening {@code recordRwCmd}.
     *
     * @param commandName the metadata call being served
     * @return the physical metadata of the current write endpoint
     * @throws SQLException if the write endpoint cannot be bound
     */
    /**
     * Runs one {@link LBDatabaseMetaData} call that reaches the server through the failover
     * handler.
     *
     * <p>Only the calls that actually send a request need this - {@code getTables}, {@code
     * getColumns}, the key/index/privilege queries and {@code getDatabaseProductVersion}. The rest
     * of the 173-method surface answers from local constants, so a broker being down cannot affect
     * them. The closure resolves the metadata handle <b>and</b> runs the query, because a rebind
     * must not leave the query on the handle of the failed endpoint (LB-Pending-Issues ISSUE-5).
     *
     * <p>Not timed and not replayed: it is a command, not a statement, and the handler never
     * replays a write target. The leg is rebound, so the caller's retry lands on the new one.
     *
     * @param <T> the query's result type
     * @param label the metadata call name, for logging
     * @param query resolve-and-run, as one unit
     * @return the query's result
     * @throws SQLException if the query fails after the leg was recovered
     */
    <T> T runRwMetaQuery(final String label, final ExecuteFailoverHandler.SqlExecution<T> query)
            throws SQLException {
        return getExecuteFailoverHandler()
                .executeCommandWithFailover(
                        this, Router.RouteTarget.TO_READ_WRITE, label, true, query);
    }

    DatabaseMetaData rwMetaDataRecordingCall(final String commandName) throws SQLException {
        DatabaseMetaData delegate = rwMetaData();
        recordRwCmd("Call: " + commandName);

        return delegate;
    }

    /**
     * The write leg's physical metadata, cached per bound connection.
     *
     * <p>Acquiring it goes through {@link #runRwCommand}: the cache is dropped whenever failover or
     * failback replaces the write connection, so the next call re-opens the leg, and an unprotected
     * open against a stopped broker failed outright instead of rebinding.
     *
     * <p>This protects <b>obtaining</b> the handle, not the catalog query the caller then runs on
     * it: {@link LBDatabaseMetaData} forwards each of its methods separately, so a query that dies
     * mid-call still propagates. Closing that gap means routing every forwarded metadata method
     * through the handler - tracked as a separate item in LB-Pending-Issues.
     */
    DatabaseMetaData rwMetaData() throws SQLException {
        if (rwMetaForCmd != null) {
            return rwMetaForCmd;
        }

        rwMetaForCmd =
                runRwCommand(
                        "getMetaData",
                        new ExecuteFailoverHandler.SqlExecution<DatabaseMetaData>() {
                            public DatabaseMetaData run() throws SQLException {
                                return getPhysicalConnForCmd(SessionLeg.RW).getMetaData();
                            }
                        });

        return rwMetaForCmd;
    }

    /**
     * Drops cached DatabaseMetaData bound to a physical connection replaced by failover/restore.
     */
    private void invalidateMetaDataCache() {
        rwMetaForCmd = null;
    }

    /**
     * The physical connection viewed as the CUBRID extension API. A leg is held as a plain {@link
     * Connection} because that is what the connection manager hands out, but every connection this
     * driver opens is a {@link CUBRIDConnection}, which is where the extension methods live. The
     * cast replaces the reflective lookup that was needed when core and LB shipped as separate
     * JARs; a delegate of another type fails with the same {@code physicalDelegateFailed}
     * SQLException the reflective {@code NoSuchMethodException} path produced. {@code label} names
     * the call in error messages (may differ from the method name for overloads, e.g.
     * "Login(String)").
     */
    private static CUBRIDConnection extConn(final Connection physicalConnection, final String label)
            throws SQLException {
        if (physicalConnection instanceof CUBRIDConnection) {
            return (CUBRIDConnection) physicalConnection;
        }

        throw LbExceptions.physicalDelegateFailed(
                label,
                new ClassCastException(
                        physicalConnection.getClass().getName() + " is not a CUBRIDConnection"));
    }

    /** A session-property mutation applied to one physical connection. */
    private interface PhyConnAction {
        void apply(Connection physical) throws SQLException;
    }

    /**
     * Outcome of applying a session property to the two legs: which leg failed, if any. Returned
     * instead of thrown so a caller holding logical session state can settle that state before the
     * failure propagates — a property that never reached the write leg must not stay in the logical
     * state, or {@link #applySessionStateTo} would replay it onto every connection opened later.
     */
    private static final class LegOutcome {
        private static final LegOutcome OK = new LegOutcome(null, null);

        private final SQLException rwFailure;
        private final SQLException roFailure;

        private LegOutcome(final SQLException rwFailure, final SQLException roFailure) {
            this.rwFailure = rwFailure;
            this.roFailure = roFailure;
        }

        /** True when the write leg did not take the value, so logical state must not keep it. */
        boolean rwFailed() {
            return rwFailure != null;
        }

        /** Reports the write-leg failure first; a read-leg-only failure otherwise. */
        void rethrow() throws SQLException {
            if (rwFailure != null) {
                throw rwFailure;
            }
            if (roFailure != null) {
                throw roFailure;
            }
        }
    }

    /**
     * Applies {@code action} to the RW physical connection and, when the RO endpoint is bound to a
     * distinct physical connection, to the RO connection too. Single home for the "apply to every
     * physical connection backing this session" pattern shared by the property setters and the
     * {@code apply*()} re-application helpers, so the reapply target list lives in one place.
     * Caller owns the {@link #isSessionInitialized()} guard and any command-routing metrics.
     *
     * <p>The read leg is attempted even when the write leg failed, and the failures are returned
     * rather than thrown. The RW leg used to run unguarded, so anything it threw skipped the read
     * leg entirely — the read leg was collateral damage of a write-leg failure even though reads
     * would have kept working (the execute path absorbs a dead read endpoint by failing over, so a
     * property setter must not be the one thing that makes the connection unusable). Same shape as
     * {@code SessionPhysicalConnManager.endPhyTx}. A read-leg failure masked by a write-leg failure
     * is logged rather than dropped (Java 1.6 target, so no {@code addSuppressed}).
     */
    /**
     * Applies a session property to the write leg, rebinding the leg when the broker is down.
     *
     * <p>Before this, a property setter was the one thing that could make a logical connection
     * unusable while a surviving write broker was right there: the failure was isolated per leg but
     * the leg was never recovered, so a pool resetting a returned connection ({@code
     * setAutoCommit}, {@code setReadOnly}, {@code setTransactionIsolation}) discarded it
     * (LB-Pending-Issues ISSUE-6).
     *
     * <p>Re-applying after the rebind is not a replayed write. The caller has already written the
     * intended value into the logical session state, so the connection the rebind opened carries it
     * ({@code applySessionStateTo}); setting a property is idempotent, and this second apply is
     * what confirms the new leg took it. When the rebind itself failed, or a transaction was active
     * (where failover is forbidden), the original failure is what the caller must see - a failure
     * from the second attempt is chained onto it rather than replacing it.
     *
     * @param action the property to apply
     * @return the write leg's physical connection the property was applied to
     * @throws SQLException if the property could not be applied, after the leg was recovered
     */
    private Connection applyToWriteLegWithFailover(final PhyConnAction action) throws SQLException {
        try {
            return getExecuteFailoverHandler()
                    .executeCommandWithFailover(
                            this,
                            Router.RouteTarget.TO_READ_WRITE,
                            "sessionProperty",
                            false,
                            new ExecuteFailoverHandler.SqlExecution<Connection>() {
                                public Connection run() throws SQLException {
                                    Connection rw = getPhysicalConnForCmd(SessionLeg.RW);
                                    action.apply(rw);

                                    return rw;
                                }
                            });
        } catch (SQLException failure) {
            if (sessionState.isTransactionActive()
                    || !(ReconnectPolicy.isRetriableSqlException(failure)
                            || UnreachableEndpoints.shouldMarkUnreachable(failure))) {
                throw failure; // not a broker failure, or failover was forbidden: nothing rebound
            }

            try {
                Connection rw = getPhysicalConnForCmd(SessionLeg.RW);
                action.apply(rw);

                return rw;
            } catch (SQLException afterRebind) {
                if (afterRebind != failure) {
                    failure.setNextException(afterRebind);
                }

                throw failure;
            }
        }
    }

    private LegOutcome forEachSessPhyConn(final PhyConnAction action) {
        Connection rwPhysical = null;
        SQLException rwFailure = null;
        try {
            rwPhysical = applyToWriteLegWithFailover(action);
        } catch (SQLException failure) {
            rwFailure = failure;
        }

        SQLException roFailure = null;
        try {
            Connection roPhysical = getPhysicalConnForCmd(SessionLeg.RO);
            if (rwPhysical != roPhysical) {
                action.apply(roPhysical);
            }
        } catch (SQLException failure) {
            roFailure = failure;
            if (rwFailure != null) {
                LbLog.warn(
                        LOGGER,
                        LbLog.conn(connectionId),
                        "LB SESSION PROP [RO]: read leg failed while the write leg was already"
                                + " failing; reporting the write failure | cause: "
                                + LbLog.cause(failure));
            }
        }

        if (rwFailure == null && roFailure == null) {
            return LegOutcome.OK;
        }

        return new LegOutcome(rwFailure, roFailure);
    }

    private LegOutcome applyCasChangeMode() {
        if (null == casChangeMode || !isSessionInitialized()) {
            return LegOutcome.OK;
        }
        return forEachSessPhyConn(
                new PhyConnAction() {
                    public void apply(Connection physical) throws SQLException {
                        extConn(physical, "setCASChangeMode")
                                .setCASChangeMode(casChangeMode.intValue());
                    }
                });
    }

    private LegOutcome applyTxnIsolation() {
        if (!isSessionInitialized()) {
            return LegOutcome.OK;
        }
        return forEachSessPhyConn(
                new PhyConnAction() {
                    public void apply(Connection physical) throws SQLException {
                        physical.setTransactionIsolation(transactionIsolation);
                    }
                });
    }

    private LegOutcome applySchema() {
        if (schema == null || !isSessionInitialized()) {
            return LegOutcome.OK;
        }
        return forEachSessPhyConn(
                new PhyConnAction() {
                    public void apply(Connection physical) throws SQLException {
                        physical.setSchema(schema);
                    }
                });
    }

    private LegOutcome applyNetTimeout() {
        if (!isSessionInitialized()) {
            return LegOutcome.OK;
        }
        return forEachSessPhyConn(
                new PhyConnAction() {
                    public void apply(Connection physical) throws SQLException {
                        physical.setNetworkTimeout(null, networkTimeout);
                    }
                });
    }

    private LegOutcome applyLockTimeout() {
        if (null == lockTimeout || !isSessionInitialized()) {
            return LegOutcome.OK;
        }
        return forEachSessPhyConn(
                new PhyConnAction() {
                    public void apply(Connection physical) throws SQLException {
                        extConn(physical, "setLockTimeout").setLockTimeout(lockTimeout.intValue());
                    }
                });
    }

    private LegOutcome applyHoldability() {
        if (!isSessionInitialized()) {
            return LegOutcome.OK;
        }
        return forEachSessPhyConn(
                new PhyConnAction() {
                    public void apply(Connection physical) throws SQLException {
                        physical.setHoldability(holdability);
                    }
                });
    }

    /**
     * Applies the current logical session state to a single freshly opened physical connection.
     * Invoked by {@link SessionPhysicalConnManager} for every connection it opens, so a connection
     * created mid-session (failover, recovery, failback, reopen) matches the session instead of
     * running at driver defaults. Mirrors the per-property setters used at initial binding;
     * nullable properties (schema/lockTimeout/casChangeMode) are applied only when set.
     */
    /**
     * Claim ownership of the value objects a leg produces.
     *
     * <p>{@code UConnection} carries a back-reference to "the {@code CUBRIDConnection} that
     * application-facing value objects belong to". The driver reads it in exactly eight places, all
     * of the same kind: constructing a {@code CUBRIDOID} from wire data, converting a BLOB/CLOB
     * handle, and registering a stored-procedure OUT result set. A physical connection sets it to
     * itself at construction, so on a load-balanced session every OID and LOB the application
     * receives reported a physical leg — and {@code oid.getConnection()} handed out a connection
     * the application could {@code commit()} or {@code close()} behind the session's back.
     *
     * <p>Re-pointing it at the logical connection is safe because a leg belongs to exactly one
     * logical connection: {@code SessionPhysicalConnManager} is created per connection and opens
     * its own sockets. It is also narrow: nothing else reads the reference — statement execution,
     * exception logging and transaction control all use their own references — so only ownership
     * reporting changes. The side benefit is that LOB reads and writes on those objects now go
     * through {@link #lobRead}/{@link #lobWrite}, which resolve the write leg per call and
     * therefore survive an RW rebind.
     *
     * @param physical the leg just opened for this session
     */
    private void claimValueObjectOwnership(final Connection physical) {
        try {
            UConnection socket = extConn(physical, "getUConnection").getUConnection();
            if (socket != null) {
                socket.setCUBRIDConnection(this);
            }
        } catch (SQLException notCubrid) {
            // A physical delegate that is not a CUBRID connection produces no CUBRID value objects,
            // so there is no ownership to claim. Test doubles land here.
            LbLog.fine(
                    LOGGER,
                    LbLog.conn(connectionId),
                    "LB VALUE OWNERSHIP: skipped, delegate is not a CUBRID connection");
        }
    }

    private void applySessionStateTo(final Connection physical) throws SQLException {
        claimValueObjectOwnership(physical);
        physical.setAutoCommit(sessionState.isAutoCommit());
        physical.setTransactionIsolation(transactionIsolation);
        if (schema != null) {
            physical.setSchema(schema);
        }
        if (lockTimeout != null) {
            extConn(physical, "setLockTimeout").setLockTimeout(lockTimeout.intValue());
        }
        if (casChangeMode != null) {
            extConn(physical, "setCASChangeMode").setCASChangeMode(casChangeMode.intValue());
        }
        if (charset != null) {
            try {
                extConn(physical, "setCharset").setCharset(charset);
            } catch (UnsupportedEncodingException rejected) {
                // The name was accepted by a leg once, so a later leg rejecting it is a
                // driver-level
                // disagreement, not caller error. Report it as a SQLException because this method
                // cannot widen its throws clause.
                throw LbExceptions.invalidValue(
                        "setCharset("
                                + charset
                                + ") was rejected when re-applied to a new leg: "
                                + rejected.getMessage());
            }
        }
        physical.setHoldability(holdability);
    }

    private EndpointSelection selectionForTarget(final Router.RouteTarget target)
            throws SQLException {
        if (target == Router.RouteTarget.TO_READ_WRITE) {
            return selectRwEndpoint();
        }
        if (target == Router.RouteTarget.TO_READ_ONLY) {
            return selectReadEndpoint();
        }

        throw LbExceptions.unsupportedRoute(String.valueOf(target));
    }

    /**
     * The session-bound endpoint for {@code leg}: this connection's current (possibly
     * failover-re-pinned) binding, falling back to the manager's session endpoint.
     */
    private Endpoint currentOrManagerEndpoint(final SessionLeg leg) throws SQLException {
        Endpoint current = getCurrentEp(leg);
        if (current != null) {
            return current;
        }

        return requireConnMgr().getSessionEndpoint(leg);
    }

    private EndpointSelection selectRwEndpoint() throws SQLException {
        Endpoint rwEndpoint = currentOrManagerEndpoint(SessionLeg.RW);
        if (rwEndpoint == null) {
            throw LbExceptions.sessionNotInitialized("role=RW");
        }

        return new EndpointSelection(rwEndpoint, FallbackReason.NONE);
    }

    private EndpointSelection selectReadEndpoint() throws SQLException {
        if (sessionState.shouldRouteToRw()) {
            return new EndpointSelection(
                    selectRwEndpoint().getEndpoint(), FallbackReason.TX_ACTIVE_RW_LOCK);
        }

        Endpoint roEndpoint = currentOrManagerEndpoint(SessionLeg.RO);
        if (roEndpoint == null) {
            throw LbExceptions.sessionNotInitialized("role=RO");
        }

        Endpoint rwEndpoint = currentOrManagerEndpoint(SessionLeg.RW);

        // Session mode binds the read target once at session init; a roOnRw / RW-only
        // session binds the RO role onto the RW endpoint, so just use the bound read endpoint.
        if (rwEndpoint != null && rwEndpoint.getId().equals(roEndpoint.getId())) {
            return new EndpointSelection(roEndpoint, FallbackReason.NONE);
        }

        return withPhysicalFallbackReason(roEndpoint);
    }

    private EndpointSelection withPhysicalFallbackReason(final Endpoint logicalRoEndpoint)
            throws SQLException {
        String physicalFallback = requireConnMgr().getRoFallbackReason();
        if (physicalFallback == null
                || physicalFallback.length() == 0
                || FallbackReason.NONE.equals(physicalFallback)) {
            return new EndpointSelection(logicalRoEndpoint, FallbackReason.NONE);
        }

        return new EndpointSelection(logicalRoEndpoint, physicalFallback);
    }

    /**
     * Lazy RO self-healing on the read path. When reads have fallen back to the RW physical
     * connection and the bound RO endpoint has recovered, reconnect to a real RO connection so
     * subsequent reads are routed back to RO. No-op unless this is an RO read served outside a
     * transaction (when {@code shouldRouteToRw()} is true reads go to RW regardless).
     */
    private void maybeRestoreRoBinding(final Router.RouteTarget target) {
        if (target != Router.RouteTarget.TO_READ_ONLY) {
            return;
        }
        if (!sessionInitialized || sessionState.shouldRouteToRw()) {
            return;
        }
        if (connMgr == null) {
            return;
        }
        if (!config.isReadFailbackEnabled()) {
            return;
        }

        // Throttle: a displaced session re-probes for a recovered home at most once per interval.
        // A session already at home returns inside restoreRoIfRecovered() without opening anything.
        long now = System.currentTimeMillis();
        if (now - lastReadFailbackAttemptMs < config.getReadFailbackProbeIntervalMs()) {
            return;
        }
        lastReadFailbackAttemptMs = now;

        try {
            Endpoint restoredRo = connMgr.restoreRoIfRecovered();
            if (restoredRo != null) {
                currentRoEndpoint = restoredRo;
                boundReadEndpoint.setCurrentRoEndpoint(restoredRo);
                invalidateMetaDataCache();
                routingMetrics.recordRoFailback(restoredRo);
            }
        } catch (SQLException ignored) {
            // RO still unavailable; keep serving reads on RW and retry on a later read.
        }
    }

    /**
     * RW failback trigger on the statement path. Fires for a write, and also for a read that is
     * served by the RW connection (roOnRw / RW-only / master-read sessions) — that read is on the
     * stranded node too, so it is worth correcting.
     *
     * <p>Only for a session with no transaction in progress. A manual-commit session is
     * transaction-pinned even between commits (see {@code
     * SessionRoutingState.onTransactionBoundary}), so for it this is always a no-op and the {@code
     * commit()}/{@code rollback()} hook does the work.
     */
    private void failbackRwIfStatementAllows(final Router.RouteTarget target) {
        if (target != Router.RouteTarget.TO_READ_WRITE && !readRidesOnRw()) {
            return;
        }
        if (sessionState.isTransactionActive()) {
            return;
        }
        attemptRwFailback();
    }

    /**
     * Whether reads are currently served by the RW physical connection: either a failed-over read
     * leg ({@code roOnRw}) or an RW-only / master-read session, where the read role is bound to the
     * RW endpoint itself.
     */
    private boolean readRidesOnRw() {
        if (connMgr == null) {
            return false;
        }
        if (connMgr.isReadOnRwConnection()) {
            return true;
        }
        return currentRoEndpoint != null && currentRoEndpoint.equals(currentRwEndpoint);
    }

    /**
     * Attempts RW failback at a caller-verified safe boundary: no statement in flight and no
     * transaction work since the last commit/rollback. Throttled per session; a session already on
     * the master returns inside {@code restoreRwIfRecovered()} without opening anything.
     */
    private void attemptRwFailback() {
        if (!sessionInitialized || connMgr == null) {
            return;
        }
        if (!config.isWriteFailbackEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastRwFailbackAttemptMs < config.getWriteFailbackProbeIntervalMs()) {
            return;
        }
        lastRwFailbackAttemptMs = now;

        try {
            Endpoint restoredRw = connMgr.restoreRwIfRecovered();
            if (restoredRw != null) {
                currentRwEndpoint = restoredRw;
                // A read riding on the RW connection moved with it; re-read the RO binding from the
                // manager so the routing/metrics view is not left pointing at the stranded node.
                Endpoint boundRo = connMgr.getSessionEndpoint(SessionLeg.RO);
                if (boundRo != null && !boundRo.equals(currentRoEndpoint)) {
                    currentRoEndpoint = boundRo;
                    boundReadEndpoint.setCurrentRoEndpoint(boundRo);
                }
                invalidateMetaDataCache();
                routingMetrics.recordRwFailback(restoredRw);
            }
        } catch (SQLException ignored) {
            // Master still unavailable; keep writing on the current RW and retry at a later
            // boundary.
        }
    }

    /**
     * Records a routing metrics event for a command that always runs on the RW endpoint with no
     * fallback. Collapses the repeated {@code recordCommandRoute(cmd, TO_READ_WRITE, RW,
     * FallbackReason.NONE)} call.
     */
    private void recordRwCmd(final String commandName) {
        routingMetrics.recordCommandRoute(
                commandName,
                Router.RouteTarget.TO_READ_WRITE,
                endpointForMetrics(SessionLeg.RW),
                FallbackReason.NONE);
    }

    private Endpoint endpointForMetrics(final SessionLeg leg) {
        Endpoint current = getCurrentEp(leg);
        if (current != null) {
            return current;
        }

        return connMgr == null ? null : connMgr.getSessionEndpoint(leg);
    }

    private static final class EndpointSelection {
        private final Endpoint endpoint;
        private final String fallbackReason;

        private EndpointSelection(final Endpoint endpoint, final String fallbackReason) {
            this.endpoint = endpoint;
            this.fallbackReason = fallbackReason;
        }

        private Endpoint getEndpoint() {
            return endpoint;
        }

        private String getFallbackReason() {
            return fallbackReason;
        }
    }
}
