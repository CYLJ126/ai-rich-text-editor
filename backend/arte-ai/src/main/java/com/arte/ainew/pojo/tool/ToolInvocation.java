package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.CapabilityInput;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.value.StructuredValue;

import java.util.Objects;

/**
 * 受控工具输入
 * <p>
 * 作为 InvocationRequest 的 TOOL 类型输入，固定本次工具引用及结构化参数。
 * callId 关联模型提议或服务端分配的直接调用与结果，不充当平台幂等键。实际副作用等级从受信工具定义解析。
 * 网关检查绑定、Schema、资源权限及副作用；通用准入／预算进入协调链路，不直接修改领域数据库。
 *
 * @param callId    本次工具调用的关联标识，不能为空白且不超过 256 个 UTF-16 单元，返回 ToolResult 时必须保持一致；不等同于平台 executionId 或操作幂等键。
 * @param tool      本次调用的固定版本工具引用，不能为空且类型必须为 tool，须与已解析绑定及本次允许的工具集合匹配；引用本身不授予执行权限。
 * @param arguments 本次调用的结构化对象参数，不能为空，可使用空对象表达无参数工具；执行前仍须按固定输入 Schema、大小上限及资源授权规则校验。
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
