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

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Test;

public final class SharedSelectorStateRegistryTest {

    @After
    public void tearDown() {
        SharedSelectorStateRegistry.clearForTests();
    }

    @Test
    public void assertGetOrCreateSharesStateForSameKey() {
        String key = "url:localhost:30000/testdb";

        SharedSelectorState first = SharedSelectorStateRegistry.getOrCreate(key);
        SharedSelectorState second = SharedSelectorStateRegistry.getOrCreate(key);

        assertSame(first, second);
    }

    @Test
    public void assertGetOrCreateSeparatesDifferentKeys() {
        SharedSelectorState first = SharedSelectorStateRegistry.getOrCreate("url:host:30000/db1");
        SharedSelectorState second = SharedSelectorStateRegistry.getOrCreate("url:host:30000/db2");

        assertNotSame(first, second);
    }

    @Test
    public void assertGetOrCreateCollapsesNullAndBlankToDefault() {
        SharedSelectorState viaNull = SharedSelectorStateRegistry.getOrCreate(null);
        SharedSelectorState viaBlank = SharedSelectorStateRegistry.getOrCreate("   ");

        assertSame(viaNull, viaBlank);
    }
}
