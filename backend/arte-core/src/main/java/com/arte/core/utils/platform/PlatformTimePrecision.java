package com.arte.core.utils.platform;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 新业务时间的微秒精度边界；采用截断而非舍入，不用于改写历史回执或外部身份、授权期限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 15:28 ✾
 */
public final class PlatformTimePrecision {
    private PlatformTimePrecision() {
    }

    /**
     * 在进入业务模型前规范化为微秒；包括纪元之前的时间，不改变时区或解释为提交顺序。
     */
    public static Instant micros(Instant value) {
        return Objects.requireNonNull(value, "time").truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 存储边界拒绝非微秒值，防止驱动或数据库隐式舍入。
     */
    public static Instant requireMicros(Instant value) {
        Objects.requireNonNull(value, "time");
        PlatformChecks.require(value.getNano() % 1000 == 0, "time must have microsecond precision");
        return value;
    }
}
