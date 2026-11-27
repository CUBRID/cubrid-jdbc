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

package cubrid.jdbc.lb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * An interval measured on the wall clock stretches when that clock is set back, as NTP does: a
 * throttle, a retry or a log window then holds for as long as the step. LB code measures one on
 * {@code System.nanoTime()}.
 */
public final class IntervalClockArchitectureTest {
    private static final String WALL_CLOCK = "System.currentTimeMillis(";

    private static final String[] SOURCE_ROOTS = {
        "src/jdbc/cubrid/jdbc/lb",
        "cubrid-jdbc/src/jdbc/cubrid/jdbc/lb",
        "../src/jdbc/cubrid/jdbc/lb"
    };

    @Test
    public void lbCodeMeasuresNoIntervalOnTheWallClock() throws IOException {
        final List<File> files = new ArrayList<File>();
        collectJava(sourceRoot(), files);
        assertTrue("no LB sources found", files.size() > 10);

        final List<String> found = new ArrayList<String>();
        for (File file : files) {
            int lineNo = 0;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                lineNo++;
                final String code = line.trim();
                if (code.startsWith("*") || code.startsWith("//")) {
                    continue;
                }
                if (line.contains(WALL_CLOCK)) {
                    found.add(file.getName() + ":" + lineNo);
                }
            }
        }

        assertEquals("the wall clock is read in", "[]", found.toString());
    }

    private static void collectJava(final File dir, final List<File> out) {
        final File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File each : entries) {
            if (each.isDirectory()) {
                collectJava(each, out);
            } else if (each.getName().endsWith(".java")) {
                out.add(each);
            }
        }
    }

    private static File sourceRoot() {
        for (String candidate : SOURCE_ROOTS) {
            final File dir = new File(candidate);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "LB sources not found from " + new File(".").getAbsolutePath());
    }
}
