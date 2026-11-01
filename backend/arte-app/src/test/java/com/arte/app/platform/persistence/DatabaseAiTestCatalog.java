package com.arte.app.platform.persistence;

import com.arte.ainew.config.NewAiProperties;
import com.arte.app.platform.ai.AiControlData;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 显式发布隔离测试数据；生产代码不再提供从配置文件导入目录的路径。
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

    static AiControlData.Catalog catalog(NewAiProperties source, TrustedActor actor) {
        var bindings = source.bindings().stream().filter(b -> source.grants().stream().anyMatch(g -> g.enabled()
                        && g.subjectId().equals(actor.principal().subjectId()) && g.bindingIds().contains(b.definition().id())))
                .map(com.arte.ainew.pojo.control.ResolvedBinding::definition).toList();
        var approved = source.grants().stream().filter(g -> g.enabled() && g.subjectId().equals(actor.principal().subjectId()))
                .flatMap(g -> g.budgetRefs().stream()).collect(java.util.stream.Collectors.toSet());
        var budgets = new LinkedHashMap<DefinitionRef, AiControlData.BudgetPreset>();
        source.budgets().stream().filter(b -> approved.isEmpty() || approved.contains(b.budgetRef())).forEach(b -> budgets.merge(b.rate(),
                new AiControlData.BudgetPreset(b.rate(), b.limit()), (a, next) -> a.limit().amount().compareTo(next.limit().amount()) <= 0 ? a : next));
        return new AiControlData.Catalog(source.capabilities(), source.bindings(), source.connections(), source.rates(), source.grants(),
                source.budgets(), new AiControlData.Preset(!bindings.isEmpty(), bindings, List.copyOf(budgets.values())));
    }
}
