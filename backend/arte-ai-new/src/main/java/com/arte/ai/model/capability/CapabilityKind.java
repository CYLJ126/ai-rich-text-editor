package com.arte.ai.model.capability;

/**
 * 并列的类型化原子能力；协议、Skill、Workflow 和 Agent 不属于能力种类。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum CapabilityKind {
    MODEL,
    EMBEDDING,
    MEDIA,
    TOOL,
    APPLICATION
}
