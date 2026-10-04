package com.arte.ainew.pojo.conversation;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.reference.ResourceRef;
import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 会话元数据及选入资料，不存储另一份 Invocation 状态；version 用于提交轮次／修改会话的并发校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record Conversation(String conversationId, ExecutionOwner owner, String title, long version,
                           DefinitionRef chatProfile, List<ResourceRef> resources, State state,
                           Instant createdAt, Instant updatedAt) implements Serializable {
    public enum State {ACTIVE, DELETED}

    public Conversation {
        ContractChecks.id(conversationId, "conversationId");
        Objects.requireNonNull(owner, "owner");
        ContractChecks.text(title, "title", 256);
        ContractChecks.range(version, "version", 0, Long.MAX_VALUE);
        if (chatProfile != null) {
            chatProfile.requireType("chat-profile");
        }
        resources = ContractChecks.list(resources, "resources", 0, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(resources, "resources");
        Objects.requireNonNull(state, "state");
        ContractChecks.ordered(createdAt, updatedAt, "updatedAt");
    }
}
