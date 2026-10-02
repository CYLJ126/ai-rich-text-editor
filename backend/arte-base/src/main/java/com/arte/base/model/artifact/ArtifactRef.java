package com.arte.base.model.artifact;

import com.arte.base.model.resource.ResourceRef;

/**
 * 通用产物引用及完整性元数据；字节存储与业务附件关系分开。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ArtifactRef(
        String artifactId,
        ResourceRef owner,
        String mediaType,
        long sizeBytes,
        String contentDigest
) {
}
