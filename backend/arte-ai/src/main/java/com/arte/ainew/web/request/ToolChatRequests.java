package com.arte.ainew.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 只接受读取意图；身份、运行输入和子调用引用均从服务端持久化记录取得。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 18:20 ✾
 */
public final class ToolChatRequests {

    public record Limit(@NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                        @NotBlank @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,18})?") String amount) implements ToolManagementRequests.Strict {
    }

    /**
     * 只接受用户文本及选择意图，不接受中间消息、运行身份或客户端自报 Schema。
     */
    public record Submit(@NotNull @Valid ConversationRequests.Scope scope,
                         @NotBlank @Size(max = 256) String conversationId, @Min(0) long expectedVersion,
                         @NotBlank @Size(max = 1_000_000) String text,
                         @NotNull com.arte.ainew.common.reference.DefinitionRef binding,
                         @NotNull com.arte.ainew.common.reference.DefinitionRef capability,
                         @NotBlank @Size(max = 256) String modelBudgetRef,
                         @Min(1) @Max(10_000_000) long maxInputTokens,
                         @NotNull com.arte.ainew.pojo.generation.GenerationOptions generationOptions,
                         @NotEmpty @Size(max = 128) java.util.List<@NotBlank @Size(max = 256) String> toolBindingIds,
                         @Min(1) @Max(3600) long timeoutSeconds,
                         @Min(1) @Max(32) int maxModelRounds,
                         @Min(1) @Max(100) int maxToolCalls,
                         @Min(1) @Max(16) int maxConcurrentTools,
                         @NotEmpty @Size(max = 8) java.util.List<@Valid Limit> budgetLimits,
                         @Size(max = 32) java.util.Map<String, String> toolBudgetRefs) implements ToolManagementRequests.Strict {
        public Submit(ConversationRequests.Scope scope, String conversationId, long expectedVersion, String text,
                      com.arte.ainew.common.reference.DefinitionRef binding, com.arte.ainew.common.reference.DefinitionRef capability,
                      String modelBudgetRef, long maxInputTokens, com.arte.ainew.pojo.generation.GenerationOptions generationOptions,
                      java.util.List<String> toolBindingIds, long timeoutSeconds, int maxModelRounds, int maxToolCalls, int maxConcurrentTools, java.util.List<Limit> budgetLimits) {
            this(scope, conversationId, expectedVersion, text, binding, capability, modelBudgetRef, maxInputTokens, generationOptions, toolBindingIds,
                    timeoutSeconds, maxModelRounds, maxToolCalls, maxConcurrentTools, budgetLimits, java.util.Map.of());
        }
        public com.arte.ainew.pojo.tool.ToolChatSubmission input() {
            return new com.arte.ainew.pojo.tool.ToolChatSubmission(conversationId, expectedVersion, text, binding, capability, modelBudgetRef,
                    maxInputTokens, generationOptions, toolBindingIds,
                    new com.arte.ainew.pojo.tool.ToolChatRunSnapshot.Limits(java.time.Duration.ofSeconds(timeoutSeconds), maxModelRounds, maxToolCalls, maxConcurrentTools),
                    budgetLimits.stream().map(l -> new com.arte.ainew.pojo.budget.Money(new java.math.BigDecimal(l.amount()), java.util.Currency.getInstance(l.currency()))).toList(), toolBudgetRefs);
        }
    }

    private ToolChatRequests() {
    }

    public record Query(@NotNull @Valid ConversationRequests.Scope scope,
                        @NotBlank @Size(max = 256) String runId) implements ToolManagementRequests.Strict {
    }

    public record Events(@NotNull @Valid ConversationRequests.Scope scope,
                         @NotBlank @Size(max = 256) String runId,
                         @Min(0) long afterSequence,
                         @Min(1) @Max(100) int limit) implements ToolManagementRequests.Strict {
    }
}
