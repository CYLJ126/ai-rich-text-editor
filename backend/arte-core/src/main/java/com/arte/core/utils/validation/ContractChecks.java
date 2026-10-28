package com.arte.core.utils.validation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 公共数据契约的结构校验
 * <p>
 * 容量上限由值类型或调用场景指定。
 * 不替代授权、Schema 或业务配额校验。id 保留原 v1 的非空白及长度规则，
 * 与 PlatformChecks.id 的严格标识规则分开，不在迁移中收紧已有输入。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public final class ContractChecks {
    private ContractChecks() {
    }

    /**
     * 文本校验：非空、限长
     *
     * @param value 文本值
     * @param field 字段名
     * @param limit 最大字符数
     * @return 文本值或抛出异常
     */
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
