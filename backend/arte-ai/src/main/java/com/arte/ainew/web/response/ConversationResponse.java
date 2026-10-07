package com.arte.ainew.web.response;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.reference.ResourceRef;
import com.arte.ainew.pojo.conversation.Conversation;

import java.time.Instant;
import java.util.List;

/**
 * 页面使用的会话元数据，不暴露内部 owner、授权上下文或数据库哈希。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:38 ✾
 */
public record ConversationResponse(String conversationId, String title, long version, DefinitionRef chatProfile,
                                   List<ResourceRef> resources, Conversation.State state,
                                   Instant createdAt, Instant updatedAt) {
    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(conversation.conversationId(), conversation.title(), conversation.version(),
                conversation.chatProfile(), conversation.resources(), conversation.state(),
                conversation.createdAt(), conversation.updatedAt());
    }
}
