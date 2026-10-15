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

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class LoadBalanceConnectionPhysicalCommandDelegationTest {

    @Test
    public void assertGetTransactionIsolationDelegatesToMasterPhysical() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterGetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlyGetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlySetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger readOnlySetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger masterSetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlySetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlyGetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyGetWarningsCalls = new AtomicInteger();
        AtomicInteger masterClearWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyClearWarningsCalls = new AtomicInteger();

        Connection master =
                newPhysicalConnection(
                        7,
                        "master_schema",
                        masterGetIsolationCalls,
                        masterSetIsolationCalls,
                        masterSetLockTimeoutCalls,
                        masterSetSchemaCalls,
                        masterGetSchemaCalls,
                        masterGetWarningsCalls,
                        masterClearWarningsCalls);
        Connection readOnly =
                newPhysicalConnection(
                        11,
                        "readonly_schema",
                        readOnlyGetIsolationCalls,
                        readOnlySetIsolationCalls,
                        readOnlySetLockTimeoutCalls,
                        readOnlySetSchemaCalls,
                        readOnlyGetSchemaCalls,
                        readOnlyGetWarningsCalls,
                        readOnlyClearWarningsCalls);
        bindPhysicalConnections(connection, master, readOnly);

        int actual = connection.getTransactionIsolation();

        assertEquals(7, actual);
        assertEquals(1, masterGetIsolationCalls.get());
        assertEquals(0, readOnlyGetIsolationCalls.get());
    }

    @Test
    public void assertSetTransactionIsolationDelegatesToMasterPhysical() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterGetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlyGetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlySetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger readOnlySetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger masterSetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlySetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlyGetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyGetWarningsCalls = new AtomicInteger();
        AtomicInteger masterClearWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyClearWarningsCalls = new AtomicInteger();

        Connection master =
                newPhysicalConnection(
                        7,
                        "master_schema",
                        masterGetIsolationCalls,
                        masterSetIsolationCalls,
                        masterSetLockTimeoutCalls,
                        masterSetSchemaCalls,
                        masterGetSchemaCalls,
                        masterGetWarningsCalls,
                        masterClearWarningsCalls);
        Connection readOnly =
                newPhysicalConnection(
                        11,
                        "readonly_schema",
                        readOnlyGetIsolationCalls,
                        readOnlySetIsolationCalls,
                        readOnlySetLockTimeoutCalls,
                        readOnlySetSchemaCalls,
                        readOnlyGetSchemaCalls,
                        readOnlyGetWarningsCalls,
                        readOnlyClearWarningsCalls);
        bindPhysicalConnections(connection, master, readOnly);

        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        assertEquals(1, masterSetIsolationCalls.get());
        assertEquals(1, readOnlySetIsolationCalls.get());
    }

    @Test
    public void assertSetLockTimeoutDelegatesToMasterPhysical() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterGetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlyGetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlySetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger readOnlySetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger masterSetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlySetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlyGetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyGetWarningsCalls = new AtomicInteger();
        AtomicInteger masterClearWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyClearWarningsCalls = new AtomicInteger();

        Connection master =
                newPhysicalConnection(
                        7,
                        "master_schema",
                        masterGetIsolationCalls,
                        masterSetIsolationCalls,
                        masterSetLockTimeoutCalls,
                        masterSetSchemaCalls,
                        masterGetSchemaCalls,
                        masterGetWarningsCalls,
                        masterClearWarningsCalls);
        Connection readOnly =
                newPhysicalConnection(
                        11,
                        "readonly_schema",
                        readOnlyGetIsolationCalls,
                        readOnlySetIsolationCalls,
                        readOnlySetLockTimeoutCalls,
                        readOnlySetSchemaCalls,
                        readOnlyGetSchemaCalls,
                        readOnlyGetWarningsCalls,
                        readOnlyClearWarningsCalls);
        bindPhysicalConnections(connection, master, readOnly);

        connection.setLockTimeout(123);

        assertEquals(1, masterSetLockTimeoutCalls.get());
        assertEquals(1, readOnlySetLockTimeoutCalls.get());
    }

    @Test
    public void assertConnectionSchemaDelegatesToPhysicalConnection() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterGetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlyGetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlySetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger readOnlySetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger masterSetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlySetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlyGetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyGetWarningsCalls = new AtomicInteger();
        AtomicInteger masterClearWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyClearWarningsCalls = new AtomicInteger();

        Connection master =
                newPhysicalConnection(
                        7,
                        "master_schema",
                        masterGetIsolationCalls,
                        masterSetIsolationCalls,
                        masterSetLockTimeoutCalls,
                        masterSetSchemaCalls,
                        masterGetSchemaCalls,
                        masterGetWarningsCalls,
                        masterClearWarningsCalls);
        Connection readOnly =
                newPhysicalConnection(
                        11,
                        "readonly_schema",
                        readOnlyGetIsolationCalls,
                        readOnlySetIsolationCalls,
                        readOnlySetLockTimeoutCalls,
                        readOnlySetSchemaCalls,
                        readOnlyGetSchemaCalls,
                        readOnlyGetWarningsCalls,
                        readOnlyClearWarningsCalls);
        bindPhysicalConnections(connection, master, readOnly);

        connection.setSchema("app_schema");
        String actual = connection.getSchema();

        assertEquals("master_schema", actual);
        assertEquals(1, masterSetSchemaCalls.get());
        assertEquals(1, readOnlySetSchemaCalls.get());
        assertEquals(1, masterGetSchemaCalls.get());
        assertEquals(0, readOnlyGetSchemaCalls.get());
    }

    @Test
    public void assertConnectionWarningsDelegateToPhysicalConnection() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterGetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlyGetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlySetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger readOnlySetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger masterSetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlySetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlyGetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyGetWarningsCalls = new AtomicInteger();
        AtomicInteger masterClearWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyClearWarningsCalls = new AtomicInteger();

        Connection master =
                newPhysicalConnection(
                        7,
                        "master_schema",
                        masterGetIsolationCalls,
                        masterSetIsolationCalls,
                        masterSetLockTimeoutCalls,
                        masterSetSchemaCalls,
                        masterGetSchemaCalls,
                        masterGetWarningsCalls,
                        masterClearWarningsCalls);
        Connection readOnly =
                newPhysicalConnection(
                        11,
                        "readonly_schema",
                        readOnlyGetIsolationCalls,
                        readOnlySetIsolationCalls,
                        readOnlySetLockTimeoutCalls,
                        readOnlySetSchemaCalls,
                        readOnlyGetSchemaCalls,
                        readOnlyGetWarningsCalls,
                        readOnlyClearWarningsCalls);
        bindPhysicalConnections(connection, master, readOnly);

        SQLWarning warning = connection.getWarnings();
        connection.clearWarnings();

        assertEquals("master warning", warning.getMessage());
        assertEquals(1, masterGetWarningsCalls.get());
        assertEquals(0, readOnlyGetWarningsCalls.get());
        assertEquals(1, masterClearWarningsCalls.get());
        assertEquals(0, readOnlyClearWarningsCalls.get());
    }

    @Test
    public void assertConnectionHoldabilityDelegatesToPhysicalConnection() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterSetHoldabilityCalls = new AtomicInteger();
        AtomicInteger readOnlySetHoldabilityCalls = new AtomicInteger();
        Connection master =
                newPhysicalConnection(
                        7,
                        "master_schema",
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        masterSetHoldabilityCalls,
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger());
        Connection readOnly =
                newPhysicalConnection(
                        11,
                        "readonly_schema",
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        readOnlySetHoldabilityCalls,
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger());
        bindPhysicalConnections(connection, master, readOnly);

        connection.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);

        assertEquals(1, masterSetHoldabilityCalls.get());
        assertEquals(1, readOnlySetHoldabilityCalls.get());
    }

    @Test
    public void assertConnectionNetworkTimeoutDelegatesToPhysicalConnection() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterSetNetworkTimeoutCalls = new AtomicInteger();
        AtomicInteger readOnlySetNetworkTimeoutCalls = new AtomicInteger();
        AtomicInteger masterGetNetworkTimeoutCalls = new AtomicInteger();
        AtomicInteger readOnlyGetNetworkTimeoutCalls = new AtomicInteger();
        Connection master =
                newPhysicalConnection(
                        7,
                        "master_schema",
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        masterSetNetworkTimeoutCalls,
                        masterGetNetworkTimeoutCalls,
                        new AtomicInteger());
        Connection readOnly =
                newPhysicalConnection(
                        11,
                        "readonly_schema",
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        new AtomicInteger(),
                        readOnlySetNetworkTimeoutCalls,
                        readOnlyGetNetworkTimeoutCalls,
                        new AtomicInteger());
        bindPhysicalConnections(connection, master, readOnly);

        connection.setNetworkTimeout(null, 321);
        int actual = connection.getNetworkTimeout();

        assertEquals(321, actual);
        assertEquals(1, masterSetNetworkTimeoutCalls.get());
        assertEquals(1, readOnlySetNetworkTimeoutCalls.get());
        assertEquals(1, masterGetNetworkTimeoutCalls.get());
        assertEquals(0, readOnlyGetNetworkTimeoutCalls.get());
    }

    @Test
    public void assertCommandMetricsContainsActionableRouteFields() throws Exception {
        LoadBalanceConnection connection = createBoundConnectionWithMetrics();
        AtomicInteger masterGetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlyGetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetIsolationCalls = new AtomicInteger();
        AtomicInteger readOnlySetIsolationCalls = new AtomicInteger();
        AtomicInteger masterSetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger readOnlySetLockTimeoutCalls = new AtomicInteger();
        AtomicInteger masterSetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlySetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetSchemaCalls = new AtomicInteger();
        AtomicInteger readOnlyGetSchemaCalls = new AtomicInteger();
        AtomicInteger masterGetWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyGetWarningsCalls = new AtomicInteger();
        AtomicInteger masterClearWarningsCalls = new AtomicInteger();
        AtomicInteger readOnlyClearWarningsCalls = new AtomicInteger();

        Connection master =
                newPhysicalConnection(
                        7,
                        "master_schema",
                        masterGetIsolationCalls,
                        masterSetIsolationCalls,
                        masterSetLockTimeoutCalls,
                        masterSetSchemaCalls,
                        masterGetSchemaCalls,
                        masterGetWarningsCalls,
                        masterClearWarningsCalls);
        Connection readOnly =
                newPhysicalConnection(
                        11,
                        "readonly_schema",
                        readOnlyGetIsolationCalls,
                        readOnlySetIsolationCalls,
                        readOnlySetLockTimeoutCalls,
                        readOnlySetSchemaCalls,
                        readOnlyGetSchemaCalls,
                        readOnlyGetWarningsCalls,
                        readOnlyClearWarningsCalls);
        bindPhysicalConnections(connection, master, readOnly);

        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        String event = connection.getRuntimeMetrics().getEvents().get(0);
        assertTrue(event.contains("sqlType=COMMAND"));
        assertTrue(event.contains("command=setTransactionIsolation"));
        assertTrue(event.contains("routeTarget=TO_READ_WRITE"));
        assertTrue(event.contains("endpointId=rw:33000"));
        assertTrue(event.contains("fallbackReason=NONE"));
    }

    private static LoadBalanceConnection createBoundConnectionWithMetrics() throws Exception {
        LoadBalanceConnection result = createSessionConnection();
        result.getRuntimeMetrics().setEnabled(true);
        result.setConnectionManager(new SimpleEndpointConnManager());
        result.setSharedSelectorState(new SharedSelectorState());
        result.initSessionBindings(createTopologyWithSingleRo());
        return result;
    }

    private static LoadBalanceConnection createBoundConnection() throws Exception {
        LoadBalanceConnection result = createSessionConnection();
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        result.setConnectionManager(manager);
        result.setSharedSelectorState(new SharedSelectorState());
        result.initSessionBindings(createTopologyWithSingleRo());
        return result;
    }

    private static void bindPhysicalConnections(
            final LoadBalanceConnection connection,
            final Connection master,
            final Connection readOnly)
            throws Exception {
        SimpleEndpointConnManager manager =
                (SimpleEndpointConnManager) connection.getConnectionManager();
        manager.setPhysicalConnection(connection.getCurrentEp(SessionLeg.RW), master);
        manager.setPhysicalConnection(connection.getCurrentEp(SessionLeg.RO), readOnly);
    }

    private static LoadBalanceConnection createSessionConnection() {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        return new LoadBalanceConnection(LoadBalanceSettings.of(properties));
    }

    private static EndpointTopology createTopologyWithSingleRo() {
        List<Endpoint> roEndpoints = new ArrayList<Endpoint>();
        roEndpoints.add(new Endpoint("ro1", 33000));
        return new EndpointTopology(new Endpoint("rw", 33000), roEndpoints, null);
    }

    private static Connection newPhysicalConnection(
            final int isolationValue,
            final String schemaValue,
            final AtomicInteger getIsolationCalls,
            final AtomicInteger setIsolationCalls,
            final AtomicInteger setLockTimeoutCalls,
            final AtomicInteger setSchemaCalls,
            final AtomicInteger getSchemaCalls,
            final AtomicInteger getWarningsCalls,
            final AtomicInteger clearWarningsCalls) {
        return newPhysicalConnection(
                isolationValue,
                schemaValue,
                getIsolationCalls,
                setIsolationCalls,
                setLockTimeoutCalls,
                setSchemaCalls,
                getSchemaCalls,
                getWarningsCalls,
                clearWarningsCalls,
                new AtomicInteger(),
                new AtomicInteger(),
                new AtomicInteger(),
                new AtomicInteger());
    }

    private static Connection newPhysicalConnection(
            final int isolationValue,
            final String schemaValue,
            final AtomicInteger getIsolationCalls,
            final AtomicInteger setIsolationCalls,
            final AtomicInteger setLockTimeoutCalls,
            final AtomicInteger setSchemaCalls,
            final AtomicInteger getSchemaCalls,
            final AtomicInteger getWarningsCalls,
            final AtomicInteger clearWarningsCalls,
            final AtomicInteger setHoldabilityCalls,
            final AtomicInteger setNetworkTimeoutCalls,
            final AtomicInteger getNetworkTimeoutCalls,
            final AtomicInteger networkTimeoutValue) {
        return new FakePhysicalConnection() {
            @Override
            protected Object dispatch(final String name, final Object[] args) {
                if ("getTransactionIsolation".equals(name)) {
                    getIsolationCalls.incrementAndGet();
                    return Integer.valueOf(isolationValue);
                }
                if ("setTransactionIsolation".equals(name)) {
                    setIsolationCalls.incrementAndGet();
                    return null;
                }
                if ("setLockTimeout".equals(name)) {
                    setLockTimeoutCalls.incrementAndGet();
                    return null;
                }
                if ("setSchema".equals(name)) {
                    setSchemaCalls.incrementAndGet();
                    return null;
                }
                if ("getSchema".equals(name)) {
                    getSchemaCalls.incrementAndGet();
                    return schemaValue;
                }
                if ("getWarnings".equals(name)) {
                    getWarningsCalls.incrementAndGet();
                    return new SQLWarning("master warning");
                }
                if ("clearWarnings".equals(name)) {
                    clearWarningsCalls.incrementAndGet();
                    return null;
                }
                if ("setHoldability".equals(name)) {
                    setHoldabilityCalls.incrementAndGet();
                    return null;
                }
                if ("setNetworkTimeout".equals(name)) {
                    setNetworkTimeoutCalls.incrementAndGet();
                    networkTimeoutValue.set(((Integer) args[1]).intValue());
                    return null;
                }
                if ("getNetworkTimeout".equals(name)) {
                    getNetworkTimeoutCalls.incrementAndGet();
                    return Integer.valueOf(networkTimeoutValue.get());
                }
                return null;
            }
        };
    }
}
