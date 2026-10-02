package com.arte.base.model.resource;

/**
 * 资源、正式版本或草稿及范围的通用引用；范围结构由资源领域解释。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ResourceRef(
        String resourceType,
        String resourceId,
        String version,
        String draftId,
        String rangeRef,
        String contentDigest
) {
}
