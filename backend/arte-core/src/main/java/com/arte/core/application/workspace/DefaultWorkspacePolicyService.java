package com.arte.core.application.workspace;

import com.arte.core.api.workspace.WorkspacePolicyService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.ResourceType;
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
            return new WorkspacePolicyViews.TenantPolicyView(snapshot.policy(), snapshot.tenant().version(), snapshot.tenant().authEpoch(),
                    snapshot.tenant().name(), snapshot.tenant().type());
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
                    snapshot.tenant().policy().version(), snapshot.tenant().tenant().authEpoch(),
                    snapshot.tenant().tenant().name(), snapshot.tenant().tenant().type(),
                    effect(snapshot.policy().rules(), snapshot.tenant().policy().rules()));
        });
    }

    @Override
    public WorkspacePolicyViews.PolicyPreview previewWorkspacePolicy(TrustedActor actor, Scope scope, long workspaceVersion,
                                                                     long workspacePolicyVersion, long tenantPolicyVersion,
                                                                     PolicyRules rules) {
        String subject = principals.requireUser(actor).subjectId();
        return transactions.required(() -> {
            locks.scopes(subject, List.of(scope));
            requireWorkspace(actor, scope);
            WorkspaceAccessSupport.WorkspaceSnapshot snapshot = access.snapshotCurrent(actor, scope);
            if (snapshot.workspace().version() != workspaceVersion || snapshot.policy().version() != workspacePolicyVersion
                    || snapshot.tenant().policy().version() != tenantPolicyVersion) {
                throw new PlatformException(PlatformErrorCodeEnum.VERSION_CONFLICT, "policy-preview");
            }
            validateNewDestinations(rules, snapshot.policy().rules());
            return new WorkspacePolicyViews.PolicyPreview(scope, workspaceVersion, workspacePolicyVersion, tenantPolicyVersion,
                    effect(rules, snapshot.tenant().policy().rules()));
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
                    validateNewDestinations(command.rules(), policy.rules());
                    var updated = new TenantPolicy(current.id(), command.rules(), command.membersMayCreateWorkspace(),
                            policy.policySchemaVersion(), policy.rolePresetVersion(), Math.incrementExact(policy.version()), policy.personalTemplateVersion());
                    var tenant = new Tenant(current.id(), current.name(), current.type(), current.personalSubjectId(), current.state(),
                            current.createdBy(), Math.incrementExact(current.version()), Math.incrementExact(current.authEpoch()), current.createdAt(), PlatformTimes.notBefore(clock, current.updatedAt()));
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
        List<String> fields = fields(command.rules(), command.expectedWorkspaceVersion(), command.expectedPolicyVersion());
        if (command.expectedTenantPolicyVersion() != null) {
            fields.add("tenant-policy-version");
            fields.add(Long.toString(command.expectedTenantPolicyVersion()));
        }
        return commands.execute(actor, command.meta(), scope, "workspace:policy:update", "workspace:policy:v1",
                WorkspacePolicy.class, fields,
                () -> locks.scopes(subject, List.of(command.scope())), value -> {
                    if (!value.scope().equals(command.scope()))
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, command.meta());
                    requireWorkspace(actor, command.scope());
                }, operationId -> {
                    requireWorkspace(actor, command.scope());
                    var snapshot = access.snapshotCurrent(actor, command.scope());
                    Workspace current = snapshot.workspace();
                    WorkspacePolicy policy = snapshot.policy();
                    TenantPolicy ceiling = snapshot.tenant().policy();
                    check(current.version() == command.expectedWorkspaceVersion() && policy.version() == command.expectedPolicyVersion()
                            && (command.expectedTenantPolicyVersion() == null || ceiling.version() == command.expectedTenantPolicyVersion()), command.meta());
                    validateNewDestinations(command.rules(), policy.rules());
                    if (!within(command.rules(), ceiling.rules())) {
                        throw error(PlatformErrorCodeEnum.POLICY_DENIED, command.meta());
                    }
                    var updated = new WorkspacePolicy(current.scope(), command.rules(), policy.policySchemaVersion(),
                            policy.rolePresetVersion(), Math.incrementExact(policy.version()));
                    var workspace = new Workspace(current.scope(), current.name(), current.description(), current.state(), current.createdBy(),
                            Math.incrementExact(current.version()), Math.incrementExact(current.authEpoch()), current.createdAt(), PlatformTimes.notBefore(clock, current.updatedAt()));
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
        return effect(value, ceiling).limitedFields().isEmpty();
    }

    private static WorkspacePolicyViews.PolicyEffect effect(PolicyRules value, PolicyRules ceiling) {
        List<String> limited = new ArrayList<>();
        Set<ResourceType> creation = bounded("allowedCreationTypes",
                value.allowedCreationTypes(), ceiling.allowedCreationTypes(), limited);
        Map<ResourceType, Set<WorkspaceRoleEnum>> roles = new HashMap<>();
        value.extraCreationRoles().forEach((type, requested) -> {
            Set<WorkspaceRoleEnum> allowed = intersection(requested, ceiling.extraCreationRoles().getOrDefault(type, Set.of()));
            if (!allowed.equals(requested) && !limited.contains("extraCreationRoles")) {
                limited.add("extraCreationRoles");
            }
            if (creation.contains(type) && !allowed.isEmpty()) {
                roles.put(type, allowed);
            }
        });
        PolicyRules effective = new PolicyRules(creation, roles,
                bounded("guestAiAllowed", value.guestAiAllowed(), ceiling.guestAiAllowed(), limited),
                bounded("personalCredentialsAllowed", value.personalCredentialsAllowed(), ceiling.personalCredentialsAllowed(), limited),
                bounded("allowedProtocols", value.allowedProtocols(), ceiling.allowedProtocols(), limited),
                bounded("allowedProviders", value.allowedProviders(), ceiling.allowedProviders(), limited),
                bounded("allowedDestinations", value.allowedDestinations(), ceiling.allowedDestinations(), limited),
                bounded("exportTypes", value.exportTypes(), ceiling.exportTypes(), limited),
                bounded("egressTypes", value.egressTypes(), ceiling.egressTypes(), limited),
                bounded("shareTargetKinds", value.shareTargetKinds(), ceiling.shareTargetKinds(), limited));
        return new WorkspacePolicyViews.PolicyEffect(effective, List.copyOf(limited));
    }

    private static <T> Set<T> intersection(Set<T> value, Set<T> ceiling) {
        Set<T> result = new HashSet<>(value);
        result.retainAll(ceiling);
        return result;
    }

    private static <T> Set<T> bounded(String field, Set<T> value, Set<T> ceiling, List<String> limited) {
        Set<T> result = intersection(value, ceiling);
        if (!result.equals(value)) {
            limited.add(field);
        }
        return result;
    }

    private static boolean bounded(String field, boolean value, boolean ceiling, List<String> limited) {
        if (value && !ceiling) {
            limited.add(field);
        }
        return value && ceiling;
    }

    private static void validateNewDestinations(PolicyRules value, PolicyRules saved) {
        for (String host : value.allowedDestinations()) {
            if (saved.allowedDestinations().contains(host)) {
                continue;
            }
            // 与连接端 URI#getHost 的表示一致，只接受主机，不接受 URL、端口或路径。
            try {
                if (host.equals(java.net.URI.create("https://" + host).getHost())) {
                    continue;
                }
            } catch (IllegalArgumentException ignored) {
                // 返回统一的安全参数错误。
            }
            throw new PlatformException(PlatformErrorCodeEnum.INVALID_ARGUMENT, "policy-destination");
        }
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
