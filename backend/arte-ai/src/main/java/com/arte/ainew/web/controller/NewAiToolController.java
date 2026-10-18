package com.arte.ainew.web.controller;

import com.arte.ainew.application.tool.management.ToolManagementService;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.tool.ToolBindingCommand;
import com.arte.ainew.pojo.tool.ToolReleaseCommand;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.web.ToolManagementHttpResult;
import com.arte.ainew.web.request.ToolManagementRequests;
import com.arte.ainew.web.response.ToolManagementResponses;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Locale;

import static com.arte.ainew.web.ToolManagementValues.*;

/**
 * 本地 TOOL 目录、发布和个人绑定管理；主体由当前宿主认证捕获，实际授权由管理用例及提交门控执行。
 * 不暴露来源注册、远端端点、执行接口或内部快照；Controller 路径不含应用上下文 /arte。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
@RestController
@RequestMapping("/ai-new/tool")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled"}, havingValue = "true")
public class NewAiToolController {

    private final ToolManagementService service;
    private final ToolManagementAccess access;

    public NewAiToolController(ToolManagementService service, ToolManagementAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/listTools")
    public Mono<ResultContext<ToolManagementResponses.Page<ToolManagementResponses.Tool>>> listTools(Authentication auth, @Valid @RequestBody ToolManagementRequests.ListTools request, Locale locale) {
        return service.tools(access.capture(auth), request.scope().value(), Boolean.TRUE.equals(request.managed()), request.provider(), request.cursor(), size(request.size()))
                .map(page -> success(ToolManagementResponses.page(page, ToolManagementResponses::tool), locale));
    }

    @PostMapping("/getTool")
    public Mono<ResultContext<ToolManagementResponses.ToolDetail>> getTool(Authentication auth, @Valid @RequestBody ToolManagementRequests.FindTool request, Locale locale) {
        return service.tool(access.capture(auth), request.scope().value(), Boolean.TRUE.equals(request.managed()), request.tool()).map(value -> success(ToolManagementResponses.detail(value, Boolean.TRUE.equals(request.managed())), locale));
    }

    @PostMapping("/listProviders")
    public Mono<ResultContext<ToolManagementResponses.Page<ToolManagementResponses.Provider>>> listProviders(Authentication auth, @Valid @RequestBody ToolManagementRequests.Page request, Locale locale) {
        return service.providers(access.capture(auth), request.scope().value(), request.cursor(), size(request.size())).map(page -> success(ToolManagementResponses.page(page, ToolManagementResponses::provider), locale));
    }

    @PostMapping("/refreshLocalCatalog")
    public Mono<ResponseEntity<ResultContext<ToolManagementResponses.DiscoveryCommand>>> refresh(Authentication auth, @Valid @RequestBody ToolManagementRequests.RefreshLocalCatalog request,
                                                                                                 @RequestHeader("Idempotency-Key") String key, Locale locale) {
        return service.refresh(access.capture(auth), request.scope().value(), key, request.reason()).map(value -> {
            var data = ToolManagementResponses.discovery(value);
            if (value.receipt().value() != null && value.receipt().value().successful())
                return ResponseEntity.ok(success(data, locale));
            var code = value.receipt().code() == com.arte.ainew.pojo.execution.StoreOutcome.Code.IDEMPOTENCY_CONFLICT ? ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT : ResultCodeEnum.AI_TOOL_MANAGEMENT_UNAVAILABLE;
            return ResponseEntity.status(code == ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT ? 409 : 503).body(ResultContext.<ToolManagementResponses.DiscoveryCommand>fail(code, locale).setData(data));
        });
    }

    @PostMapping("/validateTool")
    public Mono<ResultContext<ToolManagementResponses.Validation>> validateTool(Authentication auth, @Valid @RequestBody ToolManagementRequests.ValidateTool request, Locale locale) {
        return service.validate(access.capture(auth), request.scope().value(), request.tool(), review(request.review())).map(value -> success(ToolManagementResponses.validation(value), locale));
    }

    @PostMapping("/publishTool")
    public Mono<ResponseEntity<ResultContext<ToolManagementResponses.ReleaseCommand>>> publishTool(Authentication auth, @Valid @RequestBody ToolManagementRequests.PublishTool request,
                                                                                                   @RequestHeader("Idempotency-Key") String key, Locale locale) {
        var command = new ToolReleaseCommand(request.tool(), ToolReleaseCommand.Action.PUBLISH, version(request.expectedVersion()), key, request.reason(), review(request.review()), null);
        return service.release(access.capture(auth), request.scope().value(), command).map(value -> ToolManagementHttpResult.release(value, locale));
    }

    @PostMapping("/setToolState")
    public Mono<ResponseEntity<ResultContext<ToolManagementResponses.ReleaseCommand>>> setToolState(Authentication auth, @Valid @RequestBody ToolManagementRequests.SetToolState request,
                                                                                                    @RequestHeader("Idempotency-Key") String key, Locale locale) {
        ContractChecks.require(request.action() != ToolReleaseCommand.Action.PUBLISH, "Publication requires review");
        var command = new ToolReleaseCommand(request.tool(), request.action(), version(request.expectedVersion()), key, request.reason(), null, request.existingBindingsUntil());
        return service.release(access.capture(auth), request.scope().value(), command).map(value -> ToolManagementHttpResult.release(value, locale));
    }

    @PostMapping("/getToolHistory")
    public Mono<ResultContext<ToolManagementResponses.Page<ToolManagementResponses.ReleaseAudit>>> toolHistory(Authentication auth, @Valid @RequestBody ToolManagementRequests.ReleaseHistory request, Locale locale) {
        return service.releaseHistory(access.capture(auth), request.scope().value(), request.tool(), request.cursor(), size(request.size())).map(page -> success(ToolManagementResponses.page(page, ToolManagementResponses::releaseAudit), locale));
    }

    @PostMapping("/listBindings")
    public Mono<ResultContext<ToolManagementResponses.Page<ToolManagementResponses.Binding>>> listBindings(Authentication auth, @Valid @RequestBody ToolManagementRequests.Page request, Locale locale) {
        return service.bindings(access.capture(auth), request.scope().value(), request.cursor(), size(request.size())).map(page -> success(ToolManagementResponses.page(page, ToolManagementResponses::binding), locale));
    }

    @PostMapping("/getBinding")
    public Mono<ResultContext<ToolManagementResponses.BindingDetail>> getBinding(Authentication auth, @Valid @RequestBody ToolManagementRequests.FindBinding request, Locale locale) {
        return service.binding(access.capture(auth), request.scope().value(), request.bindingId()).map(value -> success(ToolManagementResponses.detail(value), locale));
    }

    @PostMapping("/getBindingRevision")
    public Mono<ResultContext<ToolManagementResponses.Revision>> getBindingRevision(Authentication auth, @Valid @RequestBody ToolManagementRequests.FindRevision request, Locale locale) {
        return service.revision(access.capture(auth), request.scope().value(), request.revision()).map(value -> success(ToolManagementResponses.revision(value), locale));
    }

    @PostMapping("/saveBinding")
    public Mono<ResponseEntity<ResultContext<ToolManagementResponses.BindingCommand>>> saveBinding(Authentication auth, @Valid @RequestBody ToolManagementRequests.SaveBinding request,
                                                                                                   @RequestHeader("Idempotency-Key") String key, Locale locale) {
        var command = new ToolBindingCommand(ToolBindingCommand.Action.SAVE, request.bindingId(), version(request.expectedVersion()), key, request.reason(), settings(request.settings()), null, null, false);
        return service.changeBinding(access.capture(auth), request.scope().value(), command).map(value -> ToolManagementHttpResult.binding(value, locale));
    }

    @PostMapping("/setBindingState")
    public Mono<ResponseEntity<ResultContext<ToolManagementResponses.BindingCommand>>> setBindingState(Authentication auth, @Valid @RequestBody ToolManagementRequests.SetBindingState request,
                                                                                                       @RequestHeader("Idempotency-Key") String key, Locale locale) {
        var command = new ToolBindingCommand(ToolBindingCommand.Action.SET_ENABLED, request.bindingId(), version(request.expectedVersion()), key, request.reason(), null, request.enabled(), null, false);
        return service.changeBinding(access.capture(auth), request.scope().value(), command).map(value -> ToolManagementHttpResult.binding(value, locale));
    }

    @PostMapping("/previewBindingUpgrade")
    public Mono<ResultContext<ToolManagementResponses.Upgrade>> previewUpgrade(Authentication auth, @Valid @RequestBody ToolManagementRequests.PreviewUpgrade request, Locale locale) {
        return service.preview(access.capture(auth), request.scope().value(), request.bindingId(), version(request.expectedVersion()), settings(request.settings())).map(value -> success(ToolManagementResponses.upgrade(value), locale));
    }

    @PostMapping("/upgradeBinding")
    public Mono<ResponseEntity<ResultContext<ToolManagementResponses.BindingCommand>>> upgradeBinding(Authentication auth, @Valid @RequestBody ToolManagementRequests.UpgradeBinding request,
                                                                                                      @RequestHeader("Idempotency-Key") String key, Locale locale) {
        var command = new ToolBindingCommand(ToolBindingCommand.Action.UPGRADE, request.bindingId(), version(request.expectedVersion()), key, request.reason(), settings(request.settings()), null, request.expectedPreviewDigest(), Boolean.TRUE.equals(request.acceptIncompatible()));
        return service.changeBinding(access.capture(auth), request.scope().value(), command).map(value -> ToolManagementHttpResult.binding(value, locale));
    }

    @PostMapping("/getBindingHistory")
    public Mono<ResultContext<ToolManagementResponses.Page<ToolManagementResponses.BindingAudit>>> bindingHistory(Authentication auth, @Valid @RequestBody ToolManagementRequests.BindingHistory request, Locale locale) {
        return service.bindingHistory(access.capture(auth), request.scope().value(), request.bindingId(), request.cursor(), size(request.size())).map(page -> success(ToolManagementResponses.page(page, ToolManagementResponses::bindingAudit), locale));
    }

    private static <T> ResultContext<T> success(T value, Locale locale) {
        return ResultContext.success(value, ResultCodeEnum.SUCCESS, locale);
    }
}
