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

import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import cubrid.jdbc.lb.log.LbLogDedup;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Held-back tallies are written when the last LB connection closes. The shutdown hook that also
 * writes them races {@code java.util.logging}'s own, which removes every handler first.
 */
public class LoadBalanceConnectionLogFlushTest {
    private static final String URL =
            "jdbc:cubrid:loadbalance://10.1.0.1:30000:33000,10.1.0.2:30000/tdb:dba:pw:";
    private static final Logger LB = Logger.getLogger(LoadBalanceConnection.class.getName());

    private Logger lbLogger;
    private CapturingHandler captured;

    @Before
    public void setUp() throws Exception {
        LbLogDedup.drain();
        zeroLiveConnections();
        lbLogger = Logger.getLogger("cubrid.jdbc.lb");
        lbLogger.setLevel(Level.ALL);
        lbLogger.setUseParentHandlers(false);
        captured = new CapturingHandler();
        lbLogger.addHandler(captured);
    }

    @After
    public void tearDown() {
        lbLogger.removeHandler(captured);
        lbLogger.setLevel(null);
        lbLogger.setUseParentHandlers(true);
        LbLogDedup.drain();
    }

    @Test
    public void closingTheLastConnectionWritesTheHeldBackTally() throws SQLException {
        LoadBalanceConnection first = open();
        LoadBalanceConnection second = open();
        for (int i = 0; i < 3; i++) {
            LbLogDedup.warn(
                    LB,
                    "conn#" + i,
                    "FAILBACK|RO|replica|slave",
                    "LB FAILBACK [RO]: replica -> slave");
        }

        first.close();
        first.close();
        assertEquals("the pool is still open: keep collapsing", 0, suppressed().size());

        second.close();
        List<String> tallies = suppressed();
        assertEquals(1, tallies.size());
        assertTrue(tallies.get(0), tallies.get(0).startsWith("LB SUPPRESSED: 2 further"));
    }

    /** Other tests leave connections open, which would keep the count above zero. */
    private static void zeroLiveConnections() throws Exception {
        Field field = LoadBalanceConnection.class.getDeclaredField("LIVE_CONNECTIONS");
        field.setAccessible(true);
        ((AtomicInteger) field.get(null)).set(0);
    }

    private static LoadBalanceConnection open() throws SQLException {
        return new LoadBalanceConnection(
                LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(URL)));
    }

    private List<String> suppressed() {
        List<String> out = new ArrayList<String>();
        for (LogRecord r : captured.records) {
            if (r.getMessage().startsWith("LB SUPPRESSED")) {
                out.add(r.getMessage());
            }
        }
        return out;
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
