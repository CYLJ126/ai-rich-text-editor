package com.arte.ainew.application.model;

import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelResourceCommands;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.ainew.spi.model.ModelPublicationSource;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.access.ResourceRegistrationService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.application.workspace.WorkspaceMutationEffects;
import com.arte.core.enums.access.ResourceOwnershipTypeEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ResourceOwnership;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.Workspace;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.MODEL;

/**
 * 稳定资源、ACL、不可变版本映射与回执在原物理事务提交，不修改执行身份或预算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public final class DefaultModelResourceService implements ModelResourceService {

    private final ModelResourceStore store;
    private final ModelPublicationSource publications;
    private final PublicTransactionRunner tx;
    private final WorkspaceGovernanceLocks locks;
    private final AuthorizationService authorization;
    private final ResourceRegistrationService registration;
    private final DefaultCommandExecutor commands;
    private final WorkspaceMutationEffects effects;
    private final WorkspaceStore workspaces;
    private final PlatformIdGenerator ids;
    private final Clock clock;
    private final TenantStore tenants;
    private com.arte.ainew.spi.model.ModelSharingStore sharing;
    private java.util.function.Consumer<TrustedActor> tenantCreation = actor -> {
        throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
    };

    public void tenantCreation(java.util.function.Consumer<TrustedActor> permission) {
        this.tenantCreation = permission;
    }

    public void sharing(com.arte.ainew.spi.model.ModelSharingStore sharing) {
        this.sharing = sharing;
    }

    public DefaultModelResourceService(ModelResourceStore store, ModelPublicationSource publications, PublicTransactionRunner tx,
                                       IdentityProvider identity, TenantStore tenants, WorkspaceStore workspaces, TenantMembershipStore tenantMembers,
                                       WorkspaceMembershipStore members, PreferenceStore preferences, AuthorizationService authorization,
                                       ResourceRegistrationService registration, CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit,
                                       PlatformIdGenerator ids, Clock clock) {
        this.store = store;
        this.publications = publications;
        this.tx = tx;
        this.workspaces = workspaces;
        this.authorization = authorization;
        this.registration = registration;
        this.ids = ids;
        this.clock = clock;
        this.tenants = tenants;
        this.locks = new WorkspaceGovernanceLocks(tx, tenants, workspaces, tenantMembers, members, preferences);
        this.commands = new DefaultCommandExecutor(tx, new CurrentPrincipalResolver(identity), receipts, ids, clock);
        this.effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
    }

    private void lock(TrustedActor actor, Scope scope) {
        locks.scopes(actor.principal().subjectId(), List.of(scope));
    }

    private void require(TrustedActor actor, Scope scope, ResourceRef ref, Action action) {
        authorization.requireInCurrentTransaction(actor, new AccessRequest(scope, ref, action, "model-management"));
    }

    private ModelResource current(Scope scope, ResourceRef ref) {
        ModelResource value = store.findCurrent(ref).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (!value.resource().equals(ref) || !scope.tenantId().equals(value.ownership().tenantId())
                || !scope.equals(value.managementScope())) {
            throw error(PlatformErrorCodeEnum.NOT_FOUND);
        }
        return value;
    }

    /**
     * 复用创建门控读取可选归属，不登记资源、回执、预算或凭据。
     */
    @Override
    public List<ModelResourceCommands.ModelKind> creationOptions(TrustedActor actor, Scope scope) {
        return tx.required(() -> {
            lock(actor, scope);
            authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "model-create-options"));
            List<ModelResourceCommands.ModelKind> allowed = new ArrayList<>();
            for (ModelResourceCommands.ModelKind kind : ModelResourceCommands.ModelKind.values()) {
                if (kind == ModelResourceCommands.ModelKind.TENANT && sharing == null) {
                    continue;
                }
                try {
                    requireCreation(actor, scope, kind == ModelResourceCommands.ModelKind.TENANT);
                    allowed.add(kind);
                } catch (PlatformException denied) {
                    if (!Set.of(PlatformErrorCodeEnum.ACCESS_DENIED, PlatformErrorCodeEnum.POLICY_DENIED,
                            PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED).contains(denied.getCode())) {
                        throw denied;
                    }
                }
            }
            return List.copyOf(allowed);
        });
    }

    @Override
    public CommandReceipt<ModelResource> create(TrustedActor actor, ModelResourceCommands.Create command) {
        boolean tenant = command.modelKind() == ModelResourceCommands.ModelKind.TENANT;
        if (tenant && sharing == null) {
            throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
        }
        return create(actor, command, tenant);
    }

    private CommandReceipt<ModelResource> create(TrustedActor actor, ModelResourceCommands.Create command, boolean tenant) {
        return commands.execute(actor, command.meta(), range(command.scope()), "model:create", "model:resource:v2", ModelResource.class, List.of(command.name(), tenant ? "TENANT" : "PERSONAL"),
                () -> lock(actor, command.scope()), value -> {
                    current(command.scope(), value.resource());
                    requireCreation(actor, command.scope(), tenant);
                    require(actor, command.scope(), value.resource(), MODEL_READ);
                }, operation -> {
                    requireCreation(actor, command.scope(), tenant);
                    ModelResource value = new ModelResource(new ResourceRef(command.scope().tenantId(), MODEL, ids.nextId()),
                            new ResourceOwnership(tenant ? ResourceOwnershipTypeEnum.TENANT : ResourceOwnershipTypeEnum.WORKSPACE,
                                    command.scope().tenantId(), tenant ? null : command.scope().workspaceId()), command.name(),
                            actor.principal().subjectId(), ModelResource.State.DRAFT, null, 0, PlatformTimes.businessNow(clock), command.scope());
                    check(store.insert(value));
                    if (tenant) {
                        check(sharing.insert(com.arte.ainew.pojo.model.ModelSharingPolicy.initial(value)));
                        registration.registerTenantCreated(actor, value.descriptor(), command.scope(), new CommandMeta("model-register:" + operation, command.meta().traceId()));
                    } else {
                        registration.registerCreated(actor, value.descriptor(), new CommandMeta("model-register:" + operation, command.meta().traceId()));
                    }
                    effects.append(actor, range(command.scope()), "model:created", operation, 0, Map.of("resourceId", value.resource().resourceId()));
                    return new DefaultCommandExecutor.Result<>(value, 0);
                });
    }

    /**
     * 创建和同键确认均检查当前模型创建资格及租户预设的额外治理资格。
     */
    private void requireCreation(TrustedActor actor, Scope scope, boolean tenant) {
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, MODEL_CREATE, "model-create"));
        if (tenant) {
            authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.tenantRequest(scope.tenantId(), TENANT_POLICY_WRITE, "tenant-model-create"));
            tenantCreation.accept(actor);
        }
    }

    @Override
    public CommandReceipt<ModelResourceVersion> associate(TrustedActor actor, ModelResourceCommands.Associate command) {
        List<String> fields = new ArrayList<>(List.of(command.resource().type().value(), command.resource().resourceId(), Long.toString(command.expectedVersion()), command.binding().id(), command.binding().version(), command.source().name()));
        fields.add(command.catalogRevision() == null ? null : command.catalogRevision().toString());
        return commands.execute(actor, command.meta(), range(command.scope()), "model:associate", "model:version:v1", ModelResourceVersion.class, fields,
                () -> lock(actor, command.scope()), value -> {
                    if (!value.resource().equals(command.resource()) || !value.publication().binding().definition().equals(command.binding())) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                    current(command.scope(), command.resource());
                    require(actor, command.scope(), command.resource(), MODEL_PUBLISH);
                    if (!store.findCurrentBinding(command.scope().tenantId(), command.binding()).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)).equals(value)) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                }, operation -> {
                    ModelResource oldModelResource = current(command.scope(), command.resource());
                    require(actor, command.scope(), command.resource(), MODEL_PUBLISH);
                    // 租户发布必须经过草稿、租户凭据及验证流程，不能借旧目录采用绕过范围与用途门控。
                    if (oldModelResource.ownership().workspaceId() == null) {
                        throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
                    }
                    check(oldModelResource.version() == command.expectedVersion());
                    if (oldModelResource.bindingId() != null && !oldModelResource.bindingId().equals(command.binding().id())) {
                        throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
                    }
                    ModelPublication published = publications.requireCurrent(actor, command.scope(), command.source(), command.catalogRevision(), command.binding());
                    if (published.source() != command.source() || !Objects.equals(published.catalogRevision(), command.catalogRevision())
                            || !published.binding().definition().equals(command.binding())) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                    Optional<ModelResourceVersion> existing = store.findCurrentBinding(command.scope().tenantId(), command.binding());
                    if (existing.isPresent()) {
                        ModelResourceVersion value = existing.get();
                        if (!value.resource().equals(command.resource())) {
                            throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
                        }
                        if (value.publication().source() != published.source() || !value.publication().contentDigest().equals(published.contentDigest())) {
                            throw error(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
                        }
                        return new DefaultCommandExecutor.Result<>(value, oldModelResource.version());
                    }
                    ModelResourceVersion value = new ModelResourceVersion(oldModelResource.resource(), published, actor.principal().subjectId(), PlatformTimes.businessNow(clock));
                    check(store.insertVersion(value));
                    ModelResource next = new ModelResource(oldModelResource.resource(), oldModelResource.ownership(), oldModelResource.name(), oldModelResource.createdBy(), ModelResource.State.PUBLISHED, command.binding().id(),
                            Math.incrementExact(oldModelResource.version()), oldModelResource.createdAt(), oldModelResource.managementScope());
                    check(store.replace(next, oldModelResource.version()));
                    bump(actor, command.scope(), next, operation, "model:version:associated");
                    return new DefaultCommandExecutor.Result<>(value, next.version());
                });
    }

    @Override
    public CommandReceipt<ModelResource> disable(TrustedActor actor, ModelResourceCommands.Disable c) {
        return commands.execute(actor, c.meta(), range(c.scope()), "model:disable", "model:resource:v1", ModelResource.class,
                List.of(c.resource().type().value(), c.resource().resourceId(), Long.toString(c.expectedVersion())), () -> lock(actor, c.scope()), value -> {
                    if (!value.resource().equals(c.resource())) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                    current(c.scope(), c.resource());
                    require(actor, c.scope(), c.resource(), MODEL_DISABLE);
                }, operation -> {
                    ModelResource old = current(c.scope(), c.resource());
                    require(actor, c.scope(), c.resource(), MODEL_DISABLE);
                    check(old.version() == c.expectedVersion());
                    if (old.bindingId() == null) {
                        throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
                    }
                    if (old.state() == ModelResource.State.DISABLED) {
                        return new DefaultCommandExecutor.Result<>(old, old.version());
                    }
                    ModelResource next = new ModelResource(old.resource(), old.ownership(), old.name(), old.createdBy(), ModelResource.State.DISABLED, old.bindingId(), Math.incrementExact(old.version()), old.createdAt(), old.managementScope());
                    check(store.replace(next, old.version()));
                    bump(actor, c.scope(), next, operation, "model:disabled");
                    return new DefaultCommandExecutor.Result<>(next, next.version());
                });
    }

    @Override
    public List<String> actions(TrustedActor actor, Scope scope, ResourceRef resource) {
        return tx.required(() -> {
            lock(actor, scope);
            current(scope, resource);
            require(actor, scope, resource, MODEL_READ);
            List<String> allowed = new ArrayList<>();
            for (Action action : List.of(MODEL_READ, MODEL_EDIT, MODEL_VALIDATE, MODEL_PUBLISH, MODEL_DISABLE, MODEL_USE)) {
                try {
                    require(actor, scope, resource, action);
                    allowed.add(action.value());
                } catch (PlatformException denied) {
                    if (!Set.of(PlatformErrorCodeEnum.ACCESS_DENIED, PlatformErrorCodeEnum.POLICY_DENIED).contains(denied.getCode())) {
                        throw denied;
                    }
                }
            }
            return List.copyOf(allowed);
        });
    }

    @Override
    public ModelResource get(TrustedActor actor, Scope scope, ResourceRef ref) {
        return tx.required(() -> {
            lock(actor, scope);
            ModelResource value = current(scope, ref);
            require(actor, scope, ref, MODEL_READ);
            return value;
        });
    }

    @Override
    public PageResult<ModelResourceVersion> versions(TrustedActor actor, Scope scope, ResourceRef ref, PageQuery query) {
        return tx.required(() -> {
            lock(actor, scope);
            current(scope, ref);
            require(actor, scope, ref, MODEL_READ);
            return store.versions(ref, query);
        });
    }

    private void bump(TrustedActor actor, Scope scope, ModelResource value, String operation, String event) {
        if (value.ownership().workspaceId() == null) {
            com.arte.core.pojo.workspace.Tenant tenant = locks.lockTenant(scope.tenantId()).orElseThrow();
            check(tenants.replace(new com.arte.core.pojo.workspace.Tenant(tenant.id(), tenant.name(), tenant.type(), tenant.personalSubjectId(), tenant.state(), tenant.createdBy(),
                    Math.incrementExact(tenant.version()), Math.incrementExact(tenant.authEpoch()), tenant.createdAt(), PlatformTimes.notBefore(clock, tenant.updatedAt())), tenant.version()));
            effects.append(actor, range(scope), event, operation, value.version(), Map.of("resourceId", value.resource().resourceId()));
            return;
        }
        Workspace w = locks.lockWorkspace(scope).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        check(workspaces.replace(new Workspace(w.scope(), w.name(), w.description(), w.state(), w.createdBy(), Math.incrementExact(w.version()),
                Math.incrementExact(w.authEpoch()), w.createdAt(), PlatformTimes.notBefore(clock, w.updatedAt())), w.version()));
        effects.append(actor, range(scope), event, operation, value.version(), Map.of("resourceId", value.resource().resourceId()));
    }

    private static CommandScope range(Scope s) {
        return new CommandScope(s.tenantId(), s.workspaceId());
    }

    private static void check(boolean value) {
        if (!value) {
            throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
        }
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "model-management");
    }
}
