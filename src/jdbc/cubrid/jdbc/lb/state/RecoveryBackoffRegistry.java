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
 * JVM-wide registry of pool-scoped {@link RecoveryBackoff} instances, keyed like {@link
 * SharedSelectorStateRegistry} (logical URL with query stripped) so all sessions of the same pool
 * share one backoff schedule — the basis of single-prober election across sessions.
 *
 * <p>The interval is fixed at first creation of a key; later callers with a different interval get
 * the existing instance (same-pool connections share one config in practice).
 */
public final class RecoveryBackoffRegistry {
    private static final ConcurrentHashMap<String, RecoveryBackoff> BY_KEY =
            new ConcurrentHashMap<String, RecoveryBackoff>();

    private RecoveryBackoffRegistry() {}

    public static RecoveryBackoff getOrCreate(final String registryKey, final long intervalMs) {
        RecoveryBackoff existing = BY_KEY.get(registryKey);
        if (existing != null) {
            return existing;
        }

        RecoveryBackoff created = new RecoveryBackoff(intervalMs);
        existing = BY_KEY.putIfAbsent(registryKey, created);
        return existing != null ? existing : created;
    }

    /**
     * Removes the backoff instance for a pool key. Not called from production today: no pool or
     * driver shutdown hook is wired to it, so entries live for the JVM lifetime. That is bounded at
     * one entry per distinct pool key, but a deployment that mints logical URLs dynamically would
     * grow without bound; wire this into a close hook if that appears. Public for that and for
     * tests.
     *
     * @param registryKey pool key whose backoff instance is removed
     */
    public static void releaseKey(final String registryKey) {
        BY_KEY.remove(registryKey);
    }

    /** Clears all registry entries; for unit tests only. */
    public static void clearForTests() {
        BY_KEY.clear();
    }
}
