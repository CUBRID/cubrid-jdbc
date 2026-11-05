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
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Each CSV file the handler opens parses on its own: the header leads every generation. */
public class MetricsFileHandlerTest {
    private static final String PREFIX = MetricsFileHandler.class.getName();
    private static final String HEADER = MetricsFormatter.HEADER.trim();

    private File dir;
    private Logger logger;

    @Before
    public void setUp() throws IOException {
        dir = createTempDir();
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
    }

    @After
    public void tearDown() throws IOException {
        LogManager.getLogManager().readConfiguration();
        deleteRecursively(dir);
    }

    @Test
    public void everyRotatedGenerationStartsWithTheHeader() throws IOException {
        configure(PREFIX + ".limit=400", PREFIX + ".count=3");
        MetricsFileHandler handler = new MetricsFileHandler();
        logger.addHandler(handler);
        try {
            for (int i = 0; i < 20; i++) {
                logger.info("2026-10-15 10:00:00,app1,10.0.0.2:33000,slave," + i + ",0");
            }
        } finally {
            logger.removeHandler(handler);
            handler.close();
        }

        for (int g = 0; g < 3; g++) {
            List<String> lines = read(new File(dir, "lb-metrics." + g + ".csv"));
            assertEquals("generation " + g, HEADER, lines.get(0));
            // The live file may hold only the header: the row that fills a file rotates it at once.
            assertTrue("generation " + g + " carries rows", g == 0 || lines.size() > 1);
        }
    }

    @Test
    public void aRestartRotatesThePreviousRunInsteadOfAppendingToIt() throws IOException {
        configure();
        writeOneRow("run-1");
        writeOneRow("run-2");

        assertEquals(Arrays.asList(HEADER, "run-2"), read(new File(dir, "lb-metrics.0.csv")));
        assertEquals(Arrays.asList(HEADER, "run-1"), read(new File(dir, "lb-metrics.1.csv")));
    }

    private void writeOneRow(final String row) throws IOException {
        MetricsFileHandler handler = new MetricsFileHandler();
        logger.addHandler(handler);
        try {
            logger.info(row);
        } finally {
            logger.removeHandler(handler);
            handler.close();
        }
    }

    private void configure(final String... lines) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append(PREFIX)
                .append(".pattern=")
                .append(new File(dir, "lb-metrics.%g.csv").getPath().replace('\\', '/'))
                .append('\n');
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
        File tmp = File.createTempFile("lb-metrics-handler", "");
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
