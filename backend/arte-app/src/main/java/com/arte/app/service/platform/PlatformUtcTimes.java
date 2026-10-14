package com.arte.app.service.platform;

import com.arte.core.utils.platform.PlatformChecks;
import com.arte.core.utils.platform.PlatformTimePrecision;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Instant 与 UTC DATETIME(6) 的显式映射，使用 LocalDateTime 避免 JVM 默认时区介入 JDBC 转换。
 * 写入前检查微秒精度及 MySQL 日期范围，不依赖隐式舍入；读取同样拒绝损坏的精度。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 15:28 ✾
 */
final class PlatformUtcTimes {

    private PlatformUtcTimes() {
    }

    static LocalDateTime encode(Instant value) {
        var utc = LocalDateTime.ofInstant(PlatformTimePrecision.requireMicros(value), ZoneOffset.UTC);
        PlatformChecks.require(utc.getYear() >= 1000 && utc.getYear() <= 9999, "time outside DATETIME range");
        return utc;
    }

    static Instant decode(LocalDateTime value) {
        var instant = Objects.requireNonNull(value, "UTC time").toInstant(ZoneOffset.UTC);
        encode(instant);
        return instant;
    }
}
