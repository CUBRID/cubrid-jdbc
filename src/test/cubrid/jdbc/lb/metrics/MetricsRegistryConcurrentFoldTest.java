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

package cubrid.jdbc.lb.metrics;

import static org.junit.Assert.assertEquals;

import cubrid.jdbc.lb.sql.SqlClassification;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@code MetricsRegistry.unregister} folds a closing connection's counts into the JVM-wide
 * cumulative tally. That tally is a plain {@code LinkedHashMap} whose readers take its monitor, so
 * the fold must take it too: pools close connections from several threads at once (shutdown,
 * maxLifetime eviction), and an unguarded fold both loses counts and can hand a reader a
 * ConcurrentModificationException mid-scrape.
 */
public class MetricsRegistryConcurrentFoldTest {

    private static final String ENDPOINT = "fold-host:33002";
    private static final int THREADS = 8;
    private static final int PER_THREAD = 400;

    @Before
    public void setUp() {
        MetricsRegistry.clearForTests();
    }

    @After
    public void tearDown() {
        MetricsRegistry.clearForTests();
    }

    @Test
    public void assertConcurrentUnregisterKeepsEveryCount() throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            new Thread(
                            new Runnable() {
                                public void run() {
                                    try {
                                        start.await();
                                        for (int i = 0; i < PER_THREAD; i++) {
                                            RuntimeMetrics m = new RuntimeMetrics();
                                            m.setEnabled(true);
                                            m.recordExec(ENDPOINT, SqlClassification.READ, false);
                                            MetricsRegistry.register(m, null, null);
                                            MetricsRegistry.unregister(m);
                                        }
                                    } catch (InterruptedException ex) {
                                        Thread.currentThread().interrupt();
                                    } finally {
                                        done.countDown();
                                    }
                                }
                            },
                            "fold-" + t)
                    .start();
        }

        start.countDown();
        done.await();

        Map<String, long[]> agg = MetricsRegistry.aggregateByEndpoint();
        long[] v = agg.get(ENDPOINT);
        assertEquals(
                "every folded read must survive a concurrent unregister",
                (long) THREADS * PER_THREAD,
                v == null ? 0L : v[0]);
    }
}
