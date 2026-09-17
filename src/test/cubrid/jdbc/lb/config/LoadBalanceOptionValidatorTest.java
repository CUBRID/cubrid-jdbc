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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.lb.config.LoadBalanceOptionValidator.Result;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class LoadBalanceOptionValidatorTest {

    private static Map<String, String> opts(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }

        return m;
    }

    @Test
    public void loadbalanceModeIgnoresClassicOnlyOptions() {
        Result r =
                LoadBalanceOptionValidator.validate(
                        true,
                        opts(
                                "altHosts", "h2:1",
                                "loadBalance", "true",
                                "useLazyConnection", "true",
                                "charSet", "utf-8",
                                "rwPort", "33000",
                                "readWeight", "slave:1"));

        assertTrue(r.getIgnoredKeys().contains("altHosts"));
        assertTrue(r.getIgnoredKeys().contains("loadBalance"));
        assertTrue(r.getIgnoredKeys().contains("useLazyConnection"));
        assertEquals(3, r.getIgnoredKeys().size());

        // common + loadbalance-own options are retained
        assertTrue(r.getRetainedOptions().containsKey("charSet"));
        assertTrue(r.getRetainedOptions().containsKey("rwPort"));
        assertTrue(r.getRetainedOptions().containsKey("readWeight"));
        assertFalse(r.getRetainedOptions().containsKey("altHosts"));
    }

    @Test
    public void classicModeIgnoresLoadbalanceOnlyOptions() {
        Result r =
                LoadBalanceOptionValidator.validate(
                        false,
                        opts(
                                "rwPort", "33000",
                                "roPort", "33002",
                                "soPort", "33004",
                                "readWeight", "slave:1",
                                "altHosts", "h2:1",
                                "charSet", "utf-8"));

        assertTrue(r.getIgnoredKeys().contains("rwPort"));
        assertTrue(r.getIgnoredKeys().contains("roPort"));
        assertTrue(r.getIgnoredKeys().contains("soPort"));
        assertTrue(r.getIgnoredKeys().contains("readWeight"));
        assertEquals(4, r.getIgnoredKeys().size());

        // classic-own + common options are retained
        assertTrue(r.getRetainedOptions().containsKey("altHosts"));
        assertTrue(r.getRetainedOptions().containsKey("charSet"));
        assertFalse(r.getRetainedOptions().containsKey("rwPort"));
    }

    @Test
    public void unknownKeysAreRetainedInBothModes() {
        assertTrue(
                LoadBalanceOptionValidator.validate(true, opts("typoKey", "x"))
                        .getRetainedOptions()
                        .containsKey("typoKey"));
        assertTrue(
                LoadBalanceOptionValidator.validate(false, opts("typoKey", "x"))
                        .getRetainedOptions()
                        .containsKey("typoKey"));
    }

    @Test
    public void optionKeyMatchingIsCaseInsensitive() {
        Result r = LoadBalanceOptionValidator.validate(true, opts("ALTHOSTS", "h2:1"));

        assertTrue(r.getIgnoredKeys().contains("ALTHOSTS"));
        assertFalse(r.getRetainedOptions().containsKey("ALTHOSTS"));
    }

    // 05 §M3: cubrid.lb.* keys are read nowhere (loadbalance uses fixed defaults) → ignored (WARN)
    // and not retained for propagation, in either mode.
    @Test
    public void cubridLbConfigKeysAreIgnoredInBothModes() {
        Result lb =
                LoadBalanceOptionValidator.validate(
                        true, opts("cubrid.lb.runtime.failover.enabled", "false"));
        assertTrue(lb.getIgnoredKeys().contains("cubrid.lb.runtime.failover.enabled"));
        assertFalse(lb.getRetainedOptions().containsKey("cubrid.lb.runtime.failover.enabled"));

        Result classic =
                LoadBalanceOptionValidator.validate(
                        false, opts("CUBRID.LB.Distribution.Mode", "session"));
        assertTrue(classic.hasIgnored());
        assertFalse(classic.getRetainedOptions().containsKey("CUBRID.LB.Distribution.Mode"));
    }

    @Test
    public void nullOrEmptyOptionsYieldEmptyResult() {
        assertFalse(LoadBalanceOptionValidator.validate(true, null).hasIgnored());
        assertTrue(LoadBalanceOptionValidator.validate(true, null).getRetainedOptions().isEmpty());
        assertFalse(LoadBalanceOptionValidator.validate(true, opts()).hasIgnored());
    }
}
