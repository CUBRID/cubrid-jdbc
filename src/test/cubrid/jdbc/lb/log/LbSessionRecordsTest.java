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
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The session-level records that make the other LB logs interpretable: where a session actually
 * bound, and when its reads move on and off the RW connection.
 *
 * <p>Before these, only <em>abnormal</em> placements were written down. A session that bound as
 * configured said nothing — leaving no baseline to check a pool's distribution against — and reads
 * dropping onto the RW connection was entirely silent, which is what let a broker with exhausted
 * CAS slots be investigated as a routing defect.
 */
public class LbSessionRecordsTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";

    private static final Endpoint RW = new Endpoint("rwnode", 34000);
    private static final Endpoint RO = new Endpoint("ronode", 34001);

    private Logger lbLogger;
    private CapturingHandler captured;

    @Before
    public void setUp() {
        LbLogDedup.resetForTests();
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
        LbLogDedup.resetForTests();
    }

    @Test
    public void aNormalBindIsRecordedSoThePoolDistributionHasABaseline() throws SQLException {
        EndpointTopology topo =
                new EndpointTopology(RW, Arrays.asList(RO), Collections.<Endpoint>emptyList());
        SessionPhysicalConnManager mgr = newManager(new AtomicBoolean(true));
        mgr.setLogContext("conn#3");

        mgr.bindSession(RW, RO, topo);

        String bind = onlyMatching("LB BIND: ");
        assertTrue(bind, bind.indexOf("rw=rwnode:34000") >= 0);
        assertTrue(bind, bind.indexOf("read=ronode:34001") >= 0);
        assertTrue(bind, bind.indexOf("readHome=ronode:34001") >= 0);
        assertTrue(bind, bind.indexOf("roOnRw=false") >= 0);
        assertEquals("conn#3", contextOf("LB BIND: "));
    }

    @Test
    public void readsFallingOntoTheRwConnectionAtBindTimeAreNamedNotSilent() throws SQLException {
        EndpointTopology topo =
                new EndpointTopology(RW, Arrays.asList(RO), Collections.<Endpoint>emptyList());
        SessionPhysicalConnManager mgr = newManager(new AtomicBoolean(false));

        mgr.bindSession(RW, RO, topo);

        String fallback = onlyMatching("LB READ ON RW:");
        assertTrue(fallback, fallback.indexOf("reads moved onto the RW connection") >= 0);
        assertTrue(fallback, fallback.indexOf("rwnode:34000") >= 0);
        assertTrue(
                "the record must say this is the fallback rung, or it reads as a routing defect: "
                        + fallback,
                fallback.indexOf("not a routing decision") >= 0);

        // The binding record reports the same state, so the two agree.
        assertTrue(onlyMatching("LB BIND: ").indexOf("roOnRw=true") >= 0);
    }

    @Test
    public void leavingTheFallbackRungIsRecordedToo() throws SQLException {
        EndpointTopology topo =
                new EndpointTopology(RW, Arrays.asList(RO), Collections.<Endpoint>emptyList());
        AtomicBoolean roUp = new AtomicBoolean(false);
        SessionPhysicalConnManager mgr = newManager(roUp);

        mgr.bindSession(RW, RO, topo);
        roUp.set(true);
        mgr.restoreRoIfRecovered();

        List<String> transitions = allMatching("LB READ ON RW:");
        assertEquals(2, transitions.size());
        assertTrue(transitions.get(0).indexOf("reads moved onto") >= 0);
        assertTrue(transitions.get(1).indexOf("reads left the fallback rung") >= 0);
        assertTrue(
                "readWeight distribution resuming is the point of the exit record: "
                        + transitions.get(1),
                transitions.get(1).indexOf("readWeight distribution resumes") >= 0);
    }

    @Test
    public void tearingASessionDownIsNotReportedAsLeavingTheFallbackRung() throws SQLException {
        EndpointTopology topo =
                new EndpointTopology(RW, Arrays.asList(RO), Collections.<Endpoint>emptyList());
        SessionPhysicalConnManager mgr = newManager(new AtomicBoolean(false));

        mgr.bindSession(RW, RO, topo);
        int afterBind = allMatching("LB READ ON RW:").size();
        mgr.releaseBindings();

        assertEquals(
                "a close is not a transition; there is no bound session to attribute one to",
                afterBind,
                allMatching("LB READ ON RW:").size());
    }

    private static SessionPhysicalConnManager newManager(final AtomicBoolean roUp) {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        if (url.contains(RO.getHost() + ":") && !roUp.get()) {
                            throw new SQLException("RO down", null, UErrorCode.ER_COMMUNICATION);
                        }
                        return openConnectionProxy();
                    }
                };

        return new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), config, factory);
    }

    private static Connection openConnectionProxy() {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args) {
                                String name = method.getName();
                                if ("isClosed".equals(name)) {
                                    return Boolean.FALSE;
                                }
                                if ("getAutoCommit".equals(name)) {
                                    return Boolean.TRUE;
                                }
                                if ("hashCode".equals(name)) {
                                    return Integer.valueOf(System.identityHashCode(proxy));
                                }
                                if ("equals".equals(name)) {
                                    return Boolean.valueOf(proxy == args[0]);
                                }
                                if ("toString".equals(name)) {
                                    return "conn-proxy";
                                }
                                return null;
                            }
                        });
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

    private String contextOf(final String needle) {
        for (int i = 0; i < captured.records.size(); i++) {
            LogRecord record = captured.records.get(i);
            String message = record.getMessage();
            if (message != null && message.indexOf(needle) >= 0) {
                Object[] params = record.getParameters();
                return params == null || params.length == 0 ? null : String.valueOf(params[0]);
            }
        }
        return null;
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
