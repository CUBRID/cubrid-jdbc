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
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The LB log file is set up in logging.properties: the handler reads its keys under its own class
 * name and writes the LB layout without a formatter being named.
 */
public class LbFileHandlerTest {
    private static final String PREFIX = LbFileHandler.class.getName();

    private File dir;
    private Logger logger;
    private LbFileHandler handler;

    @Before
    public void setUp() throws IOException {
        dir = createTempDir();
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
    }

    @After
    public void tearDown() throws IOException {
        if (handler != null) {
            logger.removeHandler(handler);
            handler.close();
        }
        LogManager.getLogManager().readConfiguration();
        deleteRecursively(dir);
    }

    @Test
    public void recordsAreWrittenInTheLbLayoutWhereThePropertiesSay() throws IOException {
        configure(PREFIX + ".pattern=" + pattern("lb.%g.log"));
        handler = attach(new LbFileHandler());

        LbLog.warn(logger, "conn#7", "LB FAILOVER [RO]: a -> b");
        logger.info("LB metrics: no connection in scope");
        handler.close();

        List<String> lines = read(new File(dir, "lb.0.log"));
        String[] failover = lines.get(1).split("\\|");
        assertEquals(lines.get(1), 4, failover.length);
        assertTrue(
                "sortable timestamp: " + failover[0],
                failover[0].matches("\\d{4}-\\d{2}-\\d{2} .*"));
        assertEquals("WARN ", failover[1]);
        assertEquals("conn#7", failover[2]);
        assertEquals("LB FAILOVER [RO]: a -> b", failover[3]);
        assertEquals("-", lines.get(2).split("\\|")[2]);
    }

    @Test
    public void everyGenerationOpensWithTheBuildThatWroteIt() throws IOException {
        configure(
                PREFIX + ".pattern=" + pattern("lb.%g.log"),
                PREFIX + ".limit=300",
                PREFIX + ".count=3");
        handler = attach(new LbFileHandler());

        for (int i = 0; i < 20; i++) {
            logger.warning("LB FAILOVER [RO]: record " + i);
        }
        handler.close();

        for (int g = 0; g < 3; g++) {
            File generation = new File(dir, "lb." + g + ".log");
            assertTrue(generation + " exists", generation.exists());
            String first = read(generation).get(0);
            assertTrue(
                    generation + " starts with the banner: " + first,
                    first.contains("LB LOG: driver="));
        }
    }

    @Test
    public void theThreadColumnIsSwitchedOnInLoggingProperties() throws IOException {
        configure(
                PREFIX + ".pattern=" + pattern("lb.%g.log"),
                LbLogFormatter.class.getName() + ".thread=true");
        handler = attach(new LbFileHandler());

        LbLog.warn(logger, "conn#7", "LB FAILOVER [RO]: a -> b");
        handler.close();

        String[] fields = read(new File(dir, "lb.0.log")).get(1).split("\\|");
        assertEquals(5, fields.length);
        assertEquals(Thread.currentThread().getName(), fields[2]);
        assertEquals("conn#7", fields[3]);
    }

    /** A connection URL used to create the directory before the server had authenticated anyone. */
    @Test
    public void aMissingDirectoryIsReportedNotCreated() throws IOException {
        File missing = new File(dir, "no-such-dir");
        configure(
                PREFIX + ".pattern=" + new File(missing, "lb.%g.log").getPath().replace('\\', '/'));

        try {
            handler = new LbFileHandler();
            fail("a handler was opened under a directory that does not exist");
        } catch (IOException expected) {
            // FileHandler cannot create its lock file there.
        }
        assertFalse(missing.exists());
    }

    private LbFileHandler attach(final LbFileHandler h) {
        logger.addHandler(h);
        return h;
    }

    private String pattern(final String name) {
        return new File(dir, name).getPath().replace('\\', '/');
    }

    private static void configure(final String... lines) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            b.append(lines[i]).append('\n');
        }
        LogManager.getLogManager()
                .readConfiguration(new ByteArrayInputStream(b.toString().getBytes("ISO-8859-1")));
    }

    private static List<String> read(final File file) throws IOException {
        List<String> lines = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            reader.close();
        }
        return lines;
    }

    private static File createTempDir() throws IOException {
        File tmp = File.createTempFile("lb-file-handler", "");
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
}
