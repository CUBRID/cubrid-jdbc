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

import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A distributed read cannot honour REPEATABLE READ or SERIALIZABLE: with autocommit on, the read
 * runs as its own transaction on the read node, so the level holds within one node only. LB accepts
 * any level and never routes on it, which used to leave this entirely unsaid — the one record that
 * answers "REPEATABLE READ is set, why do the values differ" (LB-Pending-Issues ISSUE-1).
 *
 * <p>It is a warning, not a refusal: the combination is legitimate for single-statement reads.
 */
public class LbIsolationWarningTest {

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
    public void aDistributedReadUnderRepeatableReadIsRecordedOnce() throws SQLException {
        LoadBalanceConnection conn = session();
        conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

        conn.createStatement().executeQuery("SELECT 1");
        conn.createStatement().executeQuery("SELECT 2");

        List<String> lines = matching("LB ISOLATION:");
        assertEquals("one record per session, not per statement", 1, lines.size());
        String line = lines.get(0);
        assertTrue(line, line.indexOf("isolation=REPEATABLE_READ") >= 0);
        assertTrue("the read node must be named: " + line, line.indexOf("ronode:34001") >= 0);
        assertTrue(
                "the record must say what distribution assumes: " + line,
                line.indexOf("READ COMMITTED") >= 0);
        assertTrue("the way out must be named: " + line, line.indexOf("TO_RW") >= 0);
        conn.close();
    }

    @Test
    public void serializableIsRecordedTooAndNamedAsSuch() throws SQLException {
        LoadBalanceConnection conn = session();
        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        conn.createStatement().executeQuery("SELECT 1");

        List<String> lines = matching("LB ISOLATION:");
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).indexOf("isolation=SERIALIZABLE") >= 0);
        conn.close();
    }

    @Test
    public void readCommittedIsSilentBecauseDistributionAssumesIt() throws SQLException {
        LoadBalanceConnection conn = session();
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        conn.createStatement().executeQuery("SELECT 1");

        assertTrue(matching("LB ISOLATION:").isEmpty());
        conn.close();
    }

    /* ===== harness ===== */

    private static LoadBalanceConnection session() throws SQLException {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings settings = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info) {
                        return connectionProxy();
                    }
                };

        LoadBalanceConnection conn = new LoadBalanceConnection(settings);
        conn.setConnectionManager(
                new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), settings, factory));
        conn.setSharedSelectorState(new SharedSelectorState());
        conn.initSessionBindings(
                new EndpointTopology(RW, Arrays.asList(RO), Collections.<Endpoint>emptyList()));

        return conn;
    }

    private static Connection connectionProxy() {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.FALSE;
                                }
                                if ("createStatement".equals(method.getName())) {
                                    return statementProxy();
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                if (rt.equals(Integer.TYPE)) {
                                    return Integer.valueOf(0);
                                }

                                return null;
                            }
                        });
    }

    private static Object statementProxy() {
        return Proxy.newProxyInstance(
                java.sql.Statement.class.getClassLoader(),
                new Class[] {java.sql.Statement.class},
                new InvocationHandler() {
                    public Object invoke(
                            final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        Class<?> rt = method.getReturnType();
                        if (rt.equals(Boolean.TYPE)) {
                            return Boolean.FALSE;
                        }
                        if (rt.equals(Integer.TYPE)) {
                            return Integer.valueOf(0);
                        }

                        return null;
                    }
                });
    }

    private List<String> matching(final String needle) {
        List<String> found = new ArrayList<String>();
        for (LogRecord record : captured.records) {
            String message = record.getMessage();
            if (message != null && message.indexOf(needle) >= 0) {
                found.add(message);
            }
        }

        return found;
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
