package com.arte.ai.model.tool;

/**
 * 模型提出的工具请求；此记录不代表授权或工具已执行。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ToolCall(
        String callId,
        String toolName,
        StructuredValue arguments
) {
}
