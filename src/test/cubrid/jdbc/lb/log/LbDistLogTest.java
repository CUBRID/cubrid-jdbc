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

package cubrid.jdbc.lb.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import cubrid.jdbc.lb.sql.SqlClassification;
import cubrid.jdbc.lb.state.BindingView;
import cubrid.jdbc.lb.state.MetricsRegistry;
import cubrid.jdbc.lb.state.RuntimeMetrics;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The periodic distribution record. Its reason for existing is that connection distribution and
 * query distribution come apart: the ratio is {@code connections x per-connection query rate}, and
 * the second factor belongs to the application, so a pool can be spread exactly as configured while
 * the queries are not. A record showing only one of the two sends an investigation to the wrong
 * place.
 */
public class LbDistLogTest {

    private static final String MASTER = "10.1.0.1:30000";
    private static final String SLAVE1 = "10.1.0.2:33000";
    private static final String SLAVE2 = "10.1.0.3:33000";
    private static final String REPLICA = "10.1.0.9:36000";

    private Logger lbLogger;
    private CapturingHandler captured;

    @Before
    public void setUp() {
        MetricsRegistry.clearForTests();
        LbDistLog.resetForTests();
        lbLogger = Logger.getLogger("cubrid.jdbc.lb");
        lbLogger.setLevel(Level.ALL);
        lbLogger.setUseParentHandlers(false);
        captured = new CapturingHandler();
        lbLogger.addHandler(captured);
    }

    @After
    public void tearDown() {
        lbLogger.removeHandler(captured);
        lbLogger.setLevel(null);
        lbLogger.setUseParentHandlers(true);
        LbDistLog.resetForTests();
        MetricsRegistry.clearForTests();
    }

    @Test
    public void nothingIsArmedWithoutTheOption() throws SQLException {
        LbDistLog.configure(settings(0, twoSlaves()));

        LbDistLog.onStatementRouted();

        assertEquals(0, allMatching("LB DIST").size());
    }

    @Test
    public void armingAnnouncesThatCountersAreOnButNoExporterIs() throws SQLException {
        LbDistLog.configure(settings(1, twoSlaves()));

        String line = onlyMatching("LB DIST: distribution records");
        assertTrue(line, line.indexOf("runtime counters enabled") >= 0);
        assertTrue(
                "a log option must not be able to open a port or create a CSV: " + line,
                line.indexOf("no metrics exporter started") >= 0);
    }

    @Test
    public void connectionAndQuerySharesAreReportedSeparately() throws Exception {
        LbDistLog.configure(settings(1, twoSlaves()));
        // One connection on each read endpoint, but the reads are far from even.
        session(MASTER, 400);
        session(SLAVE1, 5000);
        session(SLAVE2, 1200);
        session(REPLICA, 2400);

        emitAfterWindow();

        String conn = onlyMatching("LB DIST [conn]");
        assertTrue(conn, conn.indexOf("live=4") >= 0);
        // slave:2 of total 4 is 50% for the role, split over two slaves.
        assertTrue("per-node target, not per-role: " + conn, conn.indexOf("(target 25.0%)") >= 0);
        assertTrue(conn, conn.indexOf(SLAVE1 + " slave 1conn 25.0%") >= 0);

        String query = onlyMatching("LB DIST [query]");
        assertTrue(query, query.indexOf("total=9000") >= 0);
        assertTrue("the read share is what diverges: " + query, query.indexOf("r=5000") >= 0);
        assertTrue(query, query.indexOf("55.6%r") >= 0);
    }

    @Test
    public void theRoleFoldAppearsOnlyWhenARoleHasMoreThanOneNode() throws Exception {
        LbDistLog.configure(settings(1, twoSlaves()));
        session(SLAVE1, 100);
        session(SLAVE2, 300);

        emitAfterWindow();

        String role = onlyMatching("LB DIST [role]");
        assertTrue(role, role.indexOf("slave(2 node) r=400") >= 0);
    }

    @Test
    public void withOneNodePerRoleTheFoldWouldRepeatTheQueryLineSoItIsOmitted() throws Exception {
        LbDistLog.configure(settings(1, oneSlave()));
        session(SLAVE1, 100);

        emitAfterWindow();

        assertEquals(1, allMatching("LB DIST [query]").size());
        assertEquals(
                "a fold over single-node roles is the query line again",
                0,
                allMatching("LB DIST [role]").size());
    }

    @Test
    public void aWindowReportsTheDifferenceNotTheRunningTotal() throws Exception {
        LbDistLog.configure(settings(1, twoSlaves()));
        RuntimeMetrics m = session(SLAVE1, 1000);

        emitAfterWindow();
        assertTrue(onlyMatching("LB DIST [query]").indexOf("total=1000") >= 0);

        // Second window: 50 more statements, not 1050.
        for (int i = 0; i < 50; i++) {
            m.recordExec(SLAVE1, SqlClassification.READ, false);
        }
        captured.records.clear();
        emitAfterWindow();

        String second = onlyMatching("LB DIST [query]");
        assertTrue(
                "a cumulative ratio goes numb over time: " + second,
                second.indexOf("total=50") >= 0);
    }

    @Test
    public void onlyOneEmitterPerWindowEvenWithConcurrentStatements() throws Exception {
        LbDistLog.configure(settings(1, twoSlaves()));
        session(SLAVE1, 10);
        Thread.sleep(1100);

        List<Thread> racers = new ArrayList<Thread>();
        for (int i = 0; i < 8; i++) {
            Thread t =
                    new Thread(
                            new Runnable() {
                                public void run() {
                                    LbDistLog.onStatementRouted();
                                }
                            });
            racers.add(t);
        }
        for (Thread t : racers) {
            t.start();
        }
        for (Thread t : racers) {
            t.join();
        }

        assertEquals("the CAS elects one emitter", 1, allMatching("LB DIST [conn]").size());
    }

    @Test
    public void theWindowStillOpenAtShutdownIsWritten() throws Exception {
        LbDistLog.configure(settings(1, twoSlaves()));
        session(SLAVE1, 700);

        LbDistLog.drainFinal();

        String query = onlyMatching("LB DIST [query]");
        assertTrue(
                "the interval before traffic stopped is usually the one being investigated: "
                        + query,
                query.indexOf("total=700") >= 0);
        assertTrue(query, query.indexOf("(final)") >= 0);
    }

    @Test
    public void anEmptyFinalWindowIsNotWritten() throws Exception {
        LbDistLog.configure(settings(1, twoSlaves()));
        session(SLAVE1, 700);
        emitAfterWindow();
        captured.records.clear();

        LbDistLog.drainFinal();

        assertEquals(
                "a set of all-zero records reads as a defect, not as 'nothing happened'",
                0,
                allMatching("LB DIST").size());
    }

    @Test
    public void distributionRecordsDoNotRequireTheEventLog() throws Exception {
        LbDistLog.configure(settings(1, twoSlaves()));
        RuntimeMetrics m = session(SLAVE1, 20);
        // This is what the DIST-only path sets: counters on, per-statement events off.
        assertFalse(m.isEventsEnabled());
        m.recordEvent("would be dropped");
        assertTrue(m.getEvents().isEmpty());

        emitAfterWindow();

        assertTrue(onlyMatching("LB DIST [query]").indexOf("total=20") >= 0);
    }

    /** Advances past the window and emits, without waiting on a real clock more than necessary. */
    private void emitAfterWindow() throws Exception {
        Thread.sleep(1100);
        LbDistLog.onStatementRouted();
    }

    private RuntimeMetrics session(final String boundRead, final int reads) {
        final RuntimeMetrics m = new RuntimeMetrics();
        m.setEnabled(true);
        m.setEventsEnabled(false);
        MetricsRegistry.register(
                m,
                roles(),
                new BindingView() {
                    public String boundReadEndpointId() {
                        return boundRead;
                    }

                    public boolean readsOnRwConnection() {
                        return MASTER.equals(boundRead);
                    }
                });
        for (int i = 0; i < reads; i++) {
            m.recordExec(boundRead, SqlClassification.READ, false);
        }
        return m;
    }

    private static Map<String, String> roles() {
        Map<String, String> roles = new LinkedHashMap<String, String>();
        roles.put(MASTER, "master");
        roles.put(SLAVE1, "slave");
        roles.put(SLAVE2, "slave");
        roles.put(REPLICA, "replica");
        return roles;
    }

    private static String twoSlaves() {
        return "jdbc:cubrid:loadbalance://10.1.0.1:30000:33000,10.1.0.2:30000,10.1.0.3:30000"
                + ";replica=10.1.0.9:36000/tdb:dba:pw:?readWeight=master:1,slave:2,replica:1";
    }

    private static String oneSlave() {
        return "jdbc:cubrid:loadbalance://10.1.0.1:30000:33000,10.1.0.2:30000"
                + "/tdb:dba:pw:?readWeight=master:1,slave:2";
    }

    private static LoadBalanceSettings settings(final int distSec, final String baseUrl)
            throws SQLException {
        String url = baseUrl + "&lbLogDistIntervalSec=" + distSec;
        return LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));
    }

    private String onlyMatching(final String needle) {
        List<String> found = allMatching(needle);
        assertEquals("expected exactly one '" + needle + "' record, got " + found, 1, found.size());
        return found.get(0);
    }

    private List<String> allMatching(final String needle) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < captured.records.size(); i++) {
            String message = captured.records.get(i).getMessage();
            if (message != null && message.indexOf(needle) >= 0) {
                out.add(message);
            }
        }
        return out;
    }

    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<LogRecord>();

        @Override
        public void publish(final LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
