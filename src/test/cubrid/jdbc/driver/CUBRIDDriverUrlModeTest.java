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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.CUBRIDDriver.UrlMode;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.Before;
import org.junit.Test;

public class CUBRIDDriverUrlModeTest {

    private CUBRIDDriver driver;

    @Before
    public void setUp() {
        driver = new CUBRIDDriver();
    }

    @Test
    public void classicUrlWithoutSchemeSeparatorIsClassic() {
        assertEquals(
                UrlMode.CLASSIC,
                CUBRIDDriver.detectUrlMode("jdbc:cubrid:localhost:33000:demodb:dba:pw:"));
        assertEquals(
                UrlMode.CLASSIC,
                CUBRIDDriver.detectUrlMode(
                        "jdbc:cubrid:localhost:33000:demodb:dba:pw:?charSet=utf-8"));
    }

    @Test
    public void uriUrlWithoutModeIsSingleNode() {
        assertEquals(
                UrlMode.URI_SINGLE,
                CUBRIDDriver.detectUrlMode("jdbc:cubrid://localhost:33000/demodb"));
        assertEquals(
                UrlMode.URI_SINGLE,
                CUBRIDDriver.detectUrlMode(
                        "jdbc:cubrid://localhost:33000/demodb:dba:pw:?charSet=utf-8"));
    }

    @Test
    public void uriUrlWithLoadbalanceModeIsLoadBalance() {
        assertEquals(
                UrlMode.URI_LOADBALANCE,
                CUBRIDDriver.detectUrlMode(
                        "jdbc:cubrid:loadbalance://node1,node2,node3;replica=rep1"
                                + "/testdb:dba:secret:?readWeight=slave:40,master:10,replica:50"));
        assertEquals(
                UrlMode.URI_LOADBALANCE,
                CUBRIDDriver.detectUrlMode(
                        "jdbc:cubrid:loadbalance://node1/testdb:::?charSet=utf-8"));
    }

    @Test
    public void detectUrlModeHandlesVariants() {
        assertEquals(UrlMode.URI_SINGLE, CUBRIDDriver.detectUrlMode("jdbc:cubrid-mysql://h:1/db"));
        assertEquals(UrlMode.URI_SINGLE, CUBRIDDriver.detectUrlMode("jdbc:cubrid-oracle://h:1/db"));
        assertEquals(
                UrlMode.URI_LOADBALANCE,
                CUBRIDDriver.detectUrlMode("jdbc:cubrid-mysql:loadbalance://n1,n2/db"));
        assertEquals(
                UrlMode.URI_LOADBALANCE,
                CUBRIDDriver.detectUrlMode("jdbc:cubrid-oracle:loadbalance://n1,n2/db"));
    }

    @Test
    public void detectUrlModeIsCaseInsensitive() {
        assertEquals(
                UrlMode.URI_LOADBALANCE,
                CUBRIDDriver.detectUrlMode("JDBC:CUBRID:LOADBALANCE://n1,n2/db"));
        assertEquals(
                UrlMode.URI_LOADBALANCE,
                CUBRIDDriver.detectUrlMode("jdbc:cubrid:LoadBalance://n1/db"));
        assertEquals(UrlMode.URI_SINGLE, CUBRIDDriver.detectUrlMode("JDBC:CUBRID://h:1/db"));
    }

    /**
     * {@code loadbalance} only marks the LB mode when it is the scheme segment immediately before
     * {@code ://}; a host literally named {@code loadbalance} (after {@code ://}) is single-node.
     */
    @Test
    public void loadbalanceAsHostNameIsNotLoadBalanceMode() {
        assertEquals(
                UrlMode.URI_SINGLE,
                CUBRIDDriver.detectUrlMode("jdbc:cubrid://loadbalance:33000/db"));
    }

    @Test
    public void nullUrlIsClassic() {
        assertEquals(UrlMode.CLASSIC, CUBRIDDriver.detectUrlMode(null));
    }

    @Test
    public void acceptsUrlForClassicAndUriCubridUrls() throws SQLException {
        assertTrue(driver.acceptsURL("jdbc:cubrid:localhost:33000:demodb:dba:pw:"));
        assertTrue(driver.acceptsURL("jdbc:cubrid://localhost:33000/demodb"));
        assertTrue(driver.acceptsURL("jdbc:cubrid:loadbalance://n1,n2/db"));
        assertFalse(driver.acceptsURL("jdbc:mysql://localhost:3306/db"));
        assertFalse(driver.acceptsURL(null));
    }

    @Test
    public void connectReturnsNullForUnacceptedUrl() throws SQLException {
        assertNull(driver.connect("jdbc:mysql://localhost:3306/db", new Properties()));
    }

    /**
     * A {@code loadbalance} URL dispatches to the URI path (P2-T9): it is parsed into a role
     * topology / {@code LoadBalanceSettings}. Ports now default (RW=30000, RO=33000) when omitted,
     * so a URL with no ports resolves the topology and proceeds to a real connection attempt
     * against the (unreachable) hosts — failing there, not on the classic "invalid URL" regex, and
     * no longer on the old "not implemented" stub.
     */
    @Test
    public void connectOnLoadBalanceUrlDispatchesToUriPath() {
        try {
            driver.connect("jdbc:cubrid:loadbalance://n1,n2/db", new Properties());
            fail("expected SQLException connecting to unreachable loadbalance hosts");
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            assertFalse(msg, msg.toLowerCase().contains("not implemented"));
            assertFalse(msg, msg.toLowerCase().contains("invalid url"));
        }
    }

    /**
     * A single-node URI URL differs from a classic single-node URL by format only (appendix B): it
     * is translated to the classic colon-delimited form, normalizing the {@code db:user:pw:}
     * segments.
     */
    @Test
    public void uriSingleUrlTranslatesToClassicForm() throws SQLException {
        assertEquals(
                "jdbc:cubrid:localhost:33000:demodb:::",
                CUBRIDDriver.uriSingleToClassicUrl("jdbc:cubrid://localhost:33000/demodb"));
        assertEquals(
                "jdbc:cubrid:h:1:db:dba:pw:?charSet=utf-8",
                CUBRIDDriver.uriSingleToClassicUrl("jdbc:cubrid://h:1/db:dba:pw:?charSet=utf-8"));
        assertEquals(
                "jdbc:cubrid:h::db:::", CUBRIDDriver.uriSingleToClassicUrl("jdbc:cubrid://h/db"));
        assertEquals(
                "jdbc:cubrid:h::db:::", CUBRIDDriver.uriSingleToClassicUrl("jdbc:cubrid://h:/db"));
        assertEquals(
                "jdbc:Cubrid:h:1:db:::",
                CUBRIDDriver.uriSingleToClassicUrl("jdbc:Cubrid://h:1/db"));
        assertEquals(
                "jdbc:cubrid-oracle:h:1:db:dba:pw:?a=1&b=2",
                CUBRIDDriver.uriSingleToClassicUrl("jdbc:cubrid-oracle://h:1/db:dba:pw:?a=1&b=2"));
    }

    /**
     * As in a classic URL, an absent host and/or port is an empty segment the classic path fills
     * with {@code default_hostname}/{@code default_port} - so {@code jdbc:cubrid:///db:::?p} is
     * accepted just like the classic {@code jdbc:cubrid:::db:::?p}.
     */
    @Test
    public void uriSingleUrlAllowsEmptyHostAndPort() throws SQLException {
        assertEquals(
                "jdbc:cubrid:::tdb:::?rcTime=600",
                CUBRIDDriver.uriSingleToClassicUrl("jdbc:cubrid:///tdb:::?rcTime=600"));
        assertEquals(
                "jdbc:cubrid::30000:tdb:::",
                CUBRIDDriver.uriSingleToClassicUrl("jdbc:cubrid://:30000/tdb"));
    }

    /**
     * The URI URL is validated as written (URL_PATTERN_SINGLE), so a URL that does not match the
     * single-node URI format is rejected with the classic "invalid URL" error instead of being
     * rewritten into something the classic pattern happens to accept: {@code //h:1:2/db} used to
     * become {@code jdbc:cubrid:h:1:2:db:::}, which the classic pattern reads as db=2, user=db.
     */
    @Test
    public void uriSingleUrlRejectsMalformedFormat() {
        String[] malformed = {
            "jdbc:cubrid://h:1:2/db", // two ports in the authority
            "jdbc:cubrid://h:abc/db", // non-numeric port
            "jdbc:cubrid://h:1", // no '/<db>'
            "jdbc:cubrid://h:1/", // empty db
            "jdbc:cubrid://h_o$t:1/db", // host charset
            "jdbc:cubrid://h:1/db?a=", // empty option value (as in the classic pattern)
        };
        for (int i = 0; i < malformed.length; i++) {
            try {
                CUBRIDDriver.uriSingleToClassicUrl(malformed[i]);
                fail("expected invalid URL for " + malformed[i]);
            } catch (SQLException e) {
                assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("invalid url"));
            }
        }
    }

    /**
     * The URL recorded for logging from a URI URL shows the credentials the way the classic path's
     * {@code resolvedUrl} does ({@code db:user:********:}), so a URI-URL error message is not left
     * with the empty {@code db:::} segments when the credentials came from {@code
     * DriverManager.getConnection(url, user, password)}. The properties' user wins over an inline
     * URL user (the same precedence the connect path uses), and a classic URL is left alone.
     */
    @Test
    public void maskUriUrlPasswordRendersEffectiveCredentials() {
        Properties info = new Properties();
        info.setProperty("user", "dba");
        info.setProperty("password", "secret");

        assertEquals(
                "jdbc:cubrid:loadbalance://h1,h2;replica=h3/tdb:dba:********:?rcTime=600",
                CUBRIDDriver.maskUriUrlPassword(
                        "jdbc:cubrid:loadbalance://h1,h2;replica=h3/tdb:::?rcTime=600", info));
        assertEquals(
                "jdbc:cubrid://h:1/tdb:dba:********:",
                CUBRIDDriver.maskUriUrlPassword("jdbc:cubrid://h:1/tdb", info));
        assertEquals(
                "jdbc:cubrid://h:1/tdb:dba:********:",
                CUBRIDDriver.maskUriUrlPassword("jdbc:cubrid://h:1/tdb:scott:tiger:", info));
        assertEquals(
                "jdbc:cubrid://h:1/tdb:scott:********:",
                CUBRIDDriver.maskUriUrlPassword("jdbc:cubrid://h:1/tdb:scott:tiger:", null));
        assertEquals(
                "jdbc:cubrid:h:1:tdb:scott:tiger:",
                CUBRIDDriver.maskUriUrlPassword("jdbc:cubrid:h:1:tdb:scott:tiger:", info));
        assertNull(CUBRIDDriver.maskUriUrlPassword(null, info));
    }

    /**
     * The URL logged for a single-node URI connect resolves the host/port the same way the classic
     * path's {@code resolvedUrl} does: an absent host/port is shown as the driver default (the
     * values the connect actually uses), not left empty, and the credentials are rendered {@code
     * db:user:********:}. The URI form itself is kept, so the logged URL still looks like what the
     * user wrote.
     */
    @Test
    public void uriSingleLogUrlResolvesDefaultHostAndPort() throws SQLException {
        Properties info = new Properties();
        info.setProperty("user", "dba");
        info.setProperty("password", "secret");

        assertEquals(
                "jdbc:Cubrid://localhost:30000/tdb:dba:********:?rcTime=600",
                CUBRIDDriver.uriSingleLogUrl("jdbc:Cubrid:///tdb:::?rcTime=600", info));
        assertEquals(
                "jdbc:cubrid://h:30000/tdb:dba:********:",
                CUBRIDDriver.uriSingleLogUrl("jdbc:cubrid://h/tdb", info));
        assertEquals(
                "jdbc:cubrid://localhost:33000/tdb:dba:********:",
                CUBRIDDriver.uriSingleLogUrl("jdbc:cubrid://:33000/tdb", info));
        assertEquals(
                "jdbc:cubrid://h:1/tdb:dba:********:?charSet=utf-8",
                CUBRIDDriver.uriSingleLogUrl(
                        "jdbc:cubrid://h:1/tdb:scott:tiger:?charSet=utf-8", info));
    }

    /**
     * Options written without the {@code '?'} separator (.../db:::rwPort=30000&...) make the URL
     * malformed: the classic path rejects it (its regex requires the options group to start with
     * {@code '?'}) and the LB parser rejects it, so the single-node URI path must reject it too
     * instead of silently dropping the excess segments and connecting with the options ignored.
     */
    @Test
    public void uriSingleUrlWithExcessCredSegmentsIsInvalid() {
        String[] malformed = {
            "jdbc:cubrid://h:1/db:::rwPort=30000&useSSL=true",
            "jdbc:cubrid://h:1/db:dba::rwPort=30000",
            "jdbc:cubrid://h:1/db:dba:pw:x",
            "jdbc:cubrid://h:1/db:dba:pw::",
        };
        for (int i = 0; i < malformed.length; i++) {
            try {
                CUBRIDDriver.uriSingleToClassicUrl(malformed[i]);
                fail("expected invalid URL for " + malformed[i]);
            } catch (SQLException e) {
                assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("invalid url"));
            }
        }
    }

    @Test
    public void connectOnMalformedClassicUrlThrowsInvalidUrl() {
        try {
            driver.connect("jdbc:cubrid:bad-url", new Properties());
            fail("expected SQLException for malformed classic URL");
        } catch (SQLException e) {
            assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("invalid url"));
        }
    }
}
