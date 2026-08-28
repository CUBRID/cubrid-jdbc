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

package cubrid.jdbc.lb.config;

import java.util.Map;

/**
 * Case-insensitive lookups over the URL option map. Kept in one place so config-package call sites
 * cannot diverge on the matching rule.
 */
final class OptionKeys {

    private OptionKeys() {}

    /** Value of the first entry whose key equals {@code key} ignoring case, or {@code null}. */
    static String findValueIgnoreCase(final Map<String, String> options, final String key) {
        if (options == null) {
            return null;
        }

        for (Map.Entry<String, String> e : options.entrySet()) {
            if (key.equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }

        return null;
    }

    /** Actual stored key equal to {@code key} ignoring case, or {@code null}. */
    static String findKeyIgnoreCase(final Map<String, String> options, final String key) {
        if (options == null) {
            return null;
        }

        for (final String existing : options.keySet()) {
            if (existing.equalsIgnoreCase(key)) {
                return existing;
            }
        }

        return null;
    }

    /** True if any element of {@code names} equals {@code key} ignoring case. */
    static boolean containsIgnoreCase(final String[] names, final String key) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equalsIgnoreCase(key)) {
                return true;
            }
        }

        return false;
    }
}
