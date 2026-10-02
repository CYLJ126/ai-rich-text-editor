package com.arte.ai.model.tool;

import com.arte.ai.model.definition.DefinitionRef;

/**
 * 准备执行的工具调用；绑定、参数、授权及副作用仍需执行边界校验。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ToolInvocation(
        String callId,
        DefinitionRef toolRef,
        StructuredValue input
) {
}
