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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import javax.sql.XAConnection;
import org.junit.Test;

/**
 * The XA DataSource must refuse a loadbalance:// URL instead of accepting and ignoring it.
 *
 * <p>{@code CUBRIDXADataSource} builds its connection from serverName/portNumber/databaseName and
 * never reads the URL, while inheriting {@code setUrl()} from {@code CUBRIDDataSourceBase} — so an
 * application that configured it with a load-balance URL was silently connected somewhere else. And
 * even if the URL were honoured, an XA branch keeps autocommit off and a load-balance session pins
 * every statement of an open transaction to the write leg, so nothing would be distributed.
 */
public class CUBRIDXADataSourceLbUrlTest {

    private static final String LB_URL =
            "jdbc:cubrid:loadbalance://h1:33000,h2:33000/tdb:dba::?readWeight=slave:1";

    @Test
    public void assertSetUrlRefusesLoadBalanceUrlWithActionableMessage() {
        CUBRIDXADataSource ds = new CUBRIDXADataSource();
        try {
            ds.setUrl(LB_URL);
            fail("Expected the loadbalance:// URL to be refused at configuration time");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("CUBRIDXADataSource"));
            assertTrue("must say why", ex.getMessage().contains("pins every statement"));
            assertTrue("must say what to do instead", ex.getMessage().contains("classic URL"));
        }
    }

    /** setURL() delegates to setUrl(), so the guard must cover both spellings. */
    @Test
    public void assertSetUrlUppercaseSpellingIsGuardedToo() {
        CUBRIDXADataSource ds = new CUBRIDXADataSource();
        try {
            ds.setURL(LB_URL);
            fail("Expected the loadbalance:// URL to be refused at configuration time");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("CUBRIDXADataSource"));
        }
    }

    /**
     * The data source is Serializable, and deserialization restores the url field directly without
     * calling setUrl(). getXAConnection() is the second gate that catches that path.
     */
    @Test
    public void assertGetXAConnectionRefusesAUrlThatBypassedTheSetter() throws Exception {
        CUBRIDXADataSource ds = new CUBRIDXADataSource();
        setUrlFieldDirectly(ds, LB_URL);

        try {
            ds.getXAConnection("dba", "");
            fail("Expected CUBRIDException for a loadbalance:// URL");
        } catch (CUBRIDException ex) {
            assertEquals(CUBRIDJDBCErrorCode.invalid_url, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("CUBRIDXADataSource"));
        }
    }

    /**
     * A classic URL must still be accepted; the guard is about loadbalance:// only. JCI opens its
     * socket lazily, so building the XAConnection succeeds here without a live server — which is
     * exactly what makes this a clean assertion that the guard did not fire.
     */
    @Test
    public void assertClassicUrlIsNotRefusedByTheGuard() throws Exception {
        CUBRIDXADataSource ds = new CUBRIDXADataSource();
        ds.setUrl("jdbc:cubrid:127.0.0.1:1:tdb:dba::");
        ds.setServerName("127.0.0.1");
        ds.setPortNumber(1);
        ds.setDatabaseName("tdb");

        XAConnection xaConnection = ds.getXAConnection("dba", "");
        assertNotNull("a classic URL must pass the guard", xaConnection);
        xaConnection.close();
    }

    private static void setUrlFieldDirectly(final CUBRIDXADataSource ds, final String url)
            throws Exception {
        Field field = CUBRIDDataSourceBase.class.getDeclaredField("url");
        field.setAccessible(true);
        field.set(ds, url);
    }
}
