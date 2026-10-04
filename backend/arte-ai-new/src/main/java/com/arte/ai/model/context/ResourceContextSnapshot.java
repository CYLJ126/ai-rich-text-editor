package com.arte.ai.model.context;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.message.Message;
import com.arte.ai.validation.ChatContractChecks;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;
import java.util.List;

/** 无需会话的资料上下文；摘要覆盖实际消息、来源、范围及预算，过期不自动删除正文。 */
public record ResourceContextSnapshot(String snapshotId, ExecutionScope scope, DefinitionRef bindingRef,
                                      List<Message> messages, List<ContextFragment> fragments, ContextBudget budget,
                                      String contentDigest, String selectionDigest, Instant createdAt, Instant expiresAt) {
    public ResourceContextSnapshot {
        snapshotId = ChatContractChecks.identifier(snapshotId, 64, "snapshotId");
        scope = ContractChecks.required(scope, "scope");
        bindingRef = ContractChecks.required(bindingRef, "bindingRef");
        messages = List.copyOf(ContractChecks.required(messages, "messages"));
        fragments = List.copyOf(ContractChecks.required(fragments, "fragments"));
        if (messages.isEmpty() || messages.size() > 128 || fragments.size() > 512
                || fragments.stream().map(ContextFragment::citationId).distinct().count() != fragments.size())
            throw new IllegalArgumentException("invalid resource context size");
        budget = ContractChecks.required(budget, "budget");
        if (!"ai-binding".equals(bindingRef.definitionType()) || budget.contextWindowTokens() == null)
            throw new IllegalArgumentException("resource context requires binding and token budget");
        contentDigest = ChatContractChecks.digest(contentDigest, "contentDigest");
        selectionDigest = ChatContractChecks.digest(selectionDigest, "selectionDigest");
        createdAt = ContractChecks.required(createdAt, "createdAt");
        expiresAt = ContractChecks.required(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("context expiry must follow creation");
    }
}
