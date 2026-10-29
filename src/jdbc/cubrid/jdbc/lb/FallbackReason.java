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

package cubrid.jdbc.lb;

/**
 * Vocabulary for the {@code fallbackReason} field emitted in routing/metrics events. Kept in one
 * place so a comparison typo cannot silently break routing.
 */
public final class FallbackReason {

    private FallbackReason() {}

    /** No fallback: the request bound to its intended endpoint. */
    public static final String NONE = "NONE";

    /**
     * Read was pinned to RW because a transaction is active and no master read endpoint applies.
     */
    static final String TX_ACTIVE_RW_LOCK = "TX_ACTIVE_RW_LOCK";

    /** Physical binding was recovered after a runtime failover. */
    static final String RUNTIME_FAILOVER = "RUNTIME_FAILOVER";

    /** RO physical connection was failed over. */
    public static final String RO_PHYSICAL = "RO_PHYSICAL_FAILOVER";

    /** The read-only broker refused a READ as a write (-581), so it ran again on RW. */
    static final String RO_WRITE_REJECTED = "RO_WRITE_REJECTED";

    /** A read touched LOB content, whose files HA does not replicate, and ran again on RW. */
    static final String RO_LOB_UNAVAILABLE = "RO_LOB_UNAVAILABLE";

    /** A session SET the read leg cannot replay keeps the session's reads on RW. */
    static final String SESSION_SET_PIN = "SESSION_SET_PIN";

    /** A read named a variable an {@code INTO/TO} clause created on the write leg. */
    static final String SESSION_VARIABLE = "SESSION_VARIABLE";
}
