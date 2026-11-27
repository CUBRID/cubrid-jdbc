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

import cubrid.jdbc.jci.KitUConnection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

/**
 * A core {@link CUBRIDPreparedStatement} prepared on the kit server. Identity, closing and settings
 * stay core's; executing, binding (core binds into the server statement it lacks), batching and
 * results are replaced. Core's parameter-count checks are not modeled.
 */
public class KitPreparedStatement extends CUBRIDPreparedStatement {
    private final KitExec exec;
    private final String sql;
    private final int hold;
    private final Map<Integer, Object> binds = new TreeMap<Integer, Object>();
    private int batched;

    KitPreparedStatement(
            final KitConnection c,
            final String sql,
            final int type,
            final int concur,
            final int hold)
            throws SQLException {
        super(c);
        this.sql = sql;
        this.hold = hold;
        this.exec = new KitExec(c);
        final int code = c.kitU().send("PREPARE " + sql);
        if (code != 0) {
            throw new CUBRIDException(KitUConnection.error(code));
        }
    }

    @Override
    int getHoldability() {
        return holdsCursorsOverCommit(exec.conn.kitU(), hold)
                ? ResultSet.HOLD_CURSORS_OVER_COMMIT
                : ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    private String bound() {
        return binds.isEmpty() ? sql : sql + " " + binds.values();
    }

    @Override
    public boolean execute() throws SQLException {
        getMaxRows();
        return exec.run(bound(), false);
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        execute();
        return exec.resultSet();
    }

    @Override
    public int executeUpdate() throws SQLException {
        execute();
        return exec.updateCount();
    }

    @Override
    public boolean execute(final String other) throws SQLException {
        getMaxRows();
        return exec.run(other, false);
    }

    @Override
    public ResultSet executeQuery(final String other) throws SQLException {
        execute(other);
        return exec.resultSet();
    }

    @Override
    public int executeUpdate(final String other) throws SQLException {
        execute(other);
        return exec.updateCount();
    }

    @Override
    public void setInt(final int index, final int x) throws SQLException {
        bind(index, Integer.valueOf(x));
    }

    @Override
    public void setLong(final int index, final long x) throws SQLException {
        bind(index, Long.valueOf(x));
    }

    @Override
    public void setString(final int index, final String x) throws SQLException {
        bind(index, x);
    }

    @Override
    public void setObject(final int index, final Object x) throws SQLException {
        bind(index, x);
    }

    @Override
    public void setNull(final int index, final int sqlType) throws SQLException {
        bind(index, null);
    }

    private void bind(final int index, final Object x) throws SQLException {
        getMaxRows();
        binds.put(Integer.valueOf(index), x);
    }

    @Override
    public void clearParameters() throws SQLException {
        getMaxRows();
        binds.clear();
    }

    @Override
    public void addBatch() throws SQLException {
        getMaxRows();
        batched++;
    }

    @Override
    public void clearBatch() throws SQLException {
        getMaxRows();
        batched = 0;
    }

    @Override
    public int[] executeBatch() throws SQLException {
        getMaxRows();
        try {
            return exec.runBatch(sql, batched);
        } finally {
            batched = 0;
        }
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        getMaxRows();
        return exec.resultSet();
    }

    @Override
    public int getUpdateCount() throws SQLException {
        getMaxRows();
        return exec.updateCount();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        getMaxRows();
        return exec.moreResults();
    }

    @Override
    public void cancel() throws SQLException {
        getMaxRows();
        exec.conn.kitU().send("CANCEL");
    }

    @Override
    public String getQueryplan() throws SQLException {
        getMaxRows();
        return "plan";
    }

    @Override
    void complete() throws SQLException {
        exec.complete();
        super.complete();
    }
}
