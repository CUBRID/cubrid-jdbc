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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.state.SessionRoutingState;
import org.junit.Before;
import org.junit.Test;

public class RouterTransactionPrecedenceTest {

    private SessionRoutingState session;

    @Before
    public void setUp() {
        session = new SessionRoutingState();
        session.setAutoCommit(false);
    }

    @Test
    public void assertActiveTransactionInvalidatesToRoHint() {
        final String sql = "SELECT /*+ TO_RO */ * FROM t";
        Router.RouteDecision d = Router.decide(sql, session, failingDefaultAdvisor());
        assertEquals(Router.RouteTarget.TO_READ_WRITE, d.getTarget());
    }

    @Test
    public void assertActiveTransactionInvalidatesToSlaveHint() {
        final String sql = "SELECT /*+ TO_SLAVE */ * FROM t";
        Router.RouteDecision d = Router.decide(sql, session, failingDefaultAdvisor());
        assertEquals(Router.RouteTarget.TO_READ_WRITE, d.getTarget());
    }

    @Test
    public void assertActiveTransactionPreservesToRwHint() {
        final String sql = "SELECT /*+ TO_RW */ * FROM t";
        Router.RouteDecision d = Router.decide(sql, session, failingDefaultAdvisor());
        assertEquals(Router.RouteTarget.TO_READ_WRITE, d.getTarget());
    }

    /**
     * A manual-commit session stays transaction-pinned across commit()/rollback(): CUBRID begins
     * the next transaction implicitly, so a non-Master hint remains invalid (routes to RW) and a
     * single logical transaction never spans two physical connections (04 §C-1).
     */
    @Test
    public void assertManualCommitStaysTransactionPinnedAfterCommit() {
        session.onTransactionBoundary();
        final String sql = "SELECT /*+ TO_RO */ * FROM t";
        Router.RouteDecision d = Router.decide(sql, session, failingDefaultAdvisor());
        assertEquals(Router.RouteTarget.TO_READ_WRITE, d.getTarget());
    }

    @Test
    public void assertNonTransactionalToRoHonored() {
        SessionRoutingState autoCommit = new SessionRoutingState();
        final String sql = "SELECT /*+ TO_RO */ * FROM t";
        Router.RouteDecision d = Router.decide(sql, autoCommit, failingDefaultAdvisor());
        assertEquals(Router.RouteTarget.TO_READ_ONLY, d.getTarget());
    }

    private static Router.DefaultRouteAdvisor failingDefaultAdvisor() {
        return new Router.DefaultRouteAdvisor() {
            @Override
            public Router.RouteTarget defaultTarget(final String s) {
                fail("Default advisor must not run when transaction or hint decides routing");
                return null;
            }
        };
    }
}
