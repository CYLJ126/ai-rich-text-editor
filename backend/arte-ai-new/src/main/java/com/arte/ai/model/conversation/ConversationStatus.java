package com.arte.ai.model.conversation;

/**
 * 会话可用性，不表示任何模型执行的状态。删除不自动取消已发送的请求或清理其账本。
 */
public enum ConversationStatus {
    ACTIVE,
    DELETED
}
