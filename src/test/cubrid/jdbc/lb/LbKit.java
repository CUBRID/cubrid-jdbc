/*
 * Copyright (C) 2008 Search Solution Corporation.
 * Copyright (c) 2016 CUBRID Corporation.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * - Neither the name of the <ORGANIZATION> nor the names of its contributors may be used to endorse
 * or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package cubrid.jdbc.lb;

import cubrid.jdbc.driver.CUBRIDConnection;
import cubrid.jdbc.driver.KitConnection;
import cubrid.jdbc.driver.KitServer;
import cubrid.jdbc.jci.UUnreachableHostList;
import cubrid.jdbc.lb.config.LoadBalanceSettings;
import cubrid.jdbc.lb.config.LoadBalanceUrlParser;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Opens LB connections on a {@link KitServer} cluster the way the driver does ({@code
 * openFromSettings}), and checks what must never happen whatever the outcome of a scenario.
 *
 * <p>Node {@code i} has an RW broker on port 33000 and an RO broker on 33002; node 1 is the master.
 * Host names are new for every kit, so JVM-wide LB state (unreachable list, shared selector,
 * backoff) never carries over from another test.
 */
public final class LbKit implements AutoCloseable {
    private static final AtomicInteger KITS = new AtomicInteger();
    private static final int RW = 33000;
    private static final int RO = 33002;

    public final KitServer server = new KitServer();
    private final String prefix = "kit" + KITS.incrementAndGet() + "node";
    private final List<String> hosts = new ArrayList<String>();
    private final List<LoadBalanceConnection> opened = new ArrayList<LoadBalanceConnection>();
    private final List<String> log = new ArrayList<String>();
    private final Logger lbLogger = Logger.getLogger("cubrid.jdbc.lb");
    private final Level previousLevel = lbLogger.getLevel();
    private final Handler capture =
            new Handler() {
                @Override
                public void publish(final LogRecord r) {
                    synchronized (log) {
                        log.add(String.valueOf(r.getMessage()));
                    }
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            };

    public LbKit(final int nodes) {
        for (int i = 1; i <= nodes; i++) {
            server.node(prefix + i, RW).node(prefix + i, RO);
            hosts.add(prefix + i + ":" + RW + ":" + RO);
        }
        lbLogger.setLevel(Level.ALL);
        lbLogger.addHandler(capture);
    }

    public String rw(final int node) {
        return prefix + node + ":" + RW;
    }

    public String ro(final int node) {
        return prefix + node + ":" + RO;
    }

    /**
     * Opens an LB connection with URL {@code options} and internal tuning given as name, value
     * pairs (see {@link LbTestSettings#with}).
     */
    public LoadBalanceConnection open(final String options, final Object... tuning)
            throws SQLException {
        final String url =
                "jdbc:cubrid:loadbalance://"
                        + String.join(",", hosts)
                        + "/kitdb:::"
                        + (options.isEmpty() ? "" : "?" + options);
        final Properties info = new Properties();
        LoadBalanceSettings settings =
                LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url), info);
        for (int i = 0; i < tuning.length; i += 2) {
            settings = LbTestSettings.with(settings, (String) tuning[i], tuning[i + 1]);
        }
        final LoadBalanceConnection c =
                (LoadBalanceConnection)
                        LoadBalanceConnection.openFromSettings(url, info, settings, server);
        opened.add(c);
        return c;
    }

    /**
     * A broker that failed is back, and core's health check has seen it: it answers again and
     * leaves the unreachable list, as on a running cluster a few seconds after it restarts.
     */
    public void brokerBack(final String endpointId) {
        server.fault(endpointId, KitServer.Fault.UP);
        UUnreachableHostList.getInstance().remove(endpointId);
    }

    /** LB log messages so far. */
    public List<String> log() {
        synchronized (log) {
            return new ArrayList<String>(log);
        }
    }

    /**
     * I1 — leg accounting: no physical connection stays open for a closed LB connection, and no
     * open LB connection holds more than two.
     */
    public List<String> legLeaks() throws SQLException {
        final Map<CUBRIDConnection, List<KitConnection>> byOwner =
                new IdentityHashMap<CUBRIDConnection, List<KitConnection>>();
        final List<String> leaks = new ArrayList<String>();
        for (KitConnection leg : server.live()) {
            final CUBRIDConnection owner = leg.kitU().getCUBRIDConnection();
            if (owner == null || owner == leg) {
                leaks.add(leg + " is open and no LB connection owns it");
                continue;
            }
            if (!byOwner.containsKey(owner)) {
                byOwner.put(owner, new ArrayList<KitConnection>());
            }
            byOwner.get(owner).add(leg);
        }
        for (Map.Entry<CUBRIDConnection, List<KitConnection>> e : byOwner.entrySet()) {
            if (e.getKey().isClosed()) {
                leaks.add(e.getValue() + " still open after their LB connection closed");
            } else if (e.getValue().size() > 2) {
                leaks.add(e.getValue() + " open for one LB connection, more than two legs");
            }
        }
        return leaks;
    }

    /**
     * I3 — no unrequested execution: a statement that changes data or calls a routine runs no more
     * often than the application sent it. Reads may run again after a failure, and session SETs and
     * a user switch ({@code login} on the class db_user) are replayed by design, so they are not
     * counted.
     *
     * @param sent the statements the application sent, one entry per execution
     */
    public List<String> unrequestedExecutions(final Collection<String> sent) {
        final Map<String, Integer> ran = new LinkedHashMap<String, Integer>();
        for (KitServer.Request r : server.requests()) {
            if (r.code != 0) {
                continue;
            }
            final String text = executedText(r.what);
            if (text == null || isReplayable(text)) {
                continue;
            }
            final Integer n = ran.get(text);
            ran.put(text, n == null ? 1 : n + 1);
        }
        final List<String> extra = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : ran.entrySet()) {
            int asked = 0;
            for (String s : sent) {
                if (e.getKey().equals(s) || e.getKey().startsWith(s + " [")) {
                    asked++;
                }
            }
            if (e.getValue() > asked) {
                extra.add(e.getKey() + " ran " + e.getValue() + " times, sent " + asked);
            }
        }
        return extra;
    }

    /** How many times {@code endpointId} ran {@code sql} without an error. */
    public int runs(final String endpointId, final String sql) {
        int n = 0;
        for (KitServer.Request r : server.requests()) {
            if (r.code == 0 && r.endpoint.equals(endpointId) && sql.equals(executedText(r.what))) {
                n++;
            }
        }
        return n;
    }

    private static String executedText(final String what) {
        for (String verb : new String[] {"EXECUTE ", "CALL ", "BATCH "}) {
            if (what.startsWith(verb)) {
                return what.substring(verb.length());
            }
        }
        return null;
    }

    private static boolean isReplayable(final String text) {
        final String t = text.trim().toUpperCase(java.util.Locale.ROOT);
        return t.startsWith("SELECT")
                || t.startsWith("SET ")
                || (t.startsWith("CALL ") && t.contains("LOGIN") && t.contains("CLASS DB_USER"));
    }

    @Override
    public void close() {
        for (LoadBalanceConnection c : opened) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // a test may leave a connection already closed
            } catch (RuntimeException ignored) {
                // nor may a broken scenario keep the next test from starting clean
            }
        }
        lbLogger.removeHandler(capture);
        lbLogger.setLevel(previousLevel);
        for (int i = 1; i <= hosts.size(); i++) {
            UUnreachableHostList.getInstance().remove(rw(i));
            UUnreachableHostList.getInstance().remove(ro(i));
        }
    }
}
