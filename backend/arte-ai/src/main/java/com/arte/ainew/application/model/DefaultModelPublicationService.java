package com.arte.ainew.application.model;

import com.arte.ainew.api.model.ModelPublicationService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.control.ControlDefinitionChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.model.*;
import com.arte.ainew.pojo.model.ModelDrafts.Credential;
import com.arte.ainew.pojo.model.ModelDrafts.Draft;
import com.arte.ainew.pojo.model.ModelDrafts.Validation;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.model.*;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.application.workspace.WorkspaceMutationEffects;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.workspace.Workspace;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static com.arte.core.constant.platform.PlatformActions.*;

/**
 * 实际模型统一发布应用服务：沿用公共身份、ACL、同库事务及幂等回执。
 * 发布固定已审核模型的协议、费率和能力边界，凭据必须由本人提供且验证依赖一致；不改写预算或历史执行。
 * 运行状态只报告当前准备条件，最终执行仍由受理、派发和外发门控重新核验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public final class DefaultModelPublicationService implements ModelPublicationService {

    private final ModelResourceStore models;
    private final ModelDraftStore drafts;
    private final ModelPublicationStore releases;
    private final ModelConnectionTemplates templates;
    private final ReviewedModelPlanSource catalog;
    private final PublicTransactionRunner tx;
    private final WorkspaceGovernanceLocks locks;
    private final CurrentPrincipalResolver principals;
    private final AuthorizationService authorization;
    private final DefaultCommandExecutor commands;
    private final WorkspaceMutationEffects effects;
    private final WorkspaceStore workspaces;
    private final Clock clock;
    private final Supplier<ModelGateway> gateway;
    private final TenantMembershipStore tenantMembers;
    private final TenantStore tenants;
    private com.arte.ainew.spi.model.ModelSharingStore sharing;

    public void sharing(com.arte.ainew.spi.model.ModelSharingStore sharing) {
        this.sharing = sharing;
    }

    public DefaultModelPublicationService(ModelResourceStore models, ModelDraftStore drafts, ModelPublicationStore releases,
                                          ModelConnectionTemplates templates, ReviewedModelPlanSource catalog,
                                          PublicTransactionRunner tx, IdentityProvider identity,
                                          TenantStore tenants, WorkspaceStore workspaces, TenantMembershipStore tenantMembers, WorkspaceMembershipStore members,
                                          PreferenceStore preferences, AuthorizationService authorization, CommandReceiptStore receipts, PublicOutboxStore outbox,
                                          AuditSink audit, PlatformIdGenerator ids, Clock clock, Supplier<ModelGateway> gateway) {
        this.models = models;
        this.drafts = drafts;
        this.releases = releases;
        this.templates = templates;
        this.catalog = catalog;
        this.tx = tx;
        this.authorization = authorization;
        this.workspaces = workspaces;
        this.clock = clock;
        this.gateway = gateway;
        this.tenantMembers = tenantMembers;
        this.tenants = tenants;
        this.locks = new WorkspaceGovernanceLocks(tx, tenants, workspaces, tenantMembers, members, preferences);
        this.principals = new CurrentPrincipalResolver(identity);
        this.commands = new DefaultCommandExecutor(tx, principals, receipts, ids, clock);
        this.effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
    }

    /**
     * 宿主原写事务内取得完整治理门控并重检用户身份，不能持锁跨网络。
     */
    public void lock(TrustedActor actor, Scope scope) {
        locks.scopes(actor.principal().subjectId(), List.of(scope));
        principals.requireUserCurrent(actor);
    }

    /**
     * 宿主持有原范围门控后核验当前稳定模型身份，不授予访问许可。
     */
    public ModelResource current(Scope scope, ResourceRef resource) {
        ModelResource value = models.findCurrent(resource).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (!value.ownership().tenantId().equals(scope.tenantId()) || !value.managementScope().equals(scope)) {
            throw error(PlatformErrorCodeEnum.NOT_FOUND);
        }
        return value;
    }

    private void require(TrustedActor actor, Scope scope, ResourceRef resource, Action action) {
        authorization.requireInCurrentTransaction(actor, new AccessRequest(scope, resource, action, "personal-model"));
    }

    /**
     * 从稳定资源派生专属绑定命名空间，客户端不能选择该值。
     */
    public static String family(ResourceRef resource) {
        return "resource-" + DefaultCommandExecutor.digest(List.of(resource.tenantId(), resource.resourceId()));
    }

    static DefinitionRef binding(ResourceRef resource, long version) {
        return new DefinitionRef("binding", family(resource), "v" + version);
    }

    static DefinitionRef secret(Credential value) {
        return new DefinitionRef("secret", value.secret().id(), "v" + value.version());
    }

    private boolean matches(Draft draft, ReviewedModelPlan plan) {
        return plan.planRef().equals(draft.settings().planRef())
                && ReviewedModelPlanChecks.digest(plan).equals(ReviewedModelPlanChecks.digest(draft.plan()))
                && draft.settings().contextWindowTokens() >= 2
                && draft.settings().contextWindowTokens() <= plan.contextWindowTokens()
                && draft.settings().maxOutputTokens() < draft.settings().contextWindowTokens();
    }

    private List<ModelPublications.Option> options(Draft draft, ReviewedModelPlanSource.Snapshot reviewed) {
        ConnectionDefinition connection = templates.require(draft.scope(), draft.plan().connection());
        ModelGateway currentGateway = gateway.get();
        return reviewed.plans().stream().filter(plan -> matches(draft, plan))
                .filter(plan -> currentGateway != null && currentGateway.supportsTextChat(ReviewedModelPlanChecks.metadata(plan), connection)
                        && (!plan.features().contains(CapabilityDescriptor.Feature.TOOL_CALLS)
                        || currentGateway.supportsToolChat(ReviewedModelPlanChecks.metadata(plan), connection)))
                .filter(plan -> reviewed.rates().stream().anyMatch(rate -> rate.definition().equals(plan.rate()) && rate.toolPerCall() == null))
                .map(plan -> new ModelPublications.Option(plan.planRef(), plan.rate(), reviewed.catalogRevision(), plan.contextWindowTokens())).toList();
    }

    @Override
    public List<ModelPublications.Option> options(TrustedActor actor, Scope scope, ResourceRef resource) {
        return tx.required(() -> {
            lock(actor, scope);
            current(scope, resource);
            require(actor, scope, resource, MODEL_PUBLISH);
            Draft draft = drafts.draft(resource).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
            return options(draft, catalog.read());
        });
    }

    @Override
    public CommandReceipt<ModelResourceVersion> publish(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef resource,
                                                        long expectedVersion, long draftVersion, long credentialVersion, String validationId, DefinitionRef planRef, long catalogRevision) {
        return commands.execute(actor, meta, new CommandScope(scope.tenantId(), scope.workspaceId()), "model:publish",
                "model:version:v2", ModelResourceVersion.class,
                List.of(resource.resourceId(), Long.toString(expectedVersion), Long.toString(draftVersion), Long.toString(credentialVersion),
                        validationId, planRef.type(), planRef.id(), planRef.version(), Long.toString(catalogRevision)),
                () -> lock(actor, scope), value -> {
                    current(scope, resource);
                    require(actor, scope, resource, MODEL_PUBLISH);
                    ModelPublications.Release release = releases.find(scope, value.publication().binding().definition(), true).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
                    if (!value.resource().equals(resource) || !release.resource().equals(resource)
                            || !models.findCurrentBinding(scope.tenantId(), release.binding()).orElseThrow().equals(value)) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                    // 重放返回原发布证据；不重新发布或恢复已撤销凭据。
                }, operation -> {
                    ModelResource old = current(scope, resource);
                    require(actor, scope, resource, MODEL_PUBLISH);
                    check(old.version() == expectedVersion);
                    if (old.bindingId() != null && !old.bindingId().equals(family(resource))) {
                        throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
                    }
                    Draft draft = drafts.draft(resource).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                    Credential credential = drafts.credential(resource).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                    check(draft.version() == draftVersion && credential.version() == credentialVersion);
                    if (credential.revoked() || !credential.scope().equals(scope)
                            || !credential.responsibleSubjectId().equals(actor.principal().subjectId())) {
                        throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                    }
                    ConnectionDefinition template = templates.require(scope, draft.plan().connection());
                    String digest = templates.digest(template);
                    ModelDrafts.CredentialKind requiredKind = old.ownership().workspaceId() == null
                            ? ModelDrafts.CredentialKind.TENANT : ModelDrafts.CredentialKind.PERSONAL;
                    if (credential.kind() != requiredKind) {
                        throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
                    }
                    Validation validation = drafts.validation(scope, validationId).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                    Instant now = PlatformTimes.businessNow(clock);
                    if (!validation.resource().equals(resource) || !validation.requestedBy().equals(actor.principal().subjectId())
                            || !validation.publishableAt(now) || validation.draftVersion() != draftVersion
                            || validation.credentialVersion() != credentialVersion || !validation.templateDigest().equals(digest)
                            || !validation.planRef().equals(planRef) || !draft.settings().planRef().equals(planRef)
                            || !validation.planDigest().equals(ReviewedModelPlanChecks.digest(draft.plan()))) {
                        throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
                    }
                    ReviewedModelPlanSource.Snapshot reviewed = catalog.read();
                    check(Objects.equals(reviewed.catalogRevision(), catalogRevision));
                    ModelPublications.Option option = options(draft, reviewed).stream().filter(value -> value.planRef().equals(planRef))
                            .findFirst().orElseThrow(() -> error(PlatformErrorCodeEnum.INVALID_ARGUMENT));
                    ReviewedModelPlan base = reviewed.plans().stream().filter(value -> value.planRef().equals(planRef)).findFirst().orElseThrow();
                    RateDefinition rate = reviewed.rates().stream().filter(value -> value.definition().equals(option.rate())).findFirst().orElseThrow();
                    long nextVersion = Math.incrementExact(old.version());
                    String version = "v" + nextVersion;
                    DefinitionRef bindingRef = binding(resource, nextVersion);
                    DefinitionRef connectionRef = new DefinitionRef("connection", family(resource), version);
                    CapabilityDescriptor capability = new CapabilityDescriptor(new DefinitionRef("capability", family(resource), version), CapabilityDescriptor.Kind.GENERATION,
                            ControlDefinitionChecks.INPUT_SCHEMA,
                            ControlDefinitionChecks.OUTPUT_SCHEMA, base.features(),
                            CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
                    ResolvedBinding binding = new ResolvedBinding(bindingRef, capability, connectionRef, draft.plan().remoteModel(), draft.settings().contextWindowTokens(), rate.definition());
                    String endpoint = template.endpoint().toASCIIString().replaceAll("/+$", "");
                    if (endpoint.endsWith("/chat/completions")) {
                        endpoint = endpoint.substring(0, endpoint.length() - "/chat/completions".length());
                    }
                    ConnectionDefinition connection = new ConnectionDefinition(connectionRef, template.providerId(), template.protocol(), URI.create(endpoint),
                            secret(credential), template.state(), template.connectTimeout(), template.responseTimeout(), template.maxResponseBytes());
                    ModelPublication publication = new ModelPublication(ModelPublication.Source.RESOURCE, catalogRevision, reviewed.releaseRef(), binding, connection,
                            rate, planRef, requiredKind);
                    ModelResourceVersion value = new ModelResourceVersion(resource, publication, actor.principal().subjectId(), now);
                    ModelPublications.Release release = new ModelPublications.Release(resource, scope, bindingRef, planRef, template.definition(), digest, ReviewedModelPlanChecks.digest(base), requiredKind,
                            catalogRevision, draftVersion, credentialVersion, secret(credential), validationId, draft.settings().maxOutputTokens(), actor.principal().subjectId(), now);
                    if (releases.list(scope, true).size() >= 256) {
                        throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
                    }
                    check(models.insertVersion(value));
                    check(releases.insert(release));
                    check(models.replace(new ModelResource(resource, old.ownership(), draft.name(), old.createdBy(), ModelResource.State.PUBLISHED,
                            bindingRef.id(), nextVersion, old.createdAt(), old.managementScope()), old.version()));
                    // 在提交前校验实际目录容量和精确引用冲突，不提交无法装配的版本。
                    mergeDefinitions(new NewAiProperties(false, null, reviewed.releaseRef(), null, null, List.of(), List.of(), List.of(), List.of(),
                            reviewed.rates().stream().map(item -> new NewAiProperties.Rate(item.definition(), item.inputPerMillion(), item.outputPerMillion(), item.toolPerCall())).toList(), List.of()), releases.list(scope, true).stream()
                            .map(item -> models.findCurrentBinding(scope.tenantId(), item.binding()).orElseThrow().publication()).toList());
                    if (old.ownership().workspaceId() == null) {
                        com.arte.core.pojo.workspace.Tenant tenant = locks.lockTenant(scope.tenantId()).orElseThrow();
                        check(tenants.replace(new com.arte.core.pojo.workspace.Tenant(tenant.id(), tenant.name(), tenant.type(), tenant.personalSubjectId(), tenant.state(), tenant.createdBy(),
                                Math.incrementExact(tenant.version()), Math.incrementExact(tenant.authEpoch()), tenant.createdAt(), PlatformTimes.notBefore(clock, tenant.updatedAt())), tenant.version()));
                    } else {
                        Workspace workspace = locks.lockWorkspace(scope).orElseThrow();
                        check(workspaces.replace(new Workspace(workspace.scope(), workspace.name(), workspace.description(), workspace.state(), workspace.createdBy(),
                                Math.incrementExact(workspace.version()), Math.incrementExact(workspace.authEpoch()), workspace.createdAt(), PlatformTimes.notBefore(clock, workspace.updatedAt())), workspace.version()));
                    }
                    effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), "model:published", operation, nextVersion,
                            Map.of("resourceId", resource.resourceId(), "bindingId", bindingRef.id(), "bindingVersion", bindingRef.version()));
                    return new DefaultCommandExecutor.Result<>(value, nextVersion);
                });
    }

    /**
     * 同空间目录合并只追加不可变定义，绝不覆盖原授权和预算。
     */
    public static NewAiProperties mergeDefinitions(NewAiProperties base, List<ModelPublication> publications) {
        Map<DefinitionRef, CapabilityDescriptor> capabilities = index(base.capabilities(), CapabilityDescriptor::definition);
        Map<DefinitionRef, ResolvedBinding> bindings = index(base.bindings(), ResolvedBinding::definition);
        Map<DefinitionRef, ConnectionDefinition> connections = index(base.connections(), ConnectionDefinition::definition);
        Map<DefinitionRef, NewAiProperties.Rate> rates = index(base.rates(), NewAiProperties.Rate::definition);
        for (ModelPublication publication : publications) {
            put(capabilities, publication.binding().capability().definition(), publication.binding().capability());
            put(bindings, publication.binding().definition(), publication.binding());
            put(connections, publication.connection().definition(), publication.connection());
            RateDefinition rate = publication.rate();
            put(rates, rate.definition(), new NewAiProperties.Rate(rate.definition(), rate.inputPerMillion(), rate.outputPerMillion(), rate.toolPerCall()));
        }
        return new NewAiProperties(base.enabled(), base.dataSourceBean(), base.releaseRef(), base.persistence(), base.limits(), base.grants(),
                List.copyOf(capabilities.values()), List.copyOf(bindings.values()), List.copyOf(connections.values()), List.copyOf(rates.values()), base.budgets(),
                base.configurationMode());
    }

    private static <T> Map<DefinitionRef, T> index(List<T> values, java.util.function.Function<T, DefinitionRef> key) {
        Map<DefinitionRef, T> result = new LinkedHashMap<>();
        for (T value : values) {
            put(result, key.apply(value), value);
        }
        return result;
    }

    private static <T> void put(Map<DefinitionRef, T> values, DefinitionRef key, T value) {
        T old = values.putIfAbsent(key, value);
        if (old != null && !old.equals(value)) {
            throw error(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
        }
    }

    @Override
    public ModelPublications.Status status(TrustedActor actor, Scope scope, ResourceRef resource, DefinitionRef binding) {
        return tx.required(() -> {
            lock(actor, scope);
            ModelPublications.Release release = releases.find(scope, binding, true).filter(value -> value.resource().equals(resource))
                    .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
            if (!scope.equals(release.scope())) {
                throw error(PlatformErrorCodeEnum.NOT_FOUND);
            }
            lock(actor, release.scope());
            current(release.scope(), resource);
            require(actor, scope, resource, MODEL_READ);
            String reason = publicationReadiness(actor, release);
            return new ModelPublications.Status(binding, reason == null, reason == null ? "VALID" : reason, release.maxOutputTokens());
        });
    }

    /**
     * 仅检查管理发布依赖，不读取管理空间预算或据此宣称当前用户可聊天。
     */
    public String publicationReadiness(TrustedActor actor, ModelPublications.Release release) {
        ModelResource model = current(release.scope(), release.resource());
        if (model.state() != ModelResource.State.PUBLISHED) {
            return "MODEL_DISABLED";
        }
        Credential credential = drafts.credential(release.resource()).orElse(null);
        if (credential == null || credential.revoked() || credential.version() != release.credentialVersion()
                || credential.kind() != release.credentialKind() || !credential.scope().equals(release.scope())
                || credential.kind() != (model.ownership().workspaceId() == null ? ModelDrafts.CredentialKind.TENANT : ModelDrafts.CredentialKind.PERSONAL)
                || !secret(credential).equals(release.secret())) {
            return "CREDENTIAL_STALE";
        }
        ReviewedModelPlanSource.Snapshot snapshot = catalog.read();
        ReviewedModelPlan plan = snapshot.plans().stream().filter(value -> value.planRef().equals(release.planRef())).findFirst()
                .orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        if (!ReviewedModelPlanChecks.digest(plan).equals(release.planDigest())) {
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        }
        ConnectionDefinition template = snapshot.connections().stream().filter(value -> value.definition().equals(release.template())).findFirst()
                .orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        if (template.state() != ConnectionDefinition.State.ENABLED) {
            return "TEMPLATE_UNAVAILABLE";
        }
        if (!templates.digest(template).equals(release.templateDigest())) {
            return "TEMPLATE_STALE";
        }
        return null;
    }

    /**
     * 调用方须已取得当前主体和范围门控；只返回安全状态码，不泄露凭据。
     */
    public String readiness(TrustedActor actor, ModelPublications.Release release) {
        return readiness(actor, release, release.scope());
    }

    /**
     * 实际使用空间独立于管理空间；同租户范围扩展仍在原父租户门控内。
     */
    public String readiness(TrustedActor actor, ModelPublications.Release release, Scope executionScope) {
        if (!executionScope.tenantId().equals(release.scope().tenantId())) {
            return "MODEL_USE_DENIED";
        }
        lock(actor, release.scope());
        String dependency = publicationReadiness(actor, release);
        if (dependency != null) {
            return dependency;
        }
        String adapter = adapterBlocker(release);
        if (adapter != null) {
            return adapter;
        }
        try {
            authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(executionScope, WORKSPACE_AI_USE, "personal-model"));
            require(actor, executionScope, release.resource(), MODEL_USE);
        } catch (PlatformException denied) {
            if (denied.getCode() == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE) {
                throw denied;
            }
            return "MODEL_USE_DENIED";
        }
        String credential = credentialUseBlocker(actor, release, executionScope);
        return credential == null ? executionPolicyBlocker(release, executionScope) : credential;
    }

    /**
     * 调用方须已持有管理空间门控，并核验精确绑定。
     */
    public String adapterBlocker(ModelPublications.Release release) {
        ModelPublication publication = models.findCurrentBinding(release.scope().tenantId(), release.binding())
                .orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)).publication();
        ModelGateway adapter = gateway.get();
        return adapter == null || !adapter.supportsTextChat(publication.binding(), publication.connection())
                ? "ADAPTER_UNAVAILABLE" : null;
    }

    /**
     * 读取凭据用途前，调用方须已核验空间 AI 及模型使用权限。
     */
    public String credentialUseBlocker(TrustedActor actor, ModelPublications.Release release, Scope executionScope) {
        ModelResource model = current(release.scope(), release.resource());
        Credential credential = drafts.credential(release.resource()).orElse(null);
        if (credential == null || credential.revoked() || credential.version() != release.credentialVersion()
                || credential.kind() != release.credentialKind() || !credential.scope().equals(release.scope()) || !secret(credential).equals(release.secret())) {
            return "CREDENTIAL_STALE";
        }
        if (!credential.responsibleSubjectId().equals(actor.principal().subjectId())) {
            if (credential.kind() != com.arte.ainew.pojo.model.ModelDrafts.CredentialKind.TENANT || sharing == null || model.ownership().workspaceId() != null) {
                return "CREDENTIAL_USE_DENIED";
            }
            com.arte.ainew.pojo.model.ModelSharingPolicy policy = sharing.find(model.resource(), true).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
            com.arte.core.pojo.workspace.TenantMembership member = tenantMembers.lock(executionScope.tenantId(), actor.principal().subjectId()).orElse(null);
            if (policy.credentialVersion() != credential.version() || member == null
                    || member.state() != com.arte.core.enums.workspace.MembershipStateEnum.ACTIVE
                    || !Objects.equals(policy.consumers().get(actor.principal().subjectId()), member.generation())) {
                return "CREDENTIAL_USE_DENIED";
            }
        }
        return null;
    }

    public String executionPolicyBlocker(ModelPublications.Release release, Scope executionScope) {
        try {
            // 运行出口取租户与实际使用空间政策，管理空间只用于维护，不替代目标空间准入。
            if (!templates.digest(templates.require(executionScope, release.template())).equals(release.templateDigest())) {
                return "TEMPLATE_STALE";
            }
        } catch (PlatformException denied) {
            if (denied.getCode() != PlatformErrorCodeEnum.ACCESS_DENIED) {
                throw denied;
            }
            return "TEMPLATE_UNAVAILABLE";
        }
        return null;
    }

    static void check(boolean condition) {
        if (!condition) {
            throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
        }
    }

    public static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "personal-model");
    }
}
