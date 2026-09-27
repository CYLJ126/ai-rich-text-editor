package com.arte.ai.service.tool.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 为审批参数生成与 Map 迭代顺序无关的 SHA-256 摘要。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
@RequiredArgsConstructor
public class ToolArgumentDigest {

    private final ObjectMapper objectMapper;

    public String digest(Object value) {
        try {
            Object serializable = objectMapper.convertValue(value, Object.class);
            byte[] bytes = objectMapper.writeValueAsString(canonicalize(serializable))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to digest approval arguments", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::canonicalize).toList();
        }
        if (value instanceof Object[] array) {
            return java.util.Arrays.stream(array).map(this::canonicalize).toList();
        }
        return value;
    }
}
