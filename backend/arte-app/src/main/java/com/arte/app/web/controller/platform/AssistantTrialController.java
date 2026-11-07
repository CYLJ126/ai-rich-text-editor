package com.arte.app.web.controller.platform;

import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.assistant.Assistants.ConversationAssistant;
import com.arte.ainew.pojo.assistant.Assistants.Page;
import com.arte.ainew.pojo.assistant.Assistants.TrialHistory;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ConversationRequests;
import com.arte.app.platform.assistant.AssistantTrialService;
import com.arte.app.platform.assistant.AssistantTrialService.AcceptedTrial;
import com.arte.app.platform.assistant.PlatformAssistantExecutionAccess;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.reference.DefinitionRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/ai-new/assistant")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-assistant.enabled", "ai-new-execution.enabled",
        "platform.identity.enabled", "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
public class AssistantTrialController {

    private final AssistantTrialService trials;
    private final NewAiHttpContext http;
    private final Duration queryTimeout;
    private final PlatformAssistantExecutionAccess assistantAccess;
    private final ConversationService conversations;

    public AssistantTrialController(AssistantTrialService trials, NewAiHttpContext http, NewAiProperties properties,
                                    PlatformAssistantExecutionAccess assistantAccess, ConversationService conversations) {
        this.assistantAccess = assistantAccess;
        this.conversations = conversations;
        this.trials = trials;
        this.http = http;
        var maximum = properties.limits().maximumTimeout();
        this.queryTimeout = maximum.compareTo(Duration.ofSeconds(10)) < 0 ? maximum : Duration.ofSeconds(10);
    }

    public record Request(@NotNull @Valid ConversationRequests.Scope scope,
                          @NotBlank @Size(max = 36) String assistantId,
                          @NotNull @Positive Long draftRevision,
                          @NotBlank @Size(max = ContractChecks.MAX_TEXT_CHARS) String text,
                          @NotNull DefinitionRef capability, @NotNull DefinitionRef binding,
                          @NotBlank @Size(max = 256) String budgetRef,
                          @NotNull @Min(1) @Max(10_000_000) Long maxInputTokens,
                          @NotNull GenerationOptions generationOptions,
                          @NotNull @Min(1) @Max(3600) Long timeoutSeconds) {
    }

    public record HistoryRequest(@NotNull @Valid ConversationRequests.Scope scope,
                                 @NotBlank @Size(max = 36) String assistantId, @Size(max = 36) String cursor,
                                 @NotNull @Min(1) @Max(100) Integer limit) {
    }

    @PostMapping("/listAssistantTrials")
    public Mono<ResultContext<Page<TrialHistory>>> listAssistantTrials(@Valid @RequestBody HistoryRequest request, Locale locale) {
        return http.read(request.scope(), Set.of(AdmissionAuthorization.READ), queryTimeout, false,
                        context -> assistantAccess.trialHistory(context, request.assistantId(), request.cursor(), request.limit()))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getConversationAssistant")
    public Mono<ResultContext<ConversationAssistant>> getConversationAssistant(@Valid @RequestBody ConversationRequests.Find request, Locale locale) {
        return http.read(request.scope(), Set.of(AdmissionAuthorization.READ), queryTimeout, false,
                        context -> conversations.find(request.conversationId(), context)
                                .flatMap(value -> assistantAccess.conversationAssistant(context, value.chatProfile())))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale))
                .switchIfEmpty(Mono.fromSupplier(() -> ResultContext.success(null, ResultCodeEnum.SUCCESS, locale)));
    }

    @PostMapping("/getInvocationAssistant")
    public Mono<ResultContext<AssistantTrialService.ExecutionAssistant>> getInvocationAssistant(
            @Valid @RequestBody com.arte.ainew.web.request.InvocationRequests.Query request, Locale locale) {
        return http.read(request.scope(), Set.of(AdmissionAuthorization.READ), queryTimeout, false,
                        context -> trials.executionAssistant(request.invocationId(), context))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale))
                .switchIfEmpty(Mono.fromSupplier(() -> ResultContext.success(null, ResultCodeEnum.SUCCESS, locale)));
    }

    @PostMapping("/trialAssistantDraft")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<ResultContext<AcceptedTrial>> trialAssistantDraft(@Valid @RequestBody Request request,
                                                                  @RequestHeader("Idempotency-Key") String key, Locale locale) {
        var timeout = Duration.ofSeconds(request.timeoutSeconds());
        var input = new AssistantTrialService.Request(request.assistantId(), request.draftRevision(), request.text(),
                request.capability(), request.binding(), request.maxInputTokens(), request.generationOptions(), timeout);
        return http.prepare(request.scope(), Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ),
                        timeout, request.budgetRef(), key, context -> trials.submit(input, context))
                .timeout(timeout).map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }
}
