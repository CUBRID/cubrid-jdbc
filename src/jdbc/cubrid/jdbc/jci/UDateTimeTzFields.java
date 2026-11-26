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
import cubrid.sql.CUBRIDTimestamptz;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A value of a time zone type as the server sent it: the TIMESTAMP or DATETIME fields and the time
 * zone text, from which toOffsetDateTime takes the offset.
 */
final class UDateTimeTzFields implements UDateTimeValue {
    private static final DateTimeFormatter ZONE_ABBREVIATION =
            DateTimeFormatter.ofPattern("zzz", Locale.US);

    private final UDateTimeFields fields;
    private final String timezone;

    UDateTimeTzFields(UDateTimeFields fields, String timezone) {
        this.fields = fields;
        this.timezone = timezone;
    }

    @Override
    public Object toSqlValue() {
        return CUBRIDTimestamptz.valueOf((CUBRIDTimestamp) fields.toSqlValue(), timezone);
    }

    /* The value carries its own time zone, so zone does not apply. */
    @Override
    public Object toSqlValue(TimeZone zone) {
        return toSqlValue();
    }

    OffsetDateTime toOffsetDateTime() throws UJciException {
        LocalDateTime local = fields.toLocalDateTime();
        return OffsetDateTime.of(local, offsetAt(local));
    }

    /*
     * The text is an offset ("+01:00") or a region with its abbreviation ("Asia/Seoul KST",
     * "Asia/Ho_Chi_Minh +07"). A numeric abbreviation is the offset the server used; a letter one
     * only picks between the two offsets of an overlap in the JVM rules.
     */
    private ZoneOffset offsetAt(LocalDateTime local) throws UJciException {
        if (timezone.isEmpty()) {
            return ZoneOffset.UTC;
        }
        String[] parts = timezone.split(" ");
        String abbreviation = parts.length > 1 ? parts[1] : "";
        try {
            if (isOffsetText(parts[0])) {
                return ZoneOffset.of(parts[0]);
            }
            if (isOffsetText(abbreviation)) {
                return ZoneOffset.of(abbreviation);
            }
            ZoneId region = ZoneId.of(parts[0]);
            ZoneRules rules = region.getRules();
            List<ZoneOffset> offsets = rules.getValidOffsets(local);
            if (offsets.isEmpty()) {
                return rules.getTransition(local).getOffsetBefore();
            }
            if (offsets.size() == 1) {
                return offsets.get(0);
            }
            return offsets.stream()
                    .filter(
                            offset ->
                                    abbreviation.equals(
                                            ZONE_ABBREVIATION.format(
                                                    ZonedDateTime.ofLocal(local, region, offset))))
                    .findFirst()
                    .orElse(offsets.get(0));
        } catch (DateTimeException e) {
            throw new UJciException(UErrorCode.ER_TYPE_CONVERSION);
        }
    }

    private static boolean isOffsetText(String text) {
        return text.startsWith("+") || text.startsWith("-");
    }
}
