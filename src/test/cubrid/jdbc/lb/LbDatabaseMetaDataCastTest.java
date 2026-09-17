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
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.driver.CUBRIDDatabaseMetaData;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Coverage guard for {@link LBDatabaseMetaData}. Every overridable method of {@code
 * CUBRIDDatabaseMetaData} must be overridden, because the inherited bodies read a {@code u_con}
 * that a logical connection does not own — a missing forwarder is a NullPointerException from a
 * stack trace that never mentions LB, and it appears only when that one metadata call is made.
 * Reflection catches it at build time instead, including when a future core-driver change adds a
 * method.
 */
public class LbDatabaseMetaDataCastTest {

    @Test
    public void everyOverridableInheritedMethodIsOverridden() {
        List<String> missing = new ArrayList<String>();

        for (Method inherited : CUBRIDDatabaseMetaData.class.getDeclaredMethods()) {
            int modifiers = inherited.getModifiers();
            if (Modifier.isStatic(modifiers)
                    || Modifier.isFinal(modifiers)
                    || inherited.isSynthetic()
                    || !(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))) {
                continue;
            }
            try {
                LBDatabaseMetaData.class.getDeclaredMethod(
                        inherited.getName(), inherited.getParameterTypes());
            } catch (NoSuchMethodException notOverridden) {
                missing.add(signatureOf(inherited));
            }
        }
        Collections.sort(missing);

        assertTrue(
                "LBDatabaseMetaData inherits these from CUBRIDDatabaseMetaData; each would run"
                        + " against the null u_con of a logical connection: "
                        + missing,
                missing.isEmpty());
    }

    /**
     * {@code close()} is package-private in {@code cubrid.jdbc.driver} and so cannot be overridden
     * from here. Its only caller is {@code CUBRIDConnection.clear()}, over that connection's own
     * {@code mdata} field — and {@code LoadBalanceConnection} refuses {@code clear()} and never
     * assigns {@code mdata}, because it overrides {@code getMetaData()}. Pinning the set turns a
     * future core-driver addition into a failing test that forces the reachability question to be
     * answered again.
     */
    @Test
    public void packagePrivateMembersAreTheKnownUnreachableSet() {
        List<String> packagePrivate = new ArrayList<String>();

        for (Method declared : CUBRIDDatabaseMetaData.class.getDeclaredMethods()) {
            int modifiers = declared.getModifiers();
            if (Modifier.isStatic(modifiers)
                    || Modifier.isPrivate(modifiers)
                    || Modifier.isPublic(modifiers)
                    || Modifier.isProtected(modifiers)
                    || declared.isSynthetic()) {
                continue;
            }
            packagePrivate.add(signatureOf(declared));
        }
        Collections.sort(packagePrivate);

        assertEquals(
                "a new package-private member of CUBRIDDatabaseMetaData cannot be overridden here;"
                        + " confirm nothing hands an LB metadata object to a caller of it",
                "[close()]",
                packagePrivate.toString());
    }

    private static String signatureOf(final Method method) {
        StringBuilder out = new StringBuilder(method.getName()).append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            out.append(i == 0 ? "" : ", ").append(parameters[i].getSimpleName());
        }

        return out.append(')').toString();
    }
}
