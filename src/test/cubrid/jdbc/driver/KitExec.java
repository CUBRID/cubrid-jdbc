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

package cubrid.jdbc.driver;

import cubrid.jdbc.jci.KitUConnection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/** The one place a kit statement sends SQL and keeps what the server answered. */
final class KitExec {
    final KitConnection conn;
    private KitResultSet current;
    private int updateCount = -1;

    KitExec(final KitConnection conn) {
        this.conn = conn;
    }

    /**
     * Sends {@code sql}. In autocommit the server ends a statement's transaction itself, except a
     * CALL's, which stays open until the next request, as on a CUBRID CAS.
     *
     * @return whether it produced a result set
     */
    boolean run(final String sql, final boolean call) throws SQLException {
        complete();
        final KitUConnection socket = conn.kitU();
        final int code = socket.send((call ? "CALL " : "EXECUTE ") + sql);
        if (code != 0) {
            throw new CUBRIDException(KitUConnection.error(code));
        }
        socket.setActive(!socket.getAutoCommit() || call);
        if (isQuery(sql)) {
            current = KitResultSet.of(socket, sql);
            updateCount = -1;
            return true;
        }
        updateCount = call ? -1 : 1;
        return false;
    }

    int[] runBatch(final String sql, final int rows) throws SQLException {
        complete();
        final KitUConnection socket = conn.kitU();
        final int code = socket.send("BATCH " + rows + " " + sql);
        if (code != 0) {
            throw new CUBRIDException(KitUConnection.error(code));
        }
        socket.setActive(!socket.getAutoCommit());
        final int[] counts = new int[rows];
        java.util.Arrays.fill(counts, 1);
        return counts;
    }

    ResultSet resultSet() {
        return current;
    }

    /** The rows a CALL run by executeQuery returns, as core's CallableStatement gives one. */
    void rowsFor(final String sql) throws SQLException {
        if (current == null) {
            current = KitResultSet.of(conn.kitU(), sql);
        }
    }

    int updateCount() {
        return updateCount;
    }

    boolean moreResults() {
        complete();
        updateCount = -1;
        return false;
    }

    /** What core's statement completion does to the result: closes it. */
    void complete() {
        if (current != null) {
            current.closeQuietly();
            current = null;
        }
    }

    static boolean isQuery(final String sql) {
        final String s = sql.trim().toUpperCase(Locale.ROOT);
        return s.startsWith("SELECT") || s.startsWith("WITH") || s.startsWith("(");
    }
}
