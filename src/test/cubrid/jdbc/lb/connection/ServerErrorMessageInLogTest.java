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

package cubrid.jdbc.lb.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.FakePhysicalConnection;
import cubrid.jdbc.lb.LbTestAccess;
import cubrid.jdbc.lb.LbTestSettings;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
 * The server's message for a statement it refuses quotes the statement, values included. Under the
 * default {@code lbLogSql=masked} the LB log names such an error by its code alone, as it keeps the
 * values out of the statement it logs. Under {@code lbLogSql=raw} the statement is logged as
 * issued, so the message is logged too: that mode is chosen to read the cause off the log.
 */
public final class ServerErrorMessageInLogTest {

    private static final String SQL = "SELECT * FROMM member WHERE ssn = '860101-1234567'";
    private static final String MESSAGE =
            "Syntax: before '  FROMM member WHERE ssn = '860101-1234567''";

    private Logger lbLogger;
    private final List<String> logged = new ArrayList<String>();
    private Handler capturing;

    @Before
    public void setUp() {
        lbLogger = Logger.getLogger("cubrid.jdbc.lb");
        lbLogger.setLevel(Level.ALL);
        lbLogger.setUseParentHandlers(false);
        capturing =
                new Handler() {
                    public void publish(final LogRecord record) {
                        logged.add(record.getMessage());
                    }

                    public void flush() {}

                    public void close() {}
                };
        lbLogger.addHandler(capturing);
    }

    @After
    public void tearDown() {
        lbLogger.removeHandler(capturing);
        lbLogger.setLevel(null);
        lbLogger.setUseParentHandlers(true);
    }

    /** The record of the refused statement: why it was not failed over. */
    private String refusalLoggedWith(final String option) throws SQLException {
        LoadBalanceSettings settings =
                LbTestSettings.fromUrl(
                        "jdbc:cubrid:loadbalance://semnode1:33000:33001,semnode2:33000:33001"
                                + "/testdb:::?readWeight=slave:1"
                                + option);
        LoadBalanceConnection conn = LbTestAccess.newLoadBalanceConnection(settings);
        LbTestAccess.setConnectionManager(
                conn,
                ConnectionTestAccess.newSessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        new Properties(),
                        settings,
                        new JdbcConnectionFactory() {
                            public Connection getConnection(final String url, final Properties i) {
                                return new RefusingLeg();
                            }
                        }));
        LbTestAccess.setSharedSelectorState(conn, new SharedSelectorState());
        LbTestAccess.initSessionBindings(conn, settings.buildEndpointTopology());
        try {
            conn.createStatement().executeQuery(SQL);
            fail("the server refuses the statement");
        } catch (SQLException refused) {
            assertEquals(-493, refused.getErrorCode());
        } finally {
            conn.close();
        }
        for (String each : logged) {
            if (each.indexOf("LB RETRY") >= 0) {
                return each;
            }
        }
        throw new AssertionError("no LB RETRY record in " + logged);
    }

    @Test
    public void rawLoggingKeepsTheServersMessage() throws SQLException {
        String record = refusalLoggedWith("&lbLogSql=raw");

        assertTrue(record, record.endsWith("errorCode=-493 msg=" + MESSAGE));
    }

    @Test
    public void maskedLoggingNamesTheErrorByItsCodeAlone() throws SQLException {
        String record = refusalLoggedWith("");

        assertTrue(record, record.endsWith("errorCode=-493"));
    }

    @Test
    public void loggingNoSqlNamesTheErrorByItsCodeAlone() throws SQLException {
        String record = refusalLoggedWith("&lbLogSql=off");

        assertTrue(record, record.endsWith("errorCode=-493"));
    }

    /** A leg whose statements the server refuses with a syntax error. */
    private static final class RefusingLeg extends FakePhysicalConnection {
        private boolean closed;

        @Override
        protected Object dispatch(final String name, final Object[] args) {
            if ("isClosed".equals(name)) {
                return Boolean.valueOf(closed);
            }
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("getAutoCommit".equals(name)) {
                return Boolean.TRUE;
            }
            if ("createStatement".equals(name)) {
                return Proxy.newProxyInstance(
                        Statement.class.getClassLoader(),
                        new Class<?>[] {Statement.class},
                        new InvocationHandler() {
                            public Object invoke(final Object p, final Method m, final Object[] a)
                                    throws SQLException {
                                if (m.getName().startsWith("execute")) {
                                    throw new SQLException(MESSAGE, null, -493);
                                }
                                if ("getConnection".equals(m.getName())) {
                                    return RefusingLeg.this;
                                }
                                Class<?> returns = m.getReturnType();
                                if (returns == Boolean.TYPE) {
                                    return Boolean.FALSE;
                                }
                                return returns == Integer.TYPE ? Integer.valueOf(0) : null;
                            }
                        });
            }
            return null;
        }
    }
}
