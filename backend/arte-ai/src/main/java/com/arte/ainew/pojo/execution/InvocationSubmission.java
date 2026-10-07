package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.generation.GenerationRequest;

import java.util.Objects;

/**
 * 可信入口组装的受理参数；摘要和初始状态由 Coordinator 生成，不允许入口自报受理成功。
 *
 * @param <I>                  本次调用的能力输入类型，须实现 CapabilityInput，并与 request 中的能力种类一致。
 * @param request              必填的类型化调用请求，包含能力／绑定的固定版本引用、实际输入、执行约束及可信执行上下文。
 *                             受理服务重新校验当前授权、发布配置、预算资格及幂等摘要；构造请求本身不代表已受理或已调用模型。
 * @param snapshot             本次生成实际使用的输入上下文快照；GenerationRequest 必填，其他能力输入必须为 null。
 *                             快照的模型绑定和消息须分别与 request.binding()、生成请求的消息一致。
 *                             受理服务进一步校验快照容量、摘要和有效期，并保存快照字节；传入快照不代表已经持久化。
 * @param conversation         可选的会话关联，包含会话 ID、期望会话版本和轮次 ID；独立调用不关联会话时传 null。
 *                             会话归属、版本及活跃调用由服务和存储校验，传入引用本身不授予会话访问权限。
 *                             newTurn 或 replacesInvocationId 非 null 时必须提供此关联；当前受理实现关联会话时要求提供 newTurn。
 * @param newTurn              可选的待创建轮次；普通聊天提交通过它保存用户输入及本次调用关联，独立调用可传 null。
 *                             非 null 时，其会话 ID 和轮次 ID 必须与 conversation 一致，且 replacesInvocationId 必须为 null。
 *                             当前受理实现还校验输入、调用关联及初始版本等事实，轮次与调用在受理事务中一并提交。
 * @param replacesInvocationId 可选的重新生成来源调用 ID，普通提交传 null；非 null 值须符合 ID 约束，不能等于本次 executionId。
 *                             重新生成关联原会话轮次，不通过 newTurn 创建新轮次；原调用归属及可重新生成条件仍须由服务校验。
 *                             当前受理实现尚不支持重新生成，非 null 会被明确拒绝，不会覆盖原调用或自动重试模型请求。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public record InvocationSubmission<I extends CapabilityInput>(InvocationRequest<I> request,
                                                              ContextSnapshot snapshot,
                                                              Invocation.ConversationLink conversation,
                                                              Turn newTurn,
                                                              String replacesInvocationId) {
    public InvocationSubmission {
        Objects.requireNonNull(request, "request");
        ContractChecks.optionalId(replacesInvocationId, "replacesInvocationId");
        ContractChecks.require(!request.context().executionId().equals(replacesInvocationId), "Cannot replace self");
        if (request.input() instanceof GenerationRequest generation) {
            Objects.requireNonNull(snapshot, "snapshot");
            ContractChecks.require(snapshot.modelBinding().equals(request.binding())
                    && snapshot.messages().equals(generation.messages()), "Generation input does not match snapshot");
        } else {
            ContractChecks.require(snapshot == null, "Only generation accepts a context snapshot");
        }
        if (newTurn != null) {
            Objects.requireNonNull(conversation, "conversation");
            ContractChecks.require(newTurn.turnId().equals(conversation.turnId())
                    && newTurn.conversationId().equals(conversation.conversationId()), "Turn link mismatch");
            ContractChecks.require(replacesInvocationId == null, "Regeneration must reuse existing turn");
        }
        ContractChecks.require(replacesInvocationId == null || conversation != null,
                "Regeneration requires existing conversation turn");
    }
}
