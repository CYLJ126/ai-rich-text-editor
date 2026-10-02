package com.arte.ai.api.conversation;

import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.conversation.Conversation;
import com.arte.ai.model.conversation.ConversationStatus;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.spi.security.ChatAccessPolicy;
import com.arte.ai.spi.store.ChatStore;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.validation.ContractChecks;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * 主体隔离的会话管理；资料关联不自动授予读取权限。
 */
public class ConversationService {
    private final ChatStore store;
    private final ChatAccessPolicy access;
    private final Clock clock;

    public ConversationService(ChatStore store, ChatAccessPolicy access, Clock clock) {
        this.store = ContractChecks.required(store, "store");
        this.access = ContractChecks.required(access, "access");
        this.clock = ContractChecks.required(clock, "clock");
    }

    public Conversation create(ExecutionContext viewer, String title, DefinitionRef binding) {
        access.requireAllowed(viewer, binding);
        var now = ChatValues.now(clock);
        return store.create(new Conversation(UUID.randomUUID().toString(), viewer.scope(), title, binding,
                ConversationStatus.ACTIVE, 1, List.of(), now, now, null));
    }

    public Conversation find(ExecutionContext viewer, String id) {
        var conversation = store.conversation(viewer.scope(), ContractChecks.identifier(id, "conversationId"))
                .orElseThrow(() -> ChatValues.failure(CommonErrorCode.NOT_FOUND, "conversation"));
        access.requireAllowed(viewer, conversation.modelBindingRef());
        if (conversation.status() != ConversationStatus.ACTIVE)
            throw ChatValues.failure(CommonErrorCode.NOT_FOUND, "conversation");
        return conversation;
    }

    public List<Conversation> list(ExecutionContext viewer, DefinitionRef binding, String title, int offset, int limit) {
        access.requireAllowed(viewer, binding);
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100 || title != null && title.length() > 256)
            throw new IllegalArgumentException("invalid conversation page");
        var result = store.conversations(viewer.scope(), title, offset, limit);
        result.forEach(conversation -> access.requireAllowed(viewer, conversation.modelBindingRef()));
        return result;
    }

    public Conversation rename(ExecutionContext viewer, String id, long version, String title) {
        var previous = find(viewer, id);
        // Validate the new value before entering storage, retaining immutable binding and resources.
        new Conversation(id, viewer.scope(), title, previous.modelBindingRef(), previous.status(), version,
                previous.resources(), previous.createdAt(), ChatValues.now(clock), null);
        return store.rename(viewer.scope(), id, version, title, ChatValues.now(clock));
    }

    public Conversation delete(ExecutionContext viewer, String id, long version) {
        find(viewer, id);
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        return store.delete(viewer.scope(), id, version, ChatValues.now(clock));
    }
}
