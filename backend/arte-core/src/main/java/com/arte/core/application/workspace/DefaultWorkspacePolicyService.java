package com.arte.core.application.workspace;

import com.arte.core.api.workspace.WorkspacePolicyService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.PolicyStore;
import com.arte.core.spi.workspace.TenantStore;
import com.arte.core.spi.workspace.WorkspaceStore;

import java.time.Clock;
import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.TENANT_POLICY_WRITE;
import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_POLICY_WRITE;

/**
 * 获授权治理者的政策入
 * <p>
 * 当前读、CAS、epoch、审计及回执在原事务提交。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
public final class DefaultWorkspacePolicyService implements WorkspacePolicyService {
    private final CurrentPrincipalResolver principals;
    private final WorkspaceAccessSupport access;
    private final DefaultAuthorizationService authorization;
    private final DefaultCommandExecutor commands;
    private final WorkspaceGovernanceLocks locks;
    private final WorkspaceMutationEffects effects;
    private final TenantStore tenants;
    private final WorkspaceStore workspaces;
    private final PolicyStore policies;
    private final PublicTransactionRunner transactions;
    private final Clock clock;

    public DefaultWorkspacePolicyService(CurrentPrincipalResolver principals, WorkspaceAccessSupport access,
                                         DefaultAuthorizationService authorization, DefaultCommandExecutor commands, WorkspaceGovernanceLocks locks,
                                         WorkspaceMutationEffects effects, TenantStore tenants, WorkspaceStore workspaces, PolicyStore policies,
                                         PublicTransactionRunner transactions, Clock clock) {
        this.principals = principals;
        this.access = access;
        this.authorization = authorization;
        this.commands = commands;
        this.locks = locks;
        this.effects = effects;
        this.tenants = tenants;
        this.workspaces = workspaces;
        this.policies = policies;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public TenantPolicy getTenantPolicy(TrustedActor actor, String tenantId) {
        return getTenantPolicyView(actor, tenantId).policy();
    }

    @Override
    public WorkspacePolicy getWorkspacePolicy(TrustedActor actor, Scope scope) {
        return getWorkspacePolicyView(actor, scope).policy();
    }

    @Override
    public WorkspacePolicyViews.TenantPolicyView getTenantPolicyView(TrustedActor actor, String tenantId) {
        String subject = principals.requireUser(actor).subjectId();
        return transactions.required(() -> {
            locks.tenant(subject, tenantId);
            requireTenant(actor, tenantId);
            var snapshot = access.tenantCurrent(actor, tenantId);
            return new WorkspacePolicyViews.TenantPolicyView(snapshot.policy(), snapshot.tenant().version(), snapshot.tenant().authEpoch());
        });
    }

    @Override
    public WorkspacePolicyViews.WorkspacePolicyView getWorkspacePolicyView(TrustedActor actor, Scope scope) {
        String subject = principals.requireUser(actor).subjectId();
        return transactions.required(() -> {
            locks.scopes(subject, List.of(scope));
            requireWorkspace(actor, scope);
            var snapshot = access.snapshotCurrent(actor, scope);
            return new WorkspacePolicyViews.WorkspacePolicyView(snapshot.policy(), snapshot.workspace().version(), snapshot.workspace().authEpoch(),
                    snapshot.tenant().policy().version(), snapshot.tenant().tenant().authEpoch());
        });
    }

    @Override
    public CommandReceipt<TenantPolicy> updateTenantPolicy(TrustedActor actor, TenantCommands.UpdateTenantPolicy command) {
        String subject = principals.requireUser(actor).subjectId();
        var scope = new CommandScope(command.tenantId(), null);
        var fields = fields(command.rules(), command.expectedTenantVersion(), command.expectedPolicyVersion());
        fields.add(Boolean.toString(command.membersMayCreateWorkspace()));
        return commands.execute(actor, command.meta(), scope, "tenant:policy:update", "tenant:policy:v1",
                TenantPolicy.class, fields, () -> locks.tenant(subject, command.tenantId()), value -> {
                    if (!value.tenantId().equals(command.tenantId()))
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, command.meta());
                    requireTenant(actor, command.tenantId());
                }, operationId -> {
                    requireTenant(actor, command.tenantId());
                    var snapshot = access.tenantCurrent(actor, command.tenantId());
                    Tenant current = snapshot.tenant();
                    TenantPolicy policy = snapshot.policy();
                    check(current.version() == command.expectedTenantVersion() && policy.version() == command.expectedPolicyVersion(), command.meta());
                    var updated = new TenantPolicy(current.id(), command.rules(), command.membersMayCreateWorkspace(),
                            policy.policySchemaVersion(), policy.rolePresetVersion(), Math.incrementExact(policy.version()), policy.personalTemplateVersion());
                    var tenant = new Tenant(current.id(), current.name(), current.type(), current.personalSubjectId(), current.state(),
                            current.createdBy(), Math.incrementExact(current.version()), Math.incrementExact(current.authEpoch()), current.createdAt(), clock.instant());
                    check(policies.replaceTenantPolicy(updated, policy.version()), command.meta());
                    check(tenants.replace(tenant, current.version()), command.meta());
                    effects.append(actor, scope, "tenant:policy:updated", operationId, updated.version(),
                            Map.of("tenantEpoch", Long.toString(tenant.authEpoch()), "previousPolicyVersion", Long.toString(policy.version())));
                    return new DefaultCommandExecutor.Result<>(updated, updated.version());
                });
    }

    @Override
    public CommandReceipt<WorkspacePolicy> updateWorkspacePolicy(TrustedActor actor, WorkspaceCommands.UpdateWorkspacePolicy command) {
        String subject = principals.requireUser(actor).subjectId();
        var scope = new CommandScope(command.scope().tenantId(), command.scope().workspaceId());
        return commands.execute(actor, command.meta(), scope, "workspace:policy:update", "workspace:policy:v1",
                WorkspacePolicy.class, fields(command.rules(), command.expectedWorkspaceVersion(), command.expectedPolicyVersion()),
                () -> locks.scopes(subject, List.of(command.scope())), value -> {
                    if (!value.scope().equals(command.scope()))
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, command.meta());
                    requireWorkspace(actor, command.scope());
                }, operationId -> {
                    requireWorkspace(actor, command.scope());
                    var snapshot = access.snapshotCurrent(actor, command.scope());
                    Workspace current = snapshot.workspace();
                    WorkspacePolicy policy = snapshot.policy();
                    check(current.version() == command.expectedWorkspaceVersion() && policy.version() == command.expectedPolicyVersion(), command.meta());
                    if (!within(command.rules(), snapshot.tenant().policy().rules()))
                        throw error(PlatformErrorCodeEnum.POLICY_DENIED, command.meta());
                    var updated = new WorkspacePolicy(current.scope(), command.rules(), policy.policySchemaVersion(),
                            policy.rolePresetVersion(), Math.incrementExact(policy.version()));
                    var workspace = new Workspace(current.scope(), current.name(), current.description(), current.state(), current.createdBy(),
                            Math.incrementExact(current.version()), Math.incrementExact(current.authEpoch()), current.createdAt(), clock.instant());
                    check(policies.replaceWorkspacePolicy(updated, policy.version()), command.meta());
                    check(workspaces.replace(workspace, current.version()), command.meta());
                    effects.append(actor, scope, "workspace:policy:updated", operationId, updated.version(),
                            Map.of("workspaceEpoch", Long.toString(workspace.authEpoch()), "previousPolicyVersion", Long.toString(policy.version())));
                    return new DefaultCommandExecutor.Result<>(updated, updated.version());
                });
    }

    private void requireTenant(TrustedActor actor, String tenant) {
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.tenantRequest(tenant, TENANT_POLICY_WRITE, "policy-governance"));
    }

    private void requireWorkspace(TrustedActor actor, Scope scope) {
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_POLICY_WRITE, "policy-governance"));
    }

    static boolean within(PolicyRules value, PolicyRules ceiling) {
        return ceiling.allowedCreationTypes().containsAll(value.allowedCreationTypes())
                && value.extraCreationRoles().entrySet().stream().allMatch(e -> ceiling.extraCreationRoles().getOrDefault(e.getKey(), Set.of()).containsAll(e.getValue()))
                && (!value.guestAiAllowed() || ceiling.guestAiAllowed())
                && (!value.personalCredentialsAllowed() || ceiling.personalCredentialsAllowed())
                && ceiling.allowedProtocols().containsAll(value.allowedProtocols())
                && ceiling.allowedProviders().containsAll(value.allowedProviders())
                && ceiling.allowedDestinations().containsAll(value.allowedDestinations())
                && ceiling.exportTypes().containsAll(value.exportTypes())
                && ceiling.egressTypes().containsAll(value.egressTypes())
                && ceiling.shareTargetKinds().containsAll(value.shareTargetKinds());
    }

    /**
     * 集合及 Map 按稳定顺序展开；请求 JSON 字段顺序不影响幂等摘要。
     */
    private static List<String> fields(PolicyRules rules, long parentVersion, long policyVersion) {
        var fields = new ArrayList<String>();
        fields.add(Long.toString(parentVersion));
        fields.add(Long.toString(policyVersion));
        add(fields, rules.allowedCreationTypes());
        fields.add(Integer.toString(rules.extraCreationRoles().size()));
        rules.extraCreationRoles().entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().value())).forEach(e -> {
            fields.add(e.getKey().value());
            add(fields, e.getValue());
        });
        fields.add(Boolean.toString(rules.guestAiAllowed()));
        fields.add(Boolean.toString(rules.personalCredentialsAllowed()));
        add(fields, rules.allowedProtocols());
        add(fields, rules.allowedProviders());
        add(fields, rules.allowedDestinations());
        add(fields, rules.exportTypes());
        add(fields, rules.egressTypes());
        add(fields, rules.shareTargetKinds());
        return fields;
    }

    private static void add(List<String> fields, Set<?> values) {
        fields.add(Integer.toString(values.size()));
        values.stream().map(v -> v instanceof com.arte.core.pojo.access.ResourceType t ? t.value() : v.toString()).sorted().forEach(fields::add);
    }

    private static void check(boolean ok, CommandMeta meta) {
        if (!ok) throw error(PlatformErrorCodeEnum.VERSION_CONFLICT, meta);
    }

    private static PlatformException error(PlatformErrorCodeEnum code, CommandMeta meta) {
        return new PlatformException(code, meta.traceId());
    }
}
