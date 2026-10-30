package com.arte.ainew.pojo.control;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.List;
import java.util.Optional;

/**
 * 同一次可信读取的固定发布定义
 * <p>
 * 不携带 Grant、预算账户或凭据明文，不授予使用资格。
 *
 * @param releaseRef   本次定义视图所属的发布标识，用于核对执行上下文的发布依赖，不表示最新版本
 * @param capabilities 固定版本能力定义，描述能力类型、输入输出 Schema、支持特性及可用状态
 * @param bindings     固定版本绑定定义，关联能力、执行目标、模型上下文容量及可选费率；依赖须在本视图中可解析
 * @param connections  固定版本连接定义，包含供应商、协议、目标地址、凭据引用及传输约束，不包含凭据明文
 * @param rates        固定版本费率定义，描述模型 Token 单价或工具按次价格，不包含预算额度及账户费用状态
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 12:18 ✾
 */
public record PublishedAiDefinitions(String releaseRef, List<CapabilityDescriptor> capabilities,
                                     List<ResolvedBinding> bindings, List<ConnectionDefinition> connections,
                                     List<RateDefinition> rates) {

    public PublishedAiDefinitions {
        ContractChecks.id(releaseRef, "releaseRef");
        capabilities = List.copyOf(capabilities);
        bindings = List.copyOf(bindings);
        connections = List.copyOf(connections);
        rates = List.copyOf(rates);
        ContractChecks.unique(capabilities.stream().map(CapabilityDescriptor::definition).toList(), "capabilities");
        ContractChecks.unique(bindings.stream().map(ResolvedBinding::definition).toList(), "bindings");
        ContractChecks.unique(connections.stream().map(ConnectionDefinition::definition).toList(), "connections");
        ContractChecks.unique(rates.stream().map(RateDefinition::definition).toList(), "rates");
        for (var binding : bindings) {
            ContractChecks.require(capabilities.contains(binding.capability())
                            && (!(binding.target() instanceof ExecutionTarget.Remote remote)
                            || connections.stream().anyMatch(c -> c.definition().equals(remote.connection())))
                            && (binding.rate() == null || rates.stream().anyMatch(r -> r.definition().equals(binding.rate()))),
                    "Unresolved published binding dependency");
        }
    }

    public Optional<RateDefinition> rate(DefinitionRef reference) {
        reference.requireType("rate");
        return rates.stream().filter(value -> value.definition().equals(reference)).findFirst();
    }
}
