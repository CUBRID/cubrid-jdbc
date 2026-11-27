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

import cubrid.jdbc.jci.KitUConnection;
import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.lb.connection.JdbcConnectionFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A fake cluster for LB tests: brokers by {@code host:port}, each up or failing in one of the ways
 * core reports, and a record of every request a physical connection sent. Physical connections are
 * core {@link CUBRIDConnection}s on a {@link KitUConnection}, so only the wire is fake.
 *
 * <p>Results do not depend on the node, as with replicated data: a query returns rows derived from
 * its text alone, so a read gives the same rows on any node.
 */
public final class KitServer implements JdbcConnectionFactory {

    /** How a broker fails, with the error core reports for it. */
    public enum Fault {
        UP,
        /** Broker gone: dials and reconnects get ER_CONNECTION, open sockets are reset. */
        REFUSE,
        /** Host silent: dials get ER_TIMEOUT, requests end in a network timeout that closes. */
        DIAL_TIMEOUT,
        /** One request loses the socket (ER_COMMUNICATION); the reconnect then succeeds. */
        LOST_ON_REQUEST,
        /** A fetch times out and core closes the connection (ER_NETWORK_TIMEOUT). */
        NET_TIMEOUT_ON_FETCH
    }

    /** One request a physical connection sent. */
    public static final class Request {
        public final String endpoint;
        public final int physical;
        public final String what;
        public final int code;

        Request(final String endpoint, final int physical, final String what, final int code) {
            this.endpoint = endpoint;
            this.physical = physical;
            this.what = what;
            this.code = code;
        }

        @Override
        public String toString() {
            return endpoint + "#" + physical + " " + what + (code == 0 ? "" : " -> " + code);
        }
    }

    /** One broker endpoint. */
    public final class Node implements KitUConnection.Node {
        private final String host;
        private final int port;
        private volatile Fault fault = Fault.UP;
        private int dials;

        Node(final String host, final int port) {
            this.host = host;
            this.port = port;
        }

        public String id() {
            return host + ":" + port;
        }

        @Override
        public String host() {
            return host;
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public int request(final KitUConnection c, final String what) {
            final Hold held = holds.remove(what);
            if (held != null) {
                held.reached.countDown();
                try {
                    held.release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            final int code = answer(c, what);
            final KitConnection owner = physicalOf(c);
            synchronized (requests) {
                requests.add(new Request(id(), owner == null ? 0 : owner.serial(), what, code));
            }
            return code;
        }

        private int answer(final KitUConnection c, final String what) {
            if ("CLOSE".equals(what)) {
                return 0;
            }
            if ("RECONNECT".equals(what)) {
                return fault == Fault.REFUSE
                        ? UErrorCode.ER_CONNECTION
                        : fault == Fault.DIAL_TIMEOUT ? UErrorCode.ER_TIMEOUT : 0;
            }
            switch (fault) {
                case REFUSE:
                    c.dropSocket();
                    return UErrorCode.ER_COMMUNICATION;
                case LOST_ON_REQUEST:
                    fault = Fault.UP; // once: the broker answers the reconnect
                    c.dropSocket();
                    return UErrorCode.ER_COMMUNICATION;
                case DIAL_TIMEOUT:
                    c.lose(); // a request to a silent host ends in core's network timeout
                    return UErrorCode.ER_NETWORK_TIMEOUT;
                case NET_TIMEOUT_ON_FETCH:
                    if (what.startsWith("FETCH")) {
                        c.lose();
                        return UErrorCode.ER_NETWORK_TIMEOUT;
                    }
                    return 0;
                default:
                    final Integer failed = failing.get(what);
                    return failed == null ? 0 : failed.intValue();
            }
        }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<String, Node>();
    private final Map<String, Integer> failing = new ConcurrentHashMap<String, Integer>();
    private final Map<String, Hold> holds = new ConcurrentHashMap<String, Hold>();
    private final List<Request> requests = new ArrayList<Request>();
    private final List<KitConnection> opened = new ArrayList<KitConnection>();
    private volatile long dialTimeoutMillis;

    /** Adds a broker. */
    public synchronized KitServer node(final String host, final int port) {
        nodes.put(host + ":" + port, new Node(host, port));
        return this;
    }

    public synchronized Node node(final String endpointId) {
        final Node n = nodes.get(endpointId);
        if (n == null) {
            throw new IllegalArgumentException("no kit broker " + endpointId);
        }
        return n;
    }

    public void fault(final String endpointId, final Fault fault) {
        node(endpointId).fault = fault;
    }

    /**
     * Executing {@code sql} fails on every node with the server error {@code code}, as a statement
     * error does: the connection stays.
     */
    public void failStatement(final String sql, final int code) {
        failRequest("EXECUTE " + sql, code);
    }

    /**
     * The request {@code what}, as {@link Request#what} records it, fails on every node with {@code
     * code}; the connection stays.
     */
    public void failRequest(final String what, final int code) {
        failing.put(what, Integer.valueOf(code));
    }

    /** A request the broker holds until the test releases it. */
    public static final class Hold {
        /** Counted down when the request arrives. */
        public final CountDownLatch reached = new CountDownLatch(1);
        /** Count it down to let the broker answer, as the node stands then. */
        public final CountDownLatch release = new CountDownLatch(1);
    }

    /** The next request {@code what}, on any node, waits until the hold is released. */
    public Hold hold(final String what) {
        final Hold h = new Hold();
        holds.put(what, h);
        return h;
    }

    /** How long a dial to a silent host blocks before ER_TIMEOUT. */
    public void dialTimeoutMillis(final long millis) {
        dialTimeoutMillis = millis;
    }

    public synchronized int dials(final String endpointId) {
        return node(endpointId).dials;
    }

    public List<Request> requests() {
        synchronized (requests) {
            return new ArrayList<Request>(requests);
        }
    }

    public synchronized List<KitConnection> opened() {
        return new ArrayList<KitConnection>(opened);
    }

    /** Physical connections whose socket is still open. */
    public synchronized List<KitConnection> live() {
        final List<KitConnection> live = new ArrayList<KitConnection>();
        for (KitConnection c : opened) {
            if (!c.kitU().isClosed()) {
                live.add(c);
            }
        }
        return live;
    }

    private synchronized KitConnection physicalOf(final KitUConnection u) {
        for (KitConnection c : opened) {
            if (c.kitU() == u) {
                return c;
            }
        }
        return null;
    }

    /** Opens a physical connection for a classic URL {@code jdbc:cubrid:host:port:db:user:pw:}. */
    @Override
    public Connection getConnection(final String url, final Properties info) throws SQLException {
        final String[] p = url.substring("jdbc:cubrid:".length()).split(":", 5);
        final Node n;
        final int serial;
        synchronized (this) {
            n = node(p[0] + ":" + p[1]);
            n.dials++;
            serial = opened.size() + 1;
        }
        switch (n.fault) {
            case REFUSE:
                throw new CUBRIDException(KitUConnection.error(UErrorCode.ER_CONNECTION));
            case DIAL_TIMEOUT:
                sleep(dialTimeoutMillis);
                throw new CUBRIDException(KitUConnection.error(UErrorCode.ER_TIMEOUT));
            default:
                break;
        }
        final KitUConnection u = new KitUConnection(n);
        final KitConnection c = new KitConnection(u, url, p.length > 3 ? p[3] : "", serial);
        synchronized (this) {
            opened.add(c);
        }
        return c;
    }

    private static void sleep(final long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
