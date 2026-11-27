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

import java.lang.reflect.Field;

/**
 * Reads and replaces the private static state of a driver class, for a test that has to start from
 * a clean one. The driver carries no method that only a test calls.
 */
public final class LbTestReflection {
    private LbTestReflection() {}

    public static Object staticField(final Class<?> owner, final String name) {
        try {
            return field(owner, name).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + name, e);
        }
    }

    public static void setStatic(final Class<?> owner, final String name, final Object value) {
        try {
            field(owner, name).set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + name, e);
        }
    }

    /** The value of {@code target}'s private field, looked up through its superclasses. */
    public static Object field(final Object target, final String name) {
        try {
            return instanceField(target, name).get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(target.getClass().getSimpleName() + "." + name, e);
        }
    }

    public static void setField(final Object target, final String name, final Object value) {
        try {
            instanceField(target, name).set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(target.getClass().getSimpleName() + "." + name, e);
        }
    }

    private static Field instanceField(final Object target, final String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException inASuperclass) {
                // keep looking
            }
        }
        throw new IllegalStateException(
                target.getClass().getSimpleName() + " has no field " + name + " any more");
    }

    private static Field field(final Class<?> owner, final String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    owner.getSimpleName() + " has no field " + name + " any more", e);
        }
    }
}
