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

/**
 * Immutable result of LB hint parsing from a SQL statement.
 *
 * <p>{@link TargetHint} is the routing target: mutually exclusive, first one wins. A hint affects
 * only the statement it is written on; there are no session-scoped hints. Use {@code TO_RW} to
 * express read-after-write on the statement that needs it.
 */
public final class HintSet {

    public enum TargetHint {
        TO_RW,
        TO_RO
    }

    private static final HintSet EMPTY = new HintSet(null);
    private final TargetHint targetHint;

    public HintSet(TargetHint targetHint) {
        this.targetHint = targetHint;
    }

    public static HintSet empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return targetHint == null;
    }

    public boolean hasTargetHint() {
        return targetHint != null;
    }

    public TargetHint getTargetHint() {
        return targetHint;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("HintSet{");
        if (targetHint != null) {
            sb.append("target=").append(targetHint);
        }

        if (isEmpty()) {
            sb.append("EMPTY");
        }
        sb.append('}');

        return sb.toString();
    }
}
