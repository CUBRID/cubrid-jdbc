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

import cubrid.jdbc.jci.UGetTypeConvertedValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.sql.Blob;
import java.sql.SQLException;

/**
 * A BLOB value: an internal LOB. An external LOB (BFILE) is {@link CUBRIDBfile}.
 *
 * <p>Read from a column it is a reference (the byte length and the locator naming it) whose bytes
 * are pulled from the server on demand; a scalar function result carries its content inline. Made
 * by {@link CUBRIDConnection#createBlob()} it is written front to back and the bytes go to the
 * server as they come, without being held here.
 */
public class CUBRIDBlob implements Blob {
    private CUBRIDConnection conn;

    private byte[] internalLocator = null;
    private byte[] internalContent = null;
    private long internalLength = 0;
    private CUBRIDInternalLobUpload upload = null;

    /* made by Connection.createBlob (): an empty value to write */
    public CUBRIDBlob(CUBRIDConnection conn) throws SQLException {
        if (conn == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        this.conn = conn;
        this.upload = new CUBRIDInternalLobUpload(conn, true);
    }

    /* read from a result set: the column carried a locator, not the content */
    public CUBRIDBlob(CUBRIDConnection conn, long byteLength, byte[] locator) throws SQLException {
        if (conn == null || locator == null || byteLength < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        this.conn = conn;
        this.internalLocator = locator;
        this.internalLength = byteLength;
    }

    /* a value with no storage behind it (a scalar function result, or other data read as a Blob) */
    public CUBRIDBlob(CUBRIDConnection conn, byte[] content) throws SQLException {
        if (conn == null || content == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        this.conn = conn;
        this.internalContent = content;
        this.internalLength = content.length;
    }

    /* package-visible for CUBRIDPreparedStatement.setBlob (Blob), which binds the upload token */
    CUBRIDInternalLobUpload getUpload() {
        return upload;
    }

    /*
     * ======================================================================= |
     * java.sql.Blob interface
     * =======================================================================
     */
    public long length() throws SQLException {
        checkFreed();
        if (upload != null) {
            return upload.length();
        }
        return internalLength;
    }

    /* Reads a window of the value by streaming to it.  The server cursor is forward-only, so a non-zero
     * start costs a skip; sequential readers should prefer getBinaryStream(). */
    public byte[] getBytes(long pos, int length) throws SQLException {
        checkFreed();
        if (pos < 1 || length < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        long remaining = length() - (pos - 1);
        if (length == 0 || remaining <= 0) {
            return new byte[0];
        }
        if (length > remaining) {
            length = (int) remaining;
        }

        byte[] buf = new byte[length];
        InputStream in = getBinaryStream(pos, length);
        int total = 0;

        try {
            while (total < length) {
                int got = in.read(buf, total, length - total);
                if (got <= 0) {
                    break;
                }
                total += got;
            }
        } catch (IOException e) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.ioexception_in_stream, e);
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                /* the read already produced its result; a failed close adds nothing the caller can act on */
            }
        }

        if (total < buf.length) {
            byte[] exact = new byte[total];
            System.arraycopy(buf, 0, exact, 0, total);
            return exact;
        }
        return buf;
    }

    public InputStream getBinaryStream() throws SQLException {
        return getBinaryStream(1, length());
    }

    /* JDK 1.6 */
    public InputStream getBinaryStream(long pos, long length) throws SQLException {
        checkFreed();
        if (pos < 1 || length < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (upload != null) {
            /* the bytes went to the server as they were written; nothing here can read them back */
            throw CUBRIDException.notSupported();
        }

        if (internalContent != null) {
            int from = (int) Math.min(pos - 1, internalContent.length);
            int avail = internalContent.length - from;
            int span = (length < avail) ? (int) length : avail;
            return new java.io.ByteArrayInputStream(internalContent, from, span);
        }

        /* the server positions its own cursor, so the bytes before pos never cross the network;
         * length bounds the window per JDBC 4.0 */
        return new CUBRIDInternalLobInputStream(
                conn.getUConnection(), internalLocator, internalLength, pos - 1, length);
    }

    public long position(byte[] pattern, long start) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public long position(Blob pattern, long start) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public int setBytes(long pos, byte[] bytes) throws SQLException {
        return (setBytes(pos, bytes, 0, bytes.length));
    }

    /* only appends: the bytes before pos are already on the server */
    public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        checkWritable(pos);
        if (offset < 0 || len < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (offset + len > bytes.length) {
            throw new IndexOutOfBoundsException();
        }

        upload.write(bytes, offset, len);
        return len;
    }

    /* closing the stream finishes the value */
    public OutputStream setBinaryStream(long pos) throws SQLException {
        checkWritable(pos);
        return upload.outputStream();
    }

    public void truncate(long len) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public void free() throws SQLException {
        if (upload != null) {
            upload.abort();
        }
        conn = null;
        upload = null;
        internalLocator = null;
        internalContent = null;
    }

    private void checkFreed() throws SQLException {
        if (conn == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
    }

    private void checkWritable(long pos) throws SQLException {
        checkFreed();
        if (upload == null || upload.isFinished()) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null);
        }
        if (pos != upload.length() + 1) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_pos_invalid, null);
        }
    }

    /* like csql: a stored value shows its locator, one with no storage its content in hex */
    public String toString() {
        if (internalLocator != null) {
            return new String(internalLocator, Charset.forName("US-ASCII"));
        }
        if (internalContent != null) {
            return UGetTypeConvertedValue.getHexaDecimalString(internalContent);
        }
        return "CUBRIDBlob[internal, length=" + (upload != null ? upload.length() : 0) + "]";
    }

    public int hashCode() {
        if (upload != null) {
            return System.identityHashCode(this);
        }
        return 31 * java.util.Arrays.hashCode(internalLocator)
                + java.util.Arrays.hashCode(internalContent);
    }

    public boolean equals(Object obj) {
        if (obj instanceof CUBRIDBlob) {
            CUBRIDBlob that = (CUBRIDBlob) obj;
            if (upload != null || that.upload != null) {
                return this == that;
            }
            return java.util.Arrays.equals(internalLocator, that.internalLocator)
                    && java.util.Arrays.equals(internalContent, that.internalContent);
        }
        return false;
    }
}
