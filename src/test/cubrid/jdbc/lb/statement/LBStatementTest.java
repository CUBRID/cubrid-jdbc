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

package cubrid.jdbc.lb.statement;

import static org.junit.Assert.*;

import cubrid.jdbc.lb.LbJDBCErrorCode;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.TestConfigHelper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.Before;
import org.junit.Test;

public class LBStatementTest {

    private LoadBalanceConnection conn;

    @Before
    public void setUp() throws Exception {
        conn = new LoadBalanceConnection(TestConfigHelper.emptyConfig());
    }

    @Test
    public void assertCreateStatementReturnsLBStatement() throws Exception {
        Statement stmt = conn.createStatement();
        assertTrue(stmt instanceof LBStatement);
        assertFalse(stmt.isClosed());
    }

    @Test
    public void assertCreateStatementDefaultResultSetProperties() throws Exception {
        Statement stmt = conn.createStatement();
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, stmt.getResultSetType());
        assertEquals(ResultSet.CONCUR_READ_ONLY, stmt.getResultSetConcurrency());
        assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, stmt.getResultSetHoldability());
    }

    @Test
    public void assertCreateStatementWithTypeAndConcurrency() throws Exception {
        Statement stmt =
                conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
        assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, stmt.getResultSetType());
        assertEquals(ResultSet.CONCUR_UPDATABLE, stmt.getResultSetConcurrency());
    }

    @Test
    public void assertCreateStatementWithThreeArgs() throws Exception {
        Statement stmt =
                conn.createStatement(
                        ResultSet.TYPE_SCROLL_SENSITIVE,
                        ResultSet.CONCUR_UPDATABLE,
                        ResultSet.CLOSE_CURSORS_AT_COMMIT);
        assertEquals(ResultSet.TYPE_SCROLL_SENSITIVE, stmt.getResultSetType());
        assertEquals(ResultSet.CONCUR_UPDATABLE, stmt.getResultSetConcurrency());
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, stmt.getResultSetHoldability());
    }

    @Test
    public void assertGetConnectionReturnsParent() throws Exception {
        Statement stmt = conn.createStatement();
        Connection parent = stmt.getConnection();
        assertSame(conn, parent);
    }

    @Test
    public void assertStatementCloseAndIsClosed() throws Exception {
        Statement stmt = conn.createStatement();
        assertFalse(stmt.isClosed());
        stmt.close();
        assertTrue(stmt.isClosed());
    }

    @Test
    public void assertClosedStatementThrowsSQLException() throws Exception {
        Statement stmt = conn.createStatement();
        stmt.close();
        try {
            stmt.executeQuery("SELECT 1");
            fail("Expected SQLException on closed statement");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("closed"));
        }
    }

    @Test
    public void assertMaxRowsProperty() throws Exception {
        Statement stmt = conn.createStatement();
        assertEquals(0, stmt.getMaxRows());
        stmt.setMaxRows(100);
        assertEquals(100, stmt.getMaxRows());
    }

    @Test
    public void assertQueryTimeoutProperty() throws Exception {
        Statement stmt = conn.createStatement();
        assertEquals(0, stmt.getQueryTimeout());
        stmt.setQueryTimeout(30);
        assertEquals(30, stmt.getQueryTimeout());
    }

    @Test
    public void assertFetchSizeProperty() throws Exception {
        Statement stmt = conn.createStatement();
        assertEquals(0, stmt.getFetchSize());
        stmt.setFetchSize(50);
        assertEquals(50, stmt.getFetchSize());
    }

    @Test
    public void assertFetchDirectionProperty() throws Exception {
        Statement stmt = conn.createStatement();
        assertEquals(ResultSet.FETCH_FORWARD, stmt.getFetchDirection());
        stmt.setFetchDirection(ResultSet.FETCH_REVERSE);
        assertEquals(ResultSet.FETCH_REVERSE, stmt.getFetchDirection());
    }

    @Test
    public void assertPoolableUnsupportedLikeCubridStatement() throws Exception {
        Statement stmt = conn.createStatement();
        try {
            stmt.isPoolable();
            fail("expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getCause() instanceof UnsupportedOperationException);
        }
        try {
            stmt.setPoolable(true);
            fail("expected SQLException");
        } catch (SQLException e) {
            assertTrue(e.getCause() instanceof UnsupportedOperationException);
        }
    }

    @Test
    public void assertCloseOnCompletionProperty() throws Exception {
        Statement stmt = conn.createStatement();
        assertFalse(stmt.isCloseOnCompletion());
        stmt.closeOnCompletion();
        assertTrue(stmt.isCloseOnCompletion());
    }

    @Test
    public void assertExecuteQueryOnUnboundConnectionThrows() throws Exception {
        try {
            conn.createStatement().executeQuery("SELECT 1");
            fail("expected SQLException: this connection was never bound to a session");
        } catch (SQLException expected) {
            assertEquals(LbJDBCErrorCode.lb_internal_state, expected.getErrorCode());
        }
    }

    @Test
    public void assertExecuteUpdateOnUnboundConnectionThrows() throws Exception {
        try {
            conn.createStatement().executeUpdate("INSERT INTO t VALUES(1)");
            fail("expected SQLException: this connection was never bound to a session");
        } catch (SQLException expected) {
            assertEquals(LbJDBCErrorCode.lb_internal_state, expected.getErrorCode());
        }
    }

    @Test
    public void assertExecuteOnUnboundConnectionThrows() throws Exception {
        try {
            conn.createStatement().execute("SELECT 1");
            fail("expected SQLException: this connection was never bound to a session");
        } catch (SQLException expected) {
            assertEquals(LbJDBCErrorCode.lb_internal_state, expected.getErrorCode());
        }
    }

    @Test(expected = SQLException.class)
    public void assertCreateStatementOnClosedConnectionThrows() throws Exception {
        conn.close();
        conn.createStatement();
    }

    @Test
    public void assertUnwrapToStatement() throws Exception {
        Statement stmt = conn.createStatement();
        assertTrue(stmt.isWrapperFor(Statement.class));
        Statement unwrapped = stmt.unwrap(Statement.class);
        assertNotNull(unwrapped);
    }
}
