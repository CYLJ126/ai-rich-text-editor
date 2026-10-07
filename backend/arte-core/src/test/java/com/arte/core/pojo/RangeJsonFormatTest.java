package com.arte.core.pojo;

import com.arte.core.serialize.SerializerFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RangeJsonFormatTest {

    private final JsonMapper mapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();

    @Test
    void nonexistentDatesAreRejectedAtBothBounds() {
        for (String value : List.of("2026-02-30", "2026-02-29", "2026-04-31", "2026-13-01")) {
            for (String bound : List.of("from", "to")) {
                assertThrows(MismatchedInputException.class,
                        () -> mapper.readValue("{\"" + bound + "\":\"" + value + "\"}", DateRangeParam.class));
            }
        }
    }

    @Test
    void validDatesIncludingLeapDayRoundTrip() {
        var range = mapper.readValue("""
                {"from":"2024-02-29","to":"2026-02-28"}
                """, DateRangeParam.class);
        assertEquals(LocalDate.of(2024, 2, 29), range.getFrom());
        assertEquals(LocalDate.of(2026, 2, 28), range.getTo());
        var restored = mapper.readValue(mapper.writeValueAsString(range), DateRangeParam.class);
        assertEquals(range.getFrom(), restored.getFrom());
        assertEquals(range.getTo(), restored.getTo());
    }

    @Test
    void instantBoundsRejectNumericAndOtherNonStringValues() {
        for (String value : List.of("1700000000000", "1700000000.5", "true", "{}", "[]",
                "[\"2026-10-07T00:00:00Z\"]")) {
            for (String bound : List.of("from", "to")) {
                assertThrows(MismatchedInputException.class,
                        () -> mapper.readValue("{\"" + bound + "\":" + value + "}", InstantRangeParam.class));
            }
        }
    }

    @Test
    void instantBoundsRejectMissingOffsetInvalidDateAndEmptyText() {
        for (String value : List.of("2026-10-07", "2026-10-07T00:00:00", "2026-02-30T00:00:00Z",
                "2026-10-07T24:00:00Z", "1700000000000", "", " ")) {
            for (String bound : List.of("from", "to")) {
                assertThrows(MismatchedInputException.class,
                        () -> mapper.readValue("{\"" + bound + "\":\"" + value + "\"}", InstantRangeParam.class));
            }
        }
    }

    @Test
    void utcAndOffsetInstantsRoundTripWithoutLosingNanoseconds() {
        var range = mapper.readValue("""
                {"from":"2026-10-07T00:00:00.123456789+08:00","to":"2026-10-06T16:00:00.123456789Z"}
                """, InstantRangeParam.class);
        assertEquals(Instant.parse("2026-10-06T16:00:00.123456789Z"), range.getFrom());
        assertEquals(range.getFrom(), range.getTo());
        var restored = mapper.readValue(mapper.writeValueAsString(range), InstantRangeParam.class);
        assertEquals(range.getFrom(), restored.getFrom());
        assertEquals(range.getTo(), restored.getTo());
    }

    @Test
    void omittedAndNullBoundsRemainUnbounded() {
        for (String json : List.of("{}", "{\"from\":null,\"to\":null}")) {
            var date = mapper.readValue(json, DateRangeParam.class);
            var instant = mapper.readValue(json, InstantRangeParam.class);
            assertNull(date.getFrom());
            assertNull(date.getTo());
            assertNull(instant.getFrom());
            assertNull(instant.getTo());
        }
    }

    @Test
    void inheritedFiltersUseSameRulesAcrossStreamAndCacheMappers() {
        var query = new BaseParam();
        var time = new InstantRangeParam();
        time.setFrom(Instant.parse("2026-10-07T00:00:00.123456789Z"));
        query.setCreateTime(time);
        var date = new DateRangeParam();
        date.setTo(LocalDate.of(2024, 2, 29));
        query.setStartDate(date);

        for (JsonMapper configured : List.of(SerializerFactory.buildStreamJsonMapper(),
                SerializerFactory.buildJsonMapperWithTypeProperty())) {
            String json = configured.writeValueAsString(query);
            var restored = configured.readValue(json, BaseParam.class);
            assertEquals(time.getFrom(), restored.getCreateTime().getFrom());
            assertEquals(date.getTo(), restored.getStartDate().getTo());
            // 用 mapper 生成正常请求后替换边界，保留缓存所需的类型信息。
            String numericTime = json.replace("\"2026-10-07T00:00:00.123456789Z\"", "1700000000000");
            assertNotEquals(json, numericTime);
            assertThrows(MismatchedInputException.class, () -> configured.readValue(numericTime, BaseParam.class));
            String invalidDate = json.replace("2024-02-29", "2026-02-30");
            assertThrows(MismatchedInputException.class, () -> configured.readValue(invalidDate, BaseParam.class));
        }
    }
}
