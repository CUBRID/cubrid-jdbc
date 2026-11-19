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

package cubrid.jdbc.lb.route;

/** The line of {@link Router#decide} that chose a statement's leg. */
public enum RouteRule {
    /** A transaction is active: every statement stays on RW, a hinted one too. */
    TRANSACTION,
    /** A target hint, outside a transaction. */
    HINT,
    /** A {@code TO_RO} string that changes the session runs on RW. */
    SESSION_SET_IN_HINTED_STRING,
    /** A session change the read leg does not have keeps reads on RW. */
    READ_PIN,
    /** An updatable result set writes through its statement. */
    UPDATABLE_RESULT,
    /** The READ/WRITE classification. */
    CLASSIFICATION,
    /** A read that reports on the statement before it runs on that statement's leg. */
    PREVIOUS_STATEMENT,
    /** A read names a variable the session created on RW. */
    SESSION_VARIABLE,
    /** Core left an autocommit transaction open on RW; the next statement there ends it. */
    RW_TRANSACTION_OPEN
}
