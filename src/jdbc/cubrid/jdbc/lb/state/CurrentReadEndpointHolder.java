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

package cubrid.jdbc.lb.state;

import cubrid.jdbc.lb.config.Endpoint;

/**
 * Holds the RO (read) endpoint this connection is currently bound to. The endpoint is picked when
 * the session binds and is replaced when the read leg fails over or fails back, so this tracks the
 * <em>current</em> binding — not the session's weighted home, which the connection manager keeps.
 * It is a simple nullable holder, not an execution tracker.
 *
 * <p>Thread contract: {@code currentRoEndpoint} is non-volatile and follows the same contract as
 * the owning {@link cubrid.jdbc.lb.LoadBalanceConnection}. A JDBC connection is not used
 * concurrently, and a pool handing it between threads establishes the happens-before edge.
 */
public class CurrentReadEndpointHolder {
    private Endpoint currentRoEndpoint = null;

    public Endpoint getCurrentRoEndpoint() {
        return currentRoEndpoint;
    }

    public void setCurrentRoEndpoint(Endpoint endpoint) {
        this.currentRoEndpoint = endpoint;
    }

    public boolean isBound() {
        return currentRoEndpoint != null;
    }

    public void clear() {
        this.currentRoEndpoint = null;
    }
}
