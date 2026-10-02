package com.arte.ai.model.context;

import com.arte.ai.model.message.Message;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;

import java.util.List;

/**
 * 显式资料、消息、历史及记忆的选择请求；不默认读取全库或全部历史。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ContextRequest(
        List<Message> messages,
        List<SourceRef> selectedSources,
        String conversationId,
        String memoryPurpose,
        ResourceRef target,
        int contentBudget
) {
}
