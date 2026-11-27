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
 * A core {@link CUBRIDCallableStatement} prepared on the kit server. It keeps what the real one
 * answers by itself: identity ({@code getConnection} after {@code setReportedConnection}), closing,
 * holdability (never held over commit) and the refusal of batches. Running the CALL, SQL given to
 * it, binding, OUT values and results go to the kit.
 */
public class KitCallableStatement extends CUBRIDCallableStatement {
    private final KitExec exec;
    private final String sql;
    private final Map<Integer, Object> binds = new TreeMap<Integer, Object>();

    KitCallableStatement(final KitConnection c, final String sql) throws SQLException {
        super(c);
        this.sql = sql;
        this.exec = new KitExec(c);
        final int code = c.kitU().send("PREPARE " + sql);
        if (code != 0) {
            throw new CUBRIDException(KitUConnection.error(code));
        }
    }

    private String bound() {
        return binds.isEmpty() ? sql : sql + " " + binds.values();
    }

    @Override
    public boolean execute() throws SQLException {
        getMaxRows();
        return exec.run(bound(), true);
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        execute();
        exec.rowsFor(sql);
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
    public void registerOutParameter(final int index, final int sqlType) throws SQLException {
        getMaxRows();
    }

    /** An OUT value derived from its position, the same on every node. */
    @Override
    public int getInt(final int index) throws SQLException {
        getMaxRows();
        return index * 10;
    }

    @Override
    public String getString(final int index) throws SQLException {
        return String.valueOf(getInt(index));
    }

    @Override
    public Object getObject(final int index) throws SQLException {
        return Integer.valueOf(getInt(index));
    }

    @Override
    public boolean wasNull() throws SQLException {
        getMaxRows();
        return false;
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
