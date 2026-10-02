package com.arte.ai.model.message;

/**
 * 消息在模型交互中的角色；角色不改变资料信任或用户授权。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}
