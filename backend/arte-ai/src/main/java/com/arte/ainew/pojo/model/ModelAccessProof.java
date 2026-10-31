package com.arte.ainew.pojo.model;

import com.arte.core.pojo.access.ResourceOwnership;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.AuthorizationVersions;

import java.util.Objects;

/**
 * 本次公共模型资格依据
 * <p>
 * 只用于内存复核，不作为永久许可或改写执行归属。
 *
 * @param resource         由精确绑定映射得到的稳定模型资源引用
 * @param ownership        模型资源的主空间归属，独立于创建者和本次调用主体
 * @param versions         本次公共授权的版本依据，最终写入仍按当前事实重新判定
 * @param scopeVersion     本次采用的空间迁移清单版本，从 1 开始
 * @param definitionSource 原精确版本的定义权威来源，不随当前读取目录的模式改写
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
public record ModelAccessProof(ResourceRef resource, ResourceOwnership ownership, AuthorizationVersions versions,
                               long scopeVersion, ModelPublication.Source definitionSource) {

    public ModelAccessProof {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(ownership);
        Objects.requireNonNull(versions);
        Objects.requireNonNull(definitionSource);
        if (scopeVersion < 1 || versions.aclVersion() == null || !resource.tenantId().equals(ownership.tenantId())) {
            throw new IllegalArgumentException("Incomplete model ACL proof");
        }
    }
}
