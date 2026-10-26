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

import cubrid.jdbc.lb.LbExceptions;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Builds the per-broker physical {@link JdbcPhyConnSpec} for a URI {@code loadbalance://} config
 * and the client {@link java.util.Properties}.
 */
public final class JdbcEndpointConnSpecFactory {

    // The option grammar of the core driver's URL_PATTERN (CUBRIDDriver).
    private static final Pattern PHYSICAL_OPTION_KEY = Pattern.compile("[a-zA-Z_0-9]+");
    private static final Pattern PHYSICAL_OPTION_VALUE = Pattern.compile("[^&=?]+");

    private JdbcEndpointConnSpecFactory() {}

    /**
     * Builds a per-broker physical spec for a URI {@code loadbalance://} config, assembling the
     * single-broker URL from the parsed config fields. Loadbalance-only options (topology ports,
     * {@code readWeight}, metrics keys) are not propagated: they configure the LB layer, not one
     * physical connection.
     *
     * @param config the URL-derived load-balance settings
     * @param clientInfo the client-supplied connection properties
     * @param endpoint the broker endpoint the physical connection targets
     * @return the physical connection spec for {@code endpoint}
     * @throws SQLException if the config or endpoint is missing/unusable
     */
    public static JdbcPhyConnSpec forEndpoint(
            final LoadBalanceSettings config, final Properties clientInfo, final Endpoint endpoint)
            throws SQLException {
        if (config == null) {
            throw LbExceptions.internalState("missing load balance configuration");
        }
        if (endpoint == null) {
            throw LbExceptions.internalState("missing target endpoint");
        }

        String variant = config.getUrlVariant();
        if (variant == null) {
            variant = "";
        }

        String db = config.getDatabaseName();

        Properties merged = mergeClientProps(clientInfo);

        String user = credential(merged.getProperty("user"), config.getUrlUser());
        String password = credential(merged.getProperty("password"), config.getUrlPassword());

        if (user != null) {
            merged.setProperty("user", user);
        }

        if (password != null) {
            merged.setProperty("password", password);
        }

        String physicalQuery = buildPropagatedQuery(config.getPropagatedOptions());

        String urlUser = user != null ? user : "";

        String jdbcUrl = buildJdbcUrl(variant, endpoint, db, urlUser, physicalQuery);

        return new JdbcPhyConnSpec(jdbcUrl, merged);
    }

    /** {@code ?k=v&...} from common propagated options, dropping LB-only keys. */
    private static String buildPropagatedQuery(final Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : options.entrySet()) {
            final String key = e.getKey();
            if (!isPropagated(key)) {
                continue;
            }
            sb.append(sb.length() == 0 ? '?' : '&');
            sb.append(key).append('=').append(e.getValue() == null ? "" : e.getValue());
        }

        return sb.toString();
    }

    /**
     * Whether the broker URL carries {@code key}. LB-only keys (rwPort/..., metrics*, lbLog*) stay
     * here: they configure the LB layer, and the broker would only leak them into logs.
     */
    private static boolean isPropagated(final String key) {
        return key != null && key.length() > 0 && !isLbTopologyOption(key);
    }

    /**
     * Refuses, while the URL is parsed, an option the broker URL could not carry. The core driver
     * would refuse the URL on every leg's connect instead, far from the option that caused it.
     *
     * @param options the options a loadbalance URL propagates
     * @throws SQLException naming the first option the core URL grammar rejects
     */
    static void checkPropagatable(final Map<String, String> options) throws SQLException {
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (!isPropagated(e.getKey())) {
                continue;
            }
            final String value = e.getValue() == null ? "" : e.getValue();
            if (!PHYSICAL_OPTION_KEY.matcher(e.getKey()).matches()
                    || !PHYSICAL_OPTION_VALUE.matcher(value).matches()) {
                throw LbExceptions.invalidUrl(
                        "option '"
                                + e.getKey()
                                + "' cannot reach the broker: the name takes letters, digits and"
                                + " '_', the value must be non-empty without '&', '=' or '?'",
                        null);
            }
        }
    }

    private static boolean isLbTopologyOption(final String name) {
        for (final String key : LoadBalanceOptionValidator.LOADBALANCE_ONLY) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }

        return false;
    }

    private static String buildJdbcUrl(
            String variant,
            Endpoint endpoint,
            String db,
            String userSegment,
            String physicalQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:cubrid");
        sb.append(variant);
        sb.append(':');
        sb.append(endpoint.getHost());
        sb.append(':');
        sb.append(endpoint.getPort());
        sb.append(':');
        sb.append(db);
        sb.append(':');
        sb.append(userSegment == null ? "" : userSegment);
        sb.append(':');
        // The password is left out of the physical URL on purpose. It travels in the connect
        // Properties, which the core driver prefers over URL segments, so it cannot leak through
        // logs or error messages that echo the URL.
        sb.append(':');
        if (physicalQuery != null && physicalQuery.length() > 0) {
            sb.append(physicalQuery);
        }

        return sb.toString();
    }

    private static Properties mergeClientProps(Properties clientInfo) {
        Properties p = new Properties();
        if (clientInfo != null) {
            for (String name : clientInfo.stringPropertyNames()) {
                p.setProperty(name, clientInfo.getProperty(name));
            }
        }

        return p;
    }

    /**
     * The classic rule: {@code info} whenever it has the key, even empty, else the URL. Used as
     * given - a password may begin or end with a space.
     */
    private static String credential(String fromInfo, String fromUrl) {
        return fromInfo != null ? fromInfo : fromUrl;
    }
}
