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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class RecoveryBackoffTest {
    private static final long MS = 1000000L; // nanos per milli
    private static final String EP = "ro-host1:33000";

    @Test
    public void firstProbeClaimsAndSameWindowIsSuppressed() {
        RecoveryBackoff backoff = new RecoveryBackoff(3000);
        long t0 = 42L; // arbitrary nanoTime origin

        assertTrue(backoff.tryClaimProbe(EP, t0));
        assertFalse(backoff.tryClaimProbe(EP, t0));
        assertFalse(backoff.tryClaimProbe(EP, t0 + 2999 * MS));
    }

    @Test
    public void probeAllowedAgainAfterWindowElapses() {
        RecoveryBackoff backoff = new RecoveryBackoff(3000);
        long t0 = 42L;

        assertTrue(backoff.tryClaimProbe(EP, t0));
        assertTrue(backoff.tryClaimProbe(EP, t0 + 3000 * MS));
        assertFalse(backoff.tryClaimProbe(EP, t0 + 3000 * MS));
    }

    @Test
    public void resetAllowsImmediateReprobe() {
        RecoveryBackoff backoff = new RecoveryBackoff(3000);
        long t0 = 42L;

        assertTrue(backoff.tryClaimProbe(EP, t0));
        backoff.reset(EP);
        assertTrue(backoff.tryClaimProbe(EP, t0));
    }

    @Test
    public void zeroIntervalGrantsEveryCall() {
        RecoveryBackoff backoff = new RecoveryBackoff(0);
        long t0 = 42L;

        assertTrue(backoff.tryClaimProbe(EP, t0));
        assertTrue(backoff.tryClaimProbe(EP, t0));
        assertTrue(backoff.tryClaimProbe(EP, t0 + 1));
    }

    @Test
    public void endpointsAreIndependent() {
        RecoveryBackoff backoff = new RecoveryBackoff(3000);
        long t0 = 42L;

        assertTrue(backoff.tryClaimProbe("ep-a", t0));
        assertTrue(backoff.tryClaimProbe("ep-b", t0));
        assertFalse(backoff.tryClaimProbe("ep-a", t0));
    }

    @Test
    public void negativeNanoTimeOriginIsHandled() {
        // System.nanoTime() may be negative; only relative comparison matters.
        RecoveryBackoff backoff = new RecoveryBackoff(3000);
        long t0 = -5000 * MS;

        assertTrue(backoff.tryClaimProbe(EP, t0));
        assertFalse(backoff.tryClaimProbe(EP, t0 + 2999 * MS));
        assertTrue(backoff.tryClaimProbe(EP, t0 + 3000 * MS));
    }

    @Test
    public void concurrentClaimsGrantExactlyOneOnFirstProbe() throws Exception {
        for (int round = 0; round < 50; round++) {
            RecoveryBackoff backoff = new RecoveryBackoff(3000);
            assertEquals(1, raceClaims(backoff, 8, 42L));
        }
    }

    @Test
    public void concurrentClaimsGrantExactlyOneAfterWindowElapses() throws Exception {
        for (int round = 0; round < 50; round++) {
            RecoveryBackoff backoff = new RecoveryBackoff(3000);
            long t0 = 42L;
            assertTrue(backoff.tryClaimProbe(EP, t0));
            assertEquals(1, raceClaims(backoff, 8, t0 + 3000 * MS));
        }
    }

    private static int raceClaims(final RecoveryBackoff backoff, int threads, final long now)
            throws InterruptedException {
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger granted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(
                            new Runnable() {
                                public void run() {
                                    try {
                                        start.await();
                                        if (backoff.tryClaimProbe(EP, now)) {
                                            granted.incrementAndGet();
                                        }
                                    } catch (InterruptedException ignored) {
                                        Thread.currentThread().interrupt();
                                    } finally {
                                        done.countDown();
                                    }
                                }
                            })
                    .start();
        }

        start.countDown();
        assertTrue("threads did not finish", done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        return granted.get();
    }
}
