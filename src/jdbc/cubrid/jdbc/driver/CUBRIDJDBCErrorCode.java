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

package @CUBRID_DRIVER@;

/**
 * Title: CUBRID JDBC Driver Description:
 * 
 * @version 2.0
 */

public class CUBRIDJDBCErrorCode
{
  public static int unknown = 0;
  public static int connection_closed = 1;
  public static int statement_closed = 2;
  public static int prepared_statement_closed = 3;
  public static int result_set_closed = 4;
  public static int not_supported = 5;
  public static int invalid_trans_iso_level = 6;
  public static int invalid_url = 7;
  public static int no_dbname = 8;
  public static int invalid_query_type_for_executeQuery = 9;
  public static int invalid_query_type_for_executeUpdate = 10;
  public static int negative_value_for_length = 11;
  public static int ioexception_in_stream = 12;
  public static int deprecated = 13;
  public static int not_numerical_object = 14;
  public static int invalid_index = 15;
  public static int invalid_column_name = 16;
  public static int invalid_row = 17;
  public static int conversion_error = 18;
  public static int invalid_tuple = 19;
  public static int invalid_value = 20;
  public static int not_collection = 21;
  public static int dbmetadata_closed = 22;
  public static int non_scrollable = 23;
  public static int non_sensitive = 24;
  public static int non_updatable = 25;
  public static int non_updatable_column = 26;
  public static int invalid_query_type_for_executeInsert = 27;
  public static int argument_zero = 28;
  public static int empty_inputstream = 29;
  public static int empty_reader = 30;
  public static int insertion_query_fail = 31;
  public static int non_scrollable_statement = 32;
  public static int iss_fail_login = 33;
  public static int pooled_connection_closed = 34;
  public static int xa_connection_closed = 35;
  public static int xa_illegal_operation = 36;
  public static int oid_closed = 37;
  public static int invalid_table_name = 38;

  public static String[] err_msg = 
  {
      /**
       * 0
       */
      "",

      /**
       * 1 Connection�� ������ ��µȴ�.
       */
      "Attempt to operate on a closed Connection.",

      /**
       * 2 Statement�� ������ ��µȴ�.
       */
      "Attempt to access a closed Statement.",

      /**
       * 3 PreparedStatement�� ������ ��µȴ�.
       */
      "Attempt to access a closed PreparedStatement.",

      /**
       * 4 ResultSet�� ������ ��µȴ�.
       */
      "Attempt to access a closed ResultSet.",

      /**
       * 5 �������� ���� interface�� ȣ��Ǿ��� �� ��µȴ�.
       */
      "Not supported method",

      /**
       * 6 Connection.setTransactionLevel(int level)���� �������� �ʴ� ����
       * �Ѿ���� �� ��µȴ�.
       */
      "Unknown transaction isolation level.",

      /**
       * 7 CUBRIDDriver.connect(...)���� url�� parsing�� �� ���� �� ��µȴ�.
       */
      "invalid URL - ",

      /**
       * 8 CUBRIDDriver.connect(...)���� Database name�� �־����� �ʾ��� ��
       * ��µȴ�. Database name���� default value�� ���� �����̴�.
       */
      "The database name should be given.",

      /**
       * 9 Statement.executeQuery()���� update query�� �Ѿ���� �� ��µȴ�.
       */
      "The query is not applicable to the executeQuery(). Use the executeUpdate() instead.",

      /**
       * 10 Statement.executeUpdate()���� select query�� �Ѿ���� �� ��µȴ�.
       */
      "The query is not applicable to the executeUpdate(). Use the executeQuery() instead.",

      /**
       * 11 PreparedStatement.setAsciiStream(int, InputStream, int length)��
       * PreparedStatement.setBinaryStream(int, InputStream, int length)����
       * length�� ���� �϶� ��µȴ�.
       */
      "The length of the stream cannot be negative.",

      /**
       * 12 PreparedStatement.setAsciiStream(int, InputStream x, int)��
       * PreparedStatement.setBinaryStream(int, InputStream x, int)����
       * InputStream x�� ����ϴٰ� IOException�� �߻��� �� ��µȴ�.
       */
      "An IOException was caught during reading the inputstream.",

      /**
       * 13 deprecated�Ǿ� �������� �ʴ� interface�� ȣ��Ǹ� ��µȴ�.
       */
      "Not supported method, because it is deprecated.",

      /**
       * 14 PreparedStatement.setObject(int, Object x, int, int)���� x��
       * java.lang.Number class�� subclass�� �ƴϸ� ��µȴ�.
       */
      "The object does not seem to be a number.",

      /**
       * 15 �߸��� index�� �Ѿ���� �� ��µȴ�.
       */
      "Missing or invalid position of the bind variable provided.",

      /**
       * 16 �߸��� column name�� �Ѿ���� �� ��µȴ�.
       */
      "The column name is invalid.",

      /**
       * 17 ResultSet���� cursor�� before first�� after last�� ��� ��������
       * �Լ��� ȣ��Ǹ� ��µȴ�.
       */
      "Invalid cursor position.",

      /**
       * 18 CUBRIDResultSetWithoutQuery���� DB ���� user�� �䱸�ϴ� Java
       * type���� ��ȯ�� �� ���� �� ��µȴ�.
       */
      "Type conversion error.",

      /**
       * 19 CUBRIDResultSetWithoutQuery.addTuple( Object[] tuple )����
       * tuple.length�� column�� ������ ���� ���� �� ��µȴ�.
       */
      "Internal error: The number of attributes is different from the expected.",

      /**
       * 20 max field size�� max rows�� query timeout���� ���� �߸� �Ѿ�� ��
       * ��µȴ�.
       */
      "The argument is invalid.",

      /**
       * 21 CUBRIDResultSetMetaData���� COLLECTION type�� �ƴ� column�� ���ؼ�
       * getElementType�̳� getElementTypeName�� ȣ��Ǿ��� �� ��µȴ�.
       */
      "The type of the column should be a collection type.",

      /**
       * 22 DatabaseMetaData�� ������ ��µȴ�.
       */
      "Attempt to operate on a closed DatabaseMetaData.",

      /**
       * 23 Scrollable���� ���� ResultSet�� Scrollability���� �޼ҵ� ȣ���
       */
      "Attempt to call a method related to scrollability of non-scrollable ResultSet.",

      /**
       * 24 Scrollable���� ���� ResultSet�� Scrollability���� �޼ҵ� ȣ���
       */
      "Attempt to call a method related to sensitivity of non-sensitive ResultSet.",

      /**
       * 25 Scrollable���� ���� ResultSet�� Scrollability���� �޼ҵ� ȣ���
       */
      "Attempt to call a method related to updatability of non-updatable ResultSet.",

      /**
       * 26 Scrollable���� ���� ResultSet�� Scrollability���� �޼ҵ� ȣ���
       */
      "Attempt to update a column which cannot be updated.",

      /**
       * 27 Statement.executeInsert()���� insert query�� �ƴ� �� ��µȴ�.
       */
      "The query is not applicable to the executeInsert().",

      /**
       * 28 ResultSet.absolute()���� argument�� 0�϶� �߻��Ѵ�.
       */
      "The argument row can not be zero.",

      /**
       * 29 ��� �ִ� InputStream�� argument�� �޾��� �� �߻��Ѵ�.
       */
      "Given InputStream object has no data.",

      /**
       * 30 ��� �ִ� Reader argument�� �޾��� �� �߻��Ѵ�.
       */
      "Given Reader object has no data.",

      /**
       * 31 Insert query�� ���� �� ������ �߻����� ��
       */
      "Insertion query failed.",

      /**
       * 32 TYPE_FORWARD_ONLY Statement�� setFetchDirection() �Լ��� ȣ��� ��
       * �߻�
       */
      "Attempt to call a method related to scrollability of TYPE_FORWARD_ONLY Statement.",

      /**
       * 33
       */
      "Authentication failure",

      /**
       * 34 PooledConnection�� ������ ��µȴ�.
       */
      "Attempt to operate on a closed PooledConnection.",

      /**
       * 35 XAConnection�� ������ ��µȴ�.
       */
      "Attempt to operate on a closed XAConnection.",

      /**
       * 36 xa : setAutoCommit, commit, rollback ȣ��� ��
       */
      "Illegal operation in a distributed transaction",

      /**
       * 37 CUBRIDOID�� ���õ� Connection ������ ��µȴ�.
       */
      "Attempt to access a CUBRIDOID associated with a Connection which has been closed.",

      /**
       * 38 invalid table name specified.
       */
      "The table name is invalid."
  };

  public static String getMessage(int code)
  {
    return err_msg[code];
  }
}
