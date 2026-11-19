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

import cubrid.jdbc.jci.UUType;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/** Which java.time values are taken, how they are sent, and which columns they are read from. */
final class JavaTimeRules {
    private static final DateTimeFormatter DATE_LITERAL =
            DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.ROOT);
    private static final DateTimeFormatter TIME_LITERAL =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter DATETIME_LITERAL =
            DateTimeFormatter.ofPattern("MM/dd/uuuu HH:mm:ss.SSS", Locale.ROOT);
    private static final DateTimeFormatter OFFSET_DATETIME_LITERAL =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS xxxxx", Locale.ROOT);

    private JavaTimeRules() {}

    /* Several java.time types are not Temporal, so an instanceof Temporal guard would miss them. */
    static boolean isUnsupported(Object x) {
        return x != null
                && x.getClass().getName().startsWith("java.time.")
                && !(x instanceof LocalDate)
                && !(x instanceof LocalTime)
                && !(x instanceof LocalDateTime)
                && !(x instanceof OffsetDateTime);
    }

    /*
     * What an OffsetDateTime is sent as for a target type, or the error that target type gets.
     * JDBC allows TIME_WITH_TIMEZONE for an OffsetDateTime, but CUBRID has no such type.
     */
    static Object valueToSend(OffsetDateTime x, int targetSqlType, CUBRIDConnection con)
            throws SQLException {
        if (targetSqlType == Types.TIME_WITH_TIMEZONE) {
            throw CUBRIDException.notSupported(
                    CUBRIDException.cannotStoreAsMessage(x.getClass(), targetSqlType));
        }
        return offsetDateTimeFor(x, targetSqlType)
                .orElseThrow(
                        () ->
                                con.createCUBRIDException(
                                        CUBRIDJDBCErrorCode.invalid_value,
                                        CUBRIDException.cannotStoreAsMessage(
                                                x.getClass(), targetSqlType),
                                        null));
    }

    /* Empty when the target type is refused. */
    private static Optional<Object> offsetDateTimeFor(OffsetDateTime x, int targetSqlType) {
        switch (targetSqlType) {
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return Optional.of(x);
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
                return Optional.of(OFFSET_DATETIME_LITERAL.format(x));
            default:
                return Optional.empty();
        }
    }

    /* How a java.time value is written in an insertRow statement; empty for any other value. */
    static Optional<String> literalOf(Object value) {
        if (value instanceof LocalDate) {
            return Optional.of("'" + DATE_LITERAL.format((LocalDate) value) + "'");
        }
        if (value instanceof LocalTime) {
            return Optional.of("'" + TIME_LITERAL.format((LocalTime) value) + "'");
        }
        if (value instanceof LocalDateTime) {
            return Optional.of("'" + DATETIME_LITERAL.format((LocalDateTime) value) + "'");
        }
        if (value instanceof OffsetDateTime) {
            return Optional.of(
                    "datetimetz'" + OFFSET_DATETIME_LITERAL.format((OffsetDateTime) value) + "'");
        }
        return Optional.empty();
    }

    static boolean canRead(byte columnType, Class<?> type) {
        switch (columnType) {
            case UUType.U_TYPE_DATE:
                return type == LocalDate.class || type == LocalDateTime.class;
            case UUType.U_TYPE_TIME:
                return type == LocalTime.class || type == LocalDateTime.class;
            case UUType.U_TYPE_TIMESTAMP:
            case UUType.U_TYPE_DATETIME:
                return type == LocalDate.class
                        || type == LocalTime.class
                        || type == LocalDateTime.class;
            case UUType.U_TYPE_TIMESTAMPTZ:
            case UUType.U_TYPE_TIMESTAMPLTZ:
            case UUType.U_TYPE_DATETIMETZ:
            case UUType.U_TYPE_DATETIMELTZ:
                return type == OffsetDateTime.class;
            case UUType.U_TYPE_NULL:
                /* The server did not declare a type, so the decoded value carries its own. */
                return true;
            default:
                return false;
        }
    }
}
