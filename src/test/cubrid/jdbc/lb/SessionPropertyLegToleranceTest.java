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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import cubrid.jdbc.lb.connection.SessionPhysicalConnManager;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * A session property must reach both physical legs, and the logical value must end up describing
 * what the write leg actually took.
 *
 * <p>The write leg used to run unguarded and first, so anything it threw skipped the read leg
 * entirely — even though reads would have kept working, since the execute path absorbs a dead read
 * endpoint by failing over. And the logical value was stored before any physical contact, so a call
 * the application saw fail was still replayed onto every connection opened later.
 */
public final class SessionPropertyLegToleranceTest {

    private final AtomicInteger roLockTimeouts = new AtomicInteger();
    private final AtomicInteger roCasChangeModes = new AtomicInteger();
    private final SQLException rwBoom = new SQLException("write leg is gone");
    private final SQLException roBoom = new SQLException("read leg is gone");
    // setCharset is the one property setter a physical leg cannot fail with an SQLException:
    // CUBRIDConnection declares it throwing UnsupportedEncodingException only.
    private final UnsupportedEncodingException rwCharsetBoom =
            new UnsupportedEncodingException("write leg is gone");
    private volatile boolean rwFails;
    private volatile boolean roFails;
    private volatile boolean rwRefusesAutoCommit;
    private volatile boolean roRefusesAutoCommit;
    private final List<LegConnection> rwOpened = new ArrayList<LegConnection>();
    private final List<LegConnection> roOpened = new ArrayList<LegConnection>();

    /**
     * The write leg switched to manual mode but the read leg refused, and the session stayed in
     * autocommit: writes then ran uncommitted on RW, commit() was refused, and close rolled them
     * back. The write leg decides the mode; a read leg that cannot follow is dropped instead.
     */
    @Test
    public void autoCommitFollowsTheWriteLegWhenOnlyTheReadLegRefuses() throws Exception {
        LoadBalanceConnection connection = boundOnSessionManager();
        LegConnection ro = roOpened.get(0);
        roRefusesAutoCommit = true;

        connection.setAutoCommit(false);

        assertFalse(
                "the write leg is in manual mode, so the session is", connection.getAutoCommit());
        assertFalse(rwOpened.get(0).autoCommit);
        connection.commit();
        assertTrue("a read leg left in the other mode must not stay bound", ro.closed);
    }

    /**
     * The other way round: a read leg stuck in manual mode would run autocommit reads in a
     * transaction nobody commits. Dropped, it reopens in the session's mode.
     */
    @Test
    public void readLegReopensInTheSessionsModeAfterRefusingIt() throws Exception {
        LoadBalanceConnection connection = boundOnSessionManager();
        connection.setAutoCommit(false);
        LegConnection ro = roOpened.get(roOpened.size() - 1);
        roRefusesAutoCommit = true;

        connection.setAutoCommit(true);

        assertTrue(connection.getAutoCommit());
        assertTrue(ro.closed);
        roRefusesAutoCommit = false;
        connection.getPhysicalConnForCmd(SessionLeg.RO);
        assertTrue(
                "the reopened read leg must be in autocommit",
                roOpened.get(roOpened.size() - 1).autoCommit);
    }

    /** A mode the write leg refused must not reach the read leg either: nothing changes. */
    @Test
    public void readLegKeepsItsModeWhenTheWriteLegRefuses() throws Exception {
        LoadBalanceConnection connection = boundOnSessionManager();
        rwRefusesAutoCommit = true;

        try {
            connection.setAutoCommit(false);
            fail("the write leg's refusal must reach the caller");
        } catch (SQLException expected) {
            assertSame(rwBoom, expected);
        }

        assertTrue(connection.getAutoCommit());
        assertTrue("the read leg must stay in the session's mode", roOpened.get(0).autoCommit);
    }

    @Test
    public void lockTimeoutReachesTheReadLegWhenTheWriteLegFails() throws Exception {
        LoadBalanceConnection connection = bound();
        rwFails = true;

        try {
            connection.setLockTimeout(9);
            fail("the write leg's failure must still reach the caller");
        } catch (SQLException propagated) {
            assertSame("the caller sees the write failure", rwBoom, propagated);
        }

        assertEquals("read leg took the value", 1, roLockTimeouts.get());
    }

    @Test
    public void logicalValueIsDroppedWhenTheWriteLegRefusesIt() throws Exception {
        LoadBalanceConnection connection = bound();
        connection.setLockTimeout(3);
        rwFails = true;

        try {
            connection.setLockTimeout(9);
            fail("expected the simulated write-leg failure");
        } catch (SQLException expected) {
        }

        assertEquals(
                "a value the write leg refused must not survive as session state",
                Integer.valueOf(3),
                logicalField(connection, "lockTimeout"));
    }

    /**
     * The mirror case: the write leg holds the new value, so the logical state must keep it too —
     * that is what restores the value on the next read leg the session binds.
     */
    @Test
    public void logicalValueSurvivesAReadLegOnlyFailure() throws Exception {
        LoadBalanceConnection connection = bound();
        roFails = true;

        try {
            connection.setLockTimeout(9);
            fail("a read-leg failure must still reach the caller");
        } catch (SQLException propagated) {
            assertSame("the caller sees the read failure", roBoom, propagated);
        }

        assertEquals(
                "the write leg holds it, so the session must too",
                Integer.valueOf(9),
                logicalField(connection, "lockTimeout"));
    }

    @Test
    public void unsetPropertyStaysUnsetWhenTheWriteLegFails() throws Exception {
        LoadBalanceConnection connection = bound();
        rwFails = true;

        try {
            connection.setLockTimeout(9);
            fail("expected the simulated write-leg failure");
        } catch (SQLException expected) {
        }

        assertNull(
                "a never-applied property must not become session state",
                logicalField(connection, "lockTimeout"));
    }

    @Test
    public void casChangeModeReachesTheReadLegWhenTheWriteLegFails() throws Exception {
        LoadBalanceConnection connection = bound();
        rwFails = true;

        try {
            connection.setCASChangeMode(2);
            fail("the write leg's failure must still reach the caller");
        } catch (SQLException propagated) {
            assertSame("the caller sees the write failure", rwBoom, propagated);
        }

        assertEquals("read leg took the mode", 1, roCasChangeModes.get());
        assertNull(
                "a mode the write leg refused must not survive as session state",
                logicalField(connection, "casChangeMode"));
    }

    @Test
    public void casChangeModeKeepsTheModeWhenOnlyTheReadLegFails() throws Exception {
        LoadBalanceConnection connection = bound();
        roFails = true;

        try {
            connection.setCASChangeMode(2);
            fail("a read-leg failure must still reach the caller");
        } catch (SQLException propagated) {
            assertSame("the caller sees the read failure", roBoom, propagated);
        }

        assertEquals(
                "the write leg holds it, so the session must too",
                Integer.valueOf(2),
                logicalField(connection, "casChangeMode"));
    }

    @Test
    public void bothLegsTakeThePropertyWhenNothingFails() throws Exception {
        LoadBalanceConnection connection = bound();

        connection.setLockTimeout(9);
        connection.setCASChangeMode(2);

        assertEquals(1, roLockTimeouts.get());
        assertEquals(1, roCasChangeModes.get());
        assertEquals(Integer.valueOf(9), logicalField(connection, "lockTimeout"));
        assertEquals(Integer.valueOf(2), logicalField(connection, "casChangeMode"));
    }

    private static Object logicalField(final LoadBalanceConnection connection, final String name)
            throws Exception {
        Field field = LoadBalanceConnection.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(connection);
    }

    /**
     * A charset set before the session has bound anything used to be a silent no-op: the call
     * reported success and the setting reached nothing. It must be remembered instead, so the value
     * is applied when a leg opens.
     */
    @Test
    public void charsetSetBeforeBindingIsRememberedNotDropped() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceConnection connection =
                new LoadBalanceConnection(LoadBalanceSettings.of(properties));

        connection.setCharset("utf-8");

        assertEquals(
                "an unbound setCharset must be deferred, not discarded",
                "utf-8",
                logicalField(connection, "charset"));
    }

    /**
     * The value that reached the legs must also survive as session state — that is what re-applies
     * it to the next leg the session binds, after a failover. Charset was the one session property
     * that did not do this, while {@code lockTimeout} and {@code casChangeMode} already did.
     */
    @Test
    public void charsetThatReachedTheLegsSurvivesAsSessionState() throws Exception {
        LoadBalanceConnection connection = bound();

        connection.setCharset("euc-kr");

        assertEquals("euc-kr", logicalField(connection, "charset"));
    }

    /**
     * Mirrors {@code logicalValueIsNotKeptWhenTheWriteLegRefuses} for charset: a value the write
     * leg refused must not survive, or the next leg would be configured from a setting the session
     * never actually holds.
     */
    @Test
    public void charsetIsNotKeptWhenTheWriteLegRefuses() throws Exception {
        LoadBalanceConnection connection = bound();
        connection.setCharset("utf-8");
        rwFails = true;

        try {
            connection.setCharset("euc-kr");
            fail("the write leg's failure must still reach the caller");
        } catch (UnsupportedEncodingException propagated) {
            // The only failure a real leg can raise here, and LB passes it through unwrapped -
            // its own signature declares the same clause.
            assertSame(rwCharsetBoom, propagated);
        }

        assertEquals(
                "a charset the write leg refused must not survive as session state",
                "utf-8",
                logicalField(connection, "charset"));
    }

    private LoadBalanceConnection bound() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceConnection connection =
                new LoadBalanceConnection(LoadBalanceSettings.of(properties));

        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        connection.setConnectionManager(manager);
        connection.setSharedSelectorState(new SharedSelectorState());

        List<Endpoint> roEndpoints = new ArrayList<Endpoint>();
        roEndpoints.add(new Endpoint("ro1", 33000));
        connection.initSessionBindings(
                new EndpointTopology(new Endpoint("rw", 33000), roEndpoints, null));

        manager.setPhysicalConnection(connection.getCurrentEp(SessionLeg.RW), writeLeg());
        manager.setPhysicalConnection(connection.getCurrentEp(SessionLeg.RO), readLeg());
        return connection;
    }

    /** Bound through the real connection manager, whose legs are {@link LegConnection}s. */
    private LoadBalanceConnection boundOnSessionManager() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceSettings config = LoadBalanceSettings.of(properties);
        LoadBalanceConnection connection = new LoadBalanceConnection(config);
        connection.setConnectionManager(
                new SessionPhysicalConnManager(
                        "jdbc:cubrid:localhost:30000:testdb:public::",
                        new Properties(),
                        config,
                        new JdbcConnectionFactory() {
                            public Connection getConnection(
                                    final String url, final Properties info) {
                                boolean readLeg = url.contains(":ro1:");
                                LegConnection leg = new LegConnection(readLeg);
                                (readLeg ? roOpened : rwOpened).add(leg);
                                return leg;
                            }
                        }));
        connection.setSharedSelectorState(new SharedSelectorState());
        connection.initSessionBindings(
                new EndpointTopology(
                        new Endpoint("rw", 33000),
                        Arrays.asList(new Endpoint("ro1", 33000)),
                        null));
        return connection;
    }

    /** A physical leg that remembers its autocommit mode and can refuse to change it. */
    private final class LegConnection extends FakePhysicalConnection {

        private final boolean readLeg;

        boolean autoCommit = true;

        boolean closed;

        LegConnection(final boolean readLeg) {
            this.readLeg = readLeg;
        }

        @Override
        protected Object dispatch(final String name, final Object[] args) throws SQLException {
            if ("setAutoCommit".equals(name)) {
                if (readLeg ? roRefusesAutoCommit : rwRefusesAutoCommit) {
                    throw readLeg ? roBoom : rwBoom;
                }
                autoCommit = ((Boolean) args[0]).booleanValue();
                return null;
            }
            if ("getAutoCommit".equals(name)) {
                return Boolean.valueOf(autoCommit);
            }
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return Boolean.valueOf(closed);
            }
            return null;
        }
    }

    /** Write leg whose property setters fail on demand. */
    private Connection writeLeg() {
        return new FakePhysicalConnection() {
            @Override
            protected Object dispatch(final String name, final Object[] args) throws SQLException {
                if (rwFails && isPropertySetter(name)) {
                    throw rwBoom;
                }
                return null;
            }

            @Override
            public void setCharset(final String charsetName) throws UnsupportedEncodingException {
                if (rwFails) {
                    throw rwCharsetBoom;
                }
            }
        };
    }

    /** Read leg that counts the calls a write-leg failure used to swallow. */
    private Connection readLeg() {
        return new FakePhysicalConnection() {
            @Override
            protected Object dispatch(final String name, final Object[] args) throws SQLException {
                if (roFails && isPropertySetter(name)) {
                    throw roBoom;
                }
                if ("setLockTimeout".equals(name)) {
                    roLockTimeouts.incrementAndGet();
                    return null;
                }
                if ("setCASChangeMode".equals(name)) {
                    roCasChangeModes.incrementAndGet();
                    return Integer.valueOf(1);
                }
                return null;
            }
        };
    }

    private static boolean isPropertySetter(final String name) {
        return "setLockTimeout".equals(name) || "setCASChangeMode".equals(name);
    }
}
