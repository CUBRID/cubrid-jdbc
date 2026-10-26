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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.config.LoadBalanceOptionValidator.Result;
import java.sql.SQLException;
import java.util.Collections;
import org.junit.Test;

/**
 * Keys from earlier LB designs never shipped, so nothing treats them specially: they are unknown
 * keys like any other, and a name the broker URL cannot carry fails the URL.
 */
public class PreviousSpecKeysTest {

    private static final String BASE =
            "jdbc:cubrid:loadbalance://node1:30000:33000,node2:30000:33000/testdb";

    private static final String[] FORMER_KEYS = {
        "haLbMode",
        "haLbConfig",
        "haLbSharedStateKey",
        "hostBalance",
        "weights",
        "readWeights",
        "hostWeights",
        "lbLogFile",
        "lbLogLevel",
        "metricsCsvPath"
    };

    @Test
    public void aCubridLbKeyFailsTheUrlAndNamesTheOption() {
        try {
            configOf(BASE + "?cubrid.lb.runtime.failover.enabled=false");
            fail("a cubrid.lb.* key was accepted");
        } catch (SQLException e) {
            assertTrue(
                    e.getMessage(), e.getMessage().contains("cubrid.lb.runtime.failover.enabled"));
            assertTrue(e.getMessage(), e.getMessage().contains("cannot reach the broker"));
        }
    }

    @Test
    public void formerKeysAreRetainedLikeAnyUnknownKey() {
        for (int i = 0; i < FORMER_KEYS.length; i++) {
            String key = FORMER_KEYS[i];
            Result r =
                    LoadBalanceOptionValidator.validate(true, Collections.singletonMap(key, "x"));
            assertTrue(key + " was ignored", r.getIgnoredKeys().isEmpty());
            assertEquals(key, "x", r.getRetainedOptions().get(key));
        }
    }

    @Test
    public void formerKeysReachThePhysicalUrl() throws SQLException {
        LoadBalanceSettings c = configOf(BASE + "?haLbMode=on&hostBalance=rr&lbLogFile=lb.log");

        String url =
                JdbcEndpointConnSpecFactory.forEndpoint(c, null, new Endpoint("node1", 30000))
                        .getJdbcUrl();

        assertTrue(url, url.contains("haLbMode=on"));
        assertTrue(url, url.contains("hostBalance=rr"));
        assertTrue(url, url.contains("lbLogFile=lb.log"));
    }

    private static LoadBalanceSettings configOf(final String url) throws SQLException {
        return LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));
    }
}
