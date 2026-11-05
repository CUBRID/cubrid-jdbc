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

import static org.junit.Assert.assertTrue;

import cubrid.jdbc.driver.CUBRIDConnection;
import cubrid.jdbc.driver.CUBRIDDatabaseMetaData;
import cubrid.jdbc.driver.CUBRIDPreparedStatement;
import cubrid.jdbc.driver.CUBRIDStatement;
import cubrid.jdbc.lb.statement.LBPreparedStatement;
import cubrid.jdbc.lb.statement.LBStatement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

/**
 * An API the core driver refuses outright is refused the same way here: same exception, code and
 * message, before any work, open or closed. The set comes from core itself - every public method is
 * called on a core object built without its constructor - so there is no list to maintain.
 */
public class CoreUnsupportedApiParityTest {

    @Test
    public void connectionRefusesWhatCoreRefuses() throws Exception {
        LoadBalanceConnection open = newConnection();
        LoadBalanceConnection closed = newConnection();
        closed.close();
        assertParity(CUBRIDConnection.class, open, closed);
    }

    @Test
    public void statementRefusesWhatCoreRefuses() throws Exception {
        LBStatement closed = new LBStatement(newConnection());
        closed.close();
        assertParity(CUBRIDStatement.class, new LBStatement(newConnection()), closed);
    }

    @Test
    public void preparedStatementRefusesWhatCoreRefuses() throws Exception {
        LBPreparedStatement closed = new LBPreparedStatement(newConnection(), "SELECT 1");
        closed.close();
        assertParity(
                CUBRIDPreparedStatement.class,
                new LBPreparedStatement(newConnection(), "SELECT 1"),
                closed);
    }

    @Test
    public void databaseMetaDataRefusesWhatCoreRefuses() throws Exception {
        assertParity(CUBRIDDatabaseMetaData.class, newConnection().getMetaData(), null);
    }

    private static LoadBalanceConnection newConnection() {
        return new LoadBalanceConnection(LbTestSettings.defaults());
    }

    /** Asserts that {@code lbOpen}/{@code lbClosed} answer each API core refuses as core does. */
    private static void assertParity(
            final Class<?> coreClass, final Object lbOpen, final Object lbClosed) throws Exception {
        Object coreOpen = coreWithoutConstructor(coreClass);
        Object coreClosed = coreWithoutConstructor(coreClass);
        setClosed(coreClosed);

        List<String> mismatches = new ArrayList<String>();
        int refused = 0;
        for (Method m : coreApi(coreClass)) {
            Object[] args = defaultArgs(m);
            Throwable core = thrownBy(m, coreOpen, args);
            if (!(core instanceof SQLFeatureNotSupportedException)) {
                continue;
            }
            refused++;
            Method lbMethod = lbOpen.getClass().getMethod(m.getName(), m.getParameterTypes());
            Throwable lb = thrownBy(lbMethod, lbOpen, args);
            if (!sameRefusal(core, lb)) {
                mismatches.add("open   " + signature(m) + ": core " + core + ", LB " + lb);
            }
            if (lbClosed != null) {
                Throwable coreWhenClosed = thrownBy(m, coreClosed, args);
                Throwable lbWhenClosed = thrownBy(lbMethod, lbClosed, args);
                if (!sameKind(coreWhenClosed, lbWhenClosed)) {
                    mismatches.add(
                            "closed "
                                    + signature(m)
                                    + ": core "
                                    + coreWhenClosed
                                    + ", LB "
                                    + lbWhenClosed);
                }
            }
        }

        assertTrue(
                coreClass.getSimpleName() + " refuses nothing - the probe is broken", refused > 0);
        assertTrue(
                coreClass.getSimpleName()
                        + ": "
                        + mismatches.size()
                        + " of "
                        + refused
                        + " differ\n"
                        + String.join("\n", mismatches),
                mismatches.isEmpty());
    }

    private static boolean sameRefusal(final Throwable core, final Throwable lb) {
        return lb instanceof SQLFeatureNotSupportedException
                && ((SQLException) lb).getErrorCode() == ((SQLException) core).getErrorCode()
                && String.valueOf(lb.getMessage()).equals(core.getMessage());
    }

    private static boolean sameKind(final Throwable core, final Throwable lb) {
        if (core == null || lb == null) {
            return core == lb;
        }
        if (core.getClass() != lb.getClass()) {
            return false;
        }
        return !(core instanceof SQLException)
                || ((SQLException) core).getErrorCode() == ((SQLException) lb).getErrorCode();
    }

    /** The public instance methods core declares on {@code type} or its CUBRID superclasses. */
    private static List<Method> coreApi(final Class<?> type) {
        List<Method> api = new ArrayList<Method>();
        for (Method m : type.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())
                    && m.getDeclaringClass().getName().startsWith("cubrid.jdbc.driver.")) {
                api.add(m);
            }
        }
        api.sort(Comparator.comparing(CoreUnsupportedApiParityTest::signature));
        return api;
    }

    private static Throwable thrownBy(final Method m, final Object target, final Object[] args) {
        try {
            m.invoke(target, args);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object[] defaultArgs(final Method m) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == boolean.class) {
                args[i] = Boolean.FALSE;
            } else if (types[i] == long.class) {
                args[i] = Long.valueOf(0L);
            } else if (types[i] == int.class) {
                args[i] = Integer.valueOf(0);
            } else if (types[i] == short.class) {
                args[i] = Short.valueOf((short) 0);
            } else if (types[i] == byte.class) {
                args[i] = Byte.valueOf((byte) 0);
            } else if (types[i] == double.class) {
                args[i] = Double.valueOf(0d);
            } else if (types[i] == float.class) {
                args[i] = Float.valueOf(0f);
            } else if (types[i] == char.class) {
                args[i] = Character.valueOf('\0');
            }
        }
        return args;
    }

    /** A core object whose fields are all at their defaults: open, but bound to nothing. */
    private static Object coreWithoutConstructor(final Class<?> type) throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    private static void setClosed(final Object core) throws IllegalAccessException {
        for (Class<?> c = core.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field closed = c.getDeclaredField("is_closed");
                closed.setAccessible(true);
                closed.setBoolean(core, true);
                return;
            } catch (NoSuchFieldException notHere) {
                // keep looking up the hierarchy
            }
        }
    }

    private static String signature(final Method m) {
        StringBuilder b = new StringBuilder(m.getName()).append('(');
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            b.append(i == 0 ? "" : ",").append(types[i].getSimpleName());
        }
        return b.append(')').toString();
    }
}
