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

package cubrid.jdbc.lb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.KitServer;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Test;

/**
 * LB on the kit cluster. The invariants hold in every scenario. A defect found but not fixed yet is
 * pinned with {@link #knownDefect}: such a test passes while the defect is there and fails once it
 * is gone, so the fix turns it into a plain test. None is pinned today.
 */
public class LbKitTest {
    private final LbKit kit = new LbKit(2);

    @After
    public void tearDown() {
        kit.close();
    }

    interface Check {
        void run() throws Exception;
    }

    /** Passes while {@code check} fails; fails once the defect {@code issue} is fixed. */
    static void knownDefect(final String issue, final Check check) throws Exception {
        try {
            check.run();
        } catch (AssertionError stillThere) {
            return;
        } catch (SQLException stillThere) {
            return;
        }
        fail(issue + " no longer fails: move this check out of the known defects");
    }

    static List<Integer> rows(final ResultSet rs) throws SQLException {
        final List<Integer> out = new ArrayList<Integer>();
        while (rs.next()) {
            out.add(rs.getInt(1));
        }
        return out;
    }

    @Test
    public void readsRunOnTheSlaveWritesOnTheMasterAndCloseLeavesNoLeg() throws Exception {
        final Connection conn = kit.open("readWeight=slave:1");
        final Statement st = conn.createStatement();

        assertEquals(3, rows(st.executeQuery("SELECT a FROM t")).size());
        assertEquals(1, st.executeUpdate("UPDATE t SET a = 1"));
        assertEquals(Collections.<String>emptyList(), kit.legLeaks());

        conn.close();

        assertEquals(Collections.<String>emptyList(), kit.legLeaks());
        assertEquals(Collections.emptyList(), kit.server.live());
        boolean readOnSlave = false;
        boolean writeOnMaster = false;
        for (KitServer.Request r : kit.server.requests()) {
            readOnSlave |= r.what.equals("EXECUTE SELECT a FROM t") && r.endpoint.equals(kit.ro(2));
            writeOnMaster |=
                    r.what.equals("EXECUTE UPDATE t SET a = 1") && r.endpoint.equals(kit.rw(1));
        }
        assertTrue(kit.server.requests().toString(), readOnSlave && writeOnMaster);
    }

    @Test
    public void aSlaveThatGoesAwayDoesNotChangeTheResultOfReads() throws Exception {
        final Connection conn = kit.open("readWeight=slave:1");
        final Statement st = conn.createStatement();
        final List<Integer> before = rows(st.executeQuery("SELECT a FROM t"));

        kit.server.fault(kit.ro(2), KitServer.Fault.REFUSE);

        assertEquals(before, rows(st.executeQuery("SELECT a FROM t")));
        assertEquals(before, rows(st.executeQuery("SELECT a FROM t")));
        assertEquals(Collections.<String>emptyList(), kit.legLeaks());
    }

    @Test
    public void aWriteLostOnTheMasterIsReportedAndNotRunAgain() throws Exception {
        final Connection conn = kit.open("readWeight=slave:1");
        final Statement st = conn.createStatement();
        st.executeQuery("SELECT a FROM t").close();

        kit.server.fault(kit.rw(1), KitServer.Fault.LOST_ON_REQUEST);
        try {
            st.executeUpdate("UPDATE t SET a = 2");
            fail("a write the master did not answer is reported");
        } catch (SQLException lost) {
            assertEquals(cubrid.jdbc.jci.UErrorCode.ER_COMMUNICATION, lost.getErrorCode());
        }
        st.executeUpdate("UPDATE t SET a = 3");

        assertEquals(
                Collections.<String>emptyList(),
                kit.unrequestedExecutions(
                        Arrays.asList("UPDATE t SET a = 2", "UPDATE t SET a = 3")));
        assertEquals(Collections.<String>emptyList(), kit.legLeaks());
    }

    /**
     * r5 M3: without the class db_user as its target, {@code CALL login(..)} runs a stored
     * procedure named login, once, where the application ran it. A login on db_user reaches the
     * read leg.
     */
    @Test
    public void aProcedureNamedLoginRunsOnceAndALoginReachesTheReadLeg() throws Exception {
        final Connection conn = kit.open("readWeight=slave:1");
        final Statement st = conn.createStatement();
        st.execute("CALL login('alice')");
        st.execute("CALL login('u', 'pw') ON CLASS db_user");
        st.execute("CALL login(CLASS db_user, 'v', 'pw')");
        st.execute("CALL dba.login('w', 'pw') ON CLASS db_user");
        rows(st.executeQuery("SELECT a FROM t"));

        assertEquals(
                Collections.<String>emptyList(),
                kit.unrequestedExecutions(Arrays.asList("CALL login('alice')")));
        assertEquals(0, kit.runs(kit.ro(2), "CALL login('alice')"));
        assertEquals(1, kit.runs(kit.ro(2), "CALL login('u', 'pw') ON CLASS db_user"));
        assertEquals(1, kit.runs(kit.ro(2), "CALL login(CLASS db_user, 'v', 'pw')"));
        assertEquals(1, kit.runs(kit.ro(2), "CALL dba.login('w', 'pw') ON CLASS db_user"));
    }

    /**
     * r5 M2: a read leg closed by a fetch timeout is reopened on the next read. When its node stays
     * silent the reopen fails, the node is listed unreachable and the read moves as a failed read
     * does; core reads on the master and never sees the slave. Later reads do not dial the node.
     */
    @Test
    public void aSilentReadNodeIsLeftAfterOneFailedReopen() throws Exception {
        final Connection conn = kit.open("readWeight=slave:1");
        conn.setNetworkTimeout(Executors.newSingleThreadExecutor(), 1000);
        final Statement st = conn.createStatement();
        final List<Integer> expected = rows(st.executeQuery("SELECT a FROM t"));

        final ResultSet rs = st.executeQuery("SELECT a FROM t");
        assertTrue(rs.next());
        kit.server.fault(kit.ro(2), KitServer.Fault.DIAL_TIMEOUT);
        try {
            rows(rs);
            fail("a cursor on the node that went silent fails, as documented in spec 15.5.5");
        } catch (SQLException documented) {
            // the read in flight is lost
        }

        assertEquals(expected, rows(st.executeQuery("SELECT a FROM t")));
        final int dials = kit.server.dials(kit.ro(2));
        assertEquals(expected, rows(st.executeQuery("SELECT a FROM t")));
        assertEquals("the silent node is not dialed again", dials, kit.server.dials(kit.ro(2)));
        assertEquals(Collections.<String>emptyList(), kit.legLeaks());
    }

    /**
     * r5 M2, the other way a read leg goes: a setter that times out on it drops it. The next read
     * reopens it; when its node stays silent the read moves, as above.
     */
    @Test
    public void aReadLegDroppedByASetterMovesWhenItsNodeStaysSilent() throws Exception {
        final Connection conn = kit.open("readWeight=slave:1");
        conn.setNetworkTimeout(Executors.newSingleThreadExecutor(), 1000);
        final Statement st = conn.createStatement();
        final List<Integer> expected = rows(st.executeQuery("SELECT a FROM t"));

        kit.server.fault(kit.ro(2), KitServer.Fault.DIAL_TIMEOUT);
        conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

        assertEquals(expected, rows(st.executeQuery("SELECT a FROM t")));
        final int dials = kit.server.dials(kit.ro(2));
        assertEquals(expected, rows(st.executeQuery("SELECT a FROM t")));
        assertEquals("the silent node is not dialed again", dials, kit.server.dials(kit.ro(2)));
        assertEquals(Collections.<String>emptyList(), kit.legLeaks());
    }

    /**
     * r5 m16: an open result of a CALL holds RW failback until it closes. Core's commit leaves it
     * open here (ISSUE-34 skips the CALL after a removed PreparedStatement).
     */
    @Test
    public void anOpenCallResultHoldsFailbackUntilItCloses() throws Exception {
        final LoadBalanceConnection conn =
                kit.open("readWeight=slave:1", "writeFailbackProbeIntervalMs", Integer.valueOf(0));
        final Statement st = conn.createStatement();
        kit.server.fault(kit.rw(1), KitServer.Fault.REFUSE);
        try {
            st.executeUpdate("UPDATE t SET a = 1");
        } catch (SQLException moved) {
            // the write leg moves to node 2
        }
        st.executeUpdate("UPDATE t SET a = 1");
        assertEquals(kit.rw(2), conn.getCurrentRwEndpoint().getId());
        kit.brokerBack(kit.rw(1));

        conn.setAutoCommit(false);
        final PreparedStatement before =
                conn.prepareStatement(
                        "UPDATE t SET a = 2",
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY,
                        ResultSet.CLOSE_CURSORS_AT_COMMIT);
        final CallableStatement cs = conn.prepareCall("CALL q()");
        before.executeUpdate();
        final ResultSet call = cs.executeQuery();
        conn.commit();

        assertTrue("core's commit left the cursor open", !call.isClosed());
        assertEquals(
                "failback waits for the cursor", kit.rw(2), conn.getCurrentRwEndpoint().getId());
        assertEquals(3, rows(call).size());

        call.close();
        conn.commit();
        assertEquals(kit.rw(1), conn.getCurrentRwEndpoint().getId());
    }

    /**
     * r5 m17: reads that share the write connection (a master-role session, or one whose read
     * brokers all failed) prepare there. A prepare that times out fails as core's does, with -21031
     * from prepareStatement, and the session is closed.
     */
    @Test
    public void aPrepareThatTimesOutOnTheWriteConnectionFailsAsOnCore() throws Exception {
        assertPrepareTimesOutAsOnCore(kit.open("readWeight=master:1"));

        kit.server.fault(kit.ro(1), KitServer.Fault.REFUSE);
        kit.server.fault(kit.ro(2), KitServer.Fault.REFUSE);
        kit.brokerBack(kit.rw(1));
        assertPrepareTimesOutAsOnCore(kit.open("readWeight=slave:1"));
    }

    private void assertPrepareTimesOutAsOnCore(final Connection conn) throws Exception {
        conn.setNetworkTimeout(Executors.newSingleThreadExecutor(), 1000);
        kit.server.fault(kit.rw(1), KitServer.Fault.DIAL_TIMEOUT);
        try {
            conn.prepareStatement("SELECT a FROM t");
            fail("core reports the timeout from prepareStatement");
        } catch (SQLException timedOut) {
            assertEquals(cubrid.jdbc.jci.UErrorCode.ER_NETWORK_TIMEOUT, timedOut.getErrorCode());
        }
        assertTrue("core's timeout closed the connection", conn.isClosed());
    }
}
