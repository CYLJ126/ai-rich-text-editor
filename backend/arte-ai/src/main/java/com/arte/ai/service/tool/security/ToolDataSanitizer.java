package com.arte.ai.service.tool.security;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工具日志、事件和持久化快照共用的递归脱敏器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
public class ToolDataSanitizer {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(password|secret|token|api[-_]?key|private[-_]?key|authorization|cookie).*"
    );
    private static final Pattern CREDENTIAL_REFERENCE = Pattern.compile(
            "(?i).*(credential[-_.]?reference|credentialReference).*"
    );

    public Map<String, Object> sanitize(Map<String, ?> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (CREDENTIAL_REFERENCE.matcher(key).matches()) return;
            result.put(key, SENSITIVE.matcher(key).matches() ? "***" : sanitizeValue(value));
        });
        return Collections.unmodifiableMap(result);
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, item) -> converted.put(String.valueOf(key), item));
            return sanitize(converted);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::sanitizeValue).toList();
        }
        return value;
    }
}
