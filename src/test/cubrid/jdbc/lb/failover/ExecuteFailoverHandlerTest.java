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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.route.Router;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Retry-guard behavior of {@link ExecuteFailoverHandler}: recovery (rebind) always runs on a
 * retriable failure, but re-execution must be suppressed for writes (CR-1) and for statements whose
 * parameters cannot be replayed (CR-4). Recovery is stubbed as successful so the assertions isolate
 * the retry decision, not the recovery outcome.
 */
public class ExecuteFailoverHandlerTest {

    private static final class RecoveringConnection extends LoadBalanceConnection {
        final AtomicInteger recoverCalls = new AtomicInteger();

        RecoveringConnection() {
            super(LoadBalanceSettings.of(new Properties()));
        }

        @Override
        public Endpoint endpointForTarget(final Router.RouteTarget target) {
            return new Endpoint("failed-host", 33000);
        }

        @Override
        public PhysicalRecoveryContext buildRecoveryCtx(
                final Router.RouteTarget target, final Endpoint failedEp) {
            return null; // ignored by the recoverPhyBinding stub below
        }

        @Override
        public PhysicalRecoveryResult recoverPhyBinding(
                final PhysicalRecoveryContext ctx, final SQLException originalEx) {
            recoverCalls.incrementAndGet();
            return null; // simulate successful rebind
        }
    }

    private static SQLException commFail() {
        return new SQLException("comm fail", null, UErrorCode.ER_COMMUNICATION);
    }

    private static final class FailOnceExecution
            implements ExecuteFailoverHandler.SqlExecution<String> {
        final AtomicInteger runs = new AtomicInteger();

        public String run() throws SQLException {
            if (runs.incrementAndGet() == 1) {
                throw commFail();
            }
            return "ok";
        }
    }

    // CR-1: a write (TO_READ_WRITE) rebinds but must NOT re-execute -> no duplicate write.
    @Test
    public void writeRebindsButDoesNotReExecute() throws SQLException {
        RecoveringConnection conn = new RecoveringConnection();
        FailOnceExecution exec = new FailOnceExecution();

        try {
            new ExecuteFailoverHandler()
                    .executeWithFailover(conn, Router.RouteTarget.TO_READ_WRITE, "UPDATE t", exec);
            fail("write must not be retried after failover");
        } catch (SQLException e) {
            assertEquals(UErrorCode.ER_COMMUNICATION, e.getErrorCode());
        }
        assertEquals(1, exec.runs.get());
        assertEquals(1, conn.recoverCalls.get());
    }

    // Baseline: a read (TO_READ_ONLY) with replayable params retries once after rebind.
    @Test
    public void readRetriesOnceAfterRebind() throws SQLException {
        RecoveringConnection conn = new RecoveringConnection();
        FailOnceExecution exec = new FailOnceExecution();

        String result =
                new ExecuteFailoverHandler()
                        .executeWithFailover(
                                conn, Router.RouteTarget.TO_READ_ONLY, "SELECT 1", exec);

        assertEquals("ok", result);
        assertEquals(2, exec.runs.get());
        assertEquals(1, conn.recoverCalls.get());
    }

    // CR-4: a read whose params are not replayable (consumed stream) rebinds but must NOT retry.
    @Test
    public void readWithNonReplayableParamsDoesNotRetry() throws SQLException {
        RecoveringConnection conn = new RecoveringConnection();
        FailOnceExecution exec = new FailOnceExecution();

        try {
            new ExecuteFailoverHandler()
                    .executeWithFailover(
                            conn, Router.RouteTarget.TO_READ_ONLY, "SELECT 1", false, exec);
            fail("consumed-stream parameter must not be replayed");
        } catch (SQLException e) {
            assertEquals(UErrorCode.ER_COMMUNICATION, e.getErrorCode());
        }
        assertEquals(1, exec.runs.get());
        assertEquals(1, conn.recoverCalls.get());
    }
}
