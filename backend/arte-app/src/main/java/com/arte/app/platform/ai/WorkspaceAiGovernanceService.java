package com.arte.app.platform.ai;

import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.model.ModelPublications;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelResourceCommands.ModelKind;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.ainew.pojo.model.ModelSelection;
import com.arte.ainew.spi.model.ModelPublicationStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.ainew.spi.model.ModelSelectionSource;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
import com.arte.app.pojo.platform.PlatformAiControlRow;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.workspace.WorkspaceMutationEffects;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.workspace.PolicyRules;
import com.arte.core.pojo.workspace.TenantPolicy;
import com.arte.core.pojo.workspace.Workspace;
import com.arte.core.pojo.workspace.WorkspacePolicy;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.PolicyStore;
import com.arte.core.spi.workspace.WorkspaceStore;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

import static com.arte.app.platform.ai.AiControlData.*;
import static com.arte.core.constant.platform.PlatformActions.*;

/**
 * 目标空间 AI 治理
 * <p>
 * 显式首次准备、实际模型固定版本启用及独立预算规则。
 * 共享公共父范围锁、物理事务、CAS、审计及命令回执，不以页面查询或模型选择创建个人账户。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 15:30 ✾
 */
public final class WorkspaceAiGovernanceService implements ModelSelectionSource {

    private final PlatformAiControlMapper mapper;
    private final PlatformJson json;
    private final PublicTransactionRunner tx;
    private final WorkspaceWriteGuard guards;
    private final WorkspaceStore workspaces;
    private final PolicyStore policies;
    private final AuthorizationService authorization;
    private final ObjectProvider<DatabaseAiControlService> control;
    private final ObjectProvider<ModelPublicationStore> publications;
    private final ObjectProvider<ModelResourceStore> resources;
    private final ObjectProvider<ModelPublicationRuntimeAdapter> runtime;
    private final Clock clock;
    private final DefaultCommandExecutor commands;
    private final WorkspaceMutationEffects effects;
    private final byte[] cursorSecret = new byte[32];

    public WorkspaceAiGovernanceService(PlatformAiControlMapper mapper, PlatformJson json, PublicTransactionRunner tx,
                                        WorkspaceWriteGuard guards, WorkspaceStore workspaces, PolicyStore policies, AuthorizationService authorization,
                                        ObjectProvider<DatabaseAiControlService> control, ObjectProvider<ModelPublicationStore> publications,
                                        ObjectProvider<ModelResourceStore> resources, ObjectProvider<ModelPublicationRuntimeAdapter> runtime,
                                        IdentityProvider identity, CommandReceiptStore receipts, PublicOutboxStore outbox,
                                        AuditSink audit, PlatformIdGenerator ids, Clock clock) {
        this.mapper = mapper;
        this.json = json;
        this.tx = tx;
        this.guards = guards;
        this.workspaces = workspaces;
        this.policies = policies;
        this.authorization = authorization;
        this.control = control;
        this.publications = publications;
        this.resources = resources;
        this.runtime = runtime;
        this.clock = clock;
        commands = new DefaultCommandExecutor(tx, new CurrentPrincipalResolver(identity), receipts, ids, clock);
        effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
        new java.security.SecureRandom().nextBytes(cursorSecret);
    }

    @Override
    public List<ModelSelection> list(Scope scope, boolean current) {
        WorkspaceConfiguration configuration = configuration(scope, current);
        return configuration == null || !configuration.enabled() ? List.of() : configuration.models();
    }

    /**
     * 读取已保存选择；缺少首次准备时返回空，不读取默认模板或创建数据。
     */
    public WorkspaceConfiguration configuration(Scope scope, boolean current) {
        tx.requireActive();
        PlatformAiControlRow row = current ? mapper.workspace(scope.tenantId(), scope.workspaceId())
                : mapper.workspaceRead(scope.tenantId(), scope.workspaceId());
        if (row == null) {
            return null;
        }
        WorkspaceConfiguration result = json.read(row.getPayload(), WorkspaceConfiguration.class);
        if (!result.scope().equals(scope) || result.version() != row.getVersion()) {
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        }
        return result;
    }

    /**
     * 独立规则必须与首次配置同时存在；单侧缺失属于权威损坏。
     */
    public BudgetRules rules(Scope scope, boolean current) {
        return rulesForConfiguration(scope, current, configuration(scope, current));
    }

    public WorkspaceView get(TrustedActor actor, Scope scope) {
        return gate(actor, scope, false, () -> view(actor, scope));
    }

    /**
     * 显式首次准备仅快照默认开关及费率规则，实际模型初始为空，绝不创建账户。
     */
    public CommandReceipt<WorkspaceView> prepare(TrustedActor actor, CommandMeta meta, Scope scope,
                                                 long expectedWorkspaceVersion, long expectedTemplateVersion) {
        return commands.execute(actor, meta, new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:ai:prepare",
                "workspace:ai:view:v2", WorkspaceView.class, List.of(Long.toString(expectedWorkspaceVersion), Long.toString(expectedTemplateVersion)),
                () -> gate(actor, scope, true, () -> true), saved -> gate(actor, scope, true, () -> {
                    checkScope(scope, saved.configuration());
                    return true;
                }), operationId -> {
                    CatalogView catalog = catalog();
                    check(catalog.version() > 0 && catalog.version() == expectedTemplateVersion
                            && workspace(scope).version() == expectedWorkspaceVersion && configuration(scope, true) == null);
                    if (rules(scope, true) != null) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                    Preset defaults = catalog.catalog().defaults();
                    WorkspaceConfiguration configuration = new WorkspaceConfiguration(scope, defaults.enabled(), List.of(), catalog.version(), 0);
                    BudgetRules rules = new BudgetRules(scope, defaults.budgets(), 0);
                    check(mapper.insertWorkspace(row(scope, 0, configuration)) == 1);
                    check(mapper.insertRules(row(scope, 0, rules)) == 1);
                    Workspace changed = changed(scope, expectedWorkspaceVersion);
                    effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:ai:prepared", operationId, 0,
                            Map.of("templateVersion", Long.toString(catalog.version()), "workspaceEpoch", Long.toString(changed.authEpoch())));
                    return new DefaultCommandExecutor.Result<>(view(actor, scope), 0);
                });
    }

    /**
     * 只修改启用开关与精确资源版本，不自动增删规则或初始化账户。
     */
    public CommandReceipt<WorkspaceView> update(TrustedActor actor, CommandMeta meta, Scope scope, long expectedWorkspaceVersion,
                                                long expectedVersion, boolean enabled, List<ModelSelection> models) {
        WorkspaceConfiguration proposed = new WorkspaceConfiguration(scope, enabled, models, 1, 0);
        return commands.execute(actor, meta, new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:ai:update",
                "workspace:ai:view:v2", WorkspaceView.class,
                List.of(Long.toString(expectedWorkspaceVersion), Long.toString(expectedVersion), json.write(proposed)),
                () -> gate(actor, scope, true, () -> true), saved -> gate(actor, scope, true, () -> {
                    checkScope(scope, saved.configuration());
                    return true;
                }), operationId -> {
                    WorkspaceConfiguration old = configuration(scope, true);
                    checkScope(scope, old);
                    rules(scope, true);
                    check(old.version() == expectedVersion && workspace(scope).version() == expectedWorkspaceVersion);
                    Set<ModelSelection> available = new HashSet<>(candidates(actor, scope).stream()
                            .map(model -> new ModelSelection(model.resourceId(), model.binding())).toList());
                    if (!available.containsAll(proposed.models())) {
                        throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                    }
                    WorkspaceConfiguration updated = new WorkspaceConfiguration(scope, enabled, proposed.models(), old.templateVersion(), Math.incrementExact(old.version()));
                    check(mapper.replaceWorkspace(row(scope, updated.version(), updated), old.version()) == 1);
                    Workspace changed = changed(scope, expectedWorkspaceVersion);
                    effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:ai:updated", operationId, updated.version(),
                            Map.of("workspaceEpoch", Long.toString(changed.authEpoch())));
                    return new DefaultCommandExecutor.Result<>(view(actor, scope), updated.version());
                });
    }

    public PolicyConnectionOptions policyConnectionOptions(TrustedActor actor, String tenantId, Scope scope) {
        if (scope != null && !scope.tenantId().equals(tenantId)) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
        AccessRequest request = scope == null
                ? DefaultAuthorizationService.tenantRequest(tenantId, TENANT_POLICY_WRITE, "policy-connection-options")
                : DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_POLICY_WRITE, "policy-connection-options");
        return tx.required(() -> guards.inCurrentTransaction(actor, List.of(request), () -> {
            PolicyRules ceiling = policies.findCurrentTenantPolicy(tenantId)
                    .orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)).rules();
            boolean platformCatalog = scope == null && control.getObject().canManageCatalog(actor);
            Catalog catalog = catalog().catalog();
            Set<String> protocols = new TreeSet<>();
            Set<String> providers = new TreeSet<>();
            Set<String> hosts = new TreeSet<>();
            if (catalog != null) {
                for (ConnectionDefinition connection : catalog.connections()) {
                    String protocol = connection.protocol().id();
                    String provider = connection.providerId();
                    String host = connection.endpoint().getHost();
                    if (host == null || connection.state() != ConnectionDefinition.State.ENABLED
                            || catalog.disabledConnections().contains(connection.definition().id())) {
                        continue;
                    }
                    if (platformCatalog || ceiling.allowedProtocols().contains(protocol)
                            && ceiling.allowedProviders().contains(provider) && ceiling.allowedDestinations().contains(host)) {
                        protocols.add(protocol);
                        providers.add(provider);
                        hosts.add(host);
                    }
                }
            }
            return new PolicyConnectionOptions(tenantId, scope, protocols.stream().limit(256).toList(),
                    providers.stream().limit(256).toList(), hosts.stream().limit(256).toList());
        }));
    }

    public ModelOptions modelOptions(TrustedActor actor, Scope scope, String cursor) {
        return gate(actor, scope, false, () -> {
            List<Model> values = candidates(actor, scope);
            int offset = offset(actor, scope, "models", values, cursor);
            int end = Math.min(offset + 100, values.size());
            return new ModelOptions(values.subList(offset, end), end < values.size() ? cursor(actor, scope, "models", values, end) : null);
        });
    }

    public BudgetRulesView getRules(TrustedActor actor, Scope scope) {
        return gate(actor, scope, true, () -> rulesView(actor, scope));
    }

    /**
     * 独立规则可在零个模型时维护，只调整已有匹配账户的额度并保留全部费用事实。
     */
    public CommandReceipt<BudgetRulesView> updateRules(TrustedActor actor, CommandMeta meta, Scope scope,
                                                       long expectedWorkspaceVersion, long expectedRuleVersion, List<BudgetPreset> rules) {
        BudgetRules proposed = new BudgetRules(scope, rules, Math.incrementExact(expectedRuleVersion));
        return commands.execute(actor, meta, new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:ai:rules:update",
                "workspace:ai:rules:v1", BudgetRulesView.class,
                List.of(Long.toString(expectedWorkspaceVersion), Long.toString(expectedRuleVersion), json.write(proposed)),
                () -> gate(actor, scope, true, () -> {
                    // 先取得当前 RBAC 依据，再读取审核目录指针，沿用发布入口的锁顺序。
                    control.getObject().canManageCatalog(actor);
                    return true;
                }), saved -> gate(actor, scope, true, () -> {
                    if (!saved.scope().equals(scope) || saved.configuration() == null) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                    if (saved.canRaiseBudget() && !control.getObject().canManageCatalog(actor)) {
                        throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                    }
                    return true;
                }), operationId -> {
                    BudgetRules old = rules(scope, true);
                    if (old == null) {
                        throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                    }
                    check(old.version() == expectedRuleVersion && workspace(scope).version() == expectedWorkspaceVersion);
                    CatalogView catalog = catalog();
                    List<BudgetPreset> caps = caps(configuration(scope, true), old, catalog);
                    control.getObject().validateBudgetRules(catalog.catalog(), proposed.rules());
                    for (BudgetPreset rule : proposed.rules()) {
                        BigDecimal cap = caps.stream().filter(item -> item.rate().equals(rule.rate())).map(item -> item.limit().amount())
                                .findFirst().orElse(BigDecimal.ZERO);
                        if (rule.limit().amount().compareTo(cap) > 0 && !control.getObject().canManageCatalog(actor)) {
                            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                        }
                    }
                    check(mapper.replaceRules(row(scope, proposed.version(), proposed), old.version()) == 1);
                    control.getObject().adjustExistingAccounts(scope, proposed.rules());
                    Workspace changed = changed(scope, expectedWorkspaceVersion);
                    effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:ai:rules:updated", operationId, proposed.version(),
                            Map.of("workspaceEpoch", Long.toString(changed.authEpoch())));
                    return new DefaultCommandExecutor.Result<>(rulesView(actor, scope), proposed.version());
                });
    }

    public BudgetRateOptions rateOptions(TrustedActor actor, Scope scope, String cursor) {
        return gate(actor, scope, true, () -> {
            BudgetRules rules = rules(scope, true);
            Set<DefinitionRef> configured = rules == null ? Set.of() : rules.rules().stream().map(BudgetPreset::rate).collect(java.util.stream.Collectors.toSet());
            CatalogView catalog = catalog();
            List<BudgetRateOption> values = catalog.catalog() == null ? List.of() : catalog.catalog().rates().stream()
                    .map(rate -> new BudgetRateOption(rate.definition(), rate.toolPerCall() == null ? "MODEL" : "TOOL", rate.currency().getCurrencyCode(),
                            configured.contains(rate.definition()), amount(rate.inputPerMillion()), amount(rate.outputPerMillion()),
                            amount(rate.toolPerCall()))).sorted(Comparator.comparing(item -> json.write(item.rate()))).toList();
            int offset = offset(actor, scope, "rates", values, cursor);
            int end = Math.min(offset + 100, values.size());
            return new BudgetRateOptions(values.subList(offset, end),
                    end < values.size() ? cursor(actor, scope, "rates", values, end) : null,
                    scope, workspace(scope).version(), rules == null ? null : rules.version(), catalog.version());
        });
    }

    private static String amount(Money price) {
        return price == null ? null : price.amount().toPlainString();
    }

    /**
     * 返回可由空间治理修复的安全阻塞码，不以发布自动代替启用。
     */
    public String selectionBlocker(Scope scope, String resourceId, DefinitionRef binding) {
        rules(scope, true);
        WorkspaceConfiguration configuration = configuration(scope, true);
        if (configuration == null) {
            return "WORKSPACE_AI_NOT_PREPARED";
        }
        if (!configuration.enabled()) {
            return "WORKSPACE_AI_DISABLED";
        }
        Optional<ModelSelection> selected = configuration.models().stream().filter(model -> model.resourceId().equals(resourceId)).findFirst();
        if (selected.isEmpty()) {
            return "MODEL_NOT_ENABLED";
        }
        return selected.get().binding().equals(binding) ? null : "MODEL_VERSION_NOT_ENABLED";
    }

    /**
     * 只暴露允许的补救操作；实际写入仍需原治理或本人准备端点重新授权。
     */
    public List<String> usageActions(TrustedActor actor, Scope scope, String reason) {
        List<String> result = new ArrayList<>();
        if ("BUDGET_NOT_PREPARED".equals(reason)) {
            result.add("PREPARE_OWN_BUDGET");
        }
        if (Set.of("WORKSPACE_AI_NOT_PREPARED", "WORKSPACE_AI_DISABLED", "MODEL_NOT_ENABLED", "MODEL_VERSION_NOT_ENABLED",
                "BUDGET_RULE_NOT_CONFIGURED", "BUDGET_RULE_ZERO").contains(reason) && canManage(actor, scope)) {
            result.add("CONFIGURE_WORKSPACE_AI");
        }
        return List.copyOf(result);
    }

    public void startupSafety() {
        tx.readOnly(mapper::rulesSafety);
    }

    BudgetRules rulesForConfiguration(Scope scope, boolean current, WorkspaceConfiguration configuration) {
        tx.requireActive();
        PlatformAiControlRow row = mapper.rules(scope.tenantId(), scope.workspaceId(), current);
        if (row == null) {
            if (configuration != null) {
                throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            }
            return null;
        }
        BudgetRules result = json.read(row.getPayload(), BudgetRules.class);
        if (!result.scope().equals(scope) || result.version() != row.getVersion() || configuration == null || !configuration.scope().equals(scope)) {
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        }
        return result;
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "workspace-ai");
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
        }
    }

    private <T> T gate(TrustedActor actor, Scope scope, boolean write, Supplier<T> action) {
        return tx.required(() -> guards.inCurrentTransaction(actor, List.of(DefaultAuthorizationService.workspaceRequest(scope,
                write ? WORKSPACE_POLICY_WRITE : WORKSPACE_READ, "workspace-ai")), action));
    }

    private boolean canManage(TrustedActor actor, Scope scope) {
        return allowed(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_POLICY_WRITE, "workspace-ai"));
    }

    private boolean allowed(TrustedActor actor, AccessRequest request) {
        try {
            authorization.requireInCurrentTransaction(actor, request);
            return true;
        } catch (PlatformException failure) {
            if (failure.getCode() != PlatformErrorCodeEnum.ACCESS_DENIED && failure.getCode() != PlatformErrorCodeEnum.NOT_FOUND) {
                throw failure;
            }
            return false;
        }
    }

    private PlatformAiControlRow row(Scope scope, long version, Object payload) {
        PlatformAiControlRow row = new PlatformAiControlRow();
        row.setTenantId(scope.tenantId());
        row.setWorkspaceId(scope.workspaceId());
        row.setVersion(version);
        row.setPayload(json.write(payload));
        return row;
    }

    private CatalogView catalog() {
        return control.getObject().workspaceCatalog();
    }

    private Workspace workspace(Scope scope) {
        return workspaces.lock(scope).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
    }

    private Workspace changed(Scope scope, long expected) {
        Workspace old = workspace(scope);
        check(old.version() == expected);
        Workspace changed = new Workspace(scope, old.name(), old.description(), old.state(), old.createdBy(),
                Math.incrementExact(old.version()), Math.incrementExact(old.authEpoch()), old.createdAt(), clock.instant());
        check(workspaces.replace(changed, old.version()));
        return changed;
    }

    private WorkspaceView view(TrustedActor actor, Scope scope) {
        CatalogView catalog = catalog();
        WorkspaceConfiguration configuration = configuration(scope, true);
        // 读取一侧时同时确认另一侧存在，避免损坏被显示为空配置。
        rules(scope, true);
        return new WorkspaceView("DATABASE", configuration, workspace(scope).version(), canManage(actor, scope),
                catalog.version() == 0, catalog.version(), candidates(actor, scope), catalog.catalog() == null ? null
                : new Preset(catalog.catalog().defaults().enabled(), List.of(), catalog.catalog().defaults().budgets()), List.of());
    }

    private static void checkScope(Scope scope, WorkspaceConfiguration configuration) {
        if (configuration == null || !configuration.scope().equals(scope)) {
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        }
    }

    private List<Model> candidates(TrustedActor actor, Scope scope) {
        if (publications.getIfAvailable() == null || resources.getIfAvailable() == null || runtime.getIfAvailable() == null) {
            return List.of();
        }
        List<ModelSelection> selected = Optional.ofNullable(configuration(scope, true)).map(WorkspaceConfiguration::models).orElse(List.of());
        PolicyRules tenantPolicy = policies.findCurrentTenantPolicy(scope.tenantId()).map(TenantPolicy::rules).orElse(null);
        PolicyRules spacePolicy = policies.findCurrentWorkspacePolicy(scope).map(WorkspacePolicy::rules).orElse(null);
        List<Model> result = new ArrayList<>();
        for (ModelPublications.Release release : publications.getObject().list(scope, true)) {
            if (!allowed(actor, new AccessRequest(scope, release.resource(), MODEL_READ, "workspace-ai-options"))) {
                continue;
            }
            ModelResource model = resources.getObject().findCurrent(release.resource()).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
            ModelResourceVersion version = runtime.getObject().version(release, true);
            ConnectionDefinition connection = version.publication().connection();
            List<String> restrictions = null;
            if (tenantPolicy != null && spacePolicy != null) {
                String protocol = connection.protocol().id();
                String host = connection.endpoint().getHost();
                Set<String> fields = new LinkedHashSet<>(tenantPolicy.connectionRestrictions(protocol, connection.providerId(), host));
                fields.addAll(spacePolicy.connectionRestrictions(protocol, connection.providerId(), host));
                restrictions = List.copyOf(fields);
            }
            // 门控只检查读取与目标范围；配置者无需拥有 MODEL_USE 或消费租户凭据。
            result.add(new Model(release.resource().resourceId(), release.binding(), model.name(), model.ownership().workspaceId() == null
                    ? ModelKind.TENANT : ModelKind.PERSONAL, version.publication().connection().definition(), version.publication().rate().definition(),
                    selected.contains(new ModelSelection(release.resource().resourceId(), release.binding())), restrictions));
        }
        return result.stream().sorted(Comparator.comparing(Model::resourceId).thenComparing(model -> model.binding().version())).toList();
    }

    private List<BudgetPreset> caps(WorkspaceConfiguration configuration, BudgetRules rules, CatalogView current) {
        Map<DefinitionRef, BudgetPreset> result = new LinkedHashMap<>();
        List<BudgetPreset> values = new ArrayList<>();
        if (configuration != null) {
            values.addAll(control.getObject().workspaceTemplate(configuration.templateVersion()).budgets());
        }
        if (current.catalog() != null) {
            values.addAll(current.catalog().defaults().budgets());
        }
        if (rules != null) {
            values.addAll(rules.rules());
        }
        for (BudgetPreset value : values) {
            result.merge(value.rate(), value, (a, b) -> a.limit().amount().compareTo(b.limit().amount()) >= 0 ? a : b);
        }
        return List.copyOf(result.values());
    }

    private BudgetRulesView rulesView(TrustedActor actor, Scope scope) {
        BudgetRules rules = rules(scope, true);
        return new BudgetRulesView(scope, rules, workspace(scope).version(), canManage(actor, scope),
                control.getObject().canManageCatalog(actor), caps(configuration(scope, true), rules, catalog()));
    }

    private String cursor(TrustedActor actor, Scope scope, String kind, Object values, int offset) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(cursorSecret, "HmacSHA256"));
            String input = json.write(List.of(actor.principal().subjectId(), scope, kind, catalog().version(), values, offset));
            return offset + "." + HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private int offset(TrustedActor actor, Scope scope, String kind, List<?> values, String value) {
        if (value == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(value.substring(0, value.indexOf('.')));
            if (offset <= 0 || offset >= values.size() || !MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),
                    cursor(actor, scope, kind, values, offset).getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException();
            }
            return offset;
        } catch (RuntimeException failure) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
    }
}
