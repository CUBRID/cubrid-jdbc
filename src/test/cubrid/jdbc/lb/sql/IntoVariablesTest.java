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

package cubrid.jdbc.lb.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class IntoVariablesTest {

    private static Set<String> names(final String... names) {
        return new HashSet<String>(Arrays.asList(names));
    }

    @Test
    public void intoAndToListsAreAssigned() {
        assertEquals(names("SET1"), IntoVariables.assignedBy("select x into set1 from xoo"));
        assertEquals(names("I1"), IntoVariables.assignedBy("SELECT i TO i1 FROM n;"));
        assertEquals(
                names("A", "B"),
                IntoVariables.assignedBy("SELECT x, y INTO :a, :b FROM t WHERE k = 1"));
        assertEquals(names("V"), IntoVariables.assignedBy("select 1 into /* c */ v"));
        assertEquals(names("Q"), IntoVariables.assignedBy("select 1 into \"q\" from t"));
        assertEquals(
                names("W"),
                IntoVariables.assignedBy("WITH c AS (SELECT 1 a) SELECT a INTO w FROM c"));
        assertEquals(
                names("V1", "V2"),
                IntoVariables.assignedBy("select 1 into v1; select 2 to v2 from db_root"));
    }

    @Test
    public void otherStatementsWithAnIntoClauseAssign() {
        assertEquals(
                names("OIDX"),
                IntoVariables.assignedBy("insert into tree2(a, text) values (1, 'A') into oidx;"));
        assertEquals(names("O"), IntoVariables.assignedBy("INSERT t VALUES (1) INTO :o"));
        assertEquals(names("R"), IntoVariables.assignedBy("REPLACE INTO t VALUES (1) TO r"));
        assertEquals(names("RES"), IntoVariables.assignedBy("CALL f(1) INTO :res"));
        assertEquals(names("E"), IntoVariables.assignedBy("EVALUATE 1 + 2 INTO e"));
        assertEquals(names("LVL"), IntoVariables.assignedBy("GET OPTIMIZATION LEVEL INTO :lvl"));
    }

    @Test
    public void dropVariableNamesWhatItRemoves() {
        assertEquals(names("V1", "V2"), IntoVariables.droppedBy("DROP VARIABLE v1, v2;"));
        assertEquals(names("Q"), IntoVariables.droppedBy("drop variable \"q\""));
        // @name is a session variable: an INTO/TO variable of the same name stays.
        assertEquals(Collections.emptySet(), IntoVariables.droppedBy("DEALLOCATE VARIABLE @v"));
        assertEquals(Collections.emptySet(), IntoVariables.droppedBy("DROP VARIABLE @v, @w"));
        assertEquals(Collections.emptySet(), IntoVariables.droppedBy("DROP TABLE v"));
        assertEquals(names("A"), IntoVariables.droppedBy("SELECT 1 INTO a; DROP VARIABLE a"));
    }

    @Test
    public void otherIntoIsNotAnAssignment() {
        assertEquals(Collections.emptySet(), IntoVariables.assignedBy("INSERT INTO t VALUES (1)"));
        assertEquals(
                Collections.emptySet(), IntoVariables.assignedBy("INSERT INTO t SELECT a FROM s"));
        assertEquals(
                Collections.emptySet(),
                IntoVariables.assignedBy("SELECT * FROM (SELECT 1 a) x WHERE 'into v' = s"));
        assertEquals(Collections.emptySet(), IntoVariables.assignedBy(null));
    }

    @Test
    public void bareOrQuotedNameIsAReference() {
        Set<String> vars = names("SET1", "I1");
        assertTrue(IntoVariables.isReferencedIn("select set1 from db_root", vars));
        assertTrue(IntoVariables.isReferencedIn("select t from table(SET1) as tbl(t)", vars));
        assertTrue(IntoVariables.isReferencedIn("select i from n where i1=3", vars));
        assertTrue(IntoVariables.isReferencedIn("select \"i1\" from n", vars));
    }

    @Test
    public void columnLiteralOrCommentIsNotAReference() {
        Set<String> vars = names("SET1");
        assertFalse(IntoVariables.isReferencedIn("select t.set1 from t", vars));
        assertFalse(IntoVariables.isReferencedIn("select 'set1' from t", vars));
        assertFalse(IntoVariables.isReferencedIn("select a /* set1 */ from t", vars));
        assertFalse(IntoVariables.isReferencedIn("select set10 from t", vars));
        assertFalse(
                IntoVariables.isReferencedIn("select 1 from t", Collections.<String>emptySet()));
    }
}
