/*
 * Copyright (C) 2008 Search Solution Corporation.
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

import cubrid.jdbc.jci.BrokerHealthCheck;
import cubrid.jdbc.jci.UClientSideConnection;
import cubrid.jdbc.jci.UJCIManager;
import cubrid.jdbc.jci.UJCIUtil;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Title: CUBRID JDBC Driver Description:
 *
 * @version 2.0
 */
public class CUBRIDDriver implements Driver {
    // version
    public static final String version_string = "@JDBC_DRIVER_VERSION_STRING@";
    public static final int major_version;
    public static final int minor_version;
    public static final int patch_version;

    static {
        StringTokenizer st = new StringTokenizer(version_string, ".");
        if (st.countTokens() != 4) {
            throw new RuntimeException("Could not parse version_string: " + version_string);
        }
        major_version = Integer.parseInt(st.nextToken());
        minor_version = Integer.parseInt(st.nextToken());
        patch_version = Integer.parseInt(st.nextToken());
    }

    // default connection informations
    public static final String default_hostname = "localhost";
    public static final int default_port = 30000;
    public static final String default_user = "public";
    public static final String default_password = "";

    private static final String URL_PATTERN =
            "jdbc:cubrid(-oracle|-mysql)?:([a-zA-Z_0-9\\.-]*):([0-9]*):([^:]+):([^:]*):([^:]*):(\\?[a-zA-Z_0-9]+=[^&=?]+(&[a-zA-Z_0-9]+=[^&=?]+)*)?";
    /**
     * Format of a single-node URI URL ({@code jdbc:cubrid[-variant]://host[:port]/db[:user[:pw[:]]]
     * [?options]}), the counterpart of {@link #URL_PATTERN} for the {@code ://} form. Its field
     * count is fixed, so the whole URL is validated (and decomposed) by this single pattern the way
     * the classic URL is by {@link #URL_PATTERN}: 1=scheme prefix as written, 2=variant, 3=host,
     * 4=port, 5=db, 6=user, 7=password, 8=options. Host and port may be empty (the classic path
     * fills the defaults, as for a classic {@code jdbc:cubrid:::db:::}); the option group is the
     * same expression the classic pattern uses, so it must start with {@code '?'}.
     */
    private static final String URL_PATTERN_SINGLE =
            "(jdbc:cubrid(-oracle|-mysql)?)://([a-zA-Z_0-9\\.-]*)(?::([0-9]*))?/([^:?]+)(?::([^:?]*))?(?::([^:?]*))?(?::)?(\\?[a-zA-Z_0-9]+=[^&=?]+(&[a-zA-Z_0-9]+=[^&=?]+)*)?";

    private static final String CUBRID_JDBC_URL_HEADER = "jdbc:cubrid";
    private static final String ENV_JDBC_PROP_NAME = "CUBRID_JDBC_PROP";
    private static final String URL_SCHEME_SEPARATOR = "://";
    private static final String LOADBALANCE_MODE_SUFFIX = ":loadbalance";

    /**
     * Internal connect-time property carrying the URL as the user wrote it, independent of the URL
     * actually used to open the physical socket. A URI URL ({@code loadbalance://} or single-node
     * {@code ://}) is rewritten to a classic colon-delimited URL per broker before the physical
     * connect, which otherwise made the URL reported back (JDBC error messages and the CAS DB_INFO
     * handshake) differ from what the user wrote. The LB layer / {@link #connectUri} place the
     * original (password-masked) user URL under this key so {@link
     * cubrid.jdbc.jci.UClientSideConnection} reports it instead. Not a real connection property —
     * {@link ConnectionProperties} ignores unknown keys, so it is inert on the physical connect.
     */
    public static final String USER_URL_PROPERTY = "cubrid.internal.user-url";

    private int conn_count = 0;

    /**
     * URL dispatch mode, determined purely lexically from the URL scheme per jdbc-loadbalance-spec
     * §4.1-4.2: a {@code ://} marks a <em>URI</em> URL; {@code loadbalance} immediately before
     * {@code ://} marks the load-balancing (multi-node) mode, otherwise it is a single-node URI
     * URL. A URL without {@code ://} is the classic colon-delimited format.
     */
    enum UrlMode {
        CLASSIC,
        URI_SINGLE,
        URI_LOADBALANCE
    }

    static {
        try {
            DriverManager.registerDriver(new CUBRIDDriver());
        } catch (SQLException e) {
        }
    }

    private static PrintStream debugOutput;

    static {
        if (UJCIUtil.isConsoleDebug()) {
            try {
                debugOutput = new PrintStream(new File("cubrid.log"));
            } catch (FileNotFoundException e) {
                debugOutput = System.out;
            }
        }
        Thread brokerHealthCheck = new Thread(new BrokerHealthCheck());
        brokerHealthCheck.setDaemon(true);
        brokerHealthCheck.setContextClassLoader(null);
        brokerHealthCheck.start();
    }

    public static void printDebug(String msg) {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd hh:mm:ss.SSS");

        String line = String.format("%s %s", fmt.format(timestamp), msg);
        debugOutput.println(line);
    }

    /*
     * java.sql.Driver interface
     */

    public Connection connect(String url, Properties info) throws SQLException {
        Connection conn = null;
        String holdability = null;
        String dummy = null;
        String host = null;
        String portString = null;
        String db = null;
        String user = null;
        String pass = null;
        String prop = null;
        int port = default_port;

        if (!acceptsURL(url)) {
            return null;
        }

        final UrlMode urlMode = detectUrlMode(url);
        if (urlMode != UrlMode.CLASSIC) {
            return connectUri(url, info, urlMode);
        }

        // The original user URL (password-masked) to show in logs/errors when this connect is a
        // physical leg of a URI connection; supplied by the LB layer / connectUri via
        // USER_URL_PROPERTY. Null for a direct classic connect. Resolved before URL parsing so the
        // invalid_url errors below report the user-written URL, not the internally-rewritten one.
        String userUrl = (info == null) ? null : info.getProperty(USER_URL_PROPERTY);
        if (userUrl != null && userUrl.length() == 0) {
            userUrl = null;
        }

        Pattern pattern = Pattern.compile(URL_PATTERN, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(url);
        if (!matcher.find()) {
            throw new CUBRIDException(
                    CUBRIDJDBCErrorCode.invalid_url, userUrl != null ? userUrl : url, null);
        }

        String match = matcher.group();
        if (!match.equals(url)) {
            throw new CUBRIDException(
                    CUBRIDJDBCErrorCode.invalid_url, userUrl != null ? userUrl : url, null);
        }

        host = matcher.group(2);
        portString = matcher.group(3);
        db = matcher.group(4);
        prop = matcher.group(7);

        UClientSideConnection u_con;
        String resolvedUrl;
        ConnectionProperties connProperties;

        if (host == null || host.length() == 0) {
            host = default_hostname;
        }

        if (portString == null || portString.length() == 0) {
            port = default_port;
        } else {
            port = Integer.parseInt(portString);
        }

        user = info.getProperty("user");
        if (user == null) {
            user = matcher.group(5);
        }

        pass = info.getProperty("password");
        if (pass == null) {
            pass = matcher.group(6);
        }

        String filePath = System.getenv(ENV_JDBC_PROP_NAME);

        if (filePath != null) {
            if (existPropertiesFile(filePath)) {
                Properties temp_prop = new Properties();
                FileInputStream in;
                try {
                    in = new FileInputStream(filePath);
                    temp_prop.load(in);
                    in.close();

                    StringBuilder sbProp = new StringBuilder();
                    String value = null;
                    for (String key : temp_prop.stringPropertyNames()) {
                        value = temp_prop.getProperty(key);
                        if (sbProp.length() == 0) {
                            sbProp.append("?");
                        } else {
                            sbProp.append("&");
                        }
                        sbProp.append(key + "=" + value);
                    }

                    if (sbProp == null || sbProp.length() <= 0) {
                        throw new CUBRIDException(
                                CUBRIDJDBCErrorCode.invalid_prop_file, filePath, null);
                    }

                    prop = sbProp.toString();
                    url =
                            "jdbc:cubrid:"
                                    + host
                                    + ":"
                                    + port
                                    + ":"
                                    + db
                                    + ":"
                                    + user
                                    + ":"
                                    + pass
                                    + ":"
                                    + prop;

                    Matcher propMatcher = pattern.matcher(url);
                    if (!propMatcher.find()) {
                        throw new CUBRIDException(
                                CUBRIDJDBCErrorCode.invalid_prop_file, filePath, null);
                    }

                    String propMatch = propMatcher.group();
                    if (!propMatch.equals(url)) {
                        throw new CUBRIDException(
                                CUBRIDJDBCErrorCode.invalid_prop_file, filePath, null);
                    }

                } catch (FileNotFoundException e) {
                    throw new CUBRIDException(
                            CUBRIDJDBCErrorCode.file_not_found_prop, filePath, null);
                } catch (IOException e) {
                    throw new CUBRIDException(
                            CUBRIDJDBCErrorCode.invalid_prop_file, filePath, null);
                }
            } else {
                throw new CUBRIDException(CUBRIDJDBCErrorCode.file_not_found_prop, filePath, null);
            }
        }

        resolvedUrl = "jdbc:cubrid:" + host + ":" + port + ":" + db + ":" + user + ":********:";
        if (prop != null) {
            resolvedUrl += prop;
        }

        // The URL to report back (JDBC errors + CAS DB_INFO). Normally the reconstructed per-broker
        // resolvedUrl, but when this connect is a physical leg of a URI (loadbalance:// or
        // single-node ://) connection the LB layer / connectUri supply the original user URL
        // (password-masked) via USER_URL_PROPERTY, so the reported URL matches what the user wrote
        // instead of the internally-rewritten classic URL.
        String reportedUrl = (userUrl != null) ? userUrl : resolvedUrl;

        connProperties = new ConnectionProperties();
        connProperties.setProperties(prop);
        connProperties.setProperties(info);

        dummy = connProperties.getAltHosts();
        if (dummy != null) {
            ArrayList<String> altHostList = new ArrayList<String>();
            altHostList.add(host + ":" + port);

            StringTokenizer st = new StringTokenizer(dummy, ",", false);
            while (st.hasMoreTokens()) {
                altHostList.add(st.nextToken());
            }

            String loadBalValue = connProperties.getConnLoadBal();

            adjustHostList(loadBalValue, altHostList);
            try {
                u_con =
                        (UClientSideConnection)
                                UJCIManager.connect(altHostList, db, user, pass, reportedUrl);
            } catch (CUBRIDException e) {
                throw e;
            }
        } else {
            try {
                u_con =
                        (UClientSideConnection)
                                UJCIManager.connect(host, port, db, user, pass, reportedUrl);
            } catch (CUBRIDException e) {
                throw e;
            }
        }

        u_con.setCharset(connProperties.getCharSet());
        u_con.setZeroDateTimeBehavior(connProperties.getZeroDateTimeBehavior());
        u_con.setResultWithCUBRIDTypes(connProperties.getResultWithCUBRIDTypes());

        u_con.setConnectionProperties(connProperties);
        u_con.tryConnect();

        conn = new CUBRIDConnection(u_con, url, user);
        if (conn != null) {
            conn.setHoldability(connProperties.getHoldCursor());
        }
        return conn;
    }

    /**
     * Classify an accepted CUBRID JDBC URL into its dispatch mode. Detection is purely lexical and
     * does not validate the rest of the URL — URI parsing/validation is done later by the URI URL
     * parser.
     *
     * <ul>
     *   <li>no {@code ://} → {@link UrlMode#CLASSIC} (classic colon-delimited URLs never contain
     *       {@code //}, so there is no ambiguity)
     *   <li>{@code loadbalance} as the segment immediately before {@code ://} → {@link
     *       UrlMode#URI_LOADBALANCE}
     *   <li>otherwise (a {@code ://} with no mode keyword) → {@link UrlMode#URI_SINGLE}
     * </ul>
     */
    static UrlMode detectUrlMode(String url) {
        if (url == null) {
            return UrlMode.CLASSIC;
        }

        int sep = url.indexOf(URL_SCHEME_SEPARATOR);
        if (sep < 0) {
            return UrlMode.CLASSIC;
        }

        String scheme = url.substring(0, sep).toLowerCase();

        return scheme.endsWith(LOADBALANCE_MODE_SUFFIX)
                ? UrlMode.URI_LOADBALANCE
                : UrlMode.URI_SINGLE;
    }

    /**
     * Connect via a URI ({@code ://}) URL.
     *
     * <ul>
     *   <li>{@link UrlMode#URI_LOADBALANCE} → parse the role topology / readWeight ({@link
     *       LoadBalanceUrlParser}) into an in-memory {@link LoadBalanceSettings} and return a
     *       {@link LoadBalanceConnection} (write fixed to master RW, reads distributed by
     *       readWeight).
     *   <li>{@link UrlMode#URI_SINGLE} → a single-node URI URL differs from a classic single-node
     *       URL by format only: translate it to the classic colon-delimited form and connect
     *       through the normal single-node path (no LB, no role assignment).
     * </ul>
     */
    private Connection connectUri(String url, Properties info, UrlMode mode) throws SQLException {
        if (mode == UrlMode.URI_LOADBALANCE) {
            LoadBalanceSettings config =
                    LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));
            return LoadBalanceConnection.openFromSettings(url, info, config);
        }

        // Single-node URI URL: connect through the classic path with the rewritten colon-delimited
        // URL, but carry the URI URL along so JDBC errors / CAS show the form the user wrote rather
        // than the rewritten classic form.
        Properties infoWithUserUrl = new Properties();
        if (info != null) {
            for (String name : info.stringPropertyNames()) {
                infoWithUserUrl.setProperty(name, info.getProperty(name));
            }
        }
        infoWithUserUrl.setProperty(USER_URL_PROPERTY, uriSingleLogUrl(url, info));
        return connect(uriSingleToClassicUrl(url), infoWithUserUrl);
    }

    /**
     * Rewrite the credential section of a URI ({@code ://}) CUBRID JDBC URL for logging as {@code
     * db:user:********:}, the same form the classic path's {@code resolvedUrl} logs, leaving every
     * other character (scheme, hosts, ports, {@code ;replica=...}, and {@code ?} options) exactly
     * as the user wrote them. The credential section is what follows the first {@code '/'} after
     * {@code '://'}; the user shown is {@code info}'s {@code user} property (credentials passed to
     * {@link java.sql.DriverManager#getConnection(String, String, String)}) and otherwise the
     * second {@code ':'}-separated segment of the credential section — the same precedence the
     * classic path uses to pick the user it connects with — and the password is always shown
     * masked. A non-URI (classic) URL, or one without a credential section, is returned unchanged.
     *
     * @param url the JDBC URL to mask; may be {@code null}
     * @param info connection properties supplying the effective {@code user}; may be {@code null}
     * @return the URL with its credentials rendered for logging, or the input unchanged when there
     *     is no credential section to rewrite
     */
    public static String maskUriUrlPassword(String url, Properties info) {
        if (url == null) {
            return url;
        }

        int sep = url.indexOf(URL_SCHEME_SEPARATOR);
        if (sep < 0) {
            return url;
        }

        int credStart = url.indexOf('/', sep + URL_SCHEME_SEPARATOR.length());
        if (credStart < 0) {
            return url;
        }

        int qIdx = url.indexOf('?', credStart);
        int credEnd = qIdx >= 0 ? qIdx : url.length();

        String[] segs = url.substring(credStart + 1, credEnd).split(":", -1); // db[:user[:pw[:]]]
        String db = segs[0];

        // Same precedence as the classic path's resolvedUrl: the properties' user (as passed to
        // DriverManager.getConnection(url, user, password)) wins over the inline URL user.
        String user = (info == null) ? null : info.getProperty("user");
        if (user == null) {
            user = segs.length > 1 ? segs[1] : "";
        }

        return url.substring(0, credStart + 1)
                + db
                + ":"
                + user
                + ":********:"
                + url.substring(credEnd);
    }

    /**
     * Translate a single-node URI URL ({@code jdbc:cubrid[-variant]://host[:port]/db:user:pw:?p})
     * to the equivalent classic colon-delimited URL ({@code
     * jdbc:cubrid[-variant]:host:port:db:...}), which differs only in the {@code ://} scheme
     * separator and the {@code /} before the database.
     *
     * <p>The URI URL is validated as written by {@link #URL_PATTERN_SINGLE} — not by checking the
     * rewritten classic URL afterwards — the way a classic URL is validated by {@link
     * #URL_PATTERN}, and a URL that does not match the format raises the same classic "invalid URL"
     * error. An absent host/port/user/password becomes an empty segment, which the classic path
     * fills with its defaults.
     */
    static String uriSingleToClassicUrl(String url) throws SQLException {
        Matcher matcher = matchUriSingleUrl(url);

        // The classic form always carries all six colon-delimited fields, so an unmatched (absent)
        // optional group becomes an empty segment: host/port -> classic defaults, user/password ->
        // taken from the connection Properties instead.
        return matcher.group(1) // scheme prefix, as written: jdbc:cubrid[-variant]
                + ":"
                + nullToEmpty(matcher.group(3)) // host
                + ":"
                + nullToEmpty(matcher.group(4)) // port
                + ":"
                + matcher.group(5) // db (required by the pattern)
                + ":"
                + nullToEmpty(matcher.group(6)) // user
                + ":"
                + nullToEmpty(matcher.group(7)) // password
                + ":"
                + nullToEmpty(matcher.group(8)); // options, including the leading '?'
    }

    /**
     * Match a single-node URI URL against {@link #URL_PATTERN_SINGLE}, raising the classic "invalid
     * URL" error when it does not match the format.
     */
    private static Matcher matchUriSingleUrl(String url) throws SQLException {
        Matcher matcher =
                Pattern.compile(URL_PATTERN_SINGLE, Pattern.CASE_INSENSITIVE).matcher(url);
        if (!matcher.matches()) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_url, url, null);
        }
        return matcher;
    }

    /**
     * The URL to record for logging (JDBC errors + CAS DB_INFO) for a single-node URI connect: the
     * URI form the user wrote, but resolved the same way the classic path's {@code resolvedUrl} is
     * - an absent host/port shows the driver default ({@link #default_hostname}/{@link
     * #default_port}, the values the connect actually uses) and the credentials are rendered {@code
     * db:user:********:}.
     *
     * @param url the single-node URI URL
     * @param info connection properties supplying the effective {@code user}; may be {@code null}
     */
    static String uriSingleLogUrl(String url, Properties info) throws SQLException {
        Matcher matcher = matchUriSingleUrl(url);

        String host = nullToEmpty(matcher.group(3));
        String port = nullToEmpty(matcher.group(4));
        if (host.length() != 0 && port.length() != 0) {
            return maskUriUrlPassword(url, info); // nothing to resolve
        }

        if (host.length() == 0) {
            host = default_hostname;
        }
        if (port.length() == 0) {
            port = String.valueOf(default_port);
        }

        // Replace the authority, keeping the rest of the URL (db-cred + options) as written; the
        // credential section is then rendered by maskUriUrlPassword.
        int credStart =
                url.indexOf('/', url.indexOf(URL_SCHEME_SEPARATOR) + URL_SCHEME_SEPARATOR.length());
        String resolved =
                matcher.group(1)
                        + URL_SCHEME_SEPARATOR
                        + host
                        + ":"
                        + port
                        + url.substring(credStart);

        return maskUriUrlPassword(resolved, info);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public boolean acceptsURL(String url) throws SQLException {
        if (url == null) {
            return false;
        }

        String urlHeader = CUBRID_JDBC_URL_HEADER;
        String className = CUBRIDDriver.class.getName();
        if (className.matches(".*mysql.*")) {
            urlHeader += "-mysql:";
        } else if (className.matches(".*oracle.*")) {
            urlHeader += "-oracle:";
        } else {
            urlHeader += ":";
        }

        if (url.toLowerCase().startsWith(urlHeader)) {
            return true;
        }

        return false;
    }

    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[0];
    }

    public int getMajorVersion() {
        return major_version;
    }

    public int getMinorVersion() {
        return minor_version;
    }

    public boolean jdbcCompliant() {
        return true;
    }

    /* JDK 1.7 */
    public Logger getParentLogger() {
        throw new java.lang.UnsupportedOperationException();
    }

    private boolean existPropertiesFile(String filePath) {
        File file = new File(filePath);
        return file.exists();
    }

    private void adjustHostList(String loadBalValue, ArrayList<String> altHostList) {
        if (ConnectionProperties.LB_VAL_TRUE.equals(loadBalValue)
                || ConnectionProperties.LB_VAL_ROUND_ROBIN.equals(loadBalValue)) {
            int count = increment_conn_count();
            int dist = (count > altHostList.size()) ? (count - 1) % altHostList.size() : count - 1;
            Collections.rotate(altHostList, -dist);
        } else if (ConnectionProperties.LB_VAL_SHUFFLE.equals(loadBalValue)) {
            Collections.shuffle(altHostList);
        } else if (ConnectionProperties.LB_VAL_FALSE.equals(loadBalValue)) {
            // do nothing
        } else {
            throw new IllegalArgumentException("Invalid loadBalValue: " + loadBalValue);
        }
    }

    private synchronized int increment_conn_count() {
        conn_count = (conn_count >= Integer.MAX_VALUE) ? 1 : conn_count + 1;
        return conn_count;
    }
}
