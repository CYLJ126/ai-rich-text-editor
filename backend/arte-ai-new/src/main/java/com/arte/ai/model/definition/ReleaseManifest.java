package com.arte.ai.model.definition;

import com.arte.base.model.resource.ResourceRef;

import java.time.Instant;
import java.util.List;

/**
 * 固定版本依赖及校验结果的发布清单；不保证供应商模型行为不变。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ReleaseManifest(
        DefinitionRef ref,
        List<DefinitionRef> definitions,
        ResourceRef validationResultRef,
        DefinitionStatus status,
        Instant publishedAt
) {
}
