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

package cubrid.jdbc.lb.sql;

import java.util.Collections;
import java.util.Set;

/**
 * What LB works out from one SQL text besides its classification: the hint, the session change, the
 * variables it assigns or drops. Every reader asks this one object, so the text is read once
 * however many steps of an execution need it, and the same text gets the same answers.
 *
 * <p>Kept JVM-wide by text ({@link SqlTextCache}); each fact is worked out the first time it is
 * asked. Immutable for its readers: a fact computed twice by racing threads is the same value.
 */
public final class SqlFacts {
    // The bound of the classification cache, for the same texts.
    private static final int MAX_ENTRIES = 8192;
    private static final SqlTextCache<SqlFacts> CACHE = new SqlTextCache<SqlFacts>(MAX_ENTRIES);
    private static final SqlTextCache.Source<SqlFacts> NEW =
            new SqlTextCache.Source<SqlFacts>() {
                public SqlFacts of(final String sql) {
                    return new SqlFacts(sql);
                }
            };

    private final String text;
    private volatile HintSet hints;
    private volatile SessionSetStatement sessionSet;
    private volatile Set<String> assignedVariables;
    private volatile Set<String> droppedVariables;
    private volatile Set<String> assignedIfFailed;
    private volatile Boolean assignsUserVariable;
    private volatile Boolean preparesOrDropsAStatement;

    private SqlFacts(final String text) {
        this.text = text;
    }

    /**
     * The facts of {@code sql}; a {@code null} text is the empty one.
     *
     * @param sql the statement as the application issued it
     * @return its facts
     */
    public static SqlFacts of(final String sql) {
        return CACHE.get(sql == null ? "" : sql, NEW);
    }

    public String text() {
        return text;
    }

    public HintSet hints() {
        HintSet v = hints;
        if (v == null) {
            v = HintParser.parse(text);
            hints = v;
        }
        return v;
    }

    public SessionSetStatement sessionSet() {
        SessionSetStatement v = sessionSet;
        if (v == null) {
            v = SessionSetStatement.parse(text);
            sessionSet = v;
        }
        return v;
    }

    /** {@link IntoVariables#assignedBy}. */
    public Set<String> assignedVariables() {
        Set<String> v = assignedVariables;
        if (v == null) {
            v = Collections.unmodifiableSet(IntoVariables.assignedBy(text));
            assignedVariables = v;
        }
        return v;
    }

    /** {@link IntoVariables#droppedBy}. */
    public Set<String> droppedVariables() {
        Set<String> v = droppedVariables;
        if (v == null) {
            v = Collections.unmodifiableSet(IntoVariables.droppedBy(text));
            droppedVariables = v;
        }
        return v;
    }

    /** {@link IntoVariables#assignedIfFailed}. */
    public Set<String> assignedIfFailed() {
        Set<String> v = assignedIfFailed;
        if (v == null) {
            v = Collections.unmodifiableSet(IntoVariables.assignedIfFailed(text));
            assignedIfFailed = v;
        }
        return v;
    }

    /** {@link SessionSetStatement#assignsUserVariable}. */
    public boolean assignsUserVariable() {
        Boolean v = assignsUserVariable;
        if (v == null) {
            v = Boolean.valueOf(SessionSetStatement.assignsUserVariable(text));
            assignsUserVariable = v;
        }
        return v.booleanValue();
    }

    /** {@link SessionSetStatement#preparesOrDropsAStatement}. */
    public boolean preparesOrDropsAStatement() {
        Boolean v = preparesOrDropsAStatement;
        if (v == null) {
            v = Boolean.valueOf(SessionSetStatement.preparesOrDropsAStatement(text));
            preparesOrDropsAStatement = v;
        }
        return v.booleanValue();
    }
}
