package com.arte.ai.model.message;

import java.util.List;

/**
 * 类型化消息快照，不依赖供应商 SDK。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record Message(
        MessageRole role,
        List<ContentPart> parts
) {
}
