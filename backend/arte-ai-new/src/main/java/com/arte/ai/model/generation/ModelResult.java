package com.arte.ai.model.generation;

import com.arte.ai.model.budget.Usage;
import com.arte.ai.model.message.ContentPart;
import com.arte.ai.model.tool.StructuredValue;
import com.arte.ai.model.tool.ToolCall;

import java.util.List;

/**
 * 模型生成结果与工具请求；业务工具由运行时统一治理后执行。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ModelResult(
        List<ContentPart> output,
        List<ToolCall> toolCalls,
        StructuredValue structuredOutput,
        Usage usage
) {
}
