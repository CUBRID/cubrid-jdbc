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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Test;

/**
 * The generation scheme both LB-written files depend on: the live file keeps its plain name and
 * history shifts up through {@code .1} … {@code .N}. Covered here rather than through either
 * consumer because a wrong shift silently loses the oldest generation.
 */
public class LbFileRotationTest {

    @Test
    public void rotationKeepsAtMostMaxFilesGenerationsAndDropsTheOldest() throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "lb-file-rotation-test");
        deleteRecursively(dir);
        assertTrue(dir.mkdirs());
        File live = new File(dir, "rotating.log");

        // 3 rotations with maxFiles=2: the live file becomes .1, the old .1 becomes .2, and the
        // generation that would have become .3 is deleted.
        for (int round = 1; round <= 3; round++) {
            write(live, "round" + round);
            LbFileRotation.rotateIfOversized(live, 1L, 2);
        }

        assertFalse("live file is rotated away; the next write re-creates it", live.exists());
        assertEquals("round3", read(new File(dir, "rotating.log.1")));
        assertEquals("round2", read(new File(dir, "rotating.log.2")));
        assertFalse(
                "generation beyond maxFiles must be deleted",
                new File(dir, "rotating.log.3").exists());
        deleteRecursively(dir);
    }

    @Test
    public void rotationIsSkippedBelowTheSizeLimitAndWhenDisabled() throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "lb-file-rotation-skip-test");
        deleteRecursively(dir);
        assertTrue(dir.mkdirs());
        File live = new File(dir, "rotating.log");
        write(live, "keep");

        LbFileRotation.rotateIfOversized(live, 1024L * 1024L, 5); // under the limit
        assertTrue(live.exists());

        LbFileRotation.rotateIfOversized(live, 0L, 5); // rotation disabled
        assertTrue(live.exists());
        assertEquals("keep", read(live));
        deleteRecursively(dir);
    }

    private static void write(final File f, final String body) throws IOException {
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            out.write(body.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static String read(final File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        } finally {
            in.close();
        }
        return new String(buf, "UTF-8");
    }

    private static void deleteRecursively(final File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                kids[i].delete();
            }
        }
        dir.delete();
    }
}
