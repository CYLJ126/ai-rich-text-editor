package com.arte.ainew.application.tool.chat;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.binding.ToolBindingConstraints;
import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.pojo.tool.ToolSetSnapshot;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.platform.Scope;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 显式绑定选择到固定工具集合；每次订阅重新授权及检查可用性，不创建 Run 或调用工具。
 * LIST 资格只允许选择元数据；实际子调用仍须经过 TOOL 准入、资源授权和审批。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 15:20 ✾
 */
public final class ToolSetService {

    public record Selection(String bindingId, ToolExecutionPolicy restriction) {
        public Selection {
            ContractChecks.id(bindingId, "bindingId");
        }
    }

    private final ToolBindingService bindings;
    private final ToolCatalogStore catalog;
    private final Scheduler scheduler;
    private final Duration timeout;
    private final ToolSchemaValidator schemas = new ToolSchemaValidator();

    public ToolSetService(ToolBindingService bindings, ToolCatalogStore catalog, Scheduler scheduler, Duration timeout) {
        this.bindings = Objects.requireNonNull(bindings);
        this.catalog = Objects.requireNonNull(catalog);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.timeout = Objects.requireNonNull(timeout);
        ContractChecks.require(!timeout.isNegative() && !timeout.isZero(), "Tool selection timeout must be positive");
    }

    public Mono<ToolSetSnapshot> prepare(ToolManagementAccess.Session session, Scope scope, List<Selection> selected) {
        var selections = ContractChecks.list(selected, "selected bindings", 1, ContractChecks.MAX_TOOLS);
        ContractChecks.unique(selections.stream().map(Selection::bindingId).toList(), "selected binding IDs");
        Objects.requireNonNull(session);
        Objects.requireNonNull(scope);
        return Mono.defer(() -> session.authorize(scope, ToolManagementAccess.Operation.LIST))
                .switchIfEmpty(Mono.error(new AccessDeniedException("Tool selection denied")))
                .flatMap(permit -> {
                    var owner = permit.owner();
                    if (permit.operation() != ToolManagementAccess.Operation.LIST || !owner.tenantId().equals(scope.tenantId())
                            || !owner.workspaceId().equals(scope.workspaceId()) || !owner.subjectId().equals(permit.actor().principal().subjectId()))
                        return Mono.error(new AccessDeniedException("Tool selection scope mismatch"));
                    return Flux.range(0, selections.size()).concatMap(index -> {
                        var selection = selections.get(index);
                        return bindings.resolve(owner, selection.bindingId()).flatMap(outcome -> {
                            if (!outcome.successful())
                                return Mono.error(new AdmissionException(ResultCodeEnum.AI_TOOL_NOT_AVAILABLE));
                            var binding = outcome.value();
                            if (!binding.owner().equals(owner) || !permit.executionScopes().containsAll(binding.tool().tool().risk().requiredScopes()))
                                return Mono.error(new AccessDeniedException("Tool selection scopes denied"));
                            return catalog.findSchema(binding.tool().tool().inputSchema())
                                    .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_TOOL_NOT_AVAILABLE)))
                                    .publishOn(scheduler).map(schema -> {
                                        ContractChecks.require(schema.checksum().equals(ToolCatalogDigests.schema(schema.document())), "Schema checksum mismatch");
                                        schemas.compile(schema.definition(), schema.document(), ToolSchemaValidator.Purpose.INPUT);
                                        var policy = ToolBindingConstraints.intersect(binding.effectivePolicy(), selection.restriction());
                                        return new ToolSetSnapshot.Entry("tool_" + index, binding, policy, schema);
                                    });
                        }).switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_TOOL_NOT_AVAILABLE)));
                    }, 1).collectList().map(entries -> new ToolSetSnapshot(owner, entries));
                }).timeout(timeout);
    }
}
