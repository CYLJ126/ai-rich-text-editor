package com.arte.core.utils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * UTF-8 内容摘要与 v1 长度前缀组合键；保持既有持久化键和摘要的字节语义。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/28 17:02 ✾
 */
public final class ContentDigests {

    private ContentDigests() {
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
