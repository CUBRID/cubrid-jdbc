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

package cubrid.jdbc.lb.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.sql.CUBRIDOID;
import cubrid.sql.CUBRIDTimestamptz;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.junit.Test;

public class ParameterBinderTest {

    @Test
    public void assertRecordOrderAndReplay() throws Exception {
        ParameterBinder binder = new ParameterBinder();
        binder.recordSetInt(1, 12);
        binder.recordSetString(2, "abc");
        binder.recordSetNull(3, Types.INTEGER);

        RecordingPreparedStatementHandler handler = new RecordingPreparedStatementHandler();
        PreparedStatement target = handler.createProxy();
        binder.replay(target);

        assertEquals(3, handler.calls.size());
        assertEquals("setInt:2:1", handler.calls.get(0));
        assertEquals("setString:2:2", handler.calls.get(1));
        assertEquals("setNull:2:3", handler.calls.get(2));
    }

    @Test
    public void assertClearRemovesRecordedOperations() throws Exception {
        ParameterBinder binder = new ParameterBinder();
        binder.recordSetInt(1, 1);
        binder.recordSetString(2, "x");
        assertEquals(2, binder.size());
        binder.clear();
        assertEquals(0, binder.size());
    }

    @Test
    public void assertLatestSetterWinsAndSizeDoesNotGrowForSameParameter() throws Exception {
        ParameterBinder binder = new ParameterBinder();
        binder.recordSetInt(1, 10);
        binder.recordSetInt(1, 20);
        binder.recordSetInt(1, 30);

        assertEquals(1, binder.size());

        RecordingPreparedStatementHandler handler = new RecordingPreparedStatementHandler();
        PreparedStatement target = handler.createProxy();
        binder.replay(target);

        assertEquals(1, handler.calls.size());
        assertEquals("setInt:2:1", handler.calls.get(0));
    }

    @Test
    public void assertReplayWithNullTargetThrowsSQLException() throws Exception {
        ParameterBinder binder = new ParameterBinder();
        binder.recordSetInt(1, 1);
        try {
            binder.replay(null);
            fail("Expected SQLException");
        } catch (SQLException ex) {
            assertTrue(ex.getMessage().contains("missing prepared statement"));
        }
    }

    @Test
    public void assertAllRecordedSetterOperationsReplayWithoutLoss() throws Exception {
        ParameterBinder binder = new ParameterBinder();
        Calendar calendar = Calendar.getInstance();
        Date date = new Date(0L);
        Time time = new Time(0L);
        Timestamp timestamp = new Timestamp(0L);
        ByteArrayInputStream binaryStream = new ByteArrayInputStream(new byte[] {1, 2, 3});
        ByteArrayInputStream asciiStream = new ByteArrayInputStream(new byte[] {65, 66});
        StringReader reader = new StringReader("lb-jdbc");
        URL url = new URL("http://localhost/test");
        Blob blob =
                (Blob)
                        Proxy.newProxyInstance(
                                Blob.class.getClassLoader(),
                                new Class[] {Blob.class},
                                new NoOpHandler());
        Clob clob =
                (Clob)
                        Proxy.newProxyInstance(
                                Clob.class.getClassLoader(),
                                new Class[] {Clob.class},
                                new NoOpHandler());
        NClob nclob =
                (NClob)
                        Proxy.newProxyInstance(
                                NClob.class.getClassLoader(),
                                new Class[] {NClob.class},
                                new NoOpHandler());
        Ref ref =
                (Ref)
                        Proxy.newProxyInstance(
                                Ref.class.getClassLoader(),
                                new Class[] {Ref.class},
                                new NoOpHandler());
        Array array =
                (Array)
                        Proxy.newProxyInstance(
                                Array.class.getClassLoader(),
                                new Class[] {Array.class},
                                new NoOpHandler());
        RowId rowId =
                (RowId)
                        Proxy.newProxyInstance(
                                RowId.class.getClassLoader(),
                                new Class[] {RowId.class},
                                new NoOpHandler());
        SQLXML sqlxml =
                (SQLXML)
                        Proxy.newProxyInstance(
                                SQLXML.class.getClassLoader(),
                                new Class[] {SQLXML.class},
                                new NoOpHandler());

        binder.recordSetNull(1, Types.INTEGER);
        binder.recordSetNull(2, Types.VARCHAR, "VARCHAR");
        binder.recordSetBoolean(3, true);
        binder.recordSetByte(4, (byte) 7);
        binder.recordSetShort(5, (short) 8);
        binder.recordSetInt(6, 9);
        binder.recordSetLong(7, 10L);
        binder.recordSetFloat(8, 1.5f);
        binder.recordSetDouble(9, 2.5d);
        binder.recordSetBigDecimal(10, new BigDecimal("3.14"));
        binder.recordSetString(11, "text");
        binder.recordSetBytes(12, new byte[] {1, 2});
        binder.recordSetDate(13, date);
        binder.recordSetDate(14, date, calendar);
        binder.recordSetTime(15, time);
        binder.recordSetTime(16, time, calendar);
        binder.recordSetTimestamp(17, timestamp);
        binder.recordSetTimestamp(18, timestamp, calendar);
        binder.recordSetAsciiStream(19, asciiStream, 2);
        binder.recordSetAsciiStream(20, asciiStream, 2L);
        binder.recordSetAsciiStream(21, asciiStream);
        binder.recordSetUnicodeStream(22, binaryStream, 3);
        binder.recordSetBinaryStream(23, binaryStream, 3);
        binder.recordSetBinaryStream(24, binaryStream, 3L);
        binder.recordSetBinaryStream(25, binaryStream);
        binder.recordSetObject(26, "obj", Types.VARCHAR);
        binder.recordSetObject(27, "obj");
        binder.recordSetObject(28, "obj", Types.VARCHAR, 10);
        binder.recordSetCharacterStream(29, reader, 4);
        binder.recordSetCharacterStream(30, reader, 4L);
        binder.recordSetCharacterStream(31, reader);
        binder.recordSetRef(32, ref);
        binder.recordSetBlob(33, blob);
        binder.recordSetBlob(34, binaryStream, 3L);
        binder.recordSetBlob(35, binaryStream);
        binder.recordSetClob(36, clob);
        binder.recordSetClob(37, reader, 4L);
        binder.recordSetClob(38, reader);
        binder.recordSetArray(39, array);
        binder.recordSetURL(40, url);
        binder.recordSetRowId(41, rowId);
        binder.recordSetNString(42, "nstr");
        binder.recordSetNCharacterStream(43, reader, 4L);
        binder.recordSetNCharacterStream(44, reader);
        binder.recordSetNClob(45, nclob);
        binder.recordSetNClob(46, reader, 4L);
        binder.recordSetNClob(47, reader);
        binder.recordSetSQLXML(48, sqlxml);
        binder.recordSetOID(49, (CUBRIDOID) null);
        binder.recordSetCollection(50, new Object[] {"a", Integer.valueOf(1)});
        binder.recordSetTimestamptz(51, (CUBRIDTimestamptz) null);
        binder.recordSetTimestamptz(52, (CUBRIDTimestamptz) null, calendar);

        RecordingPreparedStatementHandler handler = new RecordingPreparedStatementHandler();
        PreparedStatement target = handler.createProxy();
        binder.replay(target);

        assertEquals(52, handler.calls.size());
        assertTrue(handler.calls.contains("setCollection:2:50"));
        assertTrue(handler.calls.contains("setTimestamptz:2:51"));
        assertTrue(handler.calls.contains("setTimestamptz:3:52"));
    }

    /**
     * The goal is to match core, not to capture everything at set time.
     *
     * <p>LB keeps each setter as an operation and replays it at execute time; core binds inside the
     * setter. That is observable only where core copies the value, and it copies exactly two kinds:
     * {@code byte[]} ({@code UStatement.bind} clones it) and a collection array ({@code
     * CUBRIDArray} shallow-clones it). Temporal values and arbitrary objects core keeps by
     * reference and serializes at execute time, so copying those in LB would create a divergence,
     * not remove one.
     *
     * <p>Against a classic connection to a live broker, a {@code Timestamp} mutated between {@code
     * setTimestamp} and {@code executeUpdate} lands in the row with its new value, a mutated {@code
     * byte[]} with its old one. These tests pin both halves.
     */
    @Test
    public void assertMutatedByteArrayReplaysTheValueAsItWasWhenSet() throws Exception {
        byte[] value = new byte[] {1, 2, 3};
        ParameterBinder binder = new ParameterBinder();
        binder.recordSetBytes(1, value);

        value[0] = 99;

        ValueCapturingHandler handler = new ValueCapturingHandler();
        binder.replay(handler.createProxy());

        byte[] replayed = (byte[]) handler.lastArgument("setBytes");
        assertNotNull(replayed);
        assertEquals("the bytes as they were at set time", 1, replayed[0]);
    }

    /**
     * The other half: a temporal value must NOT be snapshotted, because core does not snapshot one
     * either. Copying it would make LB send the old value where core sends the new one — a
     * divergence introduced by the fix rather than removed by it.
     */
    @Test
    public void assertMutatedTimestampReplaysTheMutatedValueAsCoreDoes() throws Exception {
        Timestamp value = new Timestamp(1000L);
        ParameterBinder binder = new ParameterBinder();
        binder.recordSetTimestamp(1, value);

        value.setTime(999000L);

        ValueCapturingHandler handler = new ValueCapturingHandler();
        binder.replay(handler.createProxy());

        Timestamp replayed = (Timestamp) handler.lastArgument("setTimestamp");
        assertSame("the caller's instance must reach the physical statement", value, replayed);
        assertEquals(999000L, replayed.getTime());
    }

    @Test
    public void assertMutatedCollectionReplaysTheValueAsItWasWhenSet() throws Exception {
        Object[] collection = new Object[] {"a", "b"};
        ParameterBinder binder = new ParameterBinder();
        binder.recordSetCollection(1, collection);

        collection[0] = "mutated";

        ValueCapturingHandler handler = new ValueCapturingHandler();
        binder.replay(handler.createProxy());

        Object[] replayed = (Object[]) handler.lastArgument("setCollection");
        assertEquals("a", replayed[0]);
    }

    /**
     * The Calendar overloads are not snapshotted either, and it does not matter: core ignores the
     * Calendar and delegates to the two-argument form, so its value never reaches the server on
     * either driver. Pinned so a reader does not add a copy here looking for parity that no
     * observation can distinguish.
     */
    @Test
    public void assertCalendarIsPassedThroughUncopied() throws Exception {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(5000L);
        ParameterBinder binder = new ParameterBinder();
        binder.recordSetTimestamp(1, new Timestamp(1000L), cal);

        ValueCapturingHandler handler = new ValueCapturingHandler();
        binder.replay(handler.createProxy());

        assertSame(cal, handler.lastArgument("setTimestamp"));
    }

    /**
     * A second reason not to copy temporal values, independent of the parity argument: {@code
     * CUBRIDTimestamptz} carries a timezone and an {@code isDatetime} flag, and core derives the
     * wire type (TIMESTAMPTZ/DATETIMETZ) from the runtime class. Replacing it with a plain {@code
     * java.sql.Timestamp} would silently turn the column type into TIMESTAMP. The instance itself
     * must reach the physical statement.
     */
    @Test
    public void assertTimestamptzSubclassIsPreservedRatherThanCopied() throws Exception {
        CUBRIDTimestamptz value =
                CUBRIDTimestamptz.valueOf("2026-08-21 10:00:00.000", true, "Asia/Seoul");
        ParameterBinder binder = new ParameterBinder();
        binder.recordSetTimestamp(1, value);

        ValueCapturingHandler handler = new ValueCapturingHandler();
        binder.replay(handler.createProxy());

        Object replayed = handler.lastArgument("setTimestamp");
        assertSame(
                "the subclass instance itself must reach the physical statement", value, replayed);
    }

    private static final class ValueCapturingHandler {

        private final java.util.Map<String, Object> lastValues =
                new java.util.HashMap<String, Object>();

        private PreparedStatement createProxy() {
            return new FakePhysicalPreparedStatement() {
                @Override
                protected Object dispatch(final String name, final Object[] args) {
                    if (name.startsWith("set") && args.length >= 2) {
                        // The value is the second argument; for the Calendar overloads the third is
                        // the one worth keeping, so it wins.
                        lastValues.put(name, args[args.length - 1]);
                    }

                    return null;
                }
            };
        }

        private Object lastArgument(final String setter) {
            return lastValues.get(setter);
        }
    }

    private static final class RecordingPreparedStatementHandler {

        private final List<String> calls = new ArrayList<String>();

        private PreparedStatement createProxy() {
            return new FakePhysicalPreparedStatement() {
                @Override
                protected Object dispatch(final String name, final Object[] args) {
                    if (name.startsWith("set")) {
                        Object parameterIndex = args.length > 0 ? args[0] : null;
                        calls.add(name + ":" + args.length + ":" + parameterIndex);
                    }

                    return null;
                }
            };
        }
    }

    private static final class NoOpHandler implements InvocationHandler {

        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            Class<?> returnType = method.getReturnType();
            if (Boolean.TYPE.equals(returnType)) {
                return Boolean.FALSE;
            }
            if (Integer.TYPE.equals(returnType)) {
                return Integer.valueOf(0);
            }
            if (Long.TYPE.equals(returnType)) {
                return Long.valueOf(0L);
            }
            assertNotNull(proxy);
            return null;
        }
    }
}
