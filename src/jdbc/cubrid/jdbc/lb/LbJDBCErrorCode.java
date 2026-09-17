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

import java.util.HashMap;
import java.util.Map;

/** LB-JDBC error codes ({@code -23000} band; see {@code docs/LB-JDBC-Error-Codes.md}). */
public final class LbJDBCErrorCode {

    public static final int lb_invalid_url = -23003;

    public static final int lb_session_not_initialized = -23004;
    public static final int lb_topology_invalid = -23005;
    public static final int lb_physical_not_bound = -23007;

    public static final int lb_broker_group_exhausted = -23009;
    public static final int lb_broker_candidates_filtered = -23010;

    public static final int lb_failover_tx_forbidden = -23011;
    public static final int lb_failover_exhausted = -23012;
    public static final int lb_failover_ro_rw_fallback_failed = -23013;

    public static final int lb_stmt_no_execution_history = -23014;
    public static final int lb_delegation_not_configured = -23015;
    public static final int lb_physical_delegate_failed = -23016;
    public static final int lb_tx_autocommit_only = -23017;
    public static final int lb_unsupported_route = -23018;

    public static final int lb_option_invalid = -23019;
    public static final int lb_internal_state = -23020;

    private static final Map<Integer, String> MESSAGES = build();

    private LbJDBCErrorCode() {}

    private static Map<Integer, String> build() {
        Map<Integer, String> messageString = new HashMap<Integer, String>();

        messageString.put(Integer.valueOf(lb_invalid_url), "Invalid JDBC URL for load balance.");

        messageString.put(
                Integer.valueOf(lb_session_not_initialized),
                "Session endpoints are not initialized.");
        messageString.put(
                Integer.valueOf(lb_topology_invalid),
                "Endpoint topology is invalid for session distribution.");
        messageString.put(
                Integer.valueOf(lb_physical_not_bound), "No physical connection for endpoint.");

        messageString.put(
                Integer.valueOf(lb_broker_group_exhausted),
                "All broker candidates in group failed.");
        messageString.put(
                Integer.valueOf(lb_broker_candidates_filtered),
                "No broker candidates remain after filters.");

        messageString.put(
                Integer.valueOf(lb_failover_tx_forbidden),
                "Runtime failover is not allowed during an active transaction.");
        messageString.put(
                Integer.valueOf(lb_failover_exhausted),
                "Runtime failover failed; no reachable broker in role group.");
        messageString.put(
                Integer.valueOf(lb_failover_ro_rw_fallback_failed), "RO failover to RW failed.");

        messageString.put(
                Integer.valueOf(lb_stmt_no_execution_history),
                "No prior execution for this operation.");
        messageString.put(
                Integer.valueOf(lb_delegation_not_configured),
                "Physical statement delegation is not configured.");
        messageString.put(
                Integer.valueOf(lb_physical_delegate_failed),
                "Failed to invoke physical JDBC extension.");
        messageString.put(
                Integer.valueOf(lb_tx_autocommit_only),
                "Transaction control is not allowed in auto-commit mode.");
        messageString.put(
                Integer.valueOf(lb_unsupported_route),
                "Unsupported route target for load balance.");

        messageString.put(Integer.valueOf(lb_option_invalid), "Invalid load balance option.");
        messageString.put(Integer.valueOf(lb_internal_state), "Load balance internal error.");
        return messageString;
    }

    public static String getMessage(final int code) {
        return MESSAGES.get(Integer.valueOf(code));
    }
}
