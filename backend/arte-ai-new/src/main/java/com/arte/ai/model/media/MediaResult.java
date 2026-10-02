package com.arte.ai.model.media;

import com.arte.ai.model.budget.Usage;
import com.arte.base.model.artifact.ArtifactRef;

import java.util.List;

/**
 * 已完成的媒体产物及用量；产物存在不表示已应用到业务资源。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record MediaResult(
        List<ArtifactRef> artifacts,
        Usage usage
) implements MediaSubmission {
}
