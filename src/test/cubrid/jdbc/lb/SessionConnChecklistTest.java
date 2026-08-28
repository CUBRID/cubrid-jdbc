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

package cubrid.jdbc.lb;

import static org.junit.Assert.assertEquals;

import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

public final class SessionConnChecklistTest {

    @Test
    public void assertSC05CloseOrderStatementThenRelease() throws Exception {
        final List<String> events = new ArrayList<String>();
        LoadBalanceConnection connection = createSessionConnection("round_robin", null);
        connection.trackStatement(createTrackingStatement(events));
        connection.setPhysicalResourceReleaser(
                new LoadBalanceConnection.PhysicalResourceReleaser() {
                    public void releasePreparedStatements() {
                        events.add("releasePrepared");
                    }

                    public void releasePhysicalConnections() {
                        events.add("releasePhysical");
                    }
                });

        connection.close();

        assertEquals("statementClosed", events.get(0));
        assertEquals("releasePrepared", events.get(1));
        assertEquals("releasePhysical", events.get(2));
    }

    private static LoadBalanceConnection createSessionConnection(
            final String algorithm, final String weights) {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        LoadBalanceConnection connection =
                new LoadBalanceConnection(LoadBalanceSettings.of(properties));
        connection.setConnectionManager(new SimpleEndpointConnManager());
        return connection;
    }

    private static Statement createTrackingStatement(final List<String> events) {
        return (Statement)
                Proxy.newProxyInstance(
                        Statement.class.getClassLoader(),
                        new Class[] {Statement.class},
                        new InvocationHandler() {
                            private boolean closed;

                            public Object invoke(Object proxy, Method method, Object[] args)
                                    throws Throwable {
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.valueOf(closed);
                                }
                                if ("close".equals(method.getName())) {
                                    closed = true;
                                    events.add("statementClosed");
                                    return null;
                                }
                                if (Boolean.TYPE.equals(method.getReturnType())) {
                                    return Boolean.FALSE;
                                }
                                if (Integer.TYPE.equals(method.getReturnType())) {
                                    return Integer.valueOf(0);
                                }
                                return null;
                            }
                        });
    }
}
