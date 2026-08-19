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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Title: CUBRID JDBC Driver Description:
 *
 * @version 2.0
 */
public class CUBRIDJDBCErrorCode {

    public static final int unknown = -21100;
    public static final int connection_closed = -21101;
    public static final int statement_closed = -21102;
    public static final int prepared_statement_closed = -21103;
    public static final int result_set_closed = -21104;
    public static final int not_supported = -21105;
    public static final int invalid_trans_iso_level = -21106;
    public static final int invalid_url = -21107;
    public static final int no_dbname = -21108;
    public static final int invalid_query_type_for_executeQuery = -21109;
    public static final int invalid_query_type_for_executeUpdate = -21110;
    public static final int negative_value_for_length = -21111;
    public static final int ioexception_in_stream = -21112;
    public static final int deprecated = -21113;
    public static final int not_numerical_object = -21114;
    public static final int invalid_index = -21115;
    public static final int invalid_column_name = -21116;
    public static final int invalid_row = -21117;
    public static final int conversion_error = -21118;
    public static final int invalid_tuple = -21119;
    public static final int invalid_value = -21120;
    public static final int not_collection = -21121;
    public static final int dbmetadata_closed = -21122;
    public static final int non_scrollable = -21123;
    public static final int non_sensitive = -21124;
    public static final int non_updatable = -21125;
    public static final int non_updatable_column = -21126;
    public static final int invalid_query_type_for_executeInsert = -21127;
    public static final int argument_zero = -21128;
    public static final int empty_inputstream = -21129;
    public static final int empty_reader = -21130;
    public static final int insertion_query_fail = -21131;
    public static final int non_scrollable_statement = -21132;
    public static final int iss_fail_login = -21133;
    public static final int pooled_connection_closed = -21134;
    public static final int xa_connection_closed = -21135;
    public static final int xa_illegal_operation = -21136;
    public static final int oid_closed = -21137;
    public static final int invalid_table_name = -21138;
    public static final int lob_pos_invalid = -21139;
    public static final int lob_is_not_writable = -21140;
    public static final int request_timeout = -21141;
    public static final int invalid_prop_file = -21142;
    public static final int file_not_found_prop = -21143;
    public static final int savepoint_in_auto_commit_mode = -21144;
    public static final int invalid_savepoint = -21145;

    private static final Map<Integer, String> messageString = createMessageMap();

    private static Map<Integer, String> createMessageMap() {
        Map<Integer, String> m = new HashMap<>();
        m.put(unknown, "");
        m.put(connection_closed, "Attempt to operate on a closed Connection.");
        m.put(statement_closed, "Attempt to access a closed Statement.");
        m.put(prepared_statement_closed, "Attempt to access a closed PreparedStatement.");
        m.put(result_set_closed, "Attempt to access a closed ResultSet.");
        m.put(not_supported, "Not supported method");
        m.put(invalid_trans_iso_level, "Unknown transaction isolation level.");
        m.put(invalid_url, "invalid URL - ");
        m.put(no_dbname, "The database name should be given.");
        m.put(
                invalid_query_type_for_executeQuery,
                "The query is not applicable to the executeQuery(). Use the executeUpdate() instead.");
        m.put(
                invalid_query_type_for_executeUpdate,
                "The query is not applicable to the executeUpdate(). Use the executeQuery() instead.");
        m.put(negative_value_for_length, "The length of the stream cannot be negative.");
        m.put(ioexception_in_stream, "An IOException was caught during reading the inputstream.");
        m.put(deprecated, "Not supported method, because it is deprecated.");
        m.put(not_numerical_object, "The object does not seem to be a number.");
        m.put(invalid_index, "Missing or invalid position of the bind variable provided.");
        m.put(invalid_column_name, "The column name is invalid.");
        m.put(invalid_row, "Invalid cursor position.");
        m.put(conversion_error, "Type conversion error.");
        m.put(
                invalid_tuple,
                "Internal error: The number of attributes is different from the expected.");
        m.put(invalid_value, "The argument is invalid.");
        m.put(not_collection, "The type of the column should be a collection type.");
        m.put(dbmetadata_closed, "Attempt to operate on a closed DatabaseMetaData.");
        m.put(
                non_scrollable,
                "Attempt to call a method related to scrollability of non-scrollable ResultSet.");
        m.put(
                non_sensitive,
                "Attempt to call a method related to sensitivity of non-sensitive ResultSet.");
        m.put(
                non_updatable,
                "Attempt to call a method related to updatability of non-updatable ResultSet.");
        m.put(non_updatable_column, "Attempt to update a column which cannot be updated.");
        m.put(
                invalid_query_type_for_executeInsert,
                "The query is not applicable to the executeInsert().");
        m.put(argument_zero, "The argument row can not be zero.");
        m.put(empty_inputstream, "Given InputStream object has no data.");
        m.put(empty_reader, "Given Reader object has no data.");
        m.put(insertion_query_fail, "Insertion query failed.");
        m.put(
                non_scrollable_statement,
                "Attempt to call a method related to scrollability of TYPE_FORWARD_ONLY Statement.");
        m.put(iss_fail_login, "Authentication failure");
        m.put(pooled_connection_closed, "Attempt to operate on a closed PooledConnection.");
        m.put(xa_connection_closed, "Attempt to operate on a closed XAConnection.");
        m.put(xa_illegal_operation, "Illegal operation in a distributed transaction");
        m.put(
                oid_closed,
                "Attempt to access a CUBRIDOID associated with a Connection which has been closed.");
        m.put(invalid_table_name, "The table name is invalid.");
        m.put(lob_pos_invalid, "Lob position to write is invalid.");
        m.put(lob_is_not_writable, "Lob is not writable.");
        m.put(request_timeout, "Request timed out.");
        m.put(invalid_prop_file, "Invalid file - ");
        m.put(file_not_found_prop, "File not found - ");
        m.put(
                savepoint_in_auto_commit_mode,
                "Savepoint cannot be used while auto-commit is enabled.");
        m.put(invalid_savepoint, "Invalid savepoint - ");

        return Collections.unmodifiableMap(m);
    }

    public static String getMessage(int code) {
        return messageString.get(code);
    }
}
