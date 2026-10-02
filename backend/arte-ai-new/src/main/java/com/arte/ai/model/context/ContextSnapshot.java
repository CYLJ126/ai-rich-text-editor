package com.arte.ai.model.context;

import com.arte.ai.model.message.Message;

import java.time.Instant;
import java.util.List;

/**
 * 执行时固定的上下文值快照，不拥有来源领域的正式内容。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ContextSnapshot(
        String snapshotId,
        List<Message> messages,
        List<ContextFragment> fragments,
        String contentDigest,
        Instant createdAt
) {
}
