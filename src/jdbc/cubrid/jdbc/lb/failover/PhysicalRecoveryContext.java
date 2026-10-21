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

import cubrid.jdbc.lb.SessionLeg;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.route.Router;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable input for physical-binding recovery (stage A) after a retriable execute failure: which
 * role/endpoint failed, the route target, the topology to pick a replacement from, the endpoint ids
 * to skip, and whether a transaction is active.
 */
public final class PhysicalRecoveryContext {
    private final SessionLeg failedLeg;
    private final Endpoint failedEndpoint;
    private final Router.RouteTarget routeTarget;
    private final EndpointTopology topology;
    private final Set<String> excludeEndpointIds;
    private final boolean txActive;

    public PhysicalRecoveryContext(
            final SessionLeg failedLeg,
            final Endpoint failedEndpoint,
            final Router.RouteTarget routeTarget,
            final EndpointTopology topology,
            final Set<String> excludeEndpointIds,
            final boolean txActive) {
        if (failedLeg == null) {
            throw new IllegalArgumentException("failedLeg must not be null");
        }
        if (failedEndpoint == null) {
            throw new IllegalArgumentException("failedEndpoint must not be null");
        }
        if (routeTarget == null) {
            throw new IllegalArgumentException("routeTarget must not be null");
        }
        if (topology == null) {
            throw new IllegalArgumentException("topology must not be null");
        }

        this.failedLeg = failedLeg;
        this.failedEndpoint = failedEndpoint;
        this.routeTarget = routeTarget;
        this.topology = topology;
        this.excludeEndpointIds =
                excludeEndpointIds == null
                        ? Collections.<String>emptySet()
                        : Collections.unmodifiableSet(new HashSet<String>(excludeEndpointIds));
        this.txActive = txActive;
    }

    public SessionLeg getFailedLeg() {
        return failedLeg;
    }

    public Endpoint getFailedEndpoint() {
        return failedEndpoint;
    }

    public Router.RouteTarget getRouteTarget() {
        return routeTarget;
    }

    public EndpointTopology getTopology() {
        return topology;
    }

    public Set<String> getExcludeEndpointIds() {
        return excludeEndpointIds;
    }

    public boolean isTxActive() {
        return txActive;
    }
}
