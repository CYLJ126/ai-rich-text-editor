package com.arte.ainew.pojo.execution;

import java.util.Objects;

/**
 * 可预期的并发／准入结果；数据库故障通过 onError 传播，不能伪装为冲突或成功。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:11 ✾
 */
public record StoreOutcome<T>(Code code, T value) {
    public enum Code {
        APPLIED, REPLAYED, NOT_FOUND, OWNER_MISMATCH, VERSION_CONFLICT, IDEMPOTENCY_CONFLICT,
        LEASE_LOST, INVALID_STATE, RECONCILIATION_REQUIRED, CONVERSATION_BUSY,
        INSUFFICIENT_BUDGET, CURRENCY_MISMATCH, RATE_MISMATCH, CURSOR_EXPIRED
    }

    public StoreOutcome {
        Objects.requireNonNull(code, "code");
        if ((code == Code.APPLIED || code == Code.REPLAYED) != (value != null)) {
            throw new IllegalArgumentException("Only successful outcomes contain a value");
        }
    }

    public boolean successful() {
        return value != null;
    }

    public static <T> StoreOutcome<T> applied(T value) {
        return new StoreOutcome<>(Code.APPLIED, value);
    }

    public static <T> StoreOutcome<T> replayed(T value) {
        return new StoreOutcome<>(Code.REPLAYED, value);
    }

    public static <T> StoreOutcome<T> rejected(Code code) {
        return new StoreOutcome<>(code, null);
    }
}
