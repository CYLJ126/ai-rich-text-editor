package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.remote.RemoteApplicationRequest;
import java.io.Serializable;
import java.util.Objects;

/**
 * 服务端类型化调用信封，无凭据、远端地址或运行资源。
 * <p>
 * 受理前验证引用、Schema、当前授权与请求摘要。
 * 相同主体／操作下相同幂等键和摘要返回原调用；上下文新分配的 ID 不覆盖原受理身份。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:15 ✾
 */
public record InvocationRequest<I extends CapabilityInput>(DefinitionRef capability, DefinitionRef binding,
        CapabilityDescriptor.Kind kind, I input, ExecutionOptions options,
        ExecutionContext context) implements Serializable {
    public InvocationRequest {
        Objects.requireNonNull(capability, "capability").requireType("capability");
        Objects.requireNonNull(binding, "binding").requireType("binding");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(context, "context");
        ContractChecks.id(context.executionId(), "executionId");
        ContractChecks.id(context.idempotencyKey(), "idempotencyKey");
        ContractChecks.require(kind == input.kind(), "Capability kind and input type do not match");
        ContractChecks.require(!options.deadline().isAfter(context.deadline()), "Options extend execution deadline");
        if (input instanceof GenerationRequest generation) {
            ContractChecks.require(generation.tools().isEmpty() || options.maxToolSteps() > 0,
                    "Tools are described but tool execution is disabled");
        }
        if (input instanceof RemoteApplicationRequest remote && remote.session() != null) {
            ContractChecks.require(remote.session().owner().equals(ExecutionOwner.from(context)),
                    "Remote session does not belong to current execution owner");
        }
    }
}
