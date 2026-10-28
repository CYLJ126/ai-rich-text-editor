package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.serialization.ToolAdmissionDigests;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 受信免收费工具的耐久执行快照，按 Invocation ID 一对一保存；不包含运行对象或凭据值。
 * 独立工具无审批保持 v1、只读人工模式 v2、受控领域写入 v3；本地 Run 子工具使用 v4，受控 HTTP 独立及 Run 子工具使用 v5，并在入库及派发时校验原步骤和收紧政策。
 * 人工模式必须经等待审批事务受理，父身份本身不授予审批或业务资源资格。
 * 固定执行内容不冻结当前授权、工具启停或服务器约束，派发仍须重新核验。
 *
 * @param input      原始业务意图，用于升级／改配置后的幂等比较
 * @param request    服务端派生的实际调用及固定逻辑总期限
 * @param binding    本次实际选中的不可变绑定方案，包含配置、策略及目标
 * @param policy     产品／绑定与当前受理上限求交后的完整策略，保留输入上限及最低退避
 * @param schemas    固定输入／输出／配置 Schema 原文与摘要，按引用去重，最多三份
 * @param receivedAt 本次新请求进入受理组件的时刻，预检和授权等待均计入总期限
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
public record ToolExecutionSnapshot(ToolAdmissionInput input, InvocationRequest<ToolInvocation> request,
                                    ToolBindingResolution binding, ToolExecutionPolicy policy,
                                    List<ToolSchemaDefinition> schemas, Instant receivedAt) {

    public ToolExecutionSnapshot {
        Objects.requireNonNull(input);
        Objects.requireNonNull(request);
        Objects.requireNonNull(binding);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(receivedAt);
        schemas = ContractChecks.list(schemas, "tool schemas", 1, 3);
        ContractChecks.unique(schemas.stream().map(ToolSchemaDefinition::definition).toList(), "tool schema references");
        var tool = binding.tool().tool();
        var original = binding.effectivePolicy();
        ContractChecks.require(policy.timeout().compareTo(original.timeout()) <= 0 && policy.maxAttempts() <= original.maxAttempts()
                        && policy.retryBackoff().compareTo(original.retryBackoff()) >= 0 && policy.maxInputBytes() <= original.maxInputBytes()
                        && policy.maxOutputBytes() <= original.maxOutputBytes() && policy.approvalMode().satisfies(original.approvalMode()),
                "Tool admission policy must only tighten fixed binding");
        ContractChecks.require(request.kind() == CapabilityDescriptor.Kind.TOOL && input.bindingId().equals(binding.definition().id())
                && request.binding().equals(binding.definition()) && request.capability().equals(tool.capability())
                && request.input().tool().equals(tool.definition()) && request.input().arguments().equals(input.arguments())
                && binding.owner().equals(ExecutionOwners.from(request.context())), "Tool snapshot identity mismatch");
        ContractChecks.require(ToolExecutionRules.supportedTarget(binding, policy)
                        && ToolExecutionRules.supported(tool.risk(), policy)
                        && tool.capabilities().features().isEmpty() && !tool.capabilities().outcomeQuerySupported()
                ,
                "Snapshot requires a reviewed supported tool contract");
        ContractChecks.require(Objects.equals(request.context().budgetRef(), input.budgetRef())
                        && (tool.price() == null ? input.budgetRef() == null : input.budgetRef() != null)
                        && request.options().maxToolSteps() == 0 && request.options().maxConcurrentTools() == 0,
                "Tool billing context must match the fixed contract");
        ContractChecks.require(request.context().authorization().scopes().containsAll(tool.risk().requiredScopes()), "Tool scopes missing");
        ContractChecks.require(request.context().parentExecutionId() != null || request.input().callId().equals(ToolAdmissionDigests.callId(binding.owner(), input.bindingId(), request.context().idempotencyKey())),
                "Invalid standalone tool call identity");
        var options = request.options();
        ContractChecks.require(options.requestedTimeout() != null && !options.requestedTimeout().isNegative() && !options.requestedTimeout().isZero()
                && options.maxAttempts() <= policy.maxAttempts() && options.maxOutputBytes() <= policy.maxOutputBytes()
                && options.deadline().isAfter(receivedAt) && !options.deadline().isAfter(receivedAt.plus(policy.timeout()))
                && !options.deadline().isAfter(receivedAt.plus(options.requestedTimeout())), "Tool snapshot limits exceed policy");
        if (input.requestedTimeout() != null)
            ContractChecks.require(!options.deadline().isAfter(receivedAt.plus(input.requestedTimeout())), "Tool request timeout exceeded");
        if (input.maxAttempts() != null)
            ContractChecks.require(options.maxAttempts() <= input.maxAttempts(), "Tool attempt limit exceeded");
        if (input.maxOutputBytes() != null)
            ContractChecks.require(options.maxOutputBytes() <= input.maxOutputBytes(), "Tool output limit exceeded");
        var expected = new HashSet<>(List.of(tool.inputSchema(), tool.outputSchema(), tool.configurationSchema()));
        ContractChecks.require(schemas.stream().map(ToolSchemaDefinition::definition).collect(Collectors.toSet()).equals(expected),
                "Tool snapshot schemas do not match definition");
    }

    public String submissionDigest() {
        return ToolAdmissionDigests.submission(binding.owner(), input);
    }

    public String requestDigest() {
        return ToolAdmissionDigests.request(this);
    }
}
