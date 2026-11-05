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

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.EndpointConnManager;
import cubrid.jdbc.lb.metrics.RuntimeMetrics;
import cubrid.jdbc.lb.route.Router.RouteTarget;
import cubrid.jdbc.lb.state.CurrentReadEndpointHolder;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.sql.SQLException;

/**
 * The package-private members of {@code cubrid.jdbc.lb} that tests in other packages use. It lives
 * in the test tree, so the driver jar does not carry it.
 */
public final class LbTestAccess {
    private LbTestAccess() {}

    public static final int lb_internal_state = LbJDBCErrorCode.lb_internal_state;

    public static final int lb_broker_group_exhausted = LbJDBCErrorCode.lb_broker_group_exhausted;

    public static final int lb_failover_tx_forbidden = LbJDBCErrorCode.lb_failover_tx_forbidden;

    public static LoadBalanceConnection newLoadBalanceConnection(final LoadBalanceSettings config) {
        return new LoadBalanceConnection(config);
    }

    public static int getOpenStatementCount(final LoadBalanceConnection connection) {
        return connection.getOpenStatementCount();
    }

    public static RuntimeMetrics getRuntimeMetrics(final LoadBalanceConnection connection) {
        return connection.getRuntimeMetrics();
    }

    public static void setConnectionManager(
            final LoadBalanceConnection connection, final EndpointConnManager connectionManager) {
        connection.setConnectionManager(connectionManager);
    }

    public static void setSharedSelectorState(
            final LoadBalanceConnection connection, final SharedSelectorState shared) {
        connection.setSharedSelectorState(shared);
    }

    public static void initSessionBindings(
            final LoadBalanceConnection connection, final EndpointTopology topology)
            throws SQLException {
        connection.initSessionBindings(topology);
    }

    public static CurrentReadEndpointHolder getBoundReadEndpoint(
            final LoadBalanceConnection connection) {
        return connection.getBoundReadEndpoint();
    }

    public static SharedSelectorState getSharedSelectorState(
            final LoadBalanceConnection connection) {
        return connection.getSharedSelectorState();
    }

    public static EndpointTopology getSessionTopology(final LoadBalanceConnection connection) {
        return connection.getSessionTopology();
    }

    public static Endpoint getCurrentRoEndpoint(final LoadBalanceConnection connection) {
        return connection.getCurrentRoEndpoint();
    }

    public static Endpoint getCurrentEp(
            final LoadBalanceConnection connection, final SessionLeg leg) {
        return connection.getCurrentEp(leg);
    }

    public static Endpoint getCurrentRwEndpoint(final LoadBalanceConnection connection) {
        return connection.getCurrentRwEndpoint();
    }

    public static SessionLeg fromRouteTarget(final RouteTarget target) {
        return SessionLeg.fromRouteTarget(target);
    }
}
