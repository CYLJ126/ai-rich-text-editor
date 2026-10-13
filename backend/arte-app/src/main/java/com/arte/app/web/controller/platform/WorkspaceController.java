package com.arte.app.web.controller.platform;

import com.arte.app.platform.workspace.ReactiveWorkspaceOperations;
import com.arte.app.web.platform.PlatformHttpContext;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;

/**
 * 个人初始化、空间目录与基础管理、本人空间偏好。
 * HTTP 只绑定选择与命令参数，身份、授权、事务及幂等由已有公共服务执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 18:42 ✾
 **/
@RestController
@RequestMapping("/workspace")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.platform.workspace", name = "enabled", havingValue = "true")
public class WorkspaceController {

    private final ReactiveWorkspaceOperations workspaceOperations;

    public WorkspaceController(ReactiveWorkspaceOperations workspaceOperations) {
        this.workspaceOperations = workspaceOperations;
    }

    public record ListRequest(@Min(1) Long current, @Min(1) @Max(100) Long size) {
        public ListRequest {
            current = current == null ? 1L : current;
            size = size == null ? 20L : size;
        }
    }

    public record GetRequest(@NotNull Scope scope) {
    }

    public record CommandReceiptRequest(@NotBlank @Size(max = 256) String operationId) {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 256) String tenantId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 1000) String description) {
    }

    public record RenameRequest(
            @NotNull Scope scope,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 1000) String description) {
    }

    /**
     * 偏好为完整目标值；null 清空启动／最近访问，空集合清空收藏。
     */
    public record UpdatePreferenceRequest(
            @NotNull @Min(0) Long expectedPreferenceVersion,
            Scope startupScope,
            Scope lastScope,
            @NotNull @Size(max = 100) Set<@NotNull Scope> favorites) {
    }

    /**
     * 为本人初始化唯一个人租户和默认空间，不接收客户端主体或角色。
     */
    @PostMapping("/initializePersonalWorkspace")
    public Mono<ResultContext<CommandReceipt<WorkspaceViews.PersonalWorkspaceView>>> initializePersonalWorkspace(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String operationKey,
            HttpServletRequest httpRequest,
            Locale locale) {
        return workspaceOperations.initializePersonal(authentication, commandMeta(operationKey, httpRequest))
                .map(receipt -> ResultContext.success(receipt, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * total 仅包含当前用户可访问的空间；缺省分页为 1／20。
     */
    @PostMapping("/listAccessibleWorkspaces")
    public Mono<PageView<WorkspaceViews.WorkspaceSummary>> listAccessibleWorkspaces(
            Authentication authentication,
            @Valid @RequestBody ListRequest request,
            Locale locale) {
        return workspaceOperations.listAccessible(authentication, new PageQuery(request.current(), request.size()))
                .map(page -> pageView(page, locale));
    }

    @PostMapping("/getWorkspace")
    public Mono<ResultContext<WorkspaceViews.WorkspaceView>> getWorkspace(
            Authentication authentication,
            @Valid @RequestBody GetRequest request,
            Locale locale) {
        return workspaceOperations.get(authentication, request.scope())
                .map(view -> ResultContext.success(view, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/createWorkspace")
    public Mono<ResultContext<CommandReceipt<WorkspaceViews.WorkspaceView>>> createWorkspace(
            Authentication authentication,
            @Valid @RequestBody CreateRequest request,
            @RequestHeader("Idempotency-Key") String operationKey,
            HttpServletRequest httpRequest,
            Locale locale) {
        var command = new WorkspaceCommands.CreateWorkspace(commandMeta(operationKey, httpRequest),
                request.tenantId(), request.name(), request.description());
        return workspaceOperations.create(authentication, command)
                .map(receipt -> ResultContext.success(receipt, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/renameWorkspace")
    public Mono<ResultContext<CommandReceipt<WorkspaceViews.WorkspaceView>>> renameWorkspace(
            Authentication authentication,
            @Valid @RequestBody RenameRequest request,
            @RequestHeader("Idempotency-Key") String operationKey,
            HttpServletRequest httpRequest,
            Locale locale) {
        var meta = commandMeta(operationKey, httpRequest);
        var command = new WorkspaceCommands.RenameWorkspace(meta, request.scope(), request.expectedVersion(),
                request.name(), request.description());
        return workspaceOperations.rename(authentication, command)
                .map(receipt -> ResultContext.success(receipt, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getWorkspacePreference")
    public Mono<ResultContext<WorkspaceViews.PreferenceView>> getWorkspacePreference(
            Authentication authentication, Locale locale) {
        return workspaceOperations.getPreference(authentication)
                .map(view -> ResultContext.success(view, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/updateWorkspacePreference")
    public Mono<ResultContext<CommandReceipt<WorkspaceViews.PreferenceView>>> updateWorkspacePreference(
            Authentication authentication,
            @Valid @RequestBody UpdatePreferenceRequest request,
            @RequestHeader("Idempotency-Key") String operationKey,
            HttpServletRequest httpRequest,
            Locale locale) {
        var command = new WorkspaceCommands.UpdatePreference(commandMeta(operationKey, httpRequest),
                request.expectedPreferenceVersion(), request.startupScope(), request.lastScope(), request.favorites());
        return workspaceOperations.updatePreference(authentication, command)
                .map(receipt -> ResultContext.success(receipt, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 仅原操作者且结果当前仍可读，返回历史成功回执，不重新执行命令。
     */
    @PostMapping("/getWorkspaceCommandReceipt")
    public Mono<ResultContext<CommandReceipt<?>>> getWorkspaceCommandReceipt(
            Authentication authentication,
            @Valid @RequestBody CommandReceiptRequest request,
            Locale locale) {
        return workspaceOperations.getCommandReceipt(authentication, request.operationId())
                .map(receipt -> ResultContext.success(receipt, ResultCodeEnum.SUCCESS, locale));
    }

    public record TenantPolicyRequest(@NotBlank @Size(max = 256) String tenantId) {
    }

    public record UpdateTenantPolicyRequest(@NotBlank @Size(max = 256) String tenantId,
                                            @NotNull @Min(0) Long expectedTenantVersion,
                                            @NotNull @Min(0) Long expectedPolicyVersion,
                                            @NotNull PolicyRules rules, @NotNull Boolean membersMayCreateWorkspace) {
    }

    public record UpdateWorkspacePolicyRequest(@NotNull Scope scope,
                                               @NotNull @Min(0) Long expectedWorkspaceVersion,
                                               @NotNull @Min(0) Long expectedPolicyVersion,
                                               @NotNull PolicyRules rules) {
    }

    @PostMapping("/getTenantPolicy")
    public Mono<ResultContext<WorkspacePolicyViews.TenantPolicyView>> getTenantPolicy(Authentication authentication,
                                                                                      @Valid @RequestBody TenantPolicyRequest request, Locale locale) {
        return workspaceOperations.getTenantPolicy(authentication, request.tenantId())
                .map(policy -> ResultContext.success(policy, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getWorkspacePolicy")
    public Mono<ResultContext<WorkspacePolicyViews.WorkspacePolicyView>> getWorkspacePolicy(Authentication authentication,
                                                                                            @Valid @RequestBody GetRequest request, Locale locale) {
        return workspaceOperations.getWorkspacePolicy(authentication, request.scope())
                .map(policy -> ResultContext.success(policy, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/updateTenantPolicy")
    public Mono<ResultContext<CommandReceipt<TenantPolicy>>> updateTenantPolicy(Authentication authentication,
                                                                                @Valid @RequestBody UpdateTenantPolicyRequest request, @RequestHeader("Idempotency-Key") String operationKey,
                                                                                HttpServletRequest httpRequest, Locale locale) {
        var command = new TenantCommands.UpdateTenantPolicy(commandMeta(operationKey, httpRequest), request.tenantId(),
                request.expectedTenantVersion(), request.expectedPolicyVersion(), request.rules(), request.membersMayCreateWorkspace());
        return workspaceOperations.updateTenantPolicy(authentication, command)
                .map(receipt -> ResultContext.success(receipt, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/updateWorkspacePolicy")
    public Mono<ResultContext<CommandReceipt<WorkspacePolicy>>> updateWorkspacePolicy(Authentication authentication,
                                                                                      @Valid @RequestBody UpdateWorkspacePolicyRequest request, @RequestHeader("Idempotency-Key") String operationKey,
                                                                                      HttpServletRequest httpRequest, Locale locale) {
        var command = new WorkspaceCommands.UpdateWorkspacePolicy(commandMeta(operationKey, httpRequest), request.scope(),
                request.expectedWorkspaceVersion(), request.expectedPolicyVersion(), request.rules());
        return workspaceOperations.updateWorkspacePolicy(authentication, command)
                .map(receipt -> ResultContext.success(receipt, ResultCodeEnum.SUCCESS, locale));
    }

    private static CommandMeta commandMeta(String operationKey, HttpServletRequest request) {
        return new CommandMeta(operationKey, PlatformHttpContext.traceId(request));
    }

    private static <T> PageView<T> pageView(PageResult<T> page, Locale locale) {
        var result = new PageView<T>(locale);
        result.setCurrent(page.current());
        result.setSize(page.size());
        result.setTotal(page.total());
        result.setRecords(page.records());
        return result;
    }
}
