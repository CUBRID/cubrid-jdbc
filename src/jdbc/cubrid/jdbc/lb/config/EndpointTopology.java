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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable RW/RO/REPL endpoint lists consumed by routing and physical failover. */
public final class EndpointTopology {
    private final List<Endpoint> rwEndpoints;
    private final List<Endpoint> roEndpoints;
    private final List<Endpoint> replEndpoints;

    public EndpointTopology(
            Endpoint rwEndpoint, List<Endpoint> roEndpoints, List<Endpoint> replEndpoints) {
        this(
                rwEndpoint == null
                        ? Collections.<Endpoint>emptyList()
                        : Collections.singletonList(rwEndpoint),
                roEndpoints,
                replEndpoints);
    }

    public EndpointTopology(
            List<Endpoint> rwEndpoints, List<Endpoint> roEndpoints, List<Endpoint> replEndpoints) {
        this.rwEndpoints =
                Collections.unmodifiableList(
                        rwEndpoints != null
                                ? new ArrayList<Endpoint>(rwEndpoints)
                                : new ArrayList<Endpoint>());
        this.roEndpoints =
                Collections.unmodifiableList(
                        roEndpoints != null
                                ? new ArrayList<Endpoint>(roEndpoints)
                                : new ArrayList<Endpoint>());
        this.replEndpoints =
                Collections.unmodifiableList(
                        replEndpoints != null
                                ? new ArrayList<Endpoint>(replEndpoints)
                                : new ArrayList<Endpoint>());
    }

    /**
     * Bridge a role {@link ResolvedRoleTopology} (URI {@code loadbalance://} model) onto the
     * RW/RO/REPL endpoint topology used by the routing and physical-failover machinery:
     *
     * <ul>
     *   <li><b>RW</b>: master RW first, then every slave's RW — slaves keep an RW broker so master
     *       failover can rebind RW to another host-list node;
     *   <li><b>RO</b>: every slave RO and every replica SO — the read-failover pool;
     *   <li><b>REPL</b>: every replica SO.
     * </ul>
     *
     * @param resolved the resolved role topology; may be {@code null}
     * @return the endpoint topology, empty when {@code resolved} is {@code null}
     */
    public static EndpointTopology fromResolved(ResolvedRoleTopology resolved) {
        if (resolved == null) {
            return new EndpointTopology(
                    new ArrayList<Endpoint>(),
                    new ArrayList<Endpoint>(),
                    new ArrayList<Endpoint>());
        }

        List<Endpoint> rwList = new ArrayList<Endpoint>();
        rwList.add(resolved.getMaster().getRw());

        List<Endpoint> roList = new ArrayList<Endpoint>();
        List<ResolvedRoleTopology.ResolvedNode> slaves = resolved.getSlaves();
        for (int i = 0; i < slaves.size(); i++) {
            rwList.add(slaves.get(i).getRw());
            roList.add(slaves.get(i).getRo());
        }

        List<Endpoint> replList = new ArrayList<Endpoint>();
        List<ResolvedRoleTopology.ResolvedReplica> replicas = resolved.getReplicas();
        for (int i = 0; i < replicas.size(); i++) {
            replList.add(replicas.get(i).getSo());
        }

        roList.addAll(replList);

        return new EndpointTopology(rwList, roList, replList);
    }

    public List<Endpoint> getRwEndpoints() {
        return rwEndpoints;
    }

    public List<Endpoint> getRoEndpoints() {
        return roEndpoints;
    }

    public List<Endpoint> getReplEndpoints() {
        return replEndpoints;
    }

    public boolean hasRwEndpoint() {
        return !rwEndpoints.isEmpty();
    }

    public boolean hasRoEndpoints() {
        return !roEndpoints.isEmpty();
    }
}
