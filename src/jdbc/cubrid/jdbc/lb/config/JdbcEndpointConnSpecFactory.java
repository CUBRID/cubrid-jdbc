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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds per-broker physical {@link JdbcPhyConnSpec} from the logical JDBC URL and client {@link
 * java.util.Properties}, stripping LB bootstrap parameters.
 *
 * <p>Entry points: {@link #forEndpoint} for a classic colon-delimited logical URL and {@link
 * #forEndpointFromConfig} for a URI {@code loadbalance://} config.
 */
public final class JdbcEndpointConnSpecFactory {
    private static final Pattern URL_PATTERN =
            Pattern.compile(
                    "jdbc:cubrid(-oracle|-mysql)?:([a-zA-Z_0-9\\.-]*):([0-9]*):([^:]+):([^:]*):([^:]*):(\\?[a-zA-Z_0-9]+=[^&=?]+(&[a-zA-Z_0-9]+=[^&=?]+)*)?",
                    Pattern.CASE_INSENSITIVE);

    private JdbcEndpointConnSpecFactory() {}

    public static JdbcPhyConnSpec forEndpoint(
            String logicalJdbcUrl, Properties clientInfo, Endpoint endpoint) throws SQLException {
        return forEndpoint(logicalJdbcUrl, clientInfo, endpoint, null);
    }

    public static JdbcPhyConnSpec forEndpoint(
            String logicalJdbcUrl,
            Properties clientInfo,
            Endpoint endpoint,
            LoadBalanceSettings lbConfig)
            throws SQLException {
        if (logicalJdbcUrl == null) {
            throw LbExceptions.invalidUrl("logical JDBC URL is null", null);
        }
        if (endpoint == null) {
            throw LbExceptions.internalState("missing target endpoint");
        }

        Matcher matcher = URL_PATTERN.matcher(logicalJdbcUrl);
        if (!matcher.matches()) {
            throw LbExceptions.invalidUrl(
                    "Invalid CUBRID JDBC URL for physical mapping: " + logicalJdbcUrl, null);
        }

        String variant = matcher.group(1);
        if (variant == null) {
            variant = "";
        }

        String db = matcher.group(4);

        String userInUrl = matcher.group(5);

        String passInUrl = matcher.group(6);

        String query = matcher.group(7);

        Properties merged = mergeClientProps(clientInfo);

        String user =
                firstNonEmpty(
                        lbConfig != null ? lbConfig.getPhysicalJdbcUser() : null,
                        merged.getProperty("user"),
                        userInUrl);
        String password =
                firstNonEmpty(
                        lbConfig != null ? lbConfig.getPhysicalJdbcPassword() : null,
                        merged.getProperty("password"),
                        passInUrl);

        if (user != null) {
            merged.setProperty("user", user);
        }

        if (password != null) {
            merged.setProperty("password", password);
        }

        String physicalQuery = stripLbBootstrapQuery(query);

        String urlUser = user != null ? user : (userInUrl == null ? "" : userInUrl);

        String jdbcUrl = buildJdbcUrl(variant, endpoint, db, urlUser, physicalQuery);

        return new JdbcPhyConnSpec(jdbcUrl, merged);
    }

    /**
     * Builds a per-broker physical spec for a URI {@code loadbalance://} config. The classic {@link
     * #URL_PATTERN} cannot parse a URI URL, so the single-broker URL is assembled from the parsed
     * config fields instead. Loadbalance-only options (topology ports, {@code readWeight}, metrics
     * keys) are not propagated: they configure the LB layer, not one physical connection.
     *
     * @param config the URL-derived load-balance settings
     * @param clientInfo the client-supplied connection properties
     * @param endpoint the broker endpoint the physical connection targets
     * @return the physical connection spec for {@code endpoint}
     * @throws SQLException if the config or endpoint is missing/unusable
     */
    public static JdbcPhyConnSpec forEndpointFromConfig(
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

        String user =
                firstNonEmpty(
                        config.getPhysicalJdbcUser(),
                        merged.getProperty("user"),
                        config.getUrlUser());
        String password =
                firstNonEmpty(
                        config.getPhysicalJdbcPassword(),
                        merged.getProperty("password"),
                        config.getUrlPassword());

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
            // Drop LB topology keys (rwPort/...) and LB bootstrap keys (haLb*/altHosts/
            // cubrid.lb.*). The physical broker ignores them, so propagating them is dead weight
            // and would leak them into logs. Mirrors stripLbBootstrapQuery on the classic path.
            if (key == null
                    || key.length() == 0
                    || isLbTopologyOption(key)
                    || isLbBootstrapOrAltHostsKey(key)) {
                continue;
            }
            sb.append(sb.length() == 0 ? '?' : '&');
            sb.append(key).append('=').append(e.getValue() == null ? "" : e.getValue());
        }

        return sb.toString();
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

    public static String stripLbBootstrapQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.length() == 0) {
            return "";
        }

        String q = rawQuery.startsWith("?") ? rawQuery.substring(1) : rawQuery;
        if (q.length() == 0) {
            return "";
        }

        List<String> kept = new ArrayList<String>();

        String[] parts = q.split("&");

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part == null || part.length() == 0) {
                continue;
            }

            int eq = part.indexOf('=');

            String name = eq >= 0 ? part.substring(0, eq) : part;
            if (isLbBootstrapOrAltHostsKey(name)) {
                continue;
            }
            kept.add(part);
        }

        if (kept.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("?");

        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(kept.get(i));
        }

        return sb.toString();
    }

    private static boolean isLbBootstrapOrAltHostsKey(String name) {
        if (name == null) {
            return true;
        }

        String n = name.trim();
        if (n.equalsIgnoreCase("haLbMode")
                || n.equalsIgnoreCase("haLbConfig")
                || n.equalsIgnoreCase("haLbSharedStateKey")
                || n.equalsIgnoreCase("altHosts")) {
            return true;
        }

        final String cubridLbPrefix = "cubrid.lb.";
        if (n.regionMatches(true, 0, cubridLbPrefix, 0, cubridLbPrefix.length())) {
            return true;
        }

        return false;
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

    private static String firstNonEmpty(String a, String b, String c) {
        if (a != null && a.trim().length() > 0) {
            return a.trim();
        }

        if (b != null && b.trim().length() > 0) {
            return b.trim();
        }

        if (c != null && c.trim().length() > 0) {
            return c.trim();
        }

        return null;
    }
}
