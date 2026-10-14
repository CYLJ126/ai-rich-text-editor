package com.arte.core.application.platform;

import com.arte.core.utils.platform.PlatformTimePrecision;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 平台墙钟策略：原始观测允许回拨，新业务时间统一截断为微秒，元数据及回执完成时间保持非递减。
 * 不提供分布式事件顺序、时间唯一性或授权有效期保证。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 11:55 ✾
 */
public final class PlatformTimes {
    private PlatformTimes() {
    }

    /**
     * 获取原始墙钟观测值，不截断、不钳制，外部时间契约按各自边界处理。
     */
    public static Instant observedNow(Clock clock) {
        return Objects.requireNonNull(clock, "clock").instant();
    }

    /**
     * 在构造新元数据、回执或事件／审计之前，将观测值统一截断为微秒。
     */
    public static Instant businessNow(Clock clock) {
        return PlatformTimePrecision.micros(observedNow(clock));
    }

    /**
     * 上一个值须在资源治理事务持锁后读取；按微秒比较并取较晚值，允许相等。
     */
    public static Instant notBefore(Clock clock, Instant previous) {
        return notBefore(businessNow(clock), previous);
    }

    public static Instant notBefore(Instant observed, Instant previous) {
        var current = PlatformTimePrecision.micros(observed);
        var last = PlatformTimePrecision.requireMicros(previous);
        return current.isBefore(last) ? last : current;
    }
}
