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

package cubrid.jdbc.lb.failover;

import cubrid.jdbc.jci.ReconnectPolicy;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.log.LbLog;
import cubrid.jdbc.lb.route.Router;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Wraps a physical statement execution so that a broker failure rebinds the leg and, when replay is
 * safe, retries the execution once.
 */
public final class ExecuteFailoverHandler {

    private static final Logger LOGGER = Logger.getLogger(ExecuteFailoverHandler.class.getName());

    /**
     * The physical execution to run (and possibly re-run) under failover.
     *
     * @param <T> the execution's result type
     */
    public interface SqlExecution<T> {
        T run() throws SQLException;
    }

    public <T> T executeWithFailover(
            final LoadBalanceConnection connection,
            final Router.RouteTarget routeTarget,
            final String sql,
            final SqlExecution<T> execution)
            throws SQLException {
        return executeWithFailover(connection, routeTarget, sql, true, execution);
    }

    /**
     * Runs {@code execution}, recovering and (when safe) retrying once if the physical execution
     * fails on a broker that has gone down.
     *
     * @param <T> the execution's result type
     * @param connection the logical LB connection issuing the statement
     * @param routeTarget the route target the statement was classified to
     * @param sql the SQL being executed, for logging and re-prepare
     * @param retrySafe whether re-running {@code execution} after a rebind is safe with respect to
     *     the statement's parameters. Streams/readers bound as parameters are consumed by the first
     *     execution and cannot be replayed, so the caller passes {@code false} to forbid retry.
     *     Plain statements with no parameters pass {@code true}.
     * @param execution the physical execution to run
     * @return the execution's result
     * @throws SQLException if the execution fails and cannot be recovered/retried
     */
    public <T> T executeWithFailover(
            final LoadBalanceConnection connection,
            final Router.RouteTarget routeTarget,
            final String sql,
            final boolean retrySafe,
            final SqlExecution<T> execution)
            throws SQLException {
        return run(connection, routeTarget, sql, retrySafe, true, execution);
    }

    /**
     * Same recovery as {@link #executeWithFailover}, for a physical call that is <b>not</b> a
     * statement execution: a connection command ({@code prepareCall}, the LOB trio, {@code
     * createClob}), a session-property read, or statement/connection metadata.
     *
     * <p>The only difference is that the call is not timed. Execution latency is defined as
     * statement latency (LB-Metrics-Architecture.md), and a pool that reads {@code isReadOnly()} on
     * every checkout would otherwise flood the read/write histograms with calls that never touched
     * a SQL statement.
     *
     * @param <T> the command's result type
     * @param connection the logical LB connection issuing the command
     * @param routeTarget the leg the command runs on
     * @param label the command name, for logging
     * @param retrySafe whether re-running the command after a rebind is safe
     * @param execution the physical command to run
     * @return the command's result
     * @throws SQLException if the command fails and cannot be recovered/retried
     */
    public <T> T executeCommandWithFailover(
            final LoadBalanceConnection connection,
            final Router.RouteTarget routeTarget,
            final String label,
            final boolean retrySafe,
            final SqlExecution<T> execution)
            throws SQLException {
        return run(connection, routeTarget, label, retrySafe, false, execution);
    }

    private <T> T run(
            final LoadBalanceConnection connection,
            final Router.RouteTarget routeTarget,
            final String sql,
            final boolean retrySafe,
            final boolean timed,
            final SqlExecution<T> execution)
            throws SQLException {
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }

        if (routeTarget == null) {
            throw new IllegalArgumentException("routeTarget must not be null");
        }

        if (execution == null) {
            throw new IllegalArgumentException("execution must not be null");
        }

        try {
            return timedRun(connection, routeTarget, timed, execution);
        } catch (SQLException ex) {
            // Fail over on the retriable set and on the unreachable-host set used by the core JCI
            // althost reconnect (UClientSideConnection.reconnect) and the LB unreachable filter.
            // That covers a broker stopped with `cubrid broker off`, which surfaces as
            // ER_CONNECTION - an unreachable-host error ReconnectPolicy alone does not retry.
            if (!ReconnectPolicy.isRetriableSqlException(ex)
                    && !UnreachableEndpoints.shouldMarkUnreachable(ex)) {
                // Not a broker failure: the statement itself failed and no leg is rebound.
                // Recorded, so a log showing no failover after an error is explained rather than
                // silent.
                LbLog.fine(
                        LOGGER,
                        LbLog.conn(connection.getConnectionId()),
                        "LB RETRY ["
                                + routeTarget
                                + "]: not a broker failure -> propagating without"
                                + " failover | cause: "
                                + LbLog.cause(ex));
                throw ex;
            }

            // Recover the endpoint that was actually used for this execution (captured at execute
            // time), so recomputing the routing preview here cannot mark the wrong node.
            try {
                Endpoint failedEp = connection.resolveFailedExecEndpoint(routeTarget);
                PhysicalRecoveryContext ctx = connection.buildRecoveryCtx(routeTarget, failedEp);
                connection.recoverPhyBinding(ctx, ex);
            } catch (SQLException recoveryEx) {
                // Recovery preparation failed. The original execution exception is the meaningful
                // cause, so keep it and chain the recovery failure with setNextException instead of
                // letting it mask the original.
                if (recoveryEx != ex) {
                    ex.setNextException(recoveryEx);
                }
                LbLog.fine(
                        LOGGER,
                        LbLog.conn(connection.getConnectionId()),
                        "LB RETRY ["
                                + routeTarget
                                + "]: rebinding failed -> propagating the original"
                                + " execution failure | rebind cause: "
                                + LbLog.cause(recoveryEx));
                throw ex;
            }

            // Rebind the endpoint (done above) but do NOT re-run when re-execution is unsafe:
            //  - writes (TO_READ_WRITE): the server may have already applied the statement before
            //    the connection dropped, so replaying risks a duplicate INSERT / double UPDATE.
            //  - retrySafe == false: a consumed stream/reader parameter cannot be replayed.
            // In both cases propagate the original exception so the caller decides.
            if (!connection.getLbConfig().isRuntimeFailoverRetryOnce()
                    || routeTarget == Router.RouteTarget.TO_READ_WRITE
                    || !retrySafe) {
                // The leg IS rebound; only the replay is withheld. Naming which of the three
                // reasons applies separates "failover worked, the caller redoes the statement" from
                // "failover did nothing", which look identical from the exception alone.
                LbLog.fine(
                        LOGGER,
                        LbLog.conn(connection.getConnectionId()),
                        "LB RETRY ["
                                + routeTarget
                                + "]: leg rebound but not replayed ("
                                + (!connection.getLbConfig().isRuntimeFailoverRetryOnce()
                                        ? "retry disabled"
                                        : routeTarget == Router.RouteTarget.TO_READ_WRITE
                                                ? "write may already have been applied"
                                                : "a consumed stream parameter cannot be replayed")
                                + ") -> the caller decides");
                throw ex;
            }

            LbLog.fine(
                    LOGGER,
                    LbLog.conn(connection.getConnectionId()),
                    "LB RETRY [" + routeTarget + "]: leg rebound -> replaying the execution once");
            return timedRun(connection, routeTarget, timed, execution);
        }
    }

    /**
     * Runs {@code execution}, timing it and recording the latency on success (diagnostics only).
     * {@code timed} is false for commands and metadata, which are not statement executions.
     */
    private <T> T timedRun(
            final LoadBalanceConnection connection,
            final Router.RouteTarget routeTarget,
            final boolean timed,
            final SqlExecution<T> execution)
            throws SQLException {
        if (!timed) {
            return execution.run();
        }

        long t0 = System.nanoTime();
        T result = execution.run();
        connection.recordExecLatency(routeTarget, System.nanoTime() - t0);
        return result;
    }
}
