package com.arte.core.serialize;

import com.arte.core.utils.ContentDigests;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * v1 内部规范化编码：对象键排序、精确数字、保留数组顺序；不用于不可信多态反序列化。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class CanonicalJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();

    private CanonicalJson() {
    }

    public static Map<String, Object> fields(Object... pairs) {
        var result = new TreeMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    public static String encode(Object value) {
        return normalize(MAPPER.writeValueAsString(value));
    }

    public static String normalize(String json) {
        return MAPPER.writeValueAsString(sorted(MAPPER.readValue(json, Object.class)));
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach((key, item) -> result.put((String) key, sorted(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(CanonicalJson::sorted).toList();
        }
        if (value instanceof BigDecimal number) {
            return number.stripTrailingZeros();
        }
        return value;
    }

    public static String digest(Object value) {
        return ContentDigests.sha256(encode(value));
    }
}
