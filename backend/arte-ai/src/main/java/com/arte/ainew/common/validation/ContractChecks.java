package com.arte.ainew.common.validation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * SDK 无关的结构校验与绝对安全上限。入口、绑定与租户配额应施加更低的场景上限；
 * 字符上限不等于 Token、UTF-8 字节或费用额度，不替代授权与 Schema 校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public final class ContractChecks {
    public static final int MAX_TEXT_CHARS = 1_000_000;
    public static final int MAX_ITEMS = 256;
    public static final int MAX_PARTS = 64;
    public static final int MAX_TOOLS = 128;

    private ContractChecks() { }

    public static String text(String value, String field, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + limit + " characters");
        }
        return value;
    }

    public static String id(String value, String field) {
        return text(value, field, 256);
    }

    public static String optionalId(String value, String field) {
        return value == null ? null : id(value, field);
    }

    public static String digest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hex digest");
        }
        return value;
    }

    public static long range(long value, String field, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    public static <T> List<T> list(Collection<T> values, String field, int min, int max) {
        Objects.requireNonNull(values, field);
        range(values.size(), field + ".size", min, max);
        return List.copyOf(values);
    }

    public static <T> Set<T> set(Collection<T> values, String field, int max) {
        var copied = list(values, field, 0, max);
        unique(copied, field);
        return Set.copyOf(copied);
    }

    public static void unique(Collection<?> values, String field) {
        if (Set.copyOf(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
    }

    public static void ordered(Instant first, Instant second, String field) {
        if (Objects.requireNonNull(second, field).isBefore(Objects.requireNonNull(first, field))) {
            throw new IllegalArgumentException(field + " must not precede its start");
        }
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
