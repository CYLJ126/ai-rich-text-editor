package com.arte.ai.model.message;

/**
 * 文本内容部件；来源由上下文片段关联。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record TextPart(
        String text
) implements ContentPart {
}
