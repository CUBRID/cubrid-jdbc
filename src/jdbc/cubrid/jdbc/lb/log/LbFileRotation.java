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

import java.io.File;
import java.util.logging.Logger;

/**
 * Generation rotation for the two files the LB layer writes itself: the LB log file and the metrics
 * CSV. {@code .N} is deleted, {@code .N-1} → {@code .N} … and the live file becomes {@code .1}, so
 * the live file always keeps its plain configured name and disk use stays bounded at {@code
 * maxBytes x (maxFiles + 1)} instead of growing forever.
 *
 * <p>One scheme for both files is deliberate. JUL's {@link java.util.logging.FileHandler} appends a
 * generation number to the live file ({@code cubrid_lb.log.0}) as soon as it keeps more than one
 * generation; two CUBRID-written files under opposite conventions is a permanent documentation
 * cost, and {@code tail -f} would no longer name the live file.
 */
public final class LbFileRotation {

    private static final Logger LOGGER = Logger.getLogger(LbFileRotation.class.getName());

    private LbFileRotation() {}

    /**
     * Rotates {@code file} once it has grown past {@code maxBytes}. The caller re-opens the path
     * afterwards and, finding it absent, starts a fresh file — so a CSV writer re-emits its header
     * and every rotated generation stands on its own.
     *
     * <p>No-op when {@code maxBytes <= 0} (rotation disabled) or the file is still under the limit.
     * {@code maxFiles == 0} keeps no history: the oversized file is simply removed. A failed
     * delete/rename leaves the live file untouched and logs a WARN — losing the newest rows would
     * be worse than overshooting the size cap.
     *
     * @param file the current file to rotate
     * @param maxBytes the size at which the file is rotated; {@code <= 0} disables rotation
     * @param maxFiles how many generations to keep; {@code 0} discards the overflowing file
     */
    public static void rotateIfOversized(final File file, final long maxBytes, final int maxFiles) {
        if (maxBytes <= 0L || !file.exists() || file.length() < maxBytes) {
            return;
        }
        String base = file.getPath();
        File oldest = new File(base + "." + maxFiles);
        if (maxFiles > 0 && oldest.exists() && !oldest.delete()) {
            LOGGER.warning("LB: cannot delete rotated file " + oldest);
            return;
        }
        for (int i = maxFiles - 1; i >= 1; i--) {
            File from = new File(base + "." + i);
            if (from.exists() && !from.renameTo(new File(base + "." + (i + 1)))) {
                LOGGER.warning("LB: cannot rotate " + from);
                return;
            }
        }
        boolean moved = maxFiles == 0 ? file.delete() : file.renameTo(new File(base + ".1"));
        if (!moved) {
            LOGGER.warning("LB: cannot rotate " + file + "; it keeps growing");
        }
    }
}
