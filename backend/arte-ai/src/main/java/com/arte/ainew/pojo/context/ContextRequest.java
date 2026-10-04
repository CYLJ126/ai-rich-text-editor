package com.arte.ainew.pojo.context;

import com.arte.ainew.common.reference.ResourceRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.generation.ChatMessage;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 上下文请求内容
 * <p>
 * 显式输入及选择条件，不表示已经授权或实际选入。history=null 表示不加载历史，空 memoryIds 表示不加载记忆。
 * <p>
 * 描述用户显式带入的消息或资料、资源引用与目标范围、历史和记忆的选择条件，以及输入容量、输出预留等上下文预算约束；不默认读取全库或全部历史。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:08 ✾
 */
public record ContextRequest(List<ChatMessage> messages, HistorySelection history, List<String> memoryIds,
                             List<ResourceRef> resources, ResourceRef target,
                             ContextBudget budget) implements Serializable {
    /**
     * 固定会话版本，明确选择的轮次；maxTurns 是上限，空 turnIds 只选择该版本路径的最近轮次。
     */
    public record HistorySelection(String conversationId, long conversationVersion, List<String> turnIds,
                                   int maxTurns) implements Serializable {
        public HistorySelection {
            ContractChecks.id(conversationId, "conversationId");
            ContractChecks.range(conversationVersion, "conversationVersion", 0, Long.MAX_VALUE);
            ContractChecks.range(maxTurns, "maxTurns", 1, ContractChecks.MAX_ITEMS);
            turnIds = ContractChecks.list(turnIds, "turnIds", 0, maxTurns);
            turnIds.forEach(id -> ContractChecks.id(id, "turnId"));
            ContractChecks.unique(turnIds, "turnIds");
        }
    }

    public ContextRequest {
        messages = ContractChecks.list(messages, "messages", 1, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(messages.stream().map(ChatMessage::messageId).toList(), "message IDs");
        ContractChecks.require(messages.stream().mapToLong(ChatMessage::characterCount).sum() <= ContractChecks.MAX_TEXT_CHARS,
                "Context messages exceed character limit");
        memoryIds = ContractChecks.list(memoryIds, "memoryIds", 0, ContractChecks.MAX_ITEMS);
        memoryIds.forEach(id -> ContractChecks.id(id, "memoryId"));
        ContractChecks.unique(memoryIds, "memoryIds");
        resources = ContractChecks.list(resources, "resources", 0, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(resources, "resources");
        Objects.requireNonNull(budget, "budget");
    }
}
