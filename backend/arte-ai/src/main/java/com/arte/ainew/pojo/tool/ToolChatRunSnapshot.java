package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.spi.persistence.DispatchPreparationStore.BillingMode;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 工具聊天 Run 的不可变执行计划，固定受理时的输入、模型、工具集合、权限上限和预算选择。
 * <p>
 * 持久化后用于幂等重放、Worker 恢复及模型／工具子调用校验；执行状态与检查点另由 {@link ToolChatRun} 保存。
 * 根不继承单一费用账户，各子调用按固定预算计划选择账户，实际受理及派发仍须复核当前资格与政策。
 * <p>
 * 这里的“根”指一次工具聊天的顶层 Run，也就是根执行。例如一次聊天的执行过程是：
 * Run → 调用模型 → 执行工具 → 再次调用模型生成答复
 * 其中 Run 是根，具体的模型调用和工具调用是它的子执行。
 *
 * @param context        Run 根执行上下文，固定 Run ID、追踪标识、原主体／空间、权限上限、截止时间及幂等键；不带父执行或单一预算引用
 * @param initialContext 首轮模型输入的可信快照，包含已选历史与本轮消息、模型绑定、容量估算及有效期；后续轮次在此基础上加入模型／工具结果
 * @param model          固定版本的工具调用模型绑定，包含能力、连接、远端模型操作、上下文容量及费率引用
 * @param generation     各轮模型调用沿用的生成参数，例如最大输出 Token、温度及停止词
 * @param tools          本次 Run 可使用的固定工具集合，包含模型可见描述、绑定版本、输入 Schema 及有效执行政策
 * @param limits         本次 Run 的执行上限，包括总超时、模型调用轮数、工具调用总数及工具并发数
 * @param receivedAt     服务端开始处理本次 Run 提交的时间，用于约束根执行截止时间及记录受理输入
 * @param budgetPlan     按币种固定的费用上限，以及模型／工具绑定对应的计费方式、账户和价格；预算准备阶段或兼容旧快照时可为空，不表示已取得预算预留
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 16:20 ✾
 */
public record ToolChatRunSnapshot(ExecutionContext context, ContextSnapshot initialContext, ResolvedBinding model,
                                  GenerationOptions generation, ToolSetSnapshot tools, Limits limits,
                                  Instant receivedAt, ToolChatBudgetPlan budgetPlan) {

    /**
     * S4-02／03 旧快照仅可读取及恢复材料，没有收费子派发资格。
     */
    public ToolChatRunSnapshot(ExecutionContext context, ContextSnapshot initialContext, ResolvedBinding model,
                               GenerationOptions generation, ToolSetSnapshot tools, Limits limits, Instant receivedAt) {
        this(context, initialContext, model, generation, tools, limits, receivedAt, null);
    }

    /**
     * Run 固定的执行次数及时间上限。
     *
     * @param timeout            Run 总超时，根截止时间不得晚于 receivedAt 加此时长
     * @param maxModelRounds     最多允许的模型调用轮数
     * @param maxToolCalls       整个 Run 最多允许的工具调用总数
     * @param maxConcurrentTools 同时执行的工具数量上限，写工具仍受编排层的串行约束
     */
    public record Limits(Duration timeout, int maxModelRounds, int maxToolCalls, int maxConcurrentTools) {
        public Limits {
            Objects.requireNonNull(timeout);
            ContractChecks.require(timeout.compareTo(Duration.ofSeconds(1)) >= 0 && timeout.compareTo(Duration.ofHours(1)) <= 0, "Invalid Run timeout");
            ContractChecks.range(maxModelRounds, "maxModelRounds", 1, 32);
            ContractChecks.range(maxToolCalls, "maxToolCalls", 1, 100);
            ContractChecks.range(maxConcurrentTools, "maxConcurrentTools", 1, Math.min(16, maxToolCalls));
        }
    }

    public ToolChatRunSnapshot {
        Objects.requireNonNull(context);
        Objects.requireNonNull(initialContext);
        Objects.requireNonNull(model);
        Objects.requireNonNull(generation);
        Objects.requireNonNull(tools);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(receivedAt);
        ContractChecks.id(context.idempotencyKey(), "Run idempotencyKey");
        ContractChecks.require(context.parentExecutionId() == null && context.budgetRef() == null, "Run requires an account-neutral root context");
        ContractChecks.require(model.capability().kind() == CapabilityDescriptor.Kind.GENERATION && model.target() instanceof ExecutionTarget.Remote
                && model.capability().availability() == CapabilityDescriptor.Availability.EXECUTABLE
                && model.capability().features().containsAll(java.util.Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING,
                CapabilityDescriptor.Feature.TOOL_CALLS)), "Run requires a tool-capable model");
        ContractChecks.require(initialContext.modelBinding().equals(model.definition()) && tools.owner().equals(ExecutionOwners.from(context)), "Run fixed input mismatch");
        ContractChecks.require(context.authorization().scopes().contains("ai:invoke") && tools.entries().stream()
                .allMatch(e -> context.authorization().scopes().containsAll(e.binding().tool().tool().risk().requiredScopes())), "Run scope ceiling is insufficient");
        if (budgetPlan != null) {
            var selected = budgetPlan.selection(model.definition());
            ContractChecks.require(selected.kind() == CapabilityDescriptor.Kind.GENERATION && selected.capability().equals(model.capability().definition())
                    && selected.rate().equals(model.rate()), "Run model budget selection mismatch");
            ContractChecks.require(budgetPlan.selections().size() == tools.entries().size() + 1, "Run budget bindings must match fixed set");
            for (var entry : tools.entries()) {
                var choice = budgetPlan.selection(entry.binding().definition());
                ToolPrice price = entry.binding().tool().tool().price();
                ContractChecks.require((price == null ? choice.mode() == BillingMode.FREE
                        : choice.mode() == BillingMode.CHARGE && price.equals(choice.toolPrice()))
                        && choice.capability().equals(entry.binding().tool().tool().capability())
                        && ToolExecutionRules.supportedTarget(entry.binding(), entry.binding().effectivePolicy()), "Run tool requires explicit reviewed FREE selection");
            }
        }
        ContractChecks.require(context.deadline().isAfter(receivedAt) && !context.deadline().isAfter(receivedAt.plus(limits.timeout()))
                && !context.deadline().isAfter(initialContext.expiresAt()), "Run deadline exceeds fixed limits");
    }
}
