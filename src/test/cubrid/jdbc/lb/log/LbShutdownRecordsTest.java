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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.config.LbLogConfig;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import cubrid.jdbc.lb.metrics.BindingView;
import cubrid.jdbc.lb.metrics.MetricsRegistry;
import cubrid.jdbc.lb.metrics.RuntimeMetrics;
import cubrid.jdbc.lb.report.LbDistLog;
import cubrid.jdbc.lb.sql.SqlClassification;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
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
 * The last records of a run: the collapsed-transition tallies and the final distribution window.
 *
 * <p>Both are written from a shutdown hook, and {@code java.util.logging} has a shutdown hook of
 * its own — {@code LogManager$Cleaner}, which calls {@code LogManager.reset()} and thereby removes
 * every handler from every logger, closes it, and clears the configured levels. The JVM starts all
 * hooks at once, so writing these records through the logger is a race, and losing it is silent: a
 * logger with no handlers discards records without reporting an error.
 *
 * <p>Not a theoretical ordering: in three consecutive live coverage runs the tally of what a
 * six-session pool did was truncated twice, each time leaving the log claiming one connection
 * failed back when three did. The loss is biased towards the <em>last</em> records, because the
 * tallies drain in the order their transitions first appeared, so recovery evidence is what goes
 * missing.
 */
public class LbShutdownRecordsTest {

    private static final String MASTER = "10.1.0.1:30000";
    private static final String SLAVE1 = "10.1.0.2:33000";
    private static final String SLAVE2 = "10.1.0.3:33000";
    private static final String REPLICA = "10.1.0.9:36000";

    private static final Logger LB = Logger.getLogger("cubrid.jdbc.lb.LoadBalanceConnection");

    private File dir;

    @Before
    public void setUp() throws IOException {
        LbFileLogging.resetForTests();
        LbLogDedup.resetForTests();
        LbDistLog.resetForTests();
        MetricsRegistry.clearForTests();
        dir = createTempDir();
    }

    @After
    public void tearDown() {
        LbFileLogging.resetForTests();
        LbLogDedup.resetForTests();
        LbDistLog.resetForTests();
        MetricsRegistry.clearForTests();
        deleteRecursively(dir);
    }

    @Test
    public void tallesSurviveTheLoggerBeingTornDownFirst() throws IOException {
        File target = new File(dir, "shutdown.log");
        LbFileLogging.install(config(target.getPath()));

        for (int i = 0; i < 3; i++) {
            LbLogDedup.warn(
                    LB,
                    "conn#" + i,
                    "FAILBACK|RO|replica|slave",
                    "LB FAILBACK [RO]: replica -> slave");
        }

        julShutdownReset();
        LbLogDedup.drain();

        assertEquals(
                "the tally is the only record of what the rest of the pool did",
                1,
                countMatching(
                        target,
                        "LB SUPPRESSED: 2 further occurrence(s) of [FAILBACK|RO|replica|slave]"));
    }

    @Test
    public void aFineKeyedTallySurvivesTheClearedLoggerLevelToo() throws IOException {
        File target = new File(dir, "shutdown-fine.log");
        LbFileLogging.install(config(target.getPath()));

        for (int i = 0; i < 4; i++) {
            LbLogDedup.fine(LB, null, "BACKOFF|held|slave", "LB BACKOFF: slave held");
        }

        // reset() clears the logger's level as well as its handlers, so a FINE record asked of the
        // logger afterwards is dropped before the handler is even consulted. The level that decides
        // here has to be the configured one.
        julShutdownReset();
        LbLogDedup.drain();

        assertEquals(
                1,
                countMatching(
                        target, "LB SUPPRESSED: 3 further occurrence(s) of [BACKOFF|held|slave]"));
    }

    @Test
    public void theFinalDistributionWindowSurvivesToo() throws Exception {
        File target = new File(dir, "shutdown-dist.log");
        LbFileLogging.install(config(target.getPath()));
        LbDistLog.configure(settings(1));
        session(SLAVE1, 700);

        julShutdownReset();
        LbDistLog.drainFinal();

        assertEquals(1, countMatching(target, "LB DIST [query]"));
        assertTrue(firstMatching(target, "LB DIST [query]").indexOf("(final)") >= 0);
    }

    @Test
    public void aDirectlyAppendedRecordKeepsTheFileLayout() throws IOException {
        File target = new File(dir, "layout.log");
        LbFileLogging.install(config(target.getPath()));

        julShutdownReset();
        LbLog.atShutdown(LB, Level.WARNING, "conn#7", "LB SUPPRESSED: 2 further occurrence(s)");

        String line = firstMatching(target, "LB SUPPRESSED");
        String[] fields = line.split("\\|");
        assertEquals(
                "a record written past the logger is still one grep away: " + line,
                4,
                fields.length);
        assertTrue(
                "sortable timestamp: " + fields[0], fields[0].matches("\\d{4}-\\d{2}-\\d{2} .*"));
        assertEquals("WARN ", fields[1]);
        assertEquals("conn#7", fields[2]);
    }

    @Test
    public void withoutAnLbLogFileTheRecordStillGoesThroughTheLogger() {
        Logger pkg = Logger.getLogger("cubrid.jdbc.lb");
        CapturingHandler captured = new CapturingHandler();
        pkg.setLevel(Level.ALL);
        pkg.addHandler(captured);
        try {
            LbLog.atShutdown(LB, Level.WARNING, null, "LB SUPPRESSED: 1 further occurrence(s)");

            assertEquals(
                    "the LB records may be wired to a container's handler instead of a file",
                    1,
                    captured.records.size());
        } finally {
            pkg.removeHandler(captured);
            pkg.setLevel(null);
        }
    }

    /**
     * What {@code LogManager.reset()} does to the LB logger, without resetting the whole JVM's
     * logging out from under the rest of the suite.
     */
    private static void julShutdownReset() {
        Logger pkg = Logger.getLogger("cubrid.jdbc.lb");
        Handler[] handlers = pkg.getHandlers();
        for (int i = 0; i < handlers.length; i++) {
            pkg.removeHandler(handlers[i]);
            handlers[i].close();
        }
        pkg.setLevel(null);
    }

    private static LbLogConfig config(final String path) {
        Map<String, String> options = new HashMap<String, String>();
        options.put(LbLogConfig.OPT_FILE, path);
        options.put(LbLogConfig.OPT_LEVEL, "FINE");
        options.put(LbLogConfig.OPT_TO_CONSOLE, "false");
        return LbLogConfig.parse(options, true);
    }

    private static LoadBalanceSettings settings(final int distSec) throws SQLException {
        String url =
                "jdbc:cubrid:loadbalance://10.1.0.1:30000:33000,10.1.0.2:30000,10.1.0.3:30000"
                        + ";replica=10.1.0.9:36000/tdb:dba:pw:?readWeight=master:1,slave:2,replica:1"
                        + "&lbLogDistIntervalSec="
                        + distSec;
        return LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));
    }

    private static RuntimeMetrics session(final String boundRead, final int reads) {
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

    private static int countMatching(final File file, final String needle) throws IOException {
        int n = 0;
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.indexOf(needle) >= 0) {
                    n++;
                }
            }
        } finally {
            reader.close();
        }
        return n;
    }

    private static String firstMatching(final File file, final String needle) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.indexOf(needle) >= 0) {
                    return line;
                }
            }
        } finally {
            reader.close();
        }
        return "";
    }

    private static File createTempDir() throws IOException {
        File tmp = File.createTempFile("lb-shutdown-records", "");
        if (!tmp.delete() || !tmp.mkdir()) {
            throw new IOException("cannot create a temp directory at " + tmp);
        }
        return tmp;
    }

    private static void deleteRecursively(final File file) {
        if (file == null) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                deleteRecursively(children[i]);
            }
        }
        file.delete();
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
