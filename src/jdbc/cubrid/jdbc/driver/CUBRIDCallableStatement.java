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

package cubrid.jdbc.driver;

import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.jci.UErrorCode;
import cubrid.jdbc.jci.UStatement;
import cubrid.sql.CUBRIDTimestamptz;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.Map;

public class CUBRIDCallableStatement extends CUBRIDPreparedStatement implements CallableStatement {
    private boolean was_null;

    protected CUBRIDCallableStatement(CUBRIDConnection c, UStatement us) {
        super(
                c,
                us,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY,
                ResultSet.CLOSE_CURSORS_AT_COMMIT,
                Statement.NO_GENERATED_KEYS);
    }

    private String[] parameterNames;

    private int findParameter(String parameterName) throws SQLException {
        checkIsOpen();
        String[] names = parameterNames;
        if (names == null) {
            if (u_con.brokerProtocolVersion() < UConnection.PROTOCOL_V13
                    || u_con.isConnectedToProxy()
                    || u_con.isConnectedToGateway()) {
                throw CUBRIDException.notSupported(CUBRIDException.noParameterNamesMessage());
            }

            synchronized (con) {
                synchronized (u_stmt) {
                    names = u_stmt.getParameterNames();
                    error = u_stmt.getRecentError();
                }
            }
            if (names == null) {
                throw con.createCUBRIDException(error);
            }

            boolean named = false;
            for (String name : names) {
                named |= !name.isEmpty();
            }
            /* The server sends no names when it cannot read the routine, so ask again next time. */
            if (named) {
                parameterNames = names;
            }
        }

        for (int i = 0; i < names.length; i++) {
            if (!names[i].isEmpty() && names[i].equalsIgnoreCase(parameterName)) {
                return i + 1;
            }
        }
        throw con.createCUBRIDException(
                CUBRIDJDBCErrorCode.invalid_parameter_name, parameterName, null);
    }

    /*
     * java.sql.CallableStatement interface
     */

    public boolean wasNull() throws SQLException {
        checkIsOpen();
        return was_null;
    }

    public int getInt(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        int value;
        synchronized (u_stmt) {
            value = u_stmt.getInt(index);
            error = u_stmt.getRecentError();
        }

        checkGetXXXError();
        return value;
    }

    public String getString(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        Object obj;
        synchronized (u_stmt) {
            obj = u_stmt.getObject(index);
            error = u_stmt.getRecentError();
        }
        if (obj != null && obj instanceof Clob) {
            Clob clob = (Clob) obj;
            int length;
            if (clob.length() > (long) Integer.MAX_VALUE) {
                length = Integer.MAX_VALUE;
            } else {
                length = (int) clob.length();
            }
            return clob.getSubString(1, length);
        }

        String value;
        synchronized (u_stmt) {
            value = u_stmt.getString(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public boolean getBoolean(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        boolean value;
        synchronized (u_stmt) {
            value = u_stmt.getBoolean(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public byte getByte(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        byte value;
        synchronized (u_stmt) {
            value = u_stmt.getByte(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public short getShort(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        short value;
        synchronized (u_stmt) {
            value = u_stmt.getShort(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public long getLong(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        long value;
        synchronized (u_stmt) {
            value = u_stmt.getLong(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public float getFloat(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        float value;
        synchronized (u_stmt) {
            value = u_stmt.getFloat(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public double getDouble(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        double value;
        synchronized (u_stmt) {
            value = u_stmt.getDouble(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public byte[] getBytes(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        Object obj;
        synchronized (u_stmt) {
            obj = u_stmt.getObject(index);
            error = u_stmt.getRecentError();
        }
        if (obj != null && obj instanceof Blob) {
            Blob blob = (Blob) obj;
            int length;
            if (blob.length() > (long) Integer.MAX_VALUE) {
                length = Integer.MAX_VALUE;
            } else {
                length = (int) blob.length();
            }
            return blob.getBytes(1, length);
        }

        byte[] value;
        synchronized (u_stmt) {
            value = u_stmt.getBytes(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public Date getDate(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        Date value;
        synchronized (u_stmt) {
            value = u_stmt.getDate(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public Time getTime(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        Time value;
        synchronized (u_stmt) {
            value = u_stmt.getTime(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public Timestamp getTimestamp(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        Timestamp value;
        synchronized (u_stmt) {
            value = u_stmt.getTimestamp(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public Object getObject(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        Object value;
        synchronized (u_stmt) {
            value = u_stmt.getObject(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public BigDecimal getBigDecimal(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        BigDecimal value;
        synchronized (u_stmt) {
            value = u_stmt.getBigDecimal(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    @Deprecated
    public BigDecimal getBigDecimal(int index, int scale) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public Ref getRef(int i) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public Blob getBlob(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        Blob value;
        synchronized (u_stmt) {
            value = u_stmt.getBlob(index);
            error = u_stmt.getRecentError();
        }

        checkGetXXXError();
        return value;
    }

    public Clob getClob(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        Clob value;
        synchronized (u_stmt) {
            value = u_stmt.getClob(index);
            error = u_stmt.getRecentError();
        }

        checkGetXXXError();
        return value;
    }

    public Array getArray(int i) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public Date getDate(int index, Calendar cal) throws SQLException {
        if (cal == null) {
            return getDate(index);
        }
        checkIsOpen();
        beforeGetValue(index);

        Date value;
        synchronized (u_stmt) {
            value = u_stmt.getDate(index, cal.getTimeZone());
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public Time getTime(int index, Calendar cal) throws SQLException {
        if (cal == null) {
            return getTime(index);
        }
        checkIsOpen();
        beforeGetValue(index);

        Time value;
        synchronized (u_stmt) {
            value = u_stmt.getTime(index, cal.getTimeZone());
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public Timestamp getTimestamp(int index, Calendar cal) throws SQLException {
        if (cal == null) {
            return getTimestamp(index);
        }
        checkIsOpen();
        beforeGetValue(index);

        Timestamp value;
        synchronized (u_stmt) {
            value = u_stmt.getTimestamp(index, cal.getTimeZone());
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    public URL getURL(int index) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public void setURL(String pName, URL val) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public void setNull(String pName, int sqlType) throws SQLException {
        setNull(findParameter(pName), sqlType);
    }

    public void setBoolean(String pName, boolean x) throws SQLException {
        setBoolean(findParameter(pName), x);
    }

    public void setByte(String pName, byte x) throws SQLException {
        setByte(findParameter(pName), x);
    }

    public void setShort(String pName, short x) throws SQLException {
        setShort(findParameter(pName), x);
    }

    public void setInt(String pName, int x) throws SQLException {
        setInt(findParameter(pName), x);
    }

    public void setLong(String pName, long x) throws SQLException {
        setLong(findParameter(pName), x);
    }

    public void setFloat(String pName, float x) throws SQLException {
        setFloat(findParameter(pName), x);
    }

    public void setDouble(String pName, double x) throws SQLException {
        setDouble(findParameter(pName), x);
    }

    public void setBigDecimal(String pName, BigDecimal x) throws SQLException {
        setBigDecimal(findParameter(pName), x);
    }

    public void setString(String pName, String x) throws SQLException {
        setString(findParameter(pName), x);
    }

    public void setBytes(String pName, byte[] x) throws SQLException {
        setBytes(findParameter(pName), x);
    }

    public void setDate(String pName, Date x) throws SQLException {
        setDate(findParameter(pName), x);
    }

    public void setTime(String pName, Time x) throws SQLException {
        setTime(findParameter(pName), x);
    }

    public void setTimestamp(String pName, Timestamp x) throws SQLException {
        setTimestamp(findParameter(pName), x);
    }

    public void setTimestamptz(String pName, CUBRIDTimestamptz x) throws SQLException {
        setTimestamptz(findParameter(pName), x);
    }

    public void setAsciiStream(String pName, InputStream x, int length) throws SQLException {
        setAsciiStream(findParameter(pName), x, length);
    }

    public void setBinaryStream(String pName, InputStream x, int length) throws SQLException {
        setBinaryStream(findParameter(pName), x, length);
    }

    public void setObject(String pName, Object x, int targetSqlType, int scale)
            throws SQLException {
        setObject(findParameter(pName), x, targetSqlType, scale);
    }

    public void setObject(String pName, Object x, int targetSqlType) throws SQLException {
        setObject(findParameter(pName), x, targetSqlType);
    }

    public void setObject(String pName, Object x) throws SQLException {
        setObject(findParameter(pName), x);
    }

    public void setCharacterStream(String pName, Reader reader, int length) throws SQLException {
        setCharacterStream(findParameter(pName), reader, length);
    }

    public void setDate(String pName, Date x, Calendar cal) throws SQLException {
        setDate(findParameter(pName), x, cal);
    }

    public void setTime(String pName, Time x, Calendar cal) throws SQLException {
        setTime(findParameter(pName), x, cal);
    }

    public void setTimestamp(String pName, Timestamp x, Calendar cal) throws SQLException {
        setTimestamp(findParameter(pName), x, cal);
    }

    public void setTimestamptz(String pName, CUBRIDTimestamptz x, Calendar cal)
            throws SQLException {
        setTimestamptz(findParameter(pName), x, cal);
    }

    public void setNull(String pName, int sqlType, String typeName) throws SQLException {
        setNull(findParameter(pName), sqlType, typeName);
    }

    public String getString(String pName) throws SQLException {
        return getString(findParameter(pName));
    }

    public boolean getBoolean(String pName) throws SQLException {
        return getBoolean(findParameter(pName));
    }

    public byte getByte(String pName) throws SQLException {
        return getByte(findParameter(pName));
    }

    public short getShort(String pName) throws SQLException {
        return getShort(findParameter(pName));
    }

    public int getInt(String pName) throws SQLException {
        return getInt(findParameter(pName));
    }

    public long getLong(String pName) throws SQLException {
        return getLong(findParameter(pName));
    }

    public float getFloat(String pName) throws SQLException {
        return getFloat(findParameter(pName));
    }

    public double getDouble(String pName) throws SQLException {
        return getDouble(findParameter(pName));
    }

    public byte[] getBytes(String pName) throws SQLException {
        return getBytes(findParameter(pName));
    }

    public Date getDate(String pName) throws SQLException {
        return getDate(findParameter(pName));
    }

    public Time getTime(String pName) throws SQLException {
        return getTime(findParameter(pName));
    }

    public Timestamp getTimestamp(String pName) throws SQLException {
        return getTimestamp(findParameter(pName));
    }

    public Object getObject(String pName) throws SQLException {
        return getObject(findParameter(pName));
    }

    public Object getObject(int i, Map<String, Class<?>> map) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public Object getObject(String pName, Map<String, Class<?>> map) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public BigDecimal getBigDecimal(String pName) throws SQLException {
        return getBigDecimal(findParameter(pName));
    }

    public Ref getRef(String pName) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public Blob getBlob(String pName) throws SQLException {
        return getBlob(findParameter(pName));
    }

    public Clob getClob(String pName) throws SQLException {
        return getClob(findParameter(pName));
    }

    public Array getArray(String pName) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public Date getDate(String pName, Calendar cal) throws SQLException {
        return getDate(findParameter(pName), cal);
    }

    public Time getTime(String pName, Calendar cal) throws SQLException {
        return getTime(findParameter(pName), cal);
    }

    public Timestamp getTimestamp(String pName, Calendar cal) throws SQLException {
        return getTimestamp(findParameter(pName), cal);
    }

    public URL getURL(String pName) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public void registerOutParameter(int index, int sqlType) throws SQLException {
        doRegisterOutParameter(index, sqlType);
    }

    public void registerOutParameter(int index, int sqlType, int scale) throws SQLException {
        doRegisterOutParameter(index, sqlType);
    }

    public void registerOutParameter(int index, int sqlType, String typeName) throws SQLException {
        doRegisterOutParameter(index, sqlType);
    }

    public void registerOutParameter(String pName, int sqlType) throws SQLException {
        registerOutParameter(findParameter(pName), sqlType);
    }

    public void registerOutParameter(String pName, int sqlType, int scale) throws SQLException {
        registerOutParameter(findParameter(pName), sqlType, scale);
    }

    public void registerOutParameter(String pName, int sqlType, String typeName)
            throws SQLException {
        registerOutParameter(findParameter(pName), sqlType, typeName);
    }

    // Batch execution is not supported for CallableStatement, which represents a stored
    // procedure call with OUT/INOUT parameters. Overrides the inherited PreparedStatement
    // batch methods to reject it.
    public int[] executeBatch() throws SQLException {
        throw CUBRIDException.notSupported();
    }

    // See executeBatch(): batch is not supported for CallableStatement.
    public void addBatch() throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /*
     * ======================================================================= |
     * PRIVATE METHODS
     * =======================================================================
     */

    private void doRegisterOutParameter(int index, int sqlType) throws SQLException {
        checkIsOpen();
        synchronized (u_stmt) {
            u_stmt.registerOutParameter(index - 1, sqlType);
            error = u_stmt.getRecentError();
        }

        checkBindError();
    }

    private LocalDate getLocalDate(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        LocalDate value;
        synchronized (u_stmt) {
            value = u_stmt.getLocalDate(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    private LocalTime getLocalTime(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        LocalTime value;
        synchronized (u_stmt) {
            value = u_stmt.getLocalTime(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    private LocalDateTime getLocalDateTime(int index) throws SQLException {
        checkIsOpen();
        beforeGetValue(index);

        LocalDateTime value;
        synchronized (u_stmt) {
            value = u_stmt.getLocalDateTime(index);
            error = u_stmt.getRecentError();
        }
        checkGetXXXError();
        return value;
    }

    private void beforeGetValue(int index) throws SQLException {
        if (index < 0 || index > u_stmt.getParameterCount()) {
            throw con.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_index, null);
        }

        synchronized (u_stmt) {
            u_stmt.fetch();
            error = u_stmt.getRecentError();
        }

        switch (error.getErrorCode()) {
            case UErrorCode.ER_NO_ERROR:
                break;
            default:
                throw con.createCUBRIDException(error);
        }
    }

    void checkGetXXXError() throws SQLException {
        switch (error.getErrorCode()) {
            case UErrorCode.ER_NO_ERROR:
                was_null = false;
                break;
            case UErrorCode.ER_WAS_NULL:
                was_null = true;
                break;
            default:
                throw con.createCUBRIDException(error);
        }
    }

    /* JDK 1.6 */
    public Reader getCharacterStream(int parameterIndex) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public Reader getCharacterStream(String parameterName) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public Reader getNCharacterStream(int parameterIndex) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public Reader getNCharacterStream(String parameterName) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public NClob getNClob(int parameterIndex) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public NClob getNClob(String parameterName) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public String getNString(int parameterIndex) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public String getNString(String parameterName) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public RowId getRowId(int parameterIndex) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public RowId getRowId(String parameterName) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public SQLXML getSQLXML(int parameterIndex) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public SQLXML getSQLXML(String parameterName) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setAsciiStream(String parameterName, InputStream x) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setAsciiStream(String parameterName, InputStream x, long length)
            throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setBinaryStream(String parameterName, InputStream x) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setBinaryStream(String parameterName, InputStream x, long length)
            throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setBlob(String parameterName, Blob x) throws SQLException {
        setBlob(findParameter(parameterName), x);
    }

    /* JDK 1.6 */
    public void setBlob(String parameterName, InputStream inputStream) throws SQLException {
        setBlob(findParameter(parameterName), inputStream);
    }

    /* JDK 1.6 */
    public void setBlob(String parameterName, InputStream inputStream, long length)
            throws SQLException {
        setBlob(findParameter(parameterName), inputStream, length);
    }

    /* JDK 1.6 */
    public void setCharacterStream(String parameterName, Reader reader) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setCharacterStream(String parameterName, Reader reader, long length)
            throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setClob(String parameterName, Clob x) throws SQLException {
        setClob(findParameter(parameterName), x);
    }

    /* JDK 1.6 */
    public void setClob(String parameterName, Reader reader) throws SQLException {
        setClob(findParameter(parameterName), reader);
    }

    /* JDK 1.6 */
    public void setClob(String parameterName, Reader reader, long length) throws SQLException {
        setClob(findParameter(parameterName), reader, length);
    }

    /* JDK 1.6 */
    public void setNCharacterStream(String parameterName, Reader value) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setNCharacterStream(String parameterName, Reader value, long length)
            throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setNClob(String parameterName, NClob value) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setNClob(String parameterName, Reader reader) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setNClob(String parameterName, Reader reader, long length) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setNString(String parameterName, String value) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setRowId(String parameterName, RowId x) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.7 */
    public void closeOnCompletion() throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.7 */
    public boolean isCloseOnCompletion() throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.7 */
    public <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {
        checkIsOpen();
        if (type == null) {
            throw con.createCUBRIDException(
                    CUBRIDJDBCErrorCode.invalid_value, CUBRIDException.nullTypeMessage(), null);
        }

        if (type == String.class) {
            return type.cast(getString(parameterIndex));
        }
        if (type == BigDecimal.class) {
            return type.cast(getBigDecimal(parameterIndex));
        }
        if (type == byte[].class) {
            return type.cast(getBytes(parameterIndex));
        }
        if (type == Date.class) {
            return type.cast(getDate(parameterIndex));
        }
        if (type == Time.class) {
            return type.cast(getTime(parameterIndex));
        }
        if (type == Timestamp.class) {
            return type.cast(getTimestamp(parameterIndex));
        }
        if (type == Blob.class) {
            return type.cast(getBlob(parameterIndex));
        }
        if (type == Clob.class) {
            return type.cast(getClob(parameterIndex));
        }

        if (type == Boolean.class) {
            boolean value = getBoolean(parameterIndex);
            return wasNull() ? null : type.cast(value);
        }
        if (type == Byte.class) {
            byte value = getByte(parameterIndex);
            return wasNull() ? null : type.cast(value);
        }
        if (type == Short.class) {
            short value = getShort(parameterIndex);
            return wasNull() ? null : type.cast(value);
        }
        if (type == Integer.class) {
            int value = getInt(parameterIndex);
            return wasNull() ? null : type.cast(value);
        }
        if (type == Long.class) {
            long value = getLong(parameterIndex);
            return wasNull() ? null : type.cast(value);
        }
        if (type == Float.class) {
            float value = getFloat(parameterIndex);
            return wasNull() ? null : type.cast(value);
        }
        if (type == Double.class) {
            double value = getDouble(parameterIndex);
            return wasNull() ? null : type.cast(value);
        }

        if (type == LocalDate.class) {
            return type.cast(getLocalDate(parameterIndex));
        }
        if (type == LocalTime.class) {
            return type.cast(getLocalTime(parameterIndex));
        }
        if (type == LocalDateTime.class) {
            return type.cast(getLocalDateTime(parameterIndex));
        }

        throw con.createCUBRIDException(
                CUBRIDJDBCErrorCode.invalid_value,
                CUBRIDException.cannotConvertMessage(type),
                null);
    }

    /* JDK 1.7 */
    public <T> T getObject(String parameterName, Class<T> type) throws SQLException {
        return getObject(findParameter(parameterName), type);
    }

    // ------------------------- JDBC 4.2 -----------------------------------

    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType) throws SQLException {
        registerOutParameter(parameterIndex, checkSqlType(sqlType));
    }

    /* As with the int-based overload, scale is ignored. */
    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType, int scale)
            throws SQLException {
        registerOutParameter(parameterIndex, checkSqlType(sqlType), scale);
    }

    /*
     * As with the int-based overload, typeName is ignored: the JDBC spec allows a
     * driver that does not need the type name information to ignore it, and it
     * applies only to user-defined (STRUCT/DISTINCT/JAVA_OBJECT) and REF
     * parameters, which CUBRID does not support.
     */
    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType, String typeName)
            throws SQLException {
        registerOutParameter(parameterIndex, checkSqlType(sqlType), typeName);
    }

    @Override
    public void setObject(String parameterName, Object x, SQLType targetSqlType, int scaleOrLength)
            throws SQLException {
        setObject(findParameter(parameterName), x, targetSqlType, scaleOrLength);
    }

    @Override
    public void setObject(String parameterName, Object x, SQLType targetSqlType)
            throws SQLException {
        setObject(findParameter(parameterName), x, targetSqlType);
    }

    @Override
    public void registerOutParameter(String parameterName, SQLType sqlType) throws SQLException {
        registerOutParameter(findParameter(parameterName), sqlType);
    }

    @Override
    public void registerOutParameter(String parameterName, SQLType sqlType, int scale)
            throws SQLException {
        registerOutParameter(findParameter(parameterName), sqlType, scale);
    }

    @Override
    public void registerOutParameter(String parameterName, SQLType sqlType, String typeName)
            throws SQLException {
        registerOutParameter(findParameter(parameterName), sqlType, typeName);
    }
}
