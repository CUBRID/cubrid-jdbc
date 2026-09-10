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
import cubrid.jdbc.lb.config.LoadBalanceUrlParser.HostToken;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser.ReplicaToken;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Port resolution over a {@link ParsedRoleTopology}. Each node's broker ports are decided in the
 * order <b>inline (host token) → global option → default</b>.
 *
 * <ul>
 *   <li>each host-list node (master / slave) needs an <b>RW</b> and an <b>RO</b> port — inline
 *       {@code host:rw_port:ro_port}, else global {@code rwPort}/{@code roPort}, else the default
 *       ({@code RW=30000}, {@code RO=33000});
 *   <li>each replica node needs an <b>SO</b> port — inline {@code host:so_port}, else global {@code
 *       soPort}, else the default ({@code SO=36000}).
 * </ul>
 *
 * <p>Every host-list node gets both RW and RO, not just the master: a slave keeps an RW broker so
 * master failover can rebind RW to it, and the master keeps an RO broker even though its reads
 * reuse RW (roOnRw). Every broker kind has a default port, so an omitted port never fails
 * resolution; the throw in {@code decidePort} guards a caller that passes no default.
 */
public final class ResolvedRoleTopology {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final String OPT_RW_PORT = "rwPort";
    private static final String OPT_RO_PORT = "roPort";
    private static final String OPT_SO_PORT = "soPort";

    /** Default broker ports applied when neither inline nor global value is given. */
    private static final Integer DEFAULT_RW_PORT = Integer.valueOf(30000);

    private static final Integer DEFAULT_RO_PORT = Integer.valueOf(33000);
    private static final Integer DEFAULT_SO_PORT = Integer.valueOf(36000);

    private final ResolvedNode master;
    private final List<ResolvedNode> slaves;
    private final List<ResolvedReplica> replicas;

    ResolvedRoleTopology(
            final ResolvedNode master,
            final List<ResolvedNode> slaves,
            final List<ResolvedReplica> replicas) {
        this.master = master;
        this.slaves = Collections.unmodifiableList(new ArrayList<ResolvedNode>(slaves));
        this.replicas = Collections.unmodifiableList(new ArrayList<ResolvedReplica>(replicas));
    }

    /**
     * Resolve every node's ports from inline values and the URL's global port options ({@code
     * rwPort}/{@code roPort}/{@code soPort}).
     *
     * @param topology the role topology to resolve ports for
     * @param options the URL's raw option map, source of the global port defaults
     * @return the topology with every broker port resolved
     * @throws SQLException if the topology is missing or a port option is invalid
     */
    public static ResolvedRoleTopology resolve(
            final ParsedRoleTopology topology, final Map<String, String> options)
            throws SQLException {
        if (topology == null) {
            throw LbExceptions.topologyInvalid("role topology is null");
        }

        final Integer globalRw = globalPort(options, OPT_RW_PORT);
        final Integer globalRo = globalPort(options, OPT_RO_PORT);
        final Integer globalSo = globalPort(options, OPT_SO_PORT);

        final ResolvedNode master = resolveNode(topology.getMaster(), globalRw, globalRo);

        final List<ResolvedNode> slaves = new ArrayList<ResolvedNode>();
        final List<HostToken> slaveTokens = topology.getSlaves();
        for (int i = 0; i < slaveTokens.size(); i++) {
            slaves.add(resolveNode(slaveTokens.get(i), globalRw, globalRo));
        }

        final List<ResolvedReplica> replicas = new ArrayList<ResolvedReplica>();
        final List<ReplicaToken> replicaTokens = topology.getReplicas();
        for (int i = 0; i < replicaTokens.size(); i++) {
            replicas.add(resolveReplica(replicaTokens.get(i), globalSo));
        }

        return new ResolvedRoleTopology(master, slaves, replicas);
    }

    private static ResolvedNode resolveNode(
            final HostToken token, final Integer globalRw, final Integer globalRo)
            throws SQLException {
        final int rwPort =
                decidePort(
                        token.hasRwPort() ? Integer.valueOf(token.getRwPort()) : null,
                        globalRw,
                        DEFAULT_RW_PORT,
                        token.getHost(),
                        "RW",
                        OPT_RW_PORT);
        final int roPort =
                decidePort(
                        token.hasRoPort() ? Integer.valueOf(token.getRoPort()) : null,
                        globalRo,
                        DEFAULT_RO_PORT,
                        token.getHost(),
                        "RO",
                        OPT_RO_PORT);

        return new ResolvedNode(
                token.getHost(),
                new Endpoint(token.getHost(), rwPort),
                new Endpoint(token.getHost(), roPort));
    }

    private static ResolvedReplica resolveReplica(final ReplicaToken token, final Integer globalSo)
            throws SQLException {
        final int soPort =
                decidePort(
                        token.hasSoPort() ? Integer.valueOf(token.getSoPort()) : null,
                        globalSo,
                        DEFAULT_SO_PORT,
                        token.getHost(),
                        "SO",
                        OPT_SO_PORT);

        return new ResolvedReplica(token.getHost(), new Endpoint(token.getHost(), soPort));
    }

    private static int decidePort(
            final Integer inline,
            final Integer global,
            final Integer defaultPort,
            final String host,
            final String brokerKind,
            final String optionKey)
            throws SQLException {
        if (inline != null) {
            return inline.intValue();
        }
        if (global != null) {
            return global.intValue();
        }
        if (defaultPort != null) {
            return defaultPort.intValue();
        }

        throw LbExceptions.topologyInvalid(
                "no "
                        + brokerKind
                        + " port for host '"
                        + host
                        + "' (specify it inline or set the global '"
                        + optionKey
                        + "' property)");
    }

    private static Integer globalPort(final Map<String, String> options, final String key)
            throws SQLException {
        final String value = OptionKeys.findValueIgnoreCase(options, key);
        if (value == null) {
            return null;
        }

        try {
            final int port = Integer.parseInt(value.trim());
            if (port < MIN_PORT || port > MAX_PORT) {
                throw LbExceptions.optionValueIncompatible(key, value);
            }
            return Integer.valueOf(port);
        } catch (NumberFormatException e) {
            throw LbExceptions.optionValueIncompatible(key, value);
        }
    }

    /**
     * Master node (host-list[0]) with resolved RW+RO brokers; never null.
     *
     * @return the resolved master node
     */
    public ResolvedNode getMaster() {
        return master;
    }

    /**
     * Slave nodes (host-list[1..]) with resolved RW+RO brokers; possibly empty.
     *
     * @return the resolved slave nodes
     */
    public List<ResolvedNode> getSlaves() {
        return slaves;
    }

    /**
     * Replica nodes with resolved SO brokers; possibly empty.
     *
     * @return the resolved replica nodes
     */
    public List<ResolvedReplica> getReplicas() {
        return replicas;
    }

    /** A host-list node (master/slave) with its resolved RW and RO broker endpoints. */
    public static final class ResolvedNode {
        private final String host;
        private final Endpoint rw;
        private final Endpoint ro;

        ResolvedNode(final String host, final Endpoint rw, final Endpoint ro) {
            this.host = host;
            this.rw = rw;
            this.ro = ro;
        }

        public String getHost() {
            return host;
        }

        public Endpoint getRw() {
            return rw;
        }

        public Endpoint getRo() {
            return ro;
        }
    }

    /** A replica node with its resolved SO broker endpoint. */
    public static final class ResolvedReplica {
        private final String host;
        private final Endpoint so;

        ResolvedReplica(final String host, final Endpoint so) {
            this.host = host;
            this.so = so;
        }

        public String getHost() {
            return host;
        }

        public Endpoint getSo() {
            return so;
        }
    }
}
