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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.config.LoadBalanceUrlParser.HostToken;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser.ParsedUrl;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser.ReplicaToken;
import java.sql.SQLException;
import org.junit.Test;

public class LoadBalanceUrlParserTest {

    @Test
    public void parsesGlobalPortExampleWithCredsAndOptions() throws SQLException {
        ParsedUrl p =
                LoadBalanceUrlParser.parse(
                        "jdbc:cubrid:loadbalance://node1,node2,node3;replica=rep1"
                                + "/testdb:dba:secret:"
                                + "?rwPort=33000&roPort=33002&soPort=33004"
                                + "&readWeight=slave:40,master:10,replica:50");

        assertEquals("", p.getVariant());
        assertTrue(p.isLoadBalance());

        assertEquals(3, p.getHosts().size());
        assertEquals("node1", p.getHosts().get(0).getHost());
        assertEquals("node2", p.getHosts().get(1).getHost());
        assertEquals("node3", p.getHosts().get(2).getHost());
        // global-port example: no inline ports on any host
        assertFalse(p.getHosts().get(0).hasRwPort());
        assertFalse(p.getHosts().get(0).hasRoPort());

        assertEquals(1, p.getReplicas().size());
        assertEquals("rep1", p.getReplicas().get(0).getHost());
        assertFalse(p.getReplicas().get(0).hasSoPort());

        assertEquals("testdb", p.getDb());
        assertEquals("dba", p.getUser());
        assertEquals("secret", p.getPassword());

        assertEquals("33000", p.getOptions().get("rwPort"));
        assertEquals("33002", p.getOptions().get("roPort"));
        assertEquals("33004", p.getOptions().get("soPort"));
        // readWeight value (with its own ':' and ',') is preserved verbatim
        assertEquals("slave:40,master:10,replica:50", p.getOptions().get("readWeight"));
        assertEquals(4, p.getOptions().size());
    }

    @Test
    public void parsesInlinePortExample() throws SQLException {
        ParsedUrl p =
                LoadBalanceUrlParser.parse(
                        "jdbc:cubrid:loadbalance://node1:33000:33002,node2:34000:34002,node3"
                                + ";replica=rep1:33004/testdb:dba:secret:"
                                + "?roPort=33002&soPort=33004&readWeight=slave:50,master:0,replica:50");

        assertTrue(p.isLoadBalance());
        assertEquals(3, p.getHosts().size());

        HostToken node1 = p.getHosts().get(0);
        assertEquals("node1", node1.getHost());
        assertEquals(33000, node1.getRwPort());
        assertEquals(33002, node1.getRoPort());

        HostToken node2 = p.getHosts().get(1);
        assertEquals(34000, node2.getRwPort());
        assertEquals(34002, node2.getRoPort());

        HostToken node3 = p.getHosts().get(2);
        assertEquals("node3", node3.getHost());
        assertFalse("node3 has no inline RW port (resolved later or errors)", node3.hasRwPort());
        assertFalse(node3.hasRoPort());

        ReplicaToken rep1 = p.getReplicas().get(0);
        assertEquals("rep1", rep1.getHost());
        assertEquals(33004, rep1.getSoPort());

        assertEquals("slave:50,master:0,replica:50", p.getOptions().get("readWeight"));
    }

    @Test
    public void parsesWithoutCredentials() throws SQLException {
        ParsedUrl p =
                LoadBalanceUrlParser.parse(
                        "jdbc:cubrid:loadbalance://node1,node2/testdb?rwPort=33000");

        assertEquals("testdb", p.getDb());
        assertNull(p.getUser());
        assertNull(p.getPassword());
        assertEquals(2, p.getHosts().size());
        assertTrue(p.getReplicas().isEmpty());
    }

    @Test
    public void parsesCredentialsWithoutTrailingColon() throws SQLException {
        ParsedUrl p =
                LoadBalanceUrlParser.parse("jdbc:cubrid:loadbalance://node1/testdb:dba:secret");

        assertEquals("testdb", p.getDb());
        assertEquals("dba", p.getUser());
        assertEquals("secret", p.getPassword());
    }

    @Test
    public void parsesSingleNodeUriUrl() throws SQLException {
        ParsedUrl p = LoadBalanceUrlParser.parse("jdbc:cubrid://localhost:33000/demodb");

        assertFalse(p.isLoadBalance());
        assertEquals(1, p.getHosts().size());
        assertEquals("localhost", p.getHosts().get(0).getHost());
        assertEquals(33000, p.getHosts().get(0).getRwPort());
        assertFalse(p.getHosts().get(0).hasRoPort());
        assertTrue(p.getReplicas().isEmpty());
        assertEquals("demodb", p.getDb());
    }

    @Test
    public void parsesVariant() throws SQLException {
        assertEquals(
                "-mysql",
                LoadBalanceUrlParser.parse("jdbc:cubrid-mysql:loadbalance://n1/db").getVariant());
        assertEquals(
                "-oracle", LoadBalanceUrlParser.parse("jdbc:cubrid-oracle://h:1/db").getVariant());
    }

    @Test
    public void parsesOptionsSeparatedBySemicolon() throws SQLException {
        ParsedUrl p =
                LoadBalanceUrlParser.parse(
                        "jdbc:cubrid:loadbalance://n1/db?charSet=utf-8;rwPort=33000");

        assertEquals("utf-8", p.getOptions().get("charSet"));
        assertEquals("33000", p.getOptions().get("rwPort"));
    }

    /** Duplicate option key (case-insensitive) → last occurrence wins, matching core. */
    @Test
    public void duplicateOptionKeyLastWins() throws SQLException {
        ParsedUrl p =
                LoadBalanceUrlParser.parse("jdbc:cubrid:loadbalance://n1/db?rwPort=100&RWPort=200");

        // only the last occurrence survives; lookup is case-insensitive
        assertEquals("200", p.getOptions().get("RWPort"));
        assertNull(p.getOptions().get("rwPort"));
        assertEquals(1, p.getOptions().size());
    }

    @Test
    public void noReplicaYieldsEmptyReplicaList() throws SQLException {
        ParsedUrl p = LoadBalanceUrlParser.parse("jdbc:cubrid:loadbalance://n1,n2/db");

        assertEquals(2, p.getHosts().size());
        assertTrue(p.getReplicas().isEmpty());
    }

    @Test
    public void rejectsNullOrEmpty() {
        assertParseFails(null);
        assertParseFails("");
        assertParseFails("   ");
    }

    @Test
    public void rejectsUrlWithoutSchemeSeparator() {
        // classic colon-format has no "://"
        assertParseFails("jdbc:cubrid:localhost:33000:demodb:dba:pw:");
    }

    @Test
    public void rejectsUrlWithoutDatabase() {
        assertParseFails("jdbc:cubrid:loadbalance://node1,node2?rwPort=33000");
    }

    @Test
    public void rejectsUnknownConnectionMode() {
        assertParseFails("jdbc:cubrid:cluster://node1/db");
    }

    @Test
    public void rejectsNonNumericPort() {
        assertParseFails("jdbc:cubrid:loadbalance://node1:abc/db");
    }

    @Test
    public void rejectsTooManyPortSegments() {
        assertParseFails("jdbc:cubrid:loadbalance://node1:1:2:3/db");
    }

    @Test
    public void rejectsEmptyHostToken() {
        assertParseFails("jdbc:cubrid:loadbalance://node1,,node2/db");
    }

    /** db-cred beyond db:user:pw(:) is malformed — core rejects it, so LB must too. */
    @Test
    public void rejectsExcessDbCredSegments() {
        assertParseFails("jdbc:cubrid:loadbalance://node1/db:user:password:test");
        assertParseFails("jdbc:cubrid:loadbalance://node1/db:user:password:test:");
    }

    /** Trailing colon after the password is allowed (db[:user[:pw[:]]]). */
    @Test
    public void acceptsTrailingColonAfterPassword() throws SQLException {
        ParsedUrl p = LoadBalanceUrlParser.parse("jdbc:cubrid:loadbalance://node1/db:user:pw:");
        assertEquals("db", p.getDb());
        assertEquals("user", p.getUser());
        assertEquals("pw", p.getPassword());
    }

    @Test
    public void rejectsEmptyReplicaListAfterMarker() {
        assertParseFails("jdbc:cubrid:loadbalance://node1;replica=/db");
    }

    @Test
    public void rejectsIpv6Host() {
        assertParseFails("jdbc:cubrid:loadbalance://::1/db");
    }

    @Test
    public void rejectsMalformedOption() {
        assertParseFails("jdbc:cubrid:loadbalance://n1/db?=novalue");
        assertParseFails("jdbc:cubrid:loadbalance://n1/db?novalue");
    }

    private static void assertParseFails(String url) {
        try {
            LoadBalanceUrlParser.parse(url);
            fail("expected SQLException for URL: " + url);
        } catch (SQLException expected) {
        }
    }
}
