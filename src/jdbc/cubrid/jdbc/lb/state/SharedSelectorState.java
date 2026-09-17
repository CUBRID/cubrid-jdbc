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

package cubrid.jdbc.lb.state;

import java.util.HashMap;
import java.util.Map;

/**
 * DataSource-scoped (pool-shared) mutable state used by {@link
 * cubrid.jdbc.lb.route.RoleReadSelector} when picking read brokers.
 *
 * <p>This is <strong>not</strong> per-connection routing state. Transaction boundaries live in
 * {@link SessionRoutingState} on each {@link cubrid.jdbc.lb.LoadBalanceConnection}. {@code
 * SharedSelectorState} only carries algorithm counters, so that new logical connections from the
 * same DataSource neither all start at the same round-robin position nor re-derive the pool's read
 * distribution from scratch.
 *
 * <p>The DataSource creates one instance, and every {@code LoadBalanceConnection} it makes gets the
 * same reference through {@link
 * cubrid.jdbc.lb.LoadBalanceConnection#setSharedSelectorState(SharedSelectorState)}. On session
 * binding, {@link cubrid.jdbc.lb.route.RoleReadSelector} reads and updates the counters here.
 *
 * <p>Contents:
 *
 * <ul>
 *   <li><b>Group round-robin indices</b> — a per-group sequence ({@link
 *       #nextGroupRoundRobinIndex(String, int)}) so each role group (slave/replica) advances
 *       independently for even round-robin inside the group.
 *   <li><b>Live home counts</b> — how many bound sessions currently hold each role as their
 *       weighted read home. This is what role selection balances against ({@link
 *       #acquireIndexByPopulation(String[], int[])} / {@link #releaseHome(String)}), so the pool's
 *       population — not just the stream of picks — matches readWeight even as connections are
 *       replaced.
 * </ul>
 *
 * <p>A smooth-weighted-round-robin (deficit) selector lived here until 2026-08-11. It balanced the
 * stream of picks, not the population, and drifted toward the master whenever pooled connections
 * were replaced; see {@link #acquireIndexByPopulation(String[], int[])}.
 *
 * <p>Thread-safety: all mutating methods are {@code synchronized}. Safe to share across threads
 * that create connections from one DataSource.
 *
 * <p>If {@code null} is passed to selection policies, round-robin falls back to the first
 * candidate.
 */
public class SharedSelectorState {
    /**
     * Upper bound the round-robin counters wrap at. Keeps them in {@code [0, RR_BOUND)} so an index
     * is never negative (avoids the {@code Math.abs(MIN_VALUE)} trap). {@code RR_BOUND} need not be
     * a multiple of the candidate count, so one index may repeat once across a wrap boundary — a
     * negligible fairness effect.
     */
    private static final int RR_BOUND = 1 << 30;

    private final Map<String, Integer> groupRoundRobinIndices = new HashMap<String, Integer>();
    private final Map<String, Integer> liveHomeCounts = new HashMap<String, Integer>();

    /**
     * Next round-robin index within a named group (a role's node set), kept per {@code groupKey} so
     * slave and replica groups advance separately. The method is {@code synchronized}, so a plain
     * {@code Integer} counter is enough.
     *
     * @param groupKey the group whose round-robin cursor advances
     * @param candidateCount the number of candidates in the group
     * @return the index to use, or {@code 0} when there are no candidates
     */
    public synchronized int nextGroupRoundRobinIndex(
            final String groupKey, final int candidateCount) {
        if (candidateCount <= 0) {
            return 0;
        }

        final Integer stored = groupRoundRobinIndices.get(groupKey);
        final int current = stored != null ? stored.intValue() : 0;
        groupRoundRobinIndices.put(groupKey, Integer.valueOf((current + 1) % RR_BOUND));

        return current % candidateCount;
    }

    /**
     * Picks the key whose LIVE share is furthest below its weighted target, and counts the new
     * holder against it — one call per bound session, released by {@link #releaseHome(String)} when
     * that session closes.
     *
     * <p>Balancing the population, not the stream: readWeight is a target for the pool's live
     * connections ("40% of them read from the slave"), while a deficit round only balances the
     * order of picks. The two agree only if no pooled connection is ever replaced, and replacement
     * is biased - a master-home session reads on the RW connection, so a read-broker outage never
     * costs it its place, and the sessions the pool discards are the slave/replica-home ones.
     * Balancing against the live count makes that churn self-correcting: a replacement goes to
     * whichever role is short.
     *
     * <p>Selection is exact integer math on {@code w_i * (N + 1) - W * live_i} (the shortfall of
     * key {@code i} if it took the next slot, scaled by the total weight {@code W}); ties take the
     * lowest index, which the next call breaks anyway because the winner's live count went up.
     *
     * @param keys the candidate keys
     * @param weights the weight of each key, parallel to {@code keys}
     * @return the index of the selected key, whose live count is now incremented
     */
    public synchronized int acquireIndexByPopulation(final String[] keys, final int[] weights) {
        int totalWeight = 0;
        int liveTotal = 0;
        for (int i = 0; i < keys.length; i++) {
            totalWeight += weights[i];
            liveTotal += liveHomeCount(keys[i]);
        }

        int best = 0;
        long bestShortfall = Long.MIN_VALUE;
        for (int i = 0; i < keys.length; i++) {
            final long shortfall =
                    (long) weights[i] * (liveTotal + 1)
                            - (long) totalWeight * liveHomeCount(keys[i]);
            if (shortfall > bestShortfall) {
                bestShortfall = shortfall;
                best = i;
            }
        }

        liveHomeCounts.put(keys[best], Integer.valueOf(liveHomeCount(keys[best]) + 1));

        return best;
    }

    /**
     * Gives back the slot taken by {@link #acquireIndexByPopulation}. Must be called when the
     * holding session closes or rebinds, or the pool looks fuller than it is and the role stops
     * being selected. Clamped at zero, so an unmatched release is a no-op instead of a negative
     * count that would over-select the role forever.
     *
     * @param key the home key to release
     */
    public synchronized void releaseHome(final String key) {
        final int current = liveHomeCount(key);
        if (current <= 1) {
            liveHomeCounts.remove(key);
        } else {
            liveHomeCounts.put(key, Integer.valueOf(current - 1));
        }
    }

    /**
     * Sessions currently holding {@code key} as their weighted home; 0 when unknown.
     *
     * @param key home-slot key (endpoint id)
     * @return the number of sessions holding that home, or {@code 0}
     */
    public synchronized int liveHomeCount(final String key) {
        final Integer current = liveHomeCounts.get(key);

        return current != null ? current.intValue() : 0;
    }

    public synchronized Map<String, Integer> getLiveHomeCounts() {
        return new HashMap<String, Integer>(liveHomeCounts);
    }

    public synchronized void resetAll() {
        groupRoundRobinIndices.clear();
        liveHomeCounts.clear();
    }
}
