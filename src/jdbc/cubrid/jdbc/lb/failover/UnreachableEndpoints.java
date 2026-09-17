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

package cubrid.jdbc.lb.failover;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.log.LbLog;
import cubrid.jdbc.lb.log.LbLogDedup;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Reads and updates the JVM-wide JCI {@link UUnreachableHostList} for LB broker candidates.
 *
 * <p>LB physical URLs omit {@code altHosts}, so Core does not populate the shared list; {@link
 * #markUnreachable(Endpoint, SQLException)} records connect failures using the same errno set as
 * {@code UClientSideConnection} altHosts reconnect, plus the HA handshake rejections listed on
 * {@code isHandshakeRejection}.
 */
public final class UnreachableEndpoints {

    // CUBRID server error codes (base/error_code.h). The driver does not expose them as constants,
    // so the ones this filter reacts to are declared here with their upstream names.
    /** Umbrella handshake failure raised by the client library when capability check fails. */
    private static final int ER_NET_SERVER_HAND_SHAKE = -743;
    /** RO/SO client reached a server whose update-ability does not match (standby got promoted). */
    private static final int ER_NET_HS_INCOMPAT_RW_MODE = -1139;
    /** Standby/replica lags past {@code ha_delay_limit} and refuses read-only clients. */
    private static final int ER_NET_HS_HA_REPL_DELAY = -1140;
    /** Replica-only client reached a server that is not a replica. */
    private static final int ER_NET_HS_HA_REPLICA_ONLY = -1141;
    /** Server refuses this client type from a remote host. */
    private static final int ER_NET_HS_REMOTE_DISABLED = -1142;

    /**
     * Backstop on {@link #shouldMarkUnreachable} traversal for cyclic/pathological cause chains.
     */
    private static final int MAX_EXCEPTION_CHAIN_DEPTH = 64;

    private static final Logger LOGGER = Logger.getLogger(UnreachableEndpoints.class.getName());

    private final UUnreachableHostList unreachableHostList;

    public UnreachableEndpoints() {
        this(UUnreachableHostList.getInstance());
    }

    UnreachableEndpoints(final UUnreachableHostList unreachableHostList) {
        if (unreachableHostList == null) {
            throw new IllegalArgumentException("unreachableHostList must not be null");
        }
        this.unreachableHostList = unreachableHostList;
    }

    public boolean isUnreachable(final Endpoint endpoint) {
        if (endpoint == null) {
            return false;
        }

        return unreachableHostList.contains(endpoint.getId());
    }

    /**
     * Whether {@code endpointId} (host:port) is currently on the JVM-wide unreachable-host list,
     * i.e. excluded from binding and failover candidates. Static and id-based so an exporter can
     * render a per-endpoint reachability gauge without holding an {@link Endpoint} or a session.
     *
     * @param endpointId the {@code host:port} endpoint id; may be {@code null}
     * @return whether the endpoint is currently marked unreachable
     */
    public static boolean isEndpointIdUnreachable(final String endpointId) {
        if (endpointId == null) {
            return false;
        }

        return UUnreachableHostList.getInstance().contains(endpointId);
    }

    public static boolean shouldMarkUnreachable(final SQLException ex) {
        if (ex == null) {
            return false;
        }

        // Traverse both edges of every node - getCause() and, for SQLException, getNextException()
        // - so an IOException hidden under a SQLException that also chains a nextException is not
        // missed. The identity visited set makes a cyclic chain terminate; the depth cap backs it
        // up.
        Set<Throwable> visited =
                Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        // ArrayDeque forbids null elements, so only push non-null edges.
        Deque<Throwable> stack = new ArrayDeque<Throwable>();
        stack.push(ex);
        while (!stack.isEmpty() && visited.size() < MAX_EXCEPTION_CHAIN_DEPTH) {
            Throwable current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }

            if (current instanceof IOException) {
                return true;
            }

            if (current instanceof SQLException) {
                SQLException sqlEx = (SQLException) current;
                if (isUnreachableErrorCode(sqlEx.getErrorCode())) {
                    return true;
                }
                SQLException next = sqlEx.getNextException();
                if (next != null) {
                    stack.push(next);
                }
            }

            Throwable cause = current.getCause();
            if (cause != null) {
                stack.push(cause);
            }
        }
        return false;
    }

    /**
     * Adds {@code endpoint} to the JVM-wide unreachable set when {@code cause} says it is down, and
     * records the entry the first time it happens.
     *
     * <p>Membership is why an endpoint is skipped while candidates are tried, so without that
     * record a failover that passes over a node looks arbitrary - the node may have been marked by
     * another connection, minutes earlier. The set is process-level (the same list the core JCI
     * althost reconnect consults), so the entry carries no connection context and collapses per
     * endpoint.
     *
     * @param endpoint the endpoint to mark, ignored when {@code null}
     * @param cause the failure that decides whether the endpoint counts as down
     */
    public void markUnreachable(final Endpoint endpoint, final SQLException cause) {
        if (endpoint == null || !shouldMarkUnreachable(cause)) {
            return;
        }

        final String id = endpoint.getId();
        final boolean wasReachable = !unreachableHostList.contains(id);
        unreachableHostList.add(id);

        if (wasReachable) {
            LbLogDedup.info(
                    LOGGER,
                    null,
                    "UNREACHABLE|in|" + id,
                    "LB UNREACHABLE: "
                            + id
                            + " entered the unreachable set -- candidate selection"
                            + " will skip it until it is probed back | cause: "
                            + LbLog.cause(cause));
        }
    }

    /**
     * Drops {@code endpoint} from the unreachable set after a successful reconnect, recording the
     * exit so the window during which the node was skipped is bounded on both sides.
     *
     * @param endpoint the endpoint to clear, ignored when {@code null}
     */
    public void markReachable(final Endpoint endpoint) {
        if (endpoint == null) {
            return;
        }

        final String id = endpoint.getId();
        final boolean wasUnreachable = unreachableHostList.contains(id);
        unreachableHostList.remove(id);

        if (wasUnreachable) {
            LbLogDedup.info(
                    LOGGER,
                    null,
                    "UNREACHABLE|out|" + id,
                    "LB UNREACHABLE: "
                            + id
                            + " left the unreachable set -- it is a candidate again");
        }
    }

    public Set<String> collectUnreachableEndpointIds(final EndpointTopology topology) {
        Set<String> exclude = new HashSet<String>();
        if (topology == null) {
            return exclude;
        }

        collectFromList(topology.getRwEndpoints(), exclude);
        collectFromList(topology.getRoEndpoints(), exclude);
        collectFromList(topology.getReplEndpoints(), exclude);
        return exclude;
    }

    /**
     * Server-side handshake rejection: the broker answered, but its CAS could not attach to a DB
     * server acceptable for the broker's access mode. {@code ER_NET_SERVER_HAND_SHAKE} is the
     * umbrella error the client library raises; the {@code ER_NET_HS_*} codes are the specific
     * causes it can surface instead.
     *
     * <p>Two of them occur in normal HA operation:
     *
     * <ul>
     *   <li>{@code ER_NET_HS_INCOMPAT_RW_MODE} - an RO/SO broker's CAS reached a server whose
     *       update-ability does not match (the standby it used was promoted to master);
     *   <li>{@code ER_NET_HS_HA_REPL_DELAY} - the standby/replica lags past {@code ha_delay_limit}
     *       and refuses read-only clients until it catches up.
     * </ul>
     *
     * <p>Both mean "this endpoint cannot serve me right now, but the broker is alive", which is
     * what the unreachable list and the backoff probe exist for. Without them a lagging replica
     * makes every statement on that leg fail, because the failover gate never fires.
     *
     * <p>Left out on purpose: {@code ER_CSS_CLIENTS_EXCEEDED} (-669, server {@code max_clients}
     * exhausted). That is capacity, not "this broker cannot serve this role"; marking it would push
     * every session off the node JVM-wide, writes included, for something that usually clears in
     * seconds.
     */
    private static boolean isHandshakeRejection(final int errorCode) {
        switch (errorCode) {
            case ER_NET_SERVER_HAND_SHAKE:
            case ER_NET_HS_INCOMPAT_RW_MODE:
            case ER_NET_HS_HA_REPL_DELAY:
            case ER_NET_HS_HA_REPLICA_ONLY:
            case ER_NET_HS_REMOTE_DISABLED:
                return true;
            default:
                return false;
        }
    }

    private static boolean isUnreachableErrorCode(final int errorCode) {
        switch (errorCode) {
            case UErrorCode.ER_COMMUNICATION:
            case UErrorCode.ER_CONNECTION:
            case UErrorCode.ER_TIMEOUT:
            case UErrorCode.CAS_ER_FREE_SERVER:
                return true;
            default:
                return isHandshakeRejection(errorCode);
        }
    }

    private void collectFromList(final List<Endpoint> endpoints, final Set<String> exclude) {
        if (endpoints == null) {
            return;
        }

        for (int i = 0; i < endpoints.size(); i++) {
            Endpoint endpoint = endpoints.get(i);
            if (isUnreachable(endpoint)) {
                exclude.add(endpoint.getId());
            }
        }
    }
}
