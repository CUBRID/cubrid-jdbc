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

package cubrid.jdbc.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import org.junit.Test;

/**
 * CUBRID's built-in pool DataSource must refuse a loadbalance:// URL rather than fail on its
 * internal CUBRIDConnection cast: a PooledConnection wraps one physical UConnection, which a
 * load-balancing connection (write leg + read leg, rebound on failover) cannot supply.
 */
public class CUBRIDConnectionPoolDataSourceLbUrlTest {

    private static final String LB_URL =
            "jdbc:cubrid:loadbalance://h1:33000,h2:33000/tdb:dba::?readWeight=slave:1";

    @Test
    public void assertLoadBalanceUrlIsRefusedWithActionableMessage() throws Exception {
        CUBRIDConnectionPoolDataSource ds = new CUBRIDConnectionPoolDataSource();
        ds.setUrl(LB_URL);
        try {
            ds.getPooledConnection();
            fail("Expected CUBRIDException for a loadbalance:// URL");
        } catch (CUBRIDException ex) {
            assertEquals(CUBRIDJDBCErrorCode.invalid_url, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("CUBRIDConnectionPoolDataSource"));
            assertTrue(ex.getMessage().contains("javax.sql.DataSource"));
        }
    }

    /* The credential overload is the one CUBRIDConnectionPoolManager (dataSourceName) calls. */
    @Test
    public void assertLoadBalanceUrlIsRefusedOnCredentialOverload() throws Exception {
        CUBRIDConnectionPoolDataSource ds = new CUBRIDConnectionPoolDataSource();
        ds.setUrl(LB_URL);
        try {
            ds.getPooledConnection("dba", "");
            fail("Expected CUBRIDException for a loadbalance:// URL");
        } catch (CUBRIDException ex) {
            assertEquals(CUBRIDJDBCErrorCode.invalid_url, ex.getErrorCode());
        }
    }

    /* A classic URL must still reach the normal path (it fails later, on the real connect). */
    @Test
    public void assertClassicUrlIsNotRefusedByTheGuard() throws Exception {
        CUBRIDConnectionPoolDataSource ds = new CUBRIDConnectionPoolDataSource();
        ds.setUrl("jdbc:cubrid:127.0.0.1:1:tdb:dba::");
        try {
            ds.getPooledConnection();
            fail("Expected a connection failure, not a URL refusal");
        } catch (SQLException ex) {
            assertTrue(
                    "classic URL must not be refused by the LB guard",
                    ex.getErrorCode() != CUBRIDJDBCErrorCode.invalid_url
                            || !ex.getMessage().contains("CUBRIDConnectionPoolDataSource"));
        }
    }
}
