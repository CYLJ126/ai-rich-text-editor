package com.arte.core.utils.platform;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 公共值对象的结构校验；不承担成员／资源授权判断。
 */
public final class PlatformChecks {
    private PlatformChecks() {
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public static void id(String value, String field) {
        require(value != null && !value.isBlank() && value.length() <= 256
                        && value.equals(value.strip()) && value.chars().noneMatch(Character::isISOControl),
                field + " must be a nonblank identifier of at most 256 characters");
    }

    public static void token(String value, String field) {
        require(value != null && value.length() <= 96
                        && value.matches("[a-z][a-z0-9_-]*(?::[a-z][a-z0-9_-]*)*"),
                field + " must be a registered-name shaped token");
    }

    public static void version(long value, String field) {
        require(value >= 0, field + " must be nonnegative");
    }

    public static String name(String value) {
        require(value != null, "name is required");
        String normalized = value.strip();
        require(!normalized.isEmpty() && normalized.length() <= 100, "name length must be 1..100");
        return normalized;
    }

    public static String description(String value) {
        String normalized = value == null ? "" : value;
        require(normalized.length() <= 1000, "description length must be at most 1000");
        return normalized;
    }

    public static <K, V> Map<K, Set<V>> copySetMap(Map<K, Set<V>> input) {
        Map<K, Set<V>> copy = new HashMap<>();
        input.forEach((key, values) -> copy.put(Objects.requireNonNull(key), Set.copyOf(values)));
        return Map.copyOf(copy);
    }
}
