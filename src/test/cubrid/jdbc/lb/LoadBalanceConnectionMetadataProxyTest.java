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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.CUBRIDJDBCErrorCode;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.route.Router;
import cubrid.jdbc.lb.state.SharedSelectorState;
import cubrid.jdbc.lb.statement.LBStatement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class LoadBalanceConnectionMetadataProxyTest {

    /**
     * Was {@code assertGetMetaDataReturnsProxyInstance}. A dynamic proxy implements interfaces
     * only, so {@code (CUBRIDDatabaseMetaData) conn.getMetaData()} could never succeed against one.
     * The metadata is now a real subclass so that cast works unmodified, which is the property
     * worth pinning here.
     */
    @Test
    public void assertGetMetaDataReturnsVendorMetaDataSubclass() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterMetaDataGetUrlCalls = new AtomicInteger();
        AtomicInteger readOnlyMetaDataGetUrlCalls = new AtomicInteger();
        DatabaseMetaData masterMetaData =
                newMetaDataProxy("jdbc:cubrid:master", masterMetaDataGetUrlCalls);
        DatabaseMetaData readOnlyMetaData =
                newMetaDataProxy("jdbc:cubrid:readonly", readOnlyMetaDataGetUrlCalls);
        bindPhysicalConnections(connection, masterMetaData, readOnlyMetaData);

        DatabaseMetaData actual = connection.getMetaData();

        assertFalse(Proxy.isProxyClass(actual.getClass()));
        assertTrue(actual instanceof cubrid.jdbc.driver.CUBRIDDatabaseMetaData);
        assertSame(connection, actual.getConnection());
    }

    @Test
    public void assertMetaDataDefaultDelegatesToMasterPhysical() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterMetaDataGetUrlCalls = new AtomicInteger();
        AtomicInteger readOnlyMetaDataGetUrlCalls = new AtomicInteger();
        DatabaseMetaData masterMetaData =
                newMetaDataProxy("jdbc:cubrid:master", masterMetaDataGetUrlCalls);
        DatabaseMetaData readOnlyMetaData =
                newMetaDataProxy("jdbc:cubrid:readonly", readOnlyMetaDataGetUrlCalls);
        bindPhysicalConnections(connection, masterMetaData, readOnlyMetaData);

        String actual = connection.getMetaData().getURL();

        assertEquals("jdbc:cubrid:master", actual);
        assertEquals(1, masterMetaDataGetUrlCalls.get());
        assertEquals(0, readOnlyMetaDataGetUrlCalls.get());
    }

    @Test
    public void assertGetDatabaseProductVersionDelegatesToMasterMetaData() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterMetaDataGetUrlCalls = new AtomicInteger();
        AtomicInteger readOnlyMetaDataGetUrlCalls = new AtomicInteger();
        AtomicInteger masterProductVersionCalls = new AtomicInteger();
        AtomicInteger readOnlyProductVersionCalls = new AtomicInteger();
        AtomicInteger masterGetTablesCalls = new AtomicInteger();
        AtomicInteger readOnlyGetTablesCalls = new AtomicInteger();
        DatabaseMetaData masterMetaData =
                newMetaDataProxy(
                        "jdbc:cubrid:master",
                        masterMetaDataGetUrlCalls,
                        masterProductVersionCalls,
                        masterGetTablesCalls);
        DatabaseMetaData readOnlyMetaData =
                newMetaDataProxy(
                        "jdbc:cubrid:readonly",
                        readOnlyMetaDataGetUrlCalls,
                        readOnlyProductVersionCalls,
                        readOnlyGetTablesCalls);
        bindPhysicalConnections(connection, masterMetaData, readOnlyMetaData);

        String actual = connection.getMetaData().getDatabaseProductVersion();

        assertEquals("rw-product-version", actual);
        assertEquals(1, masterProductVersionCalls.get());
        assertEquals(0, readOnlyProductVersionCalls.get());
    }

    @Test
    public void assertGetTablesDelegatesToMasterMetaData() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterMetaDataGetUrlCalls = new AtomicInteger();
        AtomicInteger readOnlyMetaDataGetUrlCalls = new AtomicInteger();
        AtomicInteger masterProductVersionCalls = new AtomicInteger();
        AtomicInteger readOnlyProductVersionCalls = new AtomicInteger();
        AtomicInteger masterGetTablesCalls = new AtomicInteger();
        AtomicInteger readOnlyGetTablesCalls = new AtomicInteger();
        DatabaseMetaData masterMetaData =
                newMetaDataProxy(
                        "jdbc:cubrid:master",
                        masterMetaDataGetUrlCalls,
                        masterProductVersionCalls,
                        masterGetTablesCalls);
        DatabaseMetaData readOnlyMetaData =
                newMetaDataProxy(
                        "jdbc:cubrid:readonly",
                        readOnlyMetaDataGetUrlCalls,
                        readOnlyProductVersionCalls,
                        readOnlyGetTablesCalls);
        bindPhysicalConnections(connection, masterMetaData, readOnlyMetaData);

        connection.getMetaData().getTables(null, null, "%", null);

        assertEquals(1, masterGetTablesCalls.get());
        assertEquals(0, readOnlyGetTablesCalls.get());
    }

    @Test
    public void assertGetDatabaseMajorVersionDelegatesToMasterMetaData() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterMetaDataGetUrlCalls = new AtomicInteger();
        AtomicInteger readOnlyMetaDataGetUrlCalls = new AtomicInteger();
        AtomicInteger masterProductVersionCalls = new AtomicInteger();
        AtomicInteger readOnlyProductVersionCalls = new AtomicInteger();
        AtomicInteger masterGetTablesCalls = new AtomicInteger();
        AtomicInteger readOnlyGetTablesCalls = new AtomicInteger();
        AtomicInteger masterMajorVersionCalls = new AtomicInteger();
        AtomicInteger readOnlyMajorVersionCalls = new AtomicInteger();
        AtomicInteger masterMinorVersionCalls = new AtomicInteger();
        AtomicInteger readOnlyMinorVersionCalls = new AtomicInteger();
        DatabaseMetaData masterMetaData =
                newMetaDataProxy(
                        "jdbc:cubrid:master",
                        masterMetaDataGetUrlCalls,
                        masterProductVersionCalls,
                        masterGetTablesCalls,
                        masterMajorVersionCalls,
                        masterMinorVersionCalls);
        DatabaseMetaData readOnlyMetaData =
                newMetaDataProxy(
                        "jdbc:cubrid:readonly",
                        readOnlyMetaDataGetUrlCalls,
                        readOnlyProductVersionCalls,
                        readOnlyGetTablesCalls,
                        readOnlyMajorVersionCalls,
                        readOnlyMinorVersionCalls);
        bindPhysicalConnections(connection, masterMetaData, readOnlyMetaData);

        int actual = connection.getMetaData().getDatabaseMajorVersion();

        assertEquals(10, actual);
        assertEquals(1, masterMajorVersionCalls.get());
        assertEquals(0, readOnlyMajorVersionCalls.get());
    }

    @Test
    public void assertGetDatabaseMinorVersionDelegatesToMasterMetaData() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterMetaDataGetUrlCalls = new AtomicInteger();
        AtomicInteger readOnlyMetaDataGetUrlCalls = new AtomicInteger();
        AtomicInteger masterProductVersionCalls = new AtomicInteger();
        AtomicInteger readOnlyProductVersionCalls = new AtomicInteger();
        AtomicInteger masterGetTablesCalls = new AtomicInteger();
        AtomicInteger readOnlyGetTablesCalls = new AtomicInteger();
        AtomicInteger masterMajorVersionCalls = new AtomicInteger();
        AtomicInteger readOnlyMajorVersionCalls = new AtomicInteger();
        AtomicInteger masterMinorVersionCalls = new AtomicInteger();
        AtomicInteger readOnlyMinorVersionCalls = new AtomicInteger();
        DatabaseMetaData masterMetaData =
                newMetaDataProxy(
                        "jdbc:cubrid:master",
                        masterMetaDataGetUrlCalls,
                        masterProductVersionCalls,
                        masterGetTablesCalls,
                        masterMajorVersionCalls,
                        masterMinorVersionCalls);
        DatabaseMetaData readOnlyMetaData =
                newMetaDataProxy(
                        "jdbc:cubrid:readonly",
                        readOnlyMetaDataGetUrlCalls,
                        readOnlyProductVersionCalls,
                        readOnlyGetTablesCalls,
                        readOnlyMajorVersionCalls,
                        readOnlyMinorVersionCalls);
        bindPhysicalConnections(connection, masterMetaData, readOnlyMetaData);

        int actual = connection.getMetaData().getDatabaseMinorVersion();

        assertEquals(1, actual);
        assertEquals(1, masterMinorVersionCalls.get());
        assertEquals(0, readOnlyMinorVersionCalls.get());
    }

    @Test
    public void assertGetSuperTablesDelegatesToMasterMetaData() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterGetSuperTablesCalls = new AtomicInteger();
        AtomicInteger readOnlyGetSuperTablesCalls = new AtomicInteger();
        DatabaseMetaData masterMetaData =
                newMetaDataProxyWithSchemaCalls(
                        "jdbc:cubrid:master", masterGetSuperTablesCalls, null, null);
        DatabaseMetaData readOnlyMetaData =
                newMetaDataProxyWithSchemaCalls(
                        "jdbc:cubrid:readonly", readOnlyGetSuperTablesCalls, null, null);
        bindPhysicalConnections(connection, masterMetaData, readOnlyMetaData);

        connection.getMetaData().getSuperTables(null, null, "%");

        assertEquals(1, masterGetSuperTablesCalls.get());
        assertEquals(0, readOnlyGetSuperTablesCalls.get());
    }

    @Test
    public void assertGetIndexInfoDelegatesToMasterMetaData() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterGetIndexInfoCalls = new AtomicInteger();
        AtomicInteger readOnlyGetIndexInfoCalls = new AtomicInteger();
        DatabaseMetaData masterMetaData =
                newMetaDataProxyWithSchemaCalls(
                        "jdbc:cubrid:master", null, masterGetIndexInfoCalls, null);
        DatabaseMetaData readOnlyMetaData =
                newMetaDataProxyWithSchemaCalls(
                        "jdbc:cubrid:readonly", null, readOnlyGetIndexInfoCalls, null);
        bindPhysicalConnections(connection, masterMetaData, readOnlyMetaData);

        connection.getMetaData().getIndexInfo(null, null, "t_order", false, false);

        assertEquals(1, masterGetIndexInfoCalls.get());
        assertEquals(0, readOnlyGetIndexInfoCalls.get());
    }

    @Test
    public void assertGetBestRowIdentifierDelegatesToMasterMetaData() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterBestRowIdentifierCalls = new AtomicInteger();
        AtomicInteger readOnlyBestRowIdentifierCalls = new AtomicInteger();
        DatabaseMetaData masterMetaData =
                newMetaDataProxyWithSchemaCalls(
                        "jdbc:cubrid:master", null, null, masterBestRowIdentifierCalls);
        DatabaseMetaData readOnlyMetaData =
                newMetaDataProxyWithSchemaCalls(
                        "jdbc:cubrid:readonly", null, null, readOnlyBestRowIdentifierCalls);
        bindPhysicalConnections(connection, masterMetaData, readOnlyMetaData);

        connection
                .getMetaData()
                .getBestRowIdentifier(
                        null, null, "t_order", DatabaseMetaData.bestRowNotPseudo, false);

        assertEquals(1, masterBestRowIdentifierCalls.get());
        assertEquals(0, readOnlyBestRowIdentifierCalls.get());
    }

    @Test
    public void assertVersionMetaDataMethodsDelegateToMasterMetaData() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger masterProductVersionCalls = new AtomicInteger();
        AtomicInteger readOnlyProductVersionCalls = new AtomicInteger();
        AtomicInteger masterMajorVersionCalls = new AtomicInteger();
        AtomicInteger readOnlyMajorVersionCalls = new AtomicInteger();
        AtomicInteger masterMinorVersionCalls = new AtomicInteger();
        AtomicInteger readOnlyMinorVersionCalls = new AtomicInteger();
        DatabaseMetaData masterMetaData =
                newMetaDataProxy(
                        "jdbc:cubrid:master",
                        new AtomicInteger(),
                        masterProductVersionCalls,
                        new AtomicInteger(),
                        masterMajorVersionCalls,
                        masterMinorVersionCalls);
        DatabaseMetaData readOnlyMetaData =
                newMetaDataProxy(
                        "jdbc:cubrid:readonly",
                        new AtomicInteger(),
                        readOnlyProductVersionCalls,
                        new AtomicInteger(),
                        readOnlyMajorVersionCalls,
                        readOnlyMinorVersionCalls);
        bindPhysicalConnections(connection, masterMetaData, readOnlyMetaData);

        connection.getMetaData().getDatabaseProductVersion();
        connection.getMetaData().getDatabaseMajorVersion();
        connection.getMetaData().getDatabaseMinorVersion();

        assertEquals(1, masterProductVersionCalls.get());
        assertEquals(1, masterMajorVersionCalls.get());
        assertEquals(1, masterMinorVersionCalls.get());
        assertEquals(0, readOnlyProductVersionCalls.get());
        assertEquals(0, readOnlyMajorVersionCalls.get());
        assertEquals(0, readOnlyMinorVersionCalls.get());
    }

    // DatabaseMetaData.getConnection() must return the logical LB connection, not the
    // physical RW — otherwise callers can close()/setAutoCommit() the physical connection directly.
    @Test
    public void assertMetaDataGetConnectionReturnsLogicalConnection() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        DatabaseMetaData master = newMetaDataProxy("jdbc:cubrid:master", new AtomicInteger());
        DatabaseMetaData readOnly = newMetaDataProxy("jdbc:cubrid:readonly", new AtomicInteger());
        bindPhysicalConnections(connection, master, readOnly);

        assertSame(connection, connection.getMetaData().getConnection());
    }

    // The proxy answers Wrapper itself. Forwarding unwrap() would hand out the physical
    // CUBRIDDatabaseMetaData, which both defeats the getConnection() interception above and exposes
    // the SHARD-only extensions that mean nothing for an LB session.
    @Test
    public void assertMetaDataUnwrapReturnsProxyForImplementedInterface() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        DatabaseMetaData master = newMetaDataProxy("jdbc:cubrid:master", new AtomicInteger());
        DatabaseMetaData readOnly = newMetaDataProxy("jdbc:cubrid:readonly", new AtomicInteger());
        bindPhysicalConnections(connection, master, readOnly);

        DatabaseMetaData metaData = connection.getMetaData();

        assertTrue(metaData.isWrapperFor(DatabaseMetaData.class));
        assertSame(metaData, metaData.unwrap(DatabaseMetaData.class));
    }

    /**
     * Inverted: unwrapping to the vendor metadata type now succeeds, because the metadata is that
     * type. What must still be refused is a type the metadata is not — and the refusal must still
     * hand out this object rather than the physical metadata, or the {@code getConnection()}
     * interception is defeated and the SHARD extensions become reachable.
     */
    @Test
    public void assertMetaDataUnwrapToVendorTypeSucceedsAndForeignTypeIsRefused() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        DatabaseMetaData master = newMetaDataProxy("jdbc:cubrid:master", new AtomicInteger());
        DatabaseMetaData readOnly = newMetaDataProxy("jdbc:cubrid:readonly", new AtomicInteger());
        bindPhysicalConnections(connection, master, readOnly);

        DatabaseMetaData metaData = connection.getMetaData();

        assertTrue(metaData.isWrapperFor(cubrid.jdbc.driver.CUBRIDDatabaseMetaData.class));
        assertSame(metaData, metaData.unwrap(cubrid.jdbc.driver.CUBRIDDatabaseMetaData.class));

        assertFalse(metaData.isWrapperFor(java.sql.Driver.class));
        try {
            metaData.unwrap(java.sql.Driver.class);
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().startsWith("Not supported method"));
        }
    }

    /**
     * SHARD is refused on the metadata for the same reason it is refused on the connection and on
     * statements: an LB session spans brokers of one HA cluster with no shards behind them.
     */
    @Test
    public void assertMetaDataShardApiIsRefusedByName() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        DatabaseMetaData master = newMetaDataProxy("jdbc:cubrid:master", new AtomicInteger());
        DatabaseMetaData readOnly = newMetaDataProxy("jdbc:cubrid:readonly", new AtomicInteger());
        bindPhysicalConnections(connection, master, readOnly);

        cubrid.jdbc.driver.CUBRIDDatabaseMetaData metaData =
                (cubrid.jdbc.driver.CUBRIDDatabaseMetaData) connection.getMetaData();

        assertShardCallRefused(metaData, "getShardId");
        assertShardCallRefused(metaData, "setShardId");
        assertShardCallRefused(metaData, "getShardDBName");
        assertShardCallRefused(metaData, "getShardDBServer");
    }

    private static void assertShardCallRefused(
            final cubrid.jdbc.driver.CUBRIDDatabaseMetaData metaData, final String api) {
        try {
            if ("getShardId".equals(api)) {
                metaData.getShardId();
            } else if ("setShardId".equals(api)) {
                metaData.setShardId(0);
            } else if ("getShardDBName".equals(api)) {
                metaData.getShardDBName();
            } else {
                metaData.getShardDBServer();
            }
            fail("SHARD metadata API must be refused: " + api);
        } catch (SQLException expected) {
            assertTrue(
                    "message must name the refused API: " + expected.getMessage(),
                    expected.getMessage().indexOf(api) >= 0);
        }
    }

    // The 3-arg createStatement fallback must trigger only for a genuinely unsupported
    // holdability combination, degrading to the 2-arg form.
    @Test
    public void assertCreateStatementFallsBackForUnsupportedHoldability() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger threeArgCalls = new AtomicInteger();
        AtomicInteger twoArgCalls = new AtomicInteger();
        bindRwPhysical(
                connection, physicalConnFailingThreeArgCreate(threeArgCalls, twoArgCalls, true));

        LBStatement.PhysicalStmtProvider provider =
                connection.createStatementProvider(
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_UPDATABLE,
                        ResultSet.HOLD_CURSORS_OVER_COMMIT);
        Statement stmt =
                provider.getStatement(Router.RouteTarget.TO_READ_WRITE, "UPDATE t SET v=1");

        assertNotNull(stmt);
        assertEquals(1, threeArgCalls.get());
        assertEquals("unsupported holdability -> fell back to 2-arg", 1, twoArgCalls.get());
    }

    // A non-holdability error (e.g. broker down) must propagate unchanged, not be masked
    // by a second createStatement call surfacing a different, misleading error.
    @Test
    public void assertCreateStatementRethrowsNonHoldabilityError() throws Exception {
        LoadBalanceConnection connection = createBoundConnection();
        AtomicInteger threeArgCalls = new AtomicInteger();
        AtomicInteger twoArgCalls = new AtomicInteger();
        bindRwPhysical(
                connection, physicalConnFailingThreeArgCreate(threeArgCalls, twoArgCalls, false));

        LBStatement.PhysicalStmtProvider provider =
                connection.createStatementProvider(
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_UPDATABLE,
                        ResultSet.HOLD_CURSORS_OVER_COMMIT);
        try {
            provider.getStatement(Router.RouteTarget.TO_READ_WRITE, "UPDATE t SET v=1");
            fail("expected the original broker-down SQLException, not a masked fallback");
        } catch (SQLException expected) {
            assertEquals("broker down", expected.getMessage());
        }
        assertEquals(1, threeArgCalls.get());
        assertEquals(
                "non-holdability error must NOT trigger the 2-arg fallback", 0, twoArgCalls.get());
    }

    private static void bindRwPhysical(
            final LoadBalanceConnection connection, final Connection physical) throws Exception {
        SimpleEndpointConnManager manager =
                (SimpleEndpointConnManager) connection.getConnectionManager();
        manager.setPhysicalConnection(connection.getCurrentEp(SessionLeg.RW), physical);
    }

    /**
     * What {@code CUBRIDConnection.createStatement(int, int, int)} really throws for a combination
     * the driver does not support: {@code CUBRIDException.notSupported()}. That factory is
     * package-private to {@code cubrid.jdbc.driver}, so it is reproduced here field for field --
     * type, message, SQLState and vendor code all have to match, because the fallback in
     * LoadBalanceConnection keys off this exception and a fake that differs from the real driver
     * would let a broken discriminator pass.
     */
    private static SQLFeatureNotSupportedException unsupportedHoldabilityFailure() {
        return new SQLFeatureNotSupportedException(
                CUBRIDJDBCErrorCode.getMessage(CUBRIDJDBCErrorCode.not_supported),
                null,
                CUBRIDJDBCErrorCode.not_supported);
    }

    /**
     * Physical connection whose 3-arg createStatement always throws: the base driver's
     * unsupported-combination refusal when {@code unsupportedHoldability} is true, or a plain
     * "broker down" SQLException otherwise. The 2-arg createStatement succeeds and is counted.
     */
    private static Connection physicalConnFailingThreeArgCreate(
            final AtomicInteger threeArgCalls,
            final AtomicInteger twoArgCalls,
            final boolean unsupportedHoldability) {
        return (Connection)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionMetadataProxyTest.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                String methodName = method.getName();
                                if ("createStatement".equals(methodName)
                                        && args != null
                                        && args.length == 3) {
                                    threeArgCalls.incrementAndGet();
                                    if (unsupportedHoldability) {
                                        throw unsupportedHoldabilityFailure();
                                    }
                                    throw new SQLException("broker down");
                                }
                                if ("createStatement".equals(methodName)
                                        && args != null
                                        && args.length == 2) {
                                    twoArgCalls.incrementAndGet();
                                    return (Statement)
                                            Proxy.newProxyInstance(
                                                    LoadBalanceConnectionMetadataProxyTest.class
                                                            .getClassLoader(),
                                                    new Class[] {Statement.class},
                                                    new InvocationHandler() {
                                                        public Object invoke(
                                                                final Object p,
                                                                final Method m,
                                                                final Object[] a) {
                                                            return null;
                                                        }
                                                    });
                                }
                                if ("isClosed".equals(methodName)) {
                                    return Boolean.FALSE;
                                }
                                Class<?> returnType = method.getReturnType();
                                if (Boolean.TYPE.equals(returnType)) {
                                    return Boolean.FALSE;
                                }
                                if (Integer.TYPE.equals(returnType)) {
                                    return Integer.valueOf(0);
                                }
                                return null;
                            }
                        });
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
            final DatabaseMetaData masterMetaData,
            final DatabaseMetaData readOnlyMetaData)
            throws Exception {
        SimpleEndpointConnManager manager =
                (SimpleEndpointConnManager) connection.getConnectionManager();
        manager.setPhysicalConnection(
                connection.getCurrentEp(SessionLeg.RW), newConnectionProxy(masterMetaData));
        manager.setPhysicalConnection(
                connection.getCurrentEp(SessionLeg.RO), newConnectionProxy(readOnlyMetaData));
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

    private static Connection newConnectionProxy(final DatabaseMetaData metaData) {
        return (Connection)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionMetadataProxyTest.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                String methodName = method.getName();
                                if ("getMetaData".equals(methodName)) {
                                    return metaData;
                                }
                                if ("isClosed".equals(methodName)) {
                                    return Boolean.FALSE;
                                }
                                if ("close".equals(methodName)) {
                                    return null;
                                }
                                if ("unwrap".equals(methodName)) {
                                    return null;
                                }
                                if ("isWrapperFor".equals(methodName)) {
                                    return Boolean.FALSE;
                                }
                                Class<?> returnType = method.getReturnType();
                                if (Boolean.TYPE.equals(returnType)) {
                                    return Boolean.FALSE;
                                }
                                if (Integer.TYPE.equals(returnType)) {
                                    return Integer.valueOf(0);
                                }
                                if (Long.TYPE.equals(returnType)) {
                                    return Long.valueOf(0L);
                                }
                                return null;
                            }
                        });
    }

    private static DatabaseMetaData newMetaDataProxy(
            final String url, final AtomicInteger getUrlCalls) {
        return newMetaDataProxy(url, getUrlCalls, null, null, null, null);
    }

    private static DatabaseMetaData newMetaDataProxy(
            final String url,
            final AtomicInteger getUrlCalls,
            final AtomicInteger getDatabaseProductVersionCalls,
            final AtomicInteger getTablesCalls) {
        return newMetaDataProxy(
                url, getUrlCalls, getDatabaseProductVersionCalls, getTablesCalls, null, null);
    }

    private static DatabaseMetaData newMetaDataProxy(
            final String url,
            final AtomicInteger getUrlCalls,
            final AtomicInteger getDatabaseProductVersionCalls,
            final AtomicInteger getTablesCalls,
            final AtomicInteger getDatabaseMajorVersionCalls,
            final AtomicInteger getDatabaseMinorVersionCalls) {
        return (DatabaseMetaData)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionMetadataProxyTest.class.getClassLoader(),
                        new Class[] {DatabaseMetaData.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                String methodName = method.getName();
                                if ("getURL".equals(methodName)) {
                                    getUrlCalls.incrementAndGet();
                                    return url;
                                }
                                if ("getDatabaseProductVersion".equals(methodName)) {
                                    if (getDatabaseProductVersionCalls != null) {
                                        getDatabaseProductVersionCalls.incrementAndGet();
                                    }
                                    if ("jdbc:cubrid:readonly".equals(url)) {
                                        return "ro-product-version";
                                    }
                                    return "rw-product-version";
                                }
                                if ("getTables".equals(methodName)) {
                                    if (getTablesCalls != null) {
                                        getTablesCalls.incrementAndGet();
                                    }
                                    return null;
                                }
                                if ("getDatabaseMajorVersion".equals(methodName)) {
                                    if (getDatabaseMajorVersionCalls != null) {
                                        getDatabaseMajorVersionCalls.incrementAndGet();
                                    }
                                    return Integer.valueOf(
                                            "jdbc:cubrid:readonly".equals(url) ? 11 : 10);
                                }
                                if ("getDatabaseMinorVersion".equals(methodName)) {
                                    if (getDatabaseMinorVersionCalls != null) {
                                        getDatabaseMinorVersionCalls.incrementAndGet();
                                    }
                                    return Integer.valueOf(
                                            "jdbc:cubrid:readonly".equals(url) ? 3 : 1);
                                }
                                if ("unwrap".equals(methodName)) {
                                    return null;
                                }
                                if ("isWrapperFor".equals(methodName)) {
                                    return Boolean.FALSE;
                                }
                                Class<?> returnType = method.getReturnType();
                                if (Boolean.TYPE.equals(returnType)) {
                                    return Boolean.FALSE;
                                }
                                if (Integer.TYPE.equals(returnType)) {
                                    return Integer.valueOf(0);
                                }
                                if (Long.TYPE.equals(returnType)) {
                                    return Long.valueOf(0L);
                                }
                                return null;
                            }
                        });
    }

    private static DatabaseMetaData newMetaDataProxyWithSchemaCalls(
            final String url,
            final AtomicInteger getSuperTablesCalls,
            final AtomicInteger getIndexInfoCalls,
            final AtomicInteger getBestRowIdentifierCalls) {
        return (DatabaseMetaData)
                Proxy.newProxyInstance(
                        LoadBalanceConnectionMetadataProxyTest.class.getClassLoader(),
                        new Class[] {DatabaseMetaData.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                String methodName = method.getName();
                                if ("getSuperTables".equals(methodName)) {
                                    if (getSuperTablesCalls != null) {
                                        getSuperTablesCalls.incrementAndGet();
                                    }
                                    return null;
                                }
                                if ("getIndexInfo".equals(methodName)) {
                                    if (getIndexInfoCalls != null) {
                                        getIndexInfoCalls.incrementAndGet();
                                    }
                                    return null;
                                }
                                if ("getBestRowIdentifier".equals(methodName)) {
                                    if (getBestRowIdentifierCalls != null) {
                                        getBestRowIdentifierCalls.incrementAndGet();
                                    }
                                    return null;
                                }
                                if ("unwrap".equals(methodName)) {
                                    return null;
                                }
                                if ("isWrapperFor".equals(methodName)) {
                                    return Boolean.FALSE;
                                }
                                Class<?> returnType = method.getReturnType();
                                if (Boolean.TYPE.equals(returnType)) {
                                    return Boolean.FALSE;
                                }
                                if (Integer.TYPE.equals(returnType)) {
                                    return Integer.valueOf(0);
                                }
                                if (Long.TYPE.equals(returnType)) {
                                    return Long.valueOf(0L);
                                }
                                if (String.class.equals(returnType)) {
                                    return url;
                                }
                                return null;
                            }
                        });
    }
}
