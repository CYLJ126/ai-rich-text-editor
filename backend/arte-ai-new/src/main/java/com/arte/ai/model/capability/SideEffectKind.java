package com.arte.ai.model.capability;

/**
 * 声明的能力副作用等级；未知等级按受限操作处理。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum SideEffectKind {
    READ_ONLY,
    RESOURCE_WRITE,
    EXTERNAL_EFFECT,
    UNKNOWN
}
