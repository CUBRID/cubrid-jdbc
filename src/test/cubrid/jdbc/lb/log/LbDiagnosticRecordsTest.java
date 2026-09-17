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

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.failover.UnreachableEndpoints;
import cubrid.jdbc.lb.state.RecoveryBackoff;
import java.io.IOException;
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
 * The records that explain <em>why</em> a failover behaved the way it did: which endpoints were
 * excluded from candidate selection, and why a recovered endpoint was not picked up immediately.
 *
 * <p>Both states were previously invisible. An endpoint skipped during candidate selection looked
 * arbitrary, and a node that came back up but was not used for another few seconds looked like the
 * ladder ignoring a healthy broker.
 */
public class LbDiagnosticRecordsTest {

    private static final Endpoint EP = new Endpoint("diagnode", 34500);

    private Logger lbLogger;
    private CapturingHandler captured;

    @Before
    public void setUp() {
        LbLogDedup.resetForTests();
        UUnreachableHostList.getInstance().remove(EP.getId());
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
        UUnreachableHostList.getInstance().remove(EP.getId());
        LbLogDedup.resetForTests();
    }

    @Test
    public void enteringAndLeavingTheUnreachableSetAreBothRecorded() {
        UnreachableEndpoints filter = new UnreachableEndpoints();

        filter.markUnreachable(EP, communicationFailure());
        filter.markReachable(EP);

        List<String> records = allMatching("LB UNREACHABLE:");
        assertEquals(2, records.size());
        assertTrue(records.get(0), records.get(0).indexOf("entered the unreachable set") >= 0);
        assertTrue(
                "the record must say the endpoint will be skipped, which is what it explains: "
                        + records.get(0),
                records.get(0).indexOf("candidate selection will skip it") >= 0);
        assertTrue(records.get(1), records.get(1).indexOf("left the unreachable set") >= 0);
    }

    @Test
    public void aPoolMarkingTheSameNodeDownWritesOneLineNotOnePerConnection() {
        UnreachableEndpoints filter = new UnreachableEndpoints();

        for (int i = 0; i < 100; i++) {
            filter.markUnreachable(EP, communicationFailure());
        }

        assertEquals(1, allMatching("LB UNREACHABLE:").size());
    }

    @Test
    public void aFailureThatDoesNotMeanTheNodeIsDownDoesNotTouchTheSet() {
        UnreachableEndpoints filter = new UnreachableEndpoints();

        // A plain statement error: the broker answered, so it is reachable.
        filter.markUnreachable(EP, new SQLException("syntax error", "42000", -493));

        assertEquals(0, allMatching("LB UNREACHABLE:").size());
    }

    @Test
    public void beingHeldInsideTheProbeWindowIsRecordedWithTheRemainingWait() {
        RecoveryBackoff backoff = new RecoveryBackoff(5000L);
        long now = System.nanoTime();

        assertTrue("the first probe is always granted", backoff.tryClaimProbe(EP.getId(), now));
        assertTrue(
                "a second caller in the same window is held",
                !backoff.tryClaimProbe(EP.getId(), now));

        String held = onlyMatching("still inside its probe window");
        assertTrue(
                "the wait is what explains the delay before a recovered node is used: " + held,
                held.indexOf("excluded from candidates for another") >= 0);
    }

    @Test
    public void aReconnectClearsTheWindowAndSaysSo() {
        RecoveryBackoff backoff = new RecoveryBackoff(5000L);
        backoff.tryClaimProbe(EP.getId(), System.nanoTime());

        backoff.reset(EP.getId());

        assertTrue(onlyMatching("probe window cleared").indexOf("immediately eligible") >= 0);
    }

    @Test
    public void clearingAWindowThatWasNeverSetIsNotReported() {
        RecoveryBackoff backoff = new RecoveryBackoff(5000L);

        backoff.reset(EP.getId());

        assertEquals(0, allMatching("probe window cleared").size());
    }

    private static SQLException communicationFailure() {
        SQLException ex = new SQLException("cannot communicate", null, UErrorCode.ER_COMMUNICATION);
        ex.initCause(new IOException("connection refused"));
        return ex;
    }

    private String onlyMatching(final String needle) {
        List<String> found = allMatching(needle);
        assertEquals("expected exactly one '" + needle + "' record, got " + found, 1, found.size());
        return found.get(0);
    }

    private List<String> allMatching(final String needle) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < captured.records.size(); i++) {
            String message = captured.records.get(i).getMessage();
            if (message != null && message.indexOf(needle) >= 0) {
                out.add(message);
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
