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

import cubrid.jdbc.lb.config.ConfigTestAccess;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.config.EndpointTopology;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import java.lang.reflect.Field;
import java.sql.SQLException;

/**
 * Builds {@link LoadBalanceSettings} for unit tests the way the driver does, from a {@code
 * loadbalance://} URL, so a session binds through the same role-based path as in production.
 */
public final class LbTestSettings {
    /** RW port of the slave that carries the read target; the tests never dial it. */
    private static final int UNUSED_SLAVE_RW_PORT = 1;

    private LbTestSettings() {}

    /**
     * Settings whose master RW is {@code rw} and whose only read target is {@code ro}, so binding a
     * session yields exactly that pair. With {@code ro == null} the session is RW-only.
     *
     * @param rw the master RW endpoint
     * @param ro the read endpoint, or {@code null} for an RW-only session
     * @return the settings
     */
    public static LoadBalanceSettings forBinding(final Endpoint rw, final Endpoint ro) {
        StringBuilder url = new StringBuilder("jdbc:cubrid:loadbalance://");
        url.append(rw.getHost()).append(':').append(ConfigTestAccess.getPort(rw));
        if (ro != null) {
            url.append(',').append(ro.getHost()).append(':').append(UNUSED_SLAVE_RW_PORT);
            url.append(':').append(ConfigTestAccess.getPort(ro));
        }
        url.append("/testdb");
        if (ro != null) {
            url.append("?readWeight=slave:1");
        }
        return fromUrl(url.toString());
    }

    /**
     * Settings under which binding a session on {@code topology} yields its RW and first RO, the
     * pair a test's own topology names.
     *
     * @param topology the topology the test binds
     * @return the settings
     */
    public static LoadBalanceSettings forTopology(final EndpointTopology topology) {
        return forBinding(
                topology.getRwEndpoints().get(0),
                ConfigTestAccess.hasRoEndpoints(topology)
                        ? topology.getRoEndpoints().get(0)
                        : null);
    }

    /**
     * Settings for the topology most tests bind: RW {@code rw:33000}, read {@code ro1:33000}.
     *
     * @return the settings
     */
    public static LoadBalanceSettings defaults() {
        return forBinding(new Endpoint("rw", 33000), new Endpoint("ro1", 33000));
    }

    /**
     * Parses {@code url} as the driver does.
     *
     * @param url a {@code loadbalance://} URL
     * @return the settings
     */
    public static LoadBalanceSettings fromUrl(final String url) {
        try {
            return LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));
        } catch (SQLException e) {
            throw new IllegalStateException("test URL rejected: " + url, e);
        }
    }

    /**
     * Overrides one of the behaviors the driver pins to its {@code DEFAULT_*} value, for a test of
     * the non-default path. No URL option can do this.
     *
     * @param settings the settings to change
     * @param field the {@code LoadBalanceSettings} field name, e.g. {@code rtFailoverEnabled}
     * @param value the value to set
     * @return {@code settings}
     */
    public static LoadBalanceSettings with(
            final LoadBalanceSettings settings, final String field, final Object value) {
        try {
            Field f = LoadBalanceSettings.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(settings, value);
            return settings;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set LoadBalanceSettings." + field, e);
        }
    }
}
