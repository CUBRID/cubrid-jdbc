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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.state.SessionRoutingState;
import org.junit.Before;
import org.junit.Test;

public class HintChecklistTest {

    private SessionRoutingState session;

    @Before
    public void setUp() {
        session = new SessionRoutingState();
    }

    @Test
    public void assertChecklistToRwRoutesToRw() {
        final String sql = "SELECT /*+ TO_RW */ * FROM t";
        Router.RouteDecision d = Router.decide(sql, session, failingDefaultAdvisor());
        assertEquals(Router.RouteTarget.TO_READ_WRITE, d.getTarget());
        assertSame(sql, d.getSql());
    }

    @Test
    public void assertChecklistToRoRoutesToRo() {
        final String sql = "SELECT /*+ TO_RO */ * FROM t";
        Router.RouteDecision d = Router.decide(sql, session, failingDefaultAdvisor());
        assertEquals(Router.RouteTarget.TO_READ_ONLY, d.getTarget());
        assertSame(sql, d.getSql());
    }

    @Test
    public void assertChecklistMisplacedCommentNotRecognizedAsLbHint() {
        final String sql = "SELECT * FROM t /*+ TO_RW */ WHERE id = 1";
        Router.RouteDecision d =
                Router.decide(
                        sql,
                        session,
                        new Router.DefaultRouteAdvisor() {
                            @Override
                            public Router.RouteTarget defaultTarget(final String s) {
                                return Router.RouteTarget.TO_READ_ONLY;
                            }
                        });
        assertEquals(Router.RouteTarget.TO_READ_ONLY, d.getTarget());
        assertSame(sql, d.getSql());
    }

    @Test
    public void assertChecklistDuplicateTargetHintFirstWins() {
        final String sql = "SELECT /*+ TO_RW TO_RO */ * FROM t";
        Router.RouteDecision d = Router.decide(sql, session, failingDefaultAdvisor());
        assertEquals(Router.RouteTarget.TO_READ_WRITE, d.getTarget());
    }

    @Test
    public void assertChecklistOptimizerHintMixedRoutingUsesLbHintOnly() {
        final String sql =
                "SELECT /*+ USE_NL(a b) TO_RO ORDERED */ a.x FROM t1 a JOIN t2 b ON a.id=b.id";
        Router.RouteDecision d = Router.decide(sql, session, failingDefaultAdvisor());
        assertEquals(Router.RouteTarget.TO_READ_ONLY, d.getTarget());
        assertSame(sql, d.getSql());
        assertEquals(sql.indexOf("USE_NL"), d.getSql().indexOf("USE_NL"));
    }

    /**
     * A hint leaves nothing behind. The stateful trio that used to pin the session was removed in
     * 2026-08-11, so routing state can only be moved by transaction boundaries — the same statement
     * parsed twice must decide the same way, and an unrelated later statement must be unaffected.
     */
    @Test
    public void assertHintDoesNotPinTheSession() {
        Router.decide("SELECT /*+ TO_RW */ * FROM t", session, failingDefaultAdvisor());

        assertFalse("a hint must not mark the session", session.shouldRouteToRw());
        Router.RouteDecision next =
                Router.decide("SELECT /*+ TO_RO */ * FROM t", session, failingDefaultAdvisor());
        assertEquals(Router.RouteTarget.TO_READ_ONLY, next.getTarget());
    }

    private static Router.DefaultRouteAdvisor failingDefaultAdvisor() {
        return new Router.DefaultRouteAdvisor() {
            @Override
            public Router.RouteTarget defaultTarget(final String s) {
                fail("Default advisor must not run when LB hint decides routing");
                return null;
            }
        };
    }
}
