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

import cubrid.jdbc.lb.log.LbLog;
import cubrid.jdbc.lb.log.LbLogDedup;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Per-endpoint recovery-probe scheduler shared by all sessions of a pool: {@link #tryClaimProbe}
 * grants at most one caller per backoff window the right to probe an unreachable endpoint
 * (CAS-elected single prober), and {@link #reset} re-arms an endpoint immediately after a
 * successful reconnect.
 *
 * <p>Timestamps are {@link System#nanoTime()} values (relative comparison only, immune to
 * wall-clock jumps). A failed probe needs no bookkeeping: the claim itself advanced the window, so
 * further probes stay suppressed until it elapses. Thread-safe.
 */
public final class RecoveryBackoff {
    private static final long NANOS_PER_MILLI = 1000000L;

    private static final Logger LOGGER = Logger.getLogger(RecoveryBackoff.class.getName());

    private final long intervalNanos;

    // endpointId -> nanoTime of the last claimed probe. Probe right = successful CAS.
    private final ConcurrentHashMap<String, AtomicLong> lastProbeAt =
            new ConcurrentHashMap<String, AtomicLong>();

    public RecoveryBackoff(long intervalMs) {
        this.intervalNanos = intervalMs * NANOS_PER_MILLI;
    }

    /**
     * True iff the backoff window for {@code endpointId} has elapsed (or it was never probed) and
     * this call atomically claimed the probe right; concurrent callers in the same window get
     * false. {@code intervalMs=0} grants every call.
     *
     * @param endpointId the endpoint to probe
     * @param nowNanos the current {@code System.nanoTime()} reading
     * @return whether this call claimed the probe right
     */
    public boolean tryClaimProbe(String endpointId, long nowNanos) {
        AtomicLong slot = lastProbeAt.get(endpointId);
        if (slot == null) {
            // First probe of this endpoint: creating the slot IS the claim (no sentinel value —
            // nanoTime spans the full long range, so "never probed" cannot be a timestamp).
            if (lastProbeAt.putIfAbsent(endpointId, new AtomicLong(nowNanos)) == null) {
                return true;
            }
            slot = lastProbeAt.get(endpointId);
            if (slot == null) {
                // Lost the creation race and a concurrent reset() already removed it; retry.
                return tryClaimProbe(endpointId, nowNanos);
            }
        }

        long prev = slot.get();
        if (nowNanos - prev < intervalNanos) {
            // Recorded because this answers "the node was back up, why did failover not take it
            // yet?" - the candidate is soft-excluded until the window closes. Collapsed per
            // endpoint, so a pool re-asking every few milliseconds writes one line, not thousands.
            LbLogDedup.fine(
                    LOGGER,
                    null,
                    "BACKOFF|held|" + endpointId,
                    "LB BACKOFF: "
                            + endpointId
                            + " still inside its probe window ("
                            + (intervalNanos / NANOS_PER_MILLI)
                            + "ms) -- excluded from candidates"
                            + " for another "
                            + ((intervalNanos - (nowNanos - prev)) / NANOS_PER_MILLI)
                            + "ms");
            return false; // still inside the window
        }

        boolean claimed = slot.compareAndSet(prev, nowNanos); // exactly one claimer per window
        if (claimed) {
            LbLog.fine(
                    LOGGER,
                    null,
                    "LB BACKOFF: "
                            + endpointId
                            + " probe window elapsed -- this caller claimed the"
                            + " probe right");
        }
        return claimed;
    }

    /**
     * Reconnect succeeded: drop the endpoint's window so the next probe is allowed immediately.
     *
     * @param endpointId the endpoint whose backoff window is cleared
     */
    public void reset(String endpointId) {
        if (lastProbeAt.remove(endpointId) != null) {
            LbLog.fine(
                    LOGGER,
                    null,
                    "LB BACKOFF: "
                            + endpointId
                            + " reconnected -- probe window cleared, it is"
                            + " immediately eligible again");
        }
    }
}
