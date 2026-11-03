package com.arte.app.platform.ai;

import com.arte.ainew.api.model.ModelUsagePreparationService;
import com.arte.ainew.application.model.DefaultPersonalModelPublicationService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.model.*;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.model.ModelAclScopeStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.ainew.spi.model.PersonalModelStore;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.workspace.WorkspaceMutationEffects;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.platform.*;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;
import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_AI_USE;

/**
 * DATABASE 模型使用准备。公共父范围门控串行化预算准备、撤权、政策修改和模型停用；
 * 模型资格、单费率账户、审计、Outbox 及成功回执在同一物理事务内提交，不调用供应商。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/02 21:30 ✾
 */
public final class DatabaseModelUsagePreparationService implements ModelUsagePreparationService {

    private final DatabaseAiControlService control;
    private final DefaultPersonalModelPublicationService publications;
    private final PersonalModelRuntimeAdapter runtime;
    private final PersonalModelStore releases;
    private final ModelResourceStore models;
    private final ModelAclScopeStore scopes;
    private final AuthorizationService authorization;
    private final PublicTransactionRunner tx;
    private final DefaultCommandExecutor commandExecutor;
    private final WorkspaceMutationEffects effects;
    private final Supplier<ModelGateway> gateway;

    public DatabaseModelUsagePreparationService(DatabaseAiControlService control, DefaultPersonalModelPublicationService publications,
                                                PersonalModelRuntimeAdapter runtime, PersonalModelStore releases, ModelResourceStore models, ModelAclScopeStore scopes,
                                                AuthorizationService authorization, PublicTransactionRunner tx, IdentityProvider identity, CommandReceiptStore receipts,
                                                PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, Clock clock, Supplier<ModelGateway> gateway) {
        this.control = control;
        this.publications = publications;
        this.runtime = runtime;
        this.releases = releases;
        this.models = models;
        this.scopes = scopes;
        this.authorization = authorization;
        this.tx = tx;
        this.gateway = gateway;
        this.commandExecutor = new DefaultCommandExecutor(tx, new CurrentPrincipalResolver(identity), receipts, ids, clock);
        this.effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
    }

    private ModelResourceVersion requireModel(TrustedActor actor, Scope scope, ResourceRef resource, DefinitionRef binding) {
        binding.requireType("binding");
        if (binding.version().equalsIgnoreCase("latest") || !resource.tenantId().equals(scope.tenantId())) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_AI_USE, "model-usage"));
        authorization.requireInCurrentTransaction(actor, new AccessRequest(scope, resource, MODEL_USE, "model-usage"));
        // 查询模型发布版本
        ModelResourceVersion version = models.findCurrentBinding(scope.tenantId(), binding)
                .filter(value -> value.resource().equals(resource)).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        ModelResource model = models.findCurrent(resource).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (!Objects.equals(model.bindingId(), binding.id())) {
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        }
        return version;
    }

    private ModelUsagePreparation inspect(TrustedActor actor, Scope scope, ResourceRef resource, DefinitionRef binding,
                                          boolean prepare, String operationId) {
        ModelResourceVersion version = requireModel(actor, scope, resource, binding);
        ModelPublication publication = version.publication();
        String reason = null;
        if (publication.source() == ModelPublication.Source.PERSONAL) {
            PersonalModels.Release release = releases.find(scope, binding, true).filter(value -> value.resource().equals(resource))
                    .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
            if (!runtime.version(release, true).equals(version)) {
                throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            }
            reason = publications.readiness(actor, release, scope);
        } else {
            // 旧发布必须已明确切入公共模型资格；准备预算不替代或自动执行目录迁移。
            ModelAclScope manifest = scopes.findCurrent(scope).orElse(null);
            if (manifest == null || manifest.configurationSource() != AiEntitlement.Source.DATABASE
                    || !manifest.bindings().contains(binding)) {
                reason = "MODEL_NOT_MIGRATED";
            } else {
                NewAiProperties config = control.currentDefinitions();
                ResolvedBinding selected = config.bindings().stream().filter(value -> value.definition().equals(binding)).findFirst()
                        .orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
                ConnectionDefinition connection = config.connections().stream().filter(value -> value.definition().equals(selected.connection())).findFirst()
                        .orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
                NewAiProperties.Rate rate = config.rates().stream().filter(value -> value.definition().equals(selected.rate())).findFirst()
                        .orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
                if (connection.state() != ConnectionDefinition.State.ENABLED) {
                    reason = "CONNECTION_DISABLED";
                } else {
                    RateDefinition rateDefinition = new RateDefinition(rate.definition(), rate.inputPerMillion(), rate.outputPerMillion(), rate.toolPerCall());
                    PlatformModelAclAccess.validate(scope, new ModelAccessCandidate(selected, connection,
                            rateDefinition, config.releaseRef()), version, models.findCurrent(resource).orElseThrow());
                    if (gateway.get() == null || !gateway.get().supportsTextChat(selected, connection)) {
                        reason = "ADAPTER_UNAVAILABLE";
                    }
                }
            }
        }
        if (reason != null) {
            return new ModelUsagePreparation(scope, resource, binding, publication.rate().definition(),
                    ModelUsagePreparation.State.BLOCKED, reason, List.of(), null);
        }
        DatabaseAiControlService.UsageBudget budget = control.modelUsageBudget(actor, scope, publication.rate().definition(), prepare, operationId);
        return new ModelUsagePreparation(scope, resource, binding, publication.rate().definition(), budget.state(), budget.reason(), budget.budgetRefs(), budget.approvedLimit());
    }

    @Override
    public ModelUsagePreparation status(TrustedActor actor, Scope scope, ResourceRef resource, DefinitionRef binding) {
        return tx.required(() -> {
            publications.lock(actor, scope);
            return inspect(actor, scope, resource, binding, false, null);
        });
    }

    @Override
    public CommandReceipt<ModelUsagePreparation> prepare(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef resource, DefinitionRef binding) {
        return commandExecutor.execute(actor, meta, new CommandScope(scope.tenantId(), scope.workspaceId()), "model:usage:prepare",
                "model:usage:preparation:v1", ModelUsagePreparation.class,
                List.of(resource.tenantId(), resource.type().value(), resource.resourceId(), binding.type(), binding.id(), binding.version()),
                () -> publications.lock(actor, scope), saved -> {
                    ModelUsagePreparation current = inspect(actor, scope, resource, binding, false, null);
                    if (!saved.scope().equals(scope) || !saved.resource().equals(resource) || !saved.binding().equals(binding)
                            || !saved.rate().equals(current.rate()) || current.state() != ModelUsagePreparation.State.READY
                            || saved.state() != ModelUsagePreparation.State.READY || !saved.budgetRefs().equals(current.budgetRefs())) {
                        throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                    }
                }, operationId -> {
                    ModelUsagePreparation result = inspect(actor, scope, resource, binding, true, operationId);
                    if (result.state() != ModelUsagePreparation.State.READY) {
                        throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
                    }
                    effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), "model:usage:prepared", operationId, 0,
                            Map.of("resourceId", resource.resourceId(), "bindingId", binding.id(), "bindingVersion", binding.version()));
                    return new DefaultCommandExecutor.Result<>(result, 0);
                });
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "model-usage-preparation");
    }
}
