package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.GatewayCall;

import java.util.Objects;

/**
 * 网关交给受控 HTTP 适配的单次调用材料；不持久化、不从 HTTP 绑定，也不授予资源访问权。
 *
 * @param call    已有耐久派发身份的调用，运行上下文携带本次重新核验的授权及剩余期限
 * @param binding 固定版本、有效配置与策略；适配器不得重新选择 latest 或读取当前绑定来替换它
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
public record RemoteToolCall(GatewayCall<ToolInvocation> call,
                             ToolBindingResolution binding) implements ToolExecutionCall {

    public RemoteToolCall {
        Objects.requireNonNull(call);
        Objects.requireNonNull(binding);
        ContractChecks.require(binding.owner().equals(ExecutionOwner.from(call.runtime().execution()))
                        && binding.resolvedBinding().equals(call.binding())
                        && binding.tool().tool().definition().equals(call.request().input().tool())
                        && binding.tool().target() instanceof ExecutionTarget.Remote,
                "Remote tool call does not match fixed binding");
        var accepted = call.request().context();
        var current = call.runtime().execution();
        ContractChecks.require(accepted.traceId().equals(current.traceId())
                        && Objects.equals(accepted.parentExecutionId(), current.parentExecutionId())
                        && Objects.equals(accepted.budgetRef(), current.budgetRef())
                        && Objects.equals(accepted.releaseRef(), current.releaseRef())
                        && Objects.equals(accepted.idempotencyKey(), current.idempotencyKey()),
                "Runtime changed fixed tool execution identity");
    }
}
