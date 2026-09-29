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

package cubrid.jdbc.lb.config;

import cubrid.jdbc.lb.LbExceptions;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Role-based read weights:
 *
 * <pre>
 *   readWeight = role ":" weight { "," role ":" weight }
 *   role       = "slave" | "master" | "replica"
 *   weight     = non-negative integer
 * </pre>
 *
 * <ul>
 *   <li><b>Explicit</b>: only the listed roles get their weight; roles not listed are {@code 0} (no
 *       reads). {@code weight=0} excludes a role from reads (e.g. {@code master:0} = write-only
 *       master).
 *   <li><b>Unspecified</b> (no {@code readWeight}): each role that <em>exists</em> in the topology
 *       gets weight {@code 1} (master always exists; slave iff there are non-first hosts; replica
 *       iff a {@code ;replica=} list is present). Master is included by default so reads can reuse
 *       its idle RW connection (roOnRw); a single host with no slave/replica yields master-only,
 *       i.e. RW-only.
 * </ul>
 *
 * <p>Parsed weights only. {@link cubrid.jdbc.lb.route.RoleReadSelector} uses them as the target
 * share of the pool's live read sessions, not as a per-request round-robin ratio.
 */
public final class ReadWeight {
    private static final Logger LOGGER = Logger.getLogger(ReadWeight.class.getName());

    private final Map<NodeRole, Integer> weights;

    private ReadWeight(final Map<NodeRole, Integer> source) {
        final EnumMap<NodeRole, Integer> w = new EnumMap<NodeRole, Integer>(NodeRole.class);
        final NodeRole[] roles = NodeRole.values();
        for (int i = 0; i < roles.length; i++) {
            final Integer v = source.get(roles[i]);
            w.put(roles[i], Integer.valueOf(v == null ? 0 : v.intValue()));
        }
        this.weights = Collections.unmodifiableMap(w);
    }

    /**
     * Resolve weights: parse {@code spec} when present, otherwise the topology-based default (each
     * existing role gets {@code 1}).
     *
     * @param spec the {@code readWeight} spec; {@code null}/blank selects the topology default
     * @param topology the role topology the weights are validated against
     * @return the resolved weights
     * @throws SQLException if the spec is malformed or inconsistent with the topology
     */
    public static ReadWeight resolve(final String spec, final ParsedRoleTopology topology)
            throws SQLException {
        if (spec == null || spec.trim().length() == 0) {
            return defaultsFor(topology);
        }

        final ReadWeight w = parse(spec);
        validateAgainstTopology(w, topology, spec);
        return w;
    }

    /**
     * Cross-checks an explicit spec against the topology so mistakes surface at connect time. An
     * all-zero spec has no read target (error); a weight on a role the topology does not have is
     * ignored at runtime (WARN).
     */
    private static void validateAgainstTopology(
            final ReadWeight w, final ParsedRoleTopology topology, final String spec)
            throws SQLException {
        if (!w.hasReadTarget()) {
            throw LbExceptions.optionInvalid(
                    "readWeight has no read target (all weights are 0): '" + spec + "'");
        }
        if (topology == null) {
            return;
        }
        if (w.weightOf(NodeRole.SLAVE) > 0 && !topology.hasSlaves()) {
            LOGGER.warning(
                    "readWeight assigns weight to 'slave' but the topology has no slave node; "
                            + "that weight is ignored at runtime: '"
                            + spec
                            + "'");
        }
        if (w.weightOf(NodeRole.REPLICA) > 0 && !topology.hasReplicas()) {
            LOGGER.warning(
                    "readWeight assigns weight to 'replica' but the topology has no replica node; "
                            + "that weight is ignored at runtime: '"
                            + spec
                            + "'");
        }
    }

    /**
     * Parse an explicit {@code readWeight} spec. Roles not listed default to weight 0.
     *
     * @param spec the {@code readWeight} spec
     * @return the parsed weights
     * @throws SQLException if the spec is empty or malformed
     */
    public static ReadWeight parse(final String spec) throws SQLException {
        if (spec == null || spec.trim().length() == 0) {
            throw LbExceptions.optionInvalid("readWeight is empty");
        }

        final EnumMap<NodeRole, Integer> w = new EnumMap<NodeRole, Integer>(NodeRole.class);

        final String[] tokens = spec.trim().split(",", -1);
        for (int i = 0; i < tokens.length; i++) {
            final String token = tokens[i].trim();
            if (token.length() == 0) {
                throw LbExceptions.optionInvalid("empty entry in readWeight: '" + spec + "'");
            }

            final int colon = token.indexOf(':');
            if (colon <= 0 || colon == token.length() - 1) {
                throw LbExceptions.optionInvalid(
                        "malformed readWeight entry '" + token + "' (expected role:weight)");
            }

            final NodeRole role = NodeRole.fromLabel(token.substring(0, colon));
            if (role == null) {
                throw LbExceptions.optionInvalid(
                        "unknown role in readWeight entry '"
                                + token
                                + "' (expected slave/master/replica)");
            }
            if (w.containsKey(role)) {
                throw LbExceptions.optionInvalid(
                        "duplicate role '" + role.label() + "' in readWeight");
            }

            w.put(role, Integer.valueOf(parseWeight(token.substring(colon + 1), token)));
        }

        return new ReadWeight(w);
    }

    /**
     * Default weights for a topology: each existing role gets {@code 1}.
     *
     * @param topology the role topology
     * @return the default weights
     * @throws SQLException if the topology is missing
     */
    public static ReadWeight defaultsFor(final ParsedRoleTopology topology) throws SQLException {
        if (topology == null) {
            throw LbExceptions.internalState(
                    "missing topology while building default read weights");
        }

        final EnumMap<NodeRole, Integer> w = new EnumMap<NodeRole, Integer>(NodeRole.class);
        w.put(NodeRole.MASTER, Integer.valueOf(1));
        w.put(NodeRole.SLAVE, Integer.valueOf(topology.hasSlaves() ? 1 : 0));
        w.put(NodeRole.REPLICA, Integer.valueOf(topology.hasReplicas() ? 1 : 0));

        return new ReadWeight(w);
    }

    private static int parseWeight(final String text, final String token) throws SQLException {
        try {
            final int weight = Integer.parseInt(text.trim());
            if (weight < 0) {
                throw LbExceptions.optionValueIncompatible("readWeight", token);
            }
            return weight;
        } catch (NumberFormatException e) {
            throw LbExceptions.optionValueIncompatible("readWeight", token);
        }
    }

    /**
     * Weight of a role (0 if the role is excluded from reads).
     *
     * @param role the role to look up
     * @return the read weight of {@code role}
     */
    public int weightOf(final NodeRole role) {
        return weights.get(role).intValue();
    }

    /**
     * Sum of all role weights.
     *
     * @return the total read weight
     */
    public int total() {
        return weightOf(NodeRole.MASTER) + weightOf(NodeRole.SLAVE) + weightOf(NodeRole.REPLICA);
    }

    /**
     * Whether any role has a non-zero read weight (otherwise reads have no distribution target).
     *
     * @return whether any role can serve reads
     */
    public boolean hasReadTarget() {
        return total() > 0;
    }

    public String toString() {
        return "readWeight[slave="
                + weightOf(NodeRole.SLAVE)
                + ",master="
                + weightOf(NodeRole.MASTER)
                + ",replica="
                + weightOf(NodeRole.REPLICA)
                + "]";
    }
}
