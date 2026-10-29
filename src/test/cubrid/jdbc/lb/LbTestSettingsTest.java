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

package cubrid.jdbc.lb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.NodeRole;
import cubrid.jdbc.lb.config.ResolvedRoleTopology;
import org.junit.Test;

/** The settings the unit tests bind with mean what {@link LbTestSettings} says they mean. */
public final class LbTestSettingsTest {

    @Test
    public void forBindingMakesTheGivenPairTheMasterAndTheOnlyReadTarget() {
        LoadBalanceSettings s =
                LbTestSettings.forBinding(new Endpoint("rw", 33000), new Endpoint("ro1", 33001));

        ResolvedRoleTopology t = s.getResolvedTopology();
        assertEquals(new Endpoint("rw", 33000), t.getMaster().getRw());
        assertEquals(1, t.getSlaves().size());
        assertEquals(new Endpoint("ro1", 33001), t.getSlaves().get(0).getRo());
        assertEquals(0, s.getReadWeight().weightOf(NodeRole.MASTER));
        assertEquals(1, s.getReadWeight().weightOf(NodeRole.SLAVE));
    }

    /**
     * Tests of the non-default paths depend on this. An inline constant initializer on the field
     * would be folded into the getter, and the override would silently change nothing.
     */
    @Test
    public void withOverridesAPinnedDefaultAsSeenByItsGetter() {
        LoadBalanceSettings s =
                LbTestSettings.with(LbTestSettings.defaults(), "rtFailoverEnabled", Boolean.FALSE);
        assertFalse(s.isRuntimeFailoverEnabled());

        s = LbTestSettings.with(LbTestSettings.defaults(), "recoveryProbeIntervalMs", 0);
        assertEquals(0, s.getRecoveryProbeIntervalMs());
    }
}
