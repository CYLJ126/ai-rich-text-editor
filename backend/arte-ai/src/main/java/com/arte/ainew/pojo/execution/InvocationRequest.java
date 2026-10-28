package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.remote.RemoteApplicationRequest;
import com.arte.core.pojo.reference.DefinitionRef;

import java.io.Serializable;
import java.util.Objects;

/**
 * 服务端类型化调用信封
 * <p>
 * 服务端构造的一次逻辑能力调用请求，统一封装能力与绑定引用、类型化输入、执行限制及可信执行上下文，供受理校验、持久化和后续派发使用。
 * <p>
 * 无凭据、远端地址或运行资源。
 * 受理前验证引用、Schema、当前授权与请求摘要。
 * 相同主体／操作下相同幂等键和摘要返回原调用；上下文新分配的 ID 不覆盖原受理身份。
 *
 * @param capability 本次调用的固定版本能力契约引用，不能为空且类型必须为 capability；受理时须核对其与绑定及输入 Schema 的一致性。对应{@link CapabilityDescriptor#definition}
 * @param binding    本次选择的固定版本执行绑定引用，不能为空且类型必须为 binding；由服务端解析具体执行目标，引用本身不授予使用资格。对应{@link ResolvedBinding#definition}
 * @param kind       本次调用的能力类型，不能为空且必须与 input.kind() 一致，用于选择相应的校验和执行链路。
 * @param input      与能力类型相匹配的类型化输入，不能为空，例如生成请求或工具参数；仍须通过受信 Schema、来源归属及业务条件校验。
 * @param options    本次执行的期限、尝试次数、输出字节及工具步骤和并发上限，不能为空；截止时间不得晚于 context.deadline，描述工具时必须允许工具步骤。
 * @param context    由服务端可信入口构造的执行上下文，不能为空，包含执行身份、追踪、授权快照、期限及预算等引用；本请求要求提供幂等键，执行边界仍须重新授权。
 * @param <I>        实现 CapabilityInput 的具体输入类型，须由受信 Schema 注册表登记；实现接口本身不代表已支持执行。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:15 ✾
 */
public record InvocationRequest<I extends CapabilityInput>(
        DefinitionRef capability, DefinitionRef binding, CapabilityDescriptor.Kind kind,
        I input, ExecutionOptions options, ExecutionContext context) implements Serializable {

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
