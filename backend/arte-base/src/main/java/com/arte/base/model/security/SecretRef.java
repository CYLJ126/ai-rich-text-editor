package com.arte.base.model.security;

import com.arte.base.validation.ContractChecks;

/**
 * 凭据引用，不携带明文。secretId 必填，version 可为空表示由凭据提供者解析当前版本。
 * 凭据访问授权、轮换、解析与加密存储归基础设施和连接运行时。
 */
public record SecretRef(String secretId, String version) {

    public SecretRef {
        secretId = ContractChecks.identifier(secretId, "secretId");
        version = ContractChecks.optionalIdentifier(version, "version");
    }
}
