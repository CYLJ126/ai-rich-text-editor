package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
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
 * Run 的不可变输入与权限上限；根不继承单一费用账户，子账户选择另行准入。
 *
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
        ContractChecks.require(initialContext.modelBinding().equals(model.definition()) && tools.owner().equals(ExecutionOwner.from(context)), "Run fixed input mismatch");
        ContractChecks.require(context.authorization().scopes().contains("ai:invoke") && tools.entries().stream()
                .allMatch(e -> context.authorization().scopes().containsAll(e.binding().tool().tool().risk().requiredScopes())), "Run scope ceiling is insufficient");
        if (budgetPlan != null) {
            var selected = budgetPlan.selection(model.definition());
            ContractChecks.require(selected.kind() == CapabilityDescriptor.Kind.GENERATION && selected.capability().equals(model.capability().definition())
                    && selected.rate().equals(model.rate()), "Run model budget selection mismatch");
            ContractChecks.require(budgetPlan.selections().size() == tools.entries().size() + 1, "Run budget bindings must match fixed set");
            for (var entry : tools.entries()) {
                var choice = budgetPlan.selection(entry.binding().definition());
                ContractChecks.require((entry.binding().tool().tool().price() == null ? choice.mode() == BillingMode.FREE
                        : choice.mode() == BillingMode.CHARGE && entry.binding().tool().tool().price().equals(choice.toolPrice()))
                        && choice.capability().equals(entry.binding().tool().tool().capability())
                        && ToolExecutionRules.supportedTarget(entry.binding(), entry.binding().effectivePolicy()), "Run tool requires explicit reviewed FREE selection");
            }
        }
        ContractChecks.require(context.deadline().isAfter(receivedAt) && !context.deadline().isAfter(receivedAt.plus(limits.timeout()))
                && !context.deadline().isAfter(initialContext.expiresAt()), "Run deadline exceeds fixed limits");
    }
}
