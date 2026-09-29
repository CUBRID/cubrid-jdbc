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

/**
 * Immutable broker identity ({@code host:port}) used in topology, routing, and JDBC URL building.
 */
public final class Endpoint {
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final int HASH_MULTIPLIER = 31;
    private static final String PORT_RANGE_TEXT = MIN_PORT + "-" + MAX_PORT;
    private final String host;
    private final int port;

    public Endpoint(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static Endpoint parse(String hostPort) throws SQLException {
        if (hostPort == null || hostPort.trim().length() == 0) {
            throw LbExceptions.optionInvalid("Endpoint is empty");
        }

        String trimmed = hostPort.trim();

        int colonIdx = trimmed.lastIndexOf(':');
        if (colonIdx <= 0 || colonIdx == trimmed.length() - 1) {
            throw LbExceptions.optionInvalid(
                    "Invalid endpoint format (expected host:port): '" + trimmed + "'");
        }

        String host = trimmed.substring(0, colonIdx);

        String portStr = trimmed.substring(colonIdx + 1);

        try {
            int port = Integer.parseInt(portStr);
            if (port < MIN_PORT || port > MAX_PORT) {
                throw LbExceptions.optionInvalid(
                        "Port out of range (" + PORT_RANGE_TEXT + "): '" + trimmed + "'");
            }

            return new Endpoint(host, port);
        } catch (NumberFormatException e) {
            throw LbExceptions.optionInvalid("Invalid port number: '" + trimmed + "'");
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getId() {
        return host + ":" + port;
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Endpoint that = (Endpoint) o;

        return port == that.port && host.equals(that.host);
    }

    public int hashCode() {
        return HASH_MULTIPLIER * host.hashCode() + port;
    }

    public String toString() {
        return host + ":" + port;
    }
}
