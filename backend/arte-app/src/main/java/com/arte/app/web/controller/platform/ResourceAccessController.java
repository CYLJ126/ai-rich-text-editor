package com.arte.app.web.controller.platform;

import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.platform.PlatformHttpContext;
import com.arte.app.web.platform.PlatformHttpViews;
import com.arte.app.web.platform.ResourceAccessPayload;
import com.arte.app.web.platform.ResourceAccessPayload.*;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.access.ResourceAccessService;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.enums.access.ResourceVisibilityEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.access.AccessDecision;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.ResourceAccessCommands;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 公共资源 ACL 查询、授权及展示检查；复用公共应用服务，不实现另一套权限算法。
 * 请求只定位目标及变更意图，主体来自当前受信会话；成员代次、真实归属和授权来源由服务端核验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:00 ✾
 */
@RestController
@RequestMapping("/resource/access")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"platform.identity.enabled", "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
public class ResourceAccessController {

    private final PlatformAuthenticatedExecutor executor;
    private final ResourceAccessService resources;
    private final AuthorizationService authorization;

    public ResourceAccessController(PlatformAuthenticatedExecutor executor, ResourceAccessService resources,
                                    @Qualifier("platformAuthorizationService") AuthorizationService authorization) {
        this.executor = executor;
        this.resources = resources;
        this.authorization = authorization;
    }

    /**
     * 当前 ACL 管理者读取访问模式及后续写入所需的 CAS 版本。
     */
    @PostMapping("/getResourceAccess")
    public Mono<ResultContext<Access>> getResourceAccess(Authentication auth, @Valid @RequestBody GetRequest request, Locale locale) {
        return executor.call(auth, actor -> Access.from(resources.getAccess(actor, request.target().scope(), request.target().ref())))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 分页结果仅向当前 ACL 管理者公开，不返回内部成员代次。
     */
    @PostMapping("/listResourceGrants")
    public Mono<ResultContext<PageResult<Grant>>> listResourceGrants(Authentication auth, @Valid @RequestBody ListRequest request, Locale locale) {
        return executor.call(auth, actor -> PlatformHttpViews.page(resources.listGrants(actor, request.target().scope(), request.target().ref(),
                        new PageQuery(request.current(), request.size())), Grant::from))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 每个动作独立授权；实际委派权限、政策、成员资格和期限在原事务内重新核验。
     */
    @PostMapping("/grantResourceAccess")
    public Mono<ResultContext<CommandReceipt<GrantResult>>> grantResourceAccess(Authentication auth, @Valid @RequestBody GrantRequest request,
                                                                                @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        ResourceAccessCommands.GrantResource command = new ResourceAccessCommands.GrantResource(meta(key, http), request.target().scope(), request.target().ref(),
                request.expectedAclVersion(), request.recipient().recipient(request.target().scope()), ResourceAccessPayload.actionSet(request.actions()), request.expiresAt());
        return executor.call(auth, actor -> PlatformHttpViews.receipt(resources.grant(actor, command), GrantResult::from))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 只撤销指定来源记录，不删除其他独立允许项。
     */
    @PostMapping("/revokeResourceGrant")
    public Mono<ResultContext<CommandReceipt<Void>>> revokeResourceGrant(Authentication auth, @Valid @RequestBody RevokeRequest request,
                                                                         @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        ResourceAccessCommands.RevokeGrant command = new ResourceAccessCommands.RevokeGrant(meta(key, http), request.target().scope(), request.target().ref(),
                request.grantId(), request.expectedAclVersion(), request.expectedGrantVersion());
        return executor.call(auth, actor -> resources.revoke(actor, command))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 完整替换共享模式规则；EXPLICIT 必须提交空规则，不清除独立主体授权。
     */
    @PostMapping("/changeResourceVisibility")
    public Mono<ResultContext<CommandReceipt<Access>>> changeResourceVisibility(Authentication auth, @Valid @RequestBody VisibilityRequest request,
                                                                                @RequestHeader("Idempotency-Key") String key, HttpServletRequest http, Locale locale) {
        ResourceAccessCommands.ChangeVisibility command = new ResourceAccessCommands.ChangeVisibility(meta(key, http), request.target().scope(), request.target().ref(),
                request.expectedAclVersion(), ResourceVisibilityEnum.valueOf(request.visibility()), request.sharedGrants().stream().map(value -> value.toGrant(request.target().scope())).toList());
        return executor.call(auth, actor -> PlatformHttpViews.receipt(resources.changeVisibility(actor, command), Access::from))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 当前资源动作展示检查；返回值不授予后续写入权。
     */
    @PostMapping("/checkResourceAccess")
    public Mono<ResultContext<CheckResult>> checkResourceAccess(Authentication auth, @Valid @RequestBody CheckRequest request, Locale locale) {
        AccessRequest target = request.request();
        return executor.call(auth, actor -> CheckResult.from(request, authorization.check(actor, target)))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 最多 100 项，保留输入顺序；任一权威故障使整批失败。
     */
    @PostMapping("/batchCheckResourceAccess")
    public Mono<ResultContext<List<CheckResult>>> batchCheckResourceAccess(Authentication auth, @Valid @RequestBody BatchRequest request, Locale locale) {
        List<AccessRequest> targets = request.requests().stream().map(CheckRequest::request).toList();
        return executor.call(auth, actor -> {
            List<AccessDecision> decisions = authorization.batchCheck(actor, targets);
            if (decisions.size() != targets.size()) {
                throw new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "resource-access");
            }
            List<CheckResult> results = new ArrayList<>();
            for (int i = 0; i < decisions.size(); i++) {
                results.add(CheckResult.from(request.requests().get(i), decisions.get(i)));
            }
            return List.copyOf(results);
        }).map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    private static CommandMeta meta(String key, HttpServletRequest request) {
        return new CommandMeta(key, PlatformHttpContext.traceId(request));
    }
}
