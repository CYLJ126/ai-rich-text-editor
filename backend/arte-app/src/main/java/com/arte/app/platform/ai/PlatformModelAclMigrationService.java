package com.arte.app.platform.ai;

import com.arte.ainew.api.model.ModelAclMigrationService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.model.ModelAccessCandidate;
import com.arte.ainew.pojo.model.ModelAclScope;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.ainew.spi.model.ModelAclScopeStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
import com.arte.app.pojo.platform.PlatformAiControlRow;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.application.workspace.WorkspaceMutationEffects;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.CommandScope;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.workspace.Workspace;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_POLICY_WRITE;

/**
 * 显式内部迁移命令
 * <p>
 * 确认同空间精确映射后原子切换，不能删除迁移记录回到旧 Grant。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
public final class PlatformModelAclMigrationService implements ModelAclMigrationService {

    private final PublicTransactionRunner tx;
    private final ModelAclScopeStore scopes;
    private final ModelResourceStore models;
    private final NewAiProperties properties;
    private final PlatformAiControlMapper catalog;
    private final PlatformJson json;
    private final RbacIdentityProvider identities;
    private final RbacToolPermissionReader permissions;
    private final AuthorizationService authorization;
    private final WorkspaceStore workspaces;
    private final WorkspaceGovernanceLocks locks;
    private final DefaultCommandExecutor commands;
    private final WorkspaceMutationEffects effects;
    private final Clock clock;
    private Runnable runtimeReady = () -> {
    };

    /**
     * 宿主在发布迁移命令前还必须确认当前实际资格服务装有同一个门控。
     */
    void runtimeReady(Runnable check) {
        this.runtimeReady = Objects.requireNonNull(check);
    }

    public PlatformModelAclMigrationService(ModelAclScopeStore scopes, ModelResourceStore models, NewAiProperties properties,
                                            PlatformAiControlMapper catalog, PlatformJson json, RbacIdentityProvider identities, RbacToolPermissionReader permissions,
                                            PublicTransactionRunner tx, AuthorizationService authorization, TenantStore tenants, WorkspaceStore workspaces,
                                            TenantMembershipStore tenantMembers, WorkspaceMembershipStore members, PreferenceStore preferences,
                                            CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, Clock clock) {
        this.tx = tx;
        this.scopes = scopes;
        this.models = models;
        this.properties = properties;
        this.catalog = catalog;
        this.json = json;
        this.identities = identities;
        this.permissions = permissions;
        this.authorization = authorization;
        this.workspaces = workspaces;
        this.clock = clock;
        this.locks = new WorkspaceGovernanceLocks(tx, tenants, workspaces, tenantMembers, members, preferences);
        this.commands = new DefaultCommandExecutor(tx, new CurrentPrincipalResolver(identities), receipts, ids, clock);
        this.effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
    }

    @Override
    public Optional<ModelAclScope> get(TrustedActor actor, Scope scope) {
        return tx.required(() -> {
            locks.scopes(actor.principal().subjectId(), List.of(scope));
            requireManager(actor, scope);
            return scopes.findCurrent(scope);
        });
    }

    @Override
    public CommandReceipt<ModelAclScope> migrate(TrustedActor actor, Migrate command) {
        if ("DATABASE".equals(properties.configurationMode()) || command.source() == AiEntitlement.Source.DATABASE) {
            throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
        }
        List<String> fields = new ArrayList<>(List.of(command.source().name(), Long.toString(command.expectedVersion())));
        command.bindings().stream().sorted(Comparator.comparing(DefinitionRef::id).thenComparing(DefinitionRef::version)).forEach(ref -> {
            fields.add(ref.type());
            fields.add(ref.id());
            fields.add(ref.version());
        });
        Scope scope = command.scope();
        CommandScope range = new CommandScope(scope.tenantId(), scope.workspaceId());
        return commands.execute(actor, command.meta(), range, "model:acl:migrate", "model:acl:scope:v1", ModelAclScope.class, fields,
                () -> locks.scopes(actor.principal().subjectId(), List.of(scope)), value -> {
                    requireManager(actor, scope);
                    if (!value.scope().equals(scope) || value.configurationSource() != command.source()
                            || !value.bindings().equals(command.bindings()) || scopes.findCurrent(scope).isEmpty()) {
                        throw PlatformModelAclAccess.unavailable();
                    }
                }, operation -> {
                    requireManager(actor, scope);
                    runtimeReady.run();
                    if (!properties.configurationMode().equals(command.source().name())) {
                        throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
                    }
                    Optional<ModelAclScope> old = scopes.findCurrent(scope);
                    if (old.map(ModelAclScope::version).orElse(0L) != command.expectedVersion()) {
                        throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
                    }
                    if (old.isPresent() && old.get().configurationSource() != command.source()) {
                        throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
                    }
                    NewAiProperties definitions = definitions(scope, command);
                    for (DefinitionRef ref : command.bindings()) {
                        ResolvedBinding binding = definitions.bindings().stream().filter(value -> value.definition().equals(ref)).findFirst()
                                .orElseThrow(() -> error(PlatformErrorCodeEnum.INVALID_ARGUMENT));
                        ConnectionDefinition connection = definitions.connections().stream().filter(value -> value.definition().equals(binding.connection())).findFirst().orElseThrow();
                        NewAiProperties.Rate rate = definitions.rates().stream().filter(value -> value.definition().equals(binding.rate())).findFirst().orElseThrow();
                        ModelResourceVersion version = models.findCurrentBinding(scope.tenantId(), ref).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                        ModelResource resource = models.findCurrent(version.resource()).orElseThrow(PlatformModelAclAccess::unavailable);
                        if (resource.state() != ModelResource.State.PUBLISHED || connection.state() != ConnectionDefinition.State.ENABLED) {
                            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
                        }
                        PlatformModelAclAccess.validate(scope, new ModelAccessCandidate(binding, connection,
                                new RateDefinition(rate.definition(), rate.inputPerMillion(), rate.outputPerMillion(), rate.toolPerCall()), definitions.releaseRef()), version, resource);
                    }
                    ModelAclScope next = new ModelAclScope(scope, command.source(), command.bindings(), Math.incrementExact(command.expectedVersion()), actor.principal().subjectId(), PlatformTimes.businessNow(clock));
                    boolean saved = old.isEmpty() ? scopes.insert(next) : scopes.replace(next, command.expectedVersion());
                    if (!saved) {
                        throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
                    }
                    Workspace workspace = locks.lockWorkspace(scope).orElseThrow(PlatformModelAclAccess::unavailable);
                    if (!workspaces.replace(new Workspace(workspace.scope(), workspace.name(), workspace.description(), workspace.state(), workspace.createdBy(),
                            Math.incrementExact(workspace.version()), Math.incrementExact(workspace.authEpoch()), workspace.createdAt(), PlatformTimes.notBefore(clock, workspace.updatedAt())), workspace.version())) {
                        throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
                    }
                    effects.append(actor, range, "model:acl:migrated", operation, next.version(), Map.of("source", command.source().name(), "bindingCount", Integer.toString(command.bindings().size())));
                    return new DefaultCommandExecutor.Result<>(next, next.version());
                });
    }

    private void requireManager(TrustedActor actor, Scope scope) {
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_POLICY_WRITE, "model-acl-migration"));
        String name = identities.currentSubjectName(actor.principal().subjectId()).orElseThrow(PlatformModelAclAccess::unavailable);
        Set<String> current = permissions.current(name);
        if (!current.contains("admin") && !current.contains("aiConfiguration:manage")) {
            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        }
    }

    private NewAiProperties definitions(Scope scope, Migrate command) {
        if (command.source() == AiEntitlement.Source.FIXED) {
            return properties;
        }
        PlatformAiControlRow row = catalog.workspace(scope.tenantId(), scope.workspaceId());
        if (row == null) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
        AiControlData.WorkspaceConfiguration configuration = json.read(row.getPayload(), AiControlData.WorkspaceConfiguration.class);
        if (!configuration.scope().equals(scope) || !configuration.preset().enabled() || !configuration.preset().bindings().containsAll(command.bindings())) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
        Long revision = catalog.headCurrent();
        if (revision == null || revision <= 0) {
            throw PlatformModelAclAccess.unavailable();
        }
        String payload = catalog.revision(revision);
        if (payload == null) {
            throw PlatformModelAclAccess.unavailable();
        }
        return json.read(payload, AiControlData.Catalog.class).properties(properties, List.of(), List.of());
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "model-acl-migration");
    }
}
