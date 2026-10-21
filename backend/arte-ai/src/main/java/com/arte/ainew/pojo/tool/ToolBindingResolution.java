package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工具绑定解析
 * <p>
 * 内容寻址的不可变解析工件
 * 冻结实际版本、配置和约束，不冻结授权或当前启停。
 *
 * @param definition             type=binding，version=p1-内容摘要，供后续 InvocationRequest.binding 固定引用。
 * @param revision               来源的 rN 管理修订，不随新发布或配置更新改写。
 * @param owner                  固定个人归属。
 * @param tool                   实际选中的产品、提供方及执行目标。
 * @param contractDigest         首次发布的固定契约摘要。
 * @param effectiveConfiguration 工具默认值与该绑定层合并后的非敏感配置。
 * @param configurationDigest    标准 JSON 配置摘要，存储边界重算。
 * @param effectivePolicy        工具／绑定／服务器求交集后的约束，派发仍须检查当前更严策略。
 * @param networkOrigins         固定的外发交集，不授予实际网络或资源访问资格。
 * @param secrets                固定引用而非凭据值；当前本地解析只允许空映射。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public record ToolBindingResolution(DefinitionRef definition, DefinitionRef revision, ExecutionOwner owner,
                                    ToolDefinitionRevision tool, String contractDigest,
                                    StructuredValue.ObjectValue effectiveConfiguration, String configurationDigest,
                                    ToolExecutionPolicy effectivePolicy, Set<URI> networkOrigins,
                                    Map<String, DefinitionRef> secrets) {

    public ToolBindingResolution {
        Objects.requireNonNull(definition).requireType("binding");
        Objects.requireNonNull(revision).requireType("binding");
        ContractChecks.require(definition.id().equals(revision.id()) && definition.version().matches("p1-[0-9a-f]{64}")
                && revision.version().matches("r[1-9][0-9]{0,18}"), "Invalid fixed binding references");
        Objects.requireNonNull(owner);
        Objects.requireNonNull(tool);
        ContractChecks.digest(contractDigest, "contractDigest");
        Objects.requireNonNull(effectiveConfiguration);
        ContractChecks.digest(configurationDigest, "configurationDigest");
        Objects.requireNonNull(effectivePolicy);
        networkOrigins = ContractChecks.set(networkOrigins, "networkOrigins", ContractChecks.MAX_ITEMS);
        networkOrigins.forEach(ToolRiskProfile::validateOrigin);
        secrets = Map.copyOf(secrets);
        secrets.forEach((slot, ref) -> {
            ContractChecks.id(slot, "secret slot");
            ref.requireType("secret");
        });
    }

    /**
     * 投影通用固定目标；调用方须先复核当前资格，不能以工件存在代替准入或免费资格。
     */
    public ResolvedBinding resolvedBinding() {
        var capability = tool.tool().discoveredCapability();
        return new ResolvedBinding(definition, new CapabilityDescriptor(capability.definition(), capability.kind(), capability.inputSchema(),
                capability.outputSchema(), capability.features(), capability.sideEffect(), CapabilityDescriptor.Availability.EXECUTABLE), tool.target(), null, tool.tool().price() == null ? null : tool.tool().price().rate());
    }
}
