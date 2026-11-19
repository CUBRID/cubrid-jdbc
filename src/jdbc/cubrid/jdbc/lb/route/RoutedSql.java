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

package cubrid.jdbc.lb.route;

import cubrid.jdbc.lb.sql.HintParser;
import cubrid.jdbc.lb.sql.HintSet;
import cubrid.jdbc.lb.sql.SessionSetStatement;
import cubrid.jdbc.lb.sql.SqlClassification;
import cubrid.jdbc.lb.sql.SqlClassifier;

/**
 * What one SQL string means for routing: its target hint, whether it changes the session, and its
 * classification. A prepared statement keeps one for all its executions.
 */
public final class RoutedSql {
    private final String text;
    private final SqlClassifier classifier;
    private final HintSet.TargetHint hint;
    private final boolean changesSession;
    private SqlClassification classification;

    private RoutedSql(
            final String text,
            final SqlClassifier classifier,
            final HintSet.TargetHint hint,
            final boolean changesSession) {
        this.text = text;
        this.classifier = classifier;
        this.hint = hint;
        this.changesSession = changesSession;
    }

    public static RoutedSql of(final String sql, final SqlClassifier classifier) {
        final String text = sql == null ? "" : sql;
        final HintSet hints = HintParser.parse(text);
        final HintSet.TargetHint hint = hints.hasTargetHint() ? hints.getTargetHint() : null;
        // Only a TO_RO string is asked: a change made on the read leg alone would reach neither RW
        // nor the read legs opened later, and a variable assigned there is read on RW.
        final boolean changesSession =
                hint == HintSet.TargetHint.TO_RO
                        && (SessionSetStatement.parse(text).changesSession()
                                || SessionSetStatement.assignsUserVariable(text));
        return new RoutedSql(text, classifier, hint, changesSession);
    }

    public String getText() {
        return text;
    }

    /** The target hint, or {@code null} if the string has none. */
    public HintSet.TargetHint getHint() {
        return hint;
    }

    /** Whether this is a {@code TO_RO} string that changes the session or assigns a variable. */
    public boolean changesSession() {
        return changesSession;
    }

    /** Classified on first use: a transaction or a hint decides without it. */
    public SqlClassification getClassification() {
        if (classification == null) {
            classification = classifier.classify(text);
        }
        return classification;
    }
}
