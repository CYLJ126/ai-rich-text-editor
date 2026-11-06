package com.arte.app.platform.ai;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.model.ReviewedModelPlanChecks;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.control.ControlDefinitionChecks;
import com.arte.ainew.infrastructure.http.ChatCompletionsSseProtocolAdapter;
import com.arte.ainew.infrastructure.http.HttpEgressPolicy;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.model.ModelUsagePreparation;
import com.arte.ainew.pojo.model.ReviewedModelPlan;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.auth.ExecutionPreparationAuthorizationResolver;
import com.arte.ainew.spi.auth.ExecutionReadAuthorizationResolver;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.model.ModelPublicationRuntime;
import com.arte.ainew.spi.model.ReviewedModelPlanSource;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
import com.arte.app.pojo.platform.PlatformAiControlRow;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.application.workspace.WorkspaceMutationEffects;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessDecision;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.PreferenceStore;
import com.arte.core.spi.workspace.WorkspaceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import tools.jackson.core.JacksonException;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

import static com.arte.app.platform.ai.AiControlData.*;
import static com.arte.core.constant.platform.PlatformActions.*;

/**
 * 当前成员／政策授权与固定版本数据库配置组合，不以工作空间 ID 自报身份，不跨范围复用账本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
@Service
@ConditionalOnProperty(prefix = "arte.ai-new", name = "configuration-mode", havingValue = "DATABASE")
@EnableConfigurationProperties(NewAiGenerationProperties.class)
@Import(WorkspaceAiGovernanceConfiguration.class)
public class DatabaseAiControlService implements ExecutionReadAuthorizationResolver, ExecutionPreparationAuthorizationResolver, AiControlConfigurationSource {

    private static final String MANAGE_EGRESS = "aiSecurity:manageEgress";
    private final PlatformAiControlMapper mapper;
    private final PlatformJson json;
    private final RbacIdentityProvider identities;
    private final PublicTransactionRunner tx;
    private final ObjectProvider<WorkspaceWriteGuard> guards;
    private final ObjectProvider<WorkspaceStore> workspaces;
    private final NewAiProperties infrastructure;
    private final Scheduler scheduler;
    private final Clock clock;
    private final WorkspaceMutationEffects effects;
    private final DefaultCommandExecutor commands;
    private final PreferenceStore preferences;
    private final RbacToolPermissionReader catalogPermissions;
    private final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    private ObjectProvider<ModelPublicationRuntime> resourceModels;
    private ObjectProvider<ModelGateway> modelGateways;

    private WorkspaceAiGovernanceService workspaceAi;

    @Autowired
    void workspaceAi(WorkspaceAiGovernanceService workspaceAi) {
        this.workspaceAi = workspaceAi;
    }

    @Autowired
    void modelGateways(ObjectProvider<ModelGateway> modelGateways) {
        this.modelGateways = modelGateways;
    }

    /**
     * 延迟接入个人发布定义，避免改变唯一控制来源及其启动依赖关系。
     */
    @Autowired
    void resourceModels(ObjectProvider<ModelPublicationRuntime> resourceModels) {
        this.resourceModels = resourceModels;
    }

    private NewAiProperties withResources(TrustedActor actor, Scope scope, NewAiProperties base, boolean current) {
        ModelPublicationRuntime runtime = resourceModels == null ? null : resourceModels.getIfAvailable();
        NewAiProperties configuration = runtime == null ? base : runtime.contribute(actor, scope, base, current);
        Set<String> registered = configuration.bindings().stream().map(binding -> binding.definition().id()).collect(java.util.stream.Collectors.toSet());
        List<NewAiProperties.Grant> grants = configuration.grants().stream().map(grant -> new NewAiProperties.Grant(
                grant.subjectName(), grant.subjectId(), grant.principalKind(), grant.tenantId(), grant.workspaceId(), grant.grantRef(),
                grant.enabled(), grant.scopes(), grant.bindingIds().stream().filter(registered::contains).collect(java.util.stream.Collectors.toSet()), grant.budgetRefs())).toList();
        return new NewAiProperties(configuration.enabled(), configuration.dataSourceBean(), configuration.releaseRef(), configuration.persistence(),
                configuration.limits(), grants, configuration.capabilities(), configuration.bindings(), configuration.connections(), configuration.rates(),
                configuration.budgets(), configuration.configurationMode(), configuration.conversationMetadataBackfill());
    }

    /**
     * 取得原写事务内锁定的审核目录，供普通用户发布核对，不授予平台配置修改权。
     */
    public ReviewedModelPlanSource.Snapshot reviewedModelCatalog() {
        tx.requireWritable();
        Published value = publishedCurrent();
        return new ReviewedModelPlanSource.Snapshot(value.catalog().reviewedPlans(), value.catalog().properties(infrastructure, List.of(), List.of()).connections(),
                value.catalog().rates().stream().map(rate -> new RateDefinition(rate.definition(), rate.inputPerMillion(), rate.outputPerMillion(), rate.toolPerCall())).toList(),
                value.version(), infrastructure.releaseRef());
    }

    public DatabaseAiControlService(PlatformAiControlMapper mapper, PlatformJson json, RbacIdentityProvider identities,
                                    @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, ObjectProvider<WorkspaceWriteGuard> guards,
                                    ObjectProvider<WorkspaceStore> workspaces, NewAiProperties infrastructure,
                                    @Qualifier("newAiPersistenceScheduler") Scheduler scheduler, @Qualifier("platformWorkspaceClock") Clock clock,
                                    PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, CommandReceiptStore receipts,
                                    PreferenceStore preferences, @Qualifier("platformAuthorizationService") ObjectProvider<com.arte.core.api.access.AuthorizationService> authorizationService, Environment environment,
                                    @Qualifier("appDataSource") javax.sql.DataSource source) {
        for (String key : List.of("identity", "persistence", "workspace")) {
            if (!environment.getProperty("arte.platform." + key + ".enabled", Boolean.class, false))
                throw new IllegalStateException("Database AI requires public identity, persistence and workspace services");
        }
        if (!"appDataSource".equals(infrastructure.dataSourceBean()))
            throw new IllegalStateException("Database AI requires appDataSource");
        this.mapper = mapper;
        this.json = json;
        this.identities = identities;
        this.tx = tx;
        this.guards = guards;
        this.workspaces = workspaces;
        this.infrastructure = infrastructure;
        this.scheduler = scheduler;
        this.clock = clock;
        this.effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
        this.commands = new DefaultCommandExecutor(tx, new CurrentPrincipalResolver(identities), receipts, ids, clock);
        this.preferences = preferences;
        this.catalogPermissions = new RbacToolPermissionReader(source);
        this.authorizationService = authorizationService;
    }

    private record Published(long version, Catalog catalog) {
    }

    /**
     * 读取精确目录载荷，旧格式或损坏数据明确拒绝，不补空审核方案或覆盖历史。
     *
     * @param version 持久化目录的精确修订版本，用于定位失败数据
     * @param payload 该版本的原始载荷；缺失时报告权威不可用
     * @return 当前结构的完整目录
     */
    private Catalog readCatalog(long version, String payload) {
        if (payload == null) {
            throw unavailable();
        }
        try {
            return json.read(payload, Catalog.class);
        } catch (JacksonException failure) {
            throw new IllegalStateException("AI 审核目录修订 " + version
                    + " 的载荷与当前格式不兼容或已损坏，请检查 reviewedPlans 等必填字段。"
                    + "按已确认的全新数据方案重新建立 AI 相关数据及初始化 schema；"
                    + "不要仅重置 arte_ai_control_head 或给旧载荷补空列表。"
                    + "详见 backend/arte-app/docs/MODEL_CONFIGURATION_M3.md。", failure);
        }
    }

    private Published published(boolean required) {
        return published(required, false);
    }

    private Published published(boolean required, boolean lock) {
        Long version = mapper.head(lock);
        if (version == null) throw unavailable();
        if (version == 0 && !required) return null;
        if (version == 0) throw unavailable();
        String payload = mapper.revision(version);
        if (payload == null) throw unavailable();
        return new Published(version, readCatalog(version, payload));
    }

    /**
     * 取得最新发布版本并共享锁定指针；不可变 revision 内容随后只读。
     */
    private Published publishedCurrent() {
        Long version = mapper.headCurrent();
        if (version == null || version == 0) throw unavailable();
        String payload = mapper.revision(version);
        if (payload == null) throw unavailable();
        return new Published(version, readCatalog(version, payload));
    }

    /**
     * DATABASE 仅使用数据库权威；head=0 返回空目录，绝不回退文件中的模型、连接、费率或授权。
     */
    @Override
    public NewAiProperties definitions() {
        var published = published(false);
        Catalog data = published == null ? Catalog.empty() : published.catalog();
        return nonGenerationDefinitions(data);
    }

    private NewAiProperties nonGenerationDefinitions(Catalog data) {
        Catalog safe = new Catalog(data.capabilities(), data.bindings().stream()
                .filter(binding -> binding.capability().kind() != CapabilityDescriptor.Kind.GENERATION).toList(),
                data.connections(), data.rates(), List.of(), List.of(), data.defaults(), data.disabledConnections(), data.reviewedPlans());
        return safe.properties(infrastructure, List.of(), List.of());
    }

    @Override
    public NewAiProperties.Rate historicalRate(DefinitionRef reference) {
        reference.requireType("rate");
        // 发布修订只允许追加，原费率不可删除或改写；禁止使用尚未导入的文件种子替代历史权威。
        return tx.readOnly(() -> published(true).catalog().rates().stream()
                .filter(rate -> rate.definition().equals(reference)).findFirst()
                .orElseThrow(() -> new com.arte.ainew.application.support.AdmissionException(com.arte.core.enums.ResultCodeEnum.AI_RATE_MISMATCH)));
    }

    private TrustedActor actor(String name) {
        var principal = identities.resolveAuthenticatedName(name).filter(p -> p.status() == PrincipalStatusEnum.ACTIVE)
                .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        return new TrustedActor(principal, clock.instant());
    }

    private <T> T inScope(TrustedActor actor, Scope scope, com.arte.core.pojo.access.Action action, java.util.function.Supplier<T> work) {
        return tx.required(() -> guards.getObject().inCurrentTransaction(actor,
                List.of(DefaultAuthorizationService.workspaceRequest(scope, action, "database-ai")), work));
    }

    @Override
    public Mono<Preparation> prepare(String name, String tenantId, String workspaceId, Set<String> requested) {
        var scopes = Set.copyOf(requested);
        return Mono.fromCallable(() -> tx.required(() -> {
            var value = currentSnapshot(name, null, new Scope(tenantId, workspaceId), scopes, true);
            return new Preparation(value.authorization(), value.configuration(), value.catalogRevision());
        })).subscribeOn(scheduler).onErrorMap(PlatformException.class, failure ->
                failure.getCode() == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE ? failure
                        : new AccessDeniedException("Workspace AI access denied", failure));
    }

    @Override
    public Mono<ExecutionAuthorization> resolve(String name, String tenantId, String workspaceId, Set<String> requested) {
        return Mono.fromCallable(() -> tx.required(() -> currentSnapshot(name, null,
                        new Scope(tenantId, workspaceId), requested, false).authorization()))
                .subscribeOn(scheduler).onErrorMap(PlatformException.class, failure ->
                        failure.getCode() == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE ? failure
                                : new AccessDeniedException("Workspace AI access denied", failure));
    }

    @Override
    public Mono<Snapshot> readSnapshot(String name, String tenantId, String workspaceId,
                                       Set<String> requested, boolean includeConfiguration) {
        var scopes = Set.copyOf(requested);
        return Mono.fromCallable(() -> {
            // 首次稳定身份关联可有独立初始化事务；公共事实与控制面随后只读，不在快照内创建身份。
            var actor = actor(name);
            return tx.readOnly(() -> readSnapshot(actor, name, new Scope(tenantId, workspaceId), scopes, includeConfiguration));
        }).subscribeOn(scheduler).onErrorMap(PlatformException.class, failure ->
                failure.getCode() == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE ? failure
                        : new AccessDeniedException("Workspace AI access denied", failure));
    }

    private Snapshot readSnapshot(TrustedActor actor, String name, Scope scope, Set<String> requested, boolean includeConfiguration) {
        if (!Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION,
                AdmissionAuthorization.READ, AdmissionAuthorization.BUDGET_ADMIN).containsAll(requested)) {
            throw new AccessDeniedException("Workspace AI action denied");
        }
        List<AccessRequest> requests = new ArrayList<>();
        requests.add(DefaultAuthorizationService.workspaceRequest(scope,
                requested.contains(AdmissionAuthorization.INVOKE) ? WORKSPACE_AI_USE : WORKSPACE_READ, "database-ai-read"));
        if (requested.contains(AdmissionAuthorization.BUDGET_ADMIN)) {
            requests.add(DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_POLICY_WRITE, "database-ai-read"));
        }
        int requiredActions = requests.size();
        int policyAction = requested.contains(AdmissionAuthorization.BUDGET_ADMIN) ? 1 : -1;
        int invokeAction = requested.contains(AdmissionAuthorization.INVOKE) ? 0 : -1;
        if (includeConfiguration && policyAction < 0) {
            policyAction = requests.size();
            requests.add(DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_POLICY_WRITE, "database-ai-options"));
        }
        if (includeConfiguration && invokeAction < 0) {
            invokeAction = requests.size();
            requests.add(DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_AI_USE, "database-ai-options"));
        }
        List<AccessDecision> decisions = guardsAuthorization().batchCheck(actor, requests);
        if (decisions.size() != requests.size()) {
            throw unavailable();
        }
        if (decisions.subList(0, requiredActions).stream().anyMatch(d -> !d.allowed())) {
            if (decisions.stream().anyMatch(d -> d.reason() == com.arte.core.enums.access.AccessReasonEnum.AUTHORITY_UNAVAILABLE)) {
                throw unavailable();
            }
            throw new AccessDeniedException("Workspace AI access denied");
        }
        PlatformAiControlRow row = mapper.workspaceRead(scope.tenantId(), scope.workspaceId());
        WorkspaceConfiguration settings = configurationRow(scope, row);
        if (!settings.enabled() && requested.contains(AdmissionAuthorization.INVOKE)) {
            throw new AccessDeniedException("Workspace AI disabled");
        }
        ExecutionAuthorization auth = new ExecutionAuthorization(ExecutionPrincipal.from(actor.principal(), name),
                scope.tenantId(), scope.workspaceId(), requested, "workspace-ai:" + settings.version());
        if (!includeConfiguration) {
            return new Snapshot(auth, null);
        }
        List<NewAiProperties.Budget> definitions = mapper.budgetsRead(scope.tenantId(), scope.workspaceId(), actor.principal().subjectId()).stream()
                .map(b -> json.read(b.getPayload(), NewAiProperties.Budget.class)).toList();
        Set<String> permissions = new HashSet<>(Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.CONVERSATION));
        if (settings.enabled() && decisions.get(invokeAction).allowed()) {
            permissions.add(AdmissionAuthorization.INVOKE);
        }
        if (decisions.get(policyAction).allowed()) {
            permissions.add(AdmissionAuthorization.BUDGET_ADMIN);
        }
        Catalog catalog = published(true).catalog();
        NewAiProperties.Grant grant = workspaceGrant(actor, settings, permissions, name, definitions, catalog);
        List<ResolvedBinding> bindings = catalog.bindings().stream().filter(b -> b.capability().kind() != CapabilityDescriptor.Kind.GENERATION).toList();
        Catalog filtered = new Catalog(catalog.capabilities(), bindings, catalog.connections(), catalog.rates(),
                List.of(), List.of(), catalog.defaults(), catalog.disabledConnections());
        return new Snapshot(auth, withResources(actor, scope, filtered.properties(infrastructure, List.of(grant), definitions), false));
    }

    private ExecutionAuthorization authorization(NewAiProperties.Grant grant, Set<String> requested) {
        return new ExecutionAuthorization(new ExecutionPrincipal(grant.subjectId(), grant.subjectName(), grant.principalKind()),
                grant.tenantId(), grant.workspaceId(), requested, grant.grantRef());
    }

    private boolean allows(TrustedActor actor, Scope scope, com.arte.core.pojo.access.Action action) {
        try {
            guardsAuthorization().requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, action, "ai-eligibility"));
            return true;
        } catch (PlatformException failure) {
            if (failure.getCode() == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE) throw failure;
            return false;
        }
    }

    private final ObjectProvider<com.arte.core.api.access.AuthorizationService> authorizationService;

    private com.arte.core.api.access.AuthorizationService guardsAuthorization() {
        return authorizationService.getObject();
    }

    private static NewAiProperties.Grant renamed(NewAiProperties.Grant grant, String currentName) {
        return new NewAiProperties.Grant(currentName, grant.subjectId(), grant.principalKind(), grant.tenantId(), grant.workspaceId(),
                grant.grantRef(), grant.enabled(), grant.scopes(), grant.bindingIds(), grant.budgetRefs());
    }

    private WorkspaceConfiguration ownConfiguration(Scope scope) {
        return configurationRow(scope, mapper.workspace(scope.tenantId(), scope.workspaceId()));
    }

    private WorkspaceConfiguration configurationRow(Scope scope, PlatformAiControlRow row) {
        if (row == null) {
            throw new AccessDeniedException("Workspace AI is not prepared");
        }
        WorkspaceConfiguration value = json.read(row.getPayload(), WorkspaceConfiguration.class);
        if (!value.scope().equals(scope) || value.version() != row.getVersion()) {
            throw unavailable();
        }
        return value;
    }

    private List<NewAiProperties.Budget> budgets(Scope scope, String subjectId) {
        return mapper.budgets(scope.tenantId(), scope.workspaceId(), subjectId).stream()
                .map(row -> json.read(row.getPayload(), NewAiProperties.Budget.class)).toList();
    }

    private NewAiProperties.Grant workspaceGrant(TrustedActor actor, WorkspaceConfiguration value, Set<String> permissions, String name, List<NewAiProperties.Budget> definitions, Catalog catalog) {
        boolean current = !tx.isReadOnly();
        BudgetRules rules = workspaceAi.rulesForConfiguration(value.scope(), current, value);
        Set<DefinitionRef> rates = rules.rules().stream().filter(rule -> rule.limit().amount().signum() > 0)
                .map(BudgetPreset::rate).collect(java.util.stream.Collectors.toSet());
        Set<String> refs = definitions.stream().filter(budget -> rates.contains(budget.rate()))
                .map(NewAiProperties.Budget::budgetRef).collect(java.util.stream.Collectors.toSet());
        Set<String> bindings = new HashSet<>(value.models().stream().map(model -> model.binding().id()).toList());
        // 工具定义由独立 Tool 治理授权，模型选择不控制工具预算规则或执行绑定。
        catalog.bindings().stream().filter(binding -> binding.capability().kind() != CapabilityDescriptor.Kind.GENERATION)
                .map(binding -> binding.definition().id()).forEach(bindings::add);
        return new NewAiProperties.Grant(name, actor.principal().subjectId(), ExecutionPrincipal.Kind.USER,
                value.scope().tenantId(), value.scope().workspaceId(), "workspace-ai:" + value.version(), true, permissions, bindings, refs);
    }

    /**
     * 当前资格与配置发布版本，同一准备边界取得。
     */
    private record CurrentSnapshot(ExecutionAuthorization authorization, NewAiProperties configuration,
                                   Long catalogRevision) {
    }

    /**
     * 写入／派发边界使用当前事务门控，与展示读取的 MVCC 快照分开。
     */
    private CurrentSnapshot currentSnapshot(String name, String subjectId, Scope scope, Set<String> requested, boolean includeConfiguration) {
        TrustedActor actor = actor(name);
        if (subjectId != null && !actor.principal().subjectId().equals(subjectId)) {
            throw new AccessDeniedException("Stable identity mismatch");
        }
        if (!Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION,
                AdmissionAuthorization.READ, AdmissionAuthorization.BUDGET_ADMIN).containsAll(requested)) {
            throw new AccessDeniedException("Workspace AI action denied");
        }
        if (workspaces.getObject().find(scope).isEmpty()) {
            throw new AccessDeniedException("Workspace AI access denied");
        }
        List<AccessRequest> requests = new ArrayList<>();
        requests.add(DefaultAuthorizationService.workspaceRequest(scope,
                requested.contains(AdmissionAuthorization.INVOKE) ? WORKSPACE_AI_USE : WORKSPACE_READ, "database-ai-write"));
        if (requested.contains(AdmissionAuthorization.BUDGET_ADMIN)) {
            requests.add(DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_POLICY_WRITE, "database-ai-write"));
        }
        return guards.getObject().inCurrentTransaction(actor, requests, () -> {
            WorkspaceConfiguration settings = ownConfiguration(scope);
            if (!settings.enabled() && requested.contains(AdmissionAuthorization.INVOKE)) {
                throw new AccessDeniedException("Workspace AI disabled");
            }
            ExecutionAuthorization auth = new ExecutionAuthorization(ExecutionPrincipal.from(actor.principal(), name),
                    scope.tenantId(), scope.workspaceId(), requested, "workspace-ai:" + settings.version());
            if (!includeConfiguration) {
                return new CurrentSnapshot(auth, null, null);
            }
            List<NewAiProperties.Budget> definitions = budgets(scope, actor.principal().subjectId());
            Published publication = publishedCurrent();
            Catalog catalog = publication.catalog();
            NewAiProperties.Grant grant = workspaceGrant(actor, settings, requested, name, definitions, catalog);
            Catalog filtered = new Catalog(catalog.capabilities(), catalog.bindings().stream()
                    .filter(b -> b.capability().kind() != CapabilityDescriptor.Kind.GENERATION
                    ).toList(), catalog.connections(), catalog.rates(),
                    List.of(), List.of(), catalog.defaults(), catalog.disabledConnections());
            return new CurrentSnapshot(auth, withResources(actor, scope, filtered.properties(infrastructure, List.of(grant), definitions), true), publication.version());
        });
    }

    @Override
    public NewAiProperties configurationInCurrentTransaction(ExecutionContext context) {
        tx.requireActive();
        if (tx.isReadOnly()) {
            throw new IllegalStateException("AI admission requires a writable transaction");
        }
        return snapshot(context).configuration();
    }

    private CurrentSnapshot snapshot(ExecutionContext context) {
        var principal = context.authorization().principal();
        if (principal.kind() != ExecutionPrincipal.Kind.USER)
            throw new AccessDeniedException("Stable identity mismatch");
        try {
            return tx.required(() -> currentSnapshot(principal.subjectName(), principal.subjectId(),
                    new Scope(context.authorization().tenantId(), context.authorization().workspaceId()), context.authorization().scopes(), true));
        } catch (PlatformException failure) {
            if (failure.getCode() == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE) throw failure;
            throw new AccessDeniedException("Workspace AI access denied", failure);
        }
    }

    @Override
    public NewAiProperties.Grant grant(ExecutionContext context) {
        return snapshot(context).configuration().grants().getFirst();
    }

    @Override
    public NewAiProperties configuration(ExecutionContext context) {
        return snapshot(context).configuration();
    }

    @Override
    public PreparedConfiguration prepareConfiguration(ExecutionContext context) {
        var value = snapshot(context);
        return new PreparedConfiguration(value.configuration(), value.catalogRevision());
    }

    @Override
    public PreparedConfiguration revalidate(ExecutionContext context, PreparedConfiguration prepared) {
        // 模型选择与规则独立 CAS；目录或模型版本不变不能证明规则仍批准此账户。
        return prepareConfiguration(context);
    }

    private void validate(Catalog catalog, Preset preset) {
        for (ReviewedModelPlan plan : catalog.reviewedPlans()) {
            if (catalog.connections().stream().noneMatch(connection -> connection.definition().equals(plan.connection()))
                    || catalog.rates().stream().noneMatch(rate -> rate.definition().equals(plan.rate()) && rate.toolPerCall() == null)) {
                throw new IllegalArgumentException("Unresolved reviewed plan dependency");
            }
        }
        if (catalog.bindings().stream().anyMatch(b -> b.definition().version().equalsIgnoreCase("latest"))) {
            throw new IllegalArgumentException("Published models require fixed versions");
        }
        NewAiProperties properties = catalog.properties(infrastructure, catalog.legacyGrants(), catalog.legacyBudgets());
        ControlDefinitionChecks.validate(properties);
        for (DefinitionRef ref : preset.bindings()) {
            ResolvedBinding binding = catalog.bindings().stream().filter(b -> b.definition().equals(ref)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown published model"));
            if (preset.budgets().stream().noneMatch(b -> b.rate().equals(binding.rate()))) {
                throw new IllegalArgumentException("Model requires compatible budget");
            }
        }
        for (BudgetPreset budget : preset.budgets()) {
            NewAiProperties.Rate rate = catalog.rates().stream().filter(r -> r.definition().equals(budget.rate())).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown published rate"));
            if (!rate.currency().equals(budget.limit().currency()) || budget.limit().amount().compareTo(new BigDecimal("1000000000")) > 0) {
                throw new IllegalArgumentException("Invalid budget currency or limit");
            }
        }

    }

    private void prepareBudgets(Scope scope, String subject, Preset preset, boolean update) {
        ExecutionOwner owner = new ExecutionOwner(scope.tenantId(), scope.workspaceId(), subject);
        List<NewAiProperties.Budget> existing = budgets(scope, subject);
        for (BudgetPreset item : preset.budgets()) {
            Optional<NewAiProperties.Budget> old = existing.stream().filter(b -> b.rate().equals(item.rate())).findFirst();
            NewAiProperties.Budget definition = old.orElseGet(() -> new NewAiProperties.Budget("ws-budget-" + hash(scope.tenantId(), scope.workspaceId(), subject, item.rate().id(), item.rate().version()), owner, item.limit(), item.rate()));
            if (!definition.owner().equals(owner)) {
                throw unavailable();
            }
            if (old.isEmpty()) {
                PlatformAiControlRow row = new PlatformAiControlRow();
                row.setBudgetRef(definition.budgetRef());
                row.setTenantId(scope.tenantId());
                row.setWorkspaceId(scope.workspaceId());
                row.setSubjectId(subject);
                row.setPayload(json.write(definition));
                row.setVersion(0L);
                check(mapper.insertBudget(row) == 1);
            }
            String key = hash(definition.budgetRef()), ownerKey = hash(scope.tenantId(), scope.workspaceId(), subject);
            String encoded = mapper.account(key, ownerKey);
            if (encoded == null) {
                if (old.isPresent()) {
                    throw unavailable();
                }
                Money zero = new Money(BigDecimal.ZERO, definition.limit().currency());
                check(mapper.insertAccount(key, ownerKey, codec.encode(new BudgetCommands.Account(definition.budgetRef(), owner, definition.limit(), zero, zero, definition.rate(), 0))) == 1);
            } else {
                BudgetCommands.Account account = codec.decode(encoded, BudgetCommands.Account.class);
                if (!account.owner().equals(owner) || !account.budgetRef().equals(definition.budgetRef()) || !account.rateVersion().equals(item.rate())
                        || !account.limit().equals(definition.limit())) {
                    throw unavailable();
                }
                if (update && !account.limit().equals(item.limit())) {
                    if (!account.limit().currency().equals(item.limit().currency())) {
                        throw new IllegalArgumentException("Budget currency is immutable");
                    }
                    List<PlatformAiControlRow> rows = mapper.budgets(scope.tenantId(), scope.workspaceId(), subject);
                    PlatformAiControlRow row = rows.stream().filter(r -> r.getBudgetRef().equals(definition.budgetRef())).findFirst().orElseThrow();
                    long expected = row.getVersion();
                    row.setVersion(Math.incrementExact(expected));
                    row.setPayload(json.write(new NewAiProperties.Budget(definition.budgetRef(), owner, item.limit(), item.rate())));
                    check(mapper.replaceBudget(row, expected) == 1);
                    check(mapper.replaceAccount(key, ownerKey, codec.encode(new BudgetCommands.Account(account.budgetRef(), owner, item.limit(), account.held(), account.charged(), account.rateVersion(), Math.incrementExact(account.version())))) == 1);
                }
            }
        }
    }

    /**
     * 当前执行空间中，单一精确费率的受信预算规则及账户状态。调用方须已核验模型资格。
     */
    record UsageBudget(ModelUsagePreparation.State state, String reason,
                       List<String> budgetRefs, Money approvedLimit) {
    }

    /**
     * 查询或准备当前用户在目标执行空间内、与所选模型精确费率兼容的预算账户。
     * <p>
     * 仅采用目标空间显式保存的独立批准规则；未准备空间必须先完成独立治理命令。
     * 查询模式不写入业务数据，准备模式只为当前用户和所选费率初始化账户，不修改空间配置或批准规则。
     * 已有账户直接复用，不提高额度或改写预留、已计费用及账户版本；发现账本缺失或归属不一致时拒绝处理。
     * <p>
     * 调用方须已核验模型、精确发布及凭据用途资格，并在同一可写事务中调用；
     * 本方法继续核验空间 AI 使用资格，相关写入与调用方的命令回执、审计及事件共同提交或回滚。
     *
     * @param actor       当前可信操作主体，预算固定归属于该主体，不接受客户端自报其他用户
     * @param scope       实际执行的租户及工作空间，不以模型管理空间代替
     * @param rate        所选模型关联的精确费率引用，批准规则须同时匹配费率标识和版本
     * @param prepare     是否执行准备；false 仅查询当前条件，true 按批准规则复用或初始化账户
     * @param operationId 调用方的服务端命令操作标识，用于关联空间 AI 准备事件及审计；查询模式可为空
     * @return 当前准备状态、原因码、兼容预算引用及批准额度；READY 不保证余额足够或供应商成功
     * @throws IllegalStateException 当前不存在可写事务时拒绝处理
     * @throws PlatformException     当前资格核验失败，或目录、预算定义及账户事实缺失或不一致时拒绝处理
     */
    UsageBudget modelUsageBudget(TrustedActor actor, Scope scope, DefinitionRef rate, boolean prepare, String operationId) {
        tx.requireWritable();
        return inScope(actor, scope, WORKSPACE_AI_USE, () -> {
            // 使用当前锁定的目录版本，未初始化时只返回阻塞状态。
            Long revision = mapper.headCurrent();
            if (revision == null || revision == 0) {
                return new UsageBudget(ModelUsagePreparation.State.BLOCKED, "CATALOG_NOT_INITIALIZED", List.of(), null);
            }
            String payload = mapper.revision(revision);
            if (payload == null) {
                throw unavailable();
            }
            Catalog catalog = readCatalog(revision, payload);
            WorkspaceConfiguration config = mapper.workspace(scope.tenantId(), scope.workspaceId()) == null ? null : ownConfiguration(scope);
            if (config == null) {
                return new UsageBudget(ModelUsagePreparation.State.BLOCKED, "WORKSPACE_AI_NOT_PREPARED", List.of(), null);
            }
            if (!config.enabled()) {
                return new UsageBudget(ModelUsagePreparation.State.BLOCKED, "WORKSPACE_AI_DISABLED", List.of(), null);
            }
            UsageRule checked = usageRule(scope, rate, catalog);
            BudgetPreset rule = checked.rule();
            if (checked.reason() != null) {
                return new UsageBudget(ModelUsagePreparation.State.BLOCKED, checked.reason(), List.of(),
                        rule == null ? null : rule.limit());
            }
            ExecutionOwner owner = new ExecutionOwner(scope.tenantId(), scope.workspaceId(), actor.principal().subjectId());
            String ownerKey = hash(scope.tenantId(), scope.workspaceId(), owner.subjectId());
            // 当前读取本主体的预算定义，校验实际归属并收集匹配费率的记录。
            List<NewAiProperties.Budget> existing = new ArrayList<>();
            for (PlatformAiControlRow row : mapper.budgets(scope.tenantId(), scope.workspaceId(), owner.subjectId())) {
                NewAiProperties.Budget budget = json.read(row.getPayload(), NewAiProperties.Budget.class);
                if (!budget.owner().equals(owner) || !budget.budgetRef().equals(row.getBudgetRef())) {
                    throw unavailable();
                }
                if (budget.rate().equals(rate)) {
                    existing.add(budget);
                }
            }
            if (existing.size() > 1) {
                throw unavailable();
            }
            if (!existing.isEmpty()) {
                // 已有定义却丢失账户属于权威损坏，禁止以“初始化”清零历史费用。
                NewAiProperties.Budget budget = existing.getFirst();
                String encoded = mapper.account(hash(budget.budgetRef()), ownerKey);
                if (encoded == null) {
                    throw unavailable();
                }
                BudgetCommands.Account account = codec.decode(encoded, BudgetCommands.Account.class);
                if (!account.owner().equals(owner) || !account.budgetRef().equals(budget.budgetRef())
                        || !account.rateVersion().equals(rate) || !account.limit().equals(budget.limit())
                        || !account.limit().equals(rule.limit())) {
                    throw unavailable();
                }
                return new UsageBudget(ModelUsagePreparation.State.READY, "READY", List.of(budget.budgetRef()), rule.limit());
            }
            String ref = "ws-budget-" + hash(scope.tenantId(), scope.workspaceId(), owner.subjectId(), rate.id(), rate.version());
            // 有账户却缺失定义同样拒绝，避免为孤立账本重复分配额度。
            if (mapper.account(hash(ref), ownerKey) != null) {
                throw unavailable();
            }
            if (!prepare) {
                return new UsageBudget(ModelUsagePreparation.State.PREPARABLE, "BUDGET_NOT_PREPARED", List.of(), rule.limit());
            }
            // 只按已批准规则初始化当前用户、当前精确费率的账户。
            prepareBudgets(scope, owner.subjectId(), new Preset(true, List.of(), List.of(rule)), false);
            return new UsageBudget(ModelUsagePreparation.State.READY, "READY", List.of(ref), rule.limit());
        });
    }

    private record UsageRule(BudgetPreset rule, String reason) {
    }

    /**
     * 在空间和精确模型均已受权的事务中检查预算规则，不读取个人账户。
     */
    String modelUsageRuleBlocker(Scope scope, DefinitionRef rate) {
        tx.requireWritable();
        CatalogView current = workspaceCatalog();
        return current.catalog() == null ? "CATALOG_NOT_INITIALIZED" : usageRule(scope, rate, current.catalog()).reason();
    }

    private UsageRule usageRule(Scope scope, DefinitionRef rate, Catalog catalog) {
        BudgetRules rules = workspaceAi.rules(scope, true);
        BudgetPreset rule = rules == null ? null : rules.rules().stream().filter(b -> b.rate().equals(rate)).findFirst().orElse(null);
        if (rule == null) {
            return new UsageRule(null, "BUDGET_RULE_NOT_CONFIGURED");
        }
        NewAiProperties.Rate definition = catalog.rates().stream().filter(r -> r.definition().equals(rate)).findFirst()
                .orElseThrow(DatabaseAiControlService::unavailable);
        if (!definition.currency().equals(rule.limit().currency())) {
            throw unavailable();
        }
        return new UsageRule(rule, rule.limit().amount().signum() == 0 ? "BUDGET_RULE_ZERO" : null);
    }

    /**
     * 只读空间配置；首次准备由独立的幂等治理命令显式执行。
     */
    void workspaceSafety() {
        workspaceAi.startupSafety();
    }

    public WorkspaceView get(TrustedActor actor, Scope scope) {
        return workspaceAi.get(actor, scope);
    }

    /**
     * 在已有物理事务中共享锁定最新审核目录，不允许文件配置兜底。
     */
    CatalogView workspaceCatalog() {
        tx.requireWritable();
        Long version = mapper.headCurrent();
        if (version == null) {
            throw unavailable();
        }
        return new CatalogView(version, version == 0 ? null : readCatalog(version, mapper.revision(version)));
    }

    Preset workspaceTemplate(long version) {
        String payload = mapper.revision(version);
        if (payload == null) {
            throw unavailable();
        }
        return readCatalog(version, payload).defaults();
    }

    /**
     * 平台提额权限按原 appDataSource 事务读取当前 RBAC，不采信 HTTP 布尔标志。
     */
    boolean canManageCatalog(TrustedActor actor) {
        String name = identities.currentSubjectName(actor.principal().subjectId()).orElseThrow(DatabaseAiControlService::unavailable);
        Set<String> permissions = catalogPermissions.current(name);
        return permissions.contains("admin") || permissions.contains("aiConfiguration:manage");
    }

    public record ConfigurationCapabilities(boolean available, boolean manageConnections,
                                            boolean managePlans, boolean manageDefaultTemplate, boolean manageEgress) {
    }

    /**
     * Reads platform RBAC only. No workspace, reviewed catalog, or credential material is needed.
     */
    public ConfigurationCapabilities configurationCapabilities(TrustedActor actor) {
        return tx.required(() -> {
            preferences.lockSubject(actor.principal().subjectId());
            var permissions = currentPermissions(actor);
            boolean manage = permissions.contains("admin") || permissions.contains("aiConfiguration:manage");
            return new ConfigurationCapabilities(true, manage, manage, manage, permissions.contains(MANAGE_EGRESS));
        });
    }

    void validateBudgetRules(Catalog catalog, List<BudgetPreset> rules) {
        validate(catalog, new Preset(false, List.of(), rules));
    }

    /**
     * 只调整已经存在且匹配新规则的账户，保留撤销规则的所有历史定义及账本。
     */
    void adjustExistingAccounts(Scope scope, List<BudgetPreset> rules) {
        Map<String, List<BudgetPreset>> subjects = new TreeMap<>();
        for (PlatformAiControlRow row : mapper.workspaceBudgets(scope.tenantId(), scope.workspaceId())) {
            NewAiProperties.Budget definition = json.read(row.getPayload(), NewAiProperties.Budget.class);
            if (!definition.owner().equals(new ExecutionOwner(scope.tenantId(), scope.workspaceId(), row.getSubjectId()))
                    || !definition.budgetRef().equals(row.getBudgetRef())) {
                throw unavailable();
            }
            for (BudgetPreset rule : rules) {
                if (rule.rate().equals(definition.rate())) {
                    subjects.computeIfAbsent(row.getSubjectId(), ignored -> new ArrayList<>()).add(rule);
                }
            }
        }
        for (Map.Entry<String, List<BudgetPreset>> subject : subjects.entrySet()) {
            prepareBudgets(scope, subject.getKey(), new Preset(true, List.of(), subject.getValue()), true);
        }
    }

    private static String canonical(Preset preset) {
        var values = new ArrayList<String>();
        values.add(Boolean.toString(preset.enabled()));
        preset.bindings().stream().sorted(Comparator.comparing(DefinitionRef::id).thenComparing(DefinitionRef::version)).forEach(r -> {
            values.add(r.id());
            values.add(r.version());
        });
        values.add("budgets");
        preset.budgets().stream().sorted(Comparator.comparing(b -> b.rate().id() + ":" + b.rate().version())).forEach(b -> {
            values.add(b.rate().id());
            values.add(b.rate().version());
            values.add(b.limit().currency().toString());
            values.add(b.limit().amount().toPlainString());
        });
        return DefaultCommandExecutor.digest(values);
    }

    public CatalogView catalog() {
        var p = published(false);
        return p == null ? new CatalogView(0, null) : new CatalogView(p.version(), p.catalog());
    }

    /**
     * 显式初始化空目录，不从部署文件复制任何业务定义。
     */
    public CommandReceipt<CatalogView> initializeCatalog(TrustedActor actor, CommandMeta meta) {
        return changeCatalog(actor, meta, 0, "ai:catalog:initialize", "empty-v1", old -> Catalog.empty());
    }

    public record ConnectionSettings(String id, String providerId, java.net.URI endpoint,
                                     long connectTimeoutSeconds, long responseTimeoutSeconds, int maxResponseBytes) {
        public ConnectionSettings {
            com.arte.core.utils.platform.PlatformChecks.id(id, "connectionId");
            if (endpoint == null || endpoint.toASCIIString().length() > 2048
                    || !"deepseek".equals(providerId) || connectTimeoutSeconds < 1 || connectTimeoutSeconds > 60
                    || responseTimeoutSeconds < 1 || responseTimeoutSeconds > 900)
                throw new IllegalArgumentException("Unsupported provider or connection timeout");
            // 第一批只开放程序已支持的供应商；不接收平台 SecretRef 或 API Key。
            new ConnectionDefinition(new DefinitionRef("connection", id, "v1"), providerId,
                    ChatCompletionsSseProtocolAdapter.DEFINITION, endpoint, null, ConnectionDefinition.State.ENABLED,
                    java.time.Duration.ofSeconds(connectTimeoutSeconds), java.time.Duration.ofSeconds(responseTimeoutSeconds), maxResponseBytes);
        }
    }

    public CommandReceipt<CatalogView> publishConnection(TrustedActor actor, CommandMeta meta, long expected, ConnectionSettings settings) {
        return changeCatalog(actor, meta, expected, "ai:connection:publish", settings.toString(), old -> {
            requireConnectionOrigin(settings.endpoint());
            var connections = new ArrayList<>(old.connections());
            // 全局目录修订号保证版本唯一；相同原键重放保留原引用，不再创建新版本。
            connections.add(new ConnectionDefinition(new DefinitionRef("connection", settings.id(), "db-v" + Math.incrementExact(expected)),
                    settings.providerId(), ChatCompletionsSseProtocolAdapter.DEFINITION, settings.endpoint(), null,
                    ConnectionDefinition.State.ENABLED, java.time.Duration.ofSeconds(settings.connectTimeoutSeconds()),
                    java.time.Duration.ofSeconds(settings.responseTimeoutSeconds()), settings.maxResponseBytes()));
            return new Catalog(old.capabilities(), old.bindings(), connections, old.rates(), old.legacyGrants(), old.legacyBudgets(),
                    old.defaults(), old.disabledConnections(), old.reviewedPlans());
        });
    }

    /**
     * 停用是当前状态；历史连接内容与已发生调用的证据保持不变。
     */
    public CommandReceipt<CatalogView> setConnectionEnabled(TrustedActor actor, CommandMeta meta, long expected, String id, boolean enabled) {
        com.arte.core.utils.platform.PlatformChecks.id(id, "connectionId");
        return changeCatalog(actor, meta, expected, "ai:connection:state", id + ":" + enabled, old -> {
            if (old.connections().stream().noneMatch(c -> c.definition().id().equals(id)))
                throw new PlatformException(PlatformErrorCodeEnum.NOT_FOUND, "connection");
            var disabled = new HashSet<>(old.disabledConnections());
            if (enabled) disabled.remove(id);
            else disabled.add(id);
            return new Catalog(old.capabilities(), old.bindings(), old.connections(), old.rates(), old.legacyGrants(), old.legacyBudgets(),
                    old.defaults(), disabled, old.reviewedPlans());
        });
    }

    public CatalogView connectionCatalog(TrustedActor actor) {
        return tx.required(() -> {
            preferences.lockSubject(actor.principal().subjectId());
            requireCatalogManager(actor);
            return catalog();
        });
    }

    public List<String> allowedConnectionOrigins() {
        return egressPolicy(false).origins();
    }

    private EgressPolicyView egressPolicy(boolean lock) {
        var row = mapper.modelEgress(lock);
        if (row == null) throw unavailable();
        return new EgressPolicyView(row.getVersion(), List.of(json.read(row.getPayload(), String[].class)));
    }

    List<URI> modelOrigins(boolean lock) {
        return egressPolicy(lock).origins().stream().map(URI::create).map(HttpEgressPolicy::normalizeOrigin).toList();
    }

    void egressSafety() {
        modelOrigins(false);
    }

    private void requireConnectionOrigin(URI endpoint) {
        if (!HttpEgressPolicy.allows(modelOrigins(true), endpoint))
            throw new IllegalArgumentException("Connection origin is not permitted by platform egress policy");
    }

    public EgressPolicyView getEgressPolicy(TrustedActor actor) {
        return tx.required(() -> {
            preferences.lockSubject(actor.principal().subjectId());
            requireEgressManager(actor);
            return egressPolicy(false);
        });
    }

    public CommandReceipt<EgressPolicyView> updateEgressPolicy(TrustedActor actor, CommandMeta meta, long expected, List<String> origins) {
        if (origins.size() > 64) throw new IllegalArgumentException("Too many model egress origins");
        var canonical = origins.stream().map(URI::create).map(HttpEgressPolicy::normalizeOrigin)
                .map(URI::toASCIIString).sorted().toList();
        if (new HashSet<>(canonical).size() != canonical.size())
            throw new IllegalArgumentException("Duplicate model egress origin");
        var scope = new CommandScope(null, null);
        String payload = json.write(canonical);
        return commands.execute(actor, meta, scope, "ai:egress:update", "ai:egress:view:v1", EgressPolicyView.class,
                List.of(Long.toString(expected), payload), () -> {
                    preferences.lockSubject(actor.principal().subjectId());
                    requireEgressManager(actor);
                }, saved -> requireEgressManager(actor), operation -> {
                    long next = Math.incrementExact(expected);
                    check(mapper.replaceModelEgress(expected, next, payload) == 1);
                    effects.append(actor, scope, "ai:egress:updated", operation, next, Map.of());
                    return new DefaultCommandExecutor.Result<>(new EgressPolicyView(next, canonical), next);
                });
    }

    private void requireEgressManager(TrustedActor actor) {
        if (!currentPermissions(actor).contains(MANAGE_EGRESS))
            throw new PlatformException(PlatformErrorCodeEnum.ACCESS_DENIED, "model-egress");
    }

    private Set<String> currentPermissions(TrustedActor actor) {
        var name = identities.currentSubjectName(actor.principal().subjectId()).orElseThrow(() ->
                new PlatformException(PlatformErrorCodeEnum.UNAUTHENTICATED, "connection"));
        return catalogPermissions.current(name);
    }

    private void requireCatalogManager(TrustedActor actor) {
        var permissions = currentPermissions(actor);
        if (!permissions.contains("admin") && !permissions.contains("aiConfiguration:manage"))
            throw new PlatformException(PlatformErrorCodeEnum.ACCESS_DENIED, "connection");
    }

    /**
     * 发布独立审核方案及必要的新费率，不生成能力／绑定、不授予使用权或初始化账户。
     *
     * @param actor    当前平台审核主体
     * @param meta     原操作键及追踪信息
     * @param expected 当前目录修订，用于同事务 CAS
     * @param settings 受审连接、远端模型、能力及精确十进制价格
     * @return 包含新方案的目录命令回执；方案版本与目录修订分别递增
     */
    public CommandReceipt<CatalogView> publishModelPlan(TrustedActor actor, CommandMeta meta, long expected, ReviewedModelSettings settings) {
        return changeCatalog(actor, meta, expected, "ai:model-plan:publish", json.write(settings), old -> {
            ConnectionDefinition connection = old.connections().stream().filter(c -> c.definition().equals(settings.connection()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown reviewed connection"));
            if (old.disabledConnections().contains(connection.definition().id()) || connection.state() != ConnectionDefinition.State.ENABLED
                    || !"deepseek".equals(connection.providerId()) || !ChatCompletionsSseProtocolAdapter.DEFINITION.equals(connection.protocol())) {
                throw new IllegalArgumentException("Reviewed connection is disabled or unsupported");
            }
            requireConnectionOrigin(connection.endpoint());
            String family = "reviewed-" + settings.id();
            List<ReviewedModelPlan> familyPlans = old.reviewedPlans().stream().filter(plan -> plan.planRef().id().equals(family)).toList();
            ReviewedModelPlan previous = familyPlans.isEmpty() ? null : familyPlans.getLast();
            Set<CapabilityDescriptor.Feature> features = new HashSet<>(Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING));
            if (settings.usageReporting()) {
                features.add(CapabilityDescriptor.Feature.USAGE_REPORTING);
            }
            if (settings.toolCalls()) {
                features.add(CapabilityDescriptor.Feature.TOOL_CALLS);
            }
            List<NewAiProperties.Rate> rates = new ArrayList<>(old.rates());
            Currency currency = Currency.getInstance(settings.currency());
            Money input = new Money(new BigDecimal(settings.inputPerMillion()), currency);
            Money output = new Money(new BigDecimal(settings.outputPerMillion()), currency);
            NewAiProperties.Rate priorRate = previous == null ? null : rates.stream().filter(rate -> rate.definition().equals(previous.rate())).findFirst().orElseThrow();
            boolean unchangedPrice = previous != null && previous.remoteModel().equals(settings.remoteModel())
                    && priorRate.inputPerMillion().equals(input) && priorRate.outputPerMillion().equals(output);
            DefinitionRef rateRef;
            if (unchangedPrice) {
                rateRef = previous.rate();
            } else {
                long rateVersion = rates.stream().filter(rate -> rate.definition().id().equals(family)).count() + 1;
                rateRef = new DefinitionRef("rate", family, "v" + rateVersion);
                rates.add(new NewAiProperties.Rate(rateRef, input, output));
            }
            ReviewedModelPlan plan = new ReviewedModelPlan(new DefinitionRef("model-plan", family, "v" + (familyPlans.size() + 1)),
                    settings.remoteModel(), settings.connection(), settings.remoteModel(), features, settings.contextWindowTokens(), rateRef,
                    PlatformTimes.businessNow(clock), actor.principal().subjectId(), Math.incrementExact(expected));
            ModelGateway gateway = modelGateways == null ? null : modelGateways.getIfAvailable();
            ResolvedBinding metadata = ReviewedModelPlanChecks.metadata(plan);
            if (gateway == null || !gateway.supportsTextChat(metadata, connection)
                    || settings.toolCalls() && !gateway.supportsToolChat(metadata, connection)) {
                throw new IllegalArgumentException("Reviewed capabilities are not supported by the model adapter");
            }
            List<ReviewedModelPlan> plans = new ArrayList<>(old.reviewedPlans());
            plans.add(plan);
            return new Catalog(old.capabilities(), old.bindings(), old.connections(), rates, old.legacyGrants(), old.legacyBudgets(),
                    old.defaults(), old.disabledConnections(), plans);
        });
    }

    /**
     * 独立发布默认 AI 开关和费率批准上限；不接收模型绑定、Grant、凭据或账户。
     *
     * @param actor    当前平台配置主体
     * @param meta     原操作键及追踪信息
     * @param expected 当前目录修订
     * @param enabled  首次空间准备采用的默认开关
     * @param rateCaps 完整精确费率额度上限集合，重复费率拒绝
     * @return 默认模板发布回执；不覆盖已有空间或初始化账户
     */
    public CommandReceipt<CatalogView> updateDefaultAiPolicy(TrustedActor actor, CommandMeta meta, long expected,
                                                             boolean enabled, List<BudgetPreset> rateCaps) {
        Preset defaults = new Preset(enabled, List.of(), rateCaps);
        return changeCatalog(actor, meta, expected, "ai:default-policy:update", canonical(defaults), old ->
                new Catalog(old.capabilities(), old.bindings(), old.connections(), old.rates(), old.legacyGrants(), old.legacyBudgets(),
                        defaults, old.disabledConnections(), old.reviewedPlans()));
    }

    private CommandReceipt<CatalogView> changeCatalog(TrustedActor actor, CommandMeta meta, long expected, String action,
                                                      String intent, java.util.function.UnaryOperator<Catalog> change) {
        if (expected < 0) throw new IllegalArgumentException("Invalid catalog version");
        return commands.execute(actor, meta, new CommandScope(null, null), action, "ai:catalog:view:v1", CatalogView.class,
                List.of(Long.toString(expected), intent), () -> {
                    preferences.lockSubject(actor.principal().subjectId());
                    requireCatalogManager(actor);
                    mapper.head(true);
                }, saved -> requireCatalogManager(actor), operation -> {
                    check(Objects.equals(mapper.head(true), expected));
                    Published before = published(false);
                    Catalog data = change.apply(before == null ? Catalog.empty() : before.catalog());
                    return appendCatalog(actor, operation, expected, data);
                });
    }

    private DefaultCommandExecutor.Result<CatalogView> appendCatalog(TrustedActor actor, String operation, long expected, Catalog data) {
        validate(data, data.defaults());
        long next = Math.incrementExact(expected);
        check(mapper.insertRevision(next, json.write(data), actor.principal().subjectId()) == 1);
        check(mapper.advance(expected, next) == 1);
        effects.append(actor, new CommandScope(null, null), "ai:catalog:published", operation, next, Map.of());
        return new DefaultCommandExecutor.Result<>(new CatalogView(next, data), next);
    }

    public CommandReceipt<CatalogView> publish(TrustedActor actor, CommandMeta meta, long expected, Catalog data) {
        return commands.execute(actor, meta, new CommandScope(null, null), "ai:catalog:publish", "ai:catalog:view:v1", CatalogView.class,
                List.of(Long.toString(expected), catalogDigest(data)),
                () -> {
                    preferences.lockSubject(actor.principal().subjectId());
                    mapper.head(true);
                }, saved -> {
                }, operationId -> {
                    check(Objects.equals(mapper.head(true), expected));
                    validate(data, data.defaults());
                    if (expected > 0) {
                        var old = published(true).catalog();
                        // 模型、能力与费率只追加完整固定版本；重复引用仍由目录索引拒绝，旧契约不能重写或删除。
                        if (!old.legacyGrants().equals(data.legacyGrants()) || !old.legacyBudgets().equals(data.legacyBudgets())
                                || !data.bindings().containsAll(old.bindings()) || !old.connections().equals(data.connections())
                                || !old.disabledConnections().equals(data.disabledConnections())
                                || !data.capabilities().containsAll(old.capabilities()) || !data.rates().containsAll(old.rates())
                                || !data.reviewedPlans().containsAll(old.reviewedPlans()))
                            throw new IllegalArgumentException("Published resource versions and legacy ownership are immutable");
                    }
                    long next = Math.incrementExact(expected);
                    check(mapper.insertRevision(next, json.write(data), actor.principal().subjectId()) == 1);
                    check(mapper.advance(expected, next) == 1);
                    effects.append(actor, new CommandScope(null, null), "ai:catalog:published", operationId, next, Map.of());
                    return new DefaultCommandExecutor.Result<>(new CatalogView(next, data), next);
                });
    }

    private String catalogDigest(Catalog catalog) {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        Object tree = mapper.readValue(json.write(catalog), Object.class);
        return com.arte.ainew.serialization.CanonicalJson.encode(sortedSets(tree));
    }

    private Object sortedSets(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach((key, child) -> {
                Object normalized = sortedSets(child);
                if (Set.of("features", "scopes", "bindingIds", "budgetRefs", "disabledConnections").contains(key.toString()) && normalized instanceof List<?> list) {
                    normalized = list.stream().sorted(Comparator.comparing(com.arte.ainew.serialization.CanonicalJson::encode)).toList();
                }
                result.put(key.toString(), normalized);
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::sortedSets).toList();
        return value;
    }

    public static String hash(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static PlatformException unavailable() {
        return new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "database-ai");
    }

    private static void check(boolean condition) {
        if (!condition) throw new PlatformException(PlatformErrorCodeEnum.VERSION_CONFLICT, "database-ai");
    }
}
