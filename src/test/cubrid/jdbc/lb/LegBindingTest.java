/*
 * Copyright (C) 2008 Search Solution Corporation.
 * Copyright (c) 2016 CUBRID Corporation.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * - Neither the name of the <ORGANIZATION> nor the names of its contributors may be used to endorse
 * or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package cubrid.jdbc.lb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.driver.KitServer;
import cubrid.jdbc.lb.metrics.MetricsRegistry;
import cubrid.jdbc.lb.metrics.MetricsTestAccess;
import cubrid.jdbc.lb.report.ReportTestAccess;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Where a session's legs are, as other threads and connections see it (review r5 M1, m1, m4, m5,
 * D5). A core connection holds no lock another connection waits on, and once closed it opens no
 * socket again.
 */
public class LegBindingTest {
    private static final String DIST = "lbLogDistIntervalSec=1";
    private final LbKit kit = new LbKit(2);
    private final ExecutorService threads = Executors.newCachedThreadPool();

    @Before
    public void setUp() {
        MetricsTestAccess.clearForTests();
        ReportTestAccess.resetDistLog();
    }

    @After
    public void tearDown() {
        kit.close();
        threads.shutdownNow();
        ReportTestAccess.resetDistLog();
        MetricsTestAccess.clearForTests();
    }

    /**
     * r5 M1: while one connection dials a silent host, another connection's statement writes the
     * distribution record, the gauge is read, and the pool opens a connection, none of them waiting
     * for that dial.
     */
    @Test(timeout = 60000)
    public void aDialOnOneConnectionHoldsUpNoOtherConnection() throws Exception {
        final Connection y = kit.open("readWeight=master:1&" + DIST);
        kit.server.fault(kit.ro(2), KitServer.Fault.DIAL_TIMEOUT);
        kit.server.dialTimeoutMillis(30000);
        final Thread x =
                new Thread(
                        () -> {
                            try {
                                kit.open("readWeight=slave:1&" + DIST);
                            } catch (SQLException ignored) {
                                // only the dial it is in matters here
                            }
                        });
        x.start();
        try {
            while (kit.server.dials(kit.ro(2)) == 0) {
                Thread.sleep(10);
            }
            Thread.sleep(1100); // the distribution window closes: y's next statement writes it

            final Future<List<Integer>> read =
                    threads.submit(
                            () ->
                                    LbKitTest.rows(
                                            y.createStatement().executeQuery("SELECT a FROM t")));
            final Future<Integer> gauge = threads.submit(MetricsRegistry::countRoOnRwSessions);
            final Future<Connection> opened =
                    threads.submit(() -> kit.open("readWeight=master:1&" + DIST));

            assertEquals(3, read.get(5, TimeUnit.SECONDS).size());
            assertEquals(Integer.valueOf(0), gauge.get(5, TimeUnit.SECONDS));
            opened.get(5, TimeUnit.SECONDS).close();
            assertTrue("x is still dialing", x.isAlive());
        } finally {
            x.interrupt(); // ends the dial
            x.join(10000);
        }
    }

    /**
     * r5 m1: a read failing over while another thread closes the connection opens no leg. Since r5
     * D1 the close waits for the statement, as core's synchronized close does.
     */
    @Test(timeout = 30000)
    public void closeDuringReadRecoveryLeavesNoLeg() throws Exception {
        closeDuringRecovery("readWeight=slave:1", kit.ro(2), "SELECT a FROM t");
    }

    /** r5 m1: likewise for the write leg. */
    @Test(timeout = 30000)
    public void closeDuringWriteRecoveryLeavesNoLeg() throws Exception {
        closeDuringRecovery("readWeight=slave:1", kit.rw(1), "UPDATE t SET a = 1");
    }

    /**
     * The statement is in flight when another thread closes the connection; then its broker goes
     * down. The close waits until the statement, its recovery included, is over, and closes the
     * legs that recovery opened.
     */
    private void closeDuringRecovery(final String options, final String failing, final String sql)
            throws Exception {
        final LoadBalanceConnection conn = kit.open(options);
        conn.createStatement().executeQuery("SELECT a FROM t").close();
        final KitServer.Hold held = kit.server.hold("EXECUTE " + sql);
        final Future<?> statement =
                threads.submit(
                        (Callable<Boolean>)
                                () -> {
                                    try {
                                        return conn.createStatement().execute(sql);
                                    } catch (SQLException failed) {
                                        return Boolean.FALSE; // a write is not run again
                                    }
                                });
        assertTrue(held.reached.await(10, TimeUnit.SECONDS));
        final Future<?> close =
                threads.submit(
                        (Callable<Void>)
                                () -> {
                                    conn.close();
                                    return null;
                                });
        Thread.sleep(300);
        assertTrue("close() waits for the statement in flight", !close.isDone());

        kit.server.fault(failing, KitServer.Fault.REFUSE);
        held.release.countDown();
        statement.get(10, TimeUnit.SECONDS);
        close.get(10, TimeUnit.SECONDS);

        assertTrue(conn.isClosed());
        assertEquals(Collections.<String>emptyList(), kit.legLeaks());
    }

    /**
     * r5 m5: every read broker refused, so reads run on the write leg's connection, where the
     * isolation level holds as on core; nothing says they run on another node.
     */
    @Test
    public void aReadOnTheWriteConnectionDoesNotWarnOfAnotherNode() throws Exception {
        final Connection conn = openFallenBackToRw();
        conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

        assertEquals(
                3, LbKitTest.rows(conn.createStatement().executeQuery("SELECT a FROM t")).size());

        for (String line : kit.log()) {
            assertTrue(line, !line.contains("LB ISOLATION"));
        }
    }

    /** r5 D5: toString names where reads run, and the read endpoint they will return to. */
    @Test
    public void toStringShowsWhereReadsRun() throws Exception {
        final Connection conn = openFallenBackToRw();

        final String text = conn.toString();

        assertTrue(text, text.contains("read=" + kit.rw(1) + " "));
        assertTrue(text, text.contains(kit.ro(2)));
    }

    /**
     * r5 m4: {@code lb_ro_on_rw_sessions} counts sessions whose read brokers all failed. A session
     * whose read weight put it on the master reads on the write connection by design and is not
     * counted.
     */
    @Test
    public void theRoOnRwGaugeCountsOnlyFallenBackSessions() throws Exception {
        kit.open("readWeight=master:1&" + DIST);
        assertEquals(0, MetricsRegistry.countRoOnRwSessions());

        openFallenBackToRw();

        assertEquals(1, MetricsRegistry.countRoOnRwSessions());
    }

    /**
     * A slave-read session whose read brokers all refuse: it reads on node 1's write connection.
     */
    private Connection openFallenBackToRw() throws SQLException {
        kit.server.fault(kit.ro(1), KitServer.Fault.REFUSE);
        kit.server.fault(kit.ro(2), KitServer.Fault.REFUSE);
        return kit.open("readWeight=slave:1&" + DIST);
    }
}
