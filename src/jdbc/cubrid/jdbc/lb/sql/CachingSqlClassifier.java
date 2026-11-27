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

/**
 * Thread-safe cache of {@link SqlClassification} keyed by the exact SQL string passed to {@link
 * #classify(String)}. Eviction and the length limit are {@link SqlTextCache}'s.
 */
final class CachingSqlClassifier implements SqlClassifier {
    private final SqlTextCache<SqlClassification> cache;
    private final SqlTextCache.Source<SqlClassification> source;

    CachingSqlClassifier(final SqlClassifier delegate, final int maxEntries) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.cache = new SqlTextCache<SqlClassification>(maxEntries);
        this.source =
                new SqlTextCache.Source<SqlClassification>() {
                    public SqlClassification of(final String sql) {
                        return delegate.classify(sql);
                    }
                };
    }

    @Override
    public SqlClassification classify(final String sql) {
        return cache.get(sql, source);
    }
}
