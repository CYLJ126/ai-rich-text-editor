package com.arte.ainew.common.search;

import com.arte.ainew.pojo.search.ConversationSearch.Direction;
import com.arte.ainew.serialization.CanonicalJson;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * 搜索分页及聊天历史游标的编码、签名和校验工具，用于绑定会话、归属及翻页边界。
 * 使用 HMAC 防止游标被篡改，保留排序数字精度，不包含 PIT、索引名或命中正文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class SearchCursor {

    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /**
     * 搜索分页游标载荷，绑定搜索会话、规则版本、翻页方向及排序边界。
     */
    public record Page(String session, String rules, Direction direction, List<Object> after, boolean opposite) {
        public Page {
            if (session == null || !session.matches("[a-f0-9-]{36}") || rules == null || rules.length() > 256 || direction == null
                    || after == null || !(after.isEmpty() || after.size() == 4) || direction == Direction.FIRST && !after.isEmpty())
                throw invalid();
            for (var value : after)
                if (value != null && !(value instanceof String) && !(value instanceof Number)) throw invalid();
            after = List.copyOf(after);
        }
    }

    /**
     * 独立历史游标载荷，绑定主体、会话版本、轮次边界及到期时间，不依赖搜索 PIT。
     */
    public record History(String owner, String conversation, long version, int size, Direction direction, long sequence,
                          String turn, long expiresAt) {
        public History {
            if (owner == null || !owner.matches("[a-f0-9]{64}") || conversation == null || conversation.isBlank() || conversation.length() > 256
                    || version < 0 || size < 1 || size > 10 || direction == null || direction == Direction.FIRST || sequence < 1 || turn == null || turn.isBlank() || turn.length() > 256 || expiresAt <= 0)
                throw invalid();
        }
    }

    /**
     * 生成用于游标签名的安全随机密钥，并编码为 URL 安全字符串。
     */
    public static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * 将租户、空间和主体归属转换为稳定摘要，用于校验游标归属。
     */
    public static String owner(com.arte.ainew.common.execution.ExecutionOwner o) {
        return CanonicalJson.key(o.tenantId(), o.workspaceId(), o.subjectId());
    }

    /**
     * 将载荷编码为 JSON，并附加 HMAC 签名，生成可传输的游标。
     */
    public static String sign(Object payload, String key) {
        String body = ENCODER.encodeToString(JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        return body + "." + ENCODER.encodeToString(mac(body, key));
    }

    /**
     * 未验签地读取分页载荷，仅供定位搜索会话；使用载荷前仍须调用 verify 校验签名。
     */
    public static Page peek(String token) {
        return decode(token, Page.class);
    }

    /**
     * 校验签名并解码指定类型的载荷；归属、版本及有效期由调用方继续校验。
     */
    public static <T> T verify(String token, String key, Class<T> type) {
        try {
            String[] parts = parts(token);
            if (!MessageDigest.isEqual(mac(parts[0], key), DECODER.decode(parts[1]))) throw invalid();
            return decode(token, type);
        } catch (SearchFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 解码游标 JSON，保留数字精度，并将格式错误统一转换为游标无效。
     */
    private static <T> T decode(String token, Class<T> type) {
        try {
            return JSON.readValue(DECODER.decode(parts(token)[0]), type);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 检查游标长度及载荷、签名两段结构，返回待解码的字符串。
     */
    private static String[] parts(String token) {
        if (token == null || token.length() > 16384) throw invalid();
        var parts = token.split("\\.", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].length() != 43) throw invalid();
        return parts;
    }

    /**
     * 使用指定密钥计算 HMAC-SHA256，用于生成或校验签名。
     */
    private static byte[] mac(String value, String key) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(DECODER.decode(key), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC unavailable", failure);
        }
    }

    private static SearchFailure invalid() {
        return new SearchFailure(SearchFailure.Code.INVALID_CURSOR);
    }
}
