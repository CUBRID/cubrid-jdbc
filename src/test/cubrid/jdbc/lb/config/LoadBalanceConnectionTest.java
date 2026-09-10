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

package cubrid.jdbc.lb.config;

import static org.junit.Assert.*;

import cubrid.jdbc.lb.LoadBalanceConnection;
import java.sql.Connection;
import java.util.Properties;
import org.junit.Test;

public class LoadBalanceConnectionTest {

    private static LoadBalanceSettings emptyConfig() {
        return new LoadBalanceSettings(new Properties());
    }

    @Test(expected = IllegalArgumentException.class)
    public void assertConstructorRejectsNullConfig() {
        new LoadBalanceConnection(null);
    }

    @Test
    public void assertGetAutoCommitDefaultTrue() throws Exception {
        LoadBalanceConnection conn = new LoadBalanceConnection(emptyConfig());
        assertTrue(conn.getAutoCommit());
    }

    @Test
    public void assertCloseAndIsClosed() throws Exception {
        LoadBalanceConnection conn = new LoadBalanceConnection(emptyConfig());
        assertFalse(conn.isClosed());
        conn.close();
        assertTrue(conn.isClosed());
    }

    @Test
    public void assertDependencyAccessorsNotNull() throws Exception {
        LoadBalanceConnection conn = new LoadBalanceConnection(emptyConfig());
        assertNotNull(conn.getLbConfig());
        assertNotNull(conn.getSessionState());
        assertNotNull(conn.getBoundReadEndpoint());
    }

    @Test
    public void assertTransactionIsolationDefault() throws Exception {
        LoadBalanceConnection conn = new LoadBalanceConnection(emptyConfig());
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, conn.getTransactionIsolation());
    }
}
