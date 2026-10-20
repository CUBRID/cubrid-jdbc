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

package cubrid.jdbc.net;

import cubrid.jdbc.jci.SslMode;
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UJciException;
import cubrid.jdbc.jci.UTimedDataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

public class BrokerHandler {
    private static int TIMEOUT_UNIT = 1000;

    /** TLS versions this driver offers, newest first. Anything older is not negotiated. */
    private static final String[] TLS_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    /**
     * Word for word what cci says for the same failure (ssl_handshake_failure_reason() in
     * cci_network.c): a CAS that cannot load its certificate or key closes the connection before
     * the handshake - an older one first wrote a plaintext CAS error - and the cause is only in the
     * CAS log.
     */
    private static final String BROKER_DID_NOT_START_TLS =
            "the broker did not start TLS; its CAS log has the cause, such as an expired or unreadable"
                    + " certificate";

    /**
     * Word for word what cci says when the broker refuses what is offered. Both drivers offer only
     * TLS 1.2 and 1.3 suites, so a broker without them - one that speaks only TLS 1.1 included -
     * answers with a handshake_failure alert.
     */
    private static final String NO_COMMON_TLS =
            "the driver and the broker have no TLS version or cipher suite in common";

    /**
     * The suites this driver offers, the same set as cci and the broker
     * ("ECDHE+AESGCM:ECDHE+CHACHA20:!aNULL:!kRSA"): every TLS 1.3 suite cci offers, and TLS 1.2
     * with ECDHE key exchange and AES-GCM or ChaCha20-Poly1305 only. The JRE's defaults alone still
     * negotiate static RSA (TLS_RSA_*), which has no forward secrecy, with a server that offers
     * nothing else. The renegotiation signal is not a cipher and stays.
     */
    private static final Pattern ALLOWED_CIPHER_SUITE =
            Pattern.compile(
                    "TLS_AES_(128|256)_GCM_SHA(256|384)|TLS_CHACHA20_POLY1305_SHA256"
                            + "|TLS_ECDHE_(RSA|ECDSA)_WITH_(AES_(128|256)_GCM_SHA(256|384)"
                            + "|CHACHA20_POLY1305_SHA256)"
                            + "|TLS_EMPTY_RENEGOTIATION_INFO_SCSV");

    private static final Pattern IPV4_LITERAL =
            Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    /**
     * Accepts any certificate. Used only below {@code verify-ca}, which is what {@code useSSL=true}
     * has always meant: encrypt, verify nothing.
     */
    private static final X509TrustManager TRUST_ALL_TM =
            new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {}

                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {}

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };

    public static Socket connectBroker(String ip, int port, boolean useSSL, int timeout)
            throws IOException, UJciException {
        return connectBroker(ip, port, SslMode.fromUseSSL(useSSL), null, timeout);
    }

    public static Socket connectBroker(
            String ip, int port, SslMode sslMode, KeyStore trustStore, int timeout)
            throws IOException, UJciException {
        Socket toBroker = null;
        Socket toSSLBroker = null;
        UTimedDataInputStream in = null;
        DataOutputStream out = null;
        long begin = System.currentTimeMillis();

        try {
            toBroker = new Socket();
            InetSocketAddress brokerAddress = new InetSocketAddress(ip, port);
            if (timeout <= 0) {
                toBroker.connect(brokerAddress);
            } else {
                toBroker.connect(brokerAddress, timeout);
                timeout -= (System.currentTimeMillis() - begin);
                if (timeout <= 0) {
                    toBroker.close();
                    throw new UJciException(UErrorCode.ER_TIMEOUT);
                }
            }

            toBroker.setSoTimeout(TIMEOUT_UNIT);
            toBroker.setKeepAlive(true);
            in = new UTimedDataInputStream(toBroker.getInputStream(), ip, port, timeout);
            out = new DataOutputStream(toBroker.getOutputStream());

            if (sslMode.usesSsl()) {
                out.write(UConnection.driverInfossl);
            } else {
                out.write(UConnection.driverInfo);
            }

            out.flush();
            int code = in.readInt();
            if (code < 0) {
                // in here, all errors are sent by only a broker
                // the error greater than -10000 is sent by old broker
                if (code > -10000) {
                    code -= 9000;
                }

                // There is an issue that cannot display an error text. (All cas error code)
                if (code == UErrorCode.CAS_ER_SSL_TYPE_NOT_ALLOWED) {
                    throw new UJciException(
                            UErrorCode.ER_DBMS, UErrorCode.CAS_ERROR_INDICATOR, code, "");
                } else {
                    throw new UJciException(code);
                }

            } else if (code == 0) {
                if (sslMode.usesSsl()) {
                    toSSLBroker = (Socket) createSSLSocket(toBroker, ip, port, sslMode, trustStore);
                    return (Socket) toSSLBroker;
                } else {
                    return toBroker;
                }
            }

            // if (code > 0) { only windows }
            toBroker.setSoLinger(true, 0);
            toBroker.close();

            toBroker = new Socket(); // need instantiation
            brokerAddress = new InetSocketAddress(ip, code);
            if (timeout <= 0) {
                toBroker.connect(brokerAddress);
            } else {
                timeout -= (System.currentTimeMillis() - begin);
                if (timeout <= 0) {
                    throw new UJciException(UErrorCode.ER_TIMEOUT);
                }
                toBroker.connect(brokerAddress, timeout);
            }

            toBroker.setKeepAlive(true);
            if (sslMode.usesSsl()) {
                toSSLBroker = (Socket) createSSLSocket(toBroker, ip, code, sslMode, trustStore);
                return (Socket) toSSLBroker;
            } else {
                return toBroker;
            }
        } catch (SocketTimeoutException e) {
            if (toBroker != null) {
                toBroker.close();
            }
            throw new UJciException(UErrorCode.ER_TIMEOUT, e);
        } catch (IOException e) {
            if (toBroker != null) {
                toBroker.close();
            }
            throw new UJciException(UErrorCode.ER_CONNECTION, e);
        } catch (UJciException e) {
            /* An error code from the broker, or a TLS handshake that failed, leaves the socket open:
             * JSSE closes it on a fatal alert but not on a read timeout (JDK 11 and later). A close
             * that fails must not hide the error. */
            if (toBroker != null) {
                try {
                    toBroker.close();
                } catch (IOException ignored) {
                }
            }
            throw e;
        } finally {
        }
    }

    private static byte[] PING_INFO = {'P', 'I', 'N', 'G', 0, 0, 0, 0, 0, 0};

    public static void pingBroker(String ip, int port, int timeout) throws IOException {
        Socket toBroker = null;
        DataInputStream in = null;
        DataOutputStream out = null;
        long begin = System.currentTimeMillis();

        try {
            toBroker = new Socket();
            InetSocketAddress brokerAddress = new InetSocketAddress(ip, port);
            if (timeout <= 0) {
                toBroker.connect(brokerAddress);
            } else {
                toBroker.connect(brokerAddress, timeout);
                timeout -= (System.currentTimeMillis() - begin);
                if (timeout <= 0) {
                    String msg = UErrorCode.codeToMessage(UErrorCode.ER_TIMEOUT);
                    throw new SocketTimeoutException(msg);
                }
                toBroker.setSoTimeout(timeout);
            }

            in = new DataInputStream(toBroker.getInputStream());
            out = new DataOutputStream(toBroker.getOutputStream());

            out.write(PING_INFO);
            out.flush();
            in.readInt();
        } finally {
            if (in != null) in.close();
            if (out != null) out.close();
            if (toBroker != null) toBroker.close();
        }
    }

    private static int statusRequest(String ip, int port, byte[] data, int timeout)
            throws IOException, UJciException {
        Socket toBroker = null;
        DataInputStream in = null;
        DataOutputStream out = null;
        long begin = System.currentTimeMillis();

        try {
            toBroker = new Socket();
            InetSocketAddress brokerAddress = new InetSocketAddress(ip, port);
            if (timeout <= 0) {
                toBroker.connect(brokerAddress);
            } else {
                toBroker.connect(brokerAddress, timeout);
                timeout -= (System.currentTimeMillis() - begin);
                if (timeout <= 0) {
                    throw new UJciException(UErrorCode.ER_TIMEOUT);
                }
                toBroker.setSoTimeout(timeout);
            }

            in = new DataInputStream(toBroker.getInputStream());
            out = new DataOutputStream(toBroker.getOutputStream());

            out.write(data);
            out.flush();

            int error = in.readInt();
            return error;
        } catch (SocketTimeoutException e) {
            throw new UJciException(UErrorCode.ER_TIMEOUT);
        } finally {
            if (in != null) in.close();
            if (out != null) out.close();
            if (toBroker != null) toBroker.close();
        }
    }

    private static void cancelRequest(String ip, int port, byte[] data, int timeout)
            throws IOException, UJciException {
        Socket toBroker = null;
        DataInputStream in = null;
        DataOutputStream out = null;
        long begin = System.currentTimeMillis();

        try {
            toBroker = new Socket();
            InetSocketAddress brokerAddress = new InetSocketAddress(ip, port);
            if (timeout <= 0) {
                toBroker.connect(brokerAddress);
            } else {
                toBroker.connect(brokerAddress, timeout);
                timeout -= (System.currentTimeMillis() - begin);
                if (timeout <= 0) {
                    throw new UJciException(UErrorCode.ER_TIMEOUT);
                }
                toBroker.setSoTimeout(timeout);
            }

            in = new DataInputStream(toBroker.getInputStream());
            out = new DataOutputStream(toBroker.getOutputStream());

            out.write(data);
            out.flush();

            int error = in.readInt();
            if (error < 0) {
                throw new UJciException(UErrorCode.CAS_ER_QUERY_CANCEL);
            }
        } catch (SocketTimeoutException e) {
            throw new UJciException(UErrorCode.ER_TIMEOUT);
        } finally {
            if (in != null) in.close();
            if (out != null) out.close();
            if (toBroker != null) toBroker.close();
        }
    }

    private static byte[] STATUS_INFO = {'S', 'T'};

    public static int statusBroker(String ip, int port, int process, byte[] session, int timeout)
            throws IOException, UJciException {

        int status;

        ByteArrayOutputStream bao = new ByteArrayOutputStream(10);
        DataOutputStream dao = new DataOutputStream(bao);

        dao.write(STATUS_INFO);
        dao.writeInt(process);
        for (int i = 0; i < 4; i++) dao.writeByte(session[i]);

        status = statusRequest(ip, port, bao.toByteArray(), timeout);

        return status;
    }

    /* session is null when the broker is older than PROTOCOL_V13; such a broker reads only the
     * 10-byte header and would leave an appended session id unread in its socket. */
    public static void cancelBroker(String ip, int port, int process, byte[] session, int timeout)
            throws IOException, UJciException {
        ByteArrayOutputStream bao = new ByteArrayOutputStream(14);
        DataOutputStream dao = new DataOutputStream(bao);
        dao.write('X');
        dao.write('1');
        dao.write(UConnection.driverInfo[6]);
        if (session != null) {
            /* Set the session-cancel capability bit only on this cancel request, not on the shared
             * driverInfo array used for the main connect handshake, matching cubrid-cci's approach. */
            dao.write(UConnection.driverInfo[7] | UConnection.CAS_SUPPORT_SESSION_CANCEL);
        } else {
            dao.write(UConnection.driverInfo[7]);
        }
        dao.write(UConnection.driverInfo[8]);
        dao.write(UConnection.driverInfo[9]);
        dao.writeInt(process);
        if (session != null) {
            dao.write(session);
        }

        cancelRequest(ip, port, bao.toByteArray(), timeout);
    }

    /**
     * Wraps the connected socket in TLS.
     *
     * <p>Below {@code verify-ca} this keeps the historical behaviour - encrypted, unverified - so
     * that existing {@code useSSL=true} deployments are unaffected. From {@code verify-ca} up, the
     * trust managers come from a {@link TrustManagerFactory}: its X509ExtendedTrustManager is what
     * makes {@code setEndpointIdentificationAlgorithm} effective, which a hand-written
     * X509TrustManager does not reliably do.
     *
     * @param plainSocket the connected TCP socket to upgrade
     * @param host the host as the caller named it - less a trailing dot, the SNI value and the name
     *     checked against the certificate
     * @param port the broker port
     * @param sslMode how much of the certificate to check
     * @param trustStore anchors for the chain check; null only below {@code verify-ca}
     * @return the handshaken TLS socket
     * @throws UJciException {@code ER_SSL_CERT_VERIFY} when the certificate was rejected, {@code
     *     ER_SSL_HANDSHAKE} for any other handshake failure
     */
    private static SSLSocket createSSLSocket(
            Socket plainSocket, String host, int port, SslMode sslMode, KeyStore trustStore)
            throws UJciException {
        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLS");
            if (sslMode.verifiesCertificate()) {
                /* getTrustStore() never gives null here, but connectBroker() is public: null would
                 * make tmf.init() take the JVM cacerts, and withDateCheck() throw an NPE. */
                if (trustStore == null) {
                    throw new UJciException(UErrorCode.ER_CONNECTION);
                }
                TrustManagerFactory tmf =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                ctx.init(null, withDateCheck(tmf.getTrustManagers(), trustStore), null);
            } else {
                ctx.init(null, new TrustManager[] {TRUST_ALL_TM}, new SecureRandom());
            }
        } catch (GeneralSecurityException e) {
            throw new UJciException(UErrorCode.ER_CONNECTION, e);
        }

        /* An absolute name ("db.example.com.") is the same host without its trailing dot, and SNI and
         * the certificate check take it without: RFC 6066 writes SNI so, and cci does the same. JSSE
         * drops the dot itself only from 8u372, 11.0.19 and 17.0.6; before, it refused the name.
         * The socket is already connected, so the address came from the name as written. */
        String name =
                (host != null && host.endsWith(".")) ? host.substring(0, host.length() - 1) : host;

        SSLSocket sslSocket;
        try {
            sslSocket =
                    (SSLSocket) ctx.getSocketFactory().createSocket(plainSocket, name, port, true);
        } catch (IOException e) {
            throw new UJciException(UErrorCode.ER_CONNECTION, e);
        }

        SSLParameters params = sslSocket.getSSLParameters();
        String[] protocols = supportedProtocols(sslSocket);
        if (protocols.length > 0) {
            params.setProtocols(protocols);
        }
        params.setCipherSuites(allowedCipherSuites(sslSocket));
        SNIHostName serverName = sniHostName(name);
        if (serverName != null) {
            params.setServerNames(Collections.<SNIServerName>singletonList(serverName));
        }
        if (sslMode.verifiesHostname()) {
            params.setEndpointIdentificationAlgorithm("HTTPS");
        }
        sslSocket.setSSLParameters(params);

        try {
            sslSocket.startHandshake();
        } catch (SSLHandshakeException e) {
            if (isCertificateFailure(e)) {
                throw new UJciException(
                        UErrorCode.ER_SSL_CERT_VERIFY, certificateFailureReason(e), e);
            }
            throw new UJciException(UErrorCode.ER_SSL_HANDSHAKE, handshakeFailureReason(e), e);
        } catch (IOException e) {
            throw new UJciException(UErrorCode.ER_SSL_HANDSHAKE, handshakeFailureReason(e), e);
        }

        return sslSocket;
    }

    /**
     * {@link #TLS_PROTOCOLS} narrowed to what this JRE has. TLS 1.3 arrived in 8u261, so asking for
     * it unconditionally would throw on an older JRE that TLS 1.2 serves perfectly well.
     */
    private static String[] supportedProtocols(SSLSocket socket) {
        List<String> available = Arrays.asList(socket.getSupportedProtocols());
        List<String> chosen = new ArrayList<String>(TLS_PROTOCOLS.length);
        for (String protocol : TLS_PROTOCOLS) {
            if (available.contains(protocol)) {
                chosen.add(protocol);
            }
        }

        return chosen.toArray(new String[chosen.size()]);
    }

    /** The suites the JRE has enabled that {@link #ALLOWED_CIPHER_SUITE} allows, in its order. */
    private static String[] allowedCipherSuites(SSLSocket socket) {
        List<String> chosen = new ArrayList<String>();
        for (String suite : socket.getEnabledCipherSuites()) {
            if (ALLOWED_CIPHER_SUITE.matcher(suite).matches()) {
                chosen.add(suite);
            }
        }

        return chosen.toArray(new String[chosen.size()]);
    }

    /**
     * The SNI value for the host, or null when none is sent. RFC 6066 forbids an IP literal, and a
     * broker addressed by IP has nothing to match it against anyway. A name SNIHostName refuses -
     * an underscore, a label over 63 characters - also goes without SNI, as the JRE's own default
     * does, so such a host connects as it did before the driver set SNI. verify-full still checks
     * the host against the certificate.
     */
    private static SNIHostName sniHostName(String host) {
        if (host == null || isIpLiteral(host)) {
            return null;
        }

        try {
            return new SNIHostName(host);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host == null || host.length() == 0) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return true; /* IPv6 literal */
        }

        return IPV4_LITERAL.matcher(host).matches();
    }

    /**
     * Whether the handshake failed because of the certificate rather than the connection.
     *
     * <p>Every case the caller must tell apart - chain building, expiry, a missing or mismatched
     * subject alternative name - reaches the client as a {@link CertificateException} somewhere in
     * the cause chain. The walk is bounded because a malformed cause chain must not hang a connect.
     */
    private static boolean isCertificateFailure(Throwable t) {
        for (int depth = 0; t != null && depth < 16; depth++) {
            if (t instanceof CertificateException) {
                return true;
            }
            t = t.getCause();
        }

        return false;
    }

    /**
     * Why a handshake failed for a reason other than the certificate: {@link
     * #BROKER_DID_NOT_START_TLS} when the connection ended before the broker answered, or what it
     * answered was not TLS ("Unrecognized SSL message" before 8u261, "Unsupported or unrecognized
     * SSL message" since); {@link #NO_COMMON_TLS} when the broker refused the versions or suites
     * offered; otherwise the JSSE message.
     */
    private static String handshakeFailureReason(IOException e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 16; depth++) {
            if (t instanceof EOFException) {
                return BROKER_DID_NOT_START_TLS;
            }
            t = t.getCause();
        }

        String message = e.getMessage();
        if (message != null && message.contains("nrecognized SSL message")) {
            return BROKER_DID_NOT_START_TLS;
        }
        if ("Received fatal alert: handshake_failure".equals(message)) {
            return NO_COMMON_TLS;
        }

        return (message != null) ? message : "unknown reason";
    }

    /**
     * Why the certificate was rejected: the innermost message of the cause chain, which names the
     * mismatching host or the missing certification path. Expiry is spelled out, as its innermost
     * message is only a date.
     */
    private static String certificateFailureReason(Throwable t) {
        Throwable inner = t;
        for (int depth = 0; inner.getCause() != null && depth < 16; depth++) {
            inner = inner.getCause();
        }

        if (inner instanceof CertificateExpiredException) {
            return "certificate has expired (" + inner.getMessage() + ")";
        }
        if (inner instanceof CertificateNotYetValidException) {
            return "certificate is not yet valid (" + inner.getMessage() + ")";
        }

        return inner.getMessage();
    }

    /** The factory's trust managers, each one wrapped in a {@link DateCheckingTrustManager}. */
    private static TrustManager[] withDateCheck(TrustManager[] managers, KeyStore trustStore)
            throws KeyStoreException {
        List<X509Certificate> trusted = new ArrayList<X509Certificate>();
        for (Enumeration<String> aliases = trustStore.aliases(); aliases.hasMoreElements(); ) {
            Certificate cert = trustStore.getCertificate(aliases.nextElement());
            if (cert instanceof X509Certificate) {
                trusted.add((X509Certificate) cert);
            }
        }

        TrustManager[] wrapped = new TrustManager[managers.length];
        for (int i = 0; i < managers.length; i++) {
            wrapped[i] =
                    (managers[i] instanceof X509ExtendedTrustManager)
                            ? new DateCheckingTrustManager(
                                    (X509ExtendedTrustManager) managers[i], trusted)
                            : managers[i];
        }

        return wrapped;
    }

    /**
     * Also rejects a chain the PKIX trust manager accepted when a certificate on it, or the trusted
     * certificate it ends at, is outside its validity period - the check OpenSSL makes for cci.
     * JSSE checks the dates of the certificates below the trust anchor only: an anchor, and a
     * certificate that is itself in the trust store, pass whatever their dates. So an expired CA in
     * sslca, or a pinned certificate sent with an expired issuer, would connect here and fail in
     * cci.
     */
    private static final class DateCheckingTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final List<X509Certificate> trusted;

        DateCheckingTrustManager(X509ExtendedTrustManager delegate, List<X509Certificate> trusted) {
            this.delegate = delegate;
            this.trusted = trusted;
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType, socket);
            checkDates(chain);
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType, engine);
            checkDates(chain);
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            checkDates(chain);
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }

        /**
         * Walks up from the leaf as OpenSSL builds the chain - through the certificates the broker
         * sent while each issued the one before, until one was issued by a trusted certificate -
         * and checks the dates of every certificate on the way and of that trusted one.
         */
        private void checkDates(X509Certificate[] chain) throws CertificateException {
            for (int i = 0; i < chain.length; i++) {
                chain[i].checkValidity();
                X509Certificate issuer = trustedIssuer(chain[i]);
                if (issuer != null) {
                    issuer.checkValidity();
                    return;
                }
                if (i + 1 < chain.length && !issued(chain[i + 1], chain[i])) {
                    return;
                }
            }
        }

        /**
         * The trusted certificate that issued cert, null if none. One within its dates is
         * preferred, as OpenSSL does when an old and a renewed CA certificate are both trusted.
         */
        private X509Certificate trustedIssuer(X509Certificate cert) {
            X509Certificate outOfDate = null;
            for (X509Certificate candidate : trusted) {
                if (!issued(candidate, cert)) {
                    continue;
                }
                try {
                    candidate.checkValidity();
                    return candidate;
                } catch (CertificateException e) {
                    if (outOfDate == null) {
                        outOfDate = candidate;
                    }
                }
            }

            return outOfDate;
        }

        private static boolean issued(X509Certificate issuer, X509Certificate cert) {
            if (!issuer.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) {
                return false;
            }
            try {
                cert.verify(issuer.getPublicKey());
                return true;
            } catch (GeneralSecurityException e) {
                return false;
            }
        }
    }
}
