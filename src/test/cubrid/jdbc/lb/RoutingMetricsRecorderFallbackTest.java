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

import static org.junit.Assert.assertEquals;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.sql.SqlClassifierRegistry;
import cubrid.jdbc.lb.state.SessionRoutingState;
import java.util.Properties;
import org.junit.Test;

/**
 * {@code rwFallbackCount} must count only actual fallbacks.
 *
 * <p>A {@code null} reason means "none was supplied", but the plain {@code
 * !FallbackReason.NONE.equals(reason)} test read it as a fallback, so any call site that left the
 * reason unset would silently inflate the counter. That counter is what the weight operations guide
 * has operators read before re-tuning readWeight — an inflated RW-fallback rate points them at a
 * healthy read node. No caller passes null today; this pins the arithmetic so none can start.
 */
public class RoutingMetricsRecorderFallbackTest {

    private static final Endpoint ENDPOINT = new Endpoint("node1", 33000);

    private RoutingMetricsRecorder recorder() {
        LoadBalanceSettings config = LoadBalanceSettings.of(new Properties());
        return new RoutingMetricsRecorder(
                1L, config, new SessionRoutingState(), SqlClassifierRegistry.forConfig(config));
    }

    @Test
    public void nullFallbackReasonIsNotAFallback() {
        RoutingMetricsRecorder recorder = recorder();
        recorder.runtimeMetrics().setEnabled(true);

        recorder.recordCommandRoute(
                "setAutoCommit", Router.RouteTarget.TO_READ_WRITE, ENDPOINT, null);

        assertEquals(0L, recorder.runtimeMetrics().getRwFallbackCount());
    }

    @Test
    public void noneIsNotAFallbackButARealReasonIs() {
        RoutingMetricsRecorder recorder = recorder();
        recorder.runtimeMetrics().setEnabled(true);

        recorder.recordCommandRoute(
                "commit", Router.RouteTarget.TO_READ_WRITE, ENDPOINT, FallbackReason.NONE);
        assertEquals(0L, recorder.runtimeMetrics().getRwFallbackCount());

        recorder.recordCommandRoute(
                "commit", Router.RouteTarget.TO_READ_ONLY, ENDPOINT, FallbackReason.RO_PHYSICAL);
        assertEquals(1L, recorder.runtimeMetrics().getRwFallbackCount());
    }

    @Test
    public void disabledObservationRecordsNothing() {
        RoutingMetricsRecorder recorder = recorder();

        recorder.recordCommandRoute(
                "commit", Router.RouteTarget.TO_READ_ONLY, ENDPOINT, FallbackReason.RO_PHYSICAL);

        assertEquals(0L, recorder.runtimeMetrics().getRwFallbackCount());
        assertEquals(0, recorder.runtimeMetrics().getEvents().size());
    }
}
