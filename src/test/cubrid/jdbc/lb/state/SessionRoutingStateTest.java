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

package cubrid.jdbc.lb.state;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionRoutingStateTest {

    // 04 §C-1: manual commit re-arms -- the pin does not drop after the first commit.
    @Test
    public void manualCommitStaysTransactionActiveAcrossCommit() {
        SessionRoutingState s = new SessionRoutingState();
        s.setAutoCommit(false);
        assertTrue(s.isTransactionActive());

        s.onTransactionBoundary();
        assertTrue(
                "manual-commit session must re-arm the transaction after commit",
                s.isTransactionActive());
        assertTrue(s.shouldRouteToRw());

        s.onTransactionBoundary();
        assertTrue("re-arm persists across every commit", s.isTransactionActive());
    }

    // Autocommit has no active transaction after a boundary.
    @Test
    public void autocommitHasNoActiveTransactionAfterBoundary() {
        SessionRoutingState s = new SessionRoutingState();
        assertFalse(s.isTransactionActive());
        s.onTransactionBoundary();
        assertFalse(s.isTransactionActive());
        assertFalse(s.shouldRouteToRw());
    }

    // 04 §M-1 (bullet 2): switching manual -> autocommit clears the transaction pin instead of
    // leaving the session stuck on RW.
    @Test
    public void switchToAutocommitClearsPin() {
        SessionRoutingState s = new SessionRoutingState();
        s.setAutoCommit(false);
        assertTrue(s.shouldRouteToRw());

        s.setAutoCommit(true);
        assertFalse(s.isTransactionActive());
        assertFalse("switch to autocommit must release the transaction pin", s.shouldRouteToRw());
    }

    /**
     * Nothing but a transaction can pin routing. The stateful hints that used to set a session flag
     * were removed in 2026-08-11 precisely because autocommit had no boundary that cleared them —
     * and a pooled connection carried the pin to the next borrower.
     */
    @Test
    public void autocommitSessionIsNeverPinned() {
        SessionRoutingState s = new SessionRoutingState();
        assertFalse(s.shouldRouteToRw());
        s.onTransactionBoundary();
        assertFalse(s.shouldRouteToRw());
        s.setAutoCommit(true);
        assertFalse(s.shouldRouteToRw());
    }
}
