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

package cubrid.jdbc.lb.route;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.NodeRole;
import cubrid.jdbc.lb.config.ReadWeight;
import cubrid.jdbc.lb.config.ResolvedRoleTopology;
import cubrid.jdbc.lb.config.ResolvedRoleTopology.ResolvedNode;
import cubrid.jdbc.lb.config.ResolvedRoleTopology.ResolvedReplica;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.util.ArrayList;
import java.util.List;

/**
 * Role-based read endpoint selection:
 *
 * <ol>
 *   <li>pick the <b>role</b> (master/slave/replica) whose share of the pool's LIVE sessions is
 *       furthest below its {@link ReadWeight} target, and count the new session against it — the
 *       live counts are pool-shared in {@link SharedSelectorState} under {@code role:*} keys;
 *   <li>within the chosen role's node group, pick a node by <b>even round-robin</b> ({@link
 *       SharedSelectorState#nextGroupRoundRobinIndex}).
 * </ol>
 *
 * <p><b>Every selection must be paired with {@link #release}</b> when the session closes. A home is
 * fixed for the session's life, so readWeight only holds if selection balances the live population.
 * A stream-only algorithm would drift toward the master for good, because a master-home session
 * reads on the RW connection and is never the one a read-broker outage replaces. See {@link
 * SharedSelectorState#acquireIndexByPopulation}.
 *
 * <p>A role is eligible only if its weight is &gt; 0 and it has at least one node. The master read
 * endpoint is the master's RW broker (roOnRw); slave/replica reads use the node's RO/SO broker. If
 * no role is eligible, selection falls back to the master RW (RW-only).
 */
public final class RoleReadSelector {
    private static final String HOME_KEY_PREFIX = "role:";
    private static final String RR_KEY_SLAVE = "slave";
    private static final String RR_KEY_REPLICA = "replica";

    public ReadTarget select(
            final ResolvedRoleTopology topology,
            final ReadWeight weights,
            final SharedSelectorState sharedState) {
        if (topology == null) {
            throw new IllegalArgumentException("ResolvedRoleTopology must not be null");
        }
        if (weights == null) {
            throw new IllegalArgumentException("ReadWeight must not be null");
        }

        final List<NodeRole> candidates = candidateRoles(topology, weights);

        final NodeRole role;
        if (candidates.isEmpty()) {
            role = NodeRole.MASTER; // RW-only fallback (no read distribution)
            acquire(role, sharedState);
        } else if (candidates.size() == 1) {
            role = candidates.get(0);
            acquire(role, sharedState);
        } else {
            role = selectRoleByPopulation(candidates, weights, sharedState);
        }

        return new ReadTarget(role, endpointForRole(role, topology, sharedState));
    }

    /**
     * Releases the home slot a previous {@link #select} handed out. The caller owns the pairing:
     * one release per selected session, when it closes or rebinds to another home. Without it the
     * live population never shrinks and the role stops being selected.
     *
     * @param role the role returned by {@link #select}
     * @param sharedState the state the selection was made against; {@code null} is a no-op
     */
    public static void release(final NodeRole role, final SharedSelectorState sharedState) {
        if (role == null || sharedState == null) {
            return;
        }

        sharedState.releaseHome(homeKey(role));
    }

    /**
     * Counts a role selected outside the weighted path (single eligible role, RW-only fallback).
     */
    private static void acquire(final NodeRole role, final SharedSelectorState sharedState) {
        if (sharedState == null) {
            return;
        }

        // Acquire even with one candidate, so the live counts stay complete: if the pool later
        // gains a second eligible role, it must see the sessions this one already holds.
        sharedState.acquireIndexByPopulation(new String[] {homeKey(role)}, new int[] {1});
    }

    /** Roles with weight &gt; 0 that also have at least one node, in master,slave,replica order. */
    private static List<NodeRole> candidateRoles(
            final ResolvedRoleTopology topology, final ReadWeight weights) {
        final List<NodeRole> roles = new ArrayList<NodeRole>(3);

        if (weights.weightOf(NodeRole.MASTER) > 0) {
            roles.add(NodeRole.MASTER); // master node always exists
        }
        if (weights.weightOf(NodeRole.SLAVE) > 0 && !topology.getSlaves().isEmpty()) {
            roles.add(NodeRole.SLAVE);
        }
        if (weights.weightOf(NodeRole.REPLICA) > 0 && !topology.getReplicas().isEmpty()) {
            roles.add(NodeRole.REPLICA);
        }

        return roles;
    }

    private static NodeRole selectRoleByPopulation(
            final List<NodeRole> roles,
            final ReadWeight weights,
            final SharedSelectorState sharedState) {
        if (sharedState == null) {
            throw new IllegalArgumentException(
                    "SharedSelectorState is required for weighted role selection");
        }

        // Run the whole round atomically in SharedSelectorState, so two concurrent binders cannot
        // read the same live-count snapshot and both take the slot of the role that was short.
        final String[] keys = new String[roles.size()];
        final int[] roleWeights = new int[roles.size()];
        for (int i = 0; i < roles.size(); i++) {
            keys[i] = homeKey(roles.get(i));
            roleWeights[i] = weights.weightOf(roles.get(i));
        }

        final int selected = sharedState.acquireIndexByPopulation(keys, roleWeights);

        return roles.get(selected);
    }

    private static Endpoint endpointForRole(
            final NodeRole role,
            final ResolvedRoleTopology topology,
            final SharedSelectorState sharedState) {
        switch (role) {
            case MASTER:
                return topology.getMaster().getRw(); // roOnRw: reads reuse the RW broker
            case SLAVE:
                {
                    final List<ResolvedNode> slaves = topology.getSlaves();
                    final ResolvedNode node =
                            slaves.get(groupIndex(RR_KEY_SLAVE, slaves.size(), sharedState));
                    return node.getRo();
                }
            case REPLICA:
                {
                    final List<ResolvedReplica> replicas = topology.getReplicas();
                    final ResolvedReplica node =
                            replicas.get(groupIndex(RR_KEY_REPLICA, replicas.size(), sharedState));
                    return node.getSo();
                }
            default:
                throw new IllegalStateException("Unsupported role: " + role);
        }
    }

    private static int groupIndex(
            final String groupKey, final int size, final SharedSelectorState sharedState) {
        if (size <= 1 || sharedState == null) {
            return 0;
        }

        return sharedState.nextGroupRoundRobinIndex(groupKey, size);
    }

    private static String homeKey(final NodeRole role) {
        return HOME_KEY_PREFIX + role.label();
    }

    /** A selected read target: the chosen role and the broker endpoint to read from. */
    public static final class ReadTarget {
        private final NodeRole role;
        private final Endpoint endpoint;

        ReadTarget(final NodeRole role, final Endpoint endpoint) {
            this.role = role;
            this.endpoint = endpoint;
        }

        public NodeRole getRole() {
            return role;
        }

        public Endpoint getEndpoint() {
            return endpoint;
        }

        /**
         * True when the read reuses the master RW connection (roOnRw) instead of an RO/SO one.
         *
         * @return {@code true} if the read reuses the RW connection
         */
        public boolean reusesRw() {
            return role.reusesRwForRead();
        }
    }
}
