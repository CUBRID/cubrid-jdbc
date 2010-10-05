/*
 * Copyright (C) 2008 Search Solution Corporation. All rights reserved by Search Solution.
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

package @CUBRID_SQL@;

/**
 * Title:        CUBRID JDBC Driver
 * Description:
 * @version 2.0
 */

import @CUBRID_JCI@.*;
import @CUBRID_DRIVER@.*;
import java.sql.*;
import java.io.*;
import java.util.StringTokenizer;
import java.util.NoSuchElementException;

import @CUBRID_DRIVER@.CUBRIDBlob;
import @CUBRID_DRIVER@.CUBRIDClob;
import @CUBRID_DRIVER@.CUBRIDResultSet;
import @CUBRID_SQL@.CUBRIDOID;

public class CUBRIDOID
{
  public final static int GLO_MAX_SEND_SIZE = 16000;
  protected final static int GLO_MAX_SEARCH_LEN = 4096;

  private CUBRIDConnection cur_con;
  private byte[] oid;
  private boolean is_closed;
  private UError error;

  /*
   * Just for Driver's uses. DO NOT create an object with this constructor!
   */
  public CUBRIDOID(CUBRIDConnection con, byte[] o)
  {
    cur_con = con;
    oid = o;
    is_closed = false;
  }

  public CUBRIDOID(CUBRIDOID o)
  {
    cur_con = o.cur_con;
    oid = o.oid;
    is_closed = false;
  }

  /**
   * <code>this</code> object�� ����Ű�� �ִ� database�� object�κ��� �־���
   * attribute���� ������ �����´�. <code>attrNames</code>�� <code>null</code>
   * �̸� ��� attribute���� ���� �����´�.
   *
   * @param attrNames
   *          ���� ���������� �ϴ� attribute���� �̸��� ���� array
   * @return 1���� row�� <code>attrNames.length</code>�� �Ǵ� ��� column�� ����
   *         <code>CUBRIDResultSet</code> object�� return�ȴ�. column �� ������
   *         attrNames�� ����� column �̸����� ������ ����.
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized ResultSet getValues(String attrNames[])
      throws SQLException
  {
    checkIsOpen();

    UStatement u_stmt = null;
    UConnection u_con = cur_con.getUConnection();
    synchronized (u_con)
    {
      u_stmt = u_con.getByOID(this, attrNames);
      error = u_con.getRecentError();
    }

    checkError();
    return new CUBRIDResultSet(u_stmt);
  }

  /**
   * <code>this</code> object�� ����Ű�� �ִ� database�� object�� ���� �־���
   * ������ �����Ѵ�.
   *
   * @param attrNames
   *          ���� �����ϰ��� �ϴ� attribute���� �̸��� ���� array
   * @param values
   *          attribute���� �̸��� �ش��ϴ� ���� ���� array
   * @exception IllegalArgumentException
   *              if <code>attrNames</code> is <code>null</code>,
   *              <code>values</code> is <code>null</code> or
   *              <code>attrNames.length</code> is not same with
   *              <code>values.length</code>
   * @exception SQLException
   *              if type conversion fails
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void setValues(String[] attrNames, Object[] values)
      throws SQLException
  {
    checkIsOpen();

    if (attrNames == null || values == null)
    {
      throw new IllegalArgumentException();
    }

    if (attrNames.length != attrNames.length)
    {
      throw new IllegalArgumentException();
    }

    UConnection u_con = cur_con.getUConnection();
    synchronized (u_con)
    {
      u_con.putByOID(this, attrNames, values);
      error = u_con.getRecentError();
    }

    checkError();
  }

  /**
   * <code>this</code> object�� ����Ű�� �ִ� database�� object�� �����Ѵ�.
   *
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void remove() throws SQLException
  {
    checkIsOpen();

    UConnection u_con = cur_con.getUConnection();
    synchronized (u_con)
    {
      u_con.oidCmd(this, UConnection.DROP_BY_OID);
      error = u_con.getRecentError();
    }

    checkError();

    if (u_con.getAutoCommit())
    {
      u_con.turnOnAutoCommitBySelf();
    }

  }

  /**
   * transaction isolation level�� �����Ͽ��� <code>this</code> object��
   * ����Ű�� �ִ� database�� object�� �����Ǿ����� �ƴ��� �Ǵ��Ѵ�.
   *
   * @return <code>true</code> if not deleted, <code>false</code> otherwise
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized boolean isInstance() throws SQLException
  {
    checkIsOpen();

    Object instance_obj;
    UConnection u_con = cur_con.getUConnection();
    synchronized (u_con)
    {
      instance_obj = u_con.oidCmd(this, UConnection.IS_INSTANCE);
      error = u_con.getRecentError();
    }

    checkError();
    if (instance_obj == null)
      return false;
    else
      return true;
  }

  /**
   * <code>this</code> object�� ����Ű�� �ִ� database�� object�� read lock��
   * set�Ѵ�. �ٸ� transaction�� ���� write lock�� set�Ǿ� ���������� reset��
   * ������ block�ȴ�.
   *
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void setReadLock() throws SQLException
  {
    checkIsOpen();

    UConnection u_con = cur_con.getUConnection();
    synchronized (u_con)
    {
      u_con.oidCmd(this, u_con.GET_READ_LOCK_BY_OID);
      error = u_con.getRecentError();
    }

    checkError();
  }

  /**
   * <code>this</code> object�� ����Ű�� �ִ� database�� object�� write lock��
   * set�Ѵ�. �ٸ� transaction�� ���� read lock�̳� write lock�� set�Ǿ�
   * ���������� reset�� ������ block�ȴ�.
   *
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void setWriteLock() throws SQLException
  {
    checkIsOpen();

    UConnection u_con = cur_con.getUConnection();
    synchronized (u_con)
    {
      u_con.oidCmd(this, u_con.GET_WRITE_LOCK_BY_OID);
      error = u_con.getRecentError();
    }

    checkError();
  }

  /**
   * <code>this</code> object�� ����Ű�� �ִ� database�� GLO object�κ��� ����
   * �о�ͼ� stream���� ������.
   *
   * @param stream
   *          ���� ������ <code>OutputStream</code> object
   * @exception IllegalArgumentException
   *              if <code>stream</code> is <code>null</code>
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void loadGLO(OutputStream stream) throws SQLException
  {
    checkIsOpen();

    if (stream == null)
    {
      throw new IllegalArgumentException();
    }

    CUBRIDBlob blob = toBlob();
    InputStream in = blob.getBinaryStream();

    stream_copy(in, Integer.MAX_VALUE, stream);

    try
    {
      in.close();
    }
    catch (IOException e)
    {
      throw new CUBRIDException(CUBRIDJDBCErrorCode.unknown, e.getMessage());
    }
  }

  /**
   * stream���κ��� ���� �о�ͼ� <code>this</code> object�� ����Ű�� �ִ�
   * database�� GLO object�� ������.
   *
   * @param stream
   *          ���� �о���� <code>InputStream</code> object
   * @exception IllegalArgumentException
   *              if <code>stream</code> is <code>null</code>
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void saveGLO(InputStream stream) throws SQLException
  {
    saveGLO(stream, Integer.MAX_VALUE);
  }

  /**
   * stream���κ��� ���� �о�ͼ� <code>this</code> object�� ����Ű�� �ִ�
   * database�� GLO object�� ������.
   *
   * @param stream
   *          ���� �о���� <code>InputStream</code> object
   * @param length
   *          the number of bytes to save
   * @exception IllegalArgumentException
   *              if <code>stream</code> is <code>null</code>
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void saveGLO(InputStream stream, int length)
      throws SQLException
  {
    checkIsOpen();

    if (stream == null)
    {
      throw new IllegalArgumentException();
    }

    CUBRIDBlob blob = toBlob();

    blob.truncate(0);
    OutputStream out = blob.setBinaryStream(1);

    stream_copy(stream, length, out);

    try
    {
      out.close();
    }
    catch (IOException e)
    {
      throw new CUBRIDException(CUBRIDJDBCErrorCode.unknown, e.getMessage());
    }
  }

  /**
   * <code>this</code> object�� ����Ű�� �ִ� object�� set column�� ����
   * �߰��Ѵ�.
   *
   * @param attrName
   *          ���� �߰��ϰ��� �ϴ� set column�� �̸�
   * @param value
   *          �߰��ϰ��� �ϴ� ��
   * @exception IllegalArgumentException
   *              if <code>attrName</code> is <code>null</code>
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void addToSet(String attrName, Object value)
      throws SQLException
  {
    checkIsOpen();

    if (attrName == null)
    {
      throw new IllegalArgumentException();
    }

    UConnection u_con = cur_con.getUConnection();
    synchronized (u_con)
    {
      u_con.addElementToSet(this, attrName, value);
      error = u_con.getRecentError();
    }

    checkError();
  }

  /**
   * <code>this</code> object�� ����Ű�� �ִ� object�� set column���� ����
   * �����Ѵ�.
   *
   * @param attrName
   *          ���� �����ϰ��� �ϴ� set column�� �̸�
   * @param value
   *          �����ϰ��� �ϴ� ��
   * @exception IllegalArgumentException
   *              if <code>attrName</code> is <code>null</code>
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void removeFromSet(String attrName, Object value)
      throws SQLException
  {
    checkIsOpen();

    if (attrName == null)
    {
      throw new IllegalArgumentException();
    }

    UConnection u_con = cur_con.getUConnection();
    synchronized (u_con)
    {
      u_con.dropElementInSet(this, attrName, value);
      error = u_con.getRecentError();
    }

    checkError();
  }

  /**
   * <code>this</code> object�� ����Ű�� �ִ� object�� sequence column�� ����
   * �߰��Ѵ�.
   *
   * @param attrName
   *          ���� �߰��ϰ��� �ϴ� sequence column�� �̸�
   * @param index
   *          ���� �߰��� ��ġ(0-based)
   * @param value
   *          �߰��ϰ��� �ϴ� ��
   * @exception IllegalArgumentException
   *              if <code>attrName</code> is <code>null</code>
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void addToSequence(String attrName, int index,
      Object value) throws SQLException
  {
    checkIsOpen();

    if (attrName == null)
    {
      throw new IllegalArgumentException();
    }

    UConnection u_con = cur_con.getUConnection();
    synchronized (u_con)
    {
      u_con.insertElementIntoSequence(this, attrName, index, value);
      error = u_con.getRecentError();
    }

    checkError();
  }

  /**
   * <code>this</code> object�� ����Ű�� �ִ� object�� sequence column�� ����
   * �����Ѵ�.
   *
   * @param attrName
   *          ���� �����ϰ��� �ϴ� sequence column�� �̸�
   * @param index
   *          ���� ������ ��ġ(0-based)
   * @param value
   *          �����ϰ��� �ϴ� ��
   * @exception IllegalArgumentException
   *              if <code>attrName</code> is <code>null</code>
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void putIntoSequence(String attrName, int index,
      Object value) throws SQLException
  {
    checkIsOpen();

    if (attrName == null)
    {
      throw new IllegalArgumentException();
    }

    UConnection u_con = cur_con.getUConnection();
    synchronized (u_con)
    {
      u_con.putElementInSequence(this, attrName, index, value);
      error = u_con.getRecentError();
    }

    checkError();
  }

  /**
   * <code>this</code> object�� ����Ű�� �ִ� object�� sequence column�� ����
   * �����Ѵ�.
   *
   * @param attrName
   *          ���� �����ϰ��� �ϴ� sequence column�� �̸�
   * @param index
   *          ���� ������ ��ġ(0-based)
   * @exception IllegalArgumentException
   *              if <code>attrName</code> is <code>null</code>
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized void removeFromSequence(String attrName, int index)
      throws SQLException
  {
    checkIsOpen();

    if (attrName == null)
    {
      throw new IllegalArgumentException();
    }

    UConnection u_con = cur_con.getUConnection();
    synchronized (u_con)
    {
      u_con.dropElementInSequence(this, attrName, index);
      error = u_con.getRecentError();
    }

    checkError();
  }

  /**
   * <code>this</code> object�� ������ �ִ� oid string���� return�Ѵ�. oid
   * string�� format�� "@page_id|slot_id|volumn_id"�̴�.
   *
   * @return a <code>String</code> object containing oid string
   * @exception IllegalArgumentException
   *              if <code>attrName</code> is <code>null</code>
   * @exception SQLException
   *              if a database access error occurs
   */
  public synchronized String getOidString() throws SQLException
  {
    checkIsOpen();

    if (oid == null || oid.length != UConnection.OID_BYTE_SIZE)
      return "";

    return ("@" + UJCIUtil.bytes2int(oid, 0) + "|"
        + UJCIUtil.bytes2short(oid, 4) + "|" + UJCIUtil.bytes2short(oid, 6));
  }

  public byte[] getOID()
  {
    return oid;
  }

  public synchronized String getTableName() throws SQLException
  {
    checkIsOpen();

    UConnection u_con = cur_con.getUConnection();
    String tablename;
    synchronized (u_con)
    {
      tablename = (String) u_con
          .oidCmd(this, UConnection.GET_CLASS_NAME_BY_OID);
    }
    return tablename;
  }

  /**
   * �ٸ� connection���� ����� �� �ִ� <code>CUBRIDOID</code> object��
   * �����Ѵ�.
   *
   * @param con
   *          ����ϰ��� �ϴ� <code>CUBRIDConnection</code> object
   * @param oidStr
   *          oid string��
   * @return ������ <code>CUBRIDOID</code> object
   * @exception IllegalArgumentException
   *              if either <code>con</code> or <code>oidStr</code> is
   *              <code>null</code>
   * @exception SQLException
   *              if a database access error occurs
   */
  public static CUBRIDOID getNewInstance(CUBRIDConnection con, String oidStr)
      throws SQLException
  {
    if (con == null || oidStr == null)
    {
      throw new IllegalArgumentException();
    }
    if (oidStr.charAt(0) != '@')
      throw new IllegalArgumentException();
    StringTokenizer oidStringArray = new StringTokenizer(oidStr, "|");
    try
    {
      int page = Integer.parseInt(oidStringArray.nextToken().substring(1));
      short slot = Short.parseShort(oidStringArray.nextToken());
      short vol = Short.parseShort(oidStringArray.nextToken());

      byte[] bOID = new byte[UConnection.OID_BYTE_SIZE];
      bOID[0] = ((byte) ((page >>> 24) & 0xFF));
      bOID[1] = ((byte) ((page >>> 16) & 0xFF));
      bOID[2] = ((byte) ((page >>> 8) & 0xFF));
      bOID[3] = ((byte) ((page >>> 0) & 0xFF));
      bOID[4] = ((byte) ((slot >>> 8) & 0xFF));
      bOID[5] = ((byte) ((slot >>> 0) & 0xFF));
      bOID[6] = ((byte) ((vol >>> 8) & 0xFF));
      bOID[7] = ((byte) ((vol >>> 0) & 0xFF));

      return new CUBRIDOID(con, bOID);
    }
    catch (NoSuchElementException e)
    {
      throw new IllegalArgumentException();
    }
  }

  public synchronized CUBRIDBlob toBlob() throws SQLException
  {
    return (new CUBRIDBlob(this));
  }

  public synchronized CUBRIDClob toClob() throws SQLException
  {
    return (new CUBRIDClob(this, cur_con.getUConnection().getCharset()));
  }

  public synchronized int gloRead(long pos, int length, byte[] dataBuffer,
      int buf_offset) throws SQLException
  {
    checkIsOpen();

    pos--;

    int real_read_len, read_len, total_read_len = 0;

    while (length > 0)
    {
      read_len = Math.min(length, GLO_MAX_SEND_SIZE);

      real_read_len = gloCmd(UConnection.GLO_CMD_READ_DATA, pos, read_len,
          dataBuffer, buf_offset);

      pos += real_read_len;
      length -= real_read_len;
      buf_offset += real_read_len;
      total_read_len += real_read_len;

      if (real_read_len < read_len)
        break;
    }

    return total_read_len;
  }

  public synchronized int gloWrite(long pos, byte[] data, int offset, int len)
      throws SQLException
  {
    checkIsOpen();

    pos--;

    while (len > 0)
    {
      int write_len = Math.min(len, GLO_MAX_SEND_SIZE);

      gloCmd(UConnection.GLO_CMD_WRITE_DATA, pos, write_len, data, offset);

      pos += write_len;
      len -= write_len;
      offset += write_len;
    }

    return len;
  }

  public synchronized void gloInsert(long pos, byte[] data, int offset, int len)
      throws SQLException
  {
    checkIsOpen();

    pos--;

    while (len > 0)
    {
      int write_len = Math.min(len, GLO_MAX_SEND_SIZE);

      gloCmd(UConnection.GLO_CMD_INSERT_DATA, pos, write_len, data, offset);

      pos += write_len;
      len -= write_len;
      offset += write_len;
    }
  }

  public synchronized void gloDelete(long pos, int length) throws SQLException
  {
    checkIsOpen();
    gloCmd(UConnection.GLO_CMD_DELETE_DATA, pos - 1, length, null, 0);
  }

  protected synchronized void gloTruncate(long len) throws SQLException
  {
    checkIsOpen();
    gloCmd(UConnection.GLO_CMD_TRUNCATE_DATA, len, 0, null, 0);
  }

  public synchronized long gloSize() throws SQLException
  {
    checkIsOpen();
    return ((long) gloCmd(UConnection.GLO_CMD_DATA_SIZE, 0, 0, null, 0));
  }

  public synchronized long gloBinarySearch(long start, byte[] pattern,
      int offset, int len) throws SQLException
  {
    checkIsOpen();

    int position;
    len = Math.min(GLO_MAX_SEARCH_LEN, len);
    position = gloCmd(UConnection.GLO_CMD_BINARY_SEARCH, start - 1, len,
        pattern, offset);

    return ((long) position + 1);
  }

  private void close() throws SQLException
  {
    if (is_closed)
    {
      return;
    }
    is_closed = true;
    cur_con = null;
    oid = null;
  }

  private void checkIsOpen() throws SQLException
  {
    if (is_closed)
    {
      throw new CUBRIDException(CUBRIDJDBCErrorCode.oid_closed);
    }
  }

  private void checkError() throws SQLException
  {
    switch (error.getErrorCode())
    {
    case UErrorCode.ER_NO_ERROR:
      break;
    case UErrorCode.ER_IS_CLOSED:
      close();
      throw new CUBRIDException(CUBRIDJDBCErrorCode.oid_closed);
    case UErrorCode.ER_INVALID_ARGUMENT:
      throw new IllegalArgumentException();
    default:
      throw new CUBRIDException(error);
    }
  }

  private int gloCmd(byte cmd, long pos, int len, byte[] buf, int offset)
      throws SQLException
  {
    int res;
    UConnection u_con = cur_con.getUConnection();

    synchronized (u_con)
    {
      res = u_con.gloCmd(this, cmd, (int) pos, len, buf, offset);
      error = u_con.getRecentError();
    }
    checkError();
    return res;
  }

  private void stream_copy(InputStream in, int length, OutputStream out)
      throws SQLException
  {
    byte[] buf = new byte[GLO_MAX_SEND_SIZE];
    int read_len;

    try
    {
      while (length > 0)
      {
        read_len = Math.min(length, GLO_MAX_SEND_SIZE);
        read_len = in.read(buf, 0, read_len);
        if (read_len <= 0)
          break;
        out.write(buf, 0, read_len);
        length -= read_len;
      }
    }
    catch (IOException e)
    {
      throw new CUBRIDException(CUBRIDJDBCErrorCode.unknown, e.getMessage());
    }
  }
}
