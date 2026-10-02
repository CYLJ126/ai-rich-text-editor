package com.arte.ai.model.generation;

import com.arte.ai.model.capability.CapabilityDescriptor;
import com.arte.ai.model.message.Message;
import com.arte.base.model.schema.SchemaRef;

import java.util.List;

/**
 * 文本或多模态生成请求；工具描述仅限本次允许范围。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record GenerationRequest(
        List<Message> messages,
        ModelOptions options,
        List<CapabilityDescriptor> tools,
        SchemaRef outputSchema
) {
}
