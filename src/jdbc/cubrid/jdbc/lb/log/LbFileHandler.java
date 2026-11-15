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

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.LogManager;

/**
 * {@link FileHandler} for the {@code cubrid.jdbc.lb} logger. It reads {@code pattern}, {@code
 * limit}, {@code count}, {@code append}, {@code level}, {@code formatter} and {@code encoding} from
 * logging.properties under its own class name; an unset key takes the LB default rather than
 * FileHandler's ({@code ~/java0.log} in XML).
 *
 * <pre>
 * cubrid.jdbc.lb.handlers = cubrid.jdbc.lb.log.LbFileHandler
 * cubrid.jdbc.lb.useParentHandlers = false
 * cubrid.jdbc.lb.log.LbFileHandler.pattern = /var/log/app/cubrid_lb.%g.log
 * </pre>
 */
public class LbFileHandler extends FileHandler {
    private static final String PREFIX = LbFileHandler.class.getName();

    public LbFileHandler() throws IOException {
        this(PREFIX, "%h/cubrid_lb.%g.log", 10 * 1024 * 1024, 5, true);
        // Java 8 loads a named formatter through the system class loader, which cannot see a driver
        // deployed inside a web application, and silently falls back to XMLFormatter.
        String formatter = LogManager.getLogManager().getProperty(PREFIX + ".formatter");
        if (formatter == null || LbLogFormatter.class.getName().equals(formatter.trim())) {
            setFormatter(new LbLogFormatter());
        }
    }

    /**
     * For a subclass with its own defaults. {@code prefix} must be the subclass's name, the one
     * {@link FileHandler} reads the remaining keys under.
     *
     * @param prefix the subclass's class name
     * @param pattern default {@code pattern}
     * @param limit default {@code limit} in bytes
     * @param count default {@code count}
     * @param append default {@code append}
     * @throws IOException if the file cannot be opened
     */
    protected LbFileHandler(
            final String prefix,
            final String pattern,
            final int limit,
            final int count,
            final boolean append)
            throws IOException {
        super(
                property(prefix + ".pattern", pattern),
                intProperty(prefix + ".limit", limit, 0),
                intProperty(prefix + ".count", count, 1),
                Boolean.parseBoolean(property(prefix + ".append", String.valueOf(append))));
    }

    private static String property(final String key, final String defaultValue) {
        String value = LogManager.getLogManager().getProperty(key);
        return value == null || value.trim().length() == 0 ? defaultValue : value.trim();
    }

    /** FileHandler rejects a negative limit and a count below one, so those fall back too. */
    private static int intProperty(final String key, final int defaultValue, final int min) {
        try {
            int value = Integer.parseInt(property(key, String.valueOf(defaultValue)));
            return value >= min ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
