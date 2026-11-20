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
package cubrid.jdbc.lb.report;

import cubrid.jdbc.lb.LbTestReflection;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

/** Test-side access to the state of {@code cubrid.jdbc.lb.report}. It lives in the test tree. */
public final class ReportTestAccess {
    private ReportTestAccess() {}

    /** Disarms the distribution record and forgets its window. */
    public static void resetDistLog() {
        synchronized (LbDistLog.class) {
            LbTestReflection.setStatic(LbDistLog.class, "armed", Boolean.FALSE);
            LbTestReflection.setStatic(LbDistLog.class, "intervalNanos", Long.valueOf(0L));
            LbTestReflection.setStatic(LbDistLog.class, "target", null);
            LbTestReflection.setStatic(LbDistLog.class, "previous", Collections.emptyMap());
            ((AtomicLong) LbTestReflection.staticField(LbDistLog.class, "lastEmitNanos")).set(0L);
        }
    }

    /** Forgets the configurations already declared. */
    public static void resetTopologyLog() {
        synchronized (LbTopologyLog.class) {
            ((Collection<?>) LbTestReflection.staticField(LbTopologyLog.class, "SEEN")).clear();
        }
    }
}
