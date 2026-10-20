package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.generation.GenerationOptions;

import java.util.List;
import java.util.Objects;

/**
 * 用户工具聊天意图；实际历史、工具版本、Schema、账户价格与执行身份均由服务端固定。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
public record ToolChatSubmission(String conversationId, long expectedVersion, String text, DefinitionRef binding,
                                 DefinitionRef capability, String modelBudgetRef, long maxInputTokens,
                                 GenerationOptions generation, List<String> toolBindingIds,
                                 ToolChatRunSnapshot.Limits limits, List<Money> budgetLimits) {

    public ToolChatSubmission {
        ContractChecks.id(conversationId, "conversationId");
        ContractChecks.range(expectedVersion, "conversation version", 0, Long.MAX_VALUE - 1);
        ContractChecks.text(text, "text", ContractChecks.MAX_TEXT_CHARS);
        Objects.requireNonNull(binding).requireType("binding");
        Objects.requireNonNull(capability).requireType("capability");
        ContractChecks.id(modelBudgetRef, "modelBudgetRef");
        ContractChecks.range(maxInputTokens, "maxInputTokens", 1, 10_000_000);
        Objects.requireNonNull(generation);
        Objects.requireNonNull(limits);
        toolBindingIds = ContractChecks.list(toolBindingIds, "selected tools", 1, ContractChecks.MAX_TOOLS);
        toolBindingIds.forEach(id -> ContractChecks.id(id, "tool binding ID"));
        ContractChecks.unique(toolBindingIds, "selected tool binding IDs");
        budgetLimits = ContractChecks.list(budgetLimits, "Run currency limits", 1, 8);
        ContractChecks.unique(budgetLimits.stream().map(Money::currency).toList(), "Run currencies");
    }
}
