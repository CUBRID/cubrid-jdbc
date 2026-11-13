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

import static org.junit.Assert.assertArrayEquals;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.metrics.MetricsTestAccess;
import cubrid.jdbc.lb.route.Router.RouteTarget;
import cubrid.jdbc.lb.sql.SqlClassifierRegistry;
import cubrid.jdbc.lb.state.SessionRoutingState;
import org.junit.Test;

/**
 * {@code lb_endpoint_exec_total} counts statements by the leg they ran on, so one the classifier
 * could not place (UNKNOWN, a {@code ROW_COUNT()} that follows the previous statement) is counted.
 */
public class RoutingMetricsRecorderLegTest {

    private static final Endpoint ENDPOINT = new Endpoint("node1", 33000);

    @Test
    public void unplacedStatementsCountOnTheLegTheyRanOn() {
        LoadBalanceSettings config = LbTestSettings.defaults();
        RoutingMetricsRecorder recorder =
                new RoutingMetricsRecorder(
                        1L,
                        config,
                        new SessionRoutingState(),
                        SqlClassifierRegistry.forConfig(config));
        recorder.runtimeMetrics().setEnabled(true);

        recorder.recordStatementRoute(
                "SELECT my_func(1) FROM t",
                RouteTarget.TO_READ_WRITE,
                ENDPOINT,
                FallbackReason.NONE);
        recorder.recordStatementRoute(
                "SELECT ROW_COUNT()", RouteTarget.TO_READ_ONLY, ENDPOINT, FallbackReason.NONE);

        assertArrayEquals(
                new long[] {1L, 1L},
                MetricsTestAccess.getReadWriteCounts(recorder.runtimeMetrics(), ENDPOINT.getId()));
    }
}
