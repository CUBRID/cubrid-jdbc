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

package cubrid.jdbc.lb.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class RuntimeMetricsTest {

    @Test
    public void disabledByDefaultRecordsNothing() {
        RuntimeMetrics metrics = new RuntimeMetrics();

        assertFalse(metrics.isEnabled());

        metrics.recordEvent("e1");
        metrics.incRoSelectionCount();
        metrics.incUnclassifiedHit("ro1");

        assertTrue(metrics.getEvents().isEmpty());
        assertEquals(0, metrics.getRoSelectionCount());
        assertEquals(0, metrics.getEndpointCount("ro1"));
    }

    @Test
    public void enabledRecordsEventsAndCounters() {
        RuntimeMetrics metrics = new RuntimeMetrics();
        metrics.setEnabled(true);

        metrics.recordEvent("e1");
        metrics.incRoSelectionCount();
        metrics.incUnclassifiedHit("ro1");
        metrics.incUnclassifiedHit("ro1");

        assertEquals(1, metrics.getEvents().size());
        assertEquals("e1", metrics.getEvents().get(0));
        assertEquals(1, metrics.getRoSelectionCount());
        assertEquals(2, metrics.getEndpointCount("ro1"));
    }

    @Test
    public void eventLogIsBoundedAndEvictsOldest() {
        RuntimeMetrics metrics = new RuntimeMetrics(3);
        metrics.setEnabled(true);

        metrics.recordEvent("a");
        metrics.recordEvent("b");
        metrics.recordEvent("c");
        metrics.recordEvent("d"); // overflow: "a" evicted

        List<String> events = metrics.getEvents();
        assertEquals(3, events.size());
        // oldest-first order is preserved; "a" is gone, "d" is newest.
        assertEquals("b", events.get(0));
        assertEquals("d", events.get(2));
    }

    @Test
    public void disablingStopsFurtherRecordingButKeepsExisting() {
        RuntimeMetrics metrics = new RuntimeMetrics();
        metrics.setEnabled(true);
        metrics.recordEvent("kept");

        metrics.setEnabled(false);
        metrics.recordEvent("dropped");

        List<String> events = metrics.getEvents();
        assertEquals(1, events.size());
        assertEquals("kept", events.get(0));
    }
}
