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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Structural parser for the URI ({@code ://}) CUBRID JDBC URL:
 *
 * <pre>
 *   URI-url      ::= single-node-url | loadbalance-url
 *   single-node-url ::= "jdbc:cubrid" [variant] "://" host [ ":" port ] "/" db-cred [ "?" options ]
 *   loadbalance-url ::= "jdbc:cubrid" [variant] ":loadbalance://" host-list
 *                       [ ";" "replica=" replica-list ] "/" db-cred [ "?" options ]
 *   host-token      ::= host [ ":" rw_port [ ":" ro_port ] ]
 *   replica-token   ::= host [ ":" so_port ]
 *   db-cred         ::= db [ ":" user ":" pw ":" ]
 *   options         ::= option { ("&amp;" | ";") option }
 * </pre>
 *
 * <p>This parser only splits the URL into structural pieces: scheme, variant, mode, host and
 * replica tokens with their inline ports, database, positional credentials, and the raw option map.
 * Role assignment, port defaulting, {@code readWeight} parsing, and option allow-list checks are
 * later stages. Malformed structure raises {@link SQLException}; IPv6 hosts are unsupported, as in
 * the classic parser.
 *
 * <p>Both URI forms parse here, but in production only {@code loadbalance://} URLs reach this
 * class: the driver rewrites a single-node URI URL into the classic colon-delimited form and
 * connects it through the classic path ({@code CUBRIDDriver.uriSingleToClassicUrl}).
 */
public final class LoadBalanceUrlParser {

    /** Inline port not specified in a host/replica token; resolved later from global options. */
    public static final int PORT_UNSPECIFIED = -1;

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final String SCHEME_SEPARATOR = "://";
    private static final String REPLICA_MARKER = ";replica=";
    private static final String LOADBALANCE_MODE = "loadbalance";
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    private LoadBalanceUrlParser() {}

    public static ParsedUrl parse(final String url) throws SQLException {
        if (url == null || url.trim().length() == 0) {
            throw LbExceptions.invalidUrl("URI URL is null or empty", null);
        }

        final String trimmed = url.trim();

        final int sep = trimmed.indexOf(SCHEME_SEPARATOR);
        if (sep < 0) {
            throw LbExceptions.invalidUrl(
                    "URI URL must contain '" + SCHEME_SEPARATOR + "': " + url, null);
        }

        final SchemeParts scheme = parseScheme(trimmed.substring(0, sep), url);
        final String rest = trimmed.substring(sep + SCHEME_SEPARATOR.length());

        final String beforeOptions;
        final String optionsStr;
        final int qIdx = rest.indexOf('?');
        if (qIdx >= 0) {
            beforeOptions = rest.substring(0, qIdx);
            optionsStr = rest.substring(qIdx + 1);
        } else {
            beforeOptions = rest;
            optionsStr = null;
        }

        final int slash = beforeOptions.indexOf('/');
        if (slash < 0) {
            throw LbExceptions.invalidUrl("URI URL is missing '/<db>': " + url, null);
        }

        final String subname = beforeOptions.substring(0, slash);
        final String dbInfoStr = beforeOptions.substring(slash + 1);

        final List<HostToken> hosts = new ArrayList<HostToken>();
        final List<ReplicaToken> replicas = new ArrayList<ReplicaToken>();
        parseSubname(subname, hosts, replicas, url);

        final DbInfo dbInfo = parseDbInfo(dbInfoStr, url);
        final Map<String, String> options = parseOptions(optionsStr, url);

        return new ParsedUrl(
                scheme.variant,
                scheme.loadbalance,
                hosts,
                replicas,
                dbInfo.db,
                dbInfo.user,
                dbInfo.password,
                options);
    }

    private static SchemeParts parseScheme(final String scheme, final String url)
            throws SQLException {
        final String[] parts = scheme.split(":", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw LbExceptions.invalidUrl("unrecognized URL scheme '" + scheme + "': " + url, null);
        }
        if (!"jdbc".equalsIgnoreCase(parts[0])) {
            throw LbExceptions.invalidUrl("URL scheme must start with 'jdbc': " + url, null);
        }

        final String variant = parseVariant(parts[1], url);

        boolean loadbalance = false;
        if (parts.length == 3) {
            if (!LOADBALANCE_MODE.equalsIgnoreCase(parts[2])) {
                throw LbExceptions.invalidUrl(
                        "unknown connection mode '"
                                + parts[2]
                                + "' (only '"
                                + LOADBALANCE_MODE
                                + "' is supported): "
                                + url,
                        null);
            }
            loadbalance = true;
        }

        return new SchemeParts(variant, loadbalance);
    }

    private static String parseVariant(final String token, final String url) throws SQLException {
        if ("cubrid".equalsIgnoreCase(token)) {
            return "";
        }
        if ("cubrid-oracle".equalsIgnoreCase(token)) {
            return "-oracle";
        }
        if ("cubrid-mysql".equalsIgnoreCase(token)) {
            return "-mysql";
        }
        throw LbExceptions.invalidUrl(
                "unrecognized CUBRID scheme token '" + token + "': " + url, null);
    }

    private static void parseSubname(
            final String subname,
            final List<HostToken> hosts,
            final List<ReplicaToken> replicas,
            final String url)
            throws SQLException {
        if (subname == null || subname.trim().length() == 0) {
            throw LbExceptions.invalidUrl("host-list is empty: " + url, null);
        }

        String hostListStr = subname;
        String replicaListStr = null;

        final int rIdx = subname.toLowerCase(Locale.ROOT).indexOf(REPLICA_MARKER);
        if (rIdx >= 0) {
            hostListStr = subname.substring(0, rIdx);
            replicaListStr = subname.substring(rIdx + REPLICA_MARKER.length());
        }

        final String[] hostTokens = hostListStr.split(",", -1);
        for (int i = 0; i < hostTokens.length; i++) {
            hosts.add(parseHostToken(hostTokens[i], url));
        }
        if (hosts.isEmpty()) {
            throw LbExceptions.invalidUrl("host-list is empty: " + url, null);
        }

        if (replicaListStr != null) {
            if (replicaListStr.trim().length() == 0) {
                throw LbExceptions.invalidUrl(
                        "replica-list is empty after '" + REPLICA_MARKER + "': " + url, null);
            }
            final String[] replicaTokens = replicaListStr.split(",", -1);
            for (int i = 0; i < replicaTokens.length; i++) {
                replicas.add(parseReplicaToken(replicaTokens[i], url));
            }
        }
    }

    private static HostToken parseHostToken(final String token, final String url)
            throws SQLException {
        final String t = token.trim();
        if (t.length() == 0) {
            throw LbExceptions.invalidUrl("empty host token in host-list: " + url, null);
        }

        final String[] p = t.split(":", -1);
        if (p.length > 3) {
            throw LbExceptions.invalidUrl(
                    "too many ':' in host token '"
                            + t
                            + "' (expected host[:rw_port[:ro_port]]): "
                            + url,
                    null);
        }

        validateHost(p[0], t, url);

        final int rwPort = parseInlinePort(p, 1, t, url);
        final int roPort = parseInlinePort(p, 2, t, url);

        return new HostToken(p[0], rwPort, roPort);
    }

    private static ReplicaToken parseReplicaToken(final String token, final String url)
            throws SQLException {
        final String t = token.trim();
        if (t.length() == 0) {
            throw LbExceptions.invalidUrl("empty replica token in replica-list: " + url, null);
        }

        final String[] p = t.split(":", -1);
        if (p.length > 2) {
            throw LbExceptions.invalidUrl(
                    "too many ':' in replica token '" + t + "' (expected host[:so_port]): " + url,
                    null);
        }

        validateHost(p[0], t, url);

        final int soPort = parseInlinePort(p, 1, t, url);

        return new ReplicaToken(p[0], soPort);
    }

    private static void validateHost(final String host, final String token, final String url)
            throws SQLException {
        if (host == null || host.length() == 0 || !HOST_PATTERN.matcher(host).matches()) {
            throw LbExceptions.invalidUrl(
                    "invalid host '"
                            + host
                            + "' in token '"
                            + token
                            + "' (IPv6 unsupported): "
                            + url,
                    null);
        }
    }

    private static int parseInlinePort(
            final String[] parts, final int index, final String token, final String url)
            throws SQLException {
        if (index >= parts.length) {
            return PORT_UNSPECIFIED;
        }

        final String seg = parts[index];
        if (seg.length() == 0) {
            throw LbExceptions.invalidUrl(
                    "empty port segment in token '" + token + "': " + url, null);
        }

        try {
            final int port = Integer.parseInt(seg);
            if (port < MIN_PORT || port > MAX_PORT) {
                throw LbExceptions.invalidUrl(
                        "port out of range ("
                                + MIN_PORT
                                + "-"
                                + MAX_PORT
                                + ") in token '"
                                + token
                                + "': "
                                + url,
                        null);
            }
            return port;
        } catch (NumberFormatException e) {
            throw LbExceptions.invalidUrl(
                    "invalid port '" + seg + "' in token '" + token + "': " + url, e);
        }
    }

    private static DbInfo parseDbInfo(final String dbInfoStr, final String url)
            throws SQLException {
        if (dbInfoStr == null || dbInfoStr.length() == 0) {
            throw LbExceptions.invalidUrl("database name is missing: " + url, null);
        }

        final String[] p = dbInfoStr.split(":", -1);
        // db-info is db[:user[:pw[:]]] - at most 4 segments, and a 4th must be the empty trailing
        // colon. core's classic-URL regex requires exactly db:user:pw:, so reject extras here too
        // instead of dropping them.
        if (p.length > 4 || (p.length == 4 && p[3].length() != 0)) {
            throw LbExceptions.invalidUrlLikeCore(url);
        }
        final String db = p[0];
        if (db.trim().length() == 0) {
            throw LbExceptions.invalidUrl("database name is empty: " + url, null);
        }
        if (db.indexOf('/') >= 0) {
            throw LbExceptions.invalidUrl("invalid database name '" + db + "': " + url, null);
        }

        final String user = p.length > 1 ? emptyToNull(p[1]) : null;
        final String password = p.length > 2 ? emptyToNull(p[2]) : null;

        return new DbInfo(db, user, password);
    }

    private static Map<String, String> parseOptions(final String optionsStr, final String url)
            throws SQLException {
        final Map<String, String> options = new LinkedHashMap<String, String>();
        if (optionsStr == null || optionsStr.length() == 0) {
            return options;
        }

        final String[] entries = optionsStr.split("[&;]", -1);
        for (int i = 0; i < entries.length; i++) {
            final String entry = entries[i];
            if (entry.length() == 0) {
                continue;
            }

            final int eq = entry.indexOf('=');
            if (eq <= 0) {
                throw LbExceptions.invalidUrl(
                        "malformed option '" + entry + "' (expected key=value): " + url, null);
            }

            final String key = entry.substring(0, eq);
            // Option keys are case-insensitive and the last duplicate wins, as in core
            // ConnectionProperties.setProperties. Remove an earlier entry that differs only in
            // case, so both do not survive with the first winning at lookup time.
            final String prior = OptionKeys.findKeyIgnoreCase(options, key);
            if (prior != null) {
                options.remove(prior);
            }
            options.put(key, entry.substring(eq + 1));
        }

        return options;
    }

    private static String emptyToNull(final String s) {
        return (s == null || s.length() == 0) ? null : s;
    }

    /** Result of parsing the scheme portion (before {@code ://}). */
    private static final class SchemeParts {
        private final String variant;
        private final boolean loadbalance;

        private SchemeParts(final String variant, final boolean loadbalance) {
            this.variant = variant;
            this.loadbalance = loadbalance;
        }
    }

    /**
     * Database name and (optional) positional credentials — the result of parsing the URL's {@code
     * db-cred} section ({@code db[:user:pw:]}).
     */
    private static final class DbInfo {
        private final String db;
        private final String user;
        private final String password;

        private DbInfo(final String db, final String user, final String password) {
            this.db = db;
            this.user = user;
            this.password = password;
        }
    }

    /** A {@code host[:rw_port[:ro_port]]} node from the host-list. */
    public static final class HostToken {
        private final String host;
        private final int rwPort;
        private final int roPort;

        HostToken(final String host, final int rwPort, final int roPort) {
            this.host = host;
            this.rwPort = rwPort;
            this.roPort = roPort;
        }

        public String getHost() {
            return host;
        }

        /**
         * Inline RW port, or {@link LoadBalanceUrlParser#PORT_UNSPECIFIED} if absent.
         *
         * @return the inline RW port, or {@link LoadBalanceUrlParser#PORT_UNSPECIFIED}
         */
        public int getRwPort() {
            return rwPort;
        }

        /**
         * Inline RO port, or {@link LoadBalanceUrlParser#PORT_UNSPECIFIED} if absent.
         *
         * @return the inline RO port, or {@link LoadBalanceUrlParser#PORT_UNSPECIFIED}
         */
        public int getRoPort() {
            return roPort;
        }

        public boolean hasRwPort() {
            return rwPort != PORT_UNSPECIFIED;
        }

        public boolean hasRoPort() {
            return roPort != PORT_UNSPECIFIED;
        }
    }

    /** A {@code host[:so_port]} node from the replica-list. */
    public static final class ReplicaToken {
        private final String host;
        private final int soPort;

        ReplicaToken(final String host, final int soPort) {
            this.host = host;
            this.soPort = soPort;
        }

        public String getHost() {
            return host;
        }

        /**
         * Inline SO port, or {@link LoadBalanceUrlParser#PORT_UNSPECIFIED} if absent.
         *
         * @return the inline SO port, or {@link LoadBalanceUrlParser#PORT_UNSPECIFIED}
         */
        public int getSoPort() {
            return soPort;
        }

        public boolean hasSoPort() {
            return soPort != PORT_UNSPECIFIED;
        }
    }

    /** Structured result of parsing a URI URL. Lists/map are unmodifiable. */
    public static final class ParsedUrl {
        private final String variant;
        private final boolean loadbalance;
        private final List<HostToken> hosts;
        private final List<ReplicaToken> replicas;
        private final String db;
        private final String user;
        private final String password;
        private final Map<String, String> options;

        ParsedUrl(
                final String variant,
                final boolean loadbalance,
                final List<HostToken> hosts,
                final List<ReplicaToken> replicas,
                final String db,
                final String user,
                final String password,
                final Map<String, String> options) {
            this.variant = variant;
            this.loadbalance = loadbalance;
            this.hosts = Collections.unmodifiableList(hosts);
            this.replicas = Collections.unmodifiableList(replicas);
            this.db = db;
            this.user = user;
            this.password = password;
            this.options = Collections.unmodifiableMap(options);
        }

        /**
         * Scheme variant: {@code ""}, {@code "-oracle"}, or {@code "-mysql"}.
         *
         * @return the scheme variant
         */
        public String getVariant() {
            return variant;
        }

        /**
         * True if the {@code loadbalance} keyword was present, false for single-node.
         *
         * @return {@code true} if the URL is a loadbalance URL
         */
        public boolean isLoadBalance() {
            return loadbalance;
        }

        /**
         * Host-list tokens (always at least one).
         *
         * @return the host-list tokens
         */
        public List<HostToken> getHosts() {
            return hosts;
        }

        /**
         * Replica-list tokens (possibly empty).
         *
         * @return the replica-list tokens
         */
        public List<ReplicaToken> getReplicas() {
            return replicas;
        }

        public String getDb() {
            return db;
        }

        /**
         * Positional URL user, or {@code null} if absent.
         *
         * @return the URL user, or {@code null}
         */
        public String getUser() {
            return user;
        }

        /**
         * Positional URL password, or {@code null} if absent.
         *
         * @return the URL password, or {@code null}
         */
        public String getPassword() {
            return password;
        }

        /**
         * Raw {@code ?key=value} options in URL order (no interpretation).
         *
         * @return the raw URL options
         */
        public Map<String, String> getOptions() {
            return options;
        }
    }
}
