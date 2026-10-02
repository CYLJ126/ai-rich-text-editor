package com.arte.ai.model.context;

import com.arte.base.model.resource.SourceRef;

/**
 * 实际使用的内容片段、来源、引用标识及裁剪说明；派生摘要不得冒充全文。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ContextFragment(
        String citationId,
        SourceRef source,
        String content,
        boolean truncated,
        String coverageDescription
) {
}
