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

package cubrid.jdbc.lb.failover;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Failover coverage is a whitelist: LB wraps physical access <b>call site by call site</b>, so
 * nothing but review stops a new one from being added unprotected. Four such gaps were found by
 * hand in 2026-08 (prepared-statement metadata, the {@code DatabaseMetaData} handle, {@code
 * createNClob}/{@code createSQLXML}, eight session-property getters). This test replaces that
 * review with a check the build performs.
 *
 * <p>It scans the LB production sources for every call to a <b>physical access primitive</b> —
 * {@code getPhysicalConnForCmd}, {@code getPhyConn}, {@code getPreparedStatement} — and requires
 * each one to sit lexically inside a failover wrapper ({@code runRwCommand}, {@code
 * executeWithFailover}, {@code executeCommandWithFailover}, {@code commandWithFailover}). A call
 * site that is not may only stand if the enclosing method is classified in {@link #CLASSIFIED}
 * below, with a tag and a reason.
 *
 * <p>The two tags carry different meanings:
 *
 * <ul>
 *   <li><b>HELPER</b> — the method itself is not wrapped, but every caller of it is. The guard is
 *       one frame up.
 *   <li><b>EXEMPT</b> — deliberately not covered. The reason states why, and points at the spec
 *       section or pending issue that owns the decision.
 * </ul>
 *
 * <p>So {@link #CLASSIFIED} doubles as the inventory of what failover does <em>not</em> cover.
 * Compare it with the "발동 지점" table in {@code jdbc-loadbalance-spec.md} §11; the two must agree.
 *
 * <p><b>What this cannot see.</b> The analysis is lexical: it follows neither reflection nor an
 * indirect call through a field. LB has no such path today (the vendor reflection was removed in
 * 2026-07), and a future one would pass this test while being unguarded.
 */
public final class FailoverCoverageArchitectureTest {

    /** Obtaining a physical connection or a physical prepared statement. */
    private static final String[] PRIMITIVES = {
        "getPhysicalConnForCmd", "getPhyConn", "getPreparedStatement"
    };

    /** Anything reaching a primitive from inside one of these is covered. */
    private static final String[] WRAPPERS = {
        "runRwCommand", "executeWithFailover", "executeCommandWithFailover", "commandWithFailover"
    };

    /**
     * Enclosing methods that may hold an unwrapped primitive call: {@code method}, expected number
     * of such calls, tag and reason. The count is part of the contract — a new unguarded call in an
     * already-classified method fails the test too, so the classification is re-read rather than
     * inherited.
     */
    private static final String[][] CLASSIFIED = {
        // --- HELPER: the guard is at every caller ---
        {
            "LoadBalanceConnection#getPhysicalConnForCmd", "1",
            "HELPER", "resolves a leg and never recovers one; wrapping its callers is the rule"
        },
        {
            "LoadBalanceConnection#createPsProvider",
            "1",
            "HELPER",
            "physical PS provider; called from physicalPsForTarget/physicalPsForMetadata,"
                    + " both inside a handler block"
        },
        {
            "LoadBalanceConnection#createStatementProvider",
            "1",
            "HELPER",
            "physical Statement provider; called from LBStatement#createPhyStmt, which"
                    + " runs inside every execute closure"
        },
        {
            "LBPreparedStatement#physicalPsForTarget", "1",
            "HELPER", "called only from the execute closures"
        },
        {
            "LBPreparedStatement#physicalPsForMetadata", "1",
            "HELPER", "called only from the getMetaData/getParameterMetaData closures"
        },

        // --- EXEMPT: transaction-scoped, so the tx rule already forbids failover ---
        {
            "LoadBalanceConnection#setSavepoint",
            "2",
            "EXEMPT",
            "a savepoint exists only inside a transaction, and failover is forbidden"
                    + " while one is active (spec 11)"
        },
        {
            "LoadBalanceConnection#rollback",
            "1",
            "EXEMPT",
            "rollback(Savepoint): same as setSavepoint"
        },
        {
            "LoadBalanceConnection#releaseSavepoint", "1",
            "EXEMPT", "same as setSavepoint"
        },

        // --- EXEMPT: session-property propagation, both legs, per-leg error isolation ---
        {
            "LoadBalanceConnection#forEachSessPhyConn",
            "1",
            "EXEMPT",
            "the READ leg of a property setter: a read-leg failure is isolated and logged rather"
                    + " than rebound, because the read path self-heals on the next statement. The"
                    + " write leg goes through applyToWriteLegWithFailover (ISSUE-6, fixed)"
        },
        {
            "LoadBalanceConnection#applyToWriteLegWithFailover",
            "1",
            "EXEMPT",
            "the re-apply that follows a rebind: the handler already recovered the leg and does not"
                    + " replay a write target, so applying the (idempotent) property to the new leg"
                    + " is the point of this method - wrapping it again would recurse"
        },
        {
            "LoadBalanceConnection#applyCharset", "2",
            "EXEMPT", "same family as forEachSessPhyConn (ISSUE-6)"
        },
        {
            "LoadBalanceConnection#setCASChangeMode", "2",
            "EXEMPT", "same family as forEachSessPhyConn (ISSUE-6)"
        },
        {
            "LoadBalanceConnection#Login", "4",
            "EXEMPT", "vendor login key applied to both legs; same family as forEachSessPhyConn"
        },

        // --- EXEMPT: recovery would be meaningless or self-defeating ---
        {
            "LoadBalanceConnection#tryMaterializeBothLegs", "2",
            "EXEMPT", "pool probe: swallowing the failure and answering false is the purpose"
        },
        {
            "LoadBalanceConnection#getUConnection",
            "1",
            "EXEMPT",
            "hands the raw core connection to vendor code; everything after it is outside"
                    + " LB's view (spec 11, 보장하지 않는 것)"
        },
    };

    private static final String[] SOURCE_ROOTS = {
        "src/jdbc/cubrid/jdbc/lb",
        "cubrid-jdbc/src/jdbc/cubrid/jdbc/lb",
        "../src/jdbc/cubrid/jdbc/lb"
    };

    @Test
    public void everyPhysicalCallSiteIsGuardedOrClassified() throws IOException {
        File root = sourceRoot();
        List<Site> unguarded = new ArrayList<Site>();
        List<File> files = new ArrayList<File>();
        collectJava(root, files);
        assertTrue("no LB sources found under " + root, files.size() > 10);

        for (File f : files) {
            scan(f, unguarded);
        }

        Map<String, Integer> found = new LinkedHashMap<String, Integer>();
        for (Site s : unguarded) {
            String key = s.key();
            Integer n = found.get(key);
            found.put(key, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }

        List<String> problems = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : found.entrySet()) {
            String[] row = classifiedRow(e.getKey());
            if (row == null) {
                problems.add(
                        "NEW unguarded physical call in "
                                + e.getKey()
                                + " ("
                                + e.getValue()
                                + "x) at "
                                + linesOf(unguarded, e.getKey()));
                continue;
            }
            int expected = Integer.parseInt(row[1]);
            if (expected != e.getValue().intValue()) {
                problems.add(
                        e.getKey()
                                + " is classified for "
                                + expected
                                + " unguarded call(s) but has "
                                + e.getValue()
                                + " at "
                                + linesOf(unguarded, e.getKey()));
            }
        }
        for (String[] row : CLASSIFIED) {
            if (!found.containsKey(row[0])) {
                problems.add(
                        row[0]
                                + " is classified as "
                                + row[2]
                                + " but has no unguarded call any more - remove the entry");
            }
        }

        if (!problems.isEmpty()) {
            fail(report(problems));
        }
    }

    private static String report(final List<String> problems) {
        StringBuilder sb = new StringBuilder();
        sb.append("Failover coverage changed:\n");
        for (String p : problems) {
            sb.append("  - ").append(p).append('\n');
        }
        sb.append("\nA physical call must sit inside a failover wrapper:\n");
        sb.append("    runRwCommand(\"label\", new ExecuteFailoverHandler.SqlExecution<T>() {...})")
                .append("   (connection command, not timed)\n");
        sb.append("    executeWithFailover(sql, target, retrySafe, ...)")
                .append("                       (statement execution)\n");
        sb.append("\nIf it must not be wrapped, add the enclosing method to CLASSIFIED with a tag")
                .append(" (HELPER/EXEMPT) and a reason.\n");
        sb.append("Judgement rule: jdbc-loadbalance-spec.md 11 \"발동 지점\".\n");

        return sb.toString();
    }

    private static String[] classifiedRow(final String key) {
        for (String[] row : CLASSIFIED) {
            if (row[0].equals(key)) {
                return row;
            }
        }

        return null;
    }

    private static String linesOf(final List<Site> sites, final String key) {
        TreeSet<Integer> lines = new TreeSet<Integer>();
        String file = null;
        for (Site s : sites) {
            if (s.key().equals(key)) {
                lines.add(Integer.valueOf(s.line));
                file = s.file;
            }
        }

        return file + ":" + lines;
    }

    /* ===== scanning ===== */

    private static final class Site {
        private final String file;
        private final String type;
        private final String method;
        private final int line;

        private Site(final String file, final String type, final String method, final int line) {
            this.file = file;
            this.type = type;
            this.method = method;
            this.line = line;
        }

        private String key() {
            return type + "#" + method;
        }
    }

    private void scan(final File f, final List<Site> out) throws IOException {
        String src = read(f);
        String masked = maskCommentsAndLiterals(src);
        List<int[]> decls = declarations(masked); // {offset, lineStart, lineEnd}
        List<String> declNames = declarationNames(masked, decls);
        String type = f.getName().substring(0, f.getName().length() - ".java".length());

        for (String primitive : PRIMITIVES) {
            int from = 0;
            while (true) {
                int at = indexOfCall(masked, primitive, from);
                if (at < 0) {
                    break;
                }
                from = at + primitive.length();

                int lineStart = masked.lastIndexOf('\n', at) + 1;
                int lineEnd = masked.indexOf('\n', at);
                String lineText =
                        masked.substring(lineStart, lineEnd < 0 ? masked.length() : lineEnd);
                if (primitive.equals(declaredName(lineText))) {
                    continue; // the declaration of the primitive itself
                }

                int idx = enclosingDeclaration(decls, at);
                int limit = idx < 0 ? 0 : decls.get(idx)[0];
                if (insideWrapper(masked, at, limit)) {
                    continue;
                }
                String method = idx < 0 ? "<top>" : declNames.get(idx);
                out.add(new Site(f.getName(), type, method, lineNumber(src, at)));
            }
        }
    }

    /** Walks back to {@code limit}, collecting the callee of every unclosed {@code (}. */
    private static boolean insideWrapper(final String s, final int pos, final int limit) {
        int depth = 0;
        for (int i = pos - 1; i >= limit; i--) {
            char c = s.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                int j = i - 1;
                while (j >= 0 && Character.isWhitespace(s.charAt(j))) {
                    j--;
                }
                int k = j;
                while (k >= 0 && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) {
                    k--;
                }
                String callee = s.substring(k + 1, j + 1);
                for (String w : WRAPPERS) {
                    if (w.equals(callee)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static int indexOfCall(final String s, final String name, final int from) {
        int at = from;
        while (true) {
            at = s.indexOf(name, at);
            if (at < 0) {
                return -1;
            }
            int before = at - 1;
            boolean wordStart =
                    before < 0
                            || !(Character.isLetterOrDigit(s.charAt(before))
                                    || s.charAt(before) == '_');
            int after = at + name.length();
            while (after < s.length() && s.charAt(after) == ' ') {
                after++;
            }
            if (wordStart && after < s.length() && s.charAt(after) == '(') {
                return at;
            }
            at = at + name.length();
        }
    }

    private static int enclosingDeclaration(final List<int[]> decls, final int pos) {
        int found = -1;
        for (int i = 0; i < decls.size(); i++) {
            if (decls.get(i)[0] > pos) {
                break;
            }
            found = i;
        }

        return found;
    }

    private static List<int[]> declarations(final String masked) {
        List<int[]> out = new ArrayList<int[]>();
        int offset = 0;
        while (offset <= masked.length()) {
            int end = masked.indexOf('\n', offset);
            if (end < 0) {
                end = masked.length();
            }
            String line = masked.substring(offset, end);
            String name = declaredName(line);
            if (name != null) {
                out.add(new int[] {offset + line.indexOf(name), offset, end});
            }
            offset = end + 1;
            if (end == masked.length()) {
                break;
            }
        }

        return out;
    }

    private static List<String> declarationNames(final String masked, final List<int[]> decls) {
        List<String> names = new ArrayList<String>();
        for (int[] d : decls) {
            names.add(declaredName(masked.substring(d[1], d[2])));
        }

        return names;
    }

    /**
     * The method name if the line declares one, else {@code null}. A declaration is indented at
     * most 8 spaces (so a wrapped expression or an anonymous class body is not one), carries a
     * return type or modifier before the name, and holds no assignment.
     */
    private static String declaredName(final String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        if (indent > 8 || indent == line.length()) {
            return null;
        }
        if (line.indexOf('=') >= 0) {
            return null;
        }
        String trimmed = line.trim();
        char last = trimmed.charAt(trimmed.length() - 1);
        if (last != '{' && last != ')' && last != ',' && last != ';' && last != '(') {
            return null;
        }
        int paren = trimmed.indexOf('(');
        if (paren < 0) {
            return null;
        }
        String head = trimmed.substring(0, paren).trim();
        int lastSpace = head.lastIndexOf(' ');
        if (lastSpace < 0) {
            return null; // no return type/modifier: this is a call, not a declaration
        }
        String name = head.substring(lastSpace + 1);
        String prefix = head.substring(0, lastSpace).trim();
        if (name.length() == 0 || prefix.length() == 0) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return null;
            }
        }
        if ("catch".equals(name)
                || "if".equals(name)
                || "for".equals(name)
                || "while".equals(name)
                || "switch".equals(name)
                || "synchronized".equals(name)
                || "try".equals(name)) {
            return null; // a control-flow header, not a declaration
        }
        String firstToken = prefix.split("[ <]")[0];
        if ("if".equals(firstToken)
                || "for".equals(firstToken)
                || "while".equals(firstToken)
                || "switch".equals(firstToken)
                || "catch".equals(firstToken)
                || "return".equals(firstToken)
                || "new".equals(firstToken)
                || "else".equals(firstToken)
                || "throw".equals(firstToken)) {
            return null;
        }

        return name;
    }

    /** Blanks out comments and string/char literals, keeping every offset intact. */
    private static String maskCommentsAndLiterals(final String src) {
        char[] out = src.toCharArray();
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                while (i + 1 < src.length()
                        && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                out[i++] = ' ';
                if (i < src.length()) {
                    out[i++] = ' ';
                }
            } else if (c == '"' || c == '\'') {
                out[i++] = ' ';
                while (i < src.length() && src.charAt(i) != c) {
                    if (src.charAt(i) == '\\') {
                        out[i++] = ' ';
                    }
                    if (i < src.length() && src.charAt(i) != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < src.length()) {
                    out[i++] = ' ';
                }
            } else {
                i++;
            }
        }

        return new String(out);
    }

    private static int lineNumber(final String src, final int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }

        return line;
    }

    private static void collectJava(final File dir, final List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File e : entries) {
            if (e.isDirectory()) {
                collectJava(e, out);
            } else if (e.getName().endsWith(".java")) {
                out.add(e);
            }
        }
    }

    private static File sourceRoot() {
        for (String candidate : SOURCE_ROOTS) {
            File f = new File(candidate);
            if (f.isDirectory()) {
                return f;
            }
        }

        throw new IllegalStateException(
                "LB source tree not found from working directory "
                        + new File(".").getAbsolutePath()
                        + "; tried "
                        + java.util.Arrays.toString(SOURCE_ROOTS));
    }

    private static String read(final File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }

            return new String(buffer.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
