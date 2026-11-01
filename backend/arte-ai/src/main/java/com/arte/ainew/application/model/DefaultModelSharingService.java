package com.arte.ainew.application.model;

import com.arte.ainew.pojo.model.ModelDrafts;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelSharingPolicy;
import com.arte.ainew.spi.model.ModelDraftStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.ainew.spi.model.ModelSharingStore;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.application.workspace.WorkspaceMutationEffects;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.Tenant;
import com.arte.core.pojo.workspace.TenantMembership;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.*;

/**
 * 租户模型范围和租户凭据用途各自授权；不授予模型动作或预算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 19:25 ✾
 */
public final class DefaultModelSharingService {

    private final ModelResourceStore models;
    private final ModelDraftStore drafts;
    private final ModelSharingStore store;
    private final AuthorizationService authorization;
    private final PublicTransactionRunner tx;
    private final WorkspaceGovernanceLocks locks;
    private final DefaultCommandExecutor commands;
    private final WorkspaceMutationEffects effects;
    private final TenantStore tenants;
    private final Clock clock;
    private final TenantMembershipStore tenantMembers;

    public DefaultModelSharingService(ModelResourceStore models, ModelDraftStore drafts, ModelSharingStore store,
                                      AuthorizationService authorization, PublicTransactionRunner tx, IdentityProvider identity, TenantStore tenants,
                                      WorkspaceStore workspaces, TenantMembershipStore tenantMembers, WorkspaceMembershipStore members,
                                      PreferenceStore preferences, CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, Clock clock) {
        this.models = models;
        this.drafts = drafts;
        this.store = store;
        this.authorization = authorization;
        this.tx = tx;
        this.tenants = tenants;
        this.clock = clock;
        this.tenantMembers = tenantMembers;
        locks = new WorkspaceGovernanceLocks(tx, tenants, workspaces, tenantMembers, members, preferences);
        commands = new DefaultCommandExecutor(tx, new CurrentPrincipalResolver(identity), receipts, ids, clock);
        effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
    }

    private ModelResource model(Scope scope, ResourceRef ref) {
        ModelResource model = models.findCurrent(ref).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (model.ownership().workspaceId() != null || !model.managementScope().equals(scope)) {
            throw error(PlatformErrorCodeEnum.NOT_FOUND);
        }
        return model;
    }

    private void require(TrustedActor actor, Scope scope, ResourceRef ref, Action action) {
        model(scope, ref);
        authorization.requireInCurrentTransaction(actor, new AccessRequest(scope, ref, action, "model-sharing"));
    }

    public ModelSharingPolicy get(TrustedActor actor, Scope scope, ResourceRef ref) {
        return tx.required(() -> {
            locks.scopes(actor.principal().subjectId(), List.of(scope));
            require(actor, scope, ref, MODEL_READ);
            return store.find(ref, true).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        });
    }

    public boolean mayManageScopes(TrustedActor actor, Scope scope, ResourceRef ref) {
        return tx.required(() -> {
            locks.scopes(actor.principal().subjectId(), List.of(scope));
            model(scope, ref);
            try {
                authorization.requireInCurrentTransaction(actor, new AccessRequest(scope, ref, RESOURCE_ACCESS_MANAGE, "model-scope-editor"));
                return true;
            } catch (PlatformException denied) {
                if (!Set.of(PlatformErrorCodeEnum.ACCESS_DENIED, PlatformErrorCodeEnum.POLICY_DENIED, PlatformErrorCodeEnum.NOT_FOUND).contains(denied.getCode())) {
                    throw denied;
                }
                return false;
            }
        });
    }

    /**
     * 仅租户成员治理者可读取选择器；不返回成员代次。
     */
    public PageResult<TenantMembership> members(TrustedActor actor, Scope scope, PageQuery query) {
        return tx.required(() -> {
            locks.scopes(actor.principal().subjectId(), List.of(scope));
            authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.tenantRequest(scope.tenantId(), TENANT_MEMBER_MANAGE, "model-member-picker"));
            return tenantMembers.list(scope.tenantId(), query);
        });
    }

    public ModelDrafts.CredentialView credential(TrustedActor actor, Scope scope, ResourceRef ref) {
        return tx.required(() -> {
            locks.scopes(actor.principal().subjectId(), List.of(scope));
            require(actor, scope, ref, MODEL_READ);
            ModelDrafts.Credential c = drafts.credential(ref).orElse(null);
            return new ModelDrafts.CredentialView(ref.resourceId(), c == null ? "MISSING" : c.revoked() ? "REVOKED" : "CONFIGURED",
                    c == null ? 0 : c.version(), c != null && c.responsibleSubjectId().equals(actor.principal().subjectId()), c == null ? "TENANT" : c.kind().name());
        });
    }

    public CommandReceipt<ModelSharingPolicy> scopes(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef ref, long expected, Set<String> workspaceIds) {
        if (workspaceIds.size() > 256) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
        List<Scope> targets = new ArrayList<>();
        targets.add(scope);
        workspaceIds.stream().sorted().map(id -> new Scope(scope.tenantId(), id)).forEach(targets::add);
        return change(actor, meta, scope, ref, expected, "model:use-scopes", new ArrayList<>(workspaceIds.stream().sorted().toList()),
                () -> locks.scopes(actor.principal().subjectId(), targets),
                () -> require(actor, scope, ref, RESOURCE_ACCESS_MANAGE),
                old -> {
                    for (Scope target : targets) {
                        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(target, WORKSPACE_READ, "model-use-scope"));
                    }
                    return new ModelSharingPolicy(ref, scope, workspaceIds, old.credentialVersion(), old.consumers(), old.version() + 1);
                });
    }

    public CommandReceipt<ModelSharingPolicy> consumers(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef ref,
                                                        long expected, long credentialVersion, Set<String> subjects) {
        if (subjects.size() > 256 || credentialVersion < 1) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
        List<String> fields = new ArrayList<>(subjects.stream().sorted().toList());
        fields.add(Long.toString(credentialVersion));
        Runnable requireOwner = () -> {
            require(actor, scope, ref, MODEL_EDIT);
            ModelDrafts.Credential credential = drafts.credential(ref).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
            if (credential.kind() != ModelDrafts.CredentialKind.TENANT || credential.revoked()
                    || !credential.responsibleSubjectId().equals(actor.principal().subjectId())) {
                throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
            }
        };
        return change(actor, meta, scope, ref, expected, "model:credential-consumers", fields, () -> {
            TreeSet<String> owners = new TreeSet<>(subjects);
            owners.add(actor.principal().subjectId());
            owners.forEach(locks::subject);
            locks.scopes(actor.principal().subjectId(), List.of(scope));
        }, requireOwner, old -> {
            if (drafts.credential(ref).orElseThrow().version() != credentialVersion) {
                throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
            }
            Map<String, Long> grants = new TreeMap<>();
            for (String subject : subjects) {
                TenantMembership member = locks.lockTenantMember(scope.tenantId(), subject).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                if (!member.tenantId().equals(scope.tenantId()) || !member.subjectId().equals(subject)) {
                    throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                }
                if (member.state() != MembershipStateEnum.ACTIVE) {
                    throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                }
                grants.put(subject, member.generation());
            }
            return new ModelSharingPolicy(ref, scope, old.workspaceIds(), credentialVersion, grants, old.version() + 1);
        });
    }

    private CommandReceipt<ModelSharingPolicy> change(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef ref, long expected,
                                                      String action, List<String> fields, Runnable lock, Runnable permission, java.util.function.UnaryOperator<ModelSharingPolicy> update) {
        List<String> intent = new ArrayList<>(List.of(ref.resourceId(), Long.toString(expected)));
        intent.addAll(fields);
        return commands.execute(actor, meta, new CommandScope(scope.tenantId(), scope.workspaceId()), action, "model:sharing:v1", ModelSharingPolicy.class, intent,
                lock, saved -> {
                    if (!saved.resource().equals(ref) || !saved.managementScope().equals(scope)) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                    permission.run();
                },
                operation -> {
                    permission.run();
                    ModelSharingPolicy old = store.find(ref, true).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
                    if (old.version() != expected) {
                        throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
                    }
                    ModelSharingPolicy next = update.apply(old);
                    if (!store.replace(next, expected)) {
                        throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
                    }
                    Tenant tenant = locks.lockTenant(scope.tenantId()).orElseThrow();
                    if (!tenants.replace(new Tenant(tenant.id(), tenant.name(), tenant.type(), tenant.personalSubjectId(), tenant.state(), tenant.createdBy(),
                            tenant.version() + 1, tenant.authEpoch() + 1, tenant.createdAt(), PlatformTimes.notBefore(clock, tenant.updatedAt())), tenant.version())) {
                        throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
                    }
                    effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), action, operation, next.version(), Map.of("resourceId", ref.resourceId()));
                    return new DefaultCommandExecutor.Result<>(next, next.version());
                });
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "model-sharing");
    }
}
