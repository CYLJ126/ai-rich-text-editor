package com.arte.core.application.access;

import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.workspace.GovernanceLockOrderException;
import com.arte.core.application.workspace.WorkspaceAccessSupport;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.*;
import com.arte.core.pojo.platform.AuthorizationVersions;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.spi.access.ActionRegistry;
import com.arte.core.spi.workspace.TenantUpdatePolicy;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.*;

/**
 * 租户／空间治理与资源 ACL 共用的统一判定
 * <p>
 * 未配置或未登记的业务资源拒绝访问。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
@Slf4j
public final class DefaultAuthorizationService implements AuthorizationService {

    private final WorkspaceAccessSupport workspaceAccessSupport;
    private final ActionRegistry actionRegistry;
    private final Clock clock;
    private final ResourceAclSupport acl;
    private final TenantUpdatePolicy tenantUpdates;

    public DefaultAuthorizationService(WorkspaceAccessSupport support, ActionRegistry registry, Clock clock) {
        this(support, registry, clock, null, List.of(), List.of());
    }

    public DefaultAuthorizationService(WorkspaceAccessSupport support, ActionRegistry registry, Clock clock,
                                       com.arte.core.spi.access.ResourceGrantStore store,
                                       List<com.arte.core.spi.access.ResourceDescriptorProvider> descriptors,
                                       List<com.arte.core.spi.access.DomainAccessConditionProvider> conditions) {
        this(support, registry, clock, store, descriptors, conditions, (actor, tenantId, current) -> false);
    }

    public DefaultAuthorizationService(WorkspaceAccessSupport support, ActionRegistry registry, Clock clock,
                                       com.arte.core.spi.access.ResourceGrantStore store,
                                       List<com.arte.core.spi.access.ResourceDescriptorProvider> descriptors,
                                       List<com.arte.core.spi.access.DomainAccessConditionProvider> conditions,
                                       TenantUpdatePolicy tenantUpdates) {
        this.tenantUpdates = Objects.requireNonNull(tenantUpdates);
        this.workspaceAccessSupport = Objects.requireNonNull(support);
        this.actionRegistry = Objects.requireNonNull(registry);
        this.clock = Objects.requireNonNull(clock);
        this.acl = store == null ? null : new ResourceAclSupport(store, descriptors, conditions, clock);
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
        WorkspaceAccessSupport.TenantSnapshot tenant = currentRead ?
                workspaceAccessSupport.tenantCurrent(actor, tenantId) : workspaceAccessSupport.tenant(actor, tenantId);
        ActionDefinition definition = actionRegistry.find(TENANT_WORKSPACE_CREATE)
                .orElseThrow(() -> error(PlatformErrorCodeEnum.UNKNOWN_ACTION));
        if (!definition.resourceTypes().contains(TENANT) || !DefaultRolePermissions.mayCreateWorkspace(tenant)) {
            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        }
        return tenant;
    }

    public static AccessRequest workspaceRequest(Scope scope, Action action, String purpose) {
        return new AccessRequest(scope, new ResourceRef(scope.tenantId(), WORKSPACE, scope.workspaceId()), action, purpose);
    }

    public static AccessRequest tenantRequest(String tenantId, Action action, String purpose) {
        return new AccessRequest(AccessScope.tenant(tenantId), new ResourceRef(tenantId, TENANT, tenantId), action, purpose);
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
        return check(actor, request, currentRead, null);
    }

    private AccessDecision check(TrustedActor actor, AccessRequest request, boolean currentRead, WorkspaceAccessSupport.Batch batch) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(request);
        try {
            AccessReasonEnum reason;
            AuthorizationVersions versions;
            if (request.resource().type().equals(TENANT)
                    && request.resource().resourceId().equals(request.scope().tenantId())) {
                // 租户治理只验证租户身份、成员、生命周期及政策，不读取附带空间。
                var tenant = batch != null ? batch.tenant(request.scope().tenantId())
                        : currentRead ? workspaceAccessSupport.tenantCurrent(actor, request.scope().tenantId())
                        : workspaceAccessSupport.tenant(actor, request.scope().tenantId());
                var invalidAction = invalidAction(request);
                if (invalidAction != null) return denied(invalidAction);
                if (request.action().equals(TENANT_UPDATE)) {
                    boolean governor = DefaultRolePermissions.tenant(TENANT_POLICY_WRITE, tenant) == AccessReasonEnum.ALLOWED;
                    reason = governor && tenantUpdates.mayUpdate(actor, tenant.tenant().id(), currentRead)
                            ? AccessReasonEnum.ALLOWED : AccessReasonEnum.ACTION_NOT_GRANTED;
                } else {
                    reason = DefaultRolePermissions.tenant(request.action(), tenant);
                }
                versions = new AuthorizationVersions(tenant.tenant().authEpoch(), null,
                        tenant.member().generation(), tenant.member().version(), null, null, null, tenant.tenant().version());
            } else if (request.resource().type().equals(WORKSPACE)
                    && request.resource().resourceId().equals(request.scope().workspaceId())) {
                Scope workspaceScope = request.scope().requireWorkspace();
                var scope = request.action().equals(WORKSPACE_RECONCILE)
                        ? currentRead ? workspaceAccessSupport.reconciliationCurrent(actor, workspaceScope) : workspaceAccessSupport.reconciliation(actor, workspaceScope)
                        : batch != null ? batch.snapshot(workspaceScope)
                        : currentRead ? workspaceAccessSupport.snapshotCurrent(actor, workspaceScope)
                        : workspaceAccessSupport.snapshot(actor, workspaceScope);
                var invalidAction = invalidAction(request);
                if (invalidAction != null) return denied(invalidAction);
                if (Set.of(WORKSPACE_ARCHIVE, WORKSPACE_CLOSE, WORKSPACE_OWNER_TRANSFER).contains(request.action())
                        && (batch != null ? batch.isDefaultSpace(scope)
                        : currentRead ? workspaceAccessSupport.isDefaultSpaceCurrent(scope) : workspaceAccessSupport.isDefaultSpace(scope)))
                    return denied(AccessReasonEnum.POLICY_DENIED);
                reason = DefaultRolePermissions.workspace(request.action(), scope);
                versions = scope.verified().versions();
            } else {
                if (acl == null) return denied(AccessReasonEnum.ACTION_NOT_GRANTED);
                var invalid = invalidAction(request);
                if (invalid != null) return denied(invalid);
                // 业务资源首版在明确的当前使用空间内判断；同租户租户级资源也须有目标空间资格。
                var snapshot = request.resource().type().equals(BUDGET_ACCOUNT) && request.action().equals(BUDGET_ACCOUNT_RECONCILE)
                        ? currentRead ? workspaceAccessSupport.reconciliationCurrent(actor, request.scope().requireWorkspace())
                        : workspaceAccessSupport.reconciliation(actor, request.scope().requireWorkspace())
                        : batch != null ? batch.snapshot(request.scope().requireWorkspace())
                        : currentRead ? workspaceAccessSupport.snapshotCurrent(actor, request.scope().requireWorkspace())
                        : workspaceAccessSupport.snapshot(actor, request.scope().requireWorkspace());
                return acl.check(actor, request, snapshot, currentRead);
            }
            return reason == AccessReasonEnum.ALLOWED
                    ? new AccessDecision(true, reason, versions, clock.instant()) : denied(reason);
        } catch (GovernanceLockOrderException e) {
            // 事务组合方式错误不能被展示型拒绝结果吞掉。
            log.error("Authorization check violated transaction governance lock order, action={}, currentRead={}", request.action().value(), currentRead, e);
            throw e;
        } catch (PlatformException e) {
            return denied(reason(e));
        } catch (RuntimeException e) {
            return denied(AccessReasonEnum.AUTHORITY_UNAVAILABLE);
        }
    }

    private AccessReasonEnum invalidAction(AccessRequest request) {
        var definition = actionRegistry.find(request.action());
        if (definition.isEmpty()) return AccessReasonEnum.UNKNOWN_ACTION;
        return definition.get().resourceTypes().contains(request.resource().type()) ? null : AccessReasonEnum.ACTION_NOT_GRANTED;
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
                case TENANT_INACTIVE -> PlatformErrorCodeEnum.TENANT_INACTIVE;
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
        return batch(actor, inputs).decisions();
    }

    /**
     * 列表复用同一次授权的事实，避免允许后再次逐项读取、验证整套范围。
     */
    public record WorkspaceReadCheck(Scope scope, AccessDecision decision,
                                     WorkspaceAccessSupport.WorkspaceSnapshot snapshot) {
    }

    public List<WorkspaceReadCheck> checkWorkspaceReads(TrustedActor actor, List<Scope> scopes) {
        List<Scope> inputs = List.copyOf(scopes);
        if (inputs.size() > 100) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
        BatchChecks checks = batch(actor, inputs.stream().map(scope -> workspaceRequest(scope, WORKSPACE_READ, "workspace-list")).toList());
        List<WorkspaceReadCheck> results = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            AccessDecision decision = checks.decisions().get(i);
            results.add(new WorkspaceReadCheck(inputs.get(i), decision,
                    decision.allowed() ? checks.facts().snapshot(inputs.get(i)) : null));
        }
        return List.copyOf(results);
    }

    private record BatchChecks(List<AccessDecision> decisions, WorkspaceAccessSupport.Batch facts) {
    }

    private BatchChecks batch(TrustedActor actor, List<AccessRequest> inputs) {
        Objects.requireNonNull(actor);
        Set<String> tenantIds = new HashSet<>();
        Set<Scope> scopes = new HashSet<>();
        boolean includeDefaults = false;
        for (AccessRequest request : inputs) {
            if (!isRootRequest(request) && acl == null) {
                continue;
            }
            tenantIds.add(request.scope().tenantId());
            if (!request.resource().type().equals(TENANT) && request.scope().workspaceId() != null) {
                scopes.add(request.scope().requireWorkspace());
                includeDefaults |= Set.of(WORKSPACE_ARCHIVE, WORKSPACE_CLOSE, WORKSPACE_OWNER_TRANSFER).contains(request.action());
            }
        }
        if (tenantIds.isEmpty()) {
            return new BatchChecks(inputs.stream().map(request -> check(actor, request, false)).toList(), null);
        }
        WorkspaceAccessSupport.Batch facts;
        try {
            facts = workspaceAccessSupport.readBatch(actor, tenantIds, scopes, includeDefaults);
        } catch (PlatformException e) {
            return failedBatch(inputs, reason(e));
        } catch (RuntimeException e) {
            return failedBatch(inputs, AccessReasonEnum.AUTHORITY_UNAVAILABLE);
        }
        return new BatchChecks(inputs.stream().map(request -> check(actor, request, false, facts)).toList(), facts);
    }

    private BatchChecks failedBatch(List<AccessRequest> inputs, AccessReasonEnum failure) {
        return new BatchChecks(inputs.stream().map(request -> denied(isRootRequest(request) || acl != null
                ? failure : AccessReasonEnum.ACTION_NOT_GRANTED)).toList(), null);
    }

    private static boolean isRootRequest(AccessRequest request) {
        return request.resource().type().equals(TENANT) && request.resource().resourceId().equals(request.scope().tenantId())
                || request.resource().type().equals(WORKSPACE) && request.resource().resourceId().equals(request.scope().workspaceId());
    }

    private static AccessReasonEnum reason(PlatformException e) {
        return switch (e.getCode()) {
            case UNAUTHENTICATED, ACCESS_DENIED -> AccessReasonEnum.SUBJECT_INACTIVE;
            case TENANT_INACTIVE -> AccessReasonEnum.TENANT_INACTIVE;
            case NOT_FOUND -> AccessReasonEnum.NOT_FOUND;
            case SPACE_CLOSING, SPACE_READ_ONLY -> AccessReasonEnum.LIFECYCLE_DENIED;
            default -> AccessReasonEnum.AUTHORITY_UNAVAILABLE;
        };
    }

    private AccessDecision denied(AccessReasonEnum reason) {
        return new AccessDecision(false, reason, null, clock.instant());
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "authorization-check");
    }
}
