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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cubrid.jdbc.driver.CUBRIDException;
import cubrid.jdbc.driver.CUBRIDJDBCErrorCode;
import java.net.SocketException;
import java.sql.SQLException;
import org.junit.Test;

public class ReconnectPolicyTest {

    @Test
    public void isErrorCommunicationMatchesCoreCases() {
        assertTrue(ReconnectPolicy.isErrorCommunication(UErrorCode.ER_COMMUNICATION));
        assertTrue(ReconnectPolicy.isErrorCommunication(UErrorCode.ER_ILLEGAL_DATA_SIZE));
        assertTrue(ReconnectPolicy.isErrorCommunication(UErrorCode.CAS_ER_COMMUNICATION));
        assertFalse(ReconnectPolicy.isErrorCommunication(UErrorCode.ER_CONNECTION));
    }

    @Test
    public void isErrorToReconnectMatchesCoreCases() {
        assertTrue(ReconnectPolicy.isErrorToReconnect(UErrorCode.ER_COMMUNICATION));
        assertTrue(ReconnectPolicy.isErrorToReconnect(-111));
        assertTrue(ReconnectPolicy.isErrorToReconnect(-199));
        assertTrue(ReconnectPolicy.isErrorToReconnect(-224));
        assertTrue(ReconnectPolicy.isErrorToReconnect(-677));
        assertFalse(ReconnectPolicy.isErrorToReconnect(UErrorCode.ER_CONNECTION));
        assertFalse(ReconnectPolicy.isErrorToReconnect(UErrorCode.ER_TIMEOUT));
    }

    @Test
    public void uConnectionInstanceMethodsDelegateToPolicy() throws CUBRIDException {
        UClientSideConnection conn =
                new UClientSideConnection("host", 33000, "db", "user", "pass", "url");
        assertEquals(
                ReconnectPolicy.isErrorToReconnect(UErrorCode.ER_COMMUNICATION),
                conn.isErrorToReconnect(UErrorCode.ER_COMMUNICATION));
        assertEquals(
                ReconnectPolicy.isErrorCommunication(UErrorCode.ER_ILLEGAL_DATA_SIZE),
                conn.isErrorCommunication(UErrorCode.ER_ILLEGAL_DATA_SIZE));
    }

    @Test
    public void isRetriableSqlExceptionWalksChainAndIoCause() {
        assertTrue(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("comm", null, UErrorCode.ER_COMMUNICATION)));
        assertTrue(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("illegal size", null, UErrorCode.ER_ILLEGAL_DATA_SIZE)));
        assertTrue(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("cas comm", null, UErrorCode.CAS_ER_COMMUNICATION)));
        assertTrue(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("server down", null, -111)));
        assertTrue(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("server crashed", null, -199)));
        assertFalse(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("timeout", null, UErrorCode.ER_TIMEOUT)));
        assertTrue(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("io", new SocketException("reset"))));
        SQLException chained =
                new SQLException(
                        "wrapper", new SQLException("inner", null, UErrorCode.ER_COMMUNICATION));
        assertTrue(ReconnectPolicy.isRetriableSqlException(chained));
    }

    @Test
    public void isRetriableSqlExceptionRejectsNonReconnectErrors() {
        assertFalse(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("connect fail", null, UErrorCode.ER_CONNECTION)));
        assertFalse(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("free server", null, UErrorCode.CAS_ER_FREE_SERVER)));
        assertFalse(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("closed", null, CUBRIDJDBCErrorCode.connection_closed)));
    }

    @Test
    public void isRetriableSqlExceptionRejectsDbmsAndMessageOnlyErrors() {
        assertFalse(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("Syntax error", null, -493)));
        assertFalse(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("constraint", null, UErrorCode.ER_DBMS)));
        assertFalse(
                ReconnectPolicy.isRetriableSqlException(
                        new SQLException("java.net.SocketException: Connection reset")));
    }

    @Test
    public void isRetriableSqlExceptionRejectsNull() {
        assertFalse(ReconnectPolicy.isRetriableSqlException(null));
    }
}
