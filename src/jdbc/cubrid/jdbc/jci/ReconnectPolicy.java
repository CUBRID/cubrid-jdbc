/*
 * Copyright (C) 2008 Search Solution Corporation.
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

import java.io.IOException;
import java.sql.SQLException;

/** Shared reconnect-eligibility rules for JCI ({@link UConnection}) and LB runtime failover. */
public final class ReconnectPolicy {

    private ReconnectPolicy() {}

    public static boolean isErrorCommunication(final int error) {
        switch (error) {
            case UErrorCode.ER_COMMUNICATION:
            case UErrorCode.ER_ILLEGAL_DATA_SIZE:
            case UErrorCode.CAS_ER_COMMUNICATION:
                return true;
            default:
                return false;
        }
    }

    public static boolean isErrorToReconnect(final int error) {
        if (isErrorCommunication(error)) {
            return true;
        }

        switch (error) {
            case -111: // ER_TM_SERVER_DOWN_UNILATERALLY_ABORTED
            case -199: // ER_NET_SERVER_CRASHED
            case -224: // ER_OBJ_NO_CONNECT
            case -677: // ER_BO_CONNECT_FAILED
                return true;
            default:
                return false;
        }
    }

    public static boolean isRetriableSqlException(final SQLException ex) {
        if (ex == null) {
            return false;
        }

        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException) {
                SQLException sqlEx = (SQLException) current;
                if (isErrorToReconnect(sqlEx.getErrorCode())) {
                    return true;
                }

                SQLException next = sqlEx.getNextException();
                if (next != null) {
                    current = next;
                    continue;
                }
            }

            if (current instanceof IOException) {
                return true;
            }

            current = current.getCause();
        }
        return false;
    }
}
