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

package cubrid.jdbc.lb.config;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * m27: a pool parses its URL for every connection, so a configuration warning given per parse
 * repeats once per connection. Like the allow-list and readWeight warnings, these are given once
 * per JVM.
 */
public class ConfigWarningsOnceTest {
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
    private final Handler capture =
            new Handler() {
                @Override
                public void publish(final LogRecord record) {
                    if (record.getLevel() == Level.WARNING) {
                        warnings.add(record.getMessage());
                    }
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            };
    private final Logger[] loggers = {
        Logger.getLogger(LbLogConfig.class.getName()),
        Logger.getLogger(MetricsConfig.class.getName())
    };

    @Before
    public void setUp() throws Exception {
        clearWarned(); // JVM-wide: another test may have given the same warning already
        for (Logger each : loggers) {
            each.addHandler(capture);
            each.setUseParentHandlers(false);
        }
    }

    @After
    public void tearDown() throws Exception {
        for (Logger each : loggers) {
            each.removeHandler(capture);
            each.setUseParentHandlers(true);
        }
        clearWarned();
    }

    @Test
    public void lbLogWarningsAreGivenOncePerJvm() {
        for (int i = 0; i < 3; i++) {
            LbLogConfig.parse(options(LbLogConfig.OPT_SQL, "raw"));
            LbLogConfig.parse(options(LbLogConfig.OPT_SQL, "bogus"));
            LbLogConfig.parse(options(LbLogConfig.OPT_SQL_MAX_LEN, "-1"));
            LbLogConfig.parse(options(LbLogConfig.OPT_DIST_INTERVAL_SEC, "x"));
        }

        assertEquals(warnings.toString(), 4, warnings.size());
    }

    @Test
    public void metricsWarningsAreGivenOncePerJvm() throws SQLException {
        for (int i = 0; i < 3; i++) {
            MetricsConfig.parse(
                    options(MetricsConfig.OPT_ENABLED, "true", MetricsConfig.OPT_EXPORT, "foo"));
            MetricsConfig.parse(
                    options(
                            MetricsConfig.OPT_ENABLED,
                            "true",
                            MetricsConfig.OPT_EXPORT,
                            "prometheus",
                            MetricsConfig.OPT_INTERVAL_SEC,
                            "0"));
        }

        // an unknown target, no known target, a non-positive interval
        assertEquals(warnings.toString(), 3, warnings.size());
    }

    private static Map<String, String> options(final String... keysAndValues) {
        Map<String, String> options = new HashMap<String, String>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            options.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return options;
    }

    @SuppressWarnings("unchecked")
    private static void clearWarned() throws Exception {
        Field warned = LoadBalanceOptionValidator.class.getDeclaredField("WARNED");
        warned.setAccessible(true);
        synchronized (LoadBalanceOptionValidator.class) {
            ((Set<String>) warned.get(null)).clear();
        }
    }
}
