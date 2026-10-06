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

/**
 * Read-distribution role of a topology node, the unit over which {@code readWeight} is applied:
 *
 * <ul>
 *   <li>{@link #MASTER} — the first host-list node. Reads reuse the node's RW physical connection
 *       (roOnRw), so its read broker group is {@link ReadBrokerKind#RW}.
 *   <li>{@link #SLAVE} — the remaining host-list nodes. Reads use the node's RO broker ({@link
 *       ReadBrokerKind#RO}).
 *   <li>{@link #REPLICA} — the {@code ;replica=} nodes. Reads use the node's SO broker ({@link
 *       ReadBrokerKind#REPL}).
 * </ul>
 *
 * <p>Writes are never distributed: they always go to the master RW broker. This enum only maps a
 * role to its read broker pool; the physical binding is done in the connection layer.
 */
public enum NodeRole {
    MASTER("master", ReadBrokerKind.RW, true),
    SLAVE("slave", ReadBrokerKind.RO, false),
    REPLICA("replica", ReadBrokerKind.REPL, false);

    private final String label;
    private final ReadBrokerKind readGroup;
    private final boolean reusesRwForRead;

    NodeRole(final String label, final ReadBrokerKind readGroup, final boolean reusesRwForRead) {
        this.label = label;
        this.readGroup = readGroup;
        this.reusesRwForRead = reusesRwForRead;
    }

    /**
     * Lowercase spec name used in {@code readWeight} ({@code master}/{@code slave}/{@code
     * replica}).
     *
     * @return the role label
     */
    public String label() {
        return label;
    }

    /**
     * Role for a {@code readWeight} label (case-insensitive), or {@code null} if unrecognized.
     *
     * @param label the role label to look up; may be {@code null}
     * @return the matching role, or {@code null} if unrecognized
     */
    public static NodeRole fromLabel(final String label) {
        if (label == null) {
            return null;
        }

        final String trimmed = label.trim();
        final NodeRole[] roles = values();
        for (int i = 0; i < roles.length; i++) {
            if (roles[i].label.equalsIgnoreCase(trimmed)) {
                return roles[i];
            }
        }

        return null;
    }

    /**
     * Broker pool a read of this role targets.
     *
     * @return the read weight group for this role
     */
    public ReadBrokerKind readGroup() {
        return readGroup;
    }

    /**
     * Whether reads of this role reuse the master RW connection instead of opening a separate RO/SO
     * one. True for {@link #MASTER} only (roOnRw).
     *
     * @return whether reads of this role reuse the RW connection
     */
    public boolean reusesRwForRead() {
        return reusesRwForRead;
    }
}
