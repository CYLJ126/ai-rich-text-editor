package com.arte.base.model.resource;

/**
 * 可定位的来源引用；查看和重用时重新授权。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record SourceRef(
        ResourceRef resource,
        String citationId
) {
}
