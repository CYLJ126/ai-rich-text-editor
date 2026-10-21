package com.arte.app.web.controller.platform;

import com.arte.app.platform.ai.AiControlData.Preset;
import com.arte.app.platform.ai.AiControlData.WorkspaceView;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.app.web.platform.AiCatalogPayload;
import com.arte.app.web.platform.PlatformHttpContext;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Locale;

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

    public record PublishRequest(@NotNull @Min(0) Long expectedVersion, @NotNull AiCatalogPayload catalog) {
    }

    private <T> Mono<ResultContext<T>> call(Authentication authentication, Locale locale, java.util.function.Function<TrustedActor, T> work) {
        // Capture the immutable account identity before dispatch; actor() rechecks the current account in the DB thread.
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof com.arte.app.pojo.rbac.JwtUserDto(
                com.arte.core.pojo.UserOnlineInfo online
        )))
            return Mono.error(new com.arte.core.exception.platform.PlatformException(com.arte.core.enums.platform.PlatformErrorCodeEnum.UNAUTHENTICATED, "workspace-ai"));
        var copy = new com.arte.core.pojo.UserOnlineInfo().setId(online.getId()).setUserName(online.getUserName()).setAccountCreatedAt(online.getAccountCreatedAt());
        var snapshot = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(new com.arte.app.pojo.rbac.JwtUserDto(copy), null, java.util.List.of());
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

    @PostMapping("/importConfiguration")
    @PreAuthorize("@pcs.check('aiConfiguration:manage')")
    public Mono<ResultContext<CommandReceipt<AiCatalogPayload.View>>> importConfiguration(Authentication auth,
                                                                                @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        return call(auth, locale, actor -> AiCatalogPayload.View.receipt(service.importConfiguration(actor, new CommandMeta(key, PlatformHttpContext.traceId(http)))));
    }

    @PostMapping("/publishCatalog")
    @PreAuthorize("@pcs.check('aiConfiguration:manage')")
    public Mono<ResultContext<CommandReceipt<AiCatalogPayload.View>>> publish(Authentication auth, @Valid @RequestBody PublishRequest request,
                                                                    @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        return call(auth, locale, actor -> AiCatalogPayload.View.receipt(service.publish(actor,
                new CommandMeta(key, PlatformHttpContext.traceId(http)), request.expectedVersion(), request.catalog().toCatalog())));
    }
}
