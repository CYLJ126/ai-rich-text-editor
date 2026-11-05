package com.arte.app.platform.ai;

import com.arte.ainew.api.model.ModelUsagePreparationService;
import com.arte.ainew.application.model.DefaultModelPublicationService;
import com.arte.ainew.pojo.model.*;
import com.arte.ainew.spi.model.ModelPublicationStore;
import com.arte.ainew.spi.model.ModelResourceStore;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.arte.ainew.pojo.model.ModelUsageChecks.Check.evaluated;
import static com.arte.ainew.pojo.model.ModelUsageChecks.Check.unavailable;
import static com.arte.ainew.pojo.model.ModelUsageChecks.Condition.*;
import static com.arte.core.constant.platform.PlatformActions.*;

/**
 * DATABASE 模型使用准备。公共父范围门控串行化预算准备、撤权、政策修改和模型停用；
 * 模型资格、单费率账户、审计、Outbox 及成功回执在同一物理事务内提交，不调用供应商。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/02 21:30 ✾
 */
public final class DatabaseModelUsagePreparationService implements ModelUsagePreparationService {

    private final DatabaseAiControlService control;
    private final WorkspaceAiGovernanceService governance;
    private final DefaultModelPublicationService publications;
    private final ModelPublicationRuntimeAdapter runtime;
    private final ModelPublicationStore releases;
    private final ModelResourceStore models;
    private final AuthorizationService authorization;
    private final PublicTransactionRunner tx;
    private final DefaultCommandExecutor commandExecutor;
    private final WorkspaceMutationEffects effects;

    public DatabaseModelUsagePreparationService(DatabaseAiControlService control, WorkspaceAiGovernanceService governance, DefaultModelPublicationService publications,
                                                ModelPublicationRuntimeAdapter runtime, ModelPublicationStore releases, ModelResourceStore models,
                                                AuthorizationService authorization, PublicTransactionRunner tx, IdentityProvider identity, CommandReceiptStore receipts,
                                                PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, Clock clock) {
        this.control = control;
        this.governance = governance;
        this.publications = publications;
        this.runtime = runtime;
        this.releases = releases;
        this.models = models;
        this.authorization = authorization;
        this.tx = tx;
        this.commandExecutor = new DefaultCommandExecutor(tx, new CurrentPrincipalResolver(identity), receipts, ids, clock);
        this.effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
    }

    private ModelResourceVersion requireModel(TrustedActor actor, Scope scope, ResourceRef resource, DefinitionRef binding) {
        validateTarget(scope, resource, binding);
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_AI_USE, "model-usage"));
        authorization.requireInCurrentTransaction(actor, new AccessRequest(scope, resource, MODEL_USE, "model-usage"));
        return exactVersion(scope, resource, binding);
    }

    private static void validateTarget(Scope scope, ResourceRef resource, DefinitionRef binding) {
        binding.requireType("binding");
        if (binding.version().equalsIgnoreCase("latest") || !resource.tenantId().equals(scope.tenantId())) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
    }

    private ModelResourceVersion exactVersion(Scope scope, ResourceRef resource, DefinitionRef binding) {
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
        if (publication.source() == ModelPublication.Source.RESOURCE) {
            ModelPublications.Release release = releases.find(scope, binding, true).filter(value -> value.resource().equals(resource))
                    .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
            if (!runtime.version(release, true).equals(version)) {
                throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            }
            reason = governance.selectionBlocker(scope, resource.resourceId(), binding);
            if (reason == null) {
                reason = publications.readiness(actor, release, scope);
            }
        } else {
            reason = "RESOURCE_PUBLICATION_REQUIRED";
        }
        if (reason != null) {
            return new ModelUsagePreparation(scope, resource, binding, publication.rate().definition(),
                    ModelUsagePreparation.State.BLOCKED, reason, List.of(), null, governance.usageActions(actor, scope, reason));
        }
        DatabaseAiControlService.UsageBudget budget = control.modelUsageBudget(actor, scope, publication.rate().definition(), prepare, operationId);
        return new ModelUsagePreparation(scope, resource, binding, publication.rate().definition(), budget.state(), budget.reason(), budget.budgetRefs(), budget.approvedLimit(), governance.usageActions(actor, scope, budget.reason()));
    }

    @Override
    public ModelUsagePreparation status(TrustedActor actor, Scope scope, ResourceRef resource, DefinitionRef binding) {
        return tx.required(() -> {
            publications.lock(actor, scope);
            return inspect(actor, scope, resource, binding, false, null);
        });
    }

    @Override
    public ModelUsageChecks checks(TrustedActor actor, Scope scope, ResourceRef resource, DefinitionRef binding) {
        validateTarget(scope, resource, binding);
        return tx.required(() -> {
            publications.lock(actor, scope);
            authorization.requireInCurrentTransaction(actor,
                    DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "model-usage-checks"));
            boolean modelUse = allowed(actor, new AccessRequest(scope, resource, MODEL_USE, "model-usage-checks"));
            if (!modelUse && !allowed(actor, new AccessRequest(scope, resource, MODEL_READ, "model-usage-checks"))) {
                throw error(PlatformErrorCodeEnum.NOT_FOUND);
            }
            boolean spaceUse = allowed(actor,
                    DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_AI_USE, "model-usage-checks"));
            ModelResourceVersion version = exactVersion(scope, resource, binding);
            return projectChecks(actor, scope, version, spaceUse, modelUse);
        });
    }

    private ModelUsageChecks projectChecks(TrustedActor actor, Scope scope, ModelResourceVersion version,
                                           boolean spaceUse, boolean modelUse) {
        ModelPublication publication = version.publication();
        ResourceRef resource = version.resource();
        DefinitionRef binding = publication.binding().definition();
        DefinitionRef rate = publication.rate().definition();
        boolean managed = publication.source() == ModelPublication.Source.RESOURCE;
        ModelPublications.Release release = managed ? releases.find(scope, binding, true)
                .filter(value -> value.resource().equals(resource)).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND)) : null;
        if (release != null) {
            publications.lock(actor, release.scope());
            if (!runtime.version(release, true).equals(version)) {
                throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            }
        }
        String publicationBlocker = managed ? publications.publicationReadiness(actor, release) : "RESOURCE_PUBLICATION_REQUIRED";
        String selection = governance.selectionBlocker(scope, resource.resourceId(), binding);
        String rule = managed ? control.modelUsageRuleBlocker(scope, rate) : null;
        boolean publicationReady = publicationBlocker == null;
        String adapter = publicationReady ? publications.adapterBlocker(release) : null;
        String policy = publicationReady ? publications.executionPolicyBlocker(release, scope) : null;
        boolean checkedCredential = publicationReady && spaceUse && modelUse;
        String credential = checkedCredential ? publications.credentialUseBlocker(actor, release, scope) : null;

        boolean checkBudget = checkedCredential && credential == null && adapter == null && policy == null
                && selection == null && rule == null;
        String budgetBlocker = null;
        if (checkBudget) {
            DatabaseAiControlService.UsageBudget budget = control.modelUsageBudget(actor, scope, rate, false, null);
            if (budget.state() != ModelUsagePreparation.State.READY) {
                budgetBlocker = budget.reason();
            }
        }
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        for (String reason : new String[]{selection, rule, budgetBlocker}) {
            if (reason != null) {
                actions.addAll(governance.usageActions(actor, scope, reason));
            }
        }
        return new ModelUsageChecks(scope, resource, binding, List.of(
                evaluated(PUBLICATION, publicationBlocker),
                publicationReady ? evaluated(RUNTIME_ADAPTER, adapter) : unavailable(RUNTIME_ADAPTER, managed),
                publicationReady ? evaluated(EXECUTION_POLICY, policy) : unavailable(EXECUTION_POLICY, managed),
                evaluated(WORKSPACE_SELECTION, selection),
                managed ? evaluated(SPACE_BUDGET_RULE, rule) : unavailable(SPACE_BUDGET_RULE, false)
        ), List.of(
                evaluated(WORKSPACE_AI_ACCESS, spaceUse ? null : "WORKSPACE_AI_ACCESS_DENIED"),
                evaluated(MODEL_ACCESS, modelUse ? null : "MODEL_USE_DENIED"),
                checkedCredential ? evaluated(CREDENTIAL_USE, credential) : unavailable(CREDENTIAL_USE, managed),
                checkBudget ? evaluated(OWN_BUDGET, budgetBlocker) : unavailable(OWN_BUDGET, managed)
        ), List.copyOf(actions));
    }

    private boolean allowed(TrustedActor actor, AccessRequest request) {
        try {
            authorization.requireInCurrentTransaction(actor, request);
            return true;
        } catch (PlatformException denied) {
            return switch (denied.getCode()) {
                case ACCESS_DENIED, POLICY_DENIED, NOT_FOUND, SPACE_READ_ONLY, SPACE_CLOSING -> false;
                default -> throw denied;
            };
        }
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
