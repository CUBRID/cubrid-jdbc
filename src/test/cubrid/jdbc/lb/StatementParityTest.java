/*
 * Copyright (C) 2008 Search Solution Corporation.
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.CUBRIDJDBCErrorCode;
import cubrid.jdbc.driver.CUBRIDStatement;
import cubrid.jdbc.driver.KitServer;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Test;

/**
 * A logical statement answers as core's statement does after its connection closes or another
 * statement's failover or failback replaces the leg it ran on (review r5 m2, m21, m22, m23).
 */
public class StatementParityTest {
    private final LbKit kit = new LbKit(2);
    private final ExecutorService timeouts = Executors.newSingleThreadExecutor();

    @After
    public void tearDown() {
        kit.close();
        timeouts.shutdownNow();
    }

    /** r5 m21 (a): core's isClosed() is true once the connection is, even for a cached PS. */
    @Test
    public void aCachedPreparedStatementIsClosedWithItsConnection() throws Exception {
        final Connection conn = kit.open("readWeight=slave:1&usePreparedStmtCache=true");
        final PreparedStatement ps = conn.prepareStatement("SELECT a FROM t WHERE p = ?");
        ps.setInt(1, 1);
        ps.executeQuery().close();
        conn.close();

        assertTrue(ps.isClosed());
        assertFails(CUBRIDJDBCErrorCode.prepared_statement_closed, () -> ps.setInt(1, 2));
        assertFails(CUBRIDJDBCErrorCode.prepared_statement_closed, () -> ps.executeQuery());
    }

    /**
     * r5 m21 (b): core's network timeout closes the write leg, and with it the session; the
     * statements made before are closed as core's are.
     */
    @Test
    public void statementsAreClosedWhenATimeoutClosesTheSession() throws Exception {
        final Connection conn = kit.open("readWeight=slave:1");
        final Statement st = conn.createStatement();
        final PreparedStatement ps = conn.prepareStatement("UPDATE t SET a = ?");
        conn.setNetworkTimeout(timeouts, 1000);
        kit.server.fault(kit.rw(1), KitServer.Fault.DIAL_TIMEOUT);
        try {
            st.executeUpdate("UPDATE t SET a = 1");
            fail("the write times out");
        } catch (SQLException timedOut) {
            assertTrue(conn.isClosed());
        }

        assertTrue(st.isClosed());
        assertTrue(ps.isClosed());
        assertFails(
                CUBRIDJDBCErrorCode.statement_closed, () -> st.executeUpdate("UPDATE t SET a = 2"));
        assertFails(CUBRIDJDBCErrorCode.prepared_statement_closed, () -> ps.executeUpdate());
    }

    /**
     * r5 m2, m22: another statement's failover closed the leg this one last ran on. Core's
     * statement stays open there, so cancel() is an idle cancel and getQueryplan() has no plan.
     */
    @Test
    public void cancelAndPlanAfterTheLegIsReplacedAnswerAsAnIdleCoreStatement() throws Exception {
        final Connection conn = kit.open("readWeight=slave:1");
        final Statement read = conn.createStatement();
        final Statement write = conn.createStatement();
        read.executeQuery("SELECT a FROM t").close();
        write.executeUpdate("UPDATE t SET a = 1");

        kit.server.fault(kit.ro(2), KitServer.Fault.REFUSE);
        conn.createStatement().executeQuery("SELECT a FROM t").close(); // the read leg moves
        kit.server.fault(kit.rw(1), KitServer.Fault.REFUSE);
        try {
            conn.createStatement().executeUpdate("UPDATE t SET a = 2");
        } catch (SQLException moved) {
            // the write leg moves; the write is not replayed
        }

        for (Statement st : new Statement[] {read, write}) {
            st.cancel();
            assertEquals("", ((CUBRIDStatement) st).getQueryplan());
            assertTrue(!st.isClosed());
        }
    }

    /** r5 m23: core's generated-keys result of a CALL has no statement. */
    @Test
    public void generatedKeysOfACallHaveNoStatement() throws Exception {
        final Connection conn = kit.open("readWeight=slave:1");
        final CallableStatement cs = conn.prepareCall("CALL p(?)");
        cs.setInt(1, 1);
        cs.execute();

        assertNull(cs.getGeneratedKeys().getStatement());
    }

    interface Call {
        void run() throws SQLException;
    }

    private static void assertFails(final int code, final Call call) {
        try {
            call.run();
            fail("expected " + code);
        } catch (SQLException e) {
            assertEquals(e.getMessage(), code, e.getErrorCode());
        }
    }
}
