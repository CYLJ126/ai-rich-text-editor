package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.generation.GenerationRequest;

import java.util.Objects;

/**
 * 可信入口组装的受理参数；摘要和初始状态由 Coordinator 生成，不允许入口自报受理成功。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public record InvocationSubmission<I extends CapabilityInput>(InvocationRequest<I> request,
                                                              ContextSnapshot snapshot,
                                                              Invocation.ConversationLink conversation,
                                                              Turn newTurn, String replacesInvocationId) {
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
