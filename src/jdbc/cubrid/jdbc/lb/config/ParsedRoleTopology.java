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
import cubrid.jdbc.lb.config.LoadBalanceUrlParser.ParsedUrl;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser.ReplicaToken;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Role assignment over the parsed URI URL:
 *
 * <ul>
 *   <li>the <b>first</b> host-list node → {@link NodeRole#MASTER} (RW, and RW reused for reads),
 *   <li>the remaining host-list nodes → {@link NodeRole#SLAVE} (RO),
 *   <li>the {@code ;replica=} nodes → {@link NodeRole#REPLICA} (SO).
 * </ul>
 *
 * <p>The client <b>fixes</b> the first host as master and does not probe the cluster for the real
 * master: if node1 fails over and node2 becomes the real master, the master known to the client is
 * still node1 (writes go to node1's RW broker, which the broker forwards to the real master).
 *
 * <p>This stage only groups nodes by role and passes each node's inline-port info ({@link
 * HostToken}/{@link ReplicaToken}) through. Port resolution and {@link Endpoint} building happen
 * later in {@link ResolvedRoleTopology}.
 */
public final class ParsedRoleTopology {
    private final HostToken master;
    private final List<HostToken> slaves;
    private final List<ReplicaToken> replicas;

    ParsedRoleTopology(
            final HostToken master,
            final List<HostToken> slaves,
            final List<ReplicaToken> replicas) {
        this.master = master;
        this.slaves =
                Collections.unmodifiableList(
                        new ArrayList<HostToken>(
                                slaves != null ? slaves : new ArrayList<HostToken>()));
        this.replicas =
                Collections.unmodifiableList(
                        new ArrayList<ReplicaToken>(
                                replicas != null ? replicas : new ArrayList<ReplicaToken>()));
    }

    /**
     * Assign roles from a parsed URI URL. The first host is fixed as master.
     *
     * @param parsed the structurally parsed URI URL
     * @return the role topology
     * @throws SQLException if the URL is null or its host-list is empty
     */
    public static ParsedRoleTopology fromUrl(final ParsedUrl parsed) throws SQLException {
        if (parsed == null) {
            throw LbExceptions.topologyInvalid("parsed URL is null");
        }

        final List<HostToken> hosts = parsed.getHosts();
        if (hosts == null || hosts.isEmpty()) {
            throw LbExceptions.topologyInvalid("host-list is empty");
        }

        final HostToken master = hosts.get(0);
        final List<HostToken> slaves = new ArrayList<HostToken>(hosts.subList(1, hosts.size()));

        return new ParsedRoleTopology(master, slaves, parsed.getReplicas());
    }

    /**
     * The master node (host-list[0]); never null.
     *
     * @return the master host token
     */
    public HostToken getMaster() {
        return master;
    }

    /**
     * The slave nodes (host-list[1..]); possibly empty.
     *
     * @return the slave host tokens
     */
    public List<HostToken> getSlaves() {
        return slaves;
    }

    /**
     * The replica nodes ({@code ;replica=}); possibly empty.
     *
     * @return the replica tokens
     */
    public List<ReplicaToken> getReplicas() {
        return replicas;
    }

    public boolean hasSlaves() {
        return !slaves.isEmpty();
    }

    public boolean hasReplicas() {
        return !replicas.isEmpty();
    }

    /**
     * Master host name.
     *
     * @return the master host name
     */
    public String getMasterHost() {
        return master.getHost();
    }

    /**
     * Slave host names, in URL order.
     *
     * @return the slave host names
     */
    public List<String> getSlaveHosts() {
        final List<String> hosts = new ArrayList<String>(slaves.size());
        for (int i = 0; i < slaves.size(); i++) {
            hosts.add(slaves.get(i).getHost());
        }

        return hosts;
    }

    /**
     * Replica host names, in URL order.
     *
     * @return the replica host names
     */
    public List<String> getReplicaHosts() {
        final List<String> hosts = new ArrayList<String>(replicas.size());
        for (int i = 0; i < replicas.size(); i++) {
            hosts.add(replicas.get(i).getHost());
        }

        return hosts;
    }
}
