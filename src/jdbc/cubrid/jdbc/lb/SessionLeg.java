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

import cubrid.jdbc.lb.route.Router;

/**
 * One of the two physical connections a load-balanced session holds: the write leg or the read leg.
 * Both may point at the same connection when reads run on the RW connection ({@code roOnRw}).
 *
 * <p>Not a broker role. {@link cubrid.jdbc.lb.config.NodeRole} (master/slave/replica) says what a
 * node is in the cluster; this says which of the session's two connections is meant.
 *
 * <p>The names {@code RW}/{@code RO} are operator-facing: they print in {@code LB FAILOVER [RO]}
 * and key the log-dedup entry {@code FAILOVER|RO|from|to}.
 */
public enum SessionLeg {
    RW,
    RO;

    /**
     * Maps a route target to the leg that serves it.
     *
     * @param target the route target the statement was classified to
     * @return the corresponding leg
     * @throws IllegalArgumentException if {@code target} is {@code null} or does not map to a leg
     */
    public static SessionLeg fromRouteTarget(final Router.RouteTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("RouteTarget must not be null");
        }

        if (target == Router.RouteTarget.TO_READ_WRITE) {
            return RW;
        }

        if (target == Router.RouteTarget.TO_READ_ONLY) {
            return RO;
        }

        throw new IllegalArgumentException("RouteTarget does not map to a session leg: " + target);
    }
}
