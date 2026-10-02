package com.arte.app.security.bridge;

import com.arte.base.api.security.AuthorizationService;
import com.arte.base.model.security.AuthorizationDecision;
import com.arte.base.model.security.AuthorizationRequest;
import com.arte.base.model.security.CommonResourceAction;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.Set;

/**
 * 新授权端口的 app 提供者；内容运维角色不成为资源许可，旧入口保持原行为。
 */
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class ExistingAuthorizationService implements AuthorizationService {
    private static final Set<CommonResourceAction> LEGACY_ACTIONS = Set.of(CommonResourceAction.READ,
            CommonResourceAction.ANNOTATE, CommonResourceAction.COMMENT, CommonResourceAction.EDIT,
            CommonResourceAction.MANAGE_SHARING);
    private final JdbcSecurityRepository repository;
    private final LegacyResourcePermissions resources;
    private final Clock clock;

    public ExistingAuthorizationService(JdbcSecurityRepository repository, LegacyResourcePermissions resources, Clock clock) {
        this.repository = repository;
        this.resources = resources;
        this.clock = clock;
    }

    // 显式声明强制入口，使 Spring 事务代理覆盖接口 default 方法，避免复用外层事务快照。
    @Override
    public AuthorizationDecision requireAuthorized(AuthorizationRequest request) {
        return AuthorizationService.super.requireAuthorized(request, clock);
    }

    @Override
    public AuthorizationDecision requireAuthorized(AuthorizationRequest request, Clock checkClock) {
        return AuthorizationService.super.requireAuthorized(request, checkClock);
    }

    @Override
    public AuthorizationDecision evaluate(AuthorizationRequest request) {
        var evaluation = new BridgePolicyEvaluation(repository, clock.instant());
        try {
            var account = evaluation.identity(request.context());
            var action = Arrays.stream(CommonResourceAction.values()).filter(a -> a.code().equals(request.actionCode()))
                    .findFirst().orElseThrow(() -> BridgePolicyEvaluation.unknown("action_unsupported"));
            evaluation.task(request.context(), request.executor(), action.code());
            var resource = request.resource();
            if (!"ARTICLE".equals(resource.resourceType()) && !"CATALOG".equals(resource.resourceType())) {
                throw BridgePolicyEvaluation.unknown("resource_unsupported");
            }
            var taskResource = repository.taskResourceAction(request.context(), resource, action.code())
                    .orElseThrow(() -> BridgePolicyEvaluation.deny("resource_outside_task"));
            if (!taskResource.enabled()) throw BridgePolicyEvaluation.deny("task_resource_revoked");
            evaluation.version("task-resource", taskResource.revision());
            var placement = repository.placement(resource).orElseThrow(() -> BridgePolicyEvaluation.unknown("resource_scope_missing"));
            var scope = request.context().scope();
            if (!placement.enabled() || !placement.tenantId().equals(scope.tenantId())
                    || !placement.workspaceId().equals(scope.workspaceId()))
                throw BridgePolicyEvaluation.deny("resource_scope_mismatch");
            evaluation.version("resource-scope", placement.revision());
            var permission = resources.snapshot(resource, account.userName());
            if (permission == null) throw BridgePolicyEvaluation.deny("resource_missing");
            if (!permission.permits(action)) throw BridgePolicyEvaluation.deny("resource_action_denied");
            evaluation.version("legacy-resource", permission.revision());
            if (!LEGACY_ACTIONS.contains(action)) {
                var grant = repository.resourceGrant(resource, account.principal(), action.code())
                        .orElseThrow(() -> BridgePolicyEvaluation.deny("explicit_resource_grant_missing"));
                if (!grant.enabled()) throw BridgePolicyEvaluation.deny("explicit_resource_grant_revoked");
                evaluation.version("resource-grant", grant.revision());
            }
            return new AuthorizationDecision(request, evaluation.allow());
        } catch (BridgePolicyEvaluation.Rejection rejected) {
            return new AuthorizationDecision(request, evaluation.rejection(rejected));
        }
    }

    /**
     * 外发调用方需要完整合并资料授权的实际版本，并保留其短期有效窗口。
     */
    void check(AuthorizationRequest request, BridgePolicyEvaluation evaluation) {
        var decision = requireAuthorized(request, clock);
        String key = SecurityFingerprints.resource(request.resource()) + "." + request.actionCode();
        decision.policy().policyVersions().forEach((id, revision) -> evaluation.versions.put(key + "." + id, revision));
        evaluation.limit(decision.policy().validUntil());
    }
}
