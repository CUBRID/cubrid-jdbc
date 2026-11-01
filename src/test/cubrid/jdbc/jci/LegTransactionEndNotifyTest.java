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

package cubrid.jdbc.jci;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import cubrid.jdbc.driver.CUBRIDConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Core completes every statement of a connection when a transaction ends there. A connection spread
 * over several legs owns each leg's value objects, and core tells it when a leg commits or rolls
 * back so it can complete the other legs' statements too.
 */
public final class LegTransactionEndNotifyTest {

    @Test
    public void commitAndRollbackOnALegTellTheOwner() throws Exception {
        RecordingOwner owner = new RecordingOwner();
        CUBRIDConnection leg = new CUBRIDConnection(new LegSocket(), "jdbc:cubrid:leg", "dba");
        leg.getUConnection().setCUBRIDConnection(owner);

        leg.commit();
        leg.rollback();

        assertEquals(Arrays.asList("commit", "rollback"), owner.ends);
        assertSame(leg, owner.lastLeg);
    }

    /** The logical connection: owns no socket of its own, only records what its legs report. */
    private static final class RecordingOwner extends CUBRIDConnection {

        private final List<String> ends = new ArrayList<String>();

        private CUBRIDConnection lastLeg;

        private RecordingOwner() {
            super("jdbc:cubrid:owner", "dba");
        }

        @Override
        protected void legTransactionEnding(final CUBRIDConnection leg, final boolean commit) {
            ends.add(commit ? "commit" : "rollback");
            lastLeg = leg;
        }
    }

    /** A socket with nothing behind it: ending a transaction has nothing to send. */
    private static final class LegSocket extends UConnection {

        private LegSocket() {
            errorHandler = new UError(this);
        }

        @Override
        public int getPrepStmtCacheSize() {
            return 0;
        }

        public void endTransaction(final boolean type) {}

        protected void closeInternal() {}

        public void setAutoCommit(final boolean autoCommit) {}

        public boolean getAutoCommit() {
            return true;
        }
    }
}
