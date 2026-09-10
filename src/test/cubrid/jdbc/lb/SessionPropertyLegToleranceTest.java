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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
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
