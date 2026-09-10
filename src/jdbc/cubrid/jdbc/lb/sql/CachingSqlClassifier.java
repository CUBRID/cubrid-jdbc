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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe cache of {@link SqlClassification} keyed by the exact SQL string passed to {@link
 * #classify(String)}.
 *
 * <p>Eviction rotates two generations instead of wiping the cache. New entries go into the
 * <em>hot</em> generation; when it reaches {@code maxEntries} it becomes <em>cold</em> and an empty
 * hot generation takes over. Lookups try hot, then cold, and a cold hit is promoted back to hot, so
 * a frequently used entry survives rotations. Each rotation drops at most one generation, and the
 * cache holds roughly {@code 2 * maxEntries} entries at most.
 *
 * <p>The hot fill level is an {@link AtomicInteger}, so a miss needs no {@code size()} scan of the
 * map. Under concurrency the count is approximate: it only decides when to rotate, never what a
 * lookup returns.
 */
public final class CachingSqlClassifier implements SqlClassifier {
    private static final int MIN_MAP_INITIAL_CAPACITY = 16;
    private static final int MAX_MAP_INITIAL_CAPACITY = 4096;
    private final SqlClassifier delegate;
    private final int maxEntries;
    private final int initialCapacity;
    private final AtomicInteger hotCount = new AtomicInteger();
    private volatile ConcurrentHashMap<String, SqlClassification> hot;
    private volatile ConcurrentHashMap<String, SqlClassification> cold;

    public CachingSqlClassifier(final SqlClassifier delegate, final int maxEntries) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.delegate = delegate;
        this.maxEntries = maxEntries;
        this.initialCapacity = mapInitialCapacity(maxEntries);
        this.hot = new ConcurrentHashMap<String, SqlClassification>(initialCapacity);
        this.cold = new ConcurrentHashMap<String, SqlClassification>(initialCapacity);
    }

    private static int mapInitialCapacity(final int maxEntries) {
        int initial = maxEntries < MIN_MAP_INITIAL_CAPACITY ? MIN_MAP_INITIAL_CAPACITY : maxEntries;
        if (initial > MAX_MAP_INITIAL_CAPACITY) {
            initial = MAX_MAP_INITIAL_CAPACITY;
        }

        return initial;
    }

    @Override
    public SqlClassification classify(final String sql) {
        if (sql == null) {
            return delegate.classify(null);
        }

        SqlClassification hit = hot.get(sql);
        if (hit != null) {
            return hit;
        }

        hit = cold.get(sql);
        if (hit != null) {
            store(sql, hit); // promote into hot so it survives the next rotation
            return hit;
        }

        SqlClassification computed = delegate.classify(sql);
        store(sql, computed);

        return computed;
    }

    private void store(final String sql, final SqlClassification value) {
        if (hot.putIfAbsent(sql, value) == null && hotCount.incrementAndGet() > maxEntries) {
            rotate();
        }
    }

    private synchronized void rotate() {
        if (hotCount.get() <= maxEntries) {
            return; // another thread already rotated
        }
        cold = hot;
        hot = new ConcurrentHashMap<String, SqlClassification>(initialCapacity);
        hotCount.set(0);
    }
}
