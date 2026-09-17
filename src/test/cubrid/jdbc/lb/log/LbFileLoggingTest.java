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

import cubrid.jdbc.lb.config.LbLogConfig;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Installation behaviour of the LB log file: the singleton gate, the file naming and rotation
 * scheme, the record layout, and the conditions under which nothing is written at all.
 *
 * <p>The singleton test is the load-bearing one. The handler attaches to the package logger, which
 * is a single JVM-wide object, so installing per connection would give a 100-connection pool 100
 * handlers on that one logger — and JUL publishes every record to all of them, so one failover
 * would be written 100 times, into 100 separate files.
 */
public class LbFileLoggingTest {

    private static final Logger LB = Logger.getLogger("cubrid.jdbc.lb.LoadBalanceConnection");

    private File dir;

    @Before
    public void setUp() throws IOException {
        LbFileLogging.resetForTests();
        LbLogDedup.resetForTests();
        dir = createTempDir();
    }

    @After
    public void tearDown() {
        LbFileLogging.resetForTests();
        LbLogDedup.resetForTests();
        deleteRecursively(dir);
    }

    @Test
    public void noFileIsWrittenWhenLbLogFileIsNotSet() {
        LbFileLogging.install(LbLogConfig.parse(new HashMap<String, String>(), true));

        LB.warning("LB FAILOVER [RO] failed=a -> new=b");

        assertEquals(0, handlerCount());
        assertEquals(0, dir.listFiles().length);
    }

    /**
     * The file must appear where {@code lbLogFile} says and nowhere else. An earlier version sent
     * relative paths to {@code $CUBRID/log}, which put the file on a directory that usually does
     * not exist on an application host — the driver runs there, not on the database server — and
     * made {@code ./lb.log} land somewhere other than the current directory.
     */
    @Test
    public void aRelativePathIsTakenAgainstTheWorkingDirectoryNotAnInstallationDirectory()
            throws IOException {
        File cwd = new File(System.getProperty("user.dir"));
        File expected = new File(cwd, "lb-log-relative-path-test.log");
        expected.delete();
        new File(expected.getPath() + ".lck").delete();

        try {
            LbFileLogging.install(config("./" + expected.getName()));
            LB.warning("LB FAILOVER [RO] failed=a -> new=b");
            flushHandlers();

            assertTrue(
                    "expected the file at " + expected + ", the working directory",
                    expected.exists());
            assertTrue(countMatching(expected, "LB FAILOVER") > 0);

            String cubridHome = System.getenv("CUBRID");
            if (cubridHome != null && cubridHome.trim().length() > 0) {
                assertFalse(
                        "a relative path must not be redirected under $CUBRID",
                        new File(new File(cubridHome.trim(), "log"), expected.getName()).exists());
            }
        } finally {
            expected.delete();
            new File(expected.getPath() + ".lck").delete();
        }
    }

    @Test
    public void installIsIdempotentAcrossAPoolOfConnections() throws IOException {
        LbLogConfig config = config(new File(dir, "cubrid_lb.log").getPath());

        for (int i = 0; i < 100; i++) {
            LbFileLogging.install(config);
        }

        assertEquals("one handler, not one per connection", 1, handlerCount());

        LB.warning("LB FAILOVER [RO] failed=a -> new=b");

        assertEquals("one file, not one per connection", 1, logFiles().size());
        assertEquals(
                "the event is recorded once, not once per connection",
                1,
                countMatching(logFiles().get(0), "LB FAILOVER"));
    }

    @Test
    public void liveFileKeepsItsNameAndHistoryShiftsUpwards() throws IOException {
        File target = new File(dir, "cubrid_lb.log");
        LbFileLogging.install(config(target.getPath(), 1, 3));

        // 1MB cap; each record is ~175 bytes formatted, so this forces several rotations.
        for (int i = 0; i < 20000; i++) {
            LB.warning(
                    "LB FAILOVER [RO] filler record "
                            + i
                            + " ------------------------------"
                            + "------------------------------------------------------------------------");
        }
        flushHandlers();

        assertTrue("the live file keeps the configured name", target.exists());
        assertTrue(
                "history shifts up to .1, matching the metrics CSV scheme",
                new File(dir, "cubrid_lb.log.1").exists());
        assertFalse(
                "the live file is never given a generation suffix (JUL FileHandler would)",
                new File(dir, "cubrid_lb.log.0").exists());
        // The stream is closed and reopened on every rotation. If that swap left the handler
        // without
        // a writer, the file would still exist and every later record would be lost silently.
        assertEquals(
                "records keep landing after the stream is swapped",
                1,
                countMatching(target, "filler record 19999 "));
        assertTrue(
                "the oldest generation is dropped rather than kept forever",
                logFiles().size() <= 4);
    }

    @Test
    public void everyFileOpensWithTheBuildAndSettingsThatProducedIt() throws IOException {
        LbFileLogging.install(config(new File(dir, "cubrid_lb.log").getPath()));
        flushHandlers();

        String header = firstMatching(logFiles().get(0), "LB LOG:");
        assertTrue(
                "the header must survive the default WARNING level, or it is missing from every log"
                        + " that matters: "
                        + header,
                header.indexOf("file logging started") >= 0);
        assertTrue(header.indexOf("driver=") >= 0);
        assertTrue(header.indexOf("rotate=") >= 0);
    }

    @Test
    public void recordCarriesTimestampLevelContextAndMessageOnOneLine() throws IOException {
        LbFileLogging.install(config(new File(dir, "cubrid_lb.log").getPath()));

        LbLog.warn(LB, LbLog.conn(7L), "LB FAILOVER [RO] failed=a -> new=b");
        flushHandlers();

        String line = firstMatching(logFiles().get(0), "LB FAILOVER");
        String[] fields = line.split("\\|");

        assertEquals(4, fields.length);
        assertTrue(
                "sortable timestamp: " + fields[0], fields[0].matches("\\d{4}-\\d{2}-\\d{2} .*"));
        assertEquals("WARN ", fields[1]);
        assertEquals("conn#7", fields[2]);
        assertEquals("LB FAILOVER [RO] failed=a -> new=b", fields[3]);
    }

    @Test
    public void aCallSiteWithNoConnectionInScopeRendersTheNoContextMarker() throws IOException {
        LbFileLogging.install(config(new File(dir, "cubrid_lb.log").getPath()));

        // A plain Logger call, as the config/metrics call sites make.
        LB.warning("LB BIND [RW]: preferred master unreachable");
        flushHandlers();

        String line = firstMatching(logFiles().get(0), "LB BIND");
        assertEquals("-", line.split("\\|")[2]);
    }

    @Test
    public void defaultLevelKeepsPerQueryFineRecordsOutOfTheFile() throws IOException {
        LbFileLogging.install(config(new File(dir, "cubrid_lb.log").getPath()));

        LbLog.fine(LB, null, "LB CONNECT FAILED [RO] endpoint=a -> trying next candidate");
        LbLog.warn(LB, null, "LB FAILOVER [RO] failed=a -> new=b");
        flushHandlers();

        File file = logFiles().get(0);
        assertEquals(0, countMatching(file, "LB CONNECT FAILED"));
        assertEquals(1, countMatching(file, "LB FAILOVER"));
    }

    @Test
    public void fineLevelAdmitsTheDebugRecords() throws IOException {
        Map<String, String> options = new HashMap<String, String>();
        options.put(LbLogConfig.OPT_FILE, new File(dir, "cubrid_lb.log").getPath());
        options.put(LbLogConfig.OPT_LEVEL, "FINE");
        options.put(LbLogConfig.OPT_TO_CONSOLE, "false");
        LbFileLogging.install(LbLogConfig.parse(options, true));

        LbLog.fine(LB, null, "LB CONNECT FAILED [RO] endpoint=a -> trying next candidate");
        flushHandlers();

        assertEquals(1, countMatching(logFiles().get(0), "LB CONNECT FAILED"));
    }

    @Test
    public void aSecondConflictingPathIsIgnoredRatherThanDuplicatingEveryRecord()
            throws IOException {
        LbFileLogging.install(config(new File(dir, "svc-order.log").getPath()));
        LbFileLogging.install(config(new File(dir, "svc-batch.log").getPath()));

        LB.warning("LB FAILOVER [RO] failed=a -> new=b");
        flushHandlers();

        assertEquals("first path wins", 1, handlerCount());
        assertTrue(new File(dir, "svc-order.log").exists());
        assertFalse(
                "a second file would receive every record too, not a share of them",
                new File(dir, "svc-batch.log").exists());
    }

    @Test
    public void consoleOutputCanBeSwitchedOffWhileTheFileKeepsRecording() throws IOException {
        LbFileLogging.install(config(new File(dir, "cubrid_lb.log").getPath()));

        assertFalse(Logger.getLogger("cubrid.jdbc.lb").getUseParentHandlers());

        LB.warning("LB FAILOVER [RO] failed=a -> new=b");
        flushHandlers();
        assertEquals(1, countMatching(logFiles().get(0), "LB FAILOVER"));
    }

    private static LbLogConfig config(final String path) {
        Map<String, String> options = new HashMap<String, String>();
        options.put(LbLogConfig.OPT_FILE, path);
        // Size and generation count are left out so parse() applies its own defaults.
        options.put(LbLogConfig.OPT_TO_CONSOLE, "false");
        return LbLogConfig.parse(options, true);
    }

    private static LbLogConfig config(final String path, final int maxSizeMb, final int maxFiles) {
        Map<String, String> options = new HashMap<String, String>();
        options.put(LbLogConfig.OPT_FILE, path);
        options.put(LbLogConfig.OPT_MAX_SIZE_MB, String.valueOf(maxSizeMb));
        options.put(LbLogConfig.OPT_MAX_FILES, String.valueOf(maxFiles));
        // Keep the surefire console clean; the file is what these tests read.
        options.put(LbLogConfig.OPT_TO_CONSOLE, "false");
        return LbLogConfig.parse(options, true);
    }

    private static int handlerCount() {
        return Logger.getLogger("cubrid.jdbc.lb").getHandlers().length;
    }

    private static void flushHandlers() {
        Handler[] handlers = Logger.getLogger("cubrid.jdbc.lb").getHandlers();
        for (int i = 0; i < handlers.length; i++) {
            handlers[i].flush();
        }
    }

    /** Log files only: the {@code .lck} sidecars are bookkeeping, not output. */
    private List<File> logFiles() {
        List<File> out = new ArrayList<File>();
        File[] files = dir.listFiles();
        for (int i = 0; i < files.length; i++) {
            if (!files[i].getName().endsWith(".lck")) {
                out.add(files[i]);
            }
        }
        return out;
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
        File f = File.createTempFile("lb-log-test", "");
        if (!f.delete() || !f.mkdirs()) {
            throw new IOException("cannot create a temporary directory at " + f);
        }
        return f;
    }

    private static void deleteRecursively(final File target) {
        if (target == null || !target.exists()) {
            return;
        }
        File[] children = target.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                deleteRecursively(children[i]);
            }
        }
        target.delete();
    }
}
