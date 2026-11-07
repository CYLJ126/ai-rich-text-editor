package com.arte.app.web.controller.platform;

import com.arte.ainew.api.control.AssistantManager;
import com.arte.ainew.application.assistant.DefaultAssistantManager;
import com.arte.ainew.pojo.assistant.Assistants.*;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.platform.PlatformHttpContext;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.function.Function;

/**
 * 助手管理控制器
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 00:09 ✾
 */
@RestController
@RequestMapping("/ai-new/assistant")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-assistant.enabled", "platform.identity.enabled",
        "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
public class AssistantManagementController {

    private final PlatformAuthenticatedExecutor executor;
    private final AssistantManager assistants;

    public AssistantManagementController(PlatformAuthenticatedExecutor executor, AssistantManager assistants) {
        this.executor = executor;
        this.assistants = assistants;
    }

    public record DirectoryRequest(@NotNull Scope scope, @Size(max = 36) String cursor, @Size(max = 160) String name,
                                   @NotNull @Min(1) @Max(100) Integer limit) {
    }

    public record GetRequest(@NotNull Scope scope, @NotBlank @Size(max = 36) String assistantId) {
    }

    public record VersionsRequest(@NotNull Scope scope, @NotBlank @Size(max = 36) String assistantId,
                                  @Size(max = 19) String cursor,
                                  @NotNull @Min(1) @Max(100) Integer limit) {
    }

    public record CreateRequest(@NotNull Scope scope, @NotNull Content content) {
    }

    public record CopyRequest(@NotNull Scope scope, @NotBlank @Size(max = 36) String sourceId,
                              @Positive Long sourceVersion, @Positive Long sourceDraftRevision) {
    }

    public record SaveRequest(@NotNull Scope scope, @NotBlank @Size(max = 36) String assistantId,
                              @NotNull @Positive Long expectedRevision, @NotNull Content content) {
    }

    public record PublishRequest(@NotNull Scope scope, @NotBlank @Size(max = 36) String assistantId,
                                 @NotNull @PositiveOrZero Long expectedVersion,
                                 @NotNull @Positive Long expectedRevision,
                                 @NotBlank @Size(max = 36) String trialId) {
    }

    public record DisableRequest(@NotNull Scope scope, @NotBlank @Size(max = 36) String assistantId,
                                 @NotNull @PositiveOrZero Long expectedVersion) {
    }

    public record CapabilitiesRequest(@NotNull Scope scope, @Size(max = 36) String assistantId) {
    }

    @PostMapping("/listManagedAssistants")
    public Mono<ResultContext<Page<Managed>>> managed(Authentication auth, @Valid @RequestBody DirectoryRequest request, Locale locale) {
        return call(auth, locale, actor -> assistants.managed(actor, request.scope(), request.cursor(), request.name(), request.limit()));
    }

    @PostMapping("/listUsableAssistants")
    public Mono<ResultContext<Page<Summary>>> usable(Authentication auth, @Valid @RequestBody DirectoryRequest request, Locale locale) {
        return call(auth, locale, actor -> assistants.usable(actor, request.scope(), request.cursor(), request.name(), request.limit()));
    }

    @PostMapping("/getAssistantCapabilities")
    public Mono<ResultContext<Capabilities>> capabilities(Authentication auth, @Valid @RequestBody CapabilitiesRequest request, Locale locale) {
        return call(auth, locale, actor -> assistants.capabilities(actor, request.scope(), request.assistantId()));
    }

    @PostMapping("/getAssistant")
    public Mono<ResultContext<Detail>> get(Authentication auth, @Valid @RequestBody GetRequest request, Locale locale) {
        return call(auth, locale, actor -> assistants.get(actor, request.scope(), request.assistantId()));
    }

    @PostMapping("/listAssistantVersions")
    public Mono<ResultContext<Page<Release>>> versions(Authentication auth, @Valid @RequestBody VersionsRequest request, Locale locale) {
        return call(auth, locale, actor -> assistants.versions(actor, request.scope(), request.assistantId(),
                request.cursor() == null ? 0 : DefaultAssistantManager.parseVersion(request.cursor()), request.limit()));
    }

    @PostMapping("/createAssistant")
    public Mono<ResultContext<CommandReceipt<Receipt>>> create(Authentication auth, @Valid @RequestBody CreateRequest request,
                                                               @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        CommandMeta meta = meta(key, http);
        return call(auth, locale, actor -> assistants.create(actor, request.scope(), request.content(), meta));
    }

    @PostMapping("/copyAssistant")
    public Mono<ResultContext<CommandReceipt<Receipt>>> copy(Authentication auth, @Valid @RequestBody CopyRequest request,
                                                             @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        CommandMeta meta = meta(key, http);
        return call(auth, locale, actor -> assistants.copy(actor, request.scope(), request.sourceId(),
                request.sourceVersion(), request.sourceDraftRevision(), meta));
    }

    @PostMapping("/saveAssistantDraft")
    public Mono<ResultContext<CommandReceipt<Receipt>>> save(Authentication auth, @Valid @RequestBody SaveRequest request,
                                                             @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        CommandMeta meta = meta(key, http);
        return call(auth, locale, actor -> assistants.saveDraft(actor, request.scope(), request.assistantId(),
                request.expectedRevision(), request.content(), meta));
    }

    @PostMapping("/publishAssistant")
    public Mono<ResultContext<CommandReceipt<Receipt>>> publish(Authentication auth, @Valid @RequestBody PublishRequest request,
                                                                @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        CommandMeta meta = meta(key, http);
        return call(auth, locale, actor -> assistants.publish(actor, request.scope(), request.assistantId(),
                request.expectedVersion(), request.expectedRevision(), request.trialId(), meta));
    }

    @PostMapping("/disableAssistant")
    public Mono<ResultContext<CommandReceipt<Receipt>>> disable(Authentication auth, @Valid @RequestBody DisableRequest request,
                                                                @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        CommandMeta meta = meta(key, http);
        return call(auth, locale, actor -> assistants.disable(actor, request.scope(), request.assistantId(), request.expectedVersion(), meta));
    }

    private static CommandMeta meta(String key, HttpServletRequest http) {
        return new CommandMeta(key, PlatformHttpContext.traceId(http));
    }

    private <T> Mono<ResultContext<T>> call(Authentication auth, Locale locale, Function<TrustedActor, T> work) {
        return executor.call(auth, work).map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }
}
