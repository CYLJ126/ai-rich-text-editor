package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;

import java.util.Objects;

/**
 * 内部单次能力调用；不序列化、不从 HTTP 绑定。运行授权可刷新，但不能更换主体、延长期限。
 * Coordinator 在预留预算及耐久标记发送事实后构建；结构校验不能证明租约、预算或数据库提交仍有效。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public record GatewayCall<I extends CapabilityInput>(InvocationRequest<I> request, ResolvedBinding binding,
                                                     Attempt attempt, ExecutionRuntimeContext runtime) {
    public GatewayCall {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(runtime, "runtime");
        ContractChecks.require(request.binding().equals(binding.definition())
                && request.capability().equals(binding.capability().definition())
                && request.kind() == binding.capability().kind(), "Resolved binding does not match request");
        ContractChecks.require(binding.capability().availability() == CapabilityDescriptor.Availability.EXECUTABLE,
                "Capability is not executable");
        ContractChecks.require(attempt.invocationId().equals(request.context().executionId())
                && runtime.execution().executionId().equals(attempt.invocationId()), "Execution identity mismatch");
        ContractChecks.require(ExecutionOwner.from(request.context()).equals(ExecutionOwner.from(runtime.execution())),
                "Runtime owner mismatch");
        ContractChecks.require(request.context().authorization().scopes().containsAll(runtime.execution().authorization().scopes()),
                "Runtime scopes exceed accepted authorization");
        ContractChecks.require(!runtime.execution().deadline().isAfter(request.options().deadline()),
                "Runtime deadline exceeds invocation deadline");
        ContractChecks.require(attempt.state() == Attempt.State.RUNNING && attempt.dispatch() != Attempt.Dispatch.NOT_STARTED
                && attempt.remoteRequestId() != null, "Call requires a running attempt with durable dispatch identity");
    }
}
