package com.arte.ainew.web.response;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationBudgetState;

import java.time.Instant;

/**
 * 权威执行状态的 HTTP 投影，不暴露输入、授权快照、预算选择或存储摘要。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:24 ✾
 */
public record InvocationStatusResponse(String invocationId, CapabilityDescriptor.Kind kind,
                                       Invocation.ConversationLink conversation, Invocation.State state,
                                       long version, String activeAttemptId, boolean resultAvailable,
                                       Boolean partial, ExecutionError error, Instant acceptedAt, Instant updatedAt,
                                       InvocationBudgetState budgetState) {

    public static InvocationStatusResponse from(Invocation invocation, InvocationBudgetState budgetState) {
        return new InvocationStatusResponse(invocation.request().context().executionId(), invocation.request().kind(),
                invocation.conversation(), invocation.state(), invocation.version(), invocation.activeAttemptId(),
                invocation.result() != null, invocation.result() == null ? null : invocation.result().partial(),
                invocation.error(), invocation.acceptedAt(), invocation.updatedAt(), budgetState);
    }
}
