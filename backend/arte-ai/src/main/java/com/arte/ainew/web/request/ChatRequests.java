package com.arte.ainew.web.request;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.generation.GenerationOptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 单条用户文本的 HTTP 请求；不接受执行上下文、角色、历史消息或凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 16:28 ✾
 */
public final class ChatRequests {
    private ChatRequests() {
    }

    public record Submit(@NotNull @Valid ConversationRequests.Scope scope,
                         @NotBlank @Size(max = 256) String conversationId,
                         @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long expectedVersion,
                         @NotBlank @Size(max = ContractChecks.MAX_TEXT_CHARS) String text,
                         @NotNull DefinitionRef capability,
                         @NotNull DefinitionRef binding,
                         @NotBlank @Size(max = 256) String budgetRef,
                         @NotNull @Min(1) @Max(10_000_000) Long maxInputTokens,
                         @NotNull GenerationOptions generationOptions,
                         @NotNull @Min(1) @Max(3600) Long timeoutSeconds) {
    }
}
