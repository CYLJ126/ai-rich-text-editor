package com.arte.ai.service.tool;

import com.arte.ai.api.tool.ToolPolicyMerger;
import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.pojo.tool.ToolExecutionPolicy;
import com.arte.ai.pojo.tool.ToolPolicyOverride;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 默认工具策略合并器，所有覆盖只能在上一层策略基础上收紧。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Service
public class DefaultToolPolicyMerger implements ToolPolicyMerger {

    @Override
    public ToolExecutionPolicy decodePolicy(Map<String, Object> value) {
        Map<String, Object> source = requireMap(value, "defaultPolicy");
        return new ToolExecutionPolicy(
                executionMode(required(source, "executionMode")),
                duration(source, "timeout", "timeoutMillis", true),
                integer(required(source, "maxRetries"), "maxRetries"),
                duration(source, "retryBackoff", "retryBackoffMillis", false),
                integer(required(source, "maxOutputTokens"), "maxOutputTokens"),
                bool(required(source, "requiresApproval"), "requiresApproval"),
                bool(required(source, "allowsResultCache"), "allowsResultCache")
        );
    }

    @Override
    public ToolPolicyOverride decodeOverride(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return new ToolPolicyOverride(
                optional(value, "executionMode") == null ? null : executionMode(value.get("executionMode")),
                optionalDuration(value, "timeout", "timeoutMillis"),
                optional(value, "maxRetries") == null ? null : integer(value.get("maxRetries"), "maxRetries"),
                optionalDuration(value, "retryBackoff", "retryBackoffMillis"),
                optional(value, "maxOutputTokens") == null
                        ? null : integer(value.get("maxOutputTokens"), "maxOutputTokens"),
                optional(value, "requiresApproval") == null
                        ? null : bool(value.get("requiresApproval"), "requiresApproval"),
                optional(value, "allowsResultCache") == null
                        ? null : bool(value.get("allowsResultCache"), "allowsResultCache")
        );
    }

    @Override
    public Map<String, Object> encodeOverride(ToolPolicyOverride value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, "executionMode", value.executionMode() == null ? null : value.executionMode().getValue());
        put(result, "timeoutMillis", value.timeout() == null ? null : value.timeout().toMillis());
        put(result, "maxRetries", value.maxRetries());
        put(result, "retryBackoffMillis",
                value.retryBackoff() == null ? null : value.retryBackoff().toMillis());
        put(result, "maxOutputTokens", value.maxOutputTokens());
        put(result, "requiresApproval", value.requiresApproval());
        put(result, "allowsResultCache", value.allowsResultCache());
        return Map.copyOf(result);
    }

    @Override
    public ToolExecutionPolicy tighten(ToolExecutionPolicy base, ToolPolicyOverride override) {
        Objects.requireNonNull(base, "base");
        if (override == null) {
            return base;
        }
        ToolExecutionModeEnum executionMode = override.executionMode() == null
                ? base.executionMode() : override.executionMode();
        if (executionMode != base.executionMode()) {
            throw new IllegalArgumentException("executionMode cannot be changed by a policy override");
        }

        Duration timeout = override.timeout() == null ? base.timeout() : override.timeout();
        require(timeout.compareTo(base.timeout()) <= 0,
                "timeout override cannot exceed the server policy");

        int maxRetries = override.maxRetries() == null ? base.maxRetries() : override.maxRetries();
        require(maxRetries <= base.maxRetries(),
                "maxRetries override cannot exceed the server policy");

        Duration retryBackoff = override.retryBackoff() == null
                ? base.retryBackoff() : override.retryBackoff();
        require(retryBackoff.compareTo(base.retryBackoff()) >= 0,
                "retryBackoff override cannot be shorter than the server policy");

        int maxOutputTokens = override.maxOutputTokens() == null
                ? base.maxOutputTokens() : override.maxOutputTokens();
        require(maxOutputTokens <= base.maxOutputTokens(),
                "maxOutputTokens override cannot exceed the server policy");

        boolean requiresApproval = override.requiresApproval() == null
                ? base.requiresApproval() : override.requiresApproval();
        require(!base.requiresApproval() || requiresApproval,
                "requiresApproval cannot be disabled by a policy override");

        boolean allowsResultCache = override.allowsResultCache() == null
                ? base.allowsResultCache() : override.allowsResultCache();
        require(base.allowsResultCache() || !allowsResultCache,
                "result caching cannot be enabled by a policy override");

        return new ToolExecutionPolicy(executionMode, timeout, maxRetries, retryBackoff,
                maxOutputTokens, requiresApproval, allowsResultCache);
    }

    private ToolExecutionModeEnum executionMode(Object value) {
        if (value instanceof ToolExecutionModeEnum mode) {
            return mode;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        for (ToolExecutionModeEnum mode : ToolExecutionModeEnum.values()) {
            if (mode.name().equalsIgnoreCase(normalized) || mode.getValue().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unsupported executionMode: " + value);
    }

    private Duration duration(Map<String, Object> value, String durationKey,
                              String millisKey, boolean positive) {
        Duration result = optionalDuration(value, durationKey, millisKey);
        if (result == null) {
            throw new IllegalArgumentException(durationKey + " is required");
        }
        if (positive && (result.isZero() || result.isNegative())) {
            throw new IllegalArgumentException(durationKey + " must be positive");
        }
        return result;
    }

    private Duration optionalDuration(Map<String, Object> value, String durationKey, String millisKey) {
        Object duration = optional(value, durationKey);
        if (duration != null) {
            if (duration instanceof Duration typed) {
                return typed;
            }
            if (duration instanceof Number number) {
                return Duration.ofMillis(number.longValue());
            }
            return Duration.parse(String.valueOf(duration));
        }
        Object millis = optional(value, millisKey);
        return millis == null ? null : Duration.ofMillis(longValue(millis, millisKey));
    }

    private int integer(Object value, String name) {
        long parsed = longValue(value, name);
        if (parsed > Integer.MAX_VALUE || parsed < Integer.MIN_VALUE) {
            throw new IllegalArgumentException(name + " is outside integer range");
        }
        return (int) parsed;
    }

    private long longValue(Object value, String name) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be numeric", exception);
        }
    }

    private boolean bool(Object value, String name) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if ("true".equalsIgnoreCase(String.valueOf(value))) {
            return true;
        }
        if ("false".equalsIgnoreCase(String.valueOf(value))) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be boolean");
    }

    private Map<String, Object> requireMap(Map<String, Object> value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    private Object required(Map<String, Object> value, String key) {
        Object result = optional(value, key);
        if (result == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return result;
    }

    private Object optional(Map<String, Object> value, String key) {
        return value == null ? null : value.get(key);
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
