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

package cubrid.jdbc.jci;

import cubrid.sql.CUBRIDTimestamp;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * A date or time value as the server sent it, holding both the calendar fields and the epoch the
 * reader computed from them.
 *
 * <p>The wire carries year, month, day, hour, minute and second with no time zone. Rebuilding those
 * fields from an epoch applies the JVM default time zone a second time, which shifts values that do
 * not exist in that zone, so the fields are kept as they arrived and the java.time getters read
 * them directly. The epoch is kept as well because the java.sql getters answer with it and have to
 * keep their old behaviour.
 */
final class UDateTimeFields {
    private static final int NANOS_PER_MILLI = 1000000;
    static final LocalDate TIME_BASE_DATE = LocalDate.of(1970, 1, 1);
    private static final LocalDateTime ROUNDED_DATETIME = LocalDateTime.of(1, 1, 1, 0, 0, 0);
    private static final LocalDateTime ROUNDED_TIMESTAMP = LocalDateTime.of(1970, 1, 1, 0, 0, 0);

    private final byte type;
    private final long epoch;
    private final int year;
    private final int month;
    private final int day;
    private final int hour;
    private final int minute;
    private final int second;
    private final int millisecond;

    private UDateTimeFields(
            byte type,
            long epoch,
            int year,
            int month,
            int day,
            int hour,
            int minute,
            int second,
            int millisecond) {
        this.type = type;
        this.epoch = epoch;
        this.year = year;
        this.month = month;
        this.day = day;
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.millisecond = millisecond;
    }

    static UDateTimeFields ofDate(long epoch, int year, int month, int day) {
        return new UDateTimeFields(UUType.U_TYPE_DATE, epoch, year, month, day, 0, 0, 0, 0);
    }

    static UDateTimeFields ofTime(long epoch, int hour, int minute, int second) {
        return new UDateTimeFields(UUType.U_TYPE_TIME, epoch, 0, 0, 0, hour, minute, second, 0);
    }

    static UDateTimeFields ofTimestamp(
            long epoch, int year, int month, int day, int hour, int minute, int second) {
        return new UDateTimeFields(
                UUType.U_TYPE_TIMESTAMP, epoch, year, month, day, hour, minute, second, 0);
    }

    static UDateTimeFields ofDatetime(
            long epoch,
            int year,
            int month,
            int day,
            int hour,
            int minute,
            int second,
            int millisecond) {
        return new UDateTimeFields(
                UUType.U_TYPE_DATETIME, epoch, year, month, day, hour, minute, second, millisecond);
    }

    private boolean hasDate() {
        return type != UUType.U_TYPE_TIME;
    }

    private boolean hasTime() {
        return type != UUType.U_TYPE_DATE;
    }

    private boolean isZeroDate() {
        return hasDate() && year == 0 && month == 0 && day == 0;
    }

    /* Answers the java.sql value when o carries wire fields, and o itself otherwise. */
    static Object sqlValueOf(Object o) {
        return o instanceof UDateTimeFields ? ((UDateTimeFields) o).toSqlValue() : o;
    }

    private Object toSqlValue() {
        switch (type) {
            case UUType.U_TYPE_DATE:
                return new Date(epoch);
            case UUType.U_TYPE_TIME:
                return new Time(epoch);
            case UUType.U_TYPE_TIMESTAMP:
                return new CUBRIDTimestamp(epoch, CUBRIDTimestamp.TIMESTAMP);
            default:
                return new CUBRIDTimestamp(epoch, CUBRIDTimestamp.DATETIME);
        }
    }

    private LocalDateTime roundedZeroDate() {
        return type == UUType.U_TYPE_TIMESTAMP ? ROUNDED_TIMESTAMP : ROUNDED_DATETIME;
    }

    LocalDate toLocalDate() throws UJciException {
        if (!hasDate()) {
            throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
        }
        return isZeroDate() ? roundedZeroDate().toLocalDate() : LocalDate.of(year, month, day);
    }

    LocalTime toLocalTime() throws UJciException {
        if (!hasTime()) {
            throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
        }
        return isZeroDate()
                ? roundedZeroDate().toLocalTime()
                : LocalTime.of(hour, minute, second, millisecond * NANOS_PER_MILLI);
    }

    LocalDateTime toLocalDateTime() throws UJciException {
        if (!hasDate()) {
            return LocalDateTime.of(TIME_BASE_DATE, toLocalTime());
        }
        if (isZeroDate()) {
            return roundedZeroDate();
        }
        return LocalDateTime.of(
                year, month, day, hour, minute, second, millisecond * NANOS_PER_MILLI);
    }
}
