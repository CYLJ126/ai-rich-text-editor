package com.arte.ai.model.definition;

/**
 * 定义与发布状态；修改已发布契约需要新版本。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum DefinitionStatus {
    DRAFT,
    VALIDATED,
    PUBLISHED,
    DEPRECATED,
    DISABLED
}
