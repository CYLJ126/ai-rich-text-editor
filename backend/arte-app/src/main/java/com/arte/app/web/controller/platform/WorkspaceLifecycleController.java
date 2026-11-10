package com.arte.app.web.controller.platform;

import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.platform.PlatformHttpContext;
import com.arte.app.web.platform.StrictPlatformRequest;
import com.arte.core.application.workspace.DefaultWorkspaceLifecycleService;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.ClosurePreview;
import com.arte.core.pojo.workspace.ClosureView;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspaceViews;
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
 * 生命周期显式操作，可信主体来自会话，写入使用原操作键与版本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/10 18:40 ✾
 */
@RestController
@RequestMapping("/workspace")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.platform.workspace", name = "enabled", havingValue = "true")
public class WorkspaceLifecycleController {

    private final DefaultWorkspaceLifecycleService service;
    private final PlatformAuthenticatedExecutor executor;

    public WorkspaceLifecycleController(DefaultWorkspaceLifecycleService service, PlatformAuthenticatedExecutor executor) {
        this.service = service;
        this.executor = executor;
    }

    public record Target(@NotNull Scope scope) implements StrictPlatformRequest {
    }

    public record Change(@NotNull Scope scope, @NotNull @Min(0) Long expectedWorkspaceVersion,
                         @Size(max = 1000) String reason) implements StrictPlatformRequest {
    }

    public record Progress(@NotNull Scope scope,
                           @NotBlank @Size(max = 256) String operationId) implements StrictPlatformRequest {
    }

    public record Page(@Min(1) Long current, @Min(1) @Max(100) Long size) implements StrictPlatformRequest {
    }

    private <T> Mono<ResultContext<T>> call(Authentication auth, Locale locale, Function<TrustedActor, T> work) {
        return executor.call(auth, work).map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getWorkspaceLifecycleCapabilities")
    public Mono<ResultContext<DefaultWorkspaceLifecycleService.Capabilities>> capabilities(Authentication auth, @Valid @RequestBody Target target, Locale locale) {
        return call(auth, locale, actor -> service.capabilities(actor, target.scope()));
    }

    @PostMapping("/archiveWorkspace")
    public Mono<ResultContext<CommandReceipt<WorkspaceViews.WorkspaceView>>> archive(Authentication auth, @Valid @RequestBody Change request,
                                                                                     @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        return call(auth, locale, actor -> service.archive(actor, new WorkspaceCommands.ChangeWorkspaceState(
                new CommandMeta(key, PlatformHttpContext.traceId(http)), request.scope(), request.expectedWorkspaceVersion(), request.reason())));
    }

    @PostMapping("/unarchiveWorkspace")
    public Mono<ResultContext<CommandReceipt<WorkspaceViews.WorkspaceView>>> unarchive(Authentication auth, @Valid @RequestBody Change request,
                                                                                       @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        return call(auth, locale, actor -> service.unarchive(actor, new WorkspaceCommands.ChangeWorkspaceState(
                new CommandMeta(key, PlatformHttpContext.traceId(http)), request.scope(), request.expectedWorkspaceVersion(), request.reason())));
    }

    @PostMapping("/previewWorkspaceClosure")
    public Mono<ResultContext<ClosurePreview>> preview(Authentication auth, @Valid @RequestBody Target request, Locale locale) {
        return call(auth, locale, actor -> service.preview(actor, request.scope()));
    }

    @PostMapping("/closeWorkspace")
    public Mono<org.springframework.http.ResponseEntity<ResultContext<CommandReceipt<ClosureView>>>> close(Authentication auth, @Valid @RequestBody Change request,
                                                                                                           @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        return call(auth, locale, actor -> service.close(actor, new WorkspaceCommands.CloseWorkspace(
                new CommandMeta(key, PlatformHttpContext.traceId(http)), request.scope(), request.expectedWorkspaceVersion(), request.reason())))
                .map(value -> org.springframework.http.ResponseEntity.accepted().body(value));
    }

    @PostMapping("/getWorkspaceClosureProgress")
    public Mono<ResultContext<ClosureView>> progress(Authentication auth, @Valid @RequestBody Progress request, Locale locale) {
        return call(auth, locale, actor -> service.progress(actor, request.scope(), request.operationId()));
    }

    @PostMapping("/listWorkspaceClosures")
    public Mono<ResultContext<PageResult<DefaultWorkspaceLifecycleService.Record>>> list(Authentication auth, @Valid @RequestBody Page request, Locale locale) {
        return call(auth, locale, actor -> service.list(actor, new PageQuery(request.current() == null ? 1 : request.current(), request.size() == null ? 20 : request.size())));
    }
}
