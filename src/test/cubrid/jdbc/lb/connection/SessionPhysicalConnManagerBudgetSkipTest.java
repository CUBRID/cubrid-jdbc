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

package cubrid.jdbc.lb.connection;

import static org.junit.Assert.assertTrue;

import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.Test;

/**
 * The per-group attempt budget must bound DIALS, not silently retire candidates. When a tier's
 * budget runs out on a candidate, that candidate was never dialed — so a later tier holding the
 * same endpoint (a degenerate topology, per {@code readCandidateTiers}) must still be free to try
 * it against its own budget.
 */
public class SessionPhysicalConnManagerBudgetSkipTest {

    private static final String LOGICAL_URL = "jdbc:cubrid:localhost:30000:testdb:public::";
    private static final Endpoint A = new Endpoint("hostA", 33002);
    private static final Endpoint B = new Endpoint("hostB", 33002);

    @Test
    public void assertBudgetBreakDoesNotRetireAnUndialedCandidate() throws Exception {
        final List<String> dialed = new ArrayList<String>();
        // Only hostA is down; hostB would connect if it were ever dialed.
        SessionPhysicalConnManager mgr =
                newManager(dialed, new LinkedHashSet<String>(Arrays.asList("hostA")));

        // tier0 = [A, B], budget 1 -> A is dialed and fails, B breaks the budget (never dialed)
        // tier1 = [B], its own budget -> B must still be dialed here
        List<List<Endpoint>> tiers = new ArrayList<List<Endpoint>>();
        tiers.add(Arrays.asList(A, B));
        tiers.add(Collections.singletonList(B));

        String outcome;
        try {
            SessionPhysicalConnManager.BindResult r =
                    mgr.openFirstReachablePerGroup(tiers, "RO", Collections.<String>emptySet(), 1);
            outcome = "bound to " + r.getEndpoint().getId();
        } catch (SQLException ex) {
            outcome = "FAILED: " + ex.getMessage();
        }

        assertTrue(
                "hostB was never dialed even though tier1 had budget for it; dialed="
                        + dialed
                        + " outcome="
                        + outcome,
                dialed.contains("hostB"));
    }

    private static SessionPhysicalConnManager newManager(
            final List<String> dialed, final Set<String> downEndpointIds) {
        Properties cfg = new Properties();
        cfg.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        final LoadBalanceSettings config = LoadBalanceSettings.of(cfg);

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory() {
                    public Connection getConnection(final String url, final Properties info)
                            throws SQLException {
                        for (String epId : new String[] {"hostA", "hostB"}) {
                            if (url.contains(":" + epId + ":")) {
                                dialed.add(epId);
                                if (downEndpointIds.contains(epId)) {
                                    throw new SQLException(
                                            "down: " + epId, null, UErrorCode.ER_COMMUNICATION);
                                }
                            }
                        }
                        return connectionProxy();
                    }
                };

        return new SessionPhysicalConnManager(LOGICAL_URL, new Properties(), config, factory);
    }

    private static Connection connectionProxy() {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(Object p, Method m, Object[] a) {
                                Class<?> r = m.getReturnType();
                                if (r == boolean.class) return Boolean.FALSE;
                                if (r == int.class) return Integer.valueOf(0);
                                return null;
                            }
                        });
    }
}
