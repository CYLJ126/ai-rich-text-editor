package com.arte.ainew.serialization;

import com.arte.core.utils.ContentDigests;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * AI v1 编码兼容入口，通用规范化与摘要委托 core。
 * 证据 JSON 的固定容量和深度策略留在 AI，保持已有快照与幂等摘要格式。
 */
public final class CanonicalJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();

    private CanonicalJson() {
    }

    public static Map<String, Object> fields(Object... pairs) {
        return com.arte.core.serialize.CanonicalJson.fields(pairs);
    }

    public static String encode(Object value) {
        return com.arte.core.serialize.CanonicalJson.encode(value);
    }

    public static String normalize(String json) {
        return com.arte.core.serialize.CanonicalJson.normalize(json);
    }

    /**
     * 有界标准 JSON 到工具值树；保留精确数字，不加载类型名。
     */
    public static com.arte.core.pojo.value.StructuredValue structured(String json) {
        if (json == null) return null;
        com.arte.ainew.common.validation.ContractChecks.require(json.getBytes(StandardCharsets.UTF_8).length <= 32768, "Evidence JSON exceeds limit");
        return structuredValue(MAPPER.readValue(json, Object.class), 0);
    }

    private static com.arte.core.pojo.value.StructuredValue structuredValue(Object value, int depth) {
        com.arte.ainew.common.validation.ContractChecks.require(depth <= 24, "Evidence JSON too deep");
        if (value == null) return com.arte.core.pojo.value.StructuredValue.NullValue.INSTANCE;
        if (value instanceof Map<?, ?> map) {
            var fields = new TreeMap<String, com.arte.core.pojo.value.StructuredValue>();
            map.forEach((k, v) -> fields.put((String) k, structuredValue(v, depth + 1)));
            return new com.arte.core.pojo.value.StructuredValue.ObjectValue(fields);
        }
        if (value instanceof List<?> list)
            return new com.arte.core.pojo.value.StructuredValue.ArrayValue(list.stream().map(v -> structuredValue(v, depth + 1)).toList());
        if (value instanceof String text) return new com.arte.core.pojo.value.StructuredValue.StringValue(text);
        if (value instanceof Boolean b) return new com.arte.core.pojo.value.StructuredValue.BooleanValue(b);
        if (value instanceof Number n)
            return new com.arte.core.pojo.value.StructuredValue.NumberValue(new BigDecimal(n.toString()));
        throw new IllegalArgumentException("Unsupported evidence JSON");
    }

    public static String digest(Object value) {
        return com.arte.core.serialize.CanonicalJson.digest(value);
    }

    public static String sha256(String text) {
        return ContentDigests.sha256(text);
    }

    public static String key(String... parts) {
        return ContentDigests.key(parts);
    }
}
