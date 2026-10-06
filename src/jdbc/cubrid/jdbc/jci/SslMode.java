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

package cubrid.jdbc.jci;

/**
 * TLS mode of a connection, from the {@code sslmode} connection property. The names and the
 * progression match cci so that a CUBRID installation can use one value, and one CA file, for both
 * drivers.
 *
 * <p>{@code required} is what {@code useSSL=true} has always done: encrypt, verify nothing. The
 * verifying modes are opt-in, which is what keeps this change backward compatible.
 */
public enum SslMode {
    DISABLED("disabled"),
    REQUIRED("required"),
    VERIFY_CA("verify-ca"),
    VERIFY_FULL("verify-full");

    private final String label;

    private SslMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * The mode written as {@code sslmode=...}, or null when the text names no mode. Callers turn
     * null into the error they report; this class does not know about error codes.
     */
    public static SslMode fromLabel(String label) {
        if (label == null) {
            return null;
        }

        String trimmed = label.trim();
        for (SslMode mode : values()) {
            if (mode.label.equalsIgnoreCase(trimmed)) {
                return mode;
            }
        }

        return null;
    }

    /** The mode a connection has when only the older {@code useSSL} property was given. */
    public static SslMode fromUseSSL(boolean useSSL) {
        return useSSL ? REQUIRED : DISABLED;
    }

    public boolean usesSsl() {
        return this != DISABLED;
    }

    /** Whether the server certificate chain is checked against a trust anchor. */
    public boolean verifiesCertificate() {
        return compareTo(VERIFY_CA) >= 0;
    }

    /** Whether the certificate must also name the host the client asked for. */
    public boolean verifiesHostname() {
        return this == VERIFY_FULL;
    }
}
