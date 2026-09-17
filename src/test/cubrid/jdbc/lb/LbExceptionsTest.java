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

package cubrid.jdbc.lb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.driver.CUBRIDException;
import cubrid.jdbc.driver.CUBRIDJDBCErrorCode;
import java.sql.SQLException;
import org.junit.Test;

public class LbExceptionsTest {

    @Test
    public void brokerGroupExhaustedUsesLbErrorCodeAndCause() {
        SQLException root = new SQLException("connect refused");
        CUBRIDException ex = LbExceptions.brokerGroupExhausted("RW", "rw1:33000, rw2:33000", root);

        assertEquals(LbJDBCErrorCode.lb_broker_group_exhausted, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("group=RW"));
        assertTrue(ex.getMessage().contains("rw1:33000"));
        assertEquals(root, ex.getCause());
    }

    @Test
    public void brokerCandidatesFilteredUsesLbErrorCode() {
        CUBRIDException ex = LbExceptions.brokerCandidatesFiltered("RO", "no candidates");

        assertEquals(LbJDBCErrorCode.lb_broker_candidates_filtered, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("group=RO"));
        assertTrue(ex.getMessage().contains("no candidates"));
    }

    @Test
    public void failoverTxForbiddenUsesLbErrorCode() {
        CUBRIDException ex = LbExceptions.failoverTxForbidden("RW");

        assertEquals(LbJDBCErrorCode.lb_failover_tx_forbidden, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("role=RW"));
    }

    @Test
    public void failoverExhaustedUsesLbErrorCodeAndCause() {
        SQLException root = new SQLException("ro down");
        CUBRIDException ex = LbExceptions.failoverExhausted("RO", "rwFallback=disabled", root);

        assertEquals(LbJDBCErrorCode.lb_failover_exhausted, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("role=RO"));
        assertTrue(ex.getMessage().contains("rwFallback=disabled"));
        assertEquals(root, ex.getCause());
    }

    @Test
    public void failoverRoRwFallbackFailedUsesLbErrorCodeAndCause() {
        SQLException root = new SQLException("no rw");
        CUBRIDException ex = LbExceptions.failoverRoRwFallbackFailed(root);

        assertEquals(LbJDBCErrorCode.lb_failover_ro_rw_fallback_failed, ex.getErrorCode());
        assertEquals(root, ex.getCause());
    }

    @Test
    public void invalidValueUsesCoreErrorCode() {
        CUBRIDException ex = LbExceptions.invalidValue("RW endpoint must not be null");

        assertEquals(CUBRIDJDBCErrorCode.invalid_value, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("RW endpoint must not be null"));
    }

    @Test
    public void notSupportedUsesCoreErrorCode() {
        CUBRIDException ex = LbExceptions.notSupported();

        assertEquals(CUBRIDJDBCErrorCode.not_supported, ex.getErrorCode());
    }

    @Test
    public void optionInvalidUsesLbErrorCode() {
        CUBRIDException ex = LbExceptions.optionInvalid("readWeight is empty");

        assertEquals(LbJDBCErrorCode.lb_option_invalid, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("readWeight is empty"));
    }

    @Test
    public void internalStateUsesLbErrorCode() {
        CUBRIDException ex = LbExceptions.internalState("missing RW endpoint");

        assertEquals(LbJDBCErrorCode.lb_internal_state, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("missing RW endpoint"));
    }

    @Test
    public void configHelpersUseLbErrorCodes() {
        assertEquals(
                LbJDBCErrorCode.lb_invalid_url,
                LbExceptions.invalidUrl("logical JDBC URL is null", null).getErrorCode());
    }

    @Test
    public void sessionAndStatementHelpersUseLbErrorCodes() {
        assertEquals(
                LbJDBCErrorCode.lb_session_not_initialized,
                LbExceptions.sessionNotInitialized("role=RW").getErrorCode());
        assertEquals(
                LbJDBCErrorCode.lb_topology_invalid,
                LbExceptions.topologyInvalid("Session distribution requires an RW endpoint")
                        .getErrorCode());
        assertEquals(
                LbJDBCErrorCode.lb_physical_not_bound,
                LbExceptions.physicalNotBound("repl1:33000").getErrorCode());
        assertEquals(
                LbJDBCErrorCode.lb_stmt_no_execution_history,
                LbExceptions.noExecutionHistory("query plan").getErrorCode());
        assertEquals(
                LbJDBCErrorCode.lb_delegation_not_configured,
                LbExceptions.delegationNotConfigured().getErrorCode());
        assertEquals(
                CUBRIDJDBCErrorCode.connection_closed,
                LbExceptions.connectionClosed().getErrorCode());
        assertEquals(
                CUBRIDJDBCErrorCode.statement_closed,
                LbExceptions.statementClosed().getErrorCode());
        assertEquals(
                LbJDBCErrorCode.lb_tx_autocommit_only,
                LbExceptions.txAutocommitOnly().getErrorCode());
    }

    @Test
    public void getMessageReturnsRegisteredText() {
        assertNotNull(LbJDBCErrorCode.getMessage(LbJDBCErrorCode.lb_broker_group_exhausted));
    }
}
