package com.arte.app.web.controller.platform;

import com.arte.ainew.pojo.budget.Money;
import com.arte.app.platform.ai.AiControlData.BudgetPreset;
import com.arte.app.platform.ai.AiControlData.Preset;
import com.arte.app.platform.ai.AiControlData.WorkspaceView;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.ai.ReviewedModelSettings;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.app.web.platform.*;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 公共成员资格由服务端核验；
 * <p>
 * 控制面导入／发布只允许平台配置管理者。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
@RestController
@RequestMapping("/workspace/ai")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.ai-new", name = "configuration-mode", havingValue = "DATABASE")
public class WorkspaceAiConfigurationController {
    private final DatabaseAiControlService service;
    private final RbacIdentityProvider identities;
    private final PlatformPersistenceExecutor executor;
    private final com.arte.app.web.security.PermissionCheckService permissions;

    public WorkspaceAiConfigurationController(DatabaseAiControlService service, RbacIdentityProvider identities, PlatformPersistenceExecutor executor, com.arte.app.web.security.PermissionCheckService permissions) {
        this.service = service;
        this.identities = identities;
        this.executor = executor;
        this.permissions = permissions;
    }

    public record ScopeRequest(@NotNull Scope scope) {
    }

    public record UpdateRequest(@NotNull Scope scope, @NotNull @Min(0) Long expectedWorkspaceVersion,
                                @NotNull @Min(0) Long expectedVersion, @NotNull Preset preset) {
    }

    private <T> Mono<ResultContext<T>> call(Authentication authentication, Locale locale, Function<TrustedActor, T> work) {
        // Capture the immutable account identity before dispatch; actor() rechecks the current account in the DB thread.
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtUserDto(UserOnlineInfo online))) {
            return Mono.error(new PlatformException(PlatformErrorCodeEnum.UNAUTHENTICATED, "workspace-ai"));
        }
        var copy = new UserOnlineInfo().setId(online.getId()).setUserName(online.getUserName()).setAccountCreatedAt(online.getAccountCreatedAt());
        var snapshot = UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(copy), null, List.of());
        return executor.read(() -> work.apply(identities.actor(snapshot))).map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/ensure")
    public Mono<ResultContext<WorkspaceView>> ensure(Authentication auth, @Valid @RequestBody ScopeRequest request, Locale locale) {
        return call(auth, locale, actor -> service.ensure(actor, request.scope()));
    }

    @PostMapping("/getConfiguration")
    public Mono<ResultContext<WorkspaceView>> get(Authentication auth, @Valid @RequestBody ScopeRequest request, Locale locale) {
        return call(auth, locale, actor -> service.get(actor, request.scope()));
    }

    @PostMapping("/updateConfiguration")
    public Mono<ResultContext<CommandReceipt<WorkspaceView>>> update(Authentication auth, @Valid @RequestBody UpdateRequest request,
                                                                     @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        boolean canRaiseBudget = permissions.check("aiConfiguration:manage");
        return call(auth, locale, actor -> service.update(actor, new CommandMeta(key, PlatformHttpContext.traceId(http)), request.scope(),
                request.expectedWorkspaceVersion(), request.expectedVersion(), request.preset(), canRaiseBudget));
    }

    @PostMapping("/getCatalog")
    @PreAuthorize("@pcs.check('aiConfiguration:manage')")
    public Mono<ResultContext<AiCatalogPayload.View>> catalog(Authentication auth, Locale locale) {
        return call(auth, locale, actor -> AiCatalogPayload.View.from(service.catalog()));
    }

    @PostMapping("/initializeCatalog")
    @PreAuthorize("@pcs.check('aiConfiguration:manage')")
    public Mono<ResultContext<CommandReceipt<AiCatalogPayload.View>>> initializeCatalog(Authentication auth,
                                                                                        @RequestHeader("Idempotency-Key") String key,
                                                                                        HttpServletRequest http, Locale locale) {
        return call(auth, locale, actor -> AiCatalogPayload.View.receipt(service.initializeCatalog(actor,
                new CommandMeta(key, PlatformHttpContext.traceId(http)))));
    }

    public record ConnectionFields(
            @NotBlank @Size(max = 256) String id,
            @NotBlank String providerId, @NotNull java.net.URI endpoint,
            @Min(1) @Max(60) long connectTimeoutSeconds,
            @Min(1) @Max(900) long responseTimeoutSeconds,
            @Min(1) @Max(33554432) int maxResponseBytes) implements StrictPlatformRequest {
        DatabaseAiControlService.ConnectionSettings settings() {
            return new DatabaseAiControlService.ConnectionSettings(id, providerId, endpoint, connectTimeoutSeconds,
                    responseTimeoutSeconds, maxResponseBytes);
        }
    }

    public record ConnectionRequest(@NotNull @Min(0) Long expectedVersion,
                                    @NotNull @Valid ConnectionFields settings) implements StrictPlatformRequest {
    }

    public record ConnectionStateRequest(@NotNull @Min(0) Long expectedVersion,
                                         @NotBlank String id,
                                         @NotNull Boolean enabled) implements StrictPlatformRequest {
    }

    @PostMapping("/getConnections")
    @PreAuthorize("@pcs.check('aiConfiguration:manage')")
    public Mono<ResultContext<AiConnectionPayload.View>> connections(Authentication auth, Locale locale) {
        return call(auth, locale, actor -> AiConnectionPayload.View.from(service.connectionCatalog(actor),
                service.allowedConnectionOrigins()));
    }

    @PostMapping("/publishConnection")
    @PreAuthorize("@pcs.check('aiConfiguration:manage')")
    public Mono<ResultContext<CommandReceipt<AiConnectionPayload.View>>> publishConnection(Authentication auth,
                                                                                           @Valid @RequestBody ConnectionRequest request,
                                                                                           @RequestHeader("Idempotency-Key") String key,
                                                                                           HttpServletRequest http, Locale locale) {
        return call(auth, locale, actor -> AiConnectionPayload.View.receipt(service.publishConnection(actor,
                new CommandMeta(key, PlatformHttpContext.traceId(http)), request.expectedVersion(),
                request.settings().settings()), service.allowedConnectionOrigins()));
    }

    @PostMapping("/setConnectionEnabled")
    @PreAuthorize("@pcs.check('aiConfiguration:manage')")
    public Mono<ResultContext<CommandReceipt<AiConnectionPayload.View>>> connectionState(Authentication auth,
                                                                                         @Valid @RequestBody ConnectionStateRequest request,
                                                                                         @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        return call(auth, locale, actor -> AiConnectionPayload.View.receipt(service.setConnectionEnabled(actor,
                new CommandMeta(key, PlatformHttpContext.traceId(http)), request.expectedVersion(), request.id(),
                request.enabled()), service.allowedConnectionOrigins()));
    }

    public record DefaultPolicyRequest(@NotNull @Min(0) Long expectedCatalogVersion, @NotNull Boolean enabled,
                                       @NotNull @Size(max = 100) List<@NotNull @Valid DefaultRateCap> rateCaps) implements StrictPlatformRequest {
    }

    public record DefaultRateCap(@NotNull DefinitionRef rate,
                                 @NotNull @Valid DefaultBudgetLimit limit) implements StrictPlatformRequest {
        BudgetPreset preset() {
            return new BudgetPreset(rate,
                    new Money(new BigDecimal(limit.amount()), Currency.getInstance(limit.currency())));
        }
    }

    public record DefaultBudgetLimit(@NotBlank @Pattern(regexp = "[0-9]{1,18}(\\.[0-9]{1,18})?") String amount,
                                     @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) implements StrictPlatformRequest {
    }

    @PostMapping("/updateDefaultAiPolicy")
    @PreAuthorize("@pcs.check('aiConfiguration:manage')")
    public Mono<ResultContext<CommandReceipt<AiCatalogPayload.View>>> updateDefaultAiPolicy(Authentication auth,
                                                                                            @Valid @RequestBody DefaultPolicyRequest request,
                                                                                            @RequestHeader("Idempotency-Key") String key,
                                                                                            HttpServletRequest http,
                                                                                            Locale locale) {
        return call(auth, locale, actor -> AiCatalogPayload.View.receipt(service.updateDefaultAiPolicy(actor,
                new CommandMeta(key, PlatformHttpContext.traceId(http)), request.expectedCatalogVersion(), request.enabled(),
                request.rateCaps().stream().map(DefaultRateCap::preset).toList())));
    }

    public record ModelPlanFields(@NotBlank String id,
                                  @NotNull DefinitionRef connection,
                                  @NotBlank String remoteModel,
                                  @NotNull Long contextWindowTokens,
                                  @NotBlank String currency,
                                  @NotBlank String inputPerMillion,
                                  @NotBlank String outputPerMillion,
                                  @NotNull Boolean usageReporting,
                                  @NotNull Boolean toolCalls) implements StrictPlatformRequest {
        ReviewedModelSettings settings() {
            return new ReviewedModelSettings(id, connection, remoteModel, contextWindowTokens, currency, inputPerMillion,
                    outputPerMillion, usageReporting, toolCalls);
        }
    }

    public record ModelPlanRequest(@NotNull @Min(0) Long expectedVersion,
                                   @NotNull @Valid ModelPlanFields settings) implements StrictPlatformRequest {
    }

    @PostMapping("/getModelPlans")
    @PreAuthorize("@pcs.check('aiConfiguration:manage')")
    public Mono<ResultContext<AiModelPlanPayload.View>> modelPlans(Authentication auth, Locale locale) {
        return call(auth, locale, actor -> AiModelPlanPayload.View.from(service.connectionCatalog(actor)));
    }

    @PostMapping("/publishModelPlan")
    @PreAuthorize("@pcs.check('aiConfiguration:manage')")
    public Mono<ResultContext<CommandReceipt<AiModelPlanPayload.View>>> publishModelPlan(Authentication auth,
                                                                                         @Valid @RequestBody ModelPlanRequest request,
                                                                                         @RequestHeader("Idempotency-Key") String key,
                                                                                         HttpServletRequest http,
                                                                                         Locale locale) {
        return call(auth, locale, actor -> AiModelPlanPayload.View.receipt(service.publishModelPlan(actor,
                new CommandMeta(key, PlatformHttpContext.traceId(http)), request.expectedVersion(), request.settings().settings())));
    }
}
