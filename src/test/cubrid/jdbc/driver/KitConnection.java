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
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A core {@link CUBRIDConnection} on a {@link KitUConnection}. Only statement creation is replaced,
 * to make kit statements and register them where core registers its own; everything else, closing,
 * commit and rollback included, is core's code.
 */
public class KitConnection extends CUBRIDConnection {
    private final KitUConnection kitU;
    private final int serial;

    KitConnection(final KitUConnection u, final String url, final String user, final int serial) {
        super(u, url, user);
        this.kitU = u;
        this.serial = serial;
    }

    public KitUConnection kitU() {
        return kitU;
    }

    /** The order in which the kit server opened this connection, from 1. */
    public int serial() {
        return serial;
    }

    @Override
    public synchronized Statement createStatement(final int type, final int concur)
            throws SQLException {
        return createStatement(type, concur, getHoldability());
    }

    @Override
    public synchronized Statement createStatement(final int type, final int concur, final int hold)
            throws SQLException {
        getAutoCommit(); // core's closed check
        final KitStatement s = new KitStatement(this, type, concur, hold);
        addStatement(s);
        return s;
    }

    @Override
    protected PreparedStatement prepare(
            final String sql, final int type, final int concur, final int hold, final int keys)
            throws SQLException {
        getAutoCommit();
        final KitPreparedStatement ps = new KitPreparedStatement(this, sql, type, concur, hold);
        addStatement(ps);
        return ps;
    }

    @Override
    public synchronized CallableStatement prepareCall(final String sql) throws SQLException {
        getAutoCommit();
        final KitCallableStatement cs = new KitCallableStatement(this, sql);
        addStatement(cs);
        return cs;
    }

    @Override
    public String toString() {
        return "KitConnection#" + serial + "@" + kitU.getCasIp() + ":" + kitU.getCasPort();
    }
}
