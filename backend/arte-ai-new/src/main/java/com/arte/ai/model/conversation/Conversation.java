package com.arte.ai.model.conversation;

import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.resource.ResourceRef;

import java.util.List;

/**
 * 会话的只读值快照；资料关联与资料权限分开。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record Conversation(
        String conversationId,
        ExecutionScope scope,
        String title,
        long version,
        List<ResourceRef> resources
) {
}
