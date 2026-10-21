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

import java.util.Properties;
import org.junit.Test;

public class LoadBalanceSettingsRecoveryProbeTest {

    @Test
    public void defaultsToThreeSecondsWhenUnset() {
        LoadBalanceSettings cfg = LoadBalanceSettings.of(new Properties());
        assertEquals(3000, cfg.getRecoveryProbeIntervalMs());
    }

    @Test
    public void explicitValueIsReflected() {
        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_RT_RECOVERY_PROBE_INTERVAL_MS, "5000");
        LoadBalanceSettings cfg = LoadBalanceSettings.of(p);
        assertEquals(5000, cfg.getRecoveryProbeIntervalMs());
    }

    @Test
    public void zeroIsAllowed() {
        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_RT_RECOVERY_PROBE_INTERVAL_MS, "0");
        LoadBalanceSettings cfg = LoadBalanceSettings.of(p);
        assertEquals(0, cfg.getRecoveryProbeIntervalMs());
    }

    @Test
    public void negativeValueFallsBackToDefault() {
        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_RT_RECOVERY_PROBE_INTERVAL_MS, "-1");
        LoadBalanceSettings cfg = LoadBalanceSettings.of(p);
        assertEquals(3000, cfg.getRecoveryProbeIntervalMs());
    }

    @Test
    public void nonIntegerValueFallsBackToDefault() {
        Properties p = new Properties();
        p.setProperty(LoadBalanceSettings.KEY_RT_RECOVERY_PROBE_INTERVAL_MS, "abc");
        LoadBalanceSettings cfg = LoadBalanceSettings.of(p);
        assertEquals(3000, cfg.getRecoveryProbeIntervalMs());
    }
}
