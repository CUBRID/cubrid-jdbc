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

import cubrid.jdbc.driver.CUBRIDException;
import cubrid.jdbc.driver.CUBRIDJDBCErrorCode;
import java.sql.SQLException;

/** Factory for LB-JDBC {@link CUBRIDException} instances ({@link LbJDBCErrorCode} band). */
public final class LbExceptions {

    private LbExceptions() {}

    public static CUBRIDException connectionClosed() {
        return new CUBRIDException(CUBRIDJDBCErrorCode.connection_closed);
    }

    public static CUBRIDException statementClosed() {
        return new CUBRIDException(CUBRIDJDBCErrorCode.statement_closed);
    }

    public static CUBRIDException notSupported() {
        return new CUBRIDException(CUBRIDJDBCErrorCode.not_supported);
    }

    public static CUBRIDException notSupported(final Throwable cause) {
        return withCoreDetail(CUBRIDJDBCErrorCode.not_supported, null, cause);
    }

    /**
     * A CUBRID vendor extension the LB connection deliberately does not serve: SHARD-only APIs
     * (there is no shard behind a load-balance session) and driver-internal plumbing that only runs
     * on a physical connection. Named, so callers see which API was refused rather than a bare "Not
     * supported method".
     *
     * @param api the refused API, e.g. {@code "isShard()"}
     * @return the exception to throw
     */
    public static CUBRIDException notSupportedApi(final String api) {
        return withCoreDetail(
                CUBRIDJDBCErrorCode.not_supported,
                api + " - SHARD/driver-internal API, not served by the LB connection",
                null);
    }

    public static CUBRIDException invalidValue(final String detail) {
        return withCoreDetail(CUBRIDJDBCErrorCode.invalid_value, detail, null);
    }

    public static CUBRIDException optionInvalid(final String detail) {
        return withDetail(LbJDBCErrorCode.lb_option_invalid, detail, null);
    }

    /**
     * Invalid value for a known URL option, worded exactly like the core driver ({@code
     * ConnectionProperties}): {@code "invalid URL - '<value>' uncompitable value for the <name>"},
     * so LB and core report option-value errors identically. Only the message text mirrors core;
     * the LB error-code band stays.
     *
     * @param propertyName the option name whose value was rejected
     * @param value the rejected value, as written by the user
     * @return the exception to throw
     */
    public static CUBRIDException optionValueIncompatible(
            final String propertyName, final String value) {
        final String message =
                CUBRIDJDBCErrorCode.getMessage(CUBRIDJDBCErrorCode.invalid_url)
                        + " '"
                        + value
                        + "' uncompitable value for the "
                        + propertyName;
        return new LbException(message, LbJDBCErrorCode.lb_option_invalid, null);
    }

    public static CUBRIDException internalState(final String detail) {
        return withDetail(LbJDBCErrorCode.lb_internal_state, detail, null);
    }

    public static CUBRIDException invalidUrl(final String detail, final Throwable cause) {
        return withDetail(LbJDBCErrorCode.lb_invalid_url, detail, cause);
    }

    /**
     * Structurally malformed URL, worded exactly like the core driver ({@code "invalid URL -
     * <url>"}), so LB reports it identically (excess db-cred segments, for example). Message text
     * only; the LB error-code band stays.
     *
     * @param url the malformed URL, appended to the core message verbatim
     * @return the exception to throw
     */
    public static CUBRIDException invalidUrlLikeCore(final String url) {
        return new LbException(
                CUBRIDJDBCErrorCode.getMessage(CUBRIDJDBCErrorCode.invalid_url) + url,
                LbJDBCErrorCode.lb_invalid_url,
                null);
    }

    public static CUBRIDException sessionNotInitialized(final String detail) {
        return withDetail(LbJDBCErrorCode.lb_session_not_initialized, detail, null);
    }

    public static CUBRIDException topologyInvalid(final String detail) {
        return withDetail(LbJDBCErrorCode.lb_topology_invalid, detail, null);
    }

    public static CUBRIDException physicalNotBound(final String endpointId) {
        return withDetail(LbJDBCErrorCode.lb_physical_not_bound, "endpointId=" + endpointId, null);
    }

    public static CUBRIDException noExecutionHistory(final String operation) {
        return withDetail(LbJDBCErrorCode.lb_stmt_no_execution_history, operation, null);
    }

    public static CUBRIDException delegationNotConfigured() {
        return withDetail(LbJDBCErrorCode.lb_delegation_not_configured, null, null);
    }

    public static CUBRIDException physicalDelegateFailed(
            final String methodName, final Throwable cause) {
        return withDetail(
                LbJDBCErrorCode.lb_physical_delegate_failed, "method=" + methodName, cause);
    }

    public static CUBRIDException txAutocommitOnly() {
        return withDetail(LbJDBCErrorCode.lb_tx_autocommit_only, null, null);
    }

    public static CUBRIDException unsupportedRoute(final String target) {
        return withDetail(LbJDBCErrorCode.lb_unsupported_route, "target=" + target, null);
    }

    public static CUBRIDException brokerCandidatesFiltered(
            final String groupLabel, final String reason) {
        String detail = "group=" + groupLabel;
        if (reason != null && reason.length() > 0) {
            detail = detail + ", " + reason;
        }
        return withDetail(LbJDBCErrorCode.lb_broker_candidates_filtered, detail, null);
    }

    public static CUBRIDException brokerGroupExhausted(
            final String groupLabel, final String tried, final SQLException firstFailure) {
        return withDetail(
                LbJDBCErrorCode.lb_broker_group_exhausted,
                "group=" + groupLabel + ", tried=" + tried,
                firstFailure);
    }

    public static CUBRIDException failoverTxForbidden(final String role) {
        return withDetail(LbJDBCErrorCode.lb_failover_tx_forbidden, "role=" + role, null);
    }

    public static CUBRIDException failoverExhausted(
            final String role, final String detail, final SQLException cause) {
        String combined = "role=" + role;
        if (detail != null && detail.length() > 0) {
            combined = combined + ", " + detail;
        }
        return withDetail(LbJDBCErrorCode.lb_failover_exhausted, combined, cause);
    }

    public static CUBRIDException failoverRoRwFallbackFailed(final SQLException cause) {
        return withDetail(LbJDBCErrorCode.lb_failover_ro_rw_fallback_failed, null, cause);
    }

    private static CUBRIDException withCoreDetail(
            final int code, final String detail, final Throwable cause) {
        String base = CUBRIDJDBCErrorCode.getMessage(code);
        return new LbException(formatMessage(base, detail), code, cause);
    }

    private static CUBRIDException withDetail(
            final int code, final String detail, final Throwable cause) {
        String base = LbJDBCErrorCode.getMessage(code);
        return new LbException(formatMessage(base, detail), code, cause);
    }

    private static String formatMessage(final String base, final String detail) {
        String message = base == null ? "" : base;
        if (detail != null && detail.length() > 0) {
            message = message.length() == 0 ? detail : message + " " + detail;
        }
        return message;
    }

    private static final class LbException extends CUBRIDException {
        private static final long serialVersionUID = 1L;

        private LbException(final String message, final int errCode, final Throwable cause) {
            super(message, errCode);
            if (cause != null) {
                initCause(cause);
            }
        }
    }
}
