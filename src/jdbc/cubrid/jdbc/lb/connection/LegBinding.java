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

package cubrid.jdbc.lb.connection;

import cubrid.jdbc.lb.config.Endpoint;

/**
 * Where a session's legs are. Immutable: the session's {@link SessionPhysicalConnManager} publishes
 * a new one after each change, so any thread, another connection included, reads one consistent
 * answer without taking the lock that session holds while it dials.
 */
public final class LegBinding {
    /** Before the session binds and after it closes. */
    public static final LegBinding UNBOUND = new LegBinding(null, null, false);

    private final Endpoint rwEndpoint;
    private final Endpoint readEndpoint;
    private final boolean readOnRwFallback;

    LegBinding(
            final Endpoint rwEndpoint,
            final Endpoint readEndpoint,
            final boolean readOnRwFallback) {
        this.rwEndpoint = rwEndpoint;
        this.readEndpoint = readEndpoint;
        this.readOnRwFallback = readOnRwFallback;
    }

    /** The write leg's endpoint, or null when unbound. */
    public Endpoint getRwEndpoint() {
        return rwEndpoint;
    }

    /**
     * The read leg's endpoint, or null when unbound: the write endpoint itself for a master-role or
     * RW-only session. During a fallback, the read endpoint the session left, which failback climbs
     * back from.
     */
    public Endpoint getReadEndpoint() {
        return readEndpoint;
    }

    /** Whether every read broker failed, so reads run on the write leg's connection (roOnRw). */
    public boolean isReadOnRwFallback() {
        return readOnRwFallback;
    }

    /**
     * Whether reads run on the write leg's connection: by design (master role, RW-only) or after a
     * fallback.
     */
    public boolean readSharesRwConnection() {
        return readOnRwFallback
                || (readEndpoint != null
                        && rwEndpoint != null
                        && readEndpoint.getId().equals(rwEndpoint.getId()));
    }

    /** Where reads run: the write endpoint during a fallback, the read endpoint otherwise. */
    public Endpoint getReadRunsOn() {
        return readOnRwFallback ? rwEndpoint : readEndpoint;
    }
}
