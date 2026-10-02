package com.arte.ai.model.context;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.message.Message;
import com.arte.ai.validation.ChatContractChecks;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;
import java.util.List;

/**
 * 服务端实际组装的不可变上下文快照，固定会话版本、模型绑定、历史选择、来源片段及容量事实。
 * 摘要覆盖规范化上下文，不是供应商完整协议正文摘要；两者分别核对。expiresAt 不自动删除字节。
 * 快照保存不授予资料、历史或外发权限，过期快照不能直接作为新调用输入。
 */
public record ContextSnapshot(
        String snapshotId,
        String conversationId,
        ExecutionScope scope,
        long conversationVersion,
        DefinitionRef modelBindingRef,
        List<Message> messages,
        List<ContextFragment> fragments,
        List<ContextHistoryRef> history,
        ContextBudget budget,
        String contentDigest,
        Instant createdAt,
        Instant expiresAt
) {
    public ContextSnapshot {
        snapshotId = ChatContractChecks.identifier(snapshotId, 64, "snapshotId");
        conversationId = ChatContractChecks.identifier(conversationId, 64, "conversationId");
        scope = ChatContractChecks.scope(scope);
        ChatContractChecks.positive(conversationVersion, "conversationVersion");
        modelBindingRef = ContractChecks.required(modelBindingRef, "modelBindingRef");
        if (!"ai-binding".equals(modelBindingRef.definitionType()))
            throw new IllegalArgumentException("modelBindingRef must refer to an AI binding");
        ChatContractChecks.identifier(modelBindingRef.definitionId(), 128, "bindingId");
        ChatContractChecks.identifier(modelBindingRef.version(), 64, "bindingVersion");
        messages = List.copyOf(ContractChecks.required(messages, "messages"));
        if (messages.isEmpty()) throw new IllegalArgumentException("snapshot messages must not be empty");
        fragments = List.copyOf(ContractChecks.required(fragments, "fragments"));
        history = List.copyOf(ContractChecks.required(history, "history"));
        if (fragments.stream().map(ContextFragment::citationId).distinct().count() != fragments.size()
                || history.stream().map(ContextHistoryRef::turnId).distinct().count() != history.size()) {
            throw new IllegalArgumentException("context citations and historical turns must be unique");
        }
        budget = ContractChecks.required(budget, "budget");
        contentDigest = ChatContractChecks.digest(contentDigest, "contentDigest");
        createdAt = ContractChecks.required(createdAt, "createdAt");
        expiresAt = ContractChecks.required(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("expiresAt must follow createdAt");
    }

    public boolean isExpiredAt(Instant now) {
        return !ContractChecks.required(now, "now").isBefore(expiresAt);
    }
}
