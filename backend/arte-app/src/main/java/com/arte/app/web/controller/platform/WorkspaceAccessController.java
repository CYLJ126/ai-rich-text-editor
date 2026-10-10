package com.arte.app.web.controller.platform;

import com.arte.app.platform.workspace.ReactiveWorkspaceOperations;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.access.*;
import com.arte.core.utils.platform.PlatformChecks;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 展示型租户／空间权限检查，目标 ID 和用途由服务端构建，不返回内部授权版本或验证凭证。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 18:42 ✾
 */
@RestController
@RequestMapping("/workspace/access")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.platform.workspace", name = "enabled", havingValue = "true")
public class WorkspaceAccessController {
    private final ReactiveWorkspaceOperations operations;

    public WorkspaceAccessController(ReactiveWorkspaceOperations operations) {
        this.operations = operations;
    }

    public record CheckRequest(
            @NotNull AccessScope scope,
            @NotBlank @Pattern(regexp = "tenant|workspace") String resourceType,
            @NotBlank @Size(max = 96) String action) {
    }

    public record BatchRequest(@NotEmpty @Size(max = 100) List<@NotNull @Valid CheckRequest> requests) {
    }

    /**
     * 仅公开展示判断，不能作为后续命令的授权凭据。
     */
    public record AccessResponse(AccessScope scope, String resourceType, String action,
                                 boolean allowed, String reason, Instant checkedAt) {
    }

    @PostMapping("/checkWorkspaceAccess")
    public Mono<ResultContext<AccessResponse>> checkWorkspaceAccess(
            Authentication authentication, @Valid @RequestBody CheckRequest request, Locale locale) {
        return operations.check(authentication, toAccessRequest(request))
                .map(decision -> ResultContext.success(response(request, decision), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/batchCheckWorkspaceAccess")
    public Mono<ResultContext<List<AccessResponse>>> batchCheckWorkspaceAccess(
            Authentication authentication, @Valid @RequestBody BatchRequest request, Locale locale) {
        var inputs = request.requests().stream().map(WorkspaceAccessController::toAccessRequest).toList();
        return operations.batchCheck(authentication, inputs).map(decisions -> {
            var responses = java.util.stream.IntStream.range(0, decisions.size())
                    .mapToObj(index -> response(request.requests().get(index), decisions.get(index))).toList();
            return ResultContext.success(responses, ResultCodeEnum.SUCCESS, locale);
        });
    }

    private static AccessRequest toAccessRequest(CheckRequest request) {
        boolean tenant = request.resourceType().equals("tenant");
        PlatformChecks.require(!tenant || request.scope().workspaceId() == null, "tenant check requires tenant-only scope");
        String resourceId = tenant ? request.scope().tenantId() : request.scope().requireWorkspace().workspaceId();
        return new AccessRequest(request.scope(), new ResourceRef(request.scope().tenantId(),
                new ResourceType(request.resourceType()), resourceId), new Action(request.action()), "http-display");
    }

    private static AccessResponse response(CheckRequest request, AccessDecision decision) {
        // 权威不可用不伪装为普通权限拒绝；整个批次失败，前端不能缓存残缺允许结果。
        if (decision.reason() == AccessReasonEnum.AUTHORITY_UNAVAILABLE)
            throw new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "workspace-access");
        if (decision.reason() == AccessReasonEnum.SUBJECT_INACTIVE)
            throw new PlatformException(PlatformErrorCodeEnum.UNAUTHENTICATED, "workspace-access");
        String reason = decision.reason() == AccessReasonEnum.MEMBERSHIP_REQUIRED ? "NOT_FOUND" : decision.reason().name();
        return new AccessResponse(request.scope(), request.resourceType(), request.action(), decision.allowed(), reason, decision.checkedAt());
    }
}
