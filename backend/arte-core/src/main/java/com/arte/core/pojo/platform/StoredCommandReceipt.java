package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 内部持久化回执信封；resultSchema 由受信注册表解析，不以客户端类名反序列化。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record StoredCommandReceipt(
        CommandIdentity identity,
        String requestDigest,
        String resultSchema,
        CommandReceipt<?> receipt,
        long version) {
    public StoredCommandReceipt {
        Objects.requireNonNull(identity, "identity");
        PlatformChecks.id(requestDigest, "requestDigest");
        PlatformChecks.token(resultSchema, "resultSchema");
        Objects.requireNonNull(receipt, "receipt");
        PlatformChecks.version(version, "version");
    }
}
