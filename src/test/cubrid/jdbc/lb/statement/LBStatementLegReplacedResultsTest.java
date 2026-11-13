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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import cubrid.jdbc.lb.LbTestAccess;
import cubrid.jdbc.lb.LbTestSettings;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.route.Router.RouteTarget;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.Test;

/**
 * m23: a failover or failback closes the physical connection it leaves, and core closes that
 * connection's statements with it. Core's own reconnect keeps its statements open, so their results
 * stay readable; an LB statement must answer from what its last execution left.
 */
public class LBStatementLegReplacedResultsTest {

    @Test
    public void resultsStayReadableAfterTheLegTheyRanOnIsReplaced() throws Exception {
        LoadBalanceConnection conn =
                LbTestAccess.newLoadBalanceConnection(LbTestSettings.defaults());
        LBStatement st = (LBStatement) conn.createStatement();
        final CoreLikeStmt rw = new CoreLikeStmt();
        st.setStatementProvider(
                new LBStatement.PhysicalStmtProvider() {
                    public Statement getStatement(final RouteTarget target, final String rawSql) {
                        return rw;
                    }
                });

        assertEquals(5, st.executeUpdate("UPDATE t SET a = 1"));
        rw.close(); // what closing the replaced leg's connection does to its statements

        assertEquals(5, st.getUpdateCount());
        assertSame(rw.keys, st.getGeneratedKeys());
        assertNull(st.getResultSet());
        assertNull(st.getWarnings());
        st.clearWarnings();
        assertFalse(st.getMoreResults());
        assertEquals(-1, st.getUpdateCount()); // core's getMoreResults ends the results
        assertFalse(st.isClosed());
    }

    /** Answers as core's CUBRIDStatement does: checkIsOpen() fails every getter once closed. */
    private static final class CoreLikeStmt extends FakePhysicalStatement {
        final ResultSet keys = emptyResultSet();
        private boolean closed;
        private int updateCount = -1;

        @Override
        protected Object dispatch(final String name, final Object[] args) throws SQLException {
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return Boolean.valueOf(closed);
            }
            if (closed) {
                throw new SQLException("statement is closed", null, -21102);
            }
            if ("executeUpdate".equals(name)) {
                updateCount = 5;
                return Integer.valueOf(5);
            }
            if ("getUpdateCount".equals(name)) {
                return Integer.valueOf(updateCount);
            }
            if ("getGeneratedKeys".equals(name)) {
                return keys;
            }
            return null;
        }
    }

    private static ResultSet emptyResultSet() {
        return (ResultSet)
                Proxy.newProxyInstance(
                        ResultSet.class.getClassLoader(),
                        new Class[] {ResultSet.class},
                        new InvocationHandler() {
                            public Object invoke(
                                    final Object proxy, final Method method, final Object[] a) {
                                Class<?> rt = method.getReturnType();
                                if (rt.equals(Boolean.TYPE)) {
                                    return Boolean.FALSE;
                                }
                                if (rt.equals(Integer.TYPE)) {
                                    return Integer.valueOf(0);
                                }
                                return null;
                            }
                        });
    }
}
