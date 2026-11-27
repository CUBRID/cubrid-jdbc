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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.driver.KitServer;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * LB runs by itself only the session statements of the closed list (spec 15.2.3): on the read leg
 * after the write leg ran them, and on every leg it opens later. A statement off the list runs
 * where the application sent it, once (review r5, recommendation 3, decided 2026-11-27: keep the
 * replay, pin the list).
 */
public class SessionReplayTest {
    private static final List<String> LISTED =
            Arrays.asList(
                    "SET NAMES utf8",
                    "SET TIMEZONE 'Asia/Seoul'",
                    "SET SYSTEM PARAMETERS 'default_week_format=1'",
                    "SET TRANSACTION LOCK TIMEOUT 5",
                    "SET TRACE ON",
                    "CALL login('u', 'pw') ON CLASS db_user");
    private static final List<String> OFF_LIST =
            Arrays.asList(
                    "SET @v = 1",
                    "CALL p()",
                    "SET SYSTEM PARAMETERS 'no_such_param=1'",
                    "SET @collect_exec_stats = 1");
    private static final String WRITE = "UPDATE t SET a = 1";

    private final LbKit kit = new LbKit(3);

    @After
    public void tearDown() {
        kit.close();
    }

    @Test
    public void onlyListedStatementsRunAgainOnTheLegsLbOpens() throws Exception {
        final LoadBalanceConnection conn = kit.open("readWeight=slave:1");
        final Statement st = conn.createStatement();
        final List<String> sent = new ArrayList<String>();
        run(st, LISTED, sent);
        read(st, sent);
        final String home = conn.getCurrentRoEndpoint().getId();

        kit.server.fault(home, KitServer.Fault.REFUSE);
        read(st, sent);
        final String sibling = conn.getCurrentRoEndpoint().getId();
        kit.server.fault(kit.rw(1), KitServer.Fault.REFUSE);
        write(st, sent);
        assertEquals(kit.rw(2), conn.getCurrentRwEndpoint().getId());

        run(st, OFF_LIST, sent);
        kit.server.fault(kit.rw(2), KitServer.Fault.REFUSE);
        kit.brokerBack(kit.rw(1));
        write(st, sent);
        read(st, sent);

        assertEquals(Collections.<String>emptyList(), kit.unrequestedExecutions(sent));
        for (String listed : LISTED) {
            assertEquals(listed, 1, kit.runs(sibling, listed));
            assertEquals(listed, 1, kit.runs(kit.rw(2), listed));
        }
        for (String offList : OFF_LIST) {
            assertEquals(offList, 1, kit.runs(kit.rw(2), offList));
            assertEquals(offList, 0, kit.runs(kit.rw(1), offList));
        }
        assertEquals(Collections.<String>emptyList(), kit.legLeaks());
    }

    private static void run(final Statement st, final List<String> sqls, final List<String> sent)
            throws SQLException {
        for (String sql : sqls) {
            st.execute(sql);
            sent.add(sql);
        }
    }

    private static void read(final Statement st, final List<String> sent) throws SQLException {
        assertTrue(LbKitTest.rows(st.executeQuery("SELECT a FROM t")).size() > 0);
        sent.add("SELECT a FROM t");
    }

    /** A write the failed write leg did not answer is reported; the application sends it again. */
    private static void write(final Statement st, final List<String> sent) throws SQLException {
        try {
            st.executeUpdate(WRITE);
        } catch (SQLException moved) {
            // the write leg moves; the write is not replayed
        }
        sent.add(WRITE);
        st.executeUpdate(WRITE);
        sent.add(WRITE);
    }
}
