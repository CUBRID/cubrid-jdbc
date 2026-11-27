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
import cubrid.jdbc.jci.UUType;
import java.sql.SQLException;

/**
 * Rows of a kit query, in core's server-less result set class. The first row comes with the execute
 * reply; each later row is a FETCH request, so a fault can strike while the application reads, as a
 * network timeout does on core.
 *
 * <p>Kit boundary: this is not a {@code CUBRIDResultSet} (that class needs a server statement), so
 * LB's stamping of the reported statement on core result sets is not exercised here.
 */
public final class KitResultSet extends CUBRIDResultSetWithoutQuery {
    private final KitUConnection socket;
    private int read;

    private KitResultSet(final KitUConnection socket) {
        super(1, new int[] {UUType.U_TYPE_INT}, new String[] {"c1"}, new boolean[] {true}, null);
        this.socket = socket;
    }

    /** Three rows derived from the text alone, the same on every node. */
    static KitResultSet of(final KitUConnection socket, final String sql) throws SQLException {
        final KitResultSet rs = new KitResultSet(socket);
        final int base = (sql.hashCode() & 0x7fffffff) % 1000;
        for (int i = 0; i < 3; i++) {
            rs.addTuple(new Object[] {Integer.valueOf(base + i)});
        }
        return rs;
    }

    @Override
    public synchronized boolean next() throws SQLException {
        if (!isClosed() && read++ > 0) {
            final int code = socket.send("FETCH");
            if (code != 0) {
                throw new CUBRIDException(KitUConnection.error(code));
            }
        }
        return super.next();
    }

    void closeQuietly() {
        try {
            close();
        } catch (SQLException alreadyGone) {
            // nothing left to release
        }
    }
}
