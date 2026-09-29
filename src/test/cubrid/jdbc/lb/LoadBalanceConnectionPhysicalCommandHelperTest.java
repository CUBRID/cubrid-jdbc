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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.connection.SimpleEndpointConnManager;
import cubrid.jdbc.lb.state.SharedSelectorState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Test;

public final class LoadBalanceConnectionPhysicalCommandHelperTest {

    @Test
    public void assertPhysicalCommandHelpersReturnDistinctConnectionsWhenSessionBound()
            throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        SimpleEndpointConnManager manager = new SimpleEndpointConnManager();
        connection.setConnectionManager(manager);
        connection.setSharedSelectorState(new SharedSelectorState());

        connection.initSessionBindings(createTopologyWithSingleRo());

        Connection rwPhysical = newConnectionProxy();
        Connection roPhysical = newConnectionProxy();
        manager.setPhysicalConnection(connection.getCurrentEp(SessionLeg.RW), rwPhysical);
        manager.setPhysicalConnection(connection.getCurrentEp(SessionLeg.RO), roPhysical);

        assertSame(rwPhysical, connection.getPhysicalConnForCmd(SessionLeg.RW));
        assertSame(roPhysical, connection.getPhysicalConnForCmd(SessionLeg.RO));
    }

    @Test
    public void assertPhysicalCommandHelpersThrowWhenNotSessionInitialized() throws Exception {
        LoadBalanceConnection connection = createSessionConnection();
        connection.setConnectionManager(new SimpleEndpointConnManager());
        try {
            connection.getPhysicalConnForCmd(SessionLeg.RO);
            fail("Expected SQLException");
        } catch (SQLException ex) {
        }
    }

    private static LoadBalanceConnection createSessionConnection() {
        Properties properties = new Properties();
        properties.setProperty(LoadBalanceSettings.KEY_DISTRIBUTION_MODE, "session");
        return new LoadBalanceConnection(LoadBalanceSettings.of(properties));
    }

    private static EndpointTopology createTopologyWithSingleRo() {
        List<Endpoint> roEndpoints = new ArrayList<Endpoint>();
        roEndpoints.add(new Endpoint("ro1", 33000));
        return new EndpointTopology(new Endpoint("rw", 33000), roEndpoints, null);
    }

    private static Connection newConnectionProxy() {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new InvocationHandler() {
                            public Object invoke(Object proxy, Method method, Object[] args)
                                    throws Throwable {
                                if ("isClosed".equals(method.getName())) {
                                    return Boolean.FALSE;
                                }
                                if (Boolean.TYPE.equals(method.getReturnType())) {
                                    return Boolean.FALSE;
                                }
                                if (Integer.TYPE.equals(method.getReturnType())) {
                                    return Integer.valueOf(0);
                                }
                                if (Long.TYPE.equals(method.getReturnType())) {
                                    return Long.valueOf(0L);
                                }
                                return null;
                            }
                        });
    }
}
