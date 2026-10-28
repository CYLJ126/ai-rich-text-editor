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
 * 描述用户显式带入的消息或资料、资源引用与目标范围、历史和记忆的选择条件，以及输入容量、输出预留等上下文预算约束；不默认读取全库或全部历史。
 * <p>
 * 显式输入及选择条件，不表示已经授权或实际选入。history=null 表示不加载历史，空 memoryIds 表示不加载记忆。
 *
 * @param messages  本次显式输入的有序消息列表，至少一条，消息 ID 不重复，保存为不可变列表；实际角色、内容类型及场景容量限制由入口和组装服务继续校验。
 * @param history   可选的历史选择条件，固定会话及版本；上下文组装时为空则不加载历史，聊天入口可由服务端生成该条件，实际选入轮次记录到 ContextSnapshot。
 * @param memoryIds 显式选择的记忆标识列表，不重复，空列表表示不加载记忆；当前文本上下文组装服务仅支持空列表。
 * @param resources 显式选入的资源固定版本或草稿引用列表，不重复，不代表已获读取或外发权限；当前文本上下文组装服务仅支持空列表。
 * @param target    可选的目标资源及范围引用，用于声明本次动作针对的对象，不表示已修改或保存资源；当前文本上下文组装服务要求为空。
 * @param budget    本次上下文的 Token 容量约束，不能为空，包含模型窗口、输入上限及输出和工具预留；不是费用预算，须与解析后的固定模型绑定匹配。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:08 ✾
 */
public record ContextRequest(List<ChatMessage> messages, HistorySelection history,
                             List<String> memoryIds, List<ResourceRef> resources,
                             ResourceRef target, ContextBudget budget) implements Serializable {

    /**
     * 固定会话版本，明确选择的轮次；maxTurns 是上限，空 turnIds 只选择该版本路径的最近轮次。
     *
     * @param conversationId      历史来源会话的标识，读取时仍须校验当前主体归属及访问资格。
     * @param conversationVersion 历史选择所依据的非负会话版本，用于并发一致性校验；幂等重放复用原受理快照，不改读最新历史。
     * @param turnIds             显式选择的有序轮次标识列表，不重复且数量不超过 maxTurns；请求中为空表示选择最近轮次，快照中则必须记录实际选入的轮次。当前历史加载器仅接受空列表作为选择条件。
     * @param maxTurns            选择历史轮次的数量上限，结构范围为 1～ContractChecks.MAX_ITEMS，不表示一定加载足量轮次；当前历史加载器最多支持 10 轮，并继续核验回答完整性及上下文容量。
     */
    public record HistorySelection(String conversationId, long conversationVersion,
                                   List<String> turnIds, int maxTurns) implements Serializable {
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
