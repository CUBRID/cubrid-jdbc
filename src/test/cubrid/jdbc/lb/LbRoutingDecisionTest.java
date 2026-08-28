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

import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.RuntimeMetrics;
import cubrid.jdbc.lb.state.SharedSelectorState;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

public final class LbRoutingDecisionTest {

    @Test
    public void nonTransactionSelectRoutesToReadOnly() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        executePrepared(connection, "SELECT * FROM t WHERE id = ?");
        String event = lastSqlEvent(connection.getRuntimeMetrics());

        assertTrue(event.contains("sqlType=READ"));
        assertTrue(event.contains("routeTarget=TO_READ_ONLY"));
        assertTrue(
                event.contains("endpointId=ro1:33000")
                        || event.contains("endpointId=ro2:33000")
                        || event.contains("endpointId=rw:33000"));
    }

    @Test
    public void transactionReadFallsBackToMaster() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        connection.setAutoCommit(false);
        executePrepared(connection, "SELECT * FROM t WHERE id = ?");
        String event = lastSqlEvent(connection.getRuntimeMetrics());

        assertTrue(event.contains("sqlType=READ"));
        assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        assertTrue(event.contains("endpointId=rw:33000"));
    }

    @Test
    public void hintToMasterForcesMasterRoute() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        executePrepared(connection, "SELECT /*+ TO_RW */  * FROM t WHERE id = ?");
        String event = lastSqlEvent(connection.getRuntimeMetrics());

        assertTrue(event.contains("sqlType=READ"));
        assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        assertTrue(event.contains("endpointId=rw:33000"));
    }

    @Test
    public void lobFunctionReadUsesMasterOnly() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        executePrepared(connection, "SELECT CLOB_TO_CHAR(doc) FROM t WHERE id = ?");
        String event = lastSqlEvent(connection.getRuntimeMetrics());

        assertTrue(event.contains("sqlType=UNKNOWN"));
        assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        assertTrue(event.contains("endpointId=rw:33000"));
    }

    @Test
    public void hintToSlaveRoutesReadToReadOnly() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        executePrepared(connection, "SELECT /*+ TO_RO */  * FROM t WHERE id = ?");
        String event = lastSqlEvent(connection.getRuntimeMetrics());

        assertTrue(event.contains("sqlType=READ"));
        assertTrue(event.contains("routeTarget=TO_READ_ONLY"));
        assertTrue(
                event.contains("endpointId=ro1:33000")
                        || event.contains("endpointId=ro2:33000")
                        || event.contains("endpointId=rw:33000"));
    }

    @Test
    public void writeStatementAlwaysUsesMaster() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        executePrepared(connection, "UPDATE t SET v = ? WHERE id = ?");
        String event = lastSqlEvent(connection.getRuntimeMetrics());

        assertTrue(event.contains("sqlType=WRITE"));
        assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        assertTrue(event.contains("endpointId=rw:33000"));
    }

    @Test
    public void transactionBoundaryReturnsReadToReadOnlyAfterCommit() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();

        executePrepared(connection, "SELECT * FROM t WHERE id = ?");
        String first = lastSqlEvent(connection.getRuntimeMetrics());
        assertTrue(first.contains("routeTarget=TO_READ_ONLY"));

        connection.setAutoCommit(false);
        executePrepared(connection, "SELECT * FROM t WHERE id = ?");
        String second = lastSqlEvent(connection.getRuntimeMetrics());
        assertTrue(second.contains("routeTarget=TO_READ_WRITE"));

        connection.commit();
        connection.setAutoCommit(true);
        executePrepared(connection, "SELECT * FROM t WHERE id = ?");
        String third = lastSqlEvent(connection.getRuntimeMetrics());
        assertTrue(third.contains("routeTarget=TO_READ_ONLY"));
    }

    @Test
    public void metadataCallsRouteToMasterCommandPath() throws Exception {
        BoundConnection bound = createBoundSessionConnectionWithPhysicals();
        DatabaseMetaData metaData = bound.connection.getMetaData();
        // Not getDriverMajorVersion(): that one answers from this driver's own build constant and
        // never touches an endpoint, so it is no longer representative of the command path. It
        // declares no throws clause, so it could not report a bind failure anyway.
        metaData.getDatabaseProductName();

        String commandEvent =
                findLastEventContaining(bound.connection.getRuntimeMetrics(), "sqlType=COMMAND");
        assertTrue(commandEvent.contains("command=Call: getDatabaseProductName"));
        assertTrue(commandEvent.contains("routeTarget=TO_READ_WRITE"));
        assertTrue(commandEvent.contains("endpointId=rw:33000"));
    }

    @Test
    public void mixedFlowEmitsReadMasterAndCommandMetrics() throws Exception {
        BoundConnection bound = createBoundSessionConnectionWithPhysicals();

        executePrepared(bound.connection, "SELECT * FROM t WHERE id = ?");
        executePrepared(bound.connection, "SELECT /*+ TO_RW */  * FROM t WHERE id = ?");
        bound.connection.getMetaData().getUserName();

        List<String> events = bound.connection.getRuntimeMetrics().getEvents();
        assertTrue(containsEvent(events, "sqlType=READ,routeTarget=TO_READ_ONLY"));
        assertTrue(containsEvent(events, "sqlType=READ,routeTarget=TO_READ_WRITE"));
        assertTrue(containsEvent(events, "sqlType=COMMAND,command=Call: getUserName"));
    }

    /**
     * The stateful hint trio ({@code AFTER_WRITE_TO_RW} / {@code STICKY_TO_RW} / {@code
     * UNSTICK_FROM_RW}) was removed in 2026-08-11. The tokens now parse as unrecognized, so a
     * statement carrying one routes by classification and leaves the session untouched — previously
     * one such statement pinned every later read to RW, and a pooled connection handed that pin to
     * the next borrower.
     */
    @Test
    public void removedStatefulHintsDoNotPinTheSession() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        executePrepared(connection, "SELECT /*+ AFTER_WRITE_TO_RW */ * FROM t WHERE id = ?");
        executePrepared(connection, "SELECT /*+ STICKY_TO_RW */ * FROM t WHERE id = ?");
        executePrepared(connection, "SELECT * FROM t WHERE id = ?");
        String event = lastSqlEvent(connection.getRuntimeMetrics());

        assertTrue(event.contains("sqlType=READ"));
        assertTrue(event.contains("routeTarget=TO_READ_ONLY"));
        assertTrue(
                event.contains("endpointId=ro1:33000") || event.contains("endpointId=ro2:33000"));
    }

    /**
     * Read-after-write is expressed per statement now: TO_RW binds that read, and only that one.
     */
    @Test
    public void toRwHintAppliesToItsOwnStatementOnly() throws Exception {
        LoadBalanceConnection connection = createBoundSessionConnection();
        executePrepared(connection, "SELECT /*+ TO_RW */ * FROM t WHERE id = ?");
        String hinted = lastSqlEvent(connection.getRuntimeMetrics());
        assertTrue(hinted.contains("routeTarget=TO_READ_WRITE"));
        assertTrue(hinted.contains("endpointId=rw:33000"));

        executePrepared(connection, "SELECT * FROM t WHERE id = ?");
        String plain = lastSqlEvent(connection.getRuntimeMetrics());
        assertTrue(plain.contains("routeTarget=TO_READ_ONLY"));
        assertTrue(
                plain.contains("endpointId=ro1:33000") || plain.contains("endpointId=ro2:33000"));
    }

    private static void executePrepared(final LoadBalanceConnection connection, final String sql)
            throws Exception {
        LBPreparedStatement statement = (LBPreparedStatement) connection.prepareStatement(sql);
        if (sql.startsWith("UPDATE")) {
            statement.setInt(1, 2);
            statement.setInt(2, 1);
            statement.executeUpdate();
            return;
        }
        if (sql.startsWith("INSERT")) {
            statement.setInt(1, 99001);
            statement.setInt(2, 1);
            statement.executeUpdate();
            return;
        }
        statement.setInt(1, 1);
        statement.executeQuery();
    }

    private static LoadBalanceConnection createBoundSessionConnection() throws Exception {
        return createBoundSessionConnectionWithPhysicals().connection;
    }

    private static BoundConnection createBoundSessionConnectionWithPhysicals() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");

        LoadBalanceConnection connection =
                new LoadBalanceConnection(LoadBalanceSettings.of(properties));
        connection.getRuntimeMetrics().setEnabled(true);
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        connection.setConnectionManager(manager);
        connection.setSharedSelectorState(new SharedSelectorState());
        connection.initSessionBindings(createTopology());
        manager.setPhysicalConnection(
                connection.getCurrentEp(SessionLeg.RW), newCommandConnectionProxy());
        manager.setPhysicalConnection(
                connection.getCurrentEp(SessionLeg.RO), newCommandConnectionProxy());
        return new BoundConnection(connection);
    }

    private static EndpointTopology createTopology() {
        List<Endpoint> ro = new ArrayList<Endpoint>();
        ro.add(new Endpoint("ro1", 33000));
        ro.add(new Endpoint("ro2", 33000));
        return new EndpointTopology(new Endpoint("rw", 33000), ro, null);
    }

    private static String lastSqlEvent(final RuntimeMetrics metrics) {
        List<String> events = metrics.getEvents();
        for (int i = events.size() - 1; i >= 0; i--) {
            String event = events.get(i);
            if (event.contains("sqlType=READ")
                    || event.contains("sqlType=WRITE")
                    || event.contains("sqlType=UNKNOWN")) {
                return event;
            }
        }
        throw new IllegalStateException("No SQL runtime event recorded");
    }

    private static String findLastEventContaining(
            final RuntimeMetrics metrics, final String needle) {
        List<String> events = metrics.getEvents();
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).contains(needle)) {
                return events.get(i);
            }
        }
        throw new IllegalStateException("No runtime event found for: " + needle);
    }

    private static boolean containsEvent(final List<String> events, final String partial) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).contains(partial)) {
                return true;
            }
        }
        return false;
    }

    private static Connection newCommandConnectionProxy() {
        final DatabaseMetaData metadata = newMetadataProxy();
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("getMetaData".equals(method.getName())) {
                                    return metadata;
                                }
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.FALSE;
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                if (rt.equals(Integer.TYPE)) {
                                    return Integer.valueOf(0);
                                }
                                if (rt.equals(Long.TYPE)) {
                                    return Long.valueOf(0L);
                                }
                                return null;
                            }
                        });
    }

    private static DatabaseMetaData newMetadataProxy() {
        return (DatabaseMetaData)
                Proxy.newProxyInstance(
                        DatabaseMetaData.class.getClassLoader(),
                        new Class[] {DatabaseMetaData.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                if ("getDriverMajorVersion".equals(method.getName())) {
                                    return Integer.valueOf(11);
                                }
                                if ("getDriverMinorVersion".equals(method.getName())) {
                                    return Integer.valueOf(4);
                                }
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                if (rt.equals(Integer.TYPE)) {
                                    return Integer.valueOf(0);
                                }
                                if (rt.equals(Long.TYPE)) {
                                    return Long.valueOf(0L);
                                }
                                if (rt.equals(String.class)) {
                                    return "";
                                }
                                return null;
                            }
                        });
    }

    private static final class BoundConnection {
        private final LoadBalanceConnection connection;

        private BoundConnection(final LoadBalanceConnection connection) {
            this.connection = connection;
        }
    }
}
