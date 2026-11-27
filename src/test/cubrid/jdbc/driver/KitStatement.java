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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A core {@link CUBRIDStatement} whose server requests go to the kit. Identity, closing, settings
 * and the closed checks stay core's; only executing, its results and cancel are replaced.
 */
public class KitStatement extends CUBRIDStatement {
    private final KitExec exec;
    private final int hold;
    private final List<String> batch = new ArrayList<String>();

    KitStatement(final KitConnection c, final int type, final int concur, final int hold) {
        super(c, type, concur);
        this.hold = hold;
        this.exec = new KitExec(c);
    }

    /** Core's rule, applied to the holdability this statement was created with. */
    @Override
    int getHoldability() {
        return holdsCursorsOverCommit(exec.conn.kitU(), hold)
                ? ResultSet.HOLD_CURSORS_OVER_COMMIT
                : ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean execute(final String sql) throws SQLException {
        getMaxRows(); // core's closed check
        return exec.run(sql, false);
    }

    @Override
    public ResultSet executeQuery(final String sql) throws SQLException {
        execute(sql);
        return exec.resultSet();
    }

    @Override
    public int executeUpdate(final String sql) throws SQLException {
        execute(sql);
        return exec.updateCount();
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
    public void addBatch(final String sql) throws SQLException {
        getMaxRows();
        batch.add(sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        getMaxRows();
        batch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        getMaxRows();
        try {
            return exec.runBatch(String.join("; ", batch), batch.size());
        } finally {
            batch.clear();
        }
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
