package com.arte.base.model.artifact;

import java.time.Instant;

/**
 * 产物元数据快照；文件字节由 ArtifactStore 管理。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record Artifact(
        ArtifactRef ref,
        ArtifactStatus status,
        Instant createdAt,
        Instant expiresAt
) {
}
