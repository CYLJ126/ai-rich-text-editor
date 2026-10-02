package com.arte.ai.model.tool;

import com.arte.base.model.artifact.ArtifactRef;
import com.arte.base.model.resource.SourceRef;

import java.util.List;

/**
 * 工具的结构化结果、来源及产物；工具成功与领域保存的含义由工具契约明确。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ToolResult(
        StructuredValue output,
        List<SourceRef> sources,
        List<ArtifactRef> artifacts
) {
}
