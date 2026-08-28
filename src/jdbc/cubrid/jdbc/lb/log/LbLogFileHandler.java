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

import cubrid.jdbc.lb.metrics.MetricsExporters;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

/**
 * Appending file handler whose live file always keeps its configured name, with history shifted up
 * through {@code .1} … {@code .N} by {@link MetricsExporters#rotateIfOversized}.
 *
 * <p>JUL's {@link java.util.logging.FileHandler} is not used because it owns opening, locking and
 * rotation itself and, whenever more than one generation is kept, appends a generation number to
 * the live file ({@code cubrid_lb.log.0}). The metrics CSV already rotates the other way round —
 * live file plain, history {@code .1} upward — and two CUBRID-written files under opposite
 * conventions is a permanent documentation cost. Extending {@link StreamHandler} keeps the stream
 * swap under our control, so both files rotate alike and {@code tail -f <lbLogFile>} always names
 * the live file.
 *
 * <p>Each record is flushed as it is published: these are failure-path records, and a log that
 * loses the last buffered lines to a crash is worthless exactly when it is needed. Rotation happens
 * after the write, inside the same {@code synchronized} block, so no record can be written to a
 * stream that is mid-swap.
 *
 * <p><b>Single JVM per file.</b> Two JVMs appending to one path corrupt each other: after one
 * rotates, the other still holds a descriptor to the renamed inode and keeps appending to it, so
 * generation numbers stop reflecting time order and the size cap is exceeded. {@link LbFileLogging}
 * prevents that by locking the path and giving later JVMs a PID-suffixed file.
 */
final class LbLogFileHandler extends StreamHandler {
    private final File file;
    private final long maxBytes;
    private final int maxFiles;

    LbLogFileHandler(
            final File file,
            final int maxSizeMb,
            final int maxFiles,
            final Level level,
            final Formatter formatter)
            throws IOException {
        this.file = file;
        this.maxBytes = (long) maxSizeMb * 1024L * 1024L;
        this.maxFiles = maxFiles;

        setFormatter(formatter);
        // StreamHandler defaults to INFO and would silently discard FINE records even when the
        // logger passes them, so the configured level is applied to the handler as well.
        setLevel(level);
        openStream();
    }

    private void openStream() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            // Best effort: a missing directory surfaces as the FileNotFoundException below, which
            // LbFileLogging reports without failing connection creation.
            parent.mkdirs();
        }
        setOutputStream(new FileOutputStream(file, true));
    }

    @Override
    public synchronized void publish(final LogRecord record) {
        super.publish(record);
        flush();

        if (maxBytes <= 0L || file.length() < maxBytes) {
            return;
        }

        try {
            // close() flushes and closes the stream; the descriptor must be released before the
            // rename, otherwise this JVM would keep writing into the rotated generation.
            close();
            MetricsExporters.rotateIfOversized(file, maxBytes, maxFiles);
            openStream();
        } catch (IOException e) {
            reportError("LB log rotation failed for " + file, e, ErrorManager.WRITE_FAILURE);
        }
    }
}
