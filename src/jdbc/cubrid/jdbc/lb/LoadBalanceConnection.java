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
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UPreparedStatementCache;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LbLogConfig;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.NodeRole;
import cubrid.jdbc.lb.config.ResolvedRoleTopology;
import cubrid.jdbc.lb.connection.EndpointConnManager;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.connection.LegBinding;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import cubrid.jdbc.lb.failover.ExecuteFailoverHandler;
import cubrid.jdbc.lb.failover.ExecuteFailoverHandler.SqlExecution;
import cubrid.jdbc.lb.failover.PhysicalRecoveryContext;
import cubrid.jdbc.lb.failover.PhysicalRecoveryResult;
import cubrid.jdbc.lb.log.LbLog;
import cubrid.jdbc.lb.log.LbLogDedup;
import cubrid.jdbc.lb.metrics.BindingView;
import cubrid.jdbc.lb.metrics.MetricsExporters;
import cubrid.jdbc.lb.metrics.MetricsRegistry;
import cubrid.jdbc.lb.metrics.RuntimeMetrics;
import cubrid.jdbc.lb.report.LbDistLog;
import cubrid.jdbc.lb.report.LbTopologyLog;
import cubrid.jdbc.lb.route.RoleReadSelector;
import cubrid.jdbc.lb.route.RoutedSql;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.route.Router.RouteTarget;
import cubrid.jdbc.lb.sql.HintParser;
import cubrid.jdbc.lb.sql.IntoVariables;
import cubrid.jdbc.lb.sql.SessionSetStatement;
import cubrid.jdbc.lb.sql.SqlClassification;
import cubrid.jdbc.lb.sql.SqlClassifier;
import cubrid.jdbc.lb.sql.SqlClassifierRegistry;
import cubrid.jdbc.lb.sql.SqlLiteralMasker;
import cubrid.jdbc.lb.state.RecoveryBackoff;
import cubrid.jdbc.lb.state.RecoveryBackoffRegistry;
import cubrid.jdbc.lb.state.SessionRoutingState;
import cubrid.jdbc.lb.state.SharedSelectorState;
import cubrid.jdbc.lb.state.SharedSelectorStateRegistry;
import cubrid.jdbc.lb.statement.LBCallableStatement;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import cubrid.jdbc.lb.statement.LBStatement;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Logical JDBC {@link java.sql.Connection} for HA load balancing.
 *
 * <p>Applications use one connection; internally {@link cubrid.jdbc.lb.route.Router} and {@link
 * cubrid.jdbc.lb.connection.EndpointConnManager} route SQL to session-bound RW/RO physical brokers.
 *
 * <p>Driver entry for a URI {@code loadbalance://} URL: {@link #openFromSettings(String,
 * Properties, LoadBalanceSettings)}.
 *
 * <p>It also owns the session-level state that routing needs: the weighted read home slot taken
 * from the pool-shared selector, the currently bound RW/read endpoints, transaction state, the JDBC
 * session properties re-applied to every fresh physical connection, and the routing metrics.
 */
public class LoadBalanceConnection extends CUBRIDConnection {
    private static final AtomicLong CONNECTION_SEQUENCE = new AtomicLong(1L);
    // Open LB connections in this JVM; the last close flushes the held-back log records.
    private static final AtomicInteger LIVE_CONNECTIONS = new AtomicInteger();
    // Monotonic id source for per-logical-PreparedStatement prep-cache ownership.
    private static final AtomicLong PS_OWNER_SEQ = new AtomicLong(0L);
    private final LoadBalanceSettings config;
    private final SqlClassifier sqlClassifier;
    private final SessionRoutingState sessionState;
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
    // back. volatile for the same reason as `closed`: close() may run on another thread.
    private volatile NodeRole homeReadRole = null;
    // The endpoint actually selected for the most recent physical prepare/statement, so failover
    // recovers the endpoint that was really used, not a recomputed preview.
    private Endpoint lastExecEndpoint = null;
    // Where that statement ran: lastExecEndpoint, except a read on the shared RW connection
    // (a master read, or roOnRw) ran on the RW endpoint. Metrics and logs report this one.
    private Endpoint lastExecRanOn = null;
    // Whether that statement was a read on the shared RW connection (a master read, or roOnRw).
    private boolean lastExecOnRwConn = false;
    // Set only while endLostWriteTx rebinds the write leg: the transaction there is known to be
    // over, although the dead connection's last reply still says it is open.
    private boolean writeTxLost = false;
    private EndpointTopology sessionTopology = null;
    private final Object recoveryLock = new Object();
    private final Object statementCacheLock = new Object();
    private boolean sessionInitialized;
    // System.nanoTime() of the last attempt; null before the first, as nanoTime has no "never".
    private Long lastReadFailbackAttemptNanos;
    private Long lastRwFailbackAttemptNanos;
    // close() may run on a pool-eviction thread other than the one using the connection. Atomic so
    // two threads closing at once release once, as core's synchronized close() does.
    private final AtomicBoolean closed = new AtomicBoolean();
    // Set only by the application. Until then every leg keeps the server's level, as a core
    // connection does.
    private Integer transactionIsolation = null;
    // Likewise: until then every leg keeps the URL's hold_cursor.
    private Integer holdability = null;
    private boolean readOnly = false;
    private String catalog = null;
    private Integer lockTimeout = null;
    private String charset = null;
    // setNetworkTimeout: null until the application sets one, as core starts at 0.
    private Executor networkTimeoutExecutor = null;
    private int networkTimeout = 0;

    private DatabaseMetaData rwMetaForCmd = null;
    // One per connection, as core keeps one; closed with the connection.
    private final Object metaDataLock = new Object();
    private LBDatabaseMetaData metaData = null;
    // As the application wrote it, which is what core's DatabaseMetaData.getURL() reports.
    private final String urlAsWritten;
    // One warning per session when reads distribute under an isolation level that a distributed
    // read cannot honour. Not a routing input: the level is the application's to choose, and LB
    // only says that it holds within one node.
    private boolean isolationSpreadWarned;
    private Integer casChangeMode = null;
    // Core's connection keeps it and uses it when a transaction ends; null until set.
    private Boolean autoGeneratedKeys = null;
    // The legs this session claimed (claimValueObjectOwnership). They report their transaction
    // ends to legTransactionEnding, which may run inside core's lock, so this needs no lock of
    // ours.
    private final Set<Connection> ownedLegs =
            Collections.newSetFromMap(new ConcurrentHashMap<Connection, Boolean>());
    // Ends pass between legs both ways, each inside the ending leg's lock. A second thread passing
    // one at the same time skips it rather than wait for a leg the first may hold.
    private final ReentrantLock legEndLock = new ReentrantLock();
    // A read leg's rollback, held until its statement settles (settleReadLegRollback).
    private volatile CUBRIDConnection heldReadLegRollback = null;
    // SQL SETs re-run on every leg opened later (replay key -> statement), in the order they last
    // ran, so a newer SET of the same setting replaces the older one.
    private final Map<String, Replayed> sessionSets = new LinkedHashMap<String, Replayed>();
    // The server session each leg was in when it last got the session SETs; see
    // restoreSessionSetsAfterReconnect.
    private final Map<Connection, byte[]> legSessions =
            Collections.synchronizedMap(new WeakHashMap<Connection, byte[]>());
    // Why a read the read leg could not serve is running again on RW, or null when none is.
    private volatile String rejectedReadReason = null;
    // The routing decision of the statement the execute path is running, or null outside it.
    private Router.RouteDecision statementRoute;
    private final BooleanSupplier rwTransactionOpen =
            new BooleanSupplier() {
                public boolean getAsBoolean() {
                    return isRwTxOpenInAutoCommit();
                }
            };
    private static final Logger LOGGER = Logger.getLogger(LoadBalanceConnection.class.getName());
    /** CUBRID ER_DB_NO_MODIFICATIONS: a read-only broker refused to write. */
    private static final int ER_DB_NO_MODIFICATIONS = -581;
    /** CUBRID ER_SP_EXECUTE_ERROR: a stored routine failed; its write on a read node lands here. */
    private static final int ER_SP_EXECUTE_ERROR = -889;
    /** CUBRID ER_ES_FILE_NOT_FOUND: a LOB's external file, which HA does not replicate. */
    private static final int ER_ES_FILE_NOT_FOUND = -1020;
    /** CUBRID ER_TP_CANT_COERCE: how reading a LOB column's content fails on a read node. */
    private static final int ER_TP_CANT_COERCE = -181;

    private ExecuteFailoverHandler executeFailoverHandler = new ExecuteFailoverHandler();
    // True when this connection's RuntimeMetrics was registered for export; drives unregister
    // (flush of final counts) at close().
    private boolean metricsRegistered = false;
    // Birth of this logical connection, for the lifetime reported at close(). A pool discarding
    // healthy connections shows up as a population of very short lifetimes, which cannot be seen
    // from the close record alone.
    private final long createdAtNanos = System.nanoTime();

    public interface PhysicalResourceReleaser {

        void releasePreparedStatements();

        void releasePhysicalConnections();
    }

    private static final class NoOpPhysicalResourceReleaser implements PhysicalResourceReleaser {
        public void releasePreparedStatements() {}

        public void releasePhysicalConnections() {}
    }

    /**
     * Builds a connection without the URL the application wrote, so it reports none; {@link
     * #openFromSettings} uses the URL-carrying form.
     *
     * @param config the parsed load-balance settings
     */
    LoadBalanceConnection(LoadBalanceSettings config) {
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
    private LoadBalanceConnection(
            final String userUrl, final Properties info, final LoadBalanceSettings config) {
        // super() must come first, so the config null check moves into the argument helper.
        super(CUBRIDDriver.maskUriUrlPassword(userUrl, info), userOf(config));
        this.urlAsWritten = userUrl;
        this.config = config;
        // Core makes its statement cache in its constructor; LB keeps that one cache for the legs.
        this.prepStmtCache =
                new UPreparedStatementCache<String, PreparedStatement>(
                        config.getStatementCacheOptions().getPrepStmtCacheSize());
        this.sqlClassifier = SqlClassifierRegistry.forConfig(config);
        this.sessionState = new SessionRoutingState();
        this.routingMetrics =
                new RoutingMetricsRecorder(connectionId, config, sessionState, sqlClassifier);
        maybeStartMetrics(config);
        LIVE_CONNECTIONS.incrementAndGet();
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
        // Registered before the exporters are started: one stopping for want of a live connection
        // checks again under the lock configure() takes, so it sees this one or is stopped by then.
        MetricsRegistry.register(
                routingMetrics.runtimeMetrics(),
                buildEndpointRoleMap(config),
                new WeakBindingView(this));
        metricsRegistered = true;
        try {
            MetricsExporters.configure(config.getMetricsConfig());
        } catch (RuntimeException startFailed) {
            MetricsRegistry.unregister(routingMetrics.runtimeMetrics());
            metricsRegistered = false;
            throw startFailed;
        }
    }

    /**
     * The registry's view of a connection's binding, read lazily at export time: the endpoint is
     * not yet bound at construction and can move on failover/failback. Weak, so the registry does
     * not keep an abandoned connection from being collected and closed by finalize(), as core's is.
     */
    private static final class WeakBindingView implements BindingView {
        private final WeakReference<LoadBalanceConnection> connection;

        WeakBindingView(final LoadBalanceConnection connection) {
            this.connection = new WeakReference<LoadBalanceConnection>(connection);
        }

        public String boundReadEndpointId() {
            LoadBalanceConnection c = connection.get();
            Endpoint ep = c == null ? null : c.binding().getReadEndpoint();
            return ep == null ? null : ep.getId();
        }

        public boolean readsOnRwConnection() {
            LoadBalanceConnection c = connection.get();
            EndpointConnManager mgr = c == null ? null : c.connMgr;
            return mgr != null && mgr.isReadOnRwConnection();
        }
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
        // Writes nothing after the first connection of a given URL.
        LbTopologyLog.declare(url, info, config);
        LbDistLog.configure(config);
        // Selector state is shared by the pools on one cluster with one readWeight, the ratio it
        // balances to, whatever else their URLs differ in. Recovery-probe backoff has the same
        // scope: single-prober election and markReachable propagation only work if the sessions
        // share one schedule.
        final String cluster = LbTopologyLog.resolved(config);
        connection.setSharedSelectorState(SharedSelectorStateRegistry.getOrCreate(cluster));
        RecoveryBackoff backoff =
                RecoveryBackoffRegistry.getOrCreate(cluster, config.getRecoveryProbeIntervalMs());
        connection.setConnectionManager(
                new SessionPhysicalConnManager(
                        url, LbProps.copy(info), config, connectionFactory, backoff));
        // initSessionBindings opens physical connections and then applies session state
        // (isolation/holdability/...). If a post-bind step throws, the bound physical connections
        // would
        // leak, because this half-built LoadBalanceConnection is never returned and the caller
        // never close()s it. Close it here before propagating.
        try {
            connection.initSessionBindings(config.buildEndpointTopology());
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // best-effort cleanup; surface the original failure
            }
            throw e;
        }

        return connection;
    }

    public LoadBalanceSettings getLbConfig() {
        return config;
    }

    public SessionRoutingState getSessionState() {
        return sessionState;
    }

    void setSharedSelectorState(SharedSelectorState shared) {
        this.selState = shared;
    }

    SharedSelectorState getSharedSelectorState() {
        return selState;
    }

    void setPhysicalResourceReleaser(final PhysicalResourceReleaser lifecycle) {
        if (lifecycle == null) {
            throw new IllegalArgumentException("PhysicalResourceReleaser must not be null");
        }
        this.physicalResourceReleaser = lifecycle;
    }

    void setConnectionManager(final EndpointConnManager connectionManager) {
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

    EndpointConnManager getConnectionManager() {
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

    RuntimeMetrics getRuntimeMetrics() {
        return routingMetrics.runtimeMetrics();
    }

    /**
     * Records the wall-clock latency of a just-completed physical execution against the endpoint it
     * ran on ({@link #lastExecRanOn}, set at prepare/resolve time). Read vs write is taken from the
     * route target. No-op unless metrics recording is enabled. Called by {@link
     * ExecuteFailoverHandler}.
     *
     * @param target the route target the execution ran under
     * @param nanos the measured execution latency in nanoseconds
     */
    public void recordExecLatency(final RouteTarget target, final long nanos) {
        RuntimeMetrics metrics = routingMetrics.runtimeMetrics();
        if (!metrics.isEnabled()) {
            return;
        }
        Endpoint ep = lastExecRanOn;
        if (ep == null) {
            return;
        }
        metrics.recordLatency(ep.getId(), target == RouteTarget.TO_READ_WRITE, nanos);
    }

    public ExecuteFailoverHandler getExecuteFailoverHandler() {
        return executeFailoverHandler;
    }

    Endpoint endpointForTarget(final RouteTarget target) throws SQLException {
        return selectionForTarget(target).getEndpoint();
    }

    /**
     * The endpoint to recover after a failed execution.
     *
     * <p>A write resolves to the bound write leg, which is pinned to one endpoint. The RW command
     * paths ({@code runRwCommand}, {@code runRwMetaQuery}) never pass through {@link
     * #routeStatement}, so {@link #lastExecEndpoint} can still name the read endpoint of an earlier
     * SELECT - recovering that one would close a healthy RO connection and leave the dead RW
     * connection bound.
     *
     * <p>A read resolves to {@link #lastExecEndpoint}, the endpoint the statement was actually
     * prepared on, which a fresh routing preview would not always reproduce.
     *
     * @param target the route target of the failed execution
     * @return the endpoint to recover
     * @throws SQLException if the fallback route cannot be resolved
     */
    public Endpoint resolveFailedExecEndpoint(final RouteTarget target) throws SQLException {
        if (target == RouteTarget.TO_READ_WRITE) {
            Endpoint rw = getCurrentEp(SessionLeg.RW);
            if (rw != null) {
                return rw;
            }
        } else {
            Endpoint captured = lastExecEndpoint;
            if (captured != null) {
                return captured;
            }
        }

        return endpointForTarget(target);
    }

    /**
     * The route target whose leg a failed execution recovers. A read on the RW connection (roOnRw,
     * or a master-read session's) failed on the RW leg: recovered as a read, it climbed the RO
     * ladder again and got the same dead RW connection back. Recovering the RW leg moves the read
     * with it.
     *
     * @param target the route target of the failed execution
     * @return {@code TO_READ_WRITE} for such a read, otherwise {@code target}
     */
    public RouteTarget failedLegTarget(final RouteTarget target) {
        return target == RouteTarget.TO_READ_ONLY && lastExecOnRwConn
                ? RouteTarget.TO_READ_WRITE
                : target;
    }

    /**
     * The leg a call on the bound read endpoint runs on: the write leg when reads share its
     * connection, the read leg otherwise.
     *
     * @return {@code TO_READ_WRITE} or {@code TO_READ_ONLY}
     */
    public RouteTarget boundReadLegTarget() {
        return binding().readSharesRwConnection()
                ? RouteTarget.TO_READ_WRITE
                : RouteTarget.TO_READ_ONLY;
    }

    /**
     * Whether the read leg's own connection, not the RW one it may share, is closed or gone under
     * LB: core closes a connection whose network timeout expired, a setter drops a read leg that
     * failed, and a reopen that failed leaves none. A failure on it is the leg's.
     *
     * @return whether it is closed or gone
     */
    public boolean isOwnReadLegClosed() {
        final EndpointConnManager mgr = connMgr;
        final LegBinding legs = binding();
        if (mgr == null || legs.getReadEndpoint() == null || legs.readSharesRwConnection()) {
            return false;
        }
        final Connection ro = mgr.boundConnection(SessionLeg.RO);
        if (ro == null) {
            return true;
        }
        if (ro == mgr.boundConnection(SessionLeg.RW)) {
            return false;
        }
        try {
            return ro.isClosed();
        } catch (SQLException unreadable) {
            return true;
        }
    }

    /**
     * Moves the read leg as a failed read moves it, for a prepare that timed out on it.
     *
     * @param cause the failure on the read leg
     * @throws SQLException if no other read endpoint can be bound
     */
    public void recoverReadLeg(final SQLException cause) throws SQLException {
        recoverPhyBinding(
                buildRecoveryCtx(RouteTarget.TO_READ_ONLY, getCurrentRoEndpoint()), cause);
    }

    public PhysicalRecoveryContext buildRecoveryCtx(
            final RouteTarget target, final Endpoint failedEp) throws SQLException {
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
                // recoverRo gate it on the probe window when they dial, and hard-excluding it here
                // would undo the backoff-bypass probe.
                Collections.<String>emptySet(),
                hasUncommittedRwWork());
    }

    /**
     * Whether failing over now could lose work the application believes is in its transaction.
     *
     * <p>Only a manual-commit session can have such work, and its routing pin ({@code
     * shouldRouteToRw}) cannot tell: it is re-armed at every boundary to keep reads on the write
     * connection, so it says "in a transaction" even right after a commit. The server can - it
     * reports in every reply whether a transaction is open, including work LB never saw.
     */
    private boolean hasUncommittedRwWork() throws SQLException {
        if (sessionState.isAutoCommit() || writeTxLost) {
            return false;
        }

        return requireConnMgr().isRwTxOpenOnServer();
    }

    /**
     * Core leaves an autocommit transaction open after an OID write or a CALL and ends it when the
     * next statement on that connection completes. While the write leg's CAS reports one, a read
     * runs there too, so core ends it as it would on its single connection.
     *
     * @return whether the write leg reports an open transaction in autocommit
     */
    public boolean isRwTxOpenInAutoCommit() {
        return sessionState.isAutoCommit() && isRwTxReportedOpen();
    }

    /**
     * Whether the write leg's last reply said a transaction is open there, in either commit mode.
     *
     * @return whether the write leg reports an open transaction
     */
    public boolean isRwTxReportedOpen() {
        return isSessionInitialized() && connMgr != null && connMgr.isRwTxReportedOpen();
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
    private Endpoint routeStatement(final RouteTarget target, final String rawSql)
            throws SQLException {
        // Before failback, which would replace a leg core closed with a new one.
        checkClosed();
        maybeRestoreRoBinding(target);
        failbackRwIfStatementAllows();
        EndpointSelection endpointSelection = selectionForTarget(target);

        Endpoint endpoint = endpointSelection.getEndpoint();
        lastExecEndpoint = endpoint;
        String fallbackReason = endpointSelection.getFallbackReason();
        // A read on the RW connection runs, and fails, on the RW leg: under roOnRw it stays bound
        // to its RO endpoint, and a master-read session's read endpoint is the RW one. The records
        // and failover name that leg.
        final Endpoint rwNow = currentOrManagerEndpoint(SessionLeg.RW);
        lastExecOnRwConn =
                FallbackReason.RO_PHYSICAL.equals(fallbackReason)
                        || (target == RouteTarget.TO_READ_ONLY
                                && endpoint != null
                                && rwNow != null
                                && endpoint.getId().equals(rwNow.getId()));
        lastExecRanOn = lastExecOnRwConn ? rwNow : endpoint;
        // The execute path's decision; a metadata call has none and is read here.
        final Router.RouteDecision decision =
                statementRoute != null
                        ? statementRoute
                        : Router.decide(
                                RoutedSql.of(rawSql, sqlClassifier),
                                false,
                                false,
                                sessionState,
                                rwTransactionOpen);
        if (target == RouteTarget.TO_READ_WRITE) {
            fallbackReason =
                    rejectedReadReason != null
                            ? rejectedReadReason
                            : readFallbackReason(decision, fallbackReason);
        }
        routingMetrics.recordStatementRoute(rawSql, target, lastExecRanOn, fallbackReason);
        routingMetrics.logRoute(decision, target, lastExecRanOn, fallbackReason);
        // Rides here rather than inside logRoute: that record is FINE and this one is INFO, so
        // gating it on the routing record's level would silence it at the default level.
        LbDistLog.onStatementRouted();
        return endpoint;
    }

    LBPreparedStatement.PhysicalPsProvider createPsProvider() throws SQLException {
        return createPsProvider(Statement.NO_GENERATED_KEYS);
    }

    private LBPreparedStatement.PhysicalPsProvider createPsProvider(final int autoGeneratedKeys)
            throws SQLException {
        return createPsProvider(
                autoGeneratedKeys,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY,
                statementHoldability());
    }

    private LBPreparedStatement.PhysicalPsProvider createPsProvider(
            final int resultSetType,
            final int resultSetConcurrency,
            final int resultSetHoldability) {
        return createPsProvider(
                Statement.NO_GENERATED_KEYS,
                resultSetType,
                resultSetConcurrency,
                resultSetHoldability);
    }

    private LBPreparedStatement.PhysicalPsProvider createPsProvider(
            final int autoGeneratedKeys,
            final int resultSetType,
            final int resultSetConcurrency,
            final int resultSetHoldability) {
        // Unique owner id per logical PreparedStatement (one provider per PS), so the manager
        // caches
        // this PS's physical statements under its own key and close() releases only them. The
        // generated-keys flag and the result-set type/concurrency/holdability are captured here
        // and carried to every physical prepare, so RETURN_GENERATED_KEYS is honored and a
        // scrollable logical PS is backed by a scrollable physical PS.
        final String ownerId = "ps-" + PS_OWNER_SEQ.incrementAndGet();
        return new LBPreparedStatement.PhysicalPsProvider() {
            private PreparedStatement lastPrepared;

            public PreparedStatement getPreparedStatement(
                    final RouteTarget target, final String rawSql) throws SQLException {
                Endpoint endpoint = routeStatement(target, rawSql);
                lastPrepared =
                        preparePhysical(
                                endpoint,
                                rawSql,
                                ownerId,
                                autoGeneratedKeys,
                                resultSetType,
                                resultSetConcurrency,
                                resultSetHoldability);
                return lastPrepared;
            }

            public void prepareOnBoundReadEndpoint(final String rawSql) throws SQLException {
                if (!sessionInitialized || binding().getReadEndpoint() == null) {
                    return;
                }

                // Align the read binding BEFORE the eager prepare: if a displaced session fails
                // back between prepare time and execute time, the eager prepare lands on the old
                // endpoint and execute re-prepares on the new one - the same SQL prepared on two
                // brokers. Probing first (a throttled no-op when not displaced) keeps it to one.
                maybeRestoreRoBinding(RouteTarget.TO_READ_ONLY);

                // READ: prepare only the bound read endpoint. There is no eager RW leg;
                // WRITE/UNKNOWN still prepare RW via prepareOnWriteEndpoint. A master-role or
                // RW-only session's read endpoint is the RW endpoint, and a roOnRw session's maps
                // to the RW connection, so their reads still prepare on RW.
                lastPrepared =
                        preparePhysical(
                                binding().getReadEndpoint(),
                                rawSql,
                                ownerId,
                                autoGeneratedKeys,
                                resultSetType,
                                resultSetConcurrency,
                                resultSetHoldability);
            }

            public void prepareOnWriteEndpoint(final String rawSql) throws SQLException {
                final Endpoint rw = binding().getRwEndpoint();
                if (!sessionInitialized || rw == null) {
                    return;
                }
                lastPrepared =
                        preparePhysical(
                                rw,
                                rawSql,
                                ownerId,
                                autoGeneratedKeys,
                                resultSetType,
                                resultSetConcurrency,
                                resultSetHoldability);
            }

            public PreparedStatement preparedForBindCheck() throws SQLException {
                return lastPrepared != null && !lastPrepared.isClosed() ? lastPrepared : null;
            }

            public boolean takeClosedBindCheck() throws SQLException {
                if (lastPrepared == null || !lastPrepared.isClosed()) {
                    return false;
                }
                lastPrepared = null;
                return true;
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

    /**
     * The physical prepare behind every PS provider call. JDBC has no prepare form that takes both
     * a generated-keys flag and result-set properties, so a provider carries one or the other.
     */
    private PreparedStatement preparePhysical(
            final Endpoint endpoint,
            final String rawSql,
            final String ownerId,
            final int autoGeneratedKeys,
            final int resultSetType,
            final int resultSetConcurrency,
            final int resultSetHoldability)
            throws SQLException {
        try {
            if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS) {
                return requireConnMgr()
                        .prepareStatement(endpoint, rawSql, ownerId, autoGeneratedKeys);
            }
            return requireConnMgr()
                    .prepareStatement(
                            endpoint,
                            rawSql,
                            ownerId,
                            resultSetType,
                            resultSetConcurrency,
                            resultSetHoldability);
        } catch (SQLException e) {
            throw closedOr(e);
        }
    }

    public LBStatement.PhysicalStmtProvider createStatementProvider(
            final int resultSetType,
            final int resultSetConcurrency,
            final int resultSetHoldability) {
        return new LBStatement.PhysicalStmtProvider() {
            // One per leg connection, as core runs every execution of a statement on one object.
            private final Map<Connection, Statement> byLeg =
                    new IdentityHashMap<Connection, Statement>();

            public Statement getStatement(final RouteTarget target, final String rawSql)
                    throws SQLException {
                Endpoint endpoint = routeStatement(target, rawSql);
                Connection physical;
                try {
                    physical = requireConnMgr().getPhyConn(endpoint);
                } catch (SQLException e) {
                    throw closedOr(e);
                }

                Statement reused = byLeg.get(physical);
                if (reused != null && !reused.isClosed()) {
                    return reused;
                }
                for (Iterator<Statement> it = byLeg.values().iterator(); it.hasNext(); ) {
                    if (it.next().isClosed()) {
                        it.remove(); // its leg was replaced
                    }
                }
                Statement created = create(physical);
                byLeg.put(physical, created);
                return created;
            }

            private Statement create(final Connection physical) throws SQLException {
                try {
                    return physical.createStatement(
                            resultSetType, resultSetConcurrency, resultSetHoldability);
                } catch (SQLException ex) {
                    // Only a statement that took the connection's HOLD gets here refused: core's
                    // 2-arg createStatement takes it without the check, so the 2-arg form does
                    // too. Any other SQLException (a broker being down, say) is the real cause.
                    if (!(ex instanceof SQLFeatureNotSupportedException)) {
                        throw ex;
                    }
                    return physical.createStatement(resultSetType, resultSetConcurrency);
                }
            }
        };
    }

    void initSessionBindings(final EndpointTopology topology) throws SQLException {
        checkClosed();
        if (topology == null) {
            throw LbExceptions.internalState(
                    "missing endpoint topology for session initialization");
        }

        if (!topology.hasRwEndpoint()) {
            throw LbExceptions.topologyInvalid("Session distribution requires an RW endpoint");
        }

        EndpointConnManager mgr = requireConnMgr();

        bindFromUrlTopology(mgr, topology);

        sessionTopology = topology;
        sessionInitialized = true;
        mgr.applyPhyAutoCommit(sessionState.isAutoCommit());
        if (tryMaterializeBothLegs()) {
            applyTxnIsolation().rethrow();
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
     *       EndpointConnManager#bindSessionWithReadTarget}: a master read reuses the RW connection,
     *       a slave/replica read opens a separate RO/SO connection.
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
            abandoned = closed.get();
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

    /** Where the legs are now, read without the manager's lock. */
    private LegBinding binding() {
        final EndpointConnManager mgr = connMgr;
        return mgr == null ? LegBinding.UNBOUND : mgr.binding();
    }

    Endpoint getCurrentRwEndpoint() {
        return binding().getRwEndpoint();
    }

    /**
     * The endpoint reads run on. While they share the RW connection (roOnRw) that is the RW
     * endpoint, although the session stays bound to its RO endpoint for failback.
     *
     * @return the endpoint serving reads, or {@code null} before the session is bound
     */
    Endpoint getCurrentRoEndpoint() {
        return binding().getReadRunsOn();
    }

    EndpointTopology getSessionTopology() {
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
            if (ctx.isTxActive() || hasUncommittedRwWork()) {
                throw originalEx;
            }

            EndpointConnManager mgr = requireConnMgr();
            PhysicalRecoveryResult result;

            switch (ctx.getFailedLeg()) {
                case RW:
                    result = mgr.recoverRw(ctx);
                    break;
                case RO:
                    result = mgr.recoverRo(ctx);
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
                        + causeOf(cause);

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

    Endpoint getCurrentEp(final SessionLeg leg) {
        if (leg == null) {
            throw new IllegalArgumentException("SessionLeg must not be null");
        }

        switch (leg) {
            case RW:
                return binding().getRwEndpoint();
            case RO:
                return binding().getReadEndpoint();
            default:
                throw new IllegalStateException("Unsupported session leg: " + leg);
        }
    }

    boolean isSessionInitialized() {
        return sessionInitialized;
    }

    /**
     * Runs a write-endpoint command through the failover handler.
     *
     * <p>{@link #getPhysicalConnForCmd} resolves a leg but never recovers one, so commands that go
     * straight to it - {@code prepareCall}, the LOB trio, {@code createBlob}/{@code createClob} -
     * failed outright when the write broker was down, even though the session could have rebound to
     * a surviving one. Routing them here rebinds the leg on a broker failure.
     *
     * <p>It does not re-run the command ({@code retrySafe} is false), because the server may
     * already have applied the work before the connection dropped, and a {@code CALL} is not
     * idempotent. The leg is recovered and the original failure is reported, leaving the retry
     * decision to the caller.
     *
     * @param label the command name, for the routing log
     * @param command what to run against the write leg
     * @param <T> the command's result type
     * @return whatever the command returned
     * @throws SQLException if the command failed, after the leg was recovered
     */
    private <T> T runRwCommand(final String label, final SqlExecution<T> command)
            throws SQLException {
        T result =
                getExecuteFailoverHandler()
                        .executeCommandWithFailover(
                                this, RouteTarget.TO_READ_WRITE, label, false, command);
        recordRwCmd(label);

        return result;
    }

    Connection getPhysicalConnForCmd(final SessionLeg leg) throws SQLException {
        checkClosed();
        if (!sessionInitialized) {
            throw closedOr(LbExceptions.sessionNotInitialized(null));
        }

        Endpoint endpoint = getCurrentEp(leg);
        if (endpoint == null) {
            throw closedOr(LbExceptions.sessionNotInitialized("role=" + leg));
        }

        try {
            return requireConnMgr().getPhyConn(endpoint);
        } catch (SQLException e) {
            throw closedOr(e);
        }
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
            try {
                propagatePhyCommit();
            } catch (SQLException failure) {
                endLostWriteTx("implicit commit", failure);
                throw failure;
            }
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
            // No transaction to end, but core's commit still completes the open statements.
            propagatePhyCommit();
            return;
        }
        try {
            propagatePhyCommit();
        } catch (SQLException failure) {
            endLostWriteTx("commit", failure);
            throw failure;
        }
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
            // No transaction to end, but core's rollback still completes the open statements.
            propagatePhyRollback();
            return;
        }
        try {
            propagatePhyRollback();
        } catch (SQLException failure) {
            endLostWriteTx("rollback", failure);
            throw failure;
        }
        sessionState.onTransactionBoundary();
        logTxBoundary("rollback");
        attemptRwFailback(); // same transaction boundary as commit(); see the comment there
    }

    /**
     * A commit or rollback failed because the write connection is gone. The server has aborted the
     * transaction, but the dead connection still reports it open and would block every later
     * failover, so rebind the write leg now. The caller still sees the failure.
     */
    private void endLostWriteTx(final String event, final SQLException failure) {
        if (!ExecuteFailoverHandler.isBrokerFailure(failure)) {
            return; // the transaction may well still be open
        }

        sessionState.onTransactionBoundary();
        logTxBoundary(event + " lost with the write connection");
        writeTxLost = true;
        try {
            Endpoint failed = resolveFailedExecEndpoint(RouteTarget.TO_READ_WRITE);
            recoverPhyBinding(buildRecoveryCtx(RouteTarget.TO_READ_WRITE, failed), failure);
        } catch (SQLException stillDown) {
            // Nothing rebound; the next commit or rollback tries again.
            if (stillDown != failure) {
                failure.setNextException(stillDown);
            }
        } finally {
            writeTxLost = false;
        }
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
            final LegBinding legs = binding();
            if (legs.getRwEndpoint() == null || legs.getReadEndpoint() == null) {
                return false;
            }

            EndpointConnManager mgr = requireConnMgr();
            mgr.getPhyConn(legs.getRwEndpoint());
            mgr.getPhyConn(legs.getReadEndpoint());

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
        // Published before releasing, so a concurrent user thread's checkClosed() sees it and does
        // not start new work against half-released state.
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Logged before the release work and with the endpoints still readable: a pool discarding
        // healthy connections shows up as many very short lifetimes on particular endpoints, and
        // both facts are gone once the bindings are cleared below.
        LbLog.fine(
                LOGGER,
                LbLog.conn(connectionId),
                "LB CONN: closed after "
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdAtNanos)
                        + "ms"
                        + " | "
                        + describeLegs(binding(), "?", " "));
        // Before any physical release can throw: a leaked home slot silently biases every future
        // selection for this pool, and the `closed` guard means this runs exactly once.
        releaseHomeRole();
        if (metricsRegistered) {
            // Fold this connection's final counts into the registry's cumulative tally so a
            // maxLifetime eviction does not lose them.
            MetricsRegistry.unregister(routingMetrics.runtimeMetrics());
            metricsRegistered = false;
        }
        // Before the legs go: an OUT result set still holds a socket statement on one of them.
        closeOutResultSets();
        // The legs before this session's statements, so core's close() completes every statement
        // on a leg, as on its one connection, before a statement's own completion can commit.
        releasePhysicalConnections();
        closeOpenStatements();
        releasePreparedStatements();
        if (prepStmtCache != null) {
            prepStmtCache.clear(); // after the statements, as core: a cached one stayed open
        }
        ownedLegs.clear();
        if (metaData != null) {
            metaData.closeWithConnection();
        }
        sessionState.reset();
        // Init fields are otherwise mutated under recoveryLock (recoverPhyBinding); guard them here
        // too so the lock's invariant holds.
        synchronized (recoveryLock) {
            sessionTopology = null;
            sessionInitialized = false;
        }
        // The shutdown hooks that also flush these race java.util.logging's own, which removes
        // every handler first; a pool closing its last connection is the dependable point.
        if (LIVE_CONNECTIONS.decrementAndGet() == 0) {
            LbLogDedup.drain();
            LbDistLog.drainFinal();
            // A connection opened meanwhile found the shutdown flush that drain has now removed.
            if (LIVE_CONNECTIONS.get() > 0) {
                LbDistLog.configure(config);
            }
        }
    }

    public boolean isClosed() throws SQLException {
        return isClosedWithoutLock();
    }

    /**
     * Core asks this for the closed state; this session has no single socket to ask. The write leg
     * closed by its network timeout leaves the session closed, as core's one connection is; a read
     * leg on its own connection is replaced instead.
     */
    @Override
    protected boolean isClosedWithoutLock() {
        return closed.get() || (connMgr != null && connMgr.isLegClosedUnderneath());
    }

    private void closeOpenStatements() {
        // Snapshot-and-clear under the list monitor so a concurrent trackStatement() (app thread)
        // cannot corrupt the list or trigger ConcurrentModificationException while close()
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

    /** Also starts the statement at the URL's queryTimeout, as core's addStatement does. */
    void trackStatement(Statement stmt) throws SQLException {
        synchronized (openStatements) {
            openStatements.add(stmt);
        }
        final UConnection socket = openLegSocket();
        if (socket != null && socket.getQueryTimeout() > 0) {
            stmt.setQueryTimeout(socket.getQueryTimeout());
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
        checkHoldableResultType(resultSetType, resultSetConcurrency, resultSetHoldability);

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
            final String sql,
            final LBPreparedStatement ps,
            final LBPreparedStatement.PhysicalPsProvider provider)
            throws SQLException {
        ps.setPsProvider(provider);
        // Before the prepare, which opens a transaction that would hold it.
        failbackRwIfStatementAllows();
        if (!cacheable(sql)) {
            ps.prepareEagerly();
            trackStatement(ps);

            return ps;
        }
        // Core's prepareStatement is synchronized: of two threads preparing one SQL, the second
        // gets the first's statement. Taken by a prepare alone, and before any other lock.
        synchronized (statementCacheLock) {
            final PreparedStatement first = prepStmtCache.get(sql);
            if (first != null) {
                return first;
            }
            ps.prepareEagerly();
            trackStatement(ps);
            prepStmtCache.put(sql, ps);

            return ps;
        }
    }

    /**
     * Core's prepared-statement cache ({@code usePreparedStmtCache}): a prepare of SQL it holds
     * gets that statement back, whatever the other arguments.
     */
    private PreparedStatement fromStatementCache(final String sql) throws SQLException {
        return cacheable(sql) ? prepStmtCache.get(sql) : null;
    }

    private boolean cacheable(final String sql) {
        return config.getStatementCacheOptions().isPrepStmtCache(sql);
    }

    /**
     * Core's close leaves open a statement whose SQL its cache holds.
     *
     * @param sql the statement text
     * @return whether the statement cache holds a statement of it
     */
    public boolean isStatementCached(final String sql) {
        return prepStmtCache != null && prepStmtCache.get(sql) != null;
    }

    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkClosed();
        final PreparedStatement cached = fromStatementCache(sql);
        if (cached != null) {
            return cached;
        }

        return newPs(sql, new LBPreparedStatement(this, sql), createPsProvider());
    }

    public PreparedStatement prepareStatement(
            String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkClosed();
        final PreparedStatement cached = fromStatementCache(sql);
        if (cached != null) {
            return cached;
        }

        return newPs(
                sql,
                new LBPreparedStatement(this, sql, resultSetType, resultSetConcurrency),
                createPsProvider(resultSetType, resultSetConcurrency, statementHoldability()));
    }

    public PreparedStatement prepareStatement(
            String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        checkHoldableResultType(resultSetType, resultSetConcurrency, resultSetHoldability);
        checkClosed();
        final PreparedStatement cached = fromStatementCache(sql);
        if (cached != null) {
            return cached;
        }

        return newPs(
                sql,
                new LBPreparedStatement(
                        this, sql, resultSetType, resultSetConcurrency, resultSetHoldability),
                createPsProvider(
                        resultSetType,
                        resultSetConcurrency,
                        statementHoldability(resultSetHoldability)));
    }

    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
            throws SQLException {
        checkClosed();
        final PreparedStatement cached = fromStatementCache(sql);
        if (cached != null) {
            return cached;
        }

        return newPs(sql, new LBPreparedStatement(this, sql), createPsProvider(autoGeneratedKeys));
    }

    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return prepareStatement(sql);
    }

    public PreparedStatement prepareStatement(String sql, String[] columnNames)
            throws SQLException {
        return prepareStatement(sql);
    }

    /* ===== Connection: client info API (unsupported, as in the core driver) =====*/
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        SQLClientInfoException clientEx = new SQLClientInfoException();
        clientEx.initCause(new UnsupportedOperationException());
        throw clientEx;
    }

    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        SQLClientInfoException clientEx = new SQLClientInfoException();
        clientEx.initCause(new UnsupportedOperationException());
        throw clientEx;
    }

    public String getClientInfo(String name) throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    public Properties getClientInfo() throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    /* ===== Connection: basic property API =====*/
    public void setTransactionIsolation(int level) throws SQLException {
        checkClosed();
        final Integer previous = this.transactionIsolation;
        this.transactionIsolation = level;
        LegOutcome outcome = applyTxnIsolation();
        if (outcome.rwFailed()) {
            this.transactionIsolation = previous;
        }
        outcome.rethrow();
        forgetSessionSet(SessionSetStatement.ISOLATION_LEVEL_KEY);
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
                                    new SqlExecution<Integer>() {
                                        public Integer run() throws SQLException {
                                            return Integer.valueOf(
                                                    getPhysicalConnForCmd(SessionLeg.RW)
                                                            .getTransactionIsolation());
                                        }
                                    })
                            .intValue();

            return delegated;
        }

        return transactionIsolation == null
                ? TRANSACTION_READ_COMMITTED
                : transactionIsolation.intValue();
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
        forgetSessionSet(SessionSetStatement.LOCK_TIMEOUT_KEY);
        if (!isSessionInitialized()) {
            return;
        }
        recordRwCmd("setLockTimeout");
    }

    /**
     * Sets the CAS change mode as the other session properties are set: on both legs, the write leg
     * rebound when its broker is down (ISSUE-6), the value kept only when the write leg took it.
     * Returns the write leg's previous mode, core's answer for its one connection. The value is
     * kept only afterwards, so a write leg opened by the rebind starts at the previous mode.
     */
    public int setCASChangeMode(final int mode) throws SQLException {
        checkClosed();
        if (!isSessionInitialized()) {
            this.casChangeMode = Integer.valueOf(mode);
            return 0;
        }

        // The write leg answers first; a failed attempt on it answers nothing.
        final int[] previousMode = new int[1];
        final boolean[] answered = new boolean[1];
        final LegOutcome outcome =
                forEachSessPhyConn(
                        new PhyConnAction() {
                            public void apply(final Connection physical) throws SQLException {
                                final int was =
                                        extConn(physical, "setCASChangeMode")
                                                .setCASChangeMode(mode);
                                if (!answered[0]) {
                                    previousMode[0] = was;
                                    answered[0] = true;
                                }
                            }
                        });
        if (!outcome.rwFailed()) {
            this.casChangeMode = Integer.valueOf(mode);
        }
        outcome.rethrow();
        recordRwCmd("setCASChangeMode");
        return previousMode[0];
    }

    /**
     * {@code CUBRIDConnection} declares this without {@code SQLException}, so the override cannot
     * widen the clause and a physical failure surfaces unchecked. The message and cause survive on
     * the wrapped exception.
     */
    @Override
    public void setCharset(final String charsetName) throws UnsupportedEncodingException {
        if (closed.get()) {
            super.setCharset(charsetName); // what core does on a closed connection
            return;
        }
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

        requireConnMgr()
                .applyToOpenReadLeg(
                        "setCharset",
                        new EndpointConnManager.SessionStateApplier() {
                            public void applyTo(final Connection physical) throws SQLException {
                                try {
                                    extConn(physical, "setCharset").setCharset(charsetName);
                                } catch (UnsupportedEncodingException rejected) {
                                    // The write leg took the name, so this one cannot refuse it.
                                    throw LbExceptions.invalidValue(
                                            "setCharset(" + charsetName + "): " + rejected);
                                }
                            }
                        });
        recordRwCmd("setCharset");
    }

    // Core's Login, Logout and SetSignedConnection are stubs on every connection, open or closed.
    public CUBRIDConnectionKey Login(final String signedData) throws SQLException {
        return super.Login(signedData);
    }

    public CUBRIDConnectionKey Login(final byte[] signedData) throws SQLException {
        return super.Login(signedData);
    }

    @Override
    public void Logout() {
        super.Logout();
    }

    @Override
    public void SetSignedConnection() {
        super.SetSignedConnection();
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
                                    new SqlExecution<Boolean>() {
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
                            new SqlExecution<String>() {
                                public String run() throws SQLException {
                                    return getPhysicalConnForCmd(SessionLeg.RW).getCatalog();
                                }
                            });
            catalog = delegated;

            return delegated;
        }

        return catalog;
    }

    public void setSchema(final String schema) throws SQLException {
        checkClosed();
        if (isSessionInitialized()) {
            runRwCommand(
                    "setSchema",
                    new SqlExecution<Void>() {
                        public Void run() throws SQLException {
                            getPhysicalConnForCmd(SessionLeg.RW).setSchema(schema);
                            return null;
                        }
                    });
        }
    }

    public String getSchema() throws SQLException {
        checkClosed();
        if (isSessionInitialized()) {
            return runRwCommand(
                    "getSchema",
                    new SqlExecution<String>() {
                        public String run() throws SQLException {
                            return getPhysicalConnForCmd(SessionLeg.RW).getSchema();
                        }
                    });
        }

        return null;
    }

    public int getHoldability() throws SQLException {
        checkClosed();
        if (isSessionInitialized()) {
            return runRwCommand(
                            "getHoldability",
                            new SqlExecution<Integer>() {
                                public Integer run() throws SQLException {
                                    return Integer.valueOf(
                                            getPhysicalConnForCmd(SessionLeg.RW).getHoldability());
                                }
                            })
                    .intValue();
        }

        return holdability != null ? holdability.intValue() : ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    /**
     * The holdability a statement created without one takes: the connection's, as in core.
     *
     * @return the holdability
     * @throws SQLException if the open leg cannot report it
     */
    public int statementHoldability() throws SQLException {
        final Connection leg = openLeg();
        if (leg != null) {
            return statementHoldability(leg.getHoldability());
        }
        return statementHoldability(
                holdability != null ? holdability.intValue() : ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }

    /**
     * The holdability a statement asking for {@code requested} reports: core's statement decides it
     * on an open leg. With no leg open yet there is no server to ask, and HOLD stays HOLD.
     *
     * @param requested the holdability asked for
     * @return the holdability the statement reports
     * @throws SQLException if the open leg cannot report it
     */
    public int statementHoldability(final int requested) throws SQLException {
        final UConnection socket = openLegSocket();
        if (socket != null) {
            return LBStatement.holdabilityOn(socket, requested);
        }
        return requested == ResultSet.HOLD_CURSORS_OVER_COMMIT
                ? ResultSet.HOLD_CURSORS_OVER_COMMIT
                : ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    /**
     * An open leg, read without opening one: creating a statement is local, and every leg was
     * opened from the same URL and carries the same session settings.
     */
    private Connection openLeg() throws SQLException {
        if (connMgr == null) {
            return null;
        }
        for (SessionLeg leg : SessionLeg.values()) {
            final Connection physical = connMgr.boundConnection(leg);
            if (physical != null && !physical.isClosed()) {
                return physical;
            }
        }
        return null;
    }

    private UConnection openLegSocket() throws SQLException {
        final Connection leg = openLeg();
        return leg instanceof CUBRIDConnection ? ((CUBRIDConnection) leg).getUConnection() : null;
    }

    public void setHoldability(int holdability) throws SQLException {
        checkClosed();
        final Integer previous = this.holdability;
        this.holdability = Integer.valueOf(holdability);
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
                    new SqlExecution<SQLWarning>() {
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
                    new SqlExecution<SQLWarning>() {
                        public SQLWarning run() throws SQLException {
                            getPhysicalConnForCmd(SessionLeg.RW).clearWarnings();
                            return null;
                        }
                    });
        }
    }

    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkClosed();
        return new HashMap<String, Class<?>>();
    }

    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    /**
     * Gives the timeout to every leg, now and opened later. Core checks the arguments on the write
     * leg, and a value it refused is not kept.
     */
    public void setNetworkTimeout(final Executor executor, final int milliseconds)
            throws SQLException {
        checkClosed();
        final Executor previousExecutor = networkTimeoutExecutor;
        final int previous = networkTimeout;
        networkTimeoutExecutor = executor;
        networkTimeout = milliseconds;
        if (!isSessionInitialized()) {
            return;
        }
        LegOutcome outcome =
                forEachSessPhyConn(
                        new PhyConnAction() {
                            public void apply(Connection physical) throws SQLException {
                                physical.setNetworkTimeout(executor, milliseconds);
                            }
                        });
        if (outcome.rwFailed()) {
            networkTimeoutExecutor = previousExecutor;
            networkTimeout = previous;
        }
        outcome.rethrow();
        recordRwCmd("setNetworkTimeout");
    }

    public int getNetworkTimeout() throws SQLException {
        checkClosed();
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
        // Core's getMetaData is synchronized: threads asking at once get the one object.
        synchronized (metaDataLock) {
            if (metaData == null) {
                metaData = new LBDatabaseMetaData(this);
            }

            return metaData;
        }
    }

    String urlAsWritten() {
        return urlAsWritten;
    }

    public String nativeSQL(String sql) throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    /* ===== Connection: CALL, savepoint, LOB, isValid; unsupported createNClob etc. =====*/
    /**
     * A {@code CALL} runs on the write endpoint. The statement returned wraps the physical one of
     * that leg and passes every call to it, so the OUT-parameter API is core's and the vendor casts
     * keep working (see {@link LBCallableStatement}). It prepares the CALL again when a failover or
     * a failback has replaced that leg.
     *
     * <p>Its runs go through the execute path, so LB sees what a {@code CALL} changes in the
     * session. A run that failed is not run again: a stored procedure is not idempotent.
     */
    public CallableStatement prepareCall(final String sql) throws SQLException {
        return newCall(
                "prepareCall",
                sql,
                new CallPreparer() {
                    public CallableStatement on(final Connection leg) throws SQLException {
                        return leg.prepareCall(sql);
                    }
                });
    }

    /** How one prepareCall overload prepares on a physical connection. */
    private interface CallPreparer {
        CallableStatement on(Connection leg) throws SQLException;
    }

    /**
     * The statement the application gets from prepareCall: prepared on the write leg, and able to
     * prepare its CALL again on the leg a failover or a failback replaces that one with.
     */
    private CallableStatement newCall(
            final String label, final String sql, final CallPreparer preparer) throws SQLException {
        checkClosed();

        final LBCallableStatement.WriteLeg writeLeg =
                new LBCallableStatement.WriteLeg() {
                    public boolean is(final Connection leg) {
                        final EndpointConnManager mgr = connMgr;
                        return leg != null
                                && mgr != null
                                && mgr.boundConnection(SessionLeg.RW) == leg;
                    }

                    public Connection now() throws SQLException {
                        return getPhysicalConnForCmd(SessionLeg.RW);
                    }

                    public Connection forRun(final boolean ranBefore) throws SQLException {
                        if (ranBefore) {
                            commitWhatHoldsTheFailback();
                        }
                        final Endpoint endpoint = routeStatement(RouteTarget.TO_READ_WRITE, sql);
                        try {
                            return requireConnMgr().getPhyConn(endpoint);
                        } catch (SQLException e) {
                            throw closedOr(e);
                        }
                    }

                    public CallableStatement prepare(final Connection leg) throws SQLException {
                        return reportSelfAsOwner(preparer.on(leg));
                    }

                    public <T> T asCommand(final SqlExecution<T> command) throws SQLException {
                        return runRwCommand(label, command);
                    }
                };
        return runRwCommand(
                label,
                new SqlExecution<CallableStatement>() {
                    public CallableStatement run() throws SQLException {
                        // Before the prepare, which opens a transaction that would hold it.
                        failbackRwIfStatementAllows();
                        final LBCallableStatement cs =
                                new LBCallableStatement(LoadBalanceConnection.this, sql, writeLeg);
                        trackStatement(cs); // the URL query timeout, closed with the connection
                        return cs;
                    }
                });
    }

    /**
     * In autocommit core commits what a CallableStatement's run left open as the first step of its
     * next run, and leaves the alternative host at that commit. Here the failback is attempted
     * before the physical statement runs, so that commit is sent first - only while the write leg
     * is displaced and reports a transaction, which is when a failback waits on it.
     */
    private void commitWhatHoldsTheFailback() throws SQLException {
        if (sessionState.isAutoCommit()
                && connMgr != null
                && connMgr.isRwDisplaced()
                && connMgr.isRwTxReportedOpen()) {
            getPhysicalConnForCmd(SessionLeg.RW).commit();
        }
    }

    /**
     * Name this session as the owner of a physical statement whose result sets reach the
     * application, so {@code getConnection()} on it cannot be used to change one leg behind the
     * session's back.
     *
     * @param statement the physical statement
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
        return newCall(
                "prepareCall(resultSetType,resultSetConcurrency)",
                sql,
                new CallPreparer() {
                    public CallableStatement on(final Connection leg) throws SQLException {
                        return leg.prepareCall(sql, resultSetType, resultSetConcurrency);
                    }
                });
    }

    public CallableStatement prepareCall(
            final String sql,
            final int resultSetType,
            final int resultSetConcurrency,
            final int resultSetHoldability)
            throws SQLException {
        return newCall(
                "prepareCall(resultSetType,resultSetConcurrency,resultSetHoldability)",
                sql,
                new CallPreparer() {
                    public CallableStatement on(final Connection leg) throws SQLException {
                        return leg.prepareCall(
                                sql, resultSetType, resultSetConcurrency, resultSetHoldability);
                    }
                });
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
        // Unlike the other refusals, core checks the connection first here.
        checkClosed();
        throw LbExceptions.featureNotSupported();
    }

    public Clob createClob() throws SQLException {
        checkClosed();

        return runRwCommand(
                "createClob",
                new SqlExecution<Clob>() {
                    public Clob run() throws SQLException {
                        return getPhysicalConnForCmd(SessionLeg.RW).createClob();
                    }
                });
    }

    public Blob createBlob() throws SQLException {
        checkClosed();

        return runRwCommand(
                "createBlob",
                new SqlExecution<Blob>() {
                    public Blob run() throws SQLException {
                        return getPhysicalConnForCmd(SessionLeg.RW).createBlob();
                    }
                });
    }

    public byte[] lobNew(final int lobType) throws SQLException {
        checkClosed();

        return runRwCommand(
                "lobNew",
                new SqlExecution<byte[]>() {
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
                        new SqlExecution<Integer>() {
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
                        new SqlExecution<Integer>() {
                            public Integer run() throws SQLException {
                                return Integer.valueOf(
                                        extConn(getPhysicalConnForCmd(SessionLeg.RW), "lobRead")
                                                .lobRead(packedLobHandle, offset, buf, start, len));
                            }
                        })
                .intValue();
    }

    public NClob createNClob() throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    public SQLXML createSQLXML() throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            return super.isValid(timeout); // core's own argument check
        }
        if (isClosedWithoutLock()) {
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
            // Off: LoadBalanceSettings pins isWriteFailbackOnValidate() to false and no URL option
            // sets it. When on, the pool probes idle and borrowed connections, so this is a chance
            // for a displaced RW leg to self-correct without waiting for traffic. Gated on no
            // active transaction, because the application may also call isValid() mid-transaction.
            if (config.isWriteFailbackOnValidate() && !sessionState.isTransactionActive()) {
                attemptRwFailback();
            }
            return connMgr.isBoundRwSocketOpen();
        }

        return true;
    }

    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw LbExceptions.featureNotSupported();
    }

    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw LbExceptions.featureNotSupported();
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
        if (closed.get()) {
            super.setAutoGeneratedKeys(isGeneratedKeys); // core only sets a field, closed or not
            return;
        }
        try {
            applyAutoGeneratedKeys(isGeneratedKeys);
        } catch (SQLException wrapped) {
            throw unchecked(wrapped);
        }
    }

    private void applyAutoGeneratedKeys(final boolean isGeneratedKeys) throws SQLException {
        checkClosed();
        final Boolean previous = autoGeneratedKeys;
        autoGeneratedKeys = Boolean.valueOf(isGeneratedKeys);
        final LegOutcome outcome =
                forEachSessPhyConn(
                        new PhyConnAction() {
                            public void apply(Connection physical) throws SQLException {
                                extConn(physical, "setAutoGeneratedKeys")
                                        .setAutoGeneratedKeys(isGeneratedKeys);
                            }
                        });
        if (outcome.rwFailed()) {
            autoGeneratedKeys = previous;
        }
        outcome.rethrow();
    }

    /* ===== inherited from CUBRIDConnection: nothing may be left inherited =====*/
    /*
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
        return getClass().getName()
                + "(id="
                + connectionId
                + ", "
                + describeLegs(binding(), "unbound", ", ")
                + ")";
    }

    /**
     * {@code rw=<endpoint> read=<endpoint>}, naming where reads run; after a fallback onto the RW
     * connection, also the read endpoint they will return to.
     */
    private static String describeLegs(final LegBinding legs, final String none, final String sep) {
        final Endpoint rw = legs.getRwEndpoint();
        final Endpoint runsOn = legs.getReadRunsOn();
        final Endpoint left = legs.getReadEndpoint();
        return "rw="
                + (rw == null ? none : rw.getId())
                + sep
                + "read="
                + (runsOn == null ? none : runsOn.getId())
                + (legs.isReadOnRwFallback() && left != null
                        ? " (fallback from " + left.getId() + ")"
                        : "");
    }

    @Override
    protected void finalize() {
        try {
            close();
        } catch (Exception ignored) {
            // best effort, as in CUBRIDConnection.finalize()
        }
    }

    /**
     * Core's internal hooks for its own statement execution. They dereference {@code u_con}, which
     * is null here, and LB never runs that execution path, so reaching one means a public entry
     * point was left inherited: refuse loudly rather than throw a NullPointerException.
     */
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
     * CUBRID SHARD APIs, refused explicitly. A load-balance session spans several brokers of one HA
     * cluster and has no shard behind it, so there is no shard id or shard metadata to report. They
     * are declared rather than absent, so a caster gets a named "not supported" error instead of a
     * {@code NoSuchMethodError}.
     *
     * <p>The refusals come in two forms, and the difference is not a choice: an override cannot
     * widen the inherited {@code throws} clause. {@code CUBRIDConnection} declares {@code
     * getShardMetaData()} with {@code SQLException} and {@code isShard()} / {@code getShardId()}
     * without it, so only the first can refuse with a checked exception. The other two go through
     * {@link #unchecked(SQLException)}, which keeps the original as the cause. Do not unify them by
     * making {@code getShardMetaData()} unchecked; that would take away the one refusal a caller
     * can still catch as {@code SQLException}.
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
        throw LbExceptions.featureNotSupported();
    }

    /* ===== Wrapper =====*/
    public <T> T unwrap(Class<T> iface) throws SQLException {
        checkClosed();
        if (iface.isAssignableFrom(getClass())) {
            return iface.cast(this);
        }

        throw LbExceptions.cannotUnwrap(iface);
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        checkClosed();
        return super.isWrapperFor(iface);
    }

    /* ===== Internal =====*/
    private void checkClosed() throws SQLException {
        if (isClosedWithoutLock()) {
            throw LbExceptions.connectionClosed();
        }
    }

    /**
     * {@code e}, or core's closed-connection error when another thread's close() released the legs
     * after this call passed {@link #checkClosed()}: core reports that call as closed.
     */
    private SQLException closedOr(final SQLException e) {
        return closed.get() ? LbExceptions.connectionClosed() : e;
    }

    /**
     * Runs one {@link LBDatabaseMetaData} call that reaches the server through the failover
     * handler.
     *
     * <p>Only the calls that actually send a request need this - {@code getTables}, {@code
     * getColumns}, the key/index/privilege queries and the database version (product version, major
     * and minor numbers). The rest of the 173-method surface answers from local constants, so a
     * broker being down cannot affect them. The closure resolves the metadata handle <b>and</b>
     * runs the query, because a rebind must not leave the query on the handle of the failed
     * endpoint.
     *
     * <p>Not timed: it is a command, not a statement. Like a write it runs again only when
     * connecting failed; otherwise the leg is rebound, so the caller's retry lands on the new one.
     *
     * @param <T> the query's result type
     * @param label the metadata call name, for logging
     * @param query resolve-and-run, as one unit
     * @return the query's result
     * @throws SQLException if the query fails after the leg was recovered
     */
    <T> T runRwMetaQuery(final String label, final SqlExecution<T> query) throws SQLException {
        return getExecuteFailoverHandler()
                .executeCommandWithFailover(this, RouteTarget.TO_READ_WRITE, label, true, query);
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
     * <p>This protects <b>obtaining</b> the handle. The catalog queries the caller then runs on it
     * go through the handler separately, in {@link #runRwMetaQuery}.
     */
    DatabaseMetaData rwMetaData() throws SQLException {
        if (rwMetaForCmd != null) {
            return rwMetaForCmd;
        }

        rwMetaForCmd =
                runRwCommand(
                        "getMetaData",
                        new SqlExecution<DatabaseMetaData>() {
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
     * the call in error messages.
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
     * Applies a session property to the write leg, rebinding the leg when the broker is down.
     *
     * <p>Before this, a property setter was the one thing that could make a logical connection
     * unusable while a surviving write broker was right there: the failure was isolated per leg but
     * the leg was never recovered, so a pool resetting a returned connection ({@code
     * setAutoCommit}, {@code setReadOnly}, {@code setTransactionIsolation}) discarded it.
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
                            RouteTarget.TO_READ_WRITE,
                            "sessionProperty",
                            false,
                            new SqlExecution<Connection>() {
                                public Connection run() throws SQLException {
                                    Connection rw = getPhysicalConnForCmd(SessionLeg.RW);
                                    action.apply(rw);

                                    return rw;
                                }
                            });
        } catch (SQLException failure) {
            if (hasUncommittedRwWork() || !ExecuteFailoverHandler.isBrokerFailure(failure)) {
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

    /**
     * Applies {@code action} to the RW physical connection and, when the read leg has its own open
     * connection, to that one too. A read leg that is gone is not opened for this, as core never
     * reaches a read node: it gets the value from the session state when a read reopens it, and one
     * lost while applying is dropped ({@code applyToOpenReadLeg}). Single home for the "apply to
     * every physical connection backing this session" pattern shared by the property setters and
     * the {@code apply*()} re-application helpers, so the reapply target list lives in one place.
     * Caller owns the {@link #isSessionInitialized()} guard and any command-routing metrics.
     *
     * <p>The read leg is attempted even when the write leg failed, and the failures are returned
     * rather than thrown. The RW leg used to run unguarded, so anything it threw skipped the read
     * leg entirely — the read leg was collateral damage of a write-leg failure even though reads
     * would have kept working (the execute path absorbs a dead read endpoint by failing over, so a
     * property setter must not be the one thing that makes the connection unusable). Same shape as
     * {@code SessionPhysicalConnManager.endPhyTx}. A read-leg failure masked by a write-leg failure
     * is attached to it as suppressed, and logged.
     */
    private LegOutcome forEachSessPhyConn(final PhyConnAction action) {
        SQLException rwFailure = null;
        try {
            applyToWriteLegWithFailover(action);
        } catch (SQLException failure) {
            rwFailure = failure;
        }

        SQLException roFailure = null;
        try {
            requireConnMgr()
                    .applyToOpenReadLeg(
                            "a session property change",
                            new EndpointConnManager.SessionStateApplier() {
                                public void applyTo(final Connection physical) throws SQLException {
                                    action.apply(physical);
                                }
                            });
        } catch (SQLException failure) {
            roFailure = failure;
            if (rwFailure != null) {
                if (failure != rwFailure) {
                    rwFailure.addSuppressed(failure);
                }
                LbLog.warn(
                        LOGGER,
                        LbLog.conn(connectionId),
                        "LB SESSION PROP [RO]: read leg failed while the write leg was already"
                                + " failing; reporting the write failure | cause: "
                                + causeOf(failure));
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
        if (null == transactionIsolation || !isSessionInitialized()) {
            return LegOutcome.OK;
        }
        return forEachSessPhyConn(
                new PhyConnAction() {
                    public void apply(Connection physical) throws SQLException {
                        physical.setTransactionIsolation(transactionIsolation.intValue());
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
        if (!isSessionInitialized() || holdability == null) {
            return LegOutcome.OK;
        }
        return forEachSessPhyConn(
                new PhyConnAction() {
                    public void apply(Connection physical) throws SQLException {
                        physical.setHoldability(holdability.intValue());
                    }
                });
    }

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
                ownedLegs.add(physical);
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

    /**
     * Core completes all of its one connection's statements when a transaction ends there, so the
     * other legs run core's own commit or rollback to complete theirs. A read leg's end is passed
     * on only where core has it too: not when LB opens, closes or ends legs itself, all under the
     * manager's lock, and a rollback only once its statement fails to the application.
     */
    @Override
    protected void legTransactionEnding(final CUBRIDConnection leg, final boolean commit) {
        if (!isWriteLeg(leg)) {
            if (!commit) {
                // A failure rolls back only in autocommit; any other rollback here is LB's own.
                if (sessionState.isAutoCommit()) {
                    heldReadLegRollback = leg;
                }
                return;
            }
            final EndpointConnManager mgr = connMgr;
            if (mgr != null && Thread.holdsLock(mgr)) {
                return;
            }
        }
        endOtherLegs(leg, commit);
    }

    /**
     * Settles a read leg's rollback held by {@link #legTransactionEnding}. Core rolls back before
     * it reports a failure; a read LB ran again elsewhere, or failed over, did not fail there. A
     * statement settles once before it runs too, so that it passes on only its own.
     *
     * @param failureReported whether the statement's failure reaches the application
     */
    public void settleReadLegRollback(final boolean failureReported) {
        final CUBRIDConnection leg = heldReadLegRollback;
        heldReadLegRollback = null;
        if (leg != null && failureReported) {
            endOtherLegs(leg, false);
        }
    }

    private void endOtherLegs(final CUBRIDConnection leg, final boolean commit) {
        if (!legEndLock.tryLock()) {
            return;
        }
        try {
            for (Connection other : ownedLegs) {
                if (other != leg) {
                    endLegLikeCore(other, commit);
                }
            }
        } finally {
            legEndLock.unlock();
        }
    }

    /**
     * By address: the manager's record of the legs is behind a lock that core may be waiting on.
     */
    private boolean isWriteLeg(final CUBRIDConnection leg) {
        final Endpoint rw = binding().getRwEndpoint();
        try {
            final UConnection socket = leg.getUConnection();
            return rw != null
                    && socket != null
                    && rw.getId().equals(socket.getCasIp() + ":" + socket.getCasPort());
        } catch (SQLException unreadable) {
            return false;
        }
    }

    private void endLegLikeCore(final Connection leg, final boolean commit) {
        try {
            if (leg.isClosed()) {
                ownedLegs.remove(leg);
            } else if (commit) {
                leg.commit();
            } else {
                leg.rollback();
            }
        } catch (SQLException failed) {
            // The leg reports its own trouble when it is next used; the ending leg's end goes on.
            LbLog.fine(
                    LOGGER,
                    LbLog.conn(connectionId),
                    "LB LEG END: completing the other leg failed | cause: " + causeOf(failed));
        }
    }

    /**
     * Applies the current logical session state to a single freshly opened physical connection.
     * Invoked by {@link SessionPhysicalConnManager} for every connection it opens, so a connection
     * created mid-session (failover, recovery, failback, reopen) matches the session instead of
     * running at driver defaults. Mirrors the per-property setters used at initial binding;
     * nullable properties (isolation, lockTimeout, casChangeMode, autoGeneratedKeys, charset,
     * holdability) are applied only when set. {@code SessionPropertyTableTest} checks the list
     * against core's setters.
     */
    private void applySessionStateTo(final Connection physical) throws SQLException {
        claimValueObjectOwnership(physical);
        if (networkTimeoutExecutor != null) {
            physical.setNetworkTimeout(networkTimeoutExecutor, networkTimeout);
        }
        physical.setAutoCommit(sessionState.isAutoCommit());
        if (transactionIsolation != null) {
            physical.setTransactionIsolation(transactionIsolation.intValue());
        }
        if (lockTimeout != null) {
            extConn(physical, "setLockTimeout").setLockTimeout(lockTimeout.intValue());
        }
        if (casChangeMode != null) {
            extConn(physical, "setCASChangeMode").setCASChangeMode(casChangeMode.intValue());
        }
        if (autoGeneratedKeys != null) {
            extConn(physical, "setAutoGeneratedKeys")
                    .setAutoGeneratedKeys(autoGeneratedKeys.booleanValue());
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
        if (holdability != null) {
            physical.setHoldability(holdability.intValue());
        }
        runSessionSets(physical, sessionSetsSnapshot());
        rememberLegSession(physical, sessionOf(physical));
    }

    /**
     * Core reconnects a leg by itself and tells no one. When the CAS could not keep the server
     * session over it, that leg alone has none of the SETs LB ran on it, and its statements would
     * run under other settings than the other leg's. A read leg on its own connection is closed, to
     * reopen with the session state; the write leg runs the SETs again, once it holds no
     * transaction they would run inside.
     *
     * @param target the leg a statement is about to run on, or just ran on
     * @return whether a read leg was closed, so a read that just ran on it should run again
     */
    public boolean restoreSessionSetsAfterReconnect(final RouteTarget target) {
        final List<Replayed> sets = sessionSetsSnapshot();
        if (sets.isEmpty() || connMgr == null || !isSessionInitialized()) {
            return false; // nothing a new session would lack
        }
        final Connection rw = connMgr.boundConnection(SessionLeg.RW);
        final Connection leg =
                target == RouteTarget.TO_READ_ONLY ? connMgr.boundConnection(SessionLeg.RO) : rw;
        final byte[] session = leg == null ? null : sessionOf(leg);
        final byte[] applied = leg == null ? null : legSessions.get(leg);
        if (session == null || applied == null || Arrays.equals(session, applied)) {
            return false;
        }
        if (leg != rw) {
            try {
                leg.close();
            } catch (SQLException alreadyGone) {
                // A leg that is closed is replaced by the next read on it either way.
            }
            LbLog.warn(
                    LOGGER,
                    LbLog.conn(connectionId),
                    "LB SESSION RENEWED [RO]: core reconnected the read leg into a new server"
                            + " session, which has none of the session SETs; the leg is closed and"
                            + " reopens with them");
            return true;
        }
        if (connMgr.isRwTxOpenOnServer()) {
            return false;
        }
        // Remembered whether or not the SETs run: a SET the server now refuses is refused again.
        rememberLegSession(rw, session);
        try {
            runSessionSets(rw, sets);
            LbLog.warn(
                    LOGGER,
                    LbLog.conn(connectionId),
                    "LB SESSION RENEWED [RW]: core reconnected the write leg into a new server"
                            + " session; the session SETs ran on it again | sets="
                            + sets.size());
        } catch (SQLException failed) {
            LbLog.warn(
                    LOGGER,
                    LbLog.conn(connectionId),
                    "LB SESSION RENEWED [RW]: core reconnected the write leg into a new server"
                            + " session and running the session SETs on it again failed; it runs"
                            + " without them | cause: "
                            + causeOf(failed));
        }
        return false;
    }

    private void rememberLegSession(final Connection leg, final byte[] session) {
        if (session != null) {
            legSessions.put(leg, session);
        }
    }

    /** The server session {@code leg} is in, or null when it has no core connection to ask. */
    private static byte[] sessionOf(final Connection leg) {
        try {
            final UConnection socket =
                    leg instanceof CUBRIDConnection
                            ? ((CUBRIDConnection) leg).getUConnection()
                            : null;
            return socket == null ? null : socket.getSessionId();
        } catch (SQLException unreadable) {
            return null;
        }
    }

    /**
     * Core prepared every open statement on its one session before a session {@code SET}, and the
     * server keeps what a statement was compiled under. One with no physical statement yet on a leg
     * it can run on is prepared there now, before the SET reaches that leg, or its first run there
     * would compile under the new setting.
     *
     * @param sql the statement about to run
     */
    public void beforeSessionSet(final String sql) {
        if (!SessionSetStatement.parse(sql).changesSession()) {
            return;
        }
        final List<Statement> open;
        synchronized (openStatements) {
            open = new ArrayList<Statement>(openStatements);
        }
        for (Statement each : open) {
            if (each instanceof LBPreparedStatement) {
                final LBPreparedStatement prepared = (LBPreparedStatement) each;
                prepared.prepareOnWriteLeg();
                prepared.prepareOnReadLeg();
            }
        }
    }

    /**
     * Records what a statement that just succeeded changed in its session. A SQL {@code SET} ran
     * only on the leg it was routed to (RW): a session-scoped one is replayed on the read leg now
     * and on every leg opened later; one the read leg cannot be given keeps reads on RW until a
     * later statement undoes it or the connection closes. Variables an {@code INTO/TO} clause
     * created on RW are remembered so reads naming them follow, until a {@code DROP VARIABLE}
     * removes them. Its leg is where a following {@code ROW_COUNT()} runs.
     *
     * @param sql the statement as the application issued it
     * @param ranOn the leg it ran on
     */
    public void afterStatementSucceeded(final String sql, final RouteTarget ranOn) {
        afterStatementSucceeded(sql, ranOn, null);
    }

    /**
     * {@link #afterStatementSucceeded(String, Router.RouteTarget)} for a statement that took values
     * from {@code ?}. A login or a lock timeout that did runs on the other legs with them, as one
     * written out runs from its text.
     *
     * @param sql the statement as the application issued it
     * @param ranOn the leg it ran on
     * @param withValues the statement with the values of this run, or null when it kept none
     */
    public void afterStatementSucceeded(
            final String sql, final RouteTarget ranOn, final BoundRun withValues) {
        sessionState.setLastStatementOnReadLeg(ranOn == RouteTarget.TO_READ_ONLY);
        if (ranOn == RouteTarget.TO_READ_WRITE) {
            sessionState.addSessionVariables(IntoVariables.assignedBy(sql));
            sessionState.removeSessionVariables(IntoVariables.droppedBy(sql));
        }
        final SessionSetStatement set = SessionSetStatement.parse(sql);
        if (!set.changesSession()) {
            return;
        }
        final String boundKey = withValues == null ? null : set.getBoundKey();
        if (boundKey != null) {
            replaySessionSet(
                    sql, Collections.singletonMap(boundKey, new Replayed(sql, withValues)));
        } else {
            if (!set.getReplay().isEmpty()) {
                final Map<String, Replayed> written = new LinkedHashMap<String, Replayed>();
                for (Map.Entry<String, String> each : set.getReplay().entrySet()) {
                    written.put(each.getKey(), new Replayed(each.getValue(), null));
                }
                replaySessionSet(sql, written);
            }
            pinReads(sql, set.getPins(), "a session change the read leg does not have ran on RW");
        }
        unpinReads(set.getUnpins(), sqlForLog(sql));
        if (!set.getNonSessionParams().isEmpty()) {
            LbLog.warn(
                    LOGGER,
                    LbLog.conn(connectionId),
                    "LB SESSION SET: "
                            + set.getNonSessionParams()
                            + " is not a session parameter; it changed the RW node only and read"
                            + " nodes keep their own value, so reads are not pinned"
                            + sqlForLog(sql));
        }
    }

    /**
     * Records what a statement that failed on RW may have changed. CUBRID keeps the SETs and
     * variables a multi-statement string made before the failing statement; which ones is not
     * known, so each setting the string touches keeps reads on RW until a later statement sets it
     * again, and each variable it assigns is read on RW.
     *
     * @param sql the statement as the application issued it
     */
    public void afterStatementFailed(final String sql) {
        pinReads(
                sql,
                SessionSetStatement.parse(sql).getPinsIfFailed(),
                "a statement string with a session change failed; the SETs before the failing"
                        + " statement may have run on RW");
        // Likewise a variable an INTO before the failing statement created. Such a string runs on
        // RW: an INTO sends it there.
        sessionState.addSessionVariables(IntoVariables.assignedIfFailed(sql));
    }

    /**
     * Records a batch the server answered with a {@link java.sql.BatchUpdateException}. Each entry
     * whose count is not {@link Statement#EXECUTE_FAILED} changed the session as it would have
     * alone, and is the statement a following {@code ROW_COUNT()} reports on; a batch in which
     * every entry failed leaves that with the statement before it, as on core.
     *
     * @param sqls the batch entries, in order
     * @param counts the update counts the server answered, possibly {@code null}
     * @param ranOn the leg the batch ran on
     */
    public void afterBatchAnswered(
            final List<String> sqls, final int[] counts, final RouteTarget ranOn) {
        final int answered = counts == null ? 0 : Math.min(sqls.size(), counts.length);
        for (int i = 0; i < answered; i++) {
            if (counts[i] == Statement.EXECUTE_FAILED) {
                afterStatementFailed(sqls.get(i));
            } else {
                afterStatementSucceeded(sqls.get(i), ranOn);
            }
        }
    }

    private void replaySessionSet(final String sql, final Map<String, Replayed> replay) {
        synchronized (sessionSets) {
            for (Map.Entry<String, Replayed> each : replay.entrySet()) {
                sessionSets.remove(each.getKey());
                sessionSets.put(each.getKey(), each.getValue());
            }
        }
        if (connMgr != null && isSessionInitialized()) {
            final Collection<Replayed> statements = replay.values();
            try {
                connMgr.applyToOpenReadLeg(
                        "session SET",
                        new EndpointConnManager.SessionStateApplier() {
                            public void applyTo(final Connection physical) throws SQLException {
                                runSessionSets(physical, statements);
                            }
                        });
            } catch (SQLException rejected) {
                // A reopened leg would reject it again: keep reads where the SET took effect.
                pinReads(
                        sql,
                        replay.keySet(),
                        "the read leg rejected a SET that ran on RW (" + causeOf(rejected) + ")");
                return;
            }
        }
        // The read leg has these settings now, or gets them when it opens.
        unpinReads(replay.keySet(), sqlForLog(sql));
    }

    private void pinReads(final String sql, final Collection<String> reasons, final String what) {
        if (reasons.isEmpty() || sessionState.pinReadsToRw(reasons).isEmpty()) {
            return;
        }
        LbLog.warn(
                LOGGER,
                LbLog.conn(connectionId),
                "LB SESSION PIN: "
                        + what
                        + "; this session's reads stay on RW until a later statement undoes it or"
                        + " the connection closes | reasons="
                        + sessionState.getReadPinReasons()
                        + sqlForLog(sql));
    }

    private void unpinReads(final Collection<String> reasons, final String cause) {
        if (!sessionState.unpinReads(reasons)) {
            return;
        }
        LbLog.info(
                LOGGER,
                LbLog.conn(connectionId),
                "LB SESSION UNPIN: the last change that kept reads on RW was undone; reads are"
                        + " distributed again"
                        + cause);
    }

    /**
     * A JDBC setter now owns {@code key}'s setting on both legs: drop the SQL {@code SET} of it, or
     * a leg opened later would get the older SQL value on top of the setter's, and release a pin a
     * bound SQL value of it left.
     */
    private void forgetSessionSet(final String key) {
        synchronized (sessionSets) {
            sessionSets.remove(key);
        }
        unpinReads(Collections.singleton(key), " | by=a JDBC setter");
    }

    private List<Replayed> sessionSetsSnapshot() {
        synchronized (sessionSets) {
            return new ArrayList<Replayed>(sessionSets.values());
        }
    }

    private static void runSessionSets(
            final Connection physical, final Collection<Replayed> statements) throws SQLException {
        for (Replayed each : statements) {
            if (each.withValues != null) {
                each.withValues.on(physical);
                continue;
            }
            Statement statement = physical.createStatement();
            try {
                statement.execute(each.sql);
            } finally {
                statement.close();
            }
        }
    }

    /**
     * A statement run on another connection with the values one run of it was given. The values are
     * kept as that run had them, a login's password among them, for the legs opened later.
     */
    public interface BoundRun {
        void on(Connection physical) throws SQLException;
    }

    /** A session SET kept for the other legs: its text, or the statement with a run's values. */
    private static final class Replayed {
        private final String sql;
        private final BoundRun withValues;

        Replayed(final String sql, final BoundRun withValues) {
            this.sql = sql;
            this.withValues = withValues;
        }
    }

    /**
     * Whether a statement that failed on the read leg may run once more on RW. The read-only broker
     * refuses a write hidden from the classifier (a view over NEXTVAL, or over a routine that
     * writes, which surfaces as -889), and a read node has no LOB files to read; either way the
     * failed statement changed nothing, so the retry runs it once. Only in autocommit, and not for
     * an explicit TO_RO hint: that read leg was the application's choice.
     *
     * @param target where the failed execution was routed
     * @param sql the statement as the application issued it
     * @param failure the read leg's failure
     * @return whether the caller should run the statement again on RW
     */
    public boolean mayRetryRejectedReadOnRw(
            final RouteTarget target, final String sql, final SQLException failure) {
        return target == RouteTarget.TO_READ_ONLY
                && sessionState.isAutoCommit()
                && !sessionState.isTransactionActive()
                && readLegRefusal(failure) != null
                && !HintParser.parse(sql).hasTargetHint();
    }

    /**
     * Marks the RW run of a refused read until {@link #endRejectedReadRetry()}.
     *
     * @param sql the statement being run again
     * @param failure the read leg's refusal
     */
    public void beginRejectedReadRetry(final String sql, final SQLException failure) {
        final String reason = readLegRefusal(failure);
        rejectedReadReason = reason != null ? reason : FallbackReason.RO_WRITE_REJECTED;
        LbLogDedup.info(
                LOGGER,
                LbLog.conn(connectionId),
                // The key is written too, with what was collapsed: no statement under lbLogSql=off.
                "RO REJECTED|"
                        + failure.getErrorCode()
                        + (LbLogConfig.SQL_OFF.equals(config.getLbLogConfig().getSqlMode())
                                ? ""
                                : "|" + SqlLiteralMasker.mask(sql, 200)),
                "LB RO REJECTED: the read leg could not serve a statement classified READ ("
                        + failure.getErrorCode()
                        + "); running it on RW"
                        + sqlForLog(sql));
    }

    public void endRejectedReadRetry() {
        rejectedReadReason = null;
    }

    /**
     * Gives the routing records the decision of the statement the execute path is about to run,
     * until {@link #endStatementRoute()}, so they name the rule that decided.
     *
     * @param decision the statement's routing decision
     */
    public void beginStatementRoute(final Router.RouteDecision decision) {
        statementRoute = decision;
    }

    public void endStatementRoute() {
        statementRoute = null;
    }

    /** A failure for a log line, a server error with its message only under lbLogSql=raw. */
    private String causeOf(final SQLException failure) {
        return LbLog.cause(failure, config.getLbLogConfig().isSqlRaw());
    }

    /** Why the read leg cannot serve the failed read, or {@code null} if the failure is its own. */
    private static String readLegRefusal(final SQLException failure) {
        for (SQLException e = failure; e != null; e = e.getNextException()) {
            final int code = e.getErrorCode();
            if (code == ER_DB_NO_MODIFICATIONS || code == ER_SP_EXECUTE_ERROR) {
                return FallbackReason.RO_WRITE_REJECTED;
            }
            if (code == ER_ES_FILE_NOT_FOUND
                    || (code == ER_TP_CANT_COERCE && namesLobDomain(e.getMessage()))) {
                return FallbackReason.RO_LOB_UNAVAILABLE;
            }
        }
        return null;
    }

    /** The domain names are not translated, so this holds for a localized message too. */
    private static boolean namesLobDomain(final String message) {
        if (message == null) {
            return false;
        }
        final String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("\"clob\"") || lower.contains("\"blob\"");
    }

    /**
     * Why a read the session would otherwise send to the read leg runs on RW, from the rule that
     * decided, or {@code reason}. A read pin is named for a READ only: any other statement runs on
     * RW with or without it.
     */
    private static String readFallbackReason(
            final Router.RouteDecision decision, final String reason) {
        if (!FallbackReason.NONE.equals(reason)) {
            return reason;
        }
        switch (decision.getRule()) {
            case READ_PIN:
                return decision.getSql().getClassification() == SqlClassification.READ
                        ? FallbackReason.SESSION_SET_PIN
                        : reason;
            case SESSION_VARIABLE:
                return FallbackReason.SESSION_VARIABLE;
            case RW_TRANSACTION_OPEN:
                return FallbackReason.RW_TX_OPEN;
            default:
                return reason;
        }
    }

    private String sqlForLog(final String sql) {
        final LbLogConfig logConfig = config.getLbLogConfig();
        final String mode = logConfig.getSqlMode();
        if (LbLogConfig.SQL_OFF.equals(mode)) {
            return "";
        }
        return " | sql="
                + (LbLogConfig.SQL_RAW.equals(mode)
                        ? SqlLiteralMasker.raw(sql, logConfig.getSqlMaxLen())
                        : SqlLiteralMasker.mask(sql, logConfig.getSqlMaxLen()));
    }

    private EndpointSelection selectionForTarget(final RouteTarget target) throws SQLException {
        if (target == RouteTarget.TO_READ_WRITE) {
            return selectRwEndpoint();
        }
        if (target == RouteTarget.TO_READ_ONLY) {
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
            throw closedOr(LbExceptions.sessionNotInitialized("role=RW"));
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
            throw closedOr(LbExceptions.sessionNotInitialized("role=RO"));
        }

        Endpoint rwEndpoint = currentOrManagerEndpoint(SessionLeg.RW);

        // Session mode binds the read target once at session init; a master-role or RW-only
        // session binds the RO role onto the RW endpoint, so just use the bound read endpoint.
        if (rwEndpoint != null && rwEndpoint.getId().equals(roEndpoint.getId())) {
            return new EndpointSelection(roEndpoint, FallbackReason.NONE);
        }

        // A roOnRw session keeps its read endpoint for failback but reads on the RW connection,
        // where the level holds as on core.
        if (!binding().isReadOnRwFallback()) {
            warnIfIsolationCannotHoldAcrossNodes(roEndpoint);
        }

        return withPhysicalFallbackReason(roEndpoint);
    }

    /**
     * Says once per session that the requested isolation level does not survive a distributed read.
     *
     * <p>Reaching here means no transaction is active (a transaction pins every read to RW) and the
     * read endpoint is a different node from the write leg. Such a read is its own transaction on
     * that node, so {@code REPEATABLE READ} and {@code SERIALIZABLE} hold <b>within one node</b>
     * only - read distribution assumes {@code READ COMMITTED} with autocommit on ({@link
     * cubrid.jdbc.lb.route.Router} never looks at the level, and {@code setTransactionIsolation}
     * accepts any level and propagates it to both legs).
     *
     * <p>A warning rather than a refusal: the combination is legitimate for a single-statement
     * read, and refusing it would break an application that sets the level once at startup and
     * never relies on cross-statement repeatability. What was wrong was staying silent, which left
     * "REPEATABLE READ is set, why do the values differ" with nothing to go on.
     *
     * @param readEndpoint the node this read is about to run on
     */
    private void warnIfIsolationCannotHoldAcrossNodes(final Endpoint readEndpoint) {
        if (isolationSpreadWarned) {
            return;
        }
        final int isolation = sessionIsolation();
        if (isolation != TRANSACTION_REPEATABLE_READ && isolation != TRANSACTION_SERIALIZABLE) {
            return;
        }

        isolationSpreadWarned = true;
        final String level =
                isolation == TRANSACTION_SERIALIZABLE ? "SERIALIZABLE" : "REPEATABLE_READ";
        LbLogDedup.warn(
                LOGGER,
                LbLog.conn(connectionId),
                "ISOLATION|" + level,
                "LB ISOLATION: isolation="
                        + level
                        + " but this read runs on "
                        + readEndpoint.getId()
                        + ", a different node from the write leg -- a distributed read is its own"
                        + " snapshot there, so the level holds within one node only. Read"
                        + " distribution assumes READ COMMITTED with autocommit on; use /*+ TO_RW"
                        + " */ on the statements that must see the level, or readWeight master:1"
                        + " alone to stop distributing");
    }

    /**
     * The level the application gave this session, by the JDBC setter or by a SQL SET, whichever
     * came later: the setter drops the SQL SET it replaces. 0 when it gave none, or one a read on
     * another node honours.
     */
    private int sessionIsolation() {
        final Replayed bySql;
        synchronized (sessionSets) {
            bySql = sessionSets.get(SessionSetStatement.ISOLATION_LEVEL_KEY);
        }
        if (bySql != null) {
            return SessionSetStatement.isolationAboveReadCommitted(bySql.sql);
        }
        final Integer bySetter = transactionIsolation;
        return bySetter == null ? 0 : bySetter.intValue();
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
    private void maybeRestoreRoBinding(final RouteTarget target) {
        if (target != RouteTarget.TO_READ_ONLY) {
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
        long now = System.nanoTime();
        if (attemptedWithin(
                lastReadFailbackAttemptNanos, now, config.getReadFailbackProbeIntervalMs())) {
            return;
        }
        if (!connMgr.isReadDisplaced()) {
            lastReadFailbackAttemptNanos = Long.valueOf(now);
            return; // at home: nothing to restore, and no statement to scan for a cursor
        }
        if (hasOpenCursorOn(connMgr.boundConnection(SessionLeg.RO))) {
            return; // retried on a later read, once the cursor is closed
        }
        lastReadFailbackAttemptNanos = Long.valueOf(now);

        try {
            Endpoint restoredRo = connMgr.restoreRoIfRecovered();
            if (restoredRo != null) {
                invalidateMetaDataCache();
                routingMetrics.recordRoFailback(restoredRo);
            }
        } catch (SQLException ignored) {
            // RO still unavailable; keep serving reads on RW and retry on a later read.
        }
    }

    /**
     * RW failback trigger on the statement path. Fires for every statement, a read on its own leg
     * included: a session that only reads would otherwise keep a CAS of the sibling RW broker until
     * its next write.
     *
     * <p>Only for a session with no transaction in progress. A manual-commit session is
     * transaction-pinned even between commits (see {@code
     * SessionRoutingState.onTransactionBoundary}), so for it this is always a no-op and the {@code
     * commit()}/{@code rollback()} hook does the work.
     */
    private void failbackRwIfStatementAllows() {
        if (sessionState.isTransactionActive()) {
            return;
        }
        attemptRwFailback();
    }

    /**
     * Whether a result set the application has not closed lives on {@code physical}. Failback
     * closes the connection it leaves, and every cursor on it with it.
     */
    private boolean hasOpenCursorOn(final Connection physical) {
        if (physical == null) {
            return false;
        }

        List<Statement> snapshot;
        synchronized (openStatements) {
            snapshot = new ArrayList<Statement>(openStatements);
        }
        for (Statement each : snapshot) {
            Connection underCursor = null;
            if (each instanceof LBPreparedStatement) {
                underCursor = ((LBPreparedStatement) each).openCursorConnection();
            } else if (each instanceof LBStatement) {
                underCursor = ((LBStatement) each).openCursorConnection();
            } else if (each instanceof LBCallableStatement) {
                underCursor = ((LBCallableStatement) each).openCursorConnection();
            }
            if (underCursor == physical) {
                return true;
            }
        }

        return false;
    }

    /**
     * Whether the attempt at {@code lastNanos} is less than {@code intervalMs} before {@code
     * nowNanos}. Both are {@code System.nanoTime()} readings: on the wall clock, one set back would
     * hold the failback until it caught up.
     */
    static boolean attemptedWithin(
            final Long lastNanos, final long nowNanos, final long intervalMs) {
        return lastNanos != null
                && nowNanos - lastNanos.longValue() < TimeUnit.MILLISECONDS.toNanos(intervalMs);
    }

    /**
     * Attempts RW failback at a caller-verified safe boundary: no statement in flight and no
     * transaction work since the last commit/rollback. Throttled per session.
     */
    private void attemptRwFailback() {
        if (!sessionInitialized || connMgr == null) {
            return;
        }
        if (!config.isWriteFailbackEnabled()) {
            return;
        }

        long now = System.nanoTime();
        if (attemptedWithin(
                lastRwFailbackAttemptNanos, now, config.getWriteFailbackProbeIntervalMs())) {
            return;
        }
        if (!connMgr.isRwDisplaced()) {
            lastRwFailbackAttemptNanos = Long.valueOf(now);
            return; // on the master: nothing to restore, and no statement to scan for a cursor
        }
        // Core ends the autocommit transaction an OID write or a CALL left open when the next
        // statement on that connection completes. Closing the connection first loses that work.
        if (connMgr.isRwTxReportedOpen()) {
            return; // retried at a later trigger, once it has ended
        }
        final Connection rw = connMgr.boundConnection(SessionLeg.RW);
        if (hasOpenCursorOn(rw)) {
            return; // retried at a later trigger, once the cursor is closed
        }
        lastRwFailbackAttemptNanos = Long.valueOf(now);

        try {
            Endpoint restoredRw = connMgr.restoreRwIfRecovered();
            if (restoredRw != null) {
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
                RouteTarget.TO_READ_WRITE,
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
