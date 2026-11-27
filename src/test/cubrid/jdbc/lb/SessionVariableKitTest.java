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

package cubrid.jdbc.lb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.sql.Statement;
import org.junit.After;
import org.junit.Test;

/**
 * Where a statement naming a variable runs, on the kit cluster: on the write leg when that leg's
 * session may hold the variable, as core's one session would (review r5 m18, m19, m20).
 */
public class SessionVariableKitTest {
    private static final int ER_DUPLICATE_KEY = -670;
    private final LbKit kit = new LbKit(2);

    @After
    public void tearDown() {
        kit.close();
    }

    /**
     * r5 m18: creating a routine compiles its body and creates no session variable, so a read that
     * names one of its local variables is distributed as before.
     */
    @Test
    public void aRoutineBodyCreatesNoSessionVariable() throws Exception {
        final Statement st = kit.open("readWeight=slave:1").createStatement();
        st.execute(
                "CREATE OR REPLACE FUNCTION f_cnt(p INT) RETURN INT AS cnt INT; total INT; BEGIN"
                        + " SELECT COUNT(*) INTO cnt FROM t WHERE id = p; SELECT SUM(price) INTO"
                        + " total FROM t WHERE id = p; RETURN cnt + total; END;");

        st.executeQuery("SELECT id, total FROM orders WHERE id = 7").close();

        assertEquals(1, kit.runs(kit.ro(2), "SELECT id, total FROM orders WHERE id = 7"));
    }

    /**
     * r5 m19: CUBRID keeps what the statements before a failing one did, so a variable an INTO
     * there created stays in the write leg's session, where a read naming it then runs.
     */
    @Test
    public void aVariableCreatedBeforeAFailingStatementIsReadOnTheWriteLeg() throws Exception {
        final String failing = "SELECT 42 INTO v FROM db_root; INSERT INTO t VALUES (1)";
        kit.server.failStatement(failing, ER_DUPLICATE_KEY);
        final Statement st = kit.open("readWeight=slave:1").createStatement();
        try {
            st.execute(failing);
            fail("the INSERT fails");
        } catch (SQLException duplicate) {
            assertEquals(ER_DUPLICATE_KEY, duplicate.getErrorCode());
        }

        st.executeQuery("SELECT v FROM db_root").close();

        assertEquals(1, kit.runs(kit.rw(1), "SELECT v FROM db_root"));
    }

    /**
     * r5 m20: {@code DROP VARIABLE @v} changes the session as {@code DEALLOCATE VARIABLE @v} does,
     * so a TO_RO string holding it runs on the write leg, where {@code @v} lives.
     */
    @Test
    public void droppingASessionVariableIgnoresTheReadHint() throws Exception {
        final Statement st = kit.open("readWeight=slave:1").createStatement();
        st.execute("SET @v = 1");
        final String drop = "SELECT /*+ TO_RO */ 1 FROM db_root; DROP VARIABLE @v";

        st.execute(drop);

        assertEquals(1, kit.runs(kit.rw(1), drop));
        assertEquals(0, kit.runs(kit.ro(2), drop));
    }
}
