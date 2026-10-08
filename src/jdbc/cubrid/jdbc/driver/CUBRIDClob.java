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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.sql.Clob;
import java.sql.SQLException;

/**
 * A CLOB value: an internal LOB. An external LOB (CFILE) is {@link CUBRIDCfile}.
 *
 * <p>Read from a column it is a reference (the byte length and the locator naming it) whose
 * characters are decoded from the server stream on demand; a scalar function result carries its
 * content inline. Made by {@link CUBRIDConnection#createClob()} it is written front to back and the
 * encoded text goes to the server as it comes, without being held here.
 */
public class CUBRIDClob implements Clob {
    private static final int CLOB_MAX_IO_CHARS = 128 * 1024;

    private CUBRIDConnection conn;
    private String charsetName;

    private byte[] internalLocator = null;
    private byte[] internalContent = null;
    private long internalLength = 0;
    private CUBRIDInternalLobUpload upload = null;
    private Writer uploadWriter = null;
    private long charsWritten = 0;

    /* where getSubString () can go on without starting over: its open reader, and the end of its last window */
    private Reader seqReader = null;
    private long seqPos = 0;
    private long lastEnd = 0;
    private long knownChars = -1;

    /* made by Connection.createClob (): an empty value to write */
    public CUBRIDClob(CUBRIDConnection conn, String charsetName) throws SQLException {
        if (conn == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        this.conn = conn;
        this.charsetName = charsetName;
        this.upload = new CUBRIDInternalLobUpload(conn, false);
        this.uploadWriter =
                new OutputStreamWriter(upload.outputStream(), Charset.forName(charsetName));
    }

    /* read from a result set: the column carried a locator, not the content */
    public CUBRIDClob(CUBRIDConnection conn, long byteLength, byte[] locator, String charsetName)
            throws SQLException {
        if (conn == null || locator == null || byteLength < 0) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        this.conn = conn;
        this.internalLocator = locator;
        this.internalLength = byteLength;
        this.charsetName = charsetName;
    }

    /* a value with no storage behind it (a scalar function result, or other data read as a Clob) */
    public CUBRIDClob(CUBRIDConnection conn, byte[] content, String charsetName)
            throws SQLException {
        if (conn == null || content == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }

        this.conn = conn;
        this.internalContent = content;
        this.internalLength = content.length;
        this.charsetName = charsetName;
    }

    /* Package-visible for CUBRIDPreparedStatement.setClob (Clob): pushes the text still being encoded
     * and returns the upload, finished. */
    CUBRIDInternalLobUpload getUpload() {
        return upload;
    }

    synchronized CUBRIDInternalLobUpload finishUpload() throws SQLException {
        if (upload == null) {
            return null;
        }
        if (!upload.isFinished()) {
            try {
                /* close, not flush: it also encodes a dangling high surrogate, and closing the upload
                 * stream finishes the upload */
                uploadWriter.close();
            } catch (IOException e) {
                upload.abort();
                if (e.getCause() instanceof SQLException) {
                    throw (SQLException) e.getCause();
                }
                throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.ioexception_in_stream, e);
            }
        }
        upload.finish();
        return upload;
    }

    /* A failed write leaves the encoder's buffered bytes unknown, so the value is given up, not retried. */
    private synchronized void writeChars(char[] cbuf, int off, int len) throws IOException {
        if (upload.isFinished()) {
            throw new IOException(
                    conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null));
        }
        try {
            uploadWriter.write(cbuf, off, len);
        } catch (IOException e) {
            upload.abort();
            throw e;
        }
        charsWritten += len;
    }

    /* A charset whose characters are one byte each lets a character offset be used as a byte offset. */
    private boolean isSingleByteCharset() {
        return charsetName != null
                && (charsetName.equalsIgnoreCase("ISO-8859-1")
                        || charsetName.equalsIgnoreCase("US-ASCII")
                        || charsetName.equalsIgnoreCase("ASCII"));
    }

    /*
     * ======================================================================= |
     * java.sql.Clob interface
     * =======================================================================
     */
    /* A value read back is measured in bytes, as the server's CLOB_LENGTH is: a character count would
     * cost a full read.  A value being written counts the characters written, the unit setString's
     * position takes. */
    public long length() throws SQLException {
        checkFreed();
        if (upload != null) {
            return charsWritten;
        }
        return internalLength;
    }

    /* Reads a window of the value by streaming to it.  pos counts characters, so for a multi-byte charset
     * the characters before it are decoded and dropped; windows read in order go on with one reader
     * instead of starting over each time. */
    public synchronized String getSubString(long pos, int length) throws SQLException {
        checkReadable();
        if (pos < 1 || length < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }
        if (length == 0) {
            return "";
        }

        if (internalContent != null || isSingleByteCharset()) {
            Reader in = getCharacterStream(pos, length);
            try {
                return readChars(in, length);
            } finally {
                closeQuietly(in);
            }
        }

        if (knownChars >= 0 && pos > knownChars) {
            return "";
        }
        if (seqReader != null && pos == seqPos) {
            try {
                return readOn(length);
            } catch (SQLException e) {
                /* the cursor is gone with its transaction; start over below */
                closeSeqReader();
            }
        } else {
            closeSeqReader();
        }

        Reader in = openReader(pos);
        if (pos != lastEnd) {
            /* a lone window keeps no cursor open */
            try {
                String s = readChars(in, length);
                lastEnd = pos + s.length();
                if (s.length() < length) {
                    knownChars = lastEnd - 1;
                }
                return s;
            } finally {
                closeQuietly(in);
            }
        }
        seqReader = in;
        seqPos = pos;
        return readOn(length);
    }

    private String readOn(int length) throws SQLException {
        String s;
        try {
            s = readChars(seqReader, length);
        } catch (SQLException e) {
            closeSeqReader();
            throw e;
        }
        seqPos += s.length();
        lastEnd = seqPos;
        if (s.length() < length) {
            knownChars = seqPos - 1;
            closeSeqReader();
        }
        return s;
    }

    private String readChars(Reader in, int length) throws SQLException {
        char[] buf = new char[length];
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
        }
        return new String(buf, 0, total);
    }

    private void closeSeqReader() {
        if (seqReader != null) {
            closeQuietly(seqReader);
            seqReader = null;
        }
    }

    private static void closeQuietly(Reader in) {
        try {
            in.close();
        } catch (IOException e) {
            /* the read already produced its result; a failed close adds nothing the caller can act on */
        }
    }

    public Reader getCharacterStream() throws SQLException {
        return getCharacterStream(1, length());
    }

    /* JDK 1.6 */
    public Reader getCharacterStream(long pos, long length) throws SQLException {
        checkReadable();
        if (pos < 1 || length < 0) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }

        if (internalContent != null) {
            String whole = new String(internalContent, Charset.forName(charsetName));
            int from = (int) Math.min(pos - 1, whole.length());
            String tail = whole.substring(from);
            if (length < tail.length()) {
                tail = tail.substring(0, (int) length);
            }
            return new java.io.StringReader(tail);
        }

        /* JDBC 4.0: the reader must be exactly `length` characters long. Counting characters is
         * charset-agnostic, unlike bounding the underlying byte stream. */
        return new CUBRIDBufferedReader(
                new BoundedReader(openReader(pos), length), CLOB_MAX_IO_CHARS);
    }

    /* A reader of the stored value from character pos on.  pos counts characters while the server cursor
     * counts bytes: for a single-byte charset the server positions itself, otherwise the characters ahead
     * are read and dropped here. */
    private Reader openReader(long pos) throws SQLException {
        boolean bytePerChar = isSingleByteCharset();
        Reader in =
                new InputStreamReader(
                        new CUBRIDInternalLobInputStream(
                                conn.getUConnection(),
                                internalLocator,
                                internalLength,
                                bytePerChar ? pos - 1 : 0),
                        Charset.forName(charsetName));
        if (!bytePerChar && pos > 1) {
            long toSkip = pos - 1;
            try {
                while (toSkip > 0) {
                    long skipped = in.skip(toSkip);
                    if (skipped <= 0) {
                        break;
                    }
                    toSkip -= skipped;
                }
            } catch (IOException e) {
                closeQuietly(in);
                throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.ioexception_in_stream, e);
            }
        }
        return in;
    }

    /* the stored bytes as they are, in the database charset */
    public InputStream getAsciiStream() throws SQLException {
        checkReadable();
        if (internalContent != null) {
            return new java.io.ByteArrayInputStream(internalContent);
        }
        return new CUBRIDInternalLobInputStream(
                conn.getUConnection(), internalLocator, internalLength, 0);
    }

    public long position(String searchstr, long start) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public long position(Clob searchstr, long start) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    public int setString(long pos, String str) throws SQLException {
        return setString(pos, str, 0, str.length());
    }

    /* only appends: the text before pos is already on its way to the server */
    public int setString(long pos, String str, int offset, int len) throws SQLException {
        checkWritable(pos);
        if (offset < 0 || len < 0 || offset + len > str.length()) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.invalid_value, null);
        }

        char[] chars = new char[len];
        str.getChars(offset, offset + len, chars, 0);
        try {
            writeChars(chars, 0, len);
        } catch (IOException e) {
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            }
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.ioexception_in_stream, e);
        }
        return len;
    }

    /* closing the stream finishes the value */
    public OutputStream setAsciiStream(long pos) throws SQLException {
        checkWritable(pos);
        final OutputStream raw = upload.outputStream();
        return new OutputStream() {
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            public void write(byte[] b, int off, int len) throws IOException {
                synchronized (CUBRIDClob.this) {
                    try {
                        uploadWriter.flush();
                        raw.write(b, off, len);
                    } catch (IOException e) {
                        upload.abort();
                        throw e;
                    }
                    charsWritten += len;
                }
            }

            public void close() throws IOException {
                try {
                    finishUpload();
                } catch (SQLException e) {
                    throw new IOException(e);
                }
            }
        };
    }

    /* closing the stream finishes the value */
    public Writer setCharacterStream(long pos) throws SQLException {
        checkWritable(pos);
        return new Writer() {
            public void write(char[] cbuf, int off, int len) throws IOException {
                writeChars(cbuf, off, len);
            }

            public void flush() throws IOException {
                /* the upload sends full chunks on its own; finishing pushes the rest */
            }

            public void close() throws IOException {
                try {
                    finishUpload();
                } catch (SQLException e) {
                    throw new IOException(e);
                }
            }
        };
    }

    public void truncate(long len) throws SQLException {
        throw CUBRIDException.notSupported();
    }

    /* JDK 1.6 */
    public synchronized void free() throws SQLException {
        if (upload != null) {
            upload.abort();
        }
        closeSeqReader();
        conn = null;
        upload = null;
        uploadWriter = null;
        internalLocator = null;
        internalContent = null;
    }

    private void checkFreed() throws SQLException {
        if (conn == null) {
            throw new CUBRIDException(CUBRIDJDBCErrorCode.invalid_value);
        }
    }

    private void checkReadable() throws SQLException {
        checkFreed();
        if (upload != null) {
            /* the text went to the server as it was written; nothing here can read it back */
            throw CUBRIDException.notSupported();
        }
    }

    private void checkWritable(long pos) throws SQLException {
        checkFreed();
        if (upload == null || upload.isFinished()) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_is_not_writable, null);
        }
        if (pos != charsWritten + 1) {
            throw conn.createCUBRIDException(CUBRIDJDBCErrorCode.lob_pos_invalid, null);
        }
    }

    /* like csql: a stored value shows its locator, one with no storage its content */
    public String toString() {
        if (internalLocator != null) {
            return new String(internalLocator, Charset.forName("US-ASCII"));
        }
        if (internalContent != null) {
            return new String(internalContent, Charset.forName(charsetName));
        }
        return "CUBRIDClob[internal, length=" + charsWritten + "]";
    }

    public int hashCode() {
        if (upload != null) {
            return System.identityHashCode(this);
        }
        return 31 * java.util.Arrays.hashCode(internalLocator)
                + java.util.Arrays.hashCode(internalContent);
    }

    public boolean equals(Object obj) {
        if (obj instanceof CUBRIDClob) {
            CUBRIDClob that = (CUBRIDClob) obj;
            if (upload != null || that.upload != null) {
                return this == that;
            }
            return java.util.Arrays.equals(internalLocator, that.internalLocator)
                    && java.util.Arrays.equals(internalContent, that.internalContent);
        }
        return false;
    }

    /* Caps a Reader at a fixed number of characters, so getCharacterStream(pos, length) yields exactly
     * `length` characters regardless of the underlying charset's bytes-per-char. */
    private static class BoundedReader extends java.io.FilterReader {
        private long remaining;

        BoundedReader(Reader in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int c = super.read();
            if (c >= 0) {
                remaining--;
            }
            return c;
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int want = (len < remaining) ? len : (int) remaining;
            int got = super.read(cbuf, off, want);
            if (got > 0) {
                remaining -= got;
            }
            return got;
        }
    }
}
