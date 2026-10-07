package com.arte.ainew.pojo.conversation;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.generation.ChatMessage;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 聊天的轮次
 * <p>
 * 一次固定用户输入和回答候选关联，无独立执行终态。重新生成保留本 Turn 并新增 Invocation；
 * 编辑重发新建 Turn，用 supersedesTurnId 关联原轮次并保留分支，不修改已经执行的输入。
 * parentTurnId 决定历史路径，候选引用及当前选择的结果归属由会话服务校验。
 *
 * @param turnId               本轮次的唯一标识，由服务端创建，不能与父轮次或被替代轮次的 ID 相同。
 * @param conversationId       所属会话的 ID；轮次归属及访问权限通过所属会话校验。
 * @param sequence             本轮次在会话内的顺序号，从 1 开始，受理新轮次时递增，用于历史排序；不是轮次修改版本。
 * @param parentTurnId         父轮次 ID，用于确定对话分支的历史路径；null 表示未显式指定父轮次。
 * @param supersedesTurnId     编辑重发时被本轮次替代的原轮次 ID；null 表示没有替代关系，原轮次的输入仍保留。
 * @param userMessage          本轮固定的用户输入消息，不能为空且角色必须为 USER；编辑输入应创建新轮次。
 * @param invocationIds        本轮关联的回答候选 Invocation ID 列表，可为空，列表不可变且 ID 不重复；执行状态和回答内容由 Invocation 及其结果提供。
 * @param selectedInvocationId 当前显式选中的回答候选 ID，非 null 时必须属于 invocationIds；null 表示尚未显式选择，不代表没有可用回答。
 * @param version              本轮次记录的修改版本，从 0 开始，用于更新候选关联等操作的并发校验；与会话版本、sequence 相互独立。
 * @param createdAt            本轮次记录的创建时间，不能为空。
 * @param updatedAt            本轮次记录的最近更新时间，不能为空且不得早于 createdAt。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record Turn(String turnId, String conversationId, long sequence, String parentTurnId,
                   String supersedesTurnId, ChatMessage userMessage, List<String> invocationIds,
                   String selectedInvocationId, long version, Instant createdAt,
                   Instant updatedAt) implements Serializable {
    public Turn {
        ContractChecks.id(turnId, "turnId");
        ContractChecks.id(conversationId, "conversationId");
        ContractChecks.range(sequence, "sequence", 1, Long.MAX_VALUE);
        ContractChecks.optionalId(parentTurnId, "parentTurnId");
        ContractChecks.optionalId(supersedesTurnId, "supersedesTurnId");
        ContractChecks.require(!turnId.equals(parentTurnId) && !turnId.equals(supersedesTurnId), "Turn cannot reference itself");
        Objects.requireNonNull(userMessage, "userMessage");
        ContractChecks.require(userMessage.role() == ChatMessage.Role.USER, "Turn input must be a user message");
        invocationIds = ContractChecks.list(invocationIds, "invocationIds", 0, ContractChecks.MAX_ITEMS);
        invocationIds.forEach(id -> ContractChecks.id(id, "invocationId"));
        ContractChecks.unique(invocationIds, "invocationIds");
        ContractChecks.optionalId(selectedInvocationId, "selectedInvocationId");
        ContractChecks.require(selectedInvocationId == null || invocationIds.contains(selectedInvocationId),
                "Selected response must belong to this turn");
        ContractChecks.range(version, "version", 0, Long.MAX_VALUE);
        ContractChecks.ordered(createdAt, updatedAt, "updatedAt");
    }
}
