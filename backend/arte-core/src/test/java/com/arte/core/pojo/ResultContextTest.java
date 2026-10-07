package com.arte.core.pojo;

import com.arte.core.enums.ResultCodeEnum;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResultContextTest {

    @Test
    void partialSuccessReportsCorrectCountsAndResponseStatus() {
        var result = ResultContext.partialSuccess(10, 3);

        assertEquals(Map.of("total", 10L, "success", 3L, "fail", 7L), result.getStatistics());
        assertEquals(ResultCodeEnum.SUCCESS.getCode(), result.getCode());
        assertEquals(Boolean.TRUE, result.getSuccess());
        assertNull(result.getData());
    }

    @Test
    void partialSuccessRejectsNegativeAndInconsistentCounts() {
        for (long[] counts : new long[][]{
                {-1, 0}, {1, -1}, {0, 1}, {1, 2},
                {Long.MIN_VALUE, 0}, {Long.MAX_VALUE, Long.MIN_VALUE}
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> ResultContext.partialSuccess(counts[0], counts[1]));
        }
    }

    @Test
    void partialSuccessHandlesEmptyAllFailedAllSucceededAndLongBoundaries() {
        for (long[] counts : new long[][]{
                {0, 0, 0}, {10, 0, 10}, {10, 10, 0},
                {Long.MAX_VALUE, 0, Long.MAX_VALUE},
                {Long.MAX_VALUE, Long.MAX_VALUE, 0},
                {Long.MAX_VALUE, Long.MAX_VALUE - 1, 1}
        }) {
            var result = ResultContext.partialSuccess(counts[0], counts[1]);
            assertEquals(Map.of("total", counts[0], "success", counts[1], "fail", counts[2]),
                    result.getStatistics());
            assertEquals(Boolean.TRUE, result.getSuccess());
        }
    }
}
