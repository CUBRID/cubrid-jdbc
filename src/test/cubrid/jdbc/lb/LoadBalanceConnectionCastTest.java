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

import cubrid.jdbc.driver.CUBRIDConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * {@link LoadBalanceConnection} must be usable as a {@link CUBRIDConnection} by an application that
 * was never changed for load balancing:
 *
 * <pre>
 *   CUBRIDConnection c = (CUBRIDConnection) conn;
 *   c.setLockTimeout(1000);
 * </pre>
 *
 * <p>Three things have to hold for that line to be safe: the cast must succeed; the vendor
 * extension API must reach the physical legs rather than the null inherited {@code u_con}; and
 * nothing may be left inherited from {@code CUBRIDConnection}, because an inherited method runs
 * against that null socket and against inherited state this object does not maintain.
 */
public final class LoadBalanceConnectionCastTest {

    @Test
    public void castToCubridConnectionSucceeds() throws Exception {
        Connection conn = boundEx();

        CUBRIDConnection c = (CUBRIDConnection) conn;
        assertNotNull(c);
    }

    @Test
    public void unwrapToCubridConnectionSucceeds() throws Exception {
        Connection conn = boundEx();

        assertTrue(conn.isWrapperFor(CUBRIDConnection.class));
        assertSame(conn, conn.unwrap(CUBRIDConnection.class));
    }

    /**
     * A statement must report the object the application holds. Returning the inner connection
     * would make {@code (CUBRIDConnection) stmt.getConnection()} fail on an object the driver
     * produced.
     */
    @Test
    public void statementReportsThisConnectionAsItsOwner() throws Exception {
        LoadBalanceConnection conn = boundEx();
        Statement stmt = conn.createStatement();

        assertSame(
                "stmt.getConnection() must be the object handed to the application",
                conn,
                stmt.getConnection());
        assertNotNull((CUBRIDConnection) stmt.getConnection());
    }

    @Test
    public void databaseMetaDataReportsThisConnectionAsItsOwner() throws Exception {
        LoadBalanceConnection conn = boundEx();
        DatabaseMetaData metaData = conn.getMetaData();

        assertSame(conn, metaData.getConnection());
    }

    @Test
    public void setLockTimeoutReachesBothLegs() throws Exception {
        Legs legs = new Legs();
        LoadBalanceConnection conn = boundEx(legs);

        CUBRIDConnection c = (CUBRIDConnection) conn;
        c.setLockTimeout(1000);

        assertEquals("write leg", 1, legs.rwLockTimeouts.get());
        assertEquals("read leg", 1, legs.roLockTimeouts.get());
    }

    @Test
    public void setCasChangeModeReachesBothLegs() throws Exception {
        Legs legs = new Legs();
        LoadBalanceConnection conn = boundEx(legs);

        ((CUBRIDConnection) conn).setCASChangeMode(CUBRIDConnection.CAS_CHANGE_MODE_KEEP);

        assertEquals(1, legs.rwCasChangeModes.get());
        assertEquals(1, legs.roCasChangeModes.get());
    }

    @Test
    public void setCharsetReachesBothLegs() throws Exception {
        Legs legs = new Legs();
        LoadBalanceConnection conn = boundEx(legs);

        ((CUBRIDConnection) conn).setCharset("utf-8");

        assertEquals(1, legs.rwCharsets.get());
        assertEquals(1, legs.roCharsets.get());
    }

    /**
     * {@code Login} used to answer null when the session had bound no physical connection yet. The
     * key is the whole point of the call, so a null answer is not an answer — it surfaces later as
     * a NullPointerException in the caller, at a place that no longer says which call produced it.
     *
     * <p>Unlike {@code setCharset}, this cannot be deferred and replayed: the key comes from the
     * server, so there is nothing to remember.
     */
    @Test
    public void loginRefusesOnAnUnboundSessionInsteadOfAnsweringNull() throws Exception {
        LoadBalanceConnection unbound =
                new LoadBalanceConnection(LoadBalanceSettings.of(sessionModeProperties()));

        try {
            ((CUBRIDConnection) unbound).Login("signed-data");
            fail("Login(String) must refuse on an unbound session");
        } catch (SQLException refused) {
            assertTrue(
                    "the message must say what is missing: " + refused.getMessage(),
                    refused.getMessage().indexOf("bound session") >= 0);
        }

        try {
            ((CUBRIDConnection) unbound).Login(new byte[] {1, 2, 3});
            fail("Login(byte[]) must refuse on an unbound session");
        } catch (SQLException refused) {
            assertTrue(refused.getMessage().indexOf("bound session") >= 0);
        }
    }

    /**
     * The write leg is the one socket a CUBRIDConnection-shaped caller means by "this connection".
     */
    @Test
    public void getUConnectionResolvesToTheWriteLeg() throws Exception {
        Legs legs = new Legs();
        LoadBalanceConnection conn = boundEx(legs);

        ((CUBRIDConnection) conn).getUConnection();

        assertEquals("write leg", 1, legs.rwUConnections.get());
        assertEquals("read leg must not be asked", 0, legs.roUConnections.get());
    }

    @Test
    public void lobApiIsPinnedToTheWriteLeg() throws Exception {
        Legs legs = new Legs();
        LoadBalanceConnection conn = boundEx(legs);

        ((CUBRIDConnection) conn).lobNew(0);

        assertEquals(1, legs.rwLobNews.get());
        assertEquals(0, legs.roLobNews.get());
    }

    @Test
    public void shardApiIsRefusedWithANamedError() throws Exception {
        LoadBalanceConnection conn = boundEx();

        assertRefused("getShardMetaData()", conn);
    }

    @Test
    public void isShardDoesNotReachTheNullInheritedSocket() throws Exception {
        LoadBalanceConnection conn = boundEx();
        try {
            conn.isShard();
            fail("isShard() must be refused, not answered");
        } catch (RuntimeException expected) {
            assertFalse(
                    "a NullPointerException here means the method was left inherited",
                    expected instanceof NullPointerException);
        }
    }

    @Test
    public void getShardIdDoesNotReachTheNullInheritedSocket() throws Exception {
        LoadBalanceConnection conn = boundEx();
        try {
            conn.getShardId();
            fail("getShardId() must be refused, not answered");
        } catch (RuntimeException expected) {
            assertFalse(
                    "a NullPointerException here means the method was left inherited",
                    expected instanceof NullPointerException);
        }
    }

    /**
     * Every {@code public}/{@code protected} method of {@code CUBRIDConnection} must be declared
     * here. An inherited one runs against {@code u_con == null} and against inherited fields this
     * object does not maintain — {@code is_closed} in particular stays false forever, so an
     * inherited {@code checkIsOpen()} would pass on a closed connection. When this fails, the named
     * method was added to {@code CUBRIDConnection} and needs an override.
     */
    @Test
    public void everyOverridableInheritedMethodIsOverridden() {
        List<String> missing = new ArrayList<String>();

        for (Method inherited : CUBRIDConnection.class.getDeclaredMethods()) {
            if (!isOverridableFromAnotherPackage(inherited)) {
                continue;
            }
            try {
                LoadBalanceConnection.class.getDeclaredMethod(
                        inherited.getName(), inherited.getParameterTypes());
            } catch (NoSuchMethodException notOverridden) {
                missing.add(signatureOf(inherited));
            }
        }

        assertTrue(
                "LoadBalanceConnection inherits these from CUBRIDConnection; each would run"
                        + " against the null u_con or against unmaintained inherited state: "
                        + missing,
                missing.isEmpty());
    }

    /**
     * The package-private members of {@code CUBRIDConnection} cannot be overridden from another
     * package — a same-named method here is a new method, not an override, and a call from inside
     * {@code cubrid.jdbc.driver} still dispatches to {@code CUBRIDConnection}'s body and its null
     * {@code u_con}. They are safe only because nothing hands this object to code that calls them:
     * {@code CUBRIDStatement} and {@code CUBRIDResultSet} call them on the physical connection that
     * created them.
     *
     * <p>The one way to break that is {@code CUBRIDOIDImpl.getNewInstance(CUBRIDConnection,
     * String)}, which an application can call with this object; the OID then passes it to {@code
     * CUBRIDResultSet}. Pinning the exact list here means a newly added package-private member
     * fails this test and gets that reachability question asked again, rather than being discovered
     * as a NullPointerException in the field.
     */
    @Test
    public void packagePrivateMembersAreTheKnownUnreachableSet() {
        List<String> packagePrivate = new ArrayList<String>();

        for (Method inherited : CUBRIDConnection.class.getDeclaredMethods()) {
            int modifiers = inherited.getModifiers();
            if (Modifier.isStatic(modifiers)
                    || Modifier.isPrivate(modifiers)
                    || Modifier.isPublic(modifiers)
                    || Modifier.isProtected(modifiers)
                    || inherited.isSynthetic()) {
                continue;
            }
            packagePrivate.add(signatureOf(inherited));
        }
        java.util.Collections.sort(packagePrivate);

        assertEquals(
                "a new package-private member of CUBRIDConnection cannot be overridden here;"
                        + " confirm nothing hands a LoadBalanceConnection to a caller of it",
                "[closeConnection(), createCUBRIDException(UError),"
                        + " createCUBRIDException(int, String, Throwable),"
                        + " createCUBRIDException(int, Throwable), isSavepointTopologySupported(),"
                        + " prepare(String, byte), removeStatement(Statement)]",
                packagePrivate.toString());
    }

    private static boolean isOverridableFromAnotherPackage(final Method method) {
        int modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || method.isSynthetic()) {
            return false;
        }
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String signatureOf(final Method method) {
        StringBuilder out = new StringBuilder(method.getName()).append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            out.append(i == 0 ? "" : ", ").append(parameters[i].getSimpleName());
        }
        return out.append(')').toString();
    }

    private static void assertRefused(final String api, final LoadBalanceConnection conn) {
        try {
            conn.getShardMetaData();
            fail(api + " must be refused");
        } catch (SQLException expected) {
            assertTrue(
                    "the message must name the refused API, not just say 'not supported'",
                    expected.getMessage().contains("getShardMetaData()"));
        }
    }

    private static Properties sessionModeProperties() {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");

        return properties;
    }

    private LoadBalanceConnection boundEx() throws Exception {
        return boundEx(new Legs());
    }

    private LoadBalanceConnection boundEx(final Legs legs) throws Exception {
        LoadBalanceConnection lb =
                new LoadBalanceConnection(LoadBalanceSettings.of(sessionModeProperties()));

        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        lb.setConnectionManager(manager);
        lb.setSharedSelectorState(new SharedSelectorState());

        List<Endpoint> roEndpoints = new ArrayList<Endpoint>();
        roEndpoints.add(new Endpoint("ro1", 33000));
        lb.initSessionBindings(new EndpointTopology(new Endpoint("rw", 33000), roEndpoints, null));

        manager.setPhysicalConnection(lb.getCurrentEp(SessionLeg.RW), legs.writeLeg());
        manager.setPhysicalConnection(lb.getCurrentEp(SessionLeg.RO), legs.readLeg());

        return lb;
    }

    private static final class Legs {

        final AtomicInteger rwLockTimeouts = new AtomicInteger();
        final AtomicInteger roLockTimeouts = new AtomicInteger();
        final AtomicInteger rwCasChangeModes = new AtomicInteger();
        final AtomicInteger roCasChangeModes = new AtomicInteger();
        final AtomicInteger rwCharsets = new AtomicInteger();
        final AtomicInteger roCharsets = new AtomicInteger();
        final AtomicInteger rwUConnections = new AtomicInteger();
        final AtomicInteger roUConnections = new AtomicInteger();
        final AtomicInteger rwLobNews = new AtomicInteger();
        final AtomicInteger roLobNews = new AtomicInteger();

        Connection writeLeg() {
            return leg(rwLockTimeouts, rwCasChangeModes, rwCharsets, rwUConnections, rwLobNews);
        }

        Connection readLeg() {
            return leg(roLockTimeouts, roCasChangeModes, roCharsets, roUConnections, roLobNews);
        }

        private static Connection leg(
                final AtomicInteger lockTimeouts,
                final AtomicInteger casChangeModes,
                final AtomicInteger charsets,
                final AtomicInteger uConnections,
                final AtomicInteger lobNews) {
            return new FakePhysicalConnection() {
                @Override
                protected Object dispatch(final String name, final Object[] args) {
                    if ("setLockTimeout".equals(name)) {
                        lockTimeouts.incrementAndGet();
                        return null;
                    }
                    if ("setCASChangeMode".equals(name)) {
                        casChangeModes.incrementAndGet();
                        return Integer.valueOf(1);
                    }
                    if ("setCharset".equals(name)) {
                        charsets.incrementAndGet();
                        return null;
                    }
                    if ("getUConnection".equals(name)) {
                        uConnections.incrementAndGet();
                        return null;
                    }
                    if ("lobNew".equals(name)) {
                        lobNews.incrementAndGet();
                        return new byte[0];
                    }
                    return null;
                }
            };
        }
    }
}
