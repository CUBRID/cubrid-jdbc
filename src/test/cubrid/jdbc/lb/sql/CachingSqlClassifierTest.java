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

import cubrid.jdbc.lb.config.LoadBalanceSettings;
import java.util.Properties;
import org.junit.Test;

public class CachingSqlClassifierTest {

    private static final class CountingClassifier implements SqlClassifier {
        int count;

        @Override
        public SqlClassification classify(final String sql) {
            count++;
            return SqlClassification.READ;
        }
    }

    @Test
    public void secondClassifyReusesCacheForSameSql() {
        CountingClassifier delegate = new CountingClassifier();
        CachingSqlClassifier cache = new CachingSqlClassifier(delegate, 8192);
        assertEquals(SqlClassification.READ, cache.classify("SELECT 1"));
        assertEquals(SqlClassification.READ, cache.classify("SELECT 1"));
        assertEquals(1, delegate.count);
    }

    @Test
    public void entrySurvivesOneRotationWithoutFullClear() {
        // maxEntries=2: A,B fill the hot generation; C overflows and rotates {A,B,C} to cold.
        CountingClassifier delegate = new CountingClassifier();
        CachingSqlClassifier cache = new CachingSqlClassifier(delegate, 2);
        cache.classify("A");
        cache.classify("B");
        cache.classify("C");
        assertEquals(3, delegate.count);

        // A and B are still cached in the cold generation (no full clear) — no recompute.
        assertEquals(SqlClassification.READ, cache.classify("A"));
        assertEquals(SqlClassification.READ, cache.classify("B"));
        assertEquals(3, delegate.count);
    }

    @Test
    public void promotedEntrySurvivesSecondRotationWhileColdOnlyEntryIsEvicted() {
        CountingClassifier delegate = new CountingClassifier();
        CachingSqlClassifier cache = new CachingSqlClassifier(delegate, 2);
        cache.classify("A");
        cache.classify("B");
        cache.classify("C"); // rotate #1: cold={A,B,C}, hot={}
        assertEquals(3, delegate.count);

        cache.classify("A"); // cold hit -> promote A into hot (no recompute)
        assertEquals(3, delegate.count);

        cache.classify("D");
        cache.classify("E"); // rotate #2: cold={A,D,E}, hot={}; old cold {A,B,C} dropped
        assertEquals(5, delegate.count);

        // B was never promoted, so the second rotation evicts it -> recompute.
        assertEquals(SqlClassification.READ, cache.classify("B"));
        assertEquals(6, delegate.count);

        // A was promoted before rotation #2, so it is still cached -> no recompute.
        assertEquals(SqlClassification.READ, cache.classify("A"));
        assertEquals(6, delegate.count);
    }

    @Test
    public void nullSqlSkipsCache() {
        CountingClassifier delegate = new CountingClassifier();
        CachingSqlClassifier cache = new CachingSqlClassifier(delegate, 8);
        cache.classify(null);
        cache.classify(null);
        assertEquals(2, delegate.count);
    }

    @Test
    public void forConfigMaxZeroDisablesCaching() {
        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_SQL_CLASSIFY_CACHE_MAX_ENTRIES, "0");
        LoadBalanceSettings cfg = LoadBalanceSettings.of(p);
        assertFalse(SqlClassifierRegistry.forConfig(cfg) instanceof CachingSqlClassifier);
    }

    @Test
    public void forConfigDefaultUsesCachingWrapper() {
        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_SQL_CLASSIFY_CACHE_MAX_ENTRIES, "0");
        assertFalse(
                SqlClassifierRegistry.forConfig(LoadBalanceSettings.of(p))
                        instanceof CachingSqlClassifier);
        assertTrue(
                SqlClassifierRegistry.forConfig(LoadBalanceSettings.of(new Properties()))
                        instanceof CachingSqlClassifier);
    }
}
