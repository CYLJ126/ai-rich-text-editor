package com.arte.app.platform.ai;

import com.arte.ainew.application.auth.AdmissionAuthorization;
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
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.model.ModelUsagePreparation;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.auth.ExecutionPreparationAuthorizationResolver;
import com.arte.ainew.spi.auth.ExecutionReadAuthorizationResolver;
import com.arte.ainew.spi.model.PersonalModelRuntime;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
import com.arte.app.pojo.platform.PlatformAiControlRow;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.workspace.WorkspaceMutationEffects;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.exception.platform.PlatformException;
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
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.math.BigDecimal;
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
public class DatabaseAiControlService implements ExecutionReadAuthorizationResolver, ExecutionPreparationAuthorizationResolver, AiControlConfigurationSource {

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
    private final HttpEgressPolicy connectionEgress;
    private final List<String> connectionOrigins;
    private final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    private ObjectProvider<PersonalModelRuntime> personalModels;

    /**
     * 延迟接入个人发布定义，避免改变唯一控制来源及其启动依赖关系。
     */
    @Autowired
    void personalModels(ObjectProvider<PersonalModelRuntime> personalModels) {
        this.personalModels = personalModels;
    }

    private NewAiProperties withPersonal(TrustedActor actor, Scope scope, NewAiProperties base, boolean current) {
        PersonalModelRuntime runtime = personalModels == null ? null : personalModels.getIfAvailable();
        return runtime == null ? base : runtime.contribute(actor, scope, base, current);
    }

    /**
     * 取得原写事务内锁定的审核目录，供普通用户发布核对，不授予平台配置修改权。
     */
    public PreparedConfiguration reviewedModelCatalog() {
        tx.requireWritable();
        Published value = publishedCurrent();
        return new PreparedConfiguration(value.catalog().properties(infrastructure, List.of(), List.of()), value.version());
    }

    /**
     * 查询当前主体原空间的预算配置；不初始化空间或账户。
     */
    public NewAiProperties personalModelConfiguration(TrustedActor actor, Scope scope) {
        tx.requireWritable();
        String name = identities.currentSubjectName(actor.principal().subjectId()).orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        return currentSnapshot(name, actor.principal().subjectId(), scope, Set.of(AdmissionAuthorization.INVOKE), true).configuration();
    }

    public DatabaseAiControlService(PlatformAiControlMapper mapper, PlatformJson json, RbacIdentityProvider identities,
                                    @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, ObjectProvider<WorkspaceWriteGuard> guards,
                                    ObjectProvider<WorkspaceStore> workspaces, NewAiProperties infrastructure,
                                    @Qualifier("newAiPersistenceScheduler") Scheduler scheduler, @Qualifier("platformWorkspaceClock") Clock clock,
                                    PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, CommandReceiptStore receipts,
                                    PreferenceStore preferences, @Qualifier("platformAuthorizationService") ObjectProvider<com.arte.core.api.access.AuthorizationService> authorizationService, Environment environment,
                                    @Qualifier("appDataSource") javax.sql.DataSource source, NewAiGenerationProperties generation) {
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
        this.connectionEgress = new HttpEgressPolicy(generation);
        this.connectionOrigins = generation.allowedOrigins().stream().map(java.net.URI::toASCIIString).toList();
        this.authorizationService = authorizationService;
    }

    private record Published(long version, Catalog catalog) {
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
        return new Published(version, json.read(payload, Catalog.class));
    }

    /**
     * 取得最新发布版本并共享锁定指针；不可变 revision 内容随后只读。
     */
    private Published publishedCurrent() {
        Long version = mapper.headCurrent();
        if (version == null || version == 0) throw unavailable();
        String payload = mapper.revision(version);
        if (payload == null) throw unavailable();
        return new Published(version, json.read(payload, Catalog.class));
    }

    /**
     * DATABASE 仅使用数据库权威；head=0 返回空目录，绝不回退文件中的模型、连接、费率或授权。
     */
    @Override
    public NewAiProperties definitions() {
        var published = published(false);
        return (published == null ? Catalog.empty() : published.catalog()).properties(infrastructure, List.of(), List.of());
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
                AdmissionAuthorization.READ, AdmissionAuthorization.BUDGET_ADMIN).containsAll(requested))
            throw new AccessDeniedException("Workspace AI action denied");
        var requests = new ArrayList<com.arte.core.pojo.access.AccessRequest>();
        requests.add(DefaultAuthorizationService.workspaceRequest(scope,
                requested.contains(AdmissionAuthorization.INVOKE) ? WORKSPACE_AI_USE : WORKSPACE_READ, "database-ai-read"));
        if (requested.contains(AdmissionAuthorization.BUDGET_ADMIN))
            requests.add(DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_POLICY_WRITE, "database-ai-read"));
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
        var decisions = guardsAuthorization().batchCheck(actor, requests);
        if (decisions.size() != requests.size()) throw unavailable();
        if (decisions.subList(0, requiredActions).stream().anyMatch(d -> !d.allowed())) {
            if (decisions.stream().anyMatch(d -> d.reason() == com.arte.core.enums.access.AccessReasonEnum.AUTHORITY_UNAVAILABLE))
                throw unavailable();
            // 仅没有公共父空间的兼容范围可使用已发布 legacy grant，真实空间不能借用旧授权。
            if (decisions.getFirst().reason() == com.arte.core.enums.access.AccessReasonEnum.NOT_FOUND
                    && workspaces.getObject().find(scope).isEmpty()) {
                var published = published(true);
                var grant = published.catalog().legacyGrants().stream().filter(g -> g.enabled()
                                && g.principalKind() == ExecutionPrincipal.Kind.USER && g.subjectId().equals(actor.principal().subjectId())
                                && g.tenantId().equals(scope.tenantId()) && g.workspaceId().equals(scope.workspaceId())
                                && g.scopes().containsAll(requested)).findFirst()
                        .map(g -> renamed(g, name)).orElseThrow(() -> new AccessDeniedException("Workspace AI access denied"));
                var configuration = includeConfiguration ? published.catalog().properties(infrastructure, List.of(grant),
                        published.catalog().legacyBudgets().stream().filter(b -> grant.budgetRefs().contains(b.budgetRef())).toList()) : null;
                return new Snapshot(authorization(grant, requested), configuration);
            }
            throw new AccessDeniedException("Workspace AI access denied");
        }
        var row = mapper.workspaceRead(scope.tenantId(), scope.workspaceId());
        var settings = configurationRow(scope, row);
        if (!settings.preset().enabled() && requested.contains(AdmissionAuthorization.INVOKE))
            throw new AccessDeniedException("Workspace AI disabled");
        var auth = new ExecutionAuthorization(ExecutionPrincipal.from(actor.principal(), name),
                scope.tenantId(), scope.workspaceId(), requested, "workspace-ai:" + settings.version());
        if (!includeConfiguration) return new Snapshot(auth, null);
        var definitions = mapper.budgetsRead(scope.tenantId(), scope.workspaceId(), actor.principal().subjectId()).stream()
                .map(b -> json.read(b.getPayload(), NewAiProperties.Budget.class)).toList();
        var permissions = new HashSet<>(Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.CONVERSATION));
        if (settings.preset().enabled() && decisions.get(invokeAction).allowed())
            permissions.add(AdmissionAuthorization.INVOKE);
        if (decisions.get(policyAction).allowed()) permissions.add(AdmissionAuthorization.BUDGET_ADMIN);
        var grant = workspaceGrant(actor, settings, permissions, name, definitions);
        var catalog = published(true).catalog();
        var bindings = catalog.bindings().stream().filter(b -> settings.preset().bindings().contains(b.definition())).toList();
        var filtered = new Catalog(catalog.capabilities(), bindings, catalog.connections(), catalog.rates(),
                List.of(), List.of(), settings.preset(), catalog.disabledConnections());
        return new Snapshot(auth, withPersonal(actor, scope, filtered.properties(infrastructure, List.of(grant), definitions), false));
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
        if (row == null) throw new AccessDeniedException("Workspace AI is not prepared");
        var value = json.read(row.getPayload(), WorkspaceConfiguration.class);
        if (!value.scope().equals(scope) || value.version() != row.getVersion()) throw unavailable();
        return value;
    }

    private List<NewAiProperties.Budget> budgets(Scope scope, String subjectId) {
        return mapper.budgets(scope.tenantId(), scope.workspaceId(), subjectId).stream()
                .map(row -> json.read(row.getPayload(), NewAiProperties.Budget.class)).toList();
    }

    private NewAiProperties.Grant workspaceGrant(TrustedActor actor, WorkspaceConfiguration value, Set<String> permissions, String name, List<NewAiProperties.Budget> definitions) {
        var rates = value.preset().budgets().stream().map(BudgetPreset::rate).toList();
        var refs = definitions.stream().filter(b -> rates.contains(b.rate()))
                .map(NewAiProperties.Budget::budgetRef).collect(java.util.stream.Collectors.toSet());
        return new NewAiProperties.Grant(name, actor.principal().subjectId(), ExecutionPrincipal.Kind.USER,
                value.scope().tenantId(), value.scope().workspaceId(), "workspace-ai:" + value.version(), true, permissions,
                value.preset().bindings().stream().map(DefinitionRef::id).collect(java.util.stream.Collectors.toSet()), refs);
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
        var actor = actor(name);
        if (subjectId != null && !actor.principal().subjectId().equals(subjectId))
            throw new AccessDeniedException("Stable identity mismatch");
        if (!Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION,
                AdmissionAuthorization.READ, AdmissionAuthorization.BUDGET_ADMIN).containsAll(requested))
            throw new AccessDeniedException("Workspace AI action denied");
        if (workspaces.getObject().find(scope).isEmpty()) {
            var published = publishedCurrent();
            var grant = published.catalog().legacyGrants().stream().filter(g -> g.enabled()
                            && g.principalKind() == ExecutionPrincipal.Kind.USER && g.subjectId().equals(actor.principal().subjectId())
                            && g.tenantId().equals(scope.tenantId()) && g.workspaceId().equals(scope.workspaceId())
                            && g.scopes().containsAll(requested)).findFirst().map(g -> renamed(g, name))
                    .orElseThrow(() -> new AccessDeniedException("Workspace AI access denied"));
            identities.findCurrentBySubjectId(actor.principal().subjectId()).filter(p -> p.status() == PrincipalStatusEnum.ACTIVE)
                    .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
            return new CurrentSnapshot(authorization(grant, requested), includeConfiguration ? published.catalog().properties(infrastructure,
                    List.of(grant), published.catalog().legacyBudgets().stream().filter(b -> grant.budgetRefs().contains(b.budgetRef())).toList()) : null, published.version());
        }
        var requests = new ArrayList<com.arte.core.pojo.access.AccessRequest>();
        requests.add(DefaultAuthorizationService.workspaceRequest(scope,
                requested.contains(AdmissionAuthorization.INVOKE) ? WORKSPACE_AI_USE : WORKSPACE_READ, "database-ai-write"));
        if (requested.contains(AdmissionAuthorization.BUDGET_ADMIN))
            requests.add(DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_POLICY_WRITE, "database-ai-write"));
        return guards.getObject().inCurrentTransaction(actor, requests, () -> {
            var settings = ownConfiguration(scope);
            if (!settings.preset().enabled() && requested.contains(AdmissionAuthorization.INVOKE))
                throw new AccessDeniedException("Workspace AI disabled");
            var auth = new ExecutionAuthorization(ExecutionPrincipal.from(actor.principal(), name),
                    scope.tenantId(), scope.workspaceId(), requested, "workspace-ai:" + settings.version());
            if (!includeConfiguration) return new CurrentSnapshot(auth, null, null);
            var definitions = budgets(scope, actor.principal().subjectId());
            var grant = workspaceGrant(actor, settings, requested, name, definitions);
            var publication = publishedCurrent();
            var catalog = publication.catalog();
            var filtered = new Catalog(catalog.capabilities(), catalog.bindings().stream()
                    .filter(b -> settings.preset().bindings().contains(b.definition())).toList(), catalog.connections(), catalog.rates(),
                    List.of(), List.of(), settings.preset(), catalog.disabledConnections());
            return new CurrentSnapshot(auth, withPersonal(actor, scope, filtered.properties(infrastructure, List.of(grant), definitions), true), publication.version());
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
        if (personalModels != null && personalModels.getIfAvailable() != null) {
            return prepareConfiguration(context);
        }
        var principal = context.authorization().principal();
        if (principal.kind() != ExecutionPrincipal.Kind.USER)
            throw new AccessDeniedException("Stable identity mismatch");
        try {
            return tx.required(() -> {
                var current = currentSnapshot(principal.subjectName(), principal.subjectId(),
                        new Scope(context.authorization().tenantId(), context.authorization().workspaceId()),
                        context.authorization().scopes(), false);
                // 成员、策略、账户状态及空间开关已按当前门控重新检查；版本只决定是否重读配置内容。
                var expected = prepared.configuration().grants().stream().filter(g -> g.enabled()
                        && g.subjectId().equals(principal.subjectId()) && g.subjectName().equals(principal.subjectName())
                        && g.principalKind() == principal.kind()
                        && g.tenantId().equals(context.authorization().tenantId())
                        && g.workspaceId().equals(context.authorization().workspaceId())
                        && g.scopes().containsAll(context.authorization().scopes())).findFirst();
                Long revision = mapper.headCurrent();
                if (prepared.catalogRevision() != null && prepared.catalogRevision().equals(revision)
                        && expected.isPresent() && expected.get().grantRef().equals(current.authorization().grantRef()))
                    return prepared;
                // 来源没有凭证或配置变化时，回退到完整读取和具体 binding/预算校验。
                return prepareConfiguration(context);
            });
        } catch (PlatformException failure) {
            if (failure.getCode() == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE) throw failure;
            throw new AccessDeniedException("Workspace AI access denied", failure);
        }
    }

    private PlatformAiControlRow workspaceRow(WorkspaceConfiguration value) {
        var row = new PlatformAiControlRow();
        row.setTenantId(value.scope().tenantId());
        row.setWorkspaceId(value.scope().workspaceId());
        row.setVersion(value.version());
        row.setPayload(json.write(value));
        return row;
    }

    private void validate(Catalog catalog, Preset preset) {
        if (catalog.bindings().stream().anyMatch(b -> b.definition().version().equalsIgnoreCase("latest")))
            throw new IllegalArgumentException("Published models require fixed versions");
        var properties = catalog.properties(infrastructure, catalog.legacyGrants(), catalog.legacyBudgets());
        ControlDefinitionChecks.validate(properties);
        for (var ref : preset.bindings()) {
            var binding = catalog.bindings().stream().filter(b -> b.definition().equals(ref)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown published model"));
            if (preset.budgets().stream().noneMatch(b -> b.rate().equals(binding.rate())))
                throw new IllegalArgumentException("Model requires compatible budget");
        }
        for (var budget : preset.budgets()) {
            var rate = catalog.rates().stream().filter(r -> r.definition().equals(budget.rate())).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown published rate"));
            if (!rate.currency().equals(budget.limit().currency()) || budget.limit().amount().compareTo(new BigDecimal("1000000000")) > 0)
                throw new IllegalArgumentException("Invalid budget currency or limit");
        }
        if (preset.enabled() && (preset.budgets().isEmpty() || preset.bindings().isEmpty()
                && preset.budgets().stream().noneMatch(b -> catalog.rates().stream().anyMatch(r -> r.definition().equals(b.rate()) && r.toolPerCall() != null))))
            throw new IllegalArgumentException("Enabled AI requires approved model or tool budgets");
    }

    private WorkspaceConfiguration prepare(TrustedActor actor, Scope scope, String operationId) {
        var row = mapper.workspace(scope.tenantId(), scope.workspaceId());
        if (row != null) return ownConfiguration(scope);
        var p = published(true, true);
        validate(p.catalog(), p.catalog().defaults());
        var value = new WorkspaceConfiguration(scope, p.catalog().defaults(), p.version(), 0);
        check(mapper.insertWorkspace(workspaceRow(value)) == 1);
        if (value.preset().enabled()) prepareBudgets(scope, actor.principal().subjectId(), value.preset(), false);
        effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:ai:prepared", operationId, 0,
                Map.of("templateVersion", Long.toString(p.version())));
        return value;
    }

    private void prepareBudgets(Scope scope, String subject, Preset preset, boolean update) {
        var owner = new ExecutionOwner(scope.tenantId(), scope.workspaceId(), subject);
        var existing = budgets(scope, subject);
        for (var item : preset.budgets()) {
            var old = existing.stream().filter(b -> b.rate().equals(item.rate())).findFirst();
            var definition = old.orElseGet(() -> new NewAiProperties.Budget("ws-budget-" + hash(scope.tenantId(), scope.workspaceId(), subject, item.rate().id(), item.rate().version()), owner, item.limit(), item.rate()));
            if (!definition.owner().equals(owner)) throw unavailable();
            if (old.isEmpty()) {
                var row = new PlatformAiControlRow();
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
                var zero = new Money(BigDecimal.ZERO, definition.limit().currency());
                check(mapper.insertAccount(key, ownerKey, codec.encode(new BudgetCommands.Account(definition.budgetRef(), owner, definition.limit(), zero, zero, definition.rate(), 0))) == 1);
            } else {
                var account = codec.decode(encoded, BudgetCommands.Account.class);
                if (!account.owner().equals(owner) || !account.budgetRef().equals(definition.budgetRef()) || !account.rateVersion().equals(item.rate()))
                    throw unavailable();
                if (update && !account.limit().equals(item.limit())) {
                    if (!account.limit().currency().equals(item.limit().currency()))
                        throw new IllegalArgumentException("Budget currency is immutable");
                    var rows = mapper.budgets(scope.tenantId(), scope.workspaceId(), subject);
                    var row = rows.stream().filter(r -> r.getBudgetRef().equals(definition.budgetRef())).findFirst().orElseThrow();
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
     * 已准备空间只采用当前保存的批准规则；空间尚未准备时，仅政策管理者可采用当前已发布默认模板。
     * 查询模式不写入业务数据，准备模式只为当前用户和所选费率初始化账户，并在需要时准备空间 AI 配置。
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
            Catalog catalog = json.read(payload, Catalog.class);
            // 已准备空间沿用保存的规则，未准备空间使用当前默认模板。
            WorkspaceConfiguration config = mapper.workspace(scope.tenantId(), scope.workspaceId()) == null ? null : ownConfiguration(scope);
            Preset preset = config == null ? catalog.defaults() : config.preset();
            if (!preset.enabled()) {
                return new UsageBudget(ModelUsagePreparation.State.BLOCKED, "WORKSPACE_AI_DISABLED", List.of(), null);
            }
            // 批准额度必须匹配所选模型的精确费率，不从其他费率借用额度。
            BudgetPreset rule = preset.budgets().stream().filter(b -> b.rate().equals(rate)).findFirst().orElse(null);
            if (rule == null) {
                return new UsageBudget(ModelUsagePreparation.State.BLOCKED, "BUDGET_RULE_NOT_CONFIGURED", List.of(), null);
            }
            NewAiProperties.Rate definition = catalog.rates().stream().filter(r -> r.definition().equals(rate)).findFirst()
                    .orElseThrow(DatabaseAiControlService::unavailable);
            if (definition.toolPerCall() != null || !definition.currency().equals(rule.limit().currency())) {
                throw unavailable();
            }
            // 模型使用资格不能替代首次准备空间 AI 所需的政策管理资格。
            if (config == null && !allows(actor, scope, WORKSPACE_POLICY_WRITE)) {
                return new UsageBudget(ModelUsagePreparation.State.BLOCKED, "WORKSPACE_AI_NOT_PREPARED", List.of(), rule.limit());
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
                        || !account.limit().currency().equals(rule.limit().currency())) {
                    throw unavailable();
                }
                if (config == null) {
                    if (!prepare) {
                        return new UsageBudget(ModelUsagePreparation.State.PREPARABLE, "WORKSPACE_AI_NOT_PREPARED", List.of(budget.budgetRef()), rule.limit());
                    }
                    validate(catalog, preset);
                    check(mapper.insertWorkspace(workspaceRow(new WorkspaceConfiguration(scope, preset, revision, 0))) == 1);
                    // 仅补齐空间 AI 配置，已有预算账户及费用记录保持原样。
                    effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:ai:prepared",
                            operationId, 0, Map.of("templateVersion", Long.toString(revision)));
                }
                return new UsageBudget(ModelUsagePreparation.State.READY, "READY", List.of(budget.budgetRef()), rule.limit());
            }
            String ref = "ws-budget-" + hash(scope.tenantId(), scope.workspaceId(), owner.subjectId(), rate.id(), rate.version());
            // 有账户却缺失定义同样拒绝，避免为孤立账本重复分配额度。
            if (mapper.account(hash(ref), ownerKey) != null) {
                throw unavailable();
            }
            if (rule.limit().amount().signum() == 0) {
                return new UsageBudget(ModelUsagePreparation.State.BLOCKED, "BUDGET_RULE_ZERO", List.of(), rule.limit());
            }
            if (!prepare) {
                return new UsageBudget(ModelUsagePreparation.State.PREPARABLE, "BUDGET_NOT_PREPARED", List.of(), rule.limit());
            }
            if (config == null) {
                // 与账户及命令回执同事务；不调用 ensure，避免顺带建立其他费率的账户。
                validate(catalog, preset);
                config = new WorkspaceConfiguration(scope, preset, revision, 0);
                check(mapper.insertWorkspace(workspaceRow(config)) == 1);
                effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:ai:prepared",
                        operationId, 0, Map.of("templateVersion", Long.toString(revision)));
            }
            // 只按已批准规则初始化当前用户、当前精确费率的账户。
            prepareBudgets(scope, owner.subjectId(), new Preset(true, List.of(), List.of(rule)), false);
            return new UsageBudget(ModelUsagePreparation.State.READY, "READY", List.of(ref), rule.limit());
        });
    }

    /**
     * 按需准备空间 AI 配置及当前主体预算；不参与公共空间创建事务。
     * 准备失败只回滚本次 AI 写入，已创建的公共空间继续可用。
     */
    public WorkspaceView ensure(TrustedActor actor, Scope scope) {
        return inScope(actor, scope, WORKSPACE_READ, () -> {
            var p = published(false, true);
            if (p == null) return view(actor, scope, null, null);
            WorkspaceConfiguration config;
            if (mapper.workspace(scope.tenantId(), scope.workspaceId()) == null) {
                config = inScope(actor, scope, WORKSPACE_POLICY_WRITE, () -> prepare(actor, scope, UUID.randomUUID().toString()));
            } else config = ownConfiguration(scope);
            if (config.preset().enabled() && allows(actor, scope, WORKSPACE_AI_USE)) {
                var before = budgets(scope, actor.principal().subjectId());
                prepareBudgets(scope, actor.principal().subjectId(), config.preset(), false);
                if (before.isEmpty())
                    effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:ai:account-prepared",
                            UUID.randomUUID().toString(), config.version(), Map.of());
            }
            return view(actor, scope, config, p);
        });
    }

    public WorkspaceView get(TrustedActor actor, Scope scope) {
        return inScope(actor, scope, WORKSPACE_READ, () -> {
            var p = published(false, true);
            var row = mapper.workspace(scope.tenantId(), scope.workspaceId());
            return view(actor, scope, row == null ? null : ownConfiguration(scope), p);
        });
    }

    private WorkspaceView view(TrustedActor actor, Scope scope, WorkspaceConfiguration config, Published published) {
        var workspace = workspaces.getObject().lock(scope).orElseThrow(DatabaseAiControlService::unavailable);
        return new WorkspaceView("DATABASE", config, workspace.version(), allows(actor, scope, WORKSPACE_POLICY_WRITE), published == null,
                published == null ? List.of() : published.catalog().bindings().stream().filter(b -> published.catalog().defaults().bindings().contains(b.definition())
                                || config != null && config.preset().bindings().contains(b.definition()))
                        .map(b -> new Model(b.definition(), b.remoteOperation(), b.connection(), b.rate())).toList(),
                published == null ? null : published.catalog().defaults(), published == null ? List.of() : published.catalog().rates().stream()
                .filter(r -> r.toolPerCall() != null && (published.catalog().defaults().budgets().stream().anyMatch(b -> b.rate().equals(r.definition()))
                        || config != null && config.preset().budgets().stream().anyMatch(b -> b.rate().equals(r.definition())))).toList());
    }

    public CommandReceipt<WorkspaceView> update(TrustedActor actor, CommandMeta meta, Scope scope, long expectedWorkspaceVersion, long expectedVersion, Preset preset) {
        return update(actor, meta, scope, expectedWorkspaceVersion, expectedVersion, preset, false);
    }

    /**
     * canRaiseBudget is supplied by trusted HTTP/RBAC adaptation, never by the request body.
     */
    public CommandReceipt<WorkspaceView> update(TrustedActor actor, CommandMeta meta, Scope scope, long expectedWorkspaceVersion, long expectedVersion, Preset preset, boolean canRaiseBudget) {
        var commandScope = new CommandScope(scope.tenantId(), scope.workspaceId());
        return commands.execute(actor, meta, commandScope, "workspace:ai:update", "workspace:ai:view:v1", WorkspaceView.class,
                List.of(Long.toString(expectedWorkspaceVersion), Long.toString(expectedVersion), canonical(preset)),
                () -> inScope(actor, scope, WORKSPACE_POLICY_WRITE, () -> true), saved -> {
                    if (saved.configuration() == null || !saved.configuration().scope().equals(scope))
                        throw unavailable();
                    inScope(actor, scope, WORKSPACE_POLICY_WRITE, () -> true);
                }, operationId -> {
                    var p = published(true);
                    var old = ownConfiguration(scope);
                    var workspace = workspaces.getObject().lock(scope).orElseThrow(DatabaseAiControlService::unavailable);
                    check(old.version() == expectedVersion && workspace.version() == expectedWorkspaceVersion);
                    validate(p.catalog(), preset);
                    var approvedModels = new HashSet<>(p.catalog().defaults().bindings());
                    approvedModels.addAll(old.preset().bindings());
                    if (!approvedModels.containsAll(preset.bindings()))
                        throw new AccessDeniedException("Model is not approved for public workspaces");
                    if (!canRaiseBudget) {
                        var template = json.read(mapper.revision(old.templateVersion()), Catalog.class).defaults();
                        for (var budget : preset.budgets()) {
                            var cap = java.util.stream.Stream.concat(old.preset().budgets().stream(), template.budgets().stream())
                                    .filter(b -> b.rate().equals(budget.rate())).map(b -> b.limit().amount()).max(BigDecimal::compareTo)
                                    .orElseGet(() -> p.catalog().defaults().budgets().stream().filter(b -> b.rate().equals(budget.rate()))
                                            .map(b -> b.limit().amount()).findFirst().orElse(BigDecimal.ZERO));
                            if (budget.limit().amount().compareTo(cap) > 0)
                                throw new AccessDeniedException("Raising allocated budget requires platform configuration permission");
                        }
                    }
                    var updated = new WorkspaceConfiguration(scope, preset, old.templateVersion(), Math.incrementExact(old.version()));
                    check(mapper.replaceWorkspace(workspaceRow(updated), old.version()) == 1);
                    for (var subject : mapper.workspaceBudgets(scope.tenantId(), scope.workspaceId()).stream().map(PlatformAiControlRow::getSubjectId).distinct().sorted().toList()) {
                        prepareBudgets(scope, subject, preset, true);
                    }
                    if (preset.enabled()) prepareBudgets(scope, actor.principal().subjectId(), preset, true);
                    var changed = new com.arte.core.pojo.workspace.Workspace(scope, workspace.name(), workspace.description(), workspace.state(), workspace.createdBy(),
                            Math.incrementExact(workspace.version()), Math.incrementExact(workspace.authEpoch()), workspace.createdAt(), clock.instant());
                    check(workspaces.getObject().replace(changed, workspace.version()));
                    effects.append(actor, commandScope, "workspace:ai:updated", operationId, updated.version(), Map.of("workspaceEpoch", Long.toString(changed.authEpoch())));
                    return new DefaultCommandExecutor.Result<>(view(actor, scope, updated, p), updated.version());
                });
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
            try {
                connectionEgress.validateOrigin(settings.endpoint());
            } catch (RuntimeException denied) {
                throw new IllegalArgumentException("Connection origin is not permitted by deployment policy");
            }
            var connections = new ArrayList<>(old.connections());
            // 全局目录修订号保证版本唯一；相同原键重放保留原引用，不再创建新版本。
            connections.add(new ConnectionDefinition(new DefinitionRef("connection", settings.id(), "db-v" + Math.incrementExact(expected)),
                    settings.providerId(), ChatCompletionsSseProtocolAdapter.DEFINITION, settings.endpoint(), null,
                    ConnectionDefinition.State.ENABLED, java.time.Duration.ofSeconds(settings.connectTimeoutSeconds()),
                    java.time.Duration.ofSeconds(settings.responseTimeoutSeconds()), settings.maxResponseBytes()));
            return new Catalog(old.capabilities(), old.bindings(), connections, old.rates(), old.legacyGrants(), old.legacyBudgets(),
                    old.defaults(), old.disabledConnections());
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
                    old.defaults(), disabled);
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
        return connectionOrigins;
    }

    private void requireCatalogManager(TrustedActor actor) {
        var name = identities.currentSubjectName(actor.principal().subjectId()).orElseThrow(() ->
                new PlatformException(PlatformErrorCodeEnum.UNAUTHENTICATED, "connection"));
        var permissions = catalogPermissions.current(name);
        if (!permissions.contains("admin") && !permissions.contains("aiConfiguration:manage"))
            throw new PlatformException(PlatformErrorCodeEnum.ACCESS_DENIED, "connection");
    }

    /**
     * 不依赖已有同名绑定，原子追加能力、绑定与费率；不自动授权或分配预算。
     */
    public CommandReceipt<CatalogView> publishModelPlan(TrustedActor actor, CommandMeta meta, long expected, ReviewedModelSettings settings) {
        return changeCatalog(actor, meta, expected, "ai:model-plan:publish", json.write(settings), old -> {
            ConnectionDefinition connection = old.connections().stream().filter(c -> c.definition().equals(settings.connection()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown reviewed connection"));
            if (old.disabledConnections().contains(connection.definition().id()) || connection.state() != ConnectionDefinition.State.ENABLED
                    || !"deepseek".equals(connection.providerId()) || !ChatCompletionsSseProtocolAdapter.DEFINITION.equals(connection.protocol())) {
                throw new IllegalArgumentException("Reviewed connection is disabled or unsupported");
            }
            try {
                connectionEgress.validateOrigin(connection.endpoint());
            } catch (RuntimeException denied) {
                throw new IllegalArgumentException("Connection origin is not permitted by deployment policy");
            }
            String family = "reviewed-" + settings.id();
            String version = "db-v" + Math.incrementExact(expected);
            Set<CapabilityDescriptor.Feature> features = new HashSet<>(Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING));
            if (settings.usageReporting()) {
                features.add(CapabilityDescriptor.Feature.USAGE_REPORTING);
            }
            CapabilityDescriptor capability = new CapabilityDescriptor(new DefinitionRef("capability", family, version),
                    CapabilityDescriptor.Kind.GENERATION, ControlDefinitionChecks.INPUT_SCHEMA, ControlDefinitionChecks.OUTPUT_SCHEMA,
                    features, CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
            DefinitionRef rate = new DefinitionRef("rate", family, version);
            List<CapabilityDescriptor> capabilities = new ArrayList<>(old.capabilities());
            capabilities.add(capability);
            List<ResolvedBinding> bindings = new ArrayList<>(old.bindings());
            bindings.add(new ResolvedBinding(new DefinitionRef("binding", family, version), capability, settings.connection(),
                    settings.remoteModel(), settings.contextWindowTokens(), rate));
            List<NewAiProperties.Rate> rates = new ArrayList<>(old.rates());
            Currency currency = Currency.getInstance(settings.currency());
            rates.add(new NewAiProperties.Rate(rate, new Money(new BigDecimal(settings.inputPerMillion()), currency),
                    new Money(new BigDecimal(settings.outputPerMillion()), currency)));
            return new Catalog(capabilities, bindings, old.connections(), rates, old.legacyGrants(), old.legacyBudgets(), old.defaults(), old.disabledConnections());
        });
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
                    var before = published(false);
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
                                || !data.capabilities().containsAll(old.capabilities()) || !data.rates().containsAll(old.rates()))
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
