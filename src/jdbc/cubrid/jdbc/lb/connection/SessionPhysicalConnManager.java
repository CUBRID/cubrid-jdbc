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

import cubrid.jdbc.driver.CUBRIDConnection;
import cubrid.jdbc.driver.CUBRIDDriver;
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.lb.FallbackReason;
import cubrid.jdbc.lb.LbExceptions;
import cubrid.jdbc.lb.LbProps;
import cubrid.jdbc.lb.SessionLeg;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.JdbcPhyConnSpec;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.failover.PhysicalRecoveryContext;
import cubrid.jdbc.lb.failover.PhysicalRecoveryResult;
import cubrid.jdbc.lb.failover.UnreachableEndpoints;
import cubrid.jdbc.lb.log.LbLog;
import cubrid.jdbc.lb.log.LbLogDedup;
import cubrid.jdbc.lb.metrics.MetricsRegistry;
import cubrid.jdbc.lb.state.RecoveryBackoff;
import cubrid.jdbc.lb.statement.PreparedSql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Session-mode physical connection manager: opens one {@link Connection} per bound RW/RO {@link
 * Endpoint} using {@link JdbcPhyConnSpec} from {@link LoadBalanceSettings#buildPhysicalJdbcSpec}.
 *
 * <p>Owns everything physical about one logical session: the bound RW/read endpoints and their
 * connections, the owner-scoped physical prepared-statement cache, the tiered read ladder used for
 * failover (home &rarr; same-role sibling &rarr; cross-role &rarr; roOnRw), and lazy failback to
 * the home endpoints once they recover.
 */
public final class SessionPhysicalConnManager implements EndpointConnManager {
    private static final Logger LOGGER =
            Logger.getLogger(SessionPhysicalConnManager.class.getName());

    // prepByKey growth signals an app-side PreparedStatement leak (see the prepByKey comment). Warn
    // once past this bound so the leak is diagnosable; no automatic eviction.
    private static final int PREP_CACHE_WARN_THRESHOLD = 1024;

    /**
     * Entries between prepared-statement cache snapshots. Coarse enough that a healthy session (a
     * fixed set of reused statements) never reaches the first one, fine enough that four snapshots
     * appear before the oversize warning does.
     */
    private static final int PREP_CACHE_SNAPSHOT_EVERY = 256;

    /**
     * Hands out read-ladder tier rotation offsets, so displaced read legs spread across a
     * multi-node tier instead of all taking its first node. One counter <b>per home endpoint</b>,
     * keyed by endpoint id.
     *
     * <p>A single JVM-wide counter does not work here. Every session takes an offset when it binds,
     * and the weighted home assignment is itself a round robin, so sessions sharing a home occur
     * every P-th in bind order (P = the weight pattern's period) and their offsets are congruent
     * mod P. Then {@code offset % tierSize} is constant whenever {@code tierSize} divides P, and
     * the whole displaced share lands on one node again.
     *
     * <p>Keying by home removes the aliasing: the sessions an endpoint's outage displaces are
     * exactly the ones drawing from that endpoint's counter, so they hold consecutive offsets and
     * rotate evenly over the surviving siblings.
     */
    private static final java.util.concurrent.ConcurrentHashMap<
                    String, java.util.concurrent.atomic.AtomicInteger>
            LADDER_ROTATION =
                    new java.util.concurrent.ConcurrentHashMap<
                            String, java.util.concurrent.atomic.AtomicInteger>();

    public static final String RO_PHYSICAL_FAILOVER_REASON = FallbackReason.RO_PHYSICAL;
    public static final JdbcConnectionFactory DRIVER_MANAGER =
            new JdbcConnectionFactory() {
                public Connection getConnection(final String url, final Properties info)
                        throws SQLException {
                    return DriverManager.getConnection(url, info);
                }
            };

    private final String logicalJdbcUrl;
    private final Properties clientInfo;
    private final LoadBalanceSettings config;
    private final JdbcConnectionFactory connectionFactory;
    private final Map<String, Connection> connsByEpId = new HashMap<String, Connection>();
    // CAS identity of each open physical connection, as of the last time LB applied the session
    // state to it. Core reconnects a leg on its own - UConnection.checkReconnect() runs before
    // every
    // request and reconnectWorker() re-opens the socket - and restores only isolation and lock
    // timeout, so CAS-side state LB had set (casChangeMode) would be silently lost. The broker
    // hands
    // out a fresh casId/casProcessId on every connect, which is the only signal LB gets that this
    // happened. See LB-Pending-Issues ISSUE-7.
    private final Map<String, String> casIdentityByEpId = new HashMap<String, String>();
    // Physical PreparedStatement cache keyed by endpointId|owner|normalizedSql. Not LRU-bounded on
    // purpose: a logical PS re-fetches its physical statement here on every execute and hands the
    // caller the physical ResultSet directly, so closing a still-referenced entry would destroy a
    // live ResultSet. Ownership bounds it instead - every production entry is owner-scoped to a
    // live
    // logical PS and removed by closePrepForOwner when that PS closes. Unbounded growth therefore
    // means the application is leaking PreparedStatements, which leaks the physical statements
    // anyway; we warn past PREP_CACHE_WARN_THRESHOLD rather than evict.
    private final Map<String, PreparedStatement> prepByKey =
            new HashMap<String, PreparedStatement>();
    private boolean prepCacheWarned;
    // Owner segment for prepares with no owning logical PS (direct/legacy callers): a
    // session-shared cache entry. Logical PreparedStatements pass their own unique owner id.
    private static final String SHARED_PREP_OWNER = "";
    private Endpoint sessRwEp;
    private Endpoint sessRoEp;
    // Immutable weighted read assignment (the ratio anchor). Failover moves sessRoEp/roOnRw but
    // never homeReadEp; failback targets it, so the readWeight distribution self-heals without a
    // pool recycle.
    private Endpoint homeReadEp;
    // Intended RW anchor: the master RW asked for at bind time, kept even when the bind or a later
    // failover landed on a sibling RW broker. RW failback targets it; RW failover never moves it.
    // Not final-by-session on purpose: a future master-aware rebind updates this in place.
    private Endpoint homeRwEp;
    /** Tier rotation offset for the read ladder; -1 until this session first needs a ladder. */
    private int ladderOffset = -1;

    private EndpointTopology sessTopo;
    private boolean roOnRw;
    private final UnreachableEndpoints unreachFilter = new UnreachableEndpoints();
    // Pool-shared probe scheduler for backoff-bypass recovery; injected via openFromSettings.
    private final RecoveryBackoff backoff;
    // Re-applies the logical session state to every freshly opened physical connection; set by the
    // owning LoadBalanceConnection. Null in bare-manager unit tests (no re-application needed).
    private SessionStateApplier sessionStateApplier;
    // Identifier this manager's log records are attributed to; injected by the owning
    // LoadBalanceConnection. Null in bare-manager unit tests, which renders as the no-context mark.
    private volatile String logContext;

    private enum FailoverGroup {
        RW,
        RO
    }

    public SessionPhysicalConnManager(
            final String logicalJdbcUrl,
            final Properties clientInfo,
            final LoadBalanceSettings loadBalanceSettings,
            final JdbcConnectionFactory connectionFactory) {
        // Legacy/test convenience: an unshared backoff at the default interval. The real path
        // (openFromSettings) injects the pool-shared instance from RecoveryBackoffRegistry.
        this(
                logicalJdbcUrl,
                clientInfo,
                loadBalanceSettings,
                connectionFactory,
                new RecoveryBackoff(LoadBalanceSettings.DEFAULT_RT_RECOVERY_PROBE_INTERVAL_MS));
    }

    public SessionPhysicalConnManager(
            final String logicalJdbcUrl,
            final Properties clientInfo,
            final LoadBalanceSettings loadBalanceSettings,
            final JdbcConnectionFactory connectionFactory,
            final RecoveryBackoff backoff) {
        if (logicalJdbcUrl == null) {
            throw new IllegalArgumentException("logicalJdbcUrl must not be null");
        }

        if (loadBalanceSettings == null) {
            throw new IllegalArgumentException("LoadBalanceSettings must not be null");
        }

        if (connectionFactory == null) {
            throw new IllegalArgumentException("JdbcConnectionFactory must not be null");
        }

        if (backoff == null) {
            throw new IllegalArgumentException("RecoveryBackoff must not be null");
        }
        this.logicalJdbcUrl = logicalJdbcUrl;
        this.clientInfo = LbProps.copy(clientInfo);
        this.config = loadBalanceSettings;
        this.connectionFactory = connectionFactory;
        this.backoff = backoff;
    }

    public void setSessionStateApplier(final SessionStateApplier applier) {
        this.sessionStateApplier = applier;
    }

    public void setLogContext(final String context) {
        this.logContext = context;
    }

    /**
     * Moves the read leg on or off the RW connection, recording the transition.
     *
     * <p>This state is the difference between "reads are distributed by {@code readWeight}" and
     * "every read in this session runs on the write connection". The observable effect is reads
     * appearing on the master, which looks exactly like a routing defect - a broker whose CAS slots
     * were exhausted was once investigated as one. Recording the transition removes that whole
     * class of misdiagnosis.
     *
     * <p>Only real transitions are recorded; teardown uses {@link #clearRoOnRw()}, which has no
     * session to attribute a transition to.
     *
     * @param value whether reads now run on the RW connection
     * @param rwEpId the RW endpoint the read leg is (or was) sharing
     * @param reason short cause, for the record
     */
    private void setRoOnRw(final boolean value, final String rwEpId, final String reason) {
        if (roOnRw == value) {
            return;
        }
        roOnRw = value;

        LbLogDedup.warn(
                LOGGER,
                logContext,
                "ROONRW|" + value + "|" + rwEpId + "|" + reason,
                value
                        ? "LB READ ON RW: reads moved onto the RW connection at "
                                + rwEpId
                                + " ("
                                + reason
                                + ") -- this is the fallback rung, not a routing"
                                + " decision; readWeight distribution is suspended for this session"
                                + " until the read leg fails back"
                        : "LB READ ON RW: reads left the fallback rung at "
                                + rwEpId
                                + " ("
                                + reason
                                + ") -- readWeight distribution resumes");
    }

    /**
     * Drops the flag as part of tearing a session down or rebinding it from scratch. Not a
     * transition: there is no bound session for the record to describe, and the rebind that follows
     * reports its own placement.
     */
    private void clearRoOnRw() {
        roOnRw = false;
    }

    public synchronized void bindSession(final Endpoint rwEndpoint, final Endpoint roEndpoint)
            throws SQLException {
        bindSession(rwEndpoint, roEndpoint, null);
    }

    public synchronized void bindSession(
            final Endpoint rwEndpoint, final Endpoint roEndpoint, final EndpointTopology topology)
            throws SQLException {
        if (rwEndpoint == null) {
            throw LbExceptions.internalState("missing RW endpoint");
        }

        if (roEndpoint == null) {
            throw LbExceptions.internalState("missing RO endpoint");
        }

        closePrepLocked();
        closeConnsLocked();
        clearRoOnRw();
        sessTopo = topology;
        homeReadEp = roEndpoint;
        homeRwEp = rwEndpoint;

        Connection rwConn = null;
        boolean bound = false;

        try {
            List<Endpoint> rwOrder = failoverOrder(rwEndpoint, topology, FailoverGroup.RW);

            BindResult rwBind = openFirstReachable(rwOrder, "RW", unreachableIds(sessTopo), 0);
            rwConn = rwBind.connection;

            Endpoint boundRw = rwBind.endpoint;
            warnIfRwOffMaster(rwEndpoint, boundRw);

            // The read leg uses the SAME tiered ladder as failover/failback (home -> same-role
            // siblings -> cross-role, each tier rotated per session). With the flat failoverOrder,
            // which is declaration order over all RO endpoints, a replica-home session displaced at
            // bind time landed on a SLAVE before trying its sibling replica, and every displaced
            // session took the same first node.
            List<Endpoint> roOrder = readCandidateOrder(roEndpoint, topology);

            Connection roConn = null;

            Endpoint boundRo = null;

            SQLException lastRoFailure = null;

            try {
                BindResult roBind = openFirstReachable(roOrder, "RO", unreachableIds(sessTopo), 0);
                roConn = roBind.connection;
                boundRo = roBind.endpoint;
            } catch (SQLException roFailure) {
                lastRoFailure = roFailure;
            }

            if (roConn == null) {
                if (config.isRoPhysicalFailoverToRw()) {
                    roConn = rwConn;
                    setRoOnRw(true, boundRw.getId(), "no RO endpoint reachable at bind time");
                } else {
                    closeQuietly(rwConn);
                    closeConnsLocked();
                    if (lastRoFailure != null) {
                        throw lastRoFailure;
                    }

                    throw LbExceptions.brokerGroupExhausted("RO", "", null);
                }
            }

            connsByEpId.put(boundRw.getId(), rwConn);
            if (roOnRw) {
                connsByEpId.put(roEndpoint.getId(), roConn);
                sessRoEp = roEndpoint;
            } else {
                connsByEpId.put(boundRo.getId(), roConn);
                sessRoEp = boundRo;
            }
            sessRwEp = boundRw;
            bound = true;
            logSessionBinding();
        } finally {
            // finally (not catch SQLException): a RuntimeException from topology access or state
            // application after the RW/RO opens must also release rwConn and any cached connection,
            // otherwise the physical connections leak. On success `bound` is true and nothing
            // closes.
            if (!bound) {
                closeQuietly(rwConn);
                closeConnsLocked();
            }
        }
    }

    public synchronized void bindSessionRwOnly(
            final Endpoint rwEndpoint, final EndpointTopology topology) throws SQLException {
        if (rwEndpoint == null) {
            throw LbExceptions.internalState("missing RW endpoint");
        }

        closePrepLocked();
        closeConnsLocked();
        clearRoOnRw();
        sessTopo = topology;
        homeRwEp = rwEndpoint;

        Connection rwConn = null;
        boolean bound = false;

        try {
            List<Endpoint> rwOrder = failoverOrder(rwEndpoint, topology, FailoverGroup.RW);

            BindResult rwBind = openFirstReachable(rwOrder, "RW", unreachableIds(sessTopo), 0);
            rwConn = rwBind.connection;

            Endpoint boundRw = rwBind.endpoint;
            warnIfRwOffMaster(rwEndpoint, boundRw);

            connsByEpId.put(boundRw.getId(), rwConn);
            sessRwEp = boundRw;
            // RW-only: the RO role permanently maps to the RW endpoint/connection.
            sessRoEp = boundRw;
            // read home is the RW endpoint itself, so the read leg is never "displaced" — read
            // failback is a no-op here; RW failback moves both legs together.
            homeReadEp = boundRw;
            bound = true;
            logSessionBinding();
        } finally {
            if (!bound) {
                closeQuietly(rwConn);
                closeConnsLocked();
            }
        }
    }

    /**
     * Records where a freshly bound session actually landed: both legs, the weighted home the read
     * leg should return to, and whether reads are on the fallback endpoint.
     *
     * <p>Only <em>abnormal</em> placements used to be recorded. A session that bound exactly as
     * configured said nothing, which left no baseline: the distribution a pool ends up with cannot
     * be checked against what was asked for if the individual placements were never written down.
     * INFO rather than WARNING, because normal placement is not something to act on.
     */
    private void logSessionBinding() {
        LbLog.info(
                LOGGER,
                logContext,
                "LB BIND: rw="
                        + (sessRwEp == null ? "?" : sessRwEp.getId())
                        + " read="
                        + (sessRoEp == null ? "?" : sessRoEp.getId())
                        + " readHome="
                        + (homeReadEp == null ? "?" : homeReadEp.getId())
                        + " rwHome="
                        + (homeRwEp == null ? "?" : homeRwEp.getId())
                        + " roOnRw="
                        + roOnRw);
    }

    public synchronized void bindSessionWithReadTarget(
            final Endpoint masterRw,
            final Endpoint readEndpoint,
            final boolean readReusesRw,
            final EndpointTopology topology)
            throws SQLException {
        if (masterRw == null) {
            throw LbExceptions.internalState("missing RW endpoint");
        }

        if (!readReusesRw) {
            // slave/replica read: open a separate RO/SO physical connection.
            bindSession(masterRw, readEndpoint, topology);
            return;
        }

        // master read: reuse the master RW physical connection — one physical connection.
        // Mechanically identical to RW-only: the RO role maps onto the RW endpoint/connection.
        bindSessionRwOnly(masterRw, topology);

        if (sessRwEp == null
                || sessRwEp.equals(masterRw)
                || !hasReadEndpointOnAnotherHost(topology, masterRw)) {
            return;
        }

        // The RW bind was displaced onto a sibling. Sharing that connection would push this
        // session's READS through the sibling's RW broker, whose CAS forwards them to the master
        // DB:
        // an extra hop, no local read, and unlike a session that existed before the outage, whose
        // read leg fails over to a real read broker. So give the read leg its own connection on the
        // best real read endpoint, and keep the read home at the master RW, so read failback
        // collapses both legs back onto one socket once the master returns.
        homeReadEp = masterRw;
        bindDisplacedMasterReadLeg(masterRw, topology);
    }

    /** Whether the topology offers a read endpoint on a host other than the master's. */
    private static boolean hasReadEndpointOnAnotherHost(
            final EndpointTopology topology, final Endpoint masterRw) {
        if (topology == null || masterRw == null) {
            return false;
        }
        return hasOffHost(topology.getRoEndpoints(), masterRw)
                || hasOffHost(topology.getReplEndpoints(), masterRw);
    }

    private static boolean hasOffHost(final List<Endpoint> endpoints, final Endpoint masterRw) {
        if (endpoints == null) {
            return false;
        }
        for (int i = 0; i < endpoints.size(); i++) {
            final Endpoint e = endpoints.get(i);
            if (e != null && !e.getHost().equals(masterRw.getHost())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens a temporary read connection for a master-read session whose RW leg was displaced. The
     * ladder is the normal read order anchored on the master ({@link #readCandidateOrder}) minus
     * the master RW itself, so the master's OWN read broker is preferred when it is still up (same
     * node, master-fresh reads, no hop) before a sibling slave RO or a replica SO.
     *
     * <p>Best effort: when no read rung is reachable the session keeps reading on the displaced RW
     * connection, which is the pre-existing behavior.
     */
    private void bindDisplacedMasterReadLeg(
            final Endpoint masterRw, final EndpointTopology topology) {
        final List<Endpoint> rungs = readCandidateOrder(masterRw, topology);
        final List<Endpoint> targets = new ArrayList<Endpoint>();
        for (int i = 0; i < rungs.size(); i++) {
            final Endpoint cand = rungs.get(i);
            if (!cand.getId().equals(masterRw.getId())) {
                targets.add(cand);
            }
        }
        if (targets.isEmpty()) {
            return;
        }

        BindResult roBind;
        try {
            roBind = openFirstReachable(targets, "RO", unreachableIds(topology), 0);
        } catch (SQLException noReadRung) {
            LbLog.warn(
                    LOGGER,
                    logContext,
                    "LB BIND [RO]: master read displaced with no reachable read rung -> reads stay on"
                            + " the RW connection at "
                            + sessRwEp.getId());
            return;
        }

        try {
            connsByEpId.put(roBind.endpoint.getId(), roBind.connection);
            sessRoEp = roBind.endpoint;
            setRoOnRw(false, sessRwEp.getId(), "read leg re-bound to " + roBind.endpoint.getId());
            LbLog.warn(
                    LOGGER,
                    logContext,
                    "LB BIND [RO]: master read displaced -> read leg temporarily on "
                            + roBind.endpoint.getId()
                            + " (write leg on "
                            + sessRwEp.getId()
                            + "; read home stays "
                            + masterRw.getId()
                            + ", both legs rejoin one connection on failback)");
        } catch (RuntimeException e) {
            closeQuietly(roBind.connection);
            throw e;
        }
    }

    /**
     * Warns (visible on the terminal) when a session binds its RW leg to a node other than the
     * intended master — i.e. the master was unreachable at bind time and RW fell over to a sibling
     * RW broker. {@link #restoreRwIfRecovered()} moves it back once the master recovers; logging
     * the displacement makes the window observable rather than silent.
     */
    private void warnIfRwOffMaster(final Endpoint preferredMaster, final Endpoint boundRw) {
        if (preferredMaster != null && boundRw != null && !boundRw.equals(preferredMaster)) {
            LbLogDedup.warn(
                    LOGGER,
                    logContext,
                    "BIND|RW|" + preferredMaster.getId() + "|" + boundRw.getId(),
                    "LB BIND [RW]: preferred master="
                            + preferredMaster.getId()
                            + " unreachable -> bound RW to "
                            + boundRw.getId()
                            + "  <== NON-master node (RW failback will return it to the master once"
                            + " the master recovers)");
        }
    }

    private static List<Endpoint> failoverOrder(
            final Endpoint preferred, final EndpointTopology topology, final FailoverGroup group) {
        List<Endpoint> order = new ArrayList<Endpoint>();
        if (preferred != null) {
            order.add(preferred);
        }

        if (topology == null) {
            return order;
        }

        List<Endpoint> candidates;
        switch (group) {
            case RW:
                candidates = topology.getRwEndpoints();
                break;
            case RO:
                candidates = topology.getRoEndpoints();
                break;
            default:
                // unknown group fallback
                candidates = new ArrayList<Endpoint>(0);
                break;
        }

        for (int i = 0; i < candidates.size(); i++) {
            Endpoint e = candidates.get(i);
            if (preferred != null && preferred.equals(e)) {
                continue;
            }
            order.add(e);
        }

        return order;
    }

    static final class BindResult {
        private final Endpoint endpoint;
        private final Connection connection;

        private BindResult(final Endpoint endpoint, final Connection connection) {
            this.endpoint = endpoint;
            this.connection = connection;
        }

        // Used by JdbcBrokerConnectionManagerTest (production code accesses the fields directly).
        Endpoint getEndpoint() {
            return endpoint;
        }

        Connection getConnection() {
            return connection;
        }
    }

    BindResult openFirstReachable(
            final List<Endpoint> order,
            final String groupLabel,
            final Set<String> excludeEndpointIds,
            final int maxAttempts)
            throws SQLException {
        if (order == null || order.isEmpty()) {
            throw LbExceptions.brokerCandidatesFiltered(groupLabel, "no candidates");
        }

        return openFirstReachablePerGroup(
                Collections.singletonList(order), groupLabel, excludeEndpointIds, maxAttempts);
    }

    /**
     * Best-first connect over candidates already split into role groups, with {@code
     * maxAttemptsPerGroup} bounding the connect attempts WITHIN each group — the semantics {@code
     * cubrid.lb.runtime.failover.max.attempts.per.group} names. A group whose budget runs out does
     * not end the search; the next group is tried. See {@link #readCandidateTiers} for what this
     * fixes.
     *
     * <p>Note {@code maxAttemptsPerGroup} counts DIALS, not candidates: an excluded endpoint is
     * skipped for free, which is why an endpoint another session already flagged unreachable does
     * not consume the budget.
     */
    BindResult openFirstReachablePerGroup(
            final List<List<Endpoint>> groups,
            final String groupLabel,
            final Set<String> excludeEndpointIds,
            final int maxAttemptsPerGroup)
            throws SQLException {
        SQLException firstFailure = null;

        StringBuilder tried = new StringBuilder();
        // One endpoint can legitimately appear in two tiers (a degenerate topology, or a home that
        // is also listed as a sibling); dialing it twice would burn a second group's budget on a
        // host already known to be failing.
        // already known to be failing.
        Set<String> dialed = new HashSet<String>();

        for (int g = 0; g < groups.size(); g++) {
            final List<Endpoint> group = groups.get(g);
            if (group == null || group.isEmpty()) {
                continue;
            }

            int attempts = 0;

            for (int i = 0; i < group.size(); i++) {
                Endpoint ep = group.get(i);

                if (excludeEndpointIds != null && excludeEndpointIds.contains(ep.getId())) {
                    continue;
                }

                // Budget BEFORE the dedup mark: the candidate that exhausts a tier's budget was
                // never dialed, so marking it here would retire it for every later tier too -- a
                // tier holding it with budget of its own would skip it and the session could report
                // brokerGroupExhausted while that endpoint was up.
                if (maxAttemptsPerGroup > 0 && attempts >= maxAttemptsPerGroup) {
                    break;
                }

                if (!dialed.add(ep.getId())) {
                    continue;
                }

                attempts++;

                if (tried.length() > 0) {
                    tried.append(", ");
                }
                tried.append(ep.getId());

                try {
                    Connection conn = openConnection(ep);
                    unreachFilter.markReachable(ep);
                    return new BindResult(ep, conn);
                } catch (SQLException ex) {
                    unreachFilter.markUnreachable(ep, ex);
                    // Surface WHY this candidate was skipped (otherwise swallowed unless all fail),
                    // so a fallback onto a non-master RW is traceable to its root connect failure.
                    // FINE, not WARNING: skipping a candidate is the ladder working as designed,
                    // and
                    // the outcome that matters - which endpoint the session ended up on - is
                    // already
                    // a WARNING from the BIND/FAILOVER record.
                    LbLog.fine(
                            LOGGER,
                            logContext,
                            "LB CONNECT FAILED ["
                                    + groupLabel
                                    + "] endpoint="
                                    + ep.getId()
                                    + " -> trying next candidate | cause: "
                                    + LbLog.cause(ex));
                    if (firstFailure == null) {
                        firstFailure = ex;
                    }
                }
            }
        }

        if (tried.length() == 0) {
            throw LbExceptions.brokerCandidatesFiltered(
                    groupLabel, "exclude/maxAttempts filters removed all candidates");
        }

        throw LbExceptions.brokerGroupExhausted(groupLabel, tried.toString(), firstFailure);
    }

    public synchronized Endpoint getSessionEndpoint(final SessionLeg leg) {
        if (leg == null) {
            throw new IllegalArgumentException("SessionLeg must not be null");
        }

        switch (leg) {
            case RW:
                return sessRwEp;
            case RO:
                return sessRoEp;
            default:
                throw new IllegalStateException("Unsupported session leg: " + leg);
        }
    }

    /** {@inheritDoc} */
    public synchronized boolean isReadOnRwConnection() {
        return roOnRw;
    }

    /**
     * Pool health probe for {@code Connection.isValid()}. Checks only the bound RW physical
     * connection, which every session has (RW-only sessions and the {@code roOnRw} fallback both
     * read on it). RO/SO are deliberately not probed: a session whose RO is down still serves reads
     * on RW and re-binds RO lazily on the read path ({@link #restoreRoIfRecovered()}), so a down RO
     * must not make the pool discard an otherwise usable connection.
     *
     * <p><b>Structural only: it does not call {@code isValid()} on the physical connection.</b> The
     * vendor's {@code isValid()} answers "does the broker still hold a CAS for my (pid, session
     * id)", which is not "can this connection still do work". Under {@code KEEP_CONNECTION=AUTO}
     * the broker reclaims an idle CAS beyond {@code MIN_NUM_APPL_SERVER} after {@code TIME_TO_KILL}
     * (default 30s), and from then on {@code isValid()} reports false for a healthy connection that
     * simply reconnects on its next statement. Trusting it cost a whole pool: HikariCP validates on
     * borrow, so the first request after a quiet spell rebuilt most of the pool while the
     * application saw no error. This is not LB-specific; LB just meets it more often, because a
     * read-mostly session leaves its RW leg idle.
     *
     * <p>So the probe answers only what it can answer honestly: is this session structurally
     * intact. Real liveness is the failover machinery's job - the RW leg heals through {@link
     * #recoverRw} and {@link #restoreRwIfRecovered()}, and a genuinely dead RW surfaces on the next
     * write. This never opens, replaces, or heals a connection, and it returns {@code true} when no
     * RW is bound yet, so a not-yet-initialized session is not evicted.
     *
     * <p><b>Contention:</b> this method is {@code synchronized} on the session monitor, which
     * {@code bindSession}/{@code recoverRw}/{@code recoverRo} hold while connecting in {@code
     * openFirstReachable} (up to the connect timeout). A pool validation thread calling this during
     * a failover blocks until that connect returns - acceptable under one user per session, but a
     * known trade-off.
     *
     * <p>No timeout parameter: {@code Connection.isValid(int)} has one because a driver is expected
     * to validate over the wire, and this does not. Taking one would advertise a network probe that
     * never happens.
     *
     * @return {@code true} when the bound RW connection is still open (or nothing is bound yet)
     */
    public synchronized boolean isBoundRwSocketOpen() {
        if (sessRwEp == null) {
            return true;
        }

        Connection rw = connsByEpId.get(sessRwEp.getId());
        if (rw == null) {
            return true;
        }

        try {
            if (rw.isClosed()) {
                logPoolProbeFailed("physical connection already closed", rw, null);
                return false;
            }
            return true;
        } catch (SQLException e) {
            logPoolProbeFailed("isClosed() threw", rw, e);
            return false;
        }
    }

    /**
     * The physical connection as the vendor describes itself — {@code CUBRIDConnection.toString()}
     * is {@code (casIp:casPort, casId, casProcessId)}, the identity the broker matches on. Falls
     * back to the class name rather than printing a bare "null" for a stub/proxy whose {@code
     * toString} gives nothing: {@code rwConn=null} would read as "the connection was null", which
     * is a different (and already-handled) condition.
     */
    private static String describeConn(final Connection conn) {
        if (conn == null) {
            return "<none>";
        }
        String described;
        try {
            described = conn.toString();
        } catch (RuntimeException ignored) {
            described = null; // a diagnostic must never be the thing that fails
        }
        return described == null || described.length() == 0 ? conn.getClass().getName() : described;
    }

    /**
     * Says which physical leg failed the pool's health probe, and what the pool will do about it.
     *
     * <p>Worth a warning because the consequence is otherwise invisible: the pool discards this
     * logical connection and opens a new one, with no error reaching the application, and a whole
     * pool can recycle this way. Bounded by pool size, not by time - a connection that fails the
     * probe is discarded at once, so this logs at most once per connection per recycle.
     *
     * <p>Since the probe became structural ({@link #isBoundRwSocketOpen}) this fires only for a
     * genuinely closed socket. A burst of them means something is closing RW connections underneath
     * the pool; it no longer fires when a broker reclaims an idle CAS.
     *
     * <p>The probe covers the RW leg only, so a failure here says nothing about the read leg
     * (logged alongside for that reason), and {@code CUBRIDConnection.toString()} carries {@code
     * (casIp:casPort, casId, casProcessId)} so the socket can be matched against the broker's CAS
     * table.
     */
    private void logPoolProbeFailed(
            final String why, final Connection rw, final SQLException cause) {
        LbLogDedup.warn(
                LOGGER,
                logContext,
                "POOL_PROBE|" + (sessRwEp == null ? "?" : sessRwEp.getId()) + "|" + why,
                "LB POOL PROBE [RW] endpoint="
                        + (sessRwEp == null ? "?" : sessRwEp.getId())
                        + " "
                        + why
                        + " -> reporting this session dead; the pool will discard and recreate it"
                        + " | rwConn="
                        + describeConn(rw)
                        + " | readLeg="
                        + (sessRoEp == null ? "?" : sessRoEp.getId())
                        + (roOnRw ? " (on the RW connection)" : "")
                        + (cause == null ? "" : " | cause: " + LbLog.cause(cause)));
    }

    public synchronized void releaseBindings() {
        closePrepLocked();
        closeConnsLocked();
    }

    public synchronized void releasePreparedStatements() {
        closePrepLocked();
    }

    public synchronized void releasePhysicalConnections() {
        releaseBindings();
    }

    public synchronized PreparedStatement prepareStatement(
            final Endpoint endpoint, final String sql) throws SQLException {
        return prepareStatement(endpoint, sql, SHARED_PREP_OWNER);
    }

    public synchronized PreparedStatement prepareStatement(
            final Endpoint endpoint, final String sql, final String ownerId) throws SQLException {
        return prepareStatement(endpoint, sql, ownerId, java.sql.Statement.NO_GENERATED_KEYS);
    }

    public synchronized PreparedStatement prepareStatement(
            final Endpoint endpoint,
            final String sql,
            final String ownerId,
            final int autoGeneratedKeys)
            throws SQLException {
        if (endpoint == null) {
            throw LbExceptions.internalState("missing endpoint for prepare");
        }
        if (sql == null) {
            throw LbExceptions.internalState("missing SQL for prepare");
        }

        String cacheKey = PreparedSql.normalizeForCacheKey(sql);

        // Key by owning logical PS, so two logical PreparedStatements with the same SQL do not
        // share one physical PS (which would cross-clobber parameters, ResultSets and batches).
        // Re-executing the same logical PS still hits the cache (same owner + SQL). The owner is
        // unique per logical PS, so it also tells a RETURN_GENERATED_KEYS prepare from a plain one.
        String key = endpoint.getId() + "|" + owner(ownerId) + "|" + cacheKey;

        PreparedStatement cached = prepByKey.get(key);
        if (cached != null) {
            if (isCachedPrepUsable(cached)) {
                return cached;
            }
            prepByKey.remove(key);
        }

        Connection physical = ensureConn(endpoint);

        // Only the generated-keys form takes the 2-arg physical prepare; the common path keeps the
        // single-arg prepare so its result-set/holdability defaults are unchanged.
        PreparedStatement ps =
                autoGeneratedKeys == java.sql.Statement.RETURN_GENERATED_KEYS
                        ? physical.prepareStatement(sql, autoGeneratedKeys)
                        : physical.prepareStatement(sql);
        prepByKey.put(key, ps);
        trackPrepCacheGrowth();

        return ps;
    }

    private static String owner(final String ownerId) {
        return ownerId == null ? SHARED_PREP_OWNER : ownerId;
    }

    private static boolean isCachedPrepUsable(final PreparedStatement ps) throws SQLException {
        if (ps == null || ps.isClosed()) {
            return false;
        }

        Connection conn = ps.getConnection();
        return conn == null || !conn.isClosed();
    }

    public synchronized void closePrepStmts() throws SQLException {
        closePrepLocked();
    }

    /**
     * Closes only the physical prepared statements owned by one logical PreparedStatement. Wired to
     * {@code LBPreparedStatement.close()} so closing one logical PS does not destroy the cached
     * physical PS (and open ResultSets) of other logical PS on the same connection. The
     * session-wide {@link #closePrepStmts()} stays reserved for Connection.close().
     */
    public synchronized void closePrepForOwner(final String ownerId) throws SQLException {
        final String owner = owner(ownerId);
        removePrepWhere(
                new PrepKeyMatcher() {
                    public boolean matches(final String key) {
                        return owner.equals(ownerSegment(key));
                    }
                });
    }

    /** Predicate over prep cache keys for {@link #removePrepWhere}. */
    private interface PrepKeyMatcher {
        boolean matches(String key);
    }

    /** Removes and quietly closes every prep cache entry whose key matches {@code matcher}. */
    private void removePrepWhere(final PrepKeyMatcher matcher) throws SQLException {
        List<String> keysToRemove = new ArrayList<String>();
        for (String key : prepByKey.keySet()) {
            if (matcher.matches(key)) {
                keysToRemove.add(key);
            }
        }

        for (int i = 0; i < keysToRemove.size(); i++) {
            closeQuietly(prepByKey.remove(keysToRemove.get(i)));
        }
    }

    /** The owner segment of a "{@code endpointId|ownerId|cacheKey}" prep cache key. */
    private static String ownerSegment(final String key) {
        int firstBar = key.indexOf('|');
        if (firstBar < 0) {
            return SHARED_PREP_OWNER;
        }
        int secondBar = key.indexOf('|', firstBar + 1);
        if (secondBar < 0) {
            return SHARED_PREP_OWNER;
        }
        return key.substring(firstBar + 1, secondBar);
    }

    public synchronized Connection getPhyConn(final Endpoint endpoint) throws SQLException {
        if (endpoint == null) {
            throw LbExceptions.internalState("missing endpoint for connection lookup");
        }

        return ensureConn(endpoint);
    }

    private Connection ensureConn(final Endpoint endpoint) throws SQLException {
        Connection physical = connsByEpId.get(endpoint.getId());
        if (physical != null && physical.isClosed()) {
            connsByEpId.remove(endpoint.getId());
            dropPrepForEp(endpoint.getId());
            physical = null;
        }
        if (physical != null) {
            reapplySessionStateIfCasReplaced(endpoint, physical);
            return physical;
        }

        if (isSessionLeg(endpoint)) {
            physical = openConnection(endpoint);
            connsByEpId.put(endpoint.getId(), physical);
            return physical;
        }

        throw LbExceptions.physicalNotBound(endpoint.getId());
    }

    private boolean isSessionLeg(final Endpoint endpoint) {
        if (endpoint == null || sessRwEp == null) {
            return false;
        }

        if (endpoint.equals(sessRwEp)) {
            return true;
        }

        return sessRoEp != null && endpoint.getId().equals(sessRoEp.getId());
    }

    /**
     * Applies the autocommit mode to both session legs; see {@link #applyToBothLegs} for the
     * one-leg-failure policy.
     */
    public synchronized void applyPhyAutoCommit(final boolean autoCommit) throws SQLException {
        applyToBothLegs(
                "LB AUTOCOMMIT",
                "autoCommit=" + autoCommit,
                new LegOperation() {
                    public void apply(final Connection physical) throws SQLException {
                        physical.setAutoCommit(autoCommit);
                    }
                });
    }

    /** One operation applied to a single physical leg. */
    private interface LegOperation {
        void apply(Connection physical) throws SQLException;
    }

    /**
     * Applies one operation to BOTH session legs, the read leg even when the write leg failed, so
     * neither leg is skipped as collateral damage of the other's failure. The write-leg failure is
     * what the caller sees; a read-leg failure masked by it is logged (Java 1.6 target, so no
     * {@code addSuppressed}).
     *
     * @param logTag the {@code "LB ..."} prefix identifying the operation in the masked-failure
     *     warning
     * @param opDesc how the operation reads in that warning, e.g. {@code "autoCommit=false"}
     * @param op the operation to run on each leg
     * @throws SQLException the write-leg failure, or the read-leg failure when the write leg was
     *     fine
     */
    private void applyToBothLegs(final String logTag, final String opDesc, final LegOperation op)
            throws SQLException {
        if (sessRwEp == null) {
            return;
        }

        final Connection rwPhysical = connsByEpId.get(sessRwEp.getId());
        final Connection roPhysical = separateRoPhysicalOrNull(rwPhysical);

        SQLException rwFailure = null;
        if (rwPhysical != null) {
            try {
                op.apply(rwPhysical);
            } catch (SQLException failure) {
                rwFailure = failure;
            }
        }

        if (roPhysical != null) {
            try {
                op.apply(roPhysical);
            } catch (SQLException roFailure) {
                if (rwFailure == null) {
                    throw roFailure;
                }
                LbLog.warn(
                        LOGGER,
                        logContext,
                        logTag
                                + " [RO]: "
                                + opDesc
                                + " failed on the read leg while the write leg was already failing;"
                                + " reporting the write failure | readLeg="
                                + (sessRoEp == null ? "?" : sessRoEp.getId())
                                + " | cause: "
                                + LbLog.cause(roFailure));
            }
        }

        if (rwFailure != null) {
            throw rwFailure;
        }
    }

    public synchronized void commitPhyTx() throws SQLException {
        endPhyTx(true);
    }

    public synchronized void rollbackPhyTx() throws SQLException {
        endPhyTx(false);
    }

    /**
     * Ends the physical transaction on BOTH legs, even when the first one fails.
     *
     * <p>The RW leg used to run first and unguarded, so anything it threw skipped the RO leg. A
     * skipped rollback is the dangerous one: the read leg keeps an open transaction on the slave,
     * pinning old versions there for as long as the session lives. The RW failure is still what the
     * caller sees. (Java 1.6 target, so no {@code addSuppressed}: an RO failure masked by an RW
     * failure is logged rather than dropped.)
     *
     * <p>Reaching the RO leg with an open transaction takes an autocommit-mode prepare on the read
     * endpoint, because manual-commit pins routing to RW ({@code
     * SessionRoutingState.shouldRouteToRw}). That makes the window narrow, not absent.
     *
     * @param commit {@code true} to commit both legs, {@code false} to roll both back
     */
    private void endPhyTx(final boolean commit) throws SQLException {
        applyToBothLegs(
                "LB TX END",
                commit ? "commit" : "rollback",
                new LegOperation() {
                    public void apply(final Connection physical) throws SQLException {
                        if (commit) {
                            physical.commit();
                        } else {
                            physical.rollback();
                        }
                    }
                });
    }

    /**
     * The separate RO physical connection to also commit/rollback, or null. Manual-commit sets
     * autoCommit=false on the RO connection too, so a read routed there opens a
     * transaction/snapshot that the logical commit/rollback must close, or it lingers on the slave
     * for the session's life. Returns null when RO reuses the RW connection (roOnRw), which the RW
     * leg already handled.
     */
    private Connection separateRoPhysicalOrNull(final Connection rwPhysical) {
        if (sessRoEp == null) {
            return null;
        }
        Connection roPhysical = connsByEpId.get(sessRoEp.getId());
        return roPhysical != rwPhysical ? roPhysical : null;
    }

    public synchronized String getRoFallbackReason() {
        if (roOnRw) {
            return RO_PHYSICAL_FAILOVER_REASON;
        }

        return FallbackReason.NONE;
    }

    /**
     * Propagate a successful (re)bind: clear the JCI unreachable entry so other sessions stop
     * excluding the endpoint, and re-arm the backoff so its next probe needs no wait.
     */
    private void markRecovered(final Endpoint endpoint) {
        unreachFilter.markReachable(endpoint);
        backoff.reset(endpoint.getId());
    }

    /**
     * Recovery candidate exclude set: hard excludes ({@code ctx} ids + just-failed) always hold;
     * the JCI-unreachable set is supplied soft so a backoff-due endpoint can be probed without
     * waiting for the 60s JCI poll.
     */
    private Set<String> recoveryExcludedIds(
            final PhysicalRecoveryContext ctx,
            final Endpoint failed,
            final EndpointTopology topology) {
        return union(
                excludedIdsWith(ctx.getExcludeEndpointIds(), failed),
                softExcludedIds(topology, System.nanoTime()));
    }

    public synchronized PhysicalRecoveryResult recoverRw(final PhysicalRecoveryContext ctx)
            throws SQLException {
        if (ctx == null) {
            throw new IllegalArgumentException("PhysicalRecoveryContext must not be null");
        }

        if (ctx.isTxActive()) {
            throw LbExceptions.failoverTxForbidden("RW");
        }

        if (ctx.getFailedLeg() != SessionLeg.RW) {
            throw new IllegalArgumentException(
                    "recoverRw requires failedLeg RW, got " + ctx.getFailedLeg());
        }

        Endpoint failed = ctx.getFailedEndpoint();
        EndpointTopology topology = ctx.getTopology();
        List<Endpoint> order = failoverOrder(failed, topology, FailoverGroup.RW);
        Set<String> exclude = recoveryExcludedIds(ctx, failed, topology);

        // Single-RW topology: the failed endpoint is the only RW candidate, so hard-excluding it
        // leaves nothing and runtime recovery could never succeed - the session would depend on a
        // pool rebind, stranding RW even after a transient blip. RO has roOnRw as a last resort, RW
        // has no such fallback. So allow ONE reconnect attempt to the failed endpoint: the connect
        // is itself the reachability probe, so a truly down master still fails fast. This relaxes
        // only the single-candidate case; with other RW endpoints an explicit ctx exclude holds.
        if (order.size() == 1 && exclude.contains(failed.getId())) {
            exclude.remove(failed.getId());
        }

        // Every RW candidate is tried (budget 0), NOT capped by
        // cubrid.lb.runtime.failover.max.attempts.per.group. The RW candidates form a single group,
        // so a budget of 1 meant one dial per recovery: with three or more RW endpoints, a session
        // whose first live sibling also failed to connect exhausted the group and never dialed the
        // rest - brokerGroupExhausted while a working RW broker was up. RO survives that cut
        // because
        // its ladder is split into tiers and bottoms out at roOnRw; RW has neither, and a failed
        // write is not re-executed. The bind path already dials all RW candidates.
        BindResult bind = openFirstReachable(order, "RW", exclude, 0);

        replaceRwConn(failed, bind.endpoint, bind.connection);
        markRecovered(bind.endpoint);
        MetricsRegistry.recordFailover("RW", failed.getId(), bind.endpoint.getId());

        return new PhysicalRecoveryResult(bind.endpoint, SessionLeg.RW, null);
    }

    public synchronized PhysicalRecoveryResult recoverRo(final PhysicalRecoveryContext ctx)
            throws SQLException {
        return recoverRo(ctx, null);
    }

    synchronized PhysicalRecoveryResult recoverRo(
            final PhysicalRecoveryContext ctx, final String rwFallbackReasonOverride)
            throws SQLException {
        if (ctx == null) {
            throw new IllegalArgumentException("PhysicalRecoveryContext must not be null");
        }

        if (ctx.isTxActive()) {
            throw LbExceptions.failoverTxForbidden("RO");
        }

        if (ctx.getFailedLeg() != SessionLeg.RO) {
            throw new IllegalArgumentException(
                    "recoverRo requires failedLeg RO, got " + ctx.getFailedLeg());
        }

        Endpoint failed = ctx.getFailedEndpoint();
        EndpointTopology topology = ctx.getTopology();
        // Same soft-gate split as recoverRw; the roOnRw fallback below is unaffected.
        Set<String> exclude = recoveryExcludedIds(ctx, failed, topology);
        SQLException lastRoFailure = null;

        // The read leg always climbs its ladder here: reclaim home first, then same-role siblings,
        // then cross-role. The just-failed endpoint is in `exclude`, so the dial loop skips it.
        // Falls back to failed-seeded order when no home is recorded (legacy/simple binding).
        //
        // The ladder is passed as TIERS so the attempt budget applies per role group: spending it
        // on a dead sibling must not hide a live cross-role rung behind roOnRw (see
        // readCandidateTiers).
        List<List<Endpoint>> roTiers =
                homeReadEp != null
                        ? readCandidateTiers(homeReadEp, topology)
                        : Collections.singletonList(
                                failoverOrder(failed, topology, FailoverGroup.RO));
        try {
            BindResult bind =
                    openFirstReachablePerGroup(
                            roTiers, "RO", exclude, config.getRuntimeFailoverMaxAttempts());
            replaceRoConn(failed, bind.endpoint, bind.connection, false);
            markRecovered(bind.endpoint);
            MetricsRegistry.recordFailover("RO", failed.getId(), bind.endpoint.getId());
            return new PhysicalRecoveryResult(bind.endpoint, SessionLeg.RO, null);
        } catch (SQLException roFailure) {
            lastRoFailure = roFailure;
        }

        if (!config.isRoPhysicalFailoverToRw()) {
            if (lastRoFailure != null) {
                throw lastRoFailure;
            }
            throw LbExceptions.failoverExhausted("RO", "rwFallback=disabled", lastRoFailure);
        }

        return failoverRoToRw(
                failed,
                topology,
                lastRoFailure,
                rwFallbackReasonOverride != null
                        ? rwFallbackReasonOverride
                        : RO_PHYSICAL_FAILOVER_REASON);
    }

    private PhysicalRecoveryResult failoverRoToRw(
            final Endpoint failedRo,
            final EndpointTopology topology,
            final SQLException roFailureCause,
            final String fallbackReason)
            throws SQLException {
        Connection rwConn = null;
        Endpoint rwEndpoint = sessRwEp;

        if (rwEndpoint != null) {
            rwConn = connsByEpId.get(rwEndpoint.getId());
        }

        if (rwConn == null) {
            Endpoint preferredRw =
                    rwEndpoint != null
                            ? rwEndpoint
                            : (topology.getRwEndpoints().isEmpty()
                                    ? null
                                    : topology.getRwEndpoints().get(0));

            if (preferredRw == null) {
                throw LbExceptions.failoverRoRwFallbackFailed(roFailureCause);
            }

            List<Endpoint> rwOrder = failoverOrder(preferredRw, topology, FailoverGroup.RW);
            // Soft-gate the JCI-unreachable set, as recoverRw/recoverRo do: a backoff-due RW can
            // be
            // probed without waiting for the 60s JCI poll. Without this, an all-RW-listed topology
            // fails immediately even after the backoff window elapses.
            BindResult rwBind =
                    openFirstReachable(
                            rwOrder,
                            "RW",
                            softExcludedIds(topology, System.nanoTime()),
                            config.getRuntimeFailoverMaxAttempts());
            rwEndpoint = rwBind.endpoint;
            rwConn = rwBind.connection;
            putConnClosingDisplaced(rwEndpoint.getId(), rwConn);
            sessRwEp = rwEndpoint;
        }

        Connection previous = connsByEpId.get(failedRo.getId());
        connsByEpId.remove(failedRo.getId());
        dropPrepForEp(failedRo.getId());

        String logicalRoKey = sessRoEp != null ? sessRoEp.getId() : failedRo.getId();
        putConnClosingDisplaced(logicalRoKey, rwConn);
        setRoOnRw(true, rwEndpoint.getId(), "RO ladder exhausted at runtime");
        // Reads now share the RW connection: the last rung of the ladder. Counted as a failover to
        // the RW endpoint so the operator sees where the read went, and the roOnRw gauge rises too.
        MetricsRegistry.recordFailover("RO", logicalRoKey, rwEndpoint.getId());

        if (previous != null && previous != rwConn && !connInUseElsewhere(previous)) {
            closeQuietly(previous);
        }

        return new PhysicalRecoveryResult(rwEndpoint, SessionLeg.RW, fallbackReason);
    }

    /**
     * Lazy read failback. While a session is displaced - its read leg failed over to a same-role
     * sibling, a cross-role endpoint, or onto the RW connection ({@code roOnRw}) - this checks
     * whether a strictly better read endpoint has recovered and rebinds the read role to it. The
     * candidate ladder is home-first ({@link #readCandidateOrder}), so a fully recovered topology
     * lands the session back on its weighted home and the readWeight distribution self-heals with
     * no pool recycling.
     *
     * <p>LB-JDBC runs no health-check thread, so failback is observed lazily on the read path. The
     * reachability gate is the JCI unreachable-host list softened by {@link #softExcludedIds}: once
     * per backoff interval one session may probe a still-listed endpoint directly, so recovery is
     * detected within one interval instead of on the 60s core JCI poll, and a success clears the
     * list for every other session.
     *
     * <p><b>Caller contract:</b> must not be called while a transaction is active - it can replace
     * the read connection and close the old one, destroying any open ResultSet. Unlike {@link
     * #recoverRo}/{@link #recoverRw} it takes no {@code ctx} and enforces no guard of its own; the
     * sole caller ({@code LoadBalanceConnection.maybeRestoreRoBinding}) gates it through {@code
     * shouldRouteToRw()}. Any new call site must uphold the same guard.
     *
     * @return the read endpoint now bound, or {@code null} when nothing changed (failback disabled,
     *     already at the best endpoint, none better reachable, or the reconnect failed)
     */
    public synchronized Endpoint restoreRoIfRecovered() throws SQLException {
        if (!config.isReadFailbackEnabled() || homeReadEp == null || sessTopo == null) {
            return null;
        }
        if (!isReadDisplaced()) {
            return null;
        }

        final List<Endpoint> order = readCandidateOrder(homeReadEp, sessTopo);
        // Soft gate: a backoff-due endpoint still on the JCI unreachable list becomes a failback
        // candidate again, so recovery is probed within one interval instead of the 60s JCI poll.
        final Set<String> exclude = softExcludedIds(sessTopo, System.nanoTime());

        // Current rung index in the ladder; roOnRw ranks below every real read rung.
        final int curIdx = roOnRw ? order.size() : rungRankOf(order, sessRoEp);

        // All endpoints strictly better than the current one that the soft gate lets through. With
        // the backoff bypass a candidate may still be down (a claimed probe, not a JCI-cleared
        // recovery), so probe the whole sublist best-first: a single target would let a still-down
        // higher endpoint starve a genuinely recovered lower one every window.
        final List<Endpoint> targets = new ArrayList<Endpoint>();
        for (int i = 0; i < curIdx && i < order.size(); i++) {
            final Endpoint cand = order.get(i);
            if (!exclude.contains(cand.getId())) {
                targets.add(cand);
            }
        }
        if (targets.isEmpty()) {
            return null; // already at the best reachable rung
        }

        // Master-read session: its read home IS the RW endpoint (see bindSessionRwOnly). Reuse the
        // live RW connection instead of opening a second one to the same broker - the second would
        // land on the same connsByEpId key, displace the RW connection and close it, so a read-path
        // failback would tear down the write leg. Reuse also restores exactly the bind-time state.
        final Endpoint bestRung = targets.get(0);
        if (sessRwEp != null && bestRung.getId().equals(sessRwEp.getId())) {
            final Endpoint reused = rebindReadOntoRwConn(bestRung);
            if (reused != null) {
                return reused;
            }
        }

        final BindResult bind;
        try {
            bind = openFirstReachable(targets, "RO", Collections.<String>emptySet(), 0);
        } catch (SQLException ex) {
            return null; // probes failed (still down); retry on a later read
        }

        final Endpoint previousReadKey = sessRoEp != null ? sessRoEp : homeReadEp;
        // Capture before replaceRoConn clears it: on a roOnRw session the read was on the RW leg,
        // that is the endpoint it climbed back FROM.
        final String fromId =
                roOnRw && sessRwEp != null ? sessRwEp.getId() : previousReadKey.getId();
        final boolean cameOffRw = roOnRw;
        replaceRoConn(previousReadKey, bind.endpoint, bind.connection, false);
        markRecovered(bind.endpoint);
        MetricsRegistry.recordFailback("RO", fromId, bind.endpoint.getId());
        // Same visibility as the RW leg (see restoreRwIfRecovered): the read leg moving back is
        // otherwise invisible in the log, leaving metrics as the only way to tell a recovered
        // readWeight distribution from a still-displaced one.
        final boolean atHome = homeReadEp != null && bind.endpoint.equals(homeReadEp);
        LbLogDedup.warn(
                LOGGER,
                logContext,
                "FAILBACK|RO|" + fromId + "|" + bind.endpoint.getId(),
                "LB FAILBACK [RO]: "
                        + fromId
                        + " -> "
                        + bind.endpoint.getId()
                        + (cameOffRw ? " (read leg left the RW connection)" : "")
                        + (atHome
                                ? "  (weighted home recovered; readWeight distribution restored)"
                                : "  (climbed to a better rung; weighted home "
                                        + (homeReadEp == null ? "?" : homeReadEp.getId())
                                        + " still unreachable)"));
        return bind.endpoint;
    }

    /**
     * Lazy RW failback. The RW connection is rerouted when it is bound to a secondary RW broker
     * instead of the primary master. This occurs because the initial fallback in {@code
     * openFirstReachable} or a runtime {@link #recoverRw} redirected it while the master was
     * unreachable (broker down or CAS saturated: {@code CAS_ER_FREE_SERVER} also marks a host as
     * unreachable). RW has no priority hierarchy - the only preferred target is {@link
     * #getHomeRwEndpoint() the master}. Therefore, this method checks only the master endpoint and,
     * upon success, rebinds the RW connection (and the read connection as well, if reads are routed
     * over RW).
     *
     * <p>Without this mechanism, a rerouted session would continue writing to the secondary broker
     * for its entire lifetime, and only connection pool recycling ({@code maxLifetime}, evict)
     * could restore it to the primary master.
     *
     * <p>The reachability check mechanism is identical to RO failback: it relies on the JCI
     * unreachable host list combined with {@link #softExcludedIds}. As a result, a blacklisted
     * master is probed once per backoff interval rather than waiting for the 60-second JCI polling
     * cycle, and a successful probe clears the blacklist for other sessions.
     *
     * <p><b>Caller contract:</b> like {@link #restoreRoIfRecovered()}, this method replaces the
     * active physical connection and closes the previous one, terminating any active resources on
     * it. Call it only at a safe boundary - when no statement is executing and no uncommitted
     * transaction exists since the last commit or rollback. {@code
     * LoadBalanceConnection.failbackRwIfStatementAllows} enforces this rule.
     *
     * @return the currently bound RW endpoint, or {@code null} if no change occurred (failback is
     *     disabled, already connected to master, master remains blocked, or the probe failed)
     * @throws SQLException if the rebind fails after opening the probe connection
     */
    public synchronized Endpoint restoreRwIfRecovered() throws SQLException {
        if (!config.isWriteFailbackEnabled() || homeRwEp == null || sessTopo == null) {
            return null;
        }
        if (!isRwDisplaced()) {
            return null;
        }

        // Mirror of rebindReadOntoRwConn: a master-read session's read leg may have returned home
        // first, in which case it already holds a live connection to this endpoint. Reuse it
        // instead
        // of opening a second one, which would land on the same connsByEpId key and close the read
        // leg's connection. Whenever both roles name one endpoint they must share one connection,
        // whichever leg gets there first.
        if (sessRoEp != null && sessRoEp.getId().equals(homeRwEp.getId())) {
            final Endpoint reused = rebindRwOntoReadConn(homeRwEp);
            if (reused != null) {
                return reused;
            }
        }

        // Soft gate: a backoff-due master still on the JCI unreachable list becomes a candidate
        // again, so recovery is detected within one interval rather than on the 60s JCI poll.
        if (softExcludedIds(sessTopo, System.nanoTime()).contains(homeRwEp.getId())) {
            return null;
        }

        final BindResult bind;
        try {
            bind =
                    openFirstReachable(
                            Collections.singletonList(homeRwEp),
                            "RW",
                            Collections.<String>emptySet(),
                            0);
        } catch (SQLException ex) {
            return null; // master still down (or still CAS-saturated); retry on a later boundary
        }

        final Endpoint from = sessRwEp;
        // RW-only / master-read sessions map the read role onto the RW endpoint id itself; that
        // logical key must move with the RW leg, otherwise reads stay keyed to the stranded node.
        final boolean readRidesOnRwEp = sessRoEp != null && sessRoEp.getId().equals(from.getId());
        replaceRwConn(from, bind.endpoint, bind.connection);
        if (readRidesOnRwEp) {
            // replaceRwConn re-published the connection under the old id for the roOnRw case; drop
            // that stale key (the connection itself lives on under the new RW id) and move the read
            // role, and its home when the read home was the RW endpoint, onto the master.
            connsByEpId.remove(from.getId());
            sessRoEp = bind.endpoint;
            if (homeReadEp != null && homeReadEp.getId().equals(from.getId())) {
                homeReadEp = bind.endpoint;
            }
        }
        markRecovered(bind.endpoint);
        MetricsRegistry.recordFailback("RW", from.getId(), bind.endpoint.getId());
        LbLogDedup.warn(
                LOGGER,
                logContext,
                "FAILBACK|RW|" + from.getId() + "|" + bind.endpoint.getId(),
                "LB FAILBACK [RW]: "
                        + from.getId()
                        + " -> "
                        + bind.endpoint.getId()
                        + " (master recovered; RW leg returned to the master node)");
        return bind.endpoint;
    }

    /**
     * Mirror of {@link #rebindReadOntoRwConn}: rebinds the RW role onto the connection the read leg
     * already holds for the same endpoint, without opening anything.
     *
     * @param rwEp the master RW endpoint, which the read role is already bound to
     * @return {@code rwEp} when the RW role was rebound, or {@code null} when there is no usable
     *     connection to reuse (the caller then takes the normal probe path)
     * @throws SQLException if dropping the stale prepares of the displaced RW endpoint fails
     */
    private Endpoint rebindRwOntoReadConn(final Endpoint rwEp) throws SQLException {
        final Connection shared = connsByEpId.get(rwEp.getId());
        if (shared == null || shared.isClosed()) {
            return null;
        }

        final Endpoint from = sessRwEp;
        final Connection previous = connsByEpId.get(from.getId());
        connsByEpId.remove(from.getId());
        dropPrepForEp(from.getId());
        sessRwEp = rwEp;

        if (previous != null && previous != shared && !connInUseElsewhere(previous)) {
            closeQuietly(previous);
        }
        markRecovered(rwEp);
        MetricsRegistry.recordFailback("RW", from.getId(), rwEp.getId());
        LbLogDedup.warn(
                LOGGER,
                logContext,
                "FAILBACK|RW|" + from.getId() + "|" + rwEp.getId(),
                "LB FAILBACK [RW]: "
                        + from.getId()
                        + " -> "
                        + rwEp.getId()
                        + " (master recovered; RW leg rejoined the read leg's connection on the master;"
                        + " reused, no new connection opened)");
        return rwEp;
    }

    /**
     * Rebinds the read role onto the live RW physical connection — the master-read state {@link
     * #bindSessionRwOnly} produces — without opening anything. {@code roOnRw} stays {@code false}
     * because for such a session the RW endpoint IS the weighted home, not a fallback rung.
     *
     * @param rwEp the RW endpoint, which is also this session's read home
     * @return {@code rwEp} when the read role was rebound, or {@code null} when there is no usable
     *     RW connection to reuse (the caller then takes the normal probe path)
     * @throws SQLException if dropping the stale read prepares fails
     */
    private Endpoint rebindReadOntoRwConn(final Endpoint rwEp) throws SQLException {
        final Connection rwConn = connsByEpId.get(rwEp.getId());
        if (rwConn == null || rwConn.isClosed()) {
            return null;
        }

        final Endpoint previousReadKey = sessRoEp != null ? sessRoEp : homeReadEp;
        final String fromId =
                roOnRw && sessRwEp != null ? sessRwEp.getId() : previousReadKey.getId();
        final Connection previous = connsByEpId.get(previousReadKey.getId());
        if (!previousReadKey.getId().equals(rwEp.getId())) {
            connsByEpId.remove(previousReadKey.getId());
            dropPrepForEp(previousReadKey.getId());
        }
        sessRoEp = rwEp;
        // Not a fallback any more: this session's read home IS the RW endpoint, so reads sharing
        // that connection is the intended placement.
        setRoOnRw(false, rwEp.getId(), "read home is the RW endpoint");

        if (previous != null && previous != rwConn && !connInUseElsewhere(previous)) {
            closeQuietly(previous);
        }
        markRecovered(rwEp);
        MetricsRegistry.recordFailback("RO", fromId, rwEp.getId());
        LbLogDedup.warn(
                LOGGER,
                logContext,
                "FAILBACK|RO|" + fromId + "|" + rwEp.getId(),
                "LB FAILBACK [RO]: "
                        + fromId
                        + " -> "
                        + rwEp.getId()
                        + " (read leg returned to its home on the RW connection; reused, no new"
                        + " connection opened)");
        return rwEp;
    }

    /** A session's RW leg is displaced when it is not bound to the master RW endpoint. */
    private boolean isRwDisplaced() {
        if (sessRwEp == null || homeRwEp == null) {
            return false;
        }
        return !sessRwEp.getId().equals(homeRwEp.getId());
    }

    /**
     * The master RW endpoint this session should be bound to, or {@code null} when unbound.
     *
     * @return the home RW endpoint, or {@code null}
     */
    public synchronized Endpoint getHomeRwEndpoint() {
        return homeRwEp;
    }

    /** A session is displaced when reads are not currently served by the weighted home endpoint. */
    private boolean isReadDisplaced() {
        if (roOnRw) {
            return true;
        }
        if (sessRoEp == null || homeReadEp == null) {
            return false;
        }
        return !sessRoEp.getId().equals(homeReadEp.getId());
    }

    /**
     * Best-first read candidate ladder anchored on {@code home}: home, then same-role siblings,
     * then cross-role. Slave ROs are the RO endpoints that are not replica SOs; replica SOs are the
     * REPL endpoints. Used by failover (the failed endpoint is skipped via {@code exclude}) and by
     * failback.
     *
     * <p><b>Each tier is rotated per session</b> ({@link #assignedLadderOffset()}). With several
     * slaves or replicas, every displaced session used to take the first entry of the tier in
     * declaration order, so one node absorbed the whole displaced share. The tier order itself is
     * untouched, so home still comes first and failback still climbs toward home.
     */
    private List<Endpoint> readCandidateOrder(
            final Endpoint home, final EndpointTopology topology) {
        final List<Endpoint> order = new ArrayList<Endpoint>();
        final List<List<Endpoint>> tiers = readCandidateTiers(home, topology);
        for (int i = 0; i < tiers.size(); i++) {
            appendNew(order, tiers.get(i));
        }
        return order;
    }

    /**
     * The same ladder as {@link #readCandidateOrder}, kept as SEPARATE TIERS: {@code [home]},
     * {@code [same-role siblings]}, {@code [cross-role]}. Empty tiers are kept so a caller can tell
     * "no sibling exists" from "the sibling failed".
     *
     * <p>Recovery needs the tier boundaries, because {@code
     * cubrid.lb.runtime.failover.max.attempts.per.group} bounds connect attempts PER ROLE GROUP.
     * Counted over the flattened ladder, the default of 1 means one dial per recovery: a displaced
     * replica spends it on its sibling SO, never reaches the cross-role slave RO, and lands on
     * {@code roOnRw} - the master - while a slave RO is alive. Which sessions escaped that depended
     * on whether another session had already flagged the sibling unreachable, so race order decided
     * the outcome. Covered by {@code LadderSpreadLbTest} phase 7 and {@code
     * SessionPhysicalConnManagerLadderSpreadTest#crossRoleIsReachedWhenTheSiblingTierFails}.
     */
    private List<List<Endpoint>> readCandidateTiers(
            final Endpoint home, final EndpointTopology topology) {
        final List<List<Endpoint>> tiers = new ArrayList<List<Endpoint>>();
        if (home != null) {
            tiers.add(Collections.singletonList(home));
        }
        if (topology == null) {
            return tiers;
        }

        final List<Endpoint> replEps = topology.getReplEndpoints();
        final List<Endpoint> slaveRoEps = subtractById(topology.getRoEndpoints(), replEps);
        final boolean homeIsReplica = containsById(replEps, home);

        if (homeIsReplica) {
            tiers.add(siblingsWithoutHomeRotated(replEps, home)); // sibling replicas (SO)
            tiers.add(siblingsWithoutHomeRotated(slaveRoEps, home)); // cross-role slave ROs
        } else {
            tiers.add(siblingsWithoutHomeRotated(slaveRoEps, home)); // sibling slaves (RO)
            tiers.add(siblingsWithoutHomeRotated(replEps, home)); // cross-role replica SOs
        }
        return tiers;
    }

    /**
     * Rotates a candidate tier by this session's ladder offset, so sessions displaced at the same
     * time do not all land on the tier's first node. A rotation, not a shuffle, keeps the tier's
     * relative order, and the offset is fixed per session so the ladder - and with it {@code
     * rungRankOf}'s notion of a better target - stays stable across repeated failback probes.
     *
     * <p><b>{@code home} is removed before rotating</b>, not deduplicated afterwards. Rotating a
     * tier that still holds home wastes the rotations that put home first: with tier {@code
     * [A,B,C]} and {@code home=A}, offsets 0 and 1 both leave B first among the siblings, so B
     * draws two thirds of the displaced share. Rotating the {@code size-1} sibling list spreads
     * them evenly.
     */
    private List<Endpoint> siblingsWithoutHomeRotated(
            final List<Endpoint> tier, final Endpoint home) {
        if (tier == null || tier.isEmpty()) {
            return tier;
        }
        final List<Endpoint> siblings =
                containsById(tier, home)
                        ? subtractById(tier, Collections.singletonList(home))
                        : tier;
        if (siblings.size() <= 1) {
            return siblings;
        }
        final int off = assignedLadderOffset(home) % siblings.size();
        if (off == 0) {
            return siblings;
        }
        final List<Endpoint> rotated = new ArrayList<Endpoint>(siblings.size());
        for (int i = 0; i < siblings.size(); i++) {
            rotated.add(siblings.get((i + off) % siblings.size()));
        }
        return rotated;
    }

    /**
     * This session's tier rotation offset, assigned on first use from the counter for its home
     * endpoint (see {@link #LADDER_ROTATION} for why the counter must be per-home and not
     * JVM-wide). Assigned once and then fixed, so repeated failback probes see a stable ladder.
     */
    private int assignedLadderOffset(final Endpoint home) {
        if (ladderOffset < 0) {
            final String key = home == null ? "" : home.getId();
            java.util.concurrent.atomic.AtomicInteger counter = LADDER_ROTATION.get(key);
            if (counter == null) {
                final java.util.concurrent.atomic.AtomicInteger created =
                        new java.util.concurrent.atomic.AtomicInteger();
                counter = LADDER_ROTATION.putIfAbsent(key, created);
                if (counter == null) {
                    counter = created;
                }
            }
            ladderOffset = counter.getAndIncrement() & 0x7fffffff;
        }
        return ladderOffset;
    }

    /**
     * Rank of {@code ep} within {@code order}, compared by endpoint id: its position when present,
     * and {@code order.size()} - one past the last entry, so ranked below everything - when it is
     * absent or {@code null}.
     *
     * <p><b>Not the {@code indexOf} contract.</b> {@code List.indexOf} answers "not found" with
     * {@code -1}. This answers with a rank, because every caller compares positions instead of
     * looking one up: {@code restoreRoIfRecovered} collects the endpoints strictly better than the
     * current one, and an absent (or {@code roOnRw}) read leg must rank <em>worst</em> so that all
     * of them qualify. With {@code -1} it would collect nothing and read failback would never fire.
     *
     * <p>Callers therefore compare against {@code size()}, not against zero: {@link #containsById}
     * tests {@code < size()} and {@link #appendNew} tests {@code == size()}.
     *
     * @param order the candidate list to rank within
     * @param ep the endpoint to rank; {@code null} ranks last
     * @return the position of {@code ep}, or {@code order.size()} when it is not in {@code order}
     */
    private static int rungRankOf(final List<Endpoint> order, final Endpoint ep) {
        if (ep == null) {
            return order.size();
        }
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).getId().equals(ep.getId())) {
                return i;
            }
        }
        return order.size();
    }

    private static boolean containsById(final List<Endpoint> list, final Endpoint ep) {
        return ep != null && rungRankOf(list, ep) < list.size();
    }

    private static List<Endpoint> subtractById(
            final List<Endpoint> from, final List<Endpoint> remove) {
        final List<Endpoint> out = new ArrayList<Endpoint>();
        for (int i = 0; i < from.size(); i++) {
            final Endpoint e = from.get(i);
            if (!containsById(remove, e)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Append endpoints not already present (by id) to {@code order}. */
    private static void appendNew(final List<Endpoint> order, final List<Endpoint> add) {
        for (int i = 0; i < add.size(); i++) {
            final Endpoint e = add.get(i);
            if (rungRankOf(order, e) == order.size()) {
                order.add(e);
            }
        }
    }

    /**
     * The immutable weighted read assignment (ratio anchor) for this session, or {@code null}.
     *
     * @return the home read endpoint, or {@code null}
     */
    public synchronized Endpoint getHomeReadEndpoint() {
        return homeReadEp;
    }

    /**
     * Rebinds the read role from {@code previousRoEp} onto {@code newRo}, or onto the RW connection
     * when {@code rwFallback}. The first parameter is the endpoint the read leg is
     * <em>leaving</em>, which is not always a failed one: {@link #restoreRoIfRecovered()} calls
     * this on the failback path, where the leg is climbing back to a better rung and nothing failed
     * at all.
     */
    private void replaceRoConn(
            final Endpoint previousRoEp,
            final Endpoint newRo,
            final Connection newConn,
            final boolean rwFallback)
            throws SQLException {
        Connection previous = connsByEpId.get(previousRoEp.getId());
        connsByEpId.remove(previousRoEp.getId());
        dropPrepForEp(previousRoEp.getId());

        setRoOnRw(
                rwFallback,
                sessRwEp != null ? sessRwEp.getId() : "?",
                rwFallback
                        ? "read leg replaced by the RW connection"
                        : "read leg replaced by " + newRo.getId());

        if (rwFallback) {
            String logicalRoKey = sessRoEp != null ? sessRoEp.getId() : previousRoEp.getId();
            putConnClosingDisplaced(logicalRoKey, newConn);
        } else {
            putConnClosingDisplaced(newRo.getId(), newConn);
            sessRoEp = newRo;
        }

        if (previous != null && previous != newConn && !connInUseElsewhere(previous)) {
            closeQuietly(previous);
        }
    }

    private void replaceRwConn(
            final Endpoint failedRw, final Endpoint newRw, final Connection newConn)
            throws SQLException {
        Connection previous = connsByEpId.get(failedRw.getId());
        connsByEpId.remove(failedRw.getId());
        dropPrepForEp(failedRw.getId());

        putConnClosingDisplaced(newRw.getId(), newConn);
        sessRwEp = newRw;

        if (roOnRw && sessRoEp != null) {
            putConnClosingDisplaced(sessRoEp.getId(), newConn);
        }

        if (previous != null && previous != newConn && !connInUseElsewhere(previous)) {
            closeQuietly(previous);
        }
    }

    private static Set<String> excludedIdsWith(
            final Set<String> excludeEndpointIds, final Endpoint failedEndpoint) {
        Set<String> merged = new HashSet<String>();
        if (excludeEndpointIds != null) {
            merged.addAll(excludeEndpointIds);
        }

        if (failedEndpoint != null) {
            merged.add(failedEndpoint.getId());
        }
        return merged;
    }

    private Set<String> unreachableIds(final EndpointTopology topology) {
        if (topology == null) {
            return Collections.<String>emptySet();
        }

        return unreachFilter.collectUnreachableEndpointIds(topology);
    }

    /**
     * Soft-gate variant of {@link #unreachableIds}: the JCI-unreachable set minus endpoints whose
     * backoff window has elapsed AND this call won the pool-wide probe claim — those re-enter the
     * candidate list so one session per interval probes a down broker instead of waiting for the
     * 60s JCI poll. Reachable-broker behavior is unchanged (empty in → empty out).
     */
    Set<String> softExcludedIds(final EndpointTopology topology, final long nowNanos) {
        Set<String> unreachable = unreachableIds(topology);
        if (unreachable.isEmpty()) {
            return unreachable;
        }

        Set<String> soft = new HashSet<String>(unreachable);
        for (String epId : unreachable) {
            if (backoff.tryClaimProbe(epId, nowNanos)) {
                soft.remove(epId); // this session claimed the probe -> allow as candidate
            }
        }
        return soft;
    }

    /**
     * A new set holding every id from both inputs; neither input is modified. Nothing here is
     * specific to soft gating - {@link #recoveryExcludedIds} decides which sets to combine and
     * {@link #softExcludedIds} does the softening. Package-private so the exclude-set tests can use
     * it directly.
     */
    static Set<String> union(final Set<String> first, final Set<String> second) {
        Set<String> merged = new HashSet<String>(first);
        merged.addAll(second);
        return merged;
    }

    private void dropPrepForEp(final String endpointId) throws SQLException {
        removePrepWhere(
                new PrepKeyMatcher() {
                    public boolean matches(final String key) {
                        return key.startsWith(endpointId + "|");
                    }
                });
    }

    /**
     * Binds {@code newConn} at {@code key}, reclaiming any live connection already there. Failover
     * recovery can pick a candidate (home, for instance) that still has an open physical connection
     * cached; a plain {@code put} would drop that reference without closing it and leave its prep
     * cache bound to the lost connection while {@code getPhyConn} returns the new one - a split
     * physical connection. Closing the displaced connection and dropping its endpoint's prep
     * entries keeps one live connection per endpoint. A connection still referenced under another
     * key (a roOnRw session) is left open.
     */
    private void putConnClosingDisplaced(final String key, final Connection newConn)
            throws SQLException {
        Connection displaced = connsByEpId.put(key, newConn);
        if (displaced == null || displaced == newConn) {
            return;
        }
        dropPrepForEp(key);
        if (!connInUseElsewhere(displaced)) {
            closeQuietly(displaced);
        }
    }

    private boolean connInUseElsewhere(final Connection connection) {
        for (Connection each : connsByEpId.values()) {
            if (each == connection) {
                return true;
            }
        }
        return false;
    }

    public String getLogicalJdbcUrl() {
        return logicalJdbcUrl;
    }

    /**
     * The CAS this physical connection is talking to: {@code ip:port/casId/casProcessId}, the tuple
     * the broker assigns at connect time. {@code null} when it cannot be read - a test double that
     * is not a {@link CUBRIDConnection}, or a connection whose {@code UConnection} is gone - in
     * which case the check below is skipped rather than guessed at.
     */
    private static String casIdentityOf(final Connection conn) {
        if (!(conn instanceof CUBRIDConnection)) {
            return null;
        }

        try {
            UConnection u = ((CUBRIDConnection) conn).getUConnection();
            if (u == null) {
                return null;
            }

            return u.getCasIp() + ":" + u.getCasPort() + "/" + u.casId + "/" + u.casProcessId;
        } catch (SQLException unreadable) {
            return null;
        } catch (RuntimeException unreadable) {
            return null; // a stub connection may not carry a UConnection at all
        }
    }

    private void rememberCasIdentity(final Endpoint endpoint, final Connection conn) {
        String identity = casIdentityOf(conn);
        if (identity == null) {
            casIdentityByEpId.remove(endpoint.getId());

            return;
        }
        casIdentityByEpId.put(endpoint.getId(), identity);
    }

    /**
     * Re-applies the session state when core has reconnected this leg underneath LB.
     *
     * <p>Core's reconnect is invisible from here: the {@code Connection} object is the same one,
     * the socket behind it is not. Comparing the CAS identity recorded when LB last applied the
     * state detects it, and re-applying costs one round of property calls on a connection that was
     * about to be used anyway. Without this, {@code casChangeMode} silently reverts to the broker
     * default (ISSUE-7); isolation and lock timeout are restored by core itself, and {@code
     * autoCommit} rides on every execute, so those need nothing.
     *
     * <p>Failure to re-apply propagates: the leg is then in an unknown state, and letting the
     * caller fail is what puts it through the failover handler.
     */
    private void reapplySessionStateIfCasReplaced(final Endpoint endpoint, final Connection conn)
            throws SQLException {
        final String known = casIdentityByEpId.get(endpoint.getId());
        if (known == null) {
            return;
        }

        final String current = casIdentityOf(conn);
        if (current == null || known.equals(current)) {
            return;
        }

        if (sessionStateApplier != null) {
            sessionStateApplier.applyTo(conn);
        }
        casIdentityByEpId.put(endpoint.getId(), current);
        LbLog.fine(
                LOGGER,
                logContext,
                "LB CAS REBOUND: "
                        + endpoint.getId()
                        + " reconnected by the core driver ("
                        + known
                        + " -> "
                        + current
                        + "); session state re-applied");
    }

    private Connection openConnection(final Endpoint endpoint) throws SQLException {
        JdbcPhyConnSpec spec = config.buildPhysicalJdbcSpec(logicalJdbcUrl, clientInfo, endpoint);

        // The physical connect uses the per-broker classic URL (spec.getJdbcUrl()); carry the
        // original URI (loadbalance://) URL the user wrote, password-masked, as the user URL so
        // JDBC errors and the CAS DB_INFO handshake show it instead of the rewritten classic URL.
        // Only for URI (://) logical URLs — a classic alt-host logical URL is already handled (and
        // password-masked) by the driver's own resolvedUrl path.
        Properties physicalProps = spec.getConnectionProperties();
        if (logicalJdbcUrl != null && logicalJdbcUrl.indexOf("://") >= 0) {
            physicalProps.setProperty(
                    CUBRIDDriver.USER_URL_PROPERTY,
                    CUBRIDDriver.maskUriUrlPassword(logicalJdbcUrl, physicalProps));
        }

        Connection conn = connectionFactory.getConnection(spec.getJdbcUrl(), physicalProps);

        // Single chokepoint for every fresh physical connection (initial bind, recoverRw/recoverRo,
        // failoverRoToRw, restoreRoIfRecovered/restoreRwIfRecovered, ensureConn reopen). Re-apply
        // the session state (autoCommit/isolation/schema/lockTimeout/casChangeMode/holdability) so
        // a connection opened mid-session is not left at the driver defaults.
        if (sessionStateApplier != null) {
            try {
                sessionStateApplier.applyTo(conn);
            } catch (SQLException e) {
                closeQuietly(conn);
                throw e;
            }
        }
        rememberCasIdentity(endpoint, conn);

        return conn;
    }

    private void closeConnsLocked() {
        Set<Connection> distinct = new HashSet<Connection>(connsByEpId.values());

        for (Connection c : distinct) {
            closeQuietly(c);
        }
        connsByEpId.clear();
        casIdentityByEpId.clear();
        sessRwEp = null;
        sessRoEp = null;
        homeReadEp = null;
        homeRwEp = null;
        sessTopo = null;
        clearRoOnRw();
    }

    private void closePrepLocked() {
        for (PreparedStatement ps : prepByKey.values()) {
            closeQuietly(ps);
        }
        prepByKey.clear();
        prepCacheWarned = false;
    }

    /**
     * Reports cache growth as it happens, and warns once when it passes the threshold.
     *
     * <p>The warning fires a single time, so a leak that keeps growing would look exactly like one
     * that stopped at the threshold. A snapshot every {@link #PREP_CACHE_SNAPSHOT_EVERY} entries
     * turns that into a visible slope: the interval between snapshots is how fast statements are
     * created without being closed, and a settled session stops producing them.
     *
     * <p>Sampled on entry count rather than on a timer: a JDBC driver should not run a thread for
     * diagnostics, and the count is the quantity in question anyway.
     */
    private void trackPrepCacheGrowth() {
        final int size = prepByKey.size();
        if (size > 0 && size % PREP_CACHE_SNAPSHOT_EVERY == 0) {
            LbLog.fine(
                    LOGGER,
                    logContext,
                    "LB PREP CACHE: "
                            + size
                            + " physical prepared statements held across "
                            + connsByEpId.size()
                            + " connection(s)");
        }

        if (prepCacheWarned || size < PREP_CACHE_WARN_THRESHOLD) {
            return;
        }
        prepCacheWarned = true;
        LbLog.warn(
                LOGGER,
                logContext,
                "LB physical prepared-statement cache exceeded "
                        + PREP_CACHE_WARN_THRESHOLD
                        + " entries ("
                        + prepByKey.size()
                        + "); this usually means PreparedStatements are being created without being"
                        + " closed. The cache is not auto-evicted (evicting a live statement would"
                        + " destroy an open ResultSet); close PreparedStatements to release entries.");
    }

    private static void closeQuietly(final PreparedStatement ps) {
        if (ps == null) {
            return;
        }

        try {
            ps.close();
        } catch (SQLException e) {
        }
    }

    private static void closeQuietly(final Connection c) {
        if (c == null) {
            return;
        }

        try {
            c.close();
        } catch (SQLException e) {
        }
    }
}
