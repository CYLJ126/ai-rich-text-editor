package com.arte.app.platform.persistence;

import com.arte.ainew.api.model.ModelUsagePreparationService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.model.ModelSelection;
import com.arte.ainew.pojo.model.ReviewedModelPlan;
import com.arte.app.platform.ai.AiControlData;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.ai.WorkspaceAiGovernanceService;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.constant.platform.PlatformActions;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.access.ResourceType;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 显式发布隔离测试数据
 * <p>
 * 生产代码不再提供从配置文件导入目录的路径。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 13:50 ✾
 */
final class DatabaseAiTestCatalog {

    private DatabaseAiTestCatalog() {
    }

    static CommandReceipt<AiControlData.CatalogView> publish(DatabaseAiControlService db, ApplicationContext context,
                                                             TrustedActor actor, CommandMeta meta) {
        return db.publish(actor, meta, 0, catalog(context.getBean(NewAiProperties.class), actor));
    }

    /**
     * 隔离测试显式准备空间；没有模型选择，也没有账户。
     */
    static AiControlData.WorkspaceView prepare(ApplicationContext context, TrustedActor actor, Scope scope) {
        WorkspaceAiGovernanceService governance = context.getBean(WorkspaceAiGovernanceService.class);
        AiControlData.WorkspaceView old = governance.get(actor, scope);
        if (old.configuration() != null || old.needsImport()) {
            return old;
        }
        return governance.prepare(actor, new CommandMeta(UUID.randomUUID().toString(), "fixture"), scope, old.workspaceVersion(),
                context.getBean(DatabaseAiControlService.class).catalog().version()).value();
    }

    /**
     * 旧执行边界回归显式建立已批准账户夹具；M3 闭环测试使用真实 prepareModelUsage。
     */
    static AiControlData.WorkspaceView prepareWithAccounts(ApplicationContext context, TrustedActor actor, Scope scope) {
        AiControlData.WorkspaceView view = prepare(context, actor, scope);
        if (view.configuration() == null) {
            return view;
        }
        PublicTransactionRunner tx = context.getBean(PublicTransactionRunner.class);
        tx.required(() -> {
            context.getBean(WorkspaceWriteGuard.class).inCurrentTransaction(actor,
                    List.of(DefaultAuthorizationService.workspaceRequest(scope, PlatformActions.WORKSPACE_AI_USE, "fixture")), () -> {
                        AiControlData.BudgetRules rules = context.getBean(WorkspaceAiGovernanceService.class).rules(scope, true);
                        ReflectionTestUtils.invokeMethod(context.getBean(DatabaseAiControlService.class), "prepareBudgets", scope,
                                actor.principal().subjectId(), new AiControlData.Preset(true, List.of(), rules.rules()), false);
                        return null;
                    });
            return null;
        });
        return context.getBean(WorkspaceAiGovernanceService.class).get(actor, scope);
    }

    static AiControlData.Preset preset(ApplicationContext context, AiControlData.WorkspaceConfiguration configuration) {
        return new AiControlData.Preset(configuration.enabled(), configuration.models().stream().map(ModelSelection::binding).toList(),
                context.getBean(WorkspaceAiGovernanceService.class).getRules(
                        context.getBean(RbacIdentityProvider.class).resolveAuthenticatedName("alice")
                                .map(principal -> new TrustedActor(principal, Instant.now())).orElseThrow(),
                        configuration.scope()).configuration().rules());
    }

    /**
     * 用真实治理接口选择资源，再用真实本人准备接口分配单一费率账户。
     */
    static void select(ApplicationContext context, TrustedActor actor, Scope scope, String resourceId, DefinitionRef binding) {
        WorkspaceAiGovernanceService governance = context.getBean(WorkspaceAiGovernanceService.class);
        AiControlData.WorkspaceView view = governance.get(actor, scope);
        List<ModelSelection> selections = new ArrayList<>(view.configuration().models());
        selections.removeIf(model -> model.resourceId().equals(resourceId));
        selections.add(new ModelSelection(resourceId, binding));
        governance.update(actor, new CommandMeta(UUID.randomUUID().toString(), "fixture-select"), scope,
                view.workspaceVersion(), view.configuration().version(), true, selections);
    }

    static void prepareOwn(ApplicationContext context, TrustedActor actor, Scope scope, String resourceId, DefinitionRef binding) {
        context.getBean(ModelUsagePreparationService.class).prepare(actor,
                new CommandMeta(UUID.randomUUID().toString(), "fixture-own"), scope,
                new ResourceRef(scope.tenantId(), new ResourceType("model"), resourceId), binding);
    }

    static AiControlData.Catalog catalog(NewAiProperties source, TrustedActor actor) {
        List<DefinitionRef> bindings = source.bindings().stream().map(ResolvedBinding::definition)
                .filter(definition -> source.grants().stream().anyMatch(g -> g.enabled()
                        && g.subjectId().equals(actor.principal().subjectId()) && g.bindingIds().contains(definition.id()))).toList();
        Set<String> approved = source.grants().stream().filter(g -> g.enabled() && g.subjectId().equals(actor.principal().subjectId()))
                .flatMap(g -> g.budgetRefs().stream()).collect(Collectors.toSet());
        Map<DefinitionRef, AiControlData.BudgetPreset> budgets = new LinkedHashMap<>();
        source.budgets().stream().filter(b -> approved.isEmpty() || approved.contains(b.budgetRef())).forEach(b -> budgets.merge(b.rate(),
                new AiControlData.BudgetPreset(b.rate(), b.limit()), (a, next) -> a.limit().amount().compareTo(next.limit().amount()) <= 0 ? a : next));
        return new AiControlData.Catalog(source.capabilities(), source.bindings(), source.connections(), source.rates(), source.grants(),
                source.budgets(), new AiControlData.Preset(!bindings.isEmpty(), bindings, List.copyOf(budgets.values())), Set.of(), source.bindings().stream()
                .filter(binding -> binding.capability().kind() == CapabilityDescriptor.Kind.GENERATION)
                .map(binding -> new ReviewedModelPlan(
                        new DefinitionRef("model-plan", binding.definition().id(), binding.definition().version()), binding.remoteOperation(),
                        binding.connection(), binding.remoteOperation(), binding.capability().features(), binding.contextWindowTokens(), binding.rate(),
                        Instant.parse("2026-01-01T00:00:00Z"), actor.principal().subjectId(), 1)).toList());
    }
}
