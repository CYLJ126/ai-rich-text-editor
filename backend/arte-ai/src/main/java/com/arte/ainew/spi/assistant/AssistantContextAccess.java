package com.arte.ainew.spi.assistant;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.context.AssistantSource;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;

/**
 * Trusted assistant configuration and current execution permissions.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 00:45 ✾
 */
public interface AssistantContextAccess {

    record Prompt(AssistantSource source, String instruction) {
    }

    Mono<Prompt> resolve(ExecutionContext context, DefinitionRef profile);

    void requireNewConversation(ExecutionContext context, DefinitionRef profile);

    void requireCurrent(ExecutionContext context, ContextSnapshot snapshot);

    Mono<Void> requireEligible(ExecutionContext context, ContextSnapshot snapshot);
}
