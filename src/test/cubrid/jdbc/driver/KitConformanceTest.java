/*
 * Copyright (C) 2008 Search Solution Corporation.
 * Copyright (c) 2016 CUBRID Corporation.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * - Neither the name of the <ORGANIZATION> nor the names of its contributors may be used to endorse
 * or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package cubrid.jdbc.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.jci.UErrorCode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.Test;

/**
 * The kit answers as core does wherever LB depends on core: a kit statement replaces only the wire
 * (executing, binding, results), and what it inherits is core's own code. A test written on the kit
 * therefore checks LB against core, not against what the test's author believed core does.
 */
public class KitConformanceTest {
    private final KitServer server = new KitServer().node("kith", 1);

    private Connection open() throws SQLException {
        return server.getConnection("jdbc:cubrid:kith:1:db:dba::", new Properties());
    }

    /**
     * The only methods a kit class may replace: the wire, and the holdability core's rule gives.
     */
    private static final Set<String> WIRE =
            new HashSet<String>(
                    Arrays.asList(
                            "execute",
                            "executeQuery",
                            "executeUpdate",
                            "getResultSet",
                            "getUpdateCount",
                            "getMoreResults",
                            "addBatch",
                            "clearBatch",
                            "executeBatch",
                            "cancel",
                            "getQueryplan",
                            "complete",
                            "getHoldability",
                            "setInt",
                            "setLong",
                            "setString",
                            "setObject",
                            "setNull",
                            "clearParameters",
                            "registerOutParameter",
                            "getInt",
                            "getString",
                            "getObject",
                            "wasNull",
                            "bound",
                            "bind"));

    @Test
    public void kitStatementsReplaceOnlyTheWire() {
        final List<String> beyond = new ArrayList<String>();
        for (Class<?> kit :
                new Class<?>[] {
                    KitStatement.class, KitPreparedStatement.class, KitCallableStatement.class
                }) {
            for (Method m : kit.getDeclaredMethods()) {
                if (!m.isSynthetic()
                        && !Modifier.isStatic(m.getModifiers())
                        && !WIRE.contains(m.getName())) {
                    beyond.add(kit.getSimpleName() + "." + m.getName());
                }
            }
        }
        assertEquals("kit methods beyond the wire", new ArrayList<String>(), beyond);
        for (String name : new String[] {"addBatch", "executeBatch"}) {
            for (Method m : KitCallableStatement.class.getDeclaredMethods()) {
                assertFalse(
                        "core refuses CallableStatement batches; the kit must not answer instead",
                        m.getName().equals(name));
            }
        }
    }

    @Test
    public void callableStatementAnswersIdentityClosingAndBatchesAsCore() throws SQLException {
        final Connection c = open();
        final Connection reported = open();
        final CallableStatement cs = c.prepareCall("CALL p()");

        ((CUBRIDStatement) cs).setReportedConnection(reported);
        assertSame("core reports the connection set on it", reported, cs.getConnection());
        try {
            cs.addBatch();
            fail("core refuses batches on a CallableStatement");
        } catch (SQLFeatureNotSupportedException expected) {
            // core's own refusal
        }
        c.close();
        assertTrue("closing the connection closes its statements", cs.isClosed());
    }

    @Test
    public void commitClosesTheCursorsCoreClosesAndKeepsHeldOnes() throws SQLException {
        final Connection c = open();
        c.setAutoCommit(false);
        final ResultSet held =
                c.createStatement(
                                ResultSet.TYPE_FORWARD_ONLY,
                                ResultSet.CONCUR_READ_ONLY,
                                ResultSet.HOLD_CURSORS_OVER_COMMIT)
                        .executeQuery("SELECT 1");
        final ResultSet closing =
                c.createStatement(
                                ResultSet.TYPE_FORWARD_ONLY,
                                ResultSet.CONCUR_READ_ONLY,
                                ResultSet.CLOSE_CURSORS_AT_COMMIT)
                        .executeQuery("SELECT 2");

        c.commit();

        assertFalse(held.isClosed());
        assertTrue(closing.isClosed());
    }

    /**
     * Core's commit loop runs unchanged: the statement after a removed PreparedStatement is
     * skipped.
     */
    @Test
    public void coreCommitLoopSkipsTheStatementAfterARemovedPreparedStatement()
            throws SQLException {
        final Connection c = open();
        c.setAutoCommit(false);
        final PreparedStatement ps =
                c.prepareStatement(
                        "SELECT 1",
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY,
                        ResultSet.CLOSE_CURSORS_AT_COMMIT);
        final ResultSet first = ps.executeQuery();
        final Statement after =
                c.createStatement(
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY,
                        ResultSet.CLOSE_CURSORS_AT_COMMIT);
        final ResultSet skipped = after.executeQuery("SELECT 2");

        c.commit();

        assertTrue(first.isClosed());
        assertFalse("ISSUE-34: core's loop skips the next statement", skipped.isClosed());
    }

    /** Core keeps the connection after a lost socket and reconnects in a new session. */
    @Test
    public void aLostSocketReconnectsOnTheNextRequestInANewSession() throws SQLException {
        final Connection c = open();
        final Statement st = c.createStatement();
        final byte[] before = ((CUBRIDConnection) c).getUConnection().getSessionId();
        server.fault("kith:1", KitServer.Fault.LOST_ON_REQUEST);
        try {
            st.executeQuery("SELECT 1");
            fail("the request is lost");
        } catch (SQLException lost) {
            assertEquals(UErrorCode.ER_COMMUNICATION, lost.getErrorCode());
        }
        assertFalse(c.isClosed());

        st.executeQuery("SELECT 1").close();

        assertFalse(Arrays.equals(before, ((CUBRIDConnection) c).getUConnection().getSessionId()));
    }

    @Test
    public void aReconnectToAGoneBrokerFailsWithCoresConnectError() throws SQLException {
        final Connection c = open();
        final Statement st = c.createStatement();
        server.fault("kith:1", KitServer.Fault.REFUSE);
        for (int expected : new int[] {UErrorCode.ER_COMMUNICATION, UErrorCode.ER_CONNECTION}) {
            try {
                st.executeQuery("SELECT 1");
                fail("the broker is gone");
            } catch (SQLException gone) {
                assertEquals(expected, gone.getErrorCode());
            }
        }
        assertFalse(c.isClosed());
    }

    @Test
    public void aFetchTimeoutClosesTheConnection() throws SQLException {
        final Connection c = open();
        final ResultSet rs = c.createStatement().executeQuery("SELECT 1");
        assertTrue(rs.next());
        server.fault("kith:1", KitServer.Fault.NET_TIMEOUT_ON_FETCH);
        try {
            rs.next();
            fail("the fetch times out");
        } catch (SQLException timedOut) {
            assertEquals(UErrorCode.ER_NETWORK_TIMEOUT, timedOut.getErrorCode());
        }
        assertTrue(c.isClosed());
    }
}
