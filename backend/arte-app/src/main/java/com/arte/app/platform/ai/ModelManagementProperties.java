package com.arte.app.platform.ai;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 密钥由部署密钥系统注入，不写入仓库。SM4 加密密钥为 16 字节；独立 HMAC 指纹密钥还可保留旧 32 字节值。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-model-management", ignoreUnknownFields = false)
public record ModelManagementProperties(boolean enabled, String activeKeyId, String fingerprintKeyId,
                                        Map<String, String> encryptionKeys) {

    public ModelManagementProperties {
        encryptionKeys = encryptionKeys == null ? Map.of() : Map.copyOf(encryptionKeys);
    }

    @Override
    public @NonNull String toString() {
        return "ModelManagementProperties[REDACTED]";
    }
}
