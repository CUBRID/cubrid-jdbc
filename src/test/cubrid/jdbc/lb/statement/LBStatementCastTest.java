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

package cubrid.jdbc.lb.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.CUBRIDPreparedStatement;
import cubrid.jdbc.driver.CUBRIDStatement;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.TestConfigHelper;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the vendor-cast contract for logical statements: an application written against {@code
 * (CUBRIDStatement) stmt} or {@code (CUBRIDPreparedStatement) ps} must run unmodified on a {@code
 * loadbalance://} URL.
 *
 * <p>Making the casts work means extending the vendor classes, and that inherits their socket-level
 * bodies, which dereference a {@code u_stmt}/{@code u_con} a logical statement does not own. So the
 * casts alone are not the contract — leaving one inherited method behind is a NullPointerException
 * from a stack trace that never mentions LB. The coverage tests below fail the build when that
 * happens, including when a future core-driver change adds a method.
 */
public class LBStatementCastTest {

    private LoadBalanceConnection conn;

    @Before
    public void setUp() throws Exception {
        conn = new LoadBalanceConnection(TestConfigHelper.emptyConfig());
    }

    @Test
    public void statementCastsToVendorStatement() throws Exception {
        Statement stmt = conn.createStatement();

        CUBRIDStatement vendor = (CUBRIDStatement) stmt;

        assertSame(stmt, vendor);
    }

    @Test
    public void preparedStatementCastsToVendorPreparedStatement() throws Exception {
        PreparedStatement ps = conn.prepareStatement("SELECT 1");

        CUBRIDPreparedStatement vendor = (CUBRIDPreparedStatement) ps;
        CUBRIDStatement alsoAStatement = (CUBRIDStatement) ps;

        assertSame(ps, vendor);
        assertSame(ps, alsoAStatement);
    }

    @Test
    public void unwrapReportsTheLeafClass() throws Exception {
        Statement stmt = conn.createStatement();
        PreparedStatement ps = conn.prepareStatement("SELECT 1");

        assertTrue(stmt.isWrapperFor(CUBRIDStatement.class));
        assertSame(stmt, stmt.unwrap(CUBRIDStatement.class));
        // Not forwarded to the delegate: forwarding would answer for LBStatement, so the one class
        // this must succeed for would fail.
        assertTrue(ps.isWrapperFor(CUBRIDPreparedStatement.class));
        assertSame(ps, ps.unwrap(CUBRIDPreparedStatement.class));
    }

    @Test
    public void statementOverridesEveryOverridableInheritedMethod() {
        assertNothingInherited(LBStatement.class, CUBRIDStatement.class);
    }

    @Test
    public void preparedStatementOverridesEveryOverridableInheritedMethod() {
        assertNothingInherited(LBPreparedStatement.class, CUBRIDPreparedStatement.class);
    }

    /**
     * A package-private member of a vendor statement cannot be overridden from {@code
     * cubrid.jdbc.lb.statement}, so coverage cannot close it. Pinning the set turns a future
     * core-driver addition into a failing test that forces the reachability question to be answered
     * again: does anything hand an LB statement to a caller of it?
     *
     * <p>For the current set the answer is no. {@code complete()} and {@code getHoldability()} are
     * called from {@code CUBRIDConnection.autoCommit()}/{@code autoRollback()} over the
     * connection's own {@code statements} list, and {@code LoadBalanceConnection} refuses {@code
     * autoCommit()}/{@code autoRollback()}/{@code addStatement()} — no LB statement ever enters
     * that list. {@code CUBRIDResultSet} reaches {@code complete()} through the statement that
     * produced it, and LB returns the physical ResultSet, whose statement is the physical one.
     * {@code MakeAutoGeneratedKeysResultSet()} and {@code resetGeneratedKeysResultSet()} have no
     * caller outside the vendor statements themselves.
     */
    @Test
    public void packagePrivateMembersAreTheKnownUnreachableSet() {
        assertEquals(
                "a new package-private member of CUBRIDStatement cannot be overridden here;"
                        + " confirm nothing hands an LB statement to a caller of it",
                "[MakeAutoGeneratedKeysResultSet(), complete(), getHoldability(),"
                        + " resetGeneratedKeysResultSet()]",
                packagePrivateSurfaceOf(CUBRIDStatement.class).toString());
        assertEquals(
                "a new package-private member of CUBRIDPreparedStatement cannot be overridden here;"
                        + " confirm nothing hands an LB prepared statement to a caller of it",
                "[complete()]",
                packagePrivateSurfaceOf(CUBRIDPreparedStatement.class).toString());
    }

    /**
     * A forwarder that was accidentally left inherited would still compile and still return a value
     * — the vendor field default — instead of failing. Reading back a value that was set proves the
     * delegate answered.
     */
    @Test
    public void preparedStatementStatePersistsThroughTheDelegate() throws Exception {
        PreparedStatement ps = conn.prepareStatement("SELECT 1");

        ps.setMaxRows(7);
        ps.setQueryTimeout(11);
        ps.setFetchSize(13);
        ps.setMaxFieldSize(17);

        assertEquals(7, ps.getMaxRows());
        assertEquals(11, ps.getQueryTimeout());
        assertEquals(13, ps.getFetchSize());
        assertEquals(17, ps.getMaxFieldSize());
    }

    /**
     * The delegate untracks itself on close, and the connection tracked the prepared statement, not
     * the delegate. Without the explicit untrack in {@code LBPreparedStatement.close()} a closed
     * prepared statement stays in the connection's open-statement set until connection close.
     */
    @Test
    public void closingPreparedStatementUntracksIt() throws Exception {
        PreparedStatement ps = conn.prepareStatement("SELECT 1");
        assertEquals(1, conn.getOpenStatementCount());

        ps.close();

        assertEquals(0, conn.getOpenStatementCount());
    }

    /**
     * {@code CUBRIDStatement.getShardId()} declares no {@code throws} clause, so the refusal must
     * be unchecked. Pinned because the natural instinct on review is to "unify" it with the checked
     * refusals elsewhere, which does not compile.
     */
    @Test
    public void shardAccessorsRefuseUnchecked() throws Exception {
        CUBRIDStatement stmt = (CUBRIDStatement) conn.createStatement();
        CUBRIDPreparedStatement ps = (CUBRIDPreparedStatement) conn.prepareStatement("SELECT 1");

        try {
            stmt.getShardId();
            fail("expected the statement shard accessor to refuse");
        } catch (RuntimeException expected) {
            assertTrue(expected.getCause() instanceof java.sql.SQLException);
        }

        try {
            ps.getShardId();
            fail("expected the prepared statement shard accessor to refuse");
        } catch (RuntimeException expected) {
            assertTrue(expected.getCause() instanceof java.sql.SQLException);
        }
    }

    private static void assertNothingInherited(final Class<?> logical, final Class<?> vendor) {
        List<String> missing = new ArrayList<String>();

        for (Class<?> each = vendor;
                each != null && Object.class != each;
                each = each.getSuperclass()) {
            for (Method inherited : each.getDeclaredMethods()) {
                if (!isOverridableFromAnotherPackage(inherited)) {
                    continue;
                }
                try {
                    logical.getDeclaredMethod(inherited.getName(), inherited.getParameterTypes());
                } catch (NoSuchMethodException notOverridden) {
                    missing.add(each.getSimpleName() + "." + signatureOf(inherited));
                }
            }
        }
        Collections.sort(missing);

        assertTrue(
                logical.getSimpleName()
                        + " inherits these from "
                        + vendor.getSimpleName()
                        + "; each would run against the null u_stmt or against unmaintained"
                        + " inherited state: "
                        + missing,
                missing.isEmpty());
    }

    private static List<String> packagePrivateSurfaceOf(final Class<?> vendor) {
        List<String> packagePrivate = new ArrayList<String>();

        for (Method declared : vendor.getDeclaredMethods()) {
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

        return packagePrivate;
    }

    private static boolean isOverridableFromAnotherPackage(final Method method) {
        int modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || method.isSynthetic()) {
            return false;
        }

        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
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
