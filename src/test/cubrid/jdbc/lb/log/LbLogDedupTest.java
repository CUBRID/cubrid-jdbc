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
import static org.junit.Assert.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Collapsing of the per-connection record burst a pool produces from one cluster event, and the
 * trimming applied when a driver exception is quoted into a record.
 *
 * <p>A hundred-connection pool reacting to one broker outage writes a hundred identical transition
 * lines, each carrying the base driver's bracketed trailers — CAS address, session id and the whole
 * connection URL — which is what turned a single failover into 400+ characters times the pool size.
 */
public class LbLogDedupTest {

    private static final String KEY = "FAILOVER|RO|10.0.0.1:30000|10.0.0.2:33000";
    private static final String MESSAGE =
            "LB FAILOVER [RO] failed=10.0.0.1:30000 -> new=10.0.0.2:33000";

    private Logger logger;
    private CapturingHandler captured;

    @Before
    public void setUp() {
        LogTestAccess.resetDedup();
        logger = Logger.getLogger("cubrid.jdbc.lb.LbLogDedupTest");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        captured = new CapturingHandler();
        logger.addHandler(captured);
    }

    @After
    public void tearDown() {
        logger.removeHandler(captured);
        logger.setUseParentHandlers(true);
        LogTestAccess.resetDedup();
    }

    @Test
    public void aPoolWideBurstOfOneTransitionIsWrittenOnce() {
        for (int i = 0; i < 100; i++) {
            LbLogDedup.warn(logger, LbLog.conn(i + 1), KEY, MESSAGE);
        }

        assertEquals(1, captured.messages.size());
    }

    @Test
    public void distinctTransitionsAreNotCollapsedIntoEachOther() {
        LbLogDedup.warn(logger, null, "FAILOVER|RO|a|b", "LB FAILOVER [RO] a -> b");
        LbLogDedup.warn(logger, null, "FAILOVER|RW|c|d", "LB FAILOVER [RW] c -> d");
        LbLogDedup.warn(logger, null, "FAILBACK|RO|b|a", "LB FAILBACK [RO] b -> a");

        assertEquals(3, captured.messages.size());
    }

    /**
     * r5 m13: what a window held back is reported as that window's, on its own line, before the
     * next occurrence; the next occurrence, which may come hours later, carries no tally.
     */
    @Test
    public void theNextOccurrenceAfterTheWindowFollowsTheTallyOfThatWindow()
            throws InterruptedException {
        long window = 5L;

        LbLogDedup.warn(logger, null, KEY, MESSAGE, window);
        for (int i = 0; i < 9; i++) {
            LbLogDedup.warn(logger, null, KEY, MESSAGE, window);
        }
        Thread.sleep(window * 2L);
        LbLogDedup.warn(logger, null, KEY, MESSAGE, window);

        assertEquals(3, captured.messages.size());
        assertTrue(
                captured.messages.get(1),
                captured.messages.get(1).startsWith("LB SUPPRESSED: 9 further occurrence(s)"));
        assertEquals(MESSAGE, captured.messages.get(2));
    }

    /**
     * r5 m10: keys that carry a statement shape can fill the map. Reaching the cap writes what is
     * held back before the map is emptied.
     */
    @Test
    public void reachingTheKeyCapWritesWhatWasHeldBack() {
        LbLogDedup.warn(logger, null, KEY, MESSAGE);
        LbLogDedup.warn(logger, null, KEY, MESSAGE);
        for (int i = 0; i < 300; i++) {
            LbLogDedup.warn(logger, null, "RO REJECTED|-181|SELECT c" + i, "LB RO REJECTED " + i);
        }

        int summaries = 0;
        for (String m : captured.messages) {
            if (m.startsWith("LB SUPPRESSED: 1 further occurrence(s) of [" + KEY + "]")) {
                summaries++;
            }
        }
        assertEquals("summaries of the held-back burst", 1, summaries);
    }

    @Test
    public void aBurstThatNeverRecursIsStillReportedOnDrain() {
        for (int i = 0; i < 100; i++) {
            LbLogDedup.warn(logger, null, KEY, MESSAGE);
        }
        LbLogDedup.drain();

        assertEquals(2, captured.messages.size());
        assertTrue(
                "without this, the log would claim one connection failed over when 100 did: "
                        + captured.messages.get(1),
                captured.messages.get(1).indexOf("LB SUPPRESSED: 99") >= 0);
    }

    @Test
    public void quotedDriverMessageDropsTheRepeatedBracketedTrailers() {
        SQLException cause =
                new SQLException(
                        "Cannot communicate with the broker or received invalid packet"
                                + "[CAS INFO-192.168.2.196:30000,1,3374505],[SESSION-591],"
                                + "[URL-jdbc:cubrid:Loadbalance://192.168.2.196:30000:33000,"
                                + "192.168.2.197:30000;replica=192.168.2.195:36000/tdb:dba:****:?"
                                + "rcTime=600&connectTimeout=5&useSSL=false].",
                        null,
                        -21019);

        String rendered = LbLog.cause(cause);

        assertEquals(
                "SQLState=null errorCode=-21019 msg="
                        + "Cannot communicate with the broker or received invalid packet",
                rendered);
    }

    /** A server message quotes the statement, values included; the driver's and CAS's do not. */
    @Test
    public void aServerErrorIsQuotedByItsCodeAlone() {
        SQLException syntax =
                new SQLException(
                        "Syntax: before '  FROMM t WHERE ssn = '860101-1234567''", null, -493);
        SQLException cas = new SQLException("Communication error", null, -10003);

        assertEquals("SQLState=null errorCode=-493", LbLog.cause(syntax));
        assertEquals("SQLState=null errorCode=-10003 msg=Communication error", LbLog.cause(cas));
    }

    /** Under lbLogSql=raw the statement is in the log as issued, so its error's message is too. */
    @Test
    public void aServerErrorKeepsItsMessageWhereStatementsAreLoggedRaw() {
        SQLException syntax =
                new SQLException(
                        "Syntax: before '  FROMM t WHERE ssn = '860101-1234567''", null, -493);

        assertEquals(
                "SQLState=null errorCode=-493 msg=Syntax: before '  FROMM t WHERE ssn ="
                        + " '860101-1234567''",
                LbLog.cause(syntax, true));
        assertEquals("SQLState=null errorCode=-493", LbLog.cause(syntax, false));
    }

    @Test
    public void quotedDriverMessageIsCappedAndKeptToOneLine() {
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longMessage.append("0123456789");
        }

        String rendered = LbLog.shortMessage(longMessage + "\nsecond line");

        assertTrue(rendered.endsWith("..."));
        assertTrue(rendered.length() < 200);
        assertEquals(-1, rendered.indexOf("second line"));
    }

    @Test
    public void aNullOrMissingConnectionIdYieldsNoContext() {
        assertEquals(null, LbLog.conn(0L));
        assertEquals("conn#7", LbLog.conn(7L));
    }

    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<String>();

        @Override
        public void publish(final LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
