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

import cubrid.jdbc.lb.config.LoadBalanceSettings;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link SqlClassifier} instances for keyword-based READ/WRITE classification. Uses a
 * bounded thread-safe map cache when {@link LoadBalanceSettings#getSqlClassifyCacheMaxEntries()} is
 * positive.
 *
 * <p>Classifiers are memoized per cache-max value ({@link #BY_MAX}). Each {@code
 * LoadBalanceConnection} resolves its own classifier once at construction, so DataSources tuned
 * with different cache maxes keep separate classifiers instead of sharing one JVM-global slot.
 */
public final class SqlClassifierRegistry {
    private static final SqlClassifier KEYWORD = new KeywordSqlClassifier();
    private static final ConcurrentHashMap<Integer, SqlClassifier> BY_MAX =
            new ConcurrentHashMap<Integer, SqlClassifier>();

    private SqlClassifierRegistry() {}

    /**
     * Returns the (memoized) classifier for the given settings' cache-max.
     *
     * @param config load-balance settings holding the classify-cache max; must not be {@code null}
     * @return the classifier to use for those settings
     */
    public static SqlClassifier forConfig(final LoadBalanceSettings config) {
        if (config == null) {
            throw new IllegalArgumentException("LoadBalanceSettings must not be null");
        }

        int max = config.getSqlClassifyCacheMaxEntries();
        return max <= 0 ? KEYWORD : cachingFor(max);
    }

    /** Reuse (or create once) the caching classifier for a given positive cache-max. */
    private static SqlClassifier cachingFor(final int max) {
        final Integer key = Integer.valueOf(max);
        SqlClassifier c = BY_MAX.get(key);
        if (c == null) {
            c = new CachingSqlClassifier(KEYWORD, max);
            final SqlClassifier prev = BY_MAX.putIfAbsent(key, c);
            if (prev != null) {
                c = prev;
            }
        }
        return c;
    }
}
