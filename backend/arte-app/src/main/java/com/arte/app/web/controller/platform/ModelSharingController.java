package com.arte.app.web.controller.platform;

import com.arte.ainew.application.model.DefaultModelSharingService;
import com.arte.ainew.pojo.model.ModelDrafts;
import com.arte.ainew.pojo.model.ModelSharingPolicy;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.platform.ModelResourcePayload;
import com.arte.app.web.platform.PlatformHttpContext;
import com.arte.app.web.platform.PlatformHttpViews;
import com.arte.app.web.platform.StrictPlatformRequest;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 租户模型和凭据用途的受信管理入口；不返回密钥及成员代次。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 19:25 ✾
 */
@RestController
@RequestMapping("/ai-new/model-sharing")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.ai-new-model-sharing", name = "enabled", havingValue = "true")
public class ModelSharingController {

    private final PlatformAuthenticatedExecutor executor;
    private final DefaultModelSharingService sharing;

    public ModelSharingController(PlatformAuthenticatedExecutor executor, DefaultModelSharingService sharing) {
        this.executor = executor;
        this.sharing = sharing;
    }

    public record Get(@NotNull Scope scope, @NotBlank String resourceId) implements StrictPlatformRequest {
    }

    public record UpdateScopes(@NotNull Scope scope, @NotBlank String resourceId, @NotNull @Min(0) Long expectedVersion,
                               @NotNull @Size(max = 256) List<@NotBlank @Size(max = 256) String> workspaceIds) implements StrictPlatformRequest {
    }

    public record UpdateConsumers(@NotNull Scope scope, @NotBlank String resourceId,
                                  @NotNull @Min(0) Long expectedVersion,
                                  @NotNull @Min(1) Long credentialVersion,
                                  @NotNull @Size(max = 256) List<@NotBlank @Size(max = 256) String> subjectIds) implements StrictPlatformRequest {
    }

    public record Policy(String resourceId, Scope scope, List<String> workspaceIds, long credentialVersion,
                         List<String> subjectIds, long version) {
        static Policy from(ModelSharingPolicy p) {
            return new Policy(p.resource().resourceId(), p.managementScope(), p.workspaceIds().stream().sorted().toList(),
                    p.credentialVersion(), p.consumers().keySet().stream().sorted().toList(), p.version());
        }
    }

    public record View(Policy policy, ModelDrafts.CredentialView credential, boolean mayManageScopes) {
    }

    public record Members(@NotNull Scope scope, @Min(1) Long current) implements StrictPlatformRequest {
        public Members {
            current = current == null ? 1 : current;
        }
    }

    public record Member(String subjectId, String role, String state) {
    }

    private <T> ResultContext<T> ok(T value, Locale locale) {
        return ResultContext.success(value, ResultCodeEnum.SUCCESS, locale);
    }

    private CommandMeta meta(String key, HttpServletRequest http) {
        return new CommandMeta(key, PlatformHttpContext.traceId(http));
    }

    private Set<String> unique(List<String> values) {
        if (Set.copyOf(values).size() != values.size()) {
            throw new IllegalArgumentException("Duplicate sharing target");
        }
        return Set.copyOf(values);
    }

    @PostMapping("/listTenantModelMembers")
    public Mono<ResultContext<PageResult<Member>>> members(Authentication auth, @Valid @RequestBody Members request, Locale locale) {
        return executor.call(auth, actor -> PlatformHttpViews.page(sharing.members(actor, request.scope(), new PageQuery(request.current(), 100)),
                value -> new Member(value.subjectId(), value.role().name(), value.state().name()))).map(v -> ok(v, locale));
    }

    @PostMapping("/getModelSharing")
    public Mono<ResultContext<View>> get(Authentication auth, @Valid @RequestBody Get request, Locale locale) {
        return executor.call(auth, actor -> {
            com.arte.core.pojo.access.ResourceRef ref = ModelResourcePayload.ref(request.scope(), request.resourceId());
            return new View(Policy.from(sharing.get(actor, request.scope(), ref)), sharing.credential(actor, request.scope(), ref), sharing.mayManageScopes(actor, request.scope(), ref));
        }).map(v -> ok(v, locale));
    }

    @PostMapping("/updateModelUseScopes")
    public Mono<ResultContext<CommandReceipt<Policy>>> scopes(Authentication auth, @Valid @RequestBody UpdateScopes request,
                                                              @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        Set<String> targets = unique(request.workspaceIds());
        return executor.call(auth, actor -> PlatformHttpViews.receipt(sharing.scopes(actor, meta(key, http), request.scope(),
                ModelResourcePayload.ref(request.scope(), request.resourceId()), request.expectedVersion(), targets), Policy::from)).map(v -> ok(v, locale));
    }

    @PostMapping("/updateModelCredentialConsumers")
    public Mono<ResultContext<CommandReceipt<Policy>>> consumers(Authentication auth, @Valid @RequestBody UpdateConsumers request,
                                                                 @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        Set<String> targets = unique(request.subjectIds());
        return executor.call(auth, actor -> PlatformHttpViews.receipt(sharing.consumers(actor, meta(key, http), request.scope(),
                ModelResourcePayload.ref(request.scope(), request.resourceId()), request.expectedVersion(), request.credentialVersion(), targets), Policy::from)).map(v -> ok(v, locale));
    }
}
