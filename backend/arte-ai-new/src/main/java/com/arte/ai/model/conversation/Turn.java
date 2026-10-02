package com.arte.ai.model.conversation;

import com.arte.ai.model.message.Message;

import java.util.List;

/**
 * 轮次输入及执行关联的记录快照，不自动收录为知识资产。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record Turn(
        String turnId,
        String conversationId,
        List<Message> input,
        String contextSnapshotId,
        String executionId
) {
}
