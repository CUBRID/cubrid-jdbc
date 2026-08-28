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

import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The configuration declaration: what cluster this JVM talks to, how the URL's shorthand resolved
 * into roles, what the read-weight target is, and which behaviour settings are in force.
 *
 * <p>Each of the four was previously unavailable from the log alone. A correct {@code readWeight}
 * in particular left no trace — it surfaced only as a warning when it was <em>invalid</em> — so an
 * observed distribution had nothing to be compared against.
 */
public class LbTopologyLogTest {

    private static final String URL =
            "jdbc:cubrid:loadbalance://192.168.2.196:30000:33000,192.168.2.197:30000"
                    + ";replica=192.168.2.195:36000/tdb:dba:secret123:?"
                    + "readWeight=master:1,slave:2,replica:1";

    private Logger lbLogger;
    private CapturingHandler captured;

    @Before
    public void setUp() {
        LbTopologyLog.resetForTests();
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
        LbTopologyLog.resetForTests();
    }

    @Test
    public void theUrlIsRecordedAsWrittenButWithThePasswordMasked() throws SQLException {
        declare(URL);

        String line = onlyMatching("LB TOPOLOGY: url=");
        assertTrue(line, line.indexOf("loadbalance://192.168.2.196:30000:33000") >= 0);
        assertTrue(
                "the options as given must survive: " + line,
                line.indexOf("readWeight=master:1,slave:2,replica:1") >= 0);
        assertEquals(
                "the password must never reach the log: " + line, -1, line.indexOf("secret123"));
    }

    @Test
    public void theShorthandIsResolvedIntoRolesWithBothLegsPerNode() throws SQLException {
        declare(URL);

        String line = onlyMatching("LB TOPOLOGY: db=");
        assertTrue(line, line.indexOf("db=tdb") >= 0);
        // A slave host serves writes on one port and reads on another; naming only one is
        // ambiguous.
        assertTrue(
                "master needs both legs: " + line,
                line.indexOf("master=192.168.2.196:30000(ro 192.168.2.196:33000)") >= 0);
        assertTrue(
                "slave needs both legs: " + line,
                line.indexOf("slave=[192.168.2.197:30000(ro 192.168.2.197:33000)]") >= 0);
        assertTrue(
                "replica reads through its SO port: " + line,
                line.indexOf("replica=[192.168.2.195:36000]") >= 0);
    }

    @Test
    public void theReadWeightTargetIsRecordedEvenWhenItIsValid() throws SQLException {
        declare(URL);

        String line = onlyMatching("readWeight target");
        assertTrue(
                "without the target there is nothing to compare an observed distribution to: "
                        + line,
                line.indexOf("master:1 slave:2 replica:1 (total 4)") >= 0);
    }

    @Test
    public void theSettingsInForceAreRecordedNotOnlyTheIgnoredOnes() throws SQLException {
        declare(URL);

        String line = onlyMatching("distributionMode=");
        // "failover did not happen" must be separable from "failover is switched off".
        assertTrue(line, line.indexOf("rtFailover=on(retryOnce, max 1)") >= 0);
        assertTrue(line, line.indexOf("readFailback=on(") >= 0);
        assertTrue(line, line.indexOf("writeFailback=on(") >= 0);
        assertTrue(line, line.indexOf("sqlClassifyCache=8192") >= 0);
        assertTrue(line, line.indexOf("metrics=off") >= 0);
    }

    @Test
    public void aPoolOpeningManyConnectionsDeclaresTheConfigurationOnce() throws SQLException {
        for (int i = 0; i < 50; i++) {
            declare(URL);
        }

        assertEquals(3, allMatching("LB TOPOLOGY:").size());
    }

    @Test
    public void aSecondClusterInTheSameJvmIsDeclaredSeparately() throws SQLException {
        declare(URL);
        declare(
                "jdbc:cubrid:loadbalance://10.0.0.1:30000:33000,10.0.0.2:30000/other:dba:pw:?"
                        + "readWeight=master:1,slave:1");

        assertEquals(
                "two DataSources on different clusters both need declaring",
                6,
                allMatching("LB TOPOLOGY:").size());
        assertEquals(1, allMatching("db=tdb").size());
        assertEquals(1, allMatching("db=other").size());
    }

    private static void declare(final String url) throws SQLException {
        LbTopologyLog.declare(
                url,
                new Properties(),
                LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url)));
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
