package com.arte.core.application.access;

import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.workspace.WorkspaceAccessSupport;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.*;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.spi.access.ActionRegistry;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.TENANT;
import static com.arte.core.constant.platform.PlatformResourceTypes.WORKSPACE;

/**
 * 第二步基础判定：可信租户／空间治理与创建资格；业务资源 ACL 在后续阶段接入此入口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultAuthorizationService implements AuthorizationService {

    private final WorkspaceAccessSupport workspaceAccessSupport;
    private final ActionRegistry actionRegistry;
    private final Clock clock;

    public DefaultAuthorizationService(WorkspaceAccessSupport workspaceAccessSupport, ActionRegistry actionRegistry, Clock clock) {
        this.workspaceAccessSupport = Objects.requireNonNull(workspaceAccessSupport);
        this.actionRegistry = Objects.requireNonNull(actionRegistry);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 尚不存在的空间不能先验证空间成员，创建资格只验证当前租户与结构化政策。
     */
    public WorkspaceAccessSupport.TenantSnapshot requireWorkspaceCreation(TrustedActor actor, String tenantId) {
        return requireWorkspaceCreation(actor, tenantId, false);
    }

    public WorkspaceAccessSupport.TenantSnapshot requireWorkspaceCreationCurrent(TrustedActor actor, String tenantId) {
        return requireWorkspaceCreation(actor, tenantId, true);
    }

    private WorkspaceAccessSupport.TenantSnapshot requireWorkspaceCreation(TrustedActor actor, String tenantId, boolean currentRead) {
        var tenant = currentRead ? workspaceAccessSupport.tenantCurrent(actor, tenantId) : workspaceAccessSupport.tenant(actor, tenantId);
        var definition = actionRegistry.find(TENANT_WORKSPACE_CREATE).orElseThrow(() -> error(PlatformErrorCodeEnum.UNKNOWN_ACTION));
        if (!definition.resourceTypes().contains(TENANT) || !DefaultRolePermissions.mayCreateWorkspace(tenant)) {
            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        }
        return tenant;
    }

    public static AccessRequest workspaceRequest(Scope scope, Action action, String purpose) {
        return new AccessRequest(scope, new ResourceRef(scope.tenantId(), WORKSPACE, scope.workspaceId()), action, purpose);
    }

    @Override
    public AccessDecision check(TrustedActor actor, AccessRequest request) {
        return check(actor, request, false);
    }

    public AccessDecision checkCurrent(TrustedActor actor, AccessRequest request) {
        workspaceAccessSupport.requireTransaction();
        return check(actor, request, true);
    }

    private AccessDecision check(TrustedActor actor, AccessRequest request, boolean currentRead) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(request);
        try {
            var scope = currentRead ? workspaceAccessSupport.snapshotCurrent(actor, request.scope())
                    : workspaceAccessSupport.snapshot(actor, request.scope());
            var definition = actionRegistry.find(request.action());
            if (definition.isEmpty()) return denied(AccessReasonEnum.UNKNOWN_ACTION);
            if (!definition.get().resourceTypes().contains(request.resource().type()))
                return denied(AccessReasonEnum.ACTION_NOT_GRANTED);
            AccessReasonEnum reason;
            if (request.resource().type().equals(WORKSPACE)
                    && request.resource().resourceId().equals(request.scope().workspaceId())) {
                if (Set.of(WORKSPACE_ARCHIVE, WORKSPACE_CLOSE, WORKSPACE_OWNER_TRANSFER).contains(request.action())
                        && (currentRead ? workspaceAccessSupport.isDefaultSpaceCurrent(scope) : workspaceAccessSupport.isDefaultSpace(scope)))
                    return denied(AccessReasonEnum.POLICY_DENIED);
                reason = DefaultRolePermissions.workspace(request.action(), scope);
            } else if (request.resource().type().equals(TENANT)
                    && request.resource().resourceId().equals(request.scope().tenantId())) {
                reason = DefaultRolePermissions.tenant(request.action(), scope.tenant());
            } else {
                // 不能根据角色或 createdBy 为未接入可信描述与 ACL 的资源放行。
                return denied(AccessReasonEnum.ACTION_NOT_GRANTED);
            }
            return reason == AccessReasonEnum.ALLOWED
                    ? new AccessDecision(true, reason, scope.verified().versions(), clock.instant()) : denied(reason);
        } catch (PlatformException e) {
            return denied(switch (e.getCode()) {
                case UNAUTHENTICATED, ACCESS_DENIED -> AccessReasonEnum.SUBJECT_INACTIVE;
                case NOT_FOUND -> AccessReasonEnum.NOT_FOUND;
                case SPACE_CLOSING, SPACE_READ_ONLY -> AccessReasonEnum.LIFECYCLE_DENIED;
                default -> AccessReasonEnum.AUTHORITY_UNAVAILABLE;
            });
        } catch (RuntimeException e) {
            return denied(AccessReasonEnum.AUTHORITY_UNAVAILABLE);
        }
    }

    @Override
    public VerifiedAccess require(TrustedActor actor, AccessRequest request) {
        return require(actor, request, false);
    }

    @Override
    public VerifiedAccess requireInCurrentTransaction(TrustedActor actor, AccessRequest request) {
        return require(actor, request, true);
    }

    private VerifiedAccess require(TrustedActor actor, AccessRequest request, boolean currentRead) {
        // 在 try/catch 外强制验证事务边界，不能将事务外误用当成普通授权拒绝。
        if (currentRead) workspaceAccessSupport.requireTransaction();
        AccessDecision decision = check(actor, request, currentRead);
        if (!decision.allowed()) {
            PlatformErrorCodeEnum code = switch (decision.reason()) {
                case NOT_FOUND -> PlatformErrorCodeEnum.NOT_FOUND;
                case UNKNOWN_ACTION -> PlatformErrorCodeEnum.UNKNOWN_ACTION;
                case POLICY_DENIED -> PlatformErrorCodeEnum.POLICY_DENIED;
                case LIFECYCLE_DENIED -> PlatformErrorCodeEnum.SPACE_READ_ONLY;
                case AUTHORITY_UNAVAILABLE -> PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE;
                default -> PlatformErrorCodeEnum.ACCESS_DENIED;
            };
            throw error(code);
        }
        return new VerifiedAccess(actor.principal(), request, decision.versions(), decision.checkedAt());
    }

    @Override
    public List<AccessDecision> batchCheck(TrustedActor actor, List<AccessRequest> requests) {
        List<AccessRequest> inputs = List.copyOf(requests);
        if (inputs.size() > 100) throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        return inputs.stream().map(request -> check(actor, request)).toList();
    }

    private AccessDecision denied(AccessReasonEnum reason) {
        return new AccessDecision(false, reason, null, clock.instant());
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "authorization-check");
    }
}
