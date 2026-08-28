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

package cubrid.jdbc.lb.state;

import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-wide registry of {@link SharedSelectorState} for round-robin / live-population continuity
 * across logical connections from the same pool or JDBC URL group.
 *
 * <p>The caller supplies the registry key; production uses the logical JDBC URL with its query
 * stripped (see {@code LoadBalanceConnection.sharedStateKeyForUrl}). Entries are never reclaimed,
 * which is bounded by the number of distinct logical URLs. A deployment that mints URLs dynamically
 * (per-tenant db names, say) would grow the map without bound.
 */
public final class SharedSelectorStateRegistry {
    private static final String DEFAULT_KEY = "default";

    private static final ConcurrentHashMap<String, SharedSelectorState> BY_KEY =
            new ConcurrentHashMap<String, SharedSelectorState>();

    private SharedSelectorStateRegistry() {}

    public static SharedSelectorState getOrCreate(final String registryKey) {
        String key = normalizeRegistryKey(registryKey);
        SharedSelectorState created = new SharedSelectorState();
        SharedSelectorState existing = BY_KEY.putIfAbsent(key, created);
        return existing != null ? existing : created;
    }

    /** Clears all registry entries; for unit tests only. */
    public static void clearForTests() {
        BY_KEY.clear();
    }

    private static String normalizeRegistryKey(final String registryKey) {
        if (registryKey == null || registryKey.trim().isEmpty()) {
            return DEFAULT_KEY;
        }

        return registryKey.trim();
    }
}
