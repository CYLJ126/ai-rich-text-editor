package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.CapabilityInput;

import java.util.Objects;

/**
 * 受控工具输入，callId 关联模型提议，不充当平台幂等键。实际副作用等级从受信工具定义解析。
 * 网关检查绑定、Schema、资源权限及副作用；通用准入／预算进入协调链路，不直接修改领域数据库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:20 ✾
 */
public record ToolInvocation(String callId, DefinitionRef tool,
                             StructuredValue.ObjectValue arguments) implements CapabilityInput {
    public ToolInvocation {
        ContractChecks.id(callId, "callId");
        Objects.requireNonNull(tool, "tool").requireType("tool");
        Objects.requireNonNull(arguments, "arguments");
    }

    @Override
    public CapabilityDescriptor.Kind kind() {
        return CapabilityDescriptor.Kind.TOOL;
    }
}
