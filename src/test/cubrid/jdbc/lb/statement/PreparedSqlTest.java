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

import org.junit.Test;

public class PreparedSqlTest {

    @Test
    public void normalizeTrimsOuterWhitespaceOnly() {
        assertEquals("SELECT  1", PreparedSql.normalizeForCacheKey("  SELECT  1  "));
        assertEquals("SELECT 1", PreparedSql.normalizeForCacheKey("SELECT 1"));
        assertEquals(
                "SELECT  1   FROM   t", PreparedSql.normalizeForCacheKey("SELECT  1   FROM   t"));
    }

    // 02 §M3: internal whitespace must NOT be collapsed — it lives inside string literals whose
    // meaning differs, so distinct SQLs must map to distinct cache keys (no physical PS aliasing).
    @Test
    public void normalizePreservesStringLiteralWhitespace() {
        String twoSpaces = "SELECT 'a  b' FROM t";
        String oneSpace = "SELECT 'a b' FROM t";
        assertEquals(twoSpaces, PreparedSql.normalizeForCacheKey(twoSpaces));
        assertEquals(oneSpace, PreparedSql.normalizeForCacheKey(oneSpace));
        assertFalse(
                PreparedSql.normalizeForCacheKey(twoSpaces)
                        .equals(PreparedSql.normalizeForCacheKey(oneSpace)));
    }

    // 02 §M3: newlines (e.g. after a line comment) must survive so trailing statements are not
    // absorbed into the comment via newline-to-space rewriting.
    @Test
    public void normalizePreservesNewlines() {
        String withComment = "SELECT 1 -- c\nFROM t";
        assertEquals(withComment, PreparedSql.normalizeForCacheKey(withComment));
    }

    @Test
    public void normalizeNullAndEmpty() {
        assertEquals("", PreparedSql.normalizeForCacheKey(null));
        assertEquals("", PreparedSql.normalizeForCacheKey("   "));
    }
}
