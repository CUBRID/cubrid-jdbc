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

package cubrid.jdbc.lb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.KitServer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Rule 1 from core, over every cell: core talks only to the master, so a fault on another node must
 * not change what reads return. A read whose cursor is on the failing node may fail while the
 * application reads it (spec 15.5.5); every read after it returns the same rows as before, and once
 * one of them has found the node failed, it is not dialed again.
 *
 * <p>Cells that fail today are listed in {@link #KNOWN} with the review item; such a cell passes
 * while it fails, and fails once it is fixed, so the fix removes it from the list.
 */
@RunWith(Parameterized.class)
public class FailureMatrixTest {
    enum Seat {
        SLAVE_READ("readWeight=slave:1"),
        MASTER_READ("readWeight=master:1");

        final String options;

        Seat(final String options) {
            this.options = options;
        }
    }

    enum Kind {
        STATEMENT,
        PREPARED
    }

    enum When {
        BEFORE_READ,
        DURING_FETCH
    }

    static final KitServer.Fault[] FAULTS = {
        KitServer.Fault.REFUSE, KitServer.Fault.LOST_ON_REQUEST, KitServer.Fault.DIAL_TIMEOUT
    };

    /** Cells that fail today, with the review item that describes the defect. */
    static final Map<String, String> KNOWN = new HashMap<String, String>();

    @Parameters(name = "{0}/{1}/{2}/{3}")
    public static Collection<Object[]> cells() {
        final List<Object[]> cells = new ArrayList<Object[]>();
        for (Seat seat : Seat.values()) {
            for (Kind kind : Kind.values()) {
                for (KitServer.Fault fault : FAULTS) {
                    for (When when : When.values()) {
                        cells.add(new Object[] {seat, kind, fault, when});
                    }
                }
            }
        }
        return cells;
    }

    private final Seat seat;
    private final Kind kind;
    private final KitServer.Fault fault;
    private final When when;
    private final LbKit kit = new LbKit(2);
    private final ExecutorService timeouts = Executors.newSingleThreadExecutor();

    public FailureMatrixTest(
            final Seat seat, final Kind kind, final KitServer.Fault fault, final When when) {
        this.seat = seat;
        this.kind = kind;
        this.fault = fault;
        this.when = when;
    }

    @After
    public void tearDown() {
        kit.close();
        timeouts.shutdownNow();
    }

    private String cell() {
        return seat + "/" + kind + "/" + fault + "/" + when;
    }

    @Test
    public void aFaultOnAnotherNodeDoesNotChangeReads() throws Exception {
        final String issue = KNOWN.get(cell());
        try {
            check();
        } catch (AssertionError failed) {
            if (issue != null) {
                return; // still the known defect
            }
            throw failed;
        } catch (SQLException failed) {
            if (issue != null) {
                return;
            }
            throw failed;
        }
        if (issue != null) {
            fail(cell() + " (" + issue + ") no longer fails: remove it from KNOWN");
        }
    }

    private void check() throws Exception {
        final Connection conn = kit.open(seat.options);
        conn.setNetworkTimeout(timeouts, 1000);
        final List<Integer> expected = read(conn);

        if (when == When.BEFORE_READ) {
            failNode2();
        } else {
            final ResultSet rs = query(conn);
            assertTrue(rs.next());
            failNode2();
            try {
                drain(rs);
            } catch (SQLException lost) {
                assertTrue(
                        "only a cursor on the failing node may fail: " + lost,
                        seat == Seat.SLAVE_READ);
            }
        }

        assertEquals(expected, read(conn));
        final int dials = kit.server.dials(kit.ro(2));
        assertEquals(expected, read(conn));
        assertEquals("the failed node is dialed again", dials, kit.server.dials(kit.ro(2)));
        assertEquals(Collections.<String>emptyList(), kit.legLeaks());
    }

    private void failNode2() {
        kit.server.fault(kit.rw(2), fault);
        kit.server.fault(kit.ro(2), fault);
    }

    private ResultSet query(final Connection conn) throws SQLException {
        return kind == Kind.STATEMENT
                ? conn.createStatement().executeQuery("SELECT a FROM t")
                : conn.prepareStatement("SELECT a FROM t").executeQuery();
    }

    private List<Integer> read(final Connection conn) throws SQLException {
        return drain(query(conn));
    }

    private static List<Integer> drain(final ResultSet rs) throws SQLException {
        final List<Integer> rows = new ArrayList<Integer>();
        while (rs.next()) {
            rows.add(rs.getInt(1));
        }
        return rows;
    }
}
