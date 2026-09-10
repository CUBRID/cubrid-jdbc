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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class RecoveryBackoffRegistryTest {
    private static final String KEY = "loadbalance://node1:33000:rw,node2:33000:read/demodb";

    @After
    public void tearDown() {
        RecoveryBackoffRegistry.clearForTests();
    }

    @Test
    public void assertSameKeySharesOneInstance() {
        RecoveryBackoff first = RecoveryBackoffRegistry.getOrCreate(KEY, 3000);
        RecoveryBackoff second = RecoveryBackoffRegistry.getOrCreate(KEY, 3000);

        assertSame(first, second);
    }

    @Test
    public void assertDifferentKeysGetSeparateInstances() {
        RecoveryBackoff first = RecoveryBackoffRegistry.getOrCreate(KEY, 3000);
        RecoveryBackoff second = RecoveryBackoffRegistry.getOrCreate(KEY + "/other", 3000);

        assertNotSame(first, second);
    }

    @Test
    public void assertIntervalIsFixedAtFirstCreation() {
        RecoveryBackoffRegistry.getOrCreate(KEY, 3000);
        RecoveryBackoff shared = RecoveryBackoffRegistry.getOrCreate(KEY, 0);

        // Still the 3000ms instance: the second claim inside the window is suppressed.
        assertTrue(shared.tryClaimProbe("ep", 42L));
        assertFalse(shared.tryClaimProbe("ep", 42L));
    }

    @Test
    public void assertReleaseKeyDropsInstance() {
        RecoveryBackoff first = RecoveryBackoffRegistry.getOrCreate(KEY, 3000);
        RecoveryBackoffRegistry.releaseKey(KEY);
        RecoveryBackoff second = RecoveryBackoffRegistry.getOrCreate(KEY, 3000);

        assertNotSame(first, second);
    }
}
