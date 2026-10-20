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
 * @param invocationIds        candidates 中普通 Invocation 的兼容投影，不包含 Run ID；须与类型化候选一致。
 * @param selectedInvocationId 选中普通 Invocation 时的兼容投影，选中 Run 或未选择时为空。
 * @param version              本轮次记录的修改版本，从 0 开始，用于更新候选关联等操作的并发校验；与会话版本、sequence 相互独立。
 * @param createdAt            本轮次记录的创建时间，不能为空。
 * @param updatedAt            本轮次记录的最近更新时间，不能为空且不得早于 createdAt。
 * @param candidates           本轮完整的有序类型化回答候选，不重复；执行状态由各类权威存储提供。
 * @param selectedCandidate    当前显式选择，非空时须属于 candidates；失败候选不自动覆盖已选中的完整回答。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record Turn(String turnId, String conversationId, long sequence, String parentTurnId,
                   String supersedesTurnId, ChatMessage userMessage, List<String> invocationIds,
                   String selectedInvocationId, long version, Instant createdAt,
                   Instant updatedAt, List<AnswerCandidate> candidates,
                   AnswerCandidate selectedCandidate) implements Serializable {
    /**
     * 原 Java 调用及 v1 快照的兼容入口，全部旧 ID 明确转为 INVOCATION。
     */
    public Turn(String turnId, String conversationId, long sequence, String parentTurnId, String supersedesTurnId,
                ChatMessage userMessage, List<String> invocationIds, String selectedInvocationId, long version, Instant createdAt, Instant updatedAt) {
        this(turnId, conversationId, sequence, parentTurnId, supersedesTurnId, userMessage, invocationIds, selectedInvocationId,
                version, createdAt, updatedAt, null, null);
    }

    public static Turn typed(String id, String conversation, long sequence, String parent, String supersedes, ChatMessage user,
                             List<AnswerCandidate> candidates, AnswerCandidate selected, long version, Instant created, Instant updated) {
        return new Turn(id, conversation, sequence, parent, supersedes, user,
                candidates.stream().filter(c -> c.kind() == AnswerCandidate.Kind.INVOCATION).map(AnswerCandidate::executionId).toList(),
                selected != null && selected.kind() == AnswerCandidate.Kind.INVOCATION ? selected.executionId() : null,
                version, created, updated, candidates, selected);
    }

    /**
     * 历史采用显式选择；无选择时取最新候选，再由读取方核验完整成功及归属。
     */
    public AnswerCandidate effectiveCandidate() {
        return selectedCandidate != null ? selectedCandidate : candidates.isEmpty() ? null : candidates.getLast();
    }
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
        if (candidates == null) {
            candidates = invocationIds.stream().map(AnswerCandidate::invocation).toList();
            selectedCandidate = selectedInvocationId == null ? null : AnswerCandidate.invocation(selectedInvocationId);
        }
        candidates = ContractChecks.list(candidates, "answer candidates", 0, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(candidates, "answer candidates");
        ContractChecks.require(candidates.stream().filter(c -> c.kind() == AnswerCandidate.Kind.INVOCATION).map(AnswerCandidate::executionId).toList().equals(invocationIds),
                "Invocation projection differs from typed candidates");
        ContractChecks.require(selectedCandidate == null || candidates.contains(selectedCandidate), "Selected candidate is outside this turn");
        ContractChecks.require(Objects.equals(selectedInvocationId, selectedCandidate != null && selectedCandidate.kind() == AnswerCandidate.Kind.INVOCATION
                ? selectedCandidate.executionId() : null), "Selected invocation projection differs from typed selection");
    }
}
