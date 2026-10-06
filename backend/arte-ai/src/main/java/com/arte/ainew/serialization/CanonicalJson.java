package com.arte.ainew.serialization;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
        return sha256(encode(value));
    }

    public static String sha256(String text) {
        return HexFormat.of().formatHex(digester().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 与既有执行存储相同的 UTF-8 长度前缀哈希；避免拼接分隔符歧义。
     */
    public static String key(String... parts) {
        var digest = digester();
        for (var part : parts) {
            var bytes = part.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digester() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
