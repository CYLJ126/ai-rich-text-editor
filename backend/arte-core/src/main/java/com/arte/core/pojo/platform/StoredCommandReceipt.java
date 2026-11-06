package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 内部持久化回执信封；resultSchema 由受信注册表解析，不以客户端类名反序列化。
 *
 * @param identity 回执对应的命令幂等身份
 * @param requestDigest 规范化命令请求的摘要，用于比对重复请求
 * @param resultSchema 由受信注册表解析的回执结果结构标识
 * @param receipt 已持久化的命令执行回执
 * @param version 回执存储记录的版本
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
