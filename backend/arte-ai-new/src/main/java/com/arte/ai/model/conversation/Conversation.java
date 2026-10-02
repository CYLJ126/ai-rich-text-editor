package com.arte.ai.model.conversation;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.validation.ChatContractChecks;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;
import java.util.List;

/**
 * 最小会话的不可变查询快照。version 用于按会话版本推进命令，不是发布版本或执行状态。
 * resources 只保存关联，读取资料及历史必须重新授权；当前聊天阶段不自动解析这些关联。
 */
public record Conversation(
        String conversationId,
        ExecutionScope scope,
        String title,
        DefinitionRef modelBindingRef,
        ConversationStatus status,
        long version,
        List<ResourceRef> resources,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public Conversation {
        conversationId = ChatContractChecks.identifier(conversationId, 64, "conversationId");
        scope = ChatContractChecks.scope(scope);
        title = ContractChecks.required(title, "title");
        if (title.isBlank() || title.length() > 256 || title.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("title must be a nonblank single-line value of at most 256 characters");
        }
        modelBindingRef = ContractChecks.required(modelBindingRef, "modelBindingRef");
        if (!"ai-binding".equals(modelBindingRef.definitionType())) {
            throw new IllegalArgumentException("modelBindingRef must refer to an AI binding");
        }
        ChatContractChecks.identifier(modelBindingRef.definitionId(), 128, "bindingId");
        ChatContractChecks.identifier(modelBindingRef.version(), 64, "bindingVersion");
        status = ContractChecks.required(status, "status");
        ChatContractChecks.positive(version, "version");
        resources = List.copyOf(ContractChecks.required(resources, "resources"));
        ChatContractChecks.times(createdAt, updatedAt);
        if (status == ConversationStatus.ACTIVE && deletedAt != null
                || status == ConversationStatus.DELETED && (deletedAt == null || deletedAt.isBefore(createdAt) || deletedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException("deletedAt must match conversation status and timestamps");
        }
    }
}
