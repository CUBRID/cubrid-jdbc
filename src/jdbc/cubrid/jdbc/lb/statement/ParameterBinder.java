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

import cubrid.jdbc.driver.CUBRIDPreparedStatement;
import cubrid.jdbc.lb.LbExceptions;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;

/**
 * Stores parameter setter calls and replays them to a target {@link PreparedStatement}.
 *
 * <p>Common JDBC setters dispatch through {@link PreparedStatement}; the CUBRID-only setters cast
 * the target to {@link CUBRIDPreparedStatement}, which every physical statement is.
 *
 * <p>Replay is what lets one logical PreparedStatement move between physical connections: the
 * parameters bound before a route change or a failover rebind are re-applied to the new physical
 * statement.
 */
public final class ParameterBinder {
    private final OperationList operations = new OperationList();

    // True once a stream/reader-backed parameter is recorded. Such a value is consumed by the first
    // execution and cannot be replayed, so failover must not retry a statement bound with one.
    private boolean hasStreamParam = false;

    public void recordSetNull(final int parameterIndex, final int sqlType) {
        operations.add(new SetNullOp(parameterIndex, sqlType));
    }

    public void recordSetNull(final int parameterIndex, final int sqlType, final String typeName) {
        operations.add(new SetNullTypeNameOp(parameterIndex, sqlType, typeName));
    }

    public void recordSetString(final int parameterIndex, final String value) {
        operations.add(new SetStringOp(parameterIndex, value));
    }

    public void recordSetInt(final int parameterIndex, final int value) {
        operations.add(new SetIntOp(parameterIndex, value));
    }

    public void recordSetLong(final int parameterIndex, final long value) {
        operations.add(new SetLongOp(parameterIndex, value));
    }

    public void recordSetByte(final int parameterIndex, final byte value) {
        operations.add(new SetByteOp(parameterIndex, value));
    }

    public void recordSetShort(final int parameterIndex, final short value) {
        operations.add(new SetShortOp(parameterIndex, value));
    }

    public void recordSetDouble(final int parameterIndex, final double value) {
        operations.add(new SetDoubleOp(parameterIndex, value));
    }

    public void recordSetFloat(final int parameterIndex, final float value) {
        operations.add(new SetFloatOp(parameterIndex, value));
    }

    public void recordSetBoolean(final int parameterIndex, final boolean value) {
        operations.add(new SetBooleanOp(parameterIndex, value));
    }

    public void recordSetDate(final int parameterIndex, final java.sql.Date value) {
        operations.add(new SetDateOp(parameterIndex, value));
    }

    public void recordSetDate(
            final int parameterIndex, final java.sql.Date value, final java.util.Calendar cal) {
        operations.add(new SetDateCalOp(parameterIndex, value, cal));
    }

    public void recordSetTime(final int parameterIndex, final java.sql.Time value) {
        operations.add(new SetTimeOp(parameterIndex, value));
    }

    public void recordSetTime(
            final int parameterIndex, final java.sql.Time value, final java.util.Calendar cal) {
        operations.add(new SetTimeCalOp(parameterIndex, value, cal));
    }

    public void recordSetTimestamp(final int parameterIndex, final java.sql.Timestamp value) {
        operations.add(new SetTimestampOp(parameterIndex, value));
    }

    public void recordSetTimestamp(
            final int parameterIndex,
            final java.sql.Timestamp value,
            final java.util.Calendar cal) {
        operations.add(new SetTimestampCalOp(parameterIndex, value, cal));
    }

    public void recordSetBigDecimal(final int parameterIndex, final java.math.BigDecimal value) {
        operations.add(new SetBigDecimalOp(parameterIndex, value));
    }

    public void recordSetBytes(final int parameterIndex, final byte[] value) {
        operations.add(new SetBytesOp(parameterIndex, snapshot(value)));
    }

    public void recordSetCharacterStream(final int parameterIndex, final java.io.Reader reader) {
        hasStreamParam = true;
        operations.add(new SetCharacterStreamOp(parameterIndex, reader));
    }

    public void recordSetCharacterStream(
            final int parameterIndex, final java.io.Reader reader, final int length) {
        hasStreamParam = true;
        operations.add(new SetCharacterStreamLenIntOp(parameterIndex, reader, length));
    }

    public void recordSetCharacterStream(
            final int parameterIndex, final java.io.Reader reader, final long length) {
        hasStreamParam = true;
        operations.add(new SetCharacterStreamLenLongOp(parameterIndex, reader, length));
    }

    public void recordSetBinaryStream(
            final int parameterIndex, final java.io.InputStream inputStream) {
        hasStreamParam = true;
        operations.add(new SetBinaryStreamOp(parameterIndex, inputStream));
    }

    public void recordSetBinaryStream(
            final int parameterIndex, final java.io.InputStream inputStream, final int length) {
        hasStreamParam = true;
        operations.add(new SetBinaryStreamLenIntOp(parameterIndex, inputStream, length));
    }

    public void recordSetBinaryStream(
            final int parameterIndex, final java.io.InputStream inputStream, final long length) {
        hasStreamParam = true;
        operations.add(new SetBinaryStreamLenLongOp(parameterIndex, inputStream, length));
    }

    public void recordSetAsciiStream(
            final int parameterIndex, final java.io.InputStream inputStream) {
        hasStreamParam = true;
        operations.add(new SetAsciiStreamOp(parameterIndex, inputStream));
    }

    public void recordSetAsciiStream(
            final int parameterIndex, final java.io.InputStream inputStream, final int length) {
        hasStreamParam = true;
        operations.add(new SetAsciiStreamLenIntOp(parameterIndex, inputStream, length));
    }

    public void recordSetAsciiStream(
            final int parameterIndex, final java.io.InputStream inputStream, final long length) {
        hasStreamParam = true;
        operations.add(new SetAsciiStreamLenLongOp(parameterIndex, inputStream, length));
    }

    public void recordSetURL(final int parameterIndex, final java.net.URL value) {
        operations.add(new SetUrlOp(parameterIndex, value));
    }

    public void recordSetBlob(final int parameterIndex, final java.sql.Blob value) {
        operations.add(new SetBlobObjOp(parameterIndex, value));
    }

    public void recordSetBlob(final int parameterIndex, final java.io.InputStream inputStream) {
        hasStreamParam = true;
        operations.add(new SetBlobStreamOp(parameterIndex, inputStream));
    }

    public void recordSetBlob(
            final int parameterIndex, final java.io.InputStream inputStream, final long length) {
        hasStreamParam = true;
        operations.add(new SetBlobLenOp(parameterIndex, inputStream, length));
    }

    public void recordSetClob(final int parameterIndex, final java.sql.Clob value) {
        operations.add(new SetClobObjOp(parameterIndex, value));
    }

    public void recordSetClob(final int parameterIndex, final java.io.Reader reader) {
        hasStreamParam = true;
        operations.add(new SetClobReaderOp(parameterIndex, reader));
    }

    public void recordSetClob(
            final int parameterIndex, final java.io.Reader reader, final long length) {
        hasStreamParam = true;
        operations.add(new SetClobLenOp(parameterIndex, reader, length));
    }

    public void recordSetRef(final int parameterIndex, final java.sql.Ref value) {
        operations.add(new SetRefOp(parameterIndex, value));
    }

    public void recordSetArray(final int parameterIndex, final java.sql.Array value) {
        operations.add(new SetArrayOp(parameterIndex, value));
    }

    public void recordSetRowId(final int parameterIndex, final java.sql.RowId value) {
        operations.add(new SetRowIdOp(parameterIndex, value));
    }

    public void recordSetNString(final int parameterIndex, final String value) {
        operations.add(new SetNStringOp(parameterIndex, value));
    }

    public void recordSetNCharacterStream(final int parameterIndex, final java.io.Reader reader) {
        hasStreamParam = true;
        operations.add(new SetNCharacterStreamOp(parameterIndex, reader));
    }

    public void recordSetNCharacterStream(
            final int parameterIndex, final java.io.Reader reader, final long length) {
        hasStreamParam = true;
        operations.add(new SetNCharacterStreamLenLongOp(parameterIndex, reader, length));
    }

    public void recordSetNClob(final int parameterIndex, final java.sql.NClob value) {
        operations.add(new SetNClobObjOp(parameterIndex, value));
    }

    public void recordSetNClob(final int parameterIndex, final java.io.Reader reader) {
        hasStreamParam = true;
        operations.add(new SetNClobReaderOp(parameterIndex, reader));
    }

    public void recordSetNClob(
            final int parameterIndex, final java.io.Reader reader, final long length) {
        hasStreamParam = true;
        operations.add(new SetNClobLenLongOp(parameterIndex, reader, length));
    }

    public void recordSetSQLXML(final int parameterIndex, final java.sql.SQLXML value) {
        operations.add(new SetSqlxmlOp(parameterIndex, value));
    }

    public void recordSetUnicodeStream(
            final int parameterIndex, final java.io.InputStream inputStream, final int length) {
        hasStreamParam = true;
        operations.add(new SetUnicodeStreamOp(parameterIndex, inputStream, length));
    }

    public void recordSetObject(final int parameterIndex, final Object value) {
        operations.add(new SetObjectOp(parameterIndex, snapshot(value)));
    }

    public void recordSetObject(
            final int parameterIndex, final Object value, final int targetSqlType) {
        operations.add(new SetObjectTypeOp(parameterIndex, snapshot(value), targetSqlType));
    }

    public void recordSetObject(
            final int parameterIndex,
            final Object value,
            final int targetSqlType,
            final int scaleOrLength) {
        operations.add(
                new SetObjectTypeScaleOp(
                        parameterIndex, snapshot(value), targetSqlType, scaleOrLength));
    }

    public void recordSetOID(final int parameterIndex, final cubrid.sql.CUBRIDOID value) {
        operations.add(new SetOidOp(parameterIndex, value));
    }

    public void recordSetCollection(final int parameterIndex, final Object[] value) {
        operations.add(new SetCollectionOp(parameterIndex, snapshot(value)));
    }

    public void recordSetTimestamptz(
            final int parameterIndex, final cubrid.sql.CUBRIDTimestamptz value) {
        operations.add(new SetTimestamptzOp(parameterIndex, value));
    }

    public void recordSetTimestamptz(
            final int parameterIndex,
            final cubrid.sql.CUBRIDTimestamptz value,
            final java.util.Calendar cal) {
        operations.add(new SetTimestamptzCalOp(parameterIndex, value, cal));
    }

    /* ===== snapshots: match what core captures at set time =====*/

    /**
     * Copies a mutable parameter value at set time, for the types core copies.
     *
     * <p>LB keeps each {@code setXxx} as an operation and replays it at execute time, which is what
     * lets a failed execution retry on another leg. Core binds into the socket statement inside
     * {@code setXxx}. The difference is observable only where core copies the value, and it copies
     * exactly two kinds:
     *
     * <ul>
     *   <li>{@code byte[]} - {@code UStatement.bind(int, byte[])} does {@code value.clone()}.
     *   <li>a collection array - {@code CUBRIDArray} shallow-clones the {@code Object[]} it is
     *       given.
     * </ul>
     *
     * <p>Everything else ({@code Date}, {@code Time}, {@code Timestamp}, arbitrary objects) core
     * stores by reference and serializes at execute time, so mutating one after the setter changes
     * what core sends too. Snapshotting those in LB would create a divergence rather than fix one.
     *
     * @param value the array the caller handed in, may be null
     * @return an independent copy, or null
     */
    private static byte[] snapshot(final byte[] value) {
        return value == null ? null : (byte[]) value.clone();
    }

    /**
     * Shallow, matching {@code CUBRIDArray}'s own clone: the caller can no longer swap elements
     * after the call, and an element that is itself mutable stays shared on both drivers alike.
     */
    private static Object[] snapshot(final Object[] value) {
        return value == null ? null : (Object[]) value.clone();
    }

    /**
     * {@code setObject} dispatches on the runtime type in core too, so a {@code byte[]} passed this
     * way reaches {@code bind(int, byte[])} and is copied there. Anything else keeps the caller's
     * reference, exactly as core does — an arbitrary object has no general copy, and core does not
     * attempt one either.
     */
    private static Object snapshot(final Object value) {
        if (value instanceof byte[]) {
            return snapshot((byte[]) value);
        }
        if (value instanceof Object[]) {
            return snapshot((Object[]) value);
        }

        return value;
    }

    public void clear() {
        operations.clear();
        hasStreamParam = false;
    }

    /**
     * Whether any bound parameter is a stream or reader that the first execution consumes and that
     * therefore cannot be replayed on a failover retry.
     *
     * @return {@code true} if a bound parameter cannot be replayed
     */
    public boolean hasNonReplayableParams() {
        return hasStreamParam;
    }

    public int size() {
        return operations.size();
    }

    public int getMaxParameterIndex() {
        return operations.maxParameterIndex();
    }

    public ParameterBinder snapshot() {
        ParameterBinder result = new ParameterBinder();
        result.operations.addAllFrom(operations);
        result.hasStreamParam = hasStreamParam;

        return result;
    }

    public void replay(final PreparedStatement target) throws SQLException {
        if (null == target) {
            throw LbExceptions.internalState("missing prepared statement");
        }
        operations.replayTo(target);
    }

    /* ===== operation base =====*/
    private abstract static class ParameterOperation {

        final int parameterIndex;

        ParameterOperation(final int parameterIndex) {
            this.parameterIndex = parameterIndex;
        }

        abstract void apply(PreparedStatement target) throws SQLException;
    }

    /* ===== typed operations =====*/ private static final class SetNullOp
            extends ParameterOperation {
        private final int sqlType;

        SetNullOp(final int parameterIndex, final int sqlType) {
            super(parameterIndex);
            this.sqlType = sqlType;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setNull(parameterIndex, sqlType);
        }
    }

    private static final class SetNullTypeNameOp extends ParameterOperation {
        private final int sqlType;
        private final String typeName;

        SetNullTypeNameOp(final int parameterIndex, final int sqlType, final String typeName) {
            super(parameterIndex);
            this.sqlType = sqlType;
            this.typeName = typeName;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setNull(parameterIndex, sqlType, typeName);
        }
    }

    private static final class SetStringOp extends ParameterOperation {
        private final String value;

        SetStringOp(final int parameterIndex, final String value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setString(parameterIndex, value);
        }
    }

    private static final class SetIntOp extends ParameterOperation {
        private final int value;

        SetIntOp(final int parameterIndex, final int value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setInt(parameterIndex, value);
        }
    }

    private static final class SetLongOp extends ParameterOperation {
        private final long value;

        SetLongOp(final int parameterIndex, final long value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setLong(parameterIndex, value);
        }
    }

    private static final class SetByteOp extends ParameterOperation {
        private final byte value;

        SetByteOp(final int parameterIndex, final byte value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setByte(parameterIndex, value);
        }
    }

    private static final class SetShortOp extends ParameterOperation {
        private final short value;

        SetShortOp(final int parameterIndex, final short value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setShort(parameterIndex, value);
        }
    }

    private static final class SetDoubleOp extends ParameterOperation {
        private final double value;

        SetDoubleOp(final int parameterIndex, final double value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setDouble(parameterIndex, value);
        }
    }

    private static final class SetFloatOp extends ParameterOperation {
        private final float value;

        SetFloatOp(final int parameterIndex, final float value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setFloat(parameterIndex, value);
        }
    }

    private static final class SetBooleanOp extends ParameterOperation {
        private final boolean value;

        SetBooleanOp(final int parameterIndex, final boolean value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setBoolean(parameterIndex, value);
        }
    }

    private static final class SetDateOp extends ParameterOperation {
        private final java.sql.Date value;

        SetDateOp(final int parameterIndex, final java.sql.Date value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setDate(parameterIndex, value);
        }
    }

    private static final class SetDateCalOp extends ParameterOperation {
        private final java.sql.Date value;
        private final java.util.Calendar cal;

        SetDateCalOp(
                final int parameterIndex, final java.sql.Date value, final java.util.Calendar cal) {
            super(parameterIndex);
            this.value = value;
            this.cal = cal;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setDate(parameterIndex, value, cal);
        }
    }

    private static final class SetTimeOp extends ParameterOperation {
        private final java.sql.Time value;

        SetTimeOp(final int parameterIndex, final java.sql.Time value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setTime(parameterIndex, value);
        }
    }

    private static final class SetTimeCalOp extends ParameterOperation {
        private final java.sql.Time value;
        private final java.util.Calendar cal;

        SetTimeCalOp(
                final int parameterIndex, final java.sql.Time value, final java.util.Calendar cal) {
            super(parameterIndex);
            this.value = value;
            this.cal = cal;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setTime(parameterIndex, value, cal);
        }
    }

    private static final class SetTimestampOp extends ParameterOperation {
        private final java.sql.Timestamp value;

        SetTimestampOp(final int parameterIndex, final java.sql.Timestamp value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setTimestamp(parameterIndex, value);
        }
    }

    private static final class SetTimestampCalOp extends ParameterOperation {
        private final java.sql.Timestamp value;
        private final java.util.Calendar cal;

        SetTimestampCalOp(
                final int parameterIndex,
                final java.sql.Timestamp value,
                final java.util.Calendar cal) {
            super(parameterIndex);
            this.value = value;
            this.cal = cal;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setTimestamp(parameterIndex, value, cal);
        }
    }

    private static final class SetBigDecimalOp extends ParameterOperation {
        private final java.math.BigDecimal value;

        SetBigDecimalOp(final int parameterIndex, final java.math.BigDecimal value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setBigDecimal(parameterIndex, value);
        }
    }

    private static final class SetBytesOp extends ParameterOperation {
        private final byte[] value;

        SetBytesOp(final int parameterIndex, final byte[] value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setBytes(parameterIndex, value);
        }
    }

    private static final class SetCharacterStreamOp extends ParameterOperation {
        private final java.io.Reader reader;

        SetCharacterStreamOp(final int parameterIndex, final java.io.Reader reader) {
            super(parameterIndex);
            this.reader = reader;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setCharacterStream(parameterIndex, reader);
        }
    }

    private static final class SetCharacterStreamLenIntOp extends ParameterOperation {
        private final java.io.Reader reader;
        private final int length;

        SetCharacterStreamLenIntOp(
                final int parameterIndex, final java.io.Reader reader, final int length) {
            super(parameterIndex);
            this.reader = reader;
            this.length = length;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setCharacterStream(parameterIndex, reader, length);
        }
    }

    private static final class SetCharacterStreamLenLongOp extends ParameterOperation {
        private final java.io.Reader reader;
        private final long length;

        SetCharacterStreamLenLongOp(
                final int parameterIndex, final java.io.Reader reader, final long length) {
            super(parameterIndex);
            this.reader = reader;
            this.length = length;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setCharacterStream(parameterIndex, reader, length);
        }
    }

    private static final class SetBinaryStreamOp extends ParameterOperation {
        private final java.io.InputStream stream;

        SetBinaryStreamOp(final int parameterIndex, final java.io.InputStream stream) {
            super(parameterIndex);
            this.stream = stream;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setBinaryStream(parameterIndex, stream);
        }
    }

    private static final class SetBinaryStreamLenIntOp extends ParameterOperation {
        private final java.io.InputStream stream;
        private final int length;

        SetBinaryStreamLenIntOp(
                final int parameterIndex, final java.io.InputStream stream, final int length) {
            super(parameterIndex);
            this.stream = stream;
            this.length = length;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setBinaryStream(parameterIndex, stream, length);
        }
    }

    private static final class SetBinaryStreamLenLongOp extends ParameterOperation {
        private final java.io.InputStream stream;
        private final long length;

        SetBinaryStreamLenLongOp(
                final int parameterIndex, final java.io.InputStream stream, final long length) {
            super(parameterIndex);
            this.stream = stream;
            this.length = length;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setBinaryStream(parameterIndex, stream, length);
        }
    }

    private static final class SetAsciiStreamOp extends ParameterOperation {
        private final java.io.InputStream stream;

        SetAsciiStreamOp(final int parameterIndex, final java.io.InputStream stream) {
            super(parameterIndex);
            this.stream = stream;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setAsciiStream(parameterIndex, stream);
        }
    }

    private static final class SetAsciiStreamLenIntOp extends ParameterOperation {
        private final java.io.InputStream stream;
        private final int length;

        SetAsciiStreamLenIntOp(
                final int parameterIndex, final java.io.InputStream stream, final int length) {
            super(parameterIndex);
            this.stream = stream;
            this.length = length;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setAsciiStream(parameterIndex, stream, length);
        }
    }

    private static final class SetAsciiStreamLenLongOp extends ParameterOperation {
        private final java.io.InputStream stream;
        private final long length;

        SetAsciiStreamLenLongOp(
                final int parameterIndex, final java.io.InputStream stream, final long length) {
            super(parameterIndex);
            this.stream = stream;
            this.length = length;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setAsciiStream(parameterIndex, stream, length);
        }
    }

    private static final class SetUrlOp extends ParameterOperation {
        private final java.net.URL value;

        SetUrlOp(final int parameterIndex, final java.net.URL value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setURL(parameterIndex, value);
        }
    }

    private static final class SetBlobObjOp extends ParameterOperation {
        private final java.sql.Blob value;

        SetBlobObjOp(final int parameterIndex, final java.sql.Blob value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setBlob(parameterIndex, value);
        }
    }

    private static final class SetBlobStreamOp extends ParameterOperation {
        private final java.io.InputStream stream;

        SetBlobStreamOp(final int parameterIndex, final java.io.InputStream stream) {
            super(parameterIndex);
            this.stream = stream;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setBlob(parameterIndex, stream);
        }
    }

    private static final class SetBlobLenOp extends ParameterOperation {
        private final java.io.InputStream stream;
        private final long length;

        SetBlobLenOp(
                final int parameterIndex, final java.io.InputStream stream, final long length) {
            super(parameterIndex);
            this.stream = stream;
            this.length = length;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setBlob(parameterIndex, stream, length);
        }
    }

    private static final class SetClobObjOp extends ParameterOperation {
        private final java.sql.Clob value;

        SetClobObjOp(final int parameterIndex, final java.sql.Clob value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setClob(parameterIndex, value);
        }
    }

    private static final class SetClobReaderOp extends ParameterOperation {
        private final java.io.Reader reader;

        SetClobReaderOp(final int parameterIndex, final java.io.Reader reader) {
            super(parameterIndex);
            this.reader = reader;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setClob(parameterIndex, reader);
        }
    }

    private static final class SetClobLenOp extends ParameterOperation {
        private final java.io.Reader reader;
        private final long length;

        SetClobLenOp(final int parameterIndex, final java.io.Reader reader, final long length) {
            super(parameterIndex);
            this.reader = reader;
            this.length = length;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setClob(parameterIndex, reader, length);
        }
    }

    private static final class SetRefOp extends ParameterOperation {
        private final java.sql.Ref value;

        SetRefOp(final int parameterIndex, final java.sql.Ref value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setRef(parameterIndex, value);
        }
    }

    private static final class SetArrayOp extends ParameterOperation {
        private final java.sql.Array value;

        SetArrayOp(final int parameterIndex, final java.sql.Array value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setArray(parameterIndex, value);
        }
    }

    private static final class SetRowIdOp extends ParameterOperation {
        private final java.sql.RowId value;

        SetRowIdOp(final int parameterIndex, final java.sql.RowId value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setRowId(parameterIndex, value);
        }
    }

    private static final class SetNStringOp extends ParameterOperation {
        private final String value;

        SetNStringOp(final int parameterIndex, final String value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setNString(parameterIndex, value);
        }
    }

    private static final class SetNCharacterStreamOp extends ParameterOperation {
        private final java.io.Reader reader;

        SetNCharacterStreamOp(final int parameterIndex, final java.io.Reader reader) {
            super(parameterIndex);
            this.reader = reader;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setNCharacterStream(parameterIndex, reader);
        }
    }

    private static final class SetNCharacterStreamLenLongOp extends ParameterOperation {
        private final java.io.Reader reader;
        private final long length;

        SetNCharacterStreamLenLongOp(
                final int parameterIndex, final java.io.Reader reader, final long length) {
            super(parameterIndex);
            this.reader = reader;
            this.length = length;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setNCharacterStream(parameterIndex, reader, length);
        }
    }

    private static final class SetNClobObjOp extends ParameterOperation {
        private final java.sql.NClob value;

        SetNClobObjOp(final int parameterIndex, final java.sql.NClob value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setNClob(parameterIndex, value);
        }
    }

    private static final class SetNClobReaderOp extends ParameterOperation {
        private final java.io.Reader reader;

        SetNClobReaderOp(final int parameterIndex, final java.io.Reader reader) {
            super(parameterIndex);
            this.reader = reader;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setNClob(parameterIndex, reader);
        }
    }

    private static final class SetNClobLenLongOp extends ParameterOperation {
        private final java.io.Reader reader;
        private final long length;

        SetNClobLenLongOp(
                final int parameterIndex, final java.io.Reader reader, final long length) {
            super(parameterIndex);
            this.reader = reader;
            this.length = length;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setNClob(parameterIndex, reader, length);
        }
    }

    private static final class SetSqlxmlOp extends ParameterOperation {
        private final java.sql.SQLXML value;

        SetSqlxmlOp(final int parameterIndex, final java.sql.SQLXML value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setSQLXML(parameterIndex, value);
        }
    }

    @SuppressWarnings("deprecation")
    private static final class SetUnicodeStreamOp extends ParameterOperation {
        private final java.io.InputStream stream;
        private final int length;

        SetUnicodeStreamOp(
                final int parameterIndex, final java.io.InputStream stream, final int length) {
            super(parameterIndex);
            this.stream = stream;
            this.length = length;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setUnicodeStream(parameterIndex, stream, length);
        }
    }

    private static final class SetObjectOp extends ParameterOperation {
        private final Object value;

        SetObjectOp(final int parameterIndex, final Object value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setObject(parameterIndex, value);
        }
    }

    private static final class SetObjectTypeOp extends ParameterOperation {
        private final Object value;
        private final int targetSqlType;

        SetObjectTypeOp(final int parameterIndex, final Object value, final int targetSqlType) {
            super(parameterIndex);
            this.value = value;
            this.targetSqlType = targetSqlType;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setObject(parameterIndex, value, targetSqlType);
        }
    }

    private static final class SetObjectTypeScaleOp extends ParameterOperation {
        private final Object value;
        private final int targetSqlType;
        private final int scaleOrLength;

        SetObjectTypeScaleOp(
                final int parameterIndex,
                final Object value,
                final int targetSqlType,
                final int scaleOrLength) {
            super(parameterIndex);
            this.value = value;
            this.targetSqlType = targetSqlType;
            this.scaleOrLength = scaleOrLength;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            target.setObject(parameterIndex, value, targetSqlType, scaleOrLength);
        }
    }

    /* ===== vendor extensions =====*/ private static final class SetOidOp
            extends ParameterOperation {
        private final cubrid.sql.CUBRIDOID value;

        SetOidOp(final int parameterIndex, final cubrid.sql.CUBRIDOID value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            requireCubrid(target, "setOID").setOID(parameterIndex, value);
        }
    }

    private static final class SetCollectionOp extends ParameterOperation {
        private final Object[] value;

        SetCollectionOp(final int parameterIndex, final Object[] value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            requireCubrid(target, "setCollection").setCollection(parameterIndex, value);
        }
    }

    private static final class SetTimestamptzOp extends ParameterOperation {
        private final cubrid.sql.CUBRIDTimestamptz value;

        SetTimestamptzOp(final int parameterIndex, final cubrid.sql.CUBRIDTimestamptz value) {
            super(parameterIndex);
            this.value = value;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            requireCubrid(target, "setTimestamptz").setTimestamptz(parameterIndex, value);
        }
    }

    private static final class SetTimestamptzCalOp extends ParameterOperation {
        private final cubrid.sql.CUBRIDTimestamptz value;
        private final java.util.Calendar cal;

        SetTimestamptzCalOp(
                final int parameterIndex,
                final cubrid.sql.CUBRIDTimestamptz value,
                final java.util.Calendar cal) {
            super(parameterIndex);
            this.value = value;
            this.cal = cal;
        }

        @Override
        void apply(final PreparedStatement target) throws SQLException {
            requireCubrid(target, "setTimestamptz").setTimestamptz(parameterIndex, value, cal);
        }
    }

    /* ===== vendor-only parameter setters =====*/
    /**
     * Narrows a physical prepared statement to the vendor type the CUBRID-only setters are declared
     * on. Every physical statement a session binds against is prepared by a CUBRID physical
     * connection, so this holds in production; the throw guards a delegate that is not one.
     *
     * @param target the physical prepared statement to narrow
     * @param methodName the setter being delegated, for the failure message
     * @return the same statement, typed for the vendor setter call
     * @throws SQLException if the delegate is not a CUBRID prepared statement
     */
    private static CUBRIDPreparedStatement requireCubrid(
            final PreparedStatement target, final String methodName) throws SQLException {
        if (!(target instanceof CUBRIDPreparedStatement)) {
            throw LbExceptions.physicalDelegateFailed("parameter:" + methodName, null);
        }

        return (CUBRIDPreparedStatement) target;
    }

    /* ===== ordering container =====*/
    /**
     * Records parameter operations keyed by parameter index, so re-binding the same index replaces
     * the previous value in place (last write wins) while first-insertion order is preserved for
     * deterministic replay. Map-backed: dedup and add are O(1), {@code snapshot} is O(n).
     */
    private static final class OperationList {
        private final LinkedHashMap<Integer, ParameterOperation> byIndex =
                new LinkedHashMap<Integer, ParameterOperation>();

        void add(final ParameterOperation operation) {
            // LinkedHashMap.put keeps an existing key's position and only replaces its value, which
            // matches the previous "set in place on re-bind" behavior.
            byIndex.put(Integer.valueOf(operation.parameterIndex), operation);
        }

        void addAllFrom(final OperationList other) {
            byIndex.putAll(other.byIndex);
        }

        void clear() {
            byIndex.clear();
        }

        int size() {
            return byIndex.size();
        }

        int maxParameterIndex() {
            int result = 0;
            for (Integer index : byIndex.keySet()) {
                if (index.intValue() > result) {
                    result = index.intValue();
                }
            }

            return result;
        }

        void replayTo(final PreparedStatement target) throws SQLException {
            for (ParameterOperation operation : byIndex.values()) {
                operation.apply(target);
            }
        }
    }
}
