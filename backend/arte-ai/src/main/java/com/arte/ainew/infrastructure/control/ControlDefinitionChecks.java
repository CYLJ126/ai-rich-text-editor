package com.arte.ainew.infrastructure.control;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.*;
import java.util.function.Function;


/**
 * 发布／固定配置的兼容校验；不读取来源、不授予调用资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 13:00 ✾
 */
public final class ControlDefinitionChecks {

    public static final DefinitionRef INPUT_SCHEMA = new DefinitionRef("schema", "generation-request", "v1");
    public static final DefinitionRef OUTPUT_SCHEMA = new DefinitionRef("schema", "model-result", "v1");

    private ControlDefinitionChecks() {
    }

    public static void validate(NewAiProperties properties) {
        var capabilities = index(properties.capabilities(), CapabilityDescriptor::definition);
        var bindings = index(properties.bindings(), ResolvedBinding::definition);
        var connections = index(properties.connections(), ConnectionDefinition::definition);
        var rates = index(properties.rates(), NewAiProperties.Rate::definition);
        var budgets = index(properties.budgets(), NewAiProperties.Budget::budgetRef);
        for (var capability : capabilities.values()) {
            ContractChecks.require(capability.kind() == CapabilityDescriptor.Kind.GENERATION
                            && INPUT_SCHEMA.equals(capability.inputSchema()) && OUTPUT_SCHEMA.equals(capability.outputSchema()),
                    "Only registered text generation schemas are supported");
            ContractChecks.require(Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING,
                    CapabilityDescriptor.Feature.USAGE_REPORTING, CapabilityDescriptor.Feature.TOOL_CALLS).containsAll(capability.features())
                    && capability.features().contains(CapabilityDescriptor.Feature.TEXT_INPUT), "Unsupported advertised capability features");
        }
        for (var binding : bindings.values()) {
            ContractChecks.require(binding.target() instanceof com.arte.ainew.pojo.control.ExecutionTarget.Remote,
                    "Fixed generation catalog requires remote bindings");
            ContractChecks.require(binding.capability().equals(capabilities.get(binding.capability().definition()))
                    && connections.containsKey(binding.connection()) && binding.rate() != null && rates.containsKey(binding.rate()) && rates.get(binding.rate()).toolPerCall() == null, "Unresolved binding dependency");
        }
        for (var budget : budgets.values()) {
            ContractChecks.require(rates.containsKey(budget.rate())
                    && rates.get(budget.rate()).currency().equals(budget.limit().currency()), "Unresolved budget rate/currency");
        }
        for (var grant : properties.grants()) {
            ContractChecks.require(new HashSet<>(bindings.keySet().stream().map(DefinitionRef::id).toList()).containsAll(grant.bindingIds())
                    && budgets.keySet().containsAll(grant.budgetRefs()), "Grant refers to unregistered configuration");
            for (var budgetRef : grant.budgetRefs()) {
                ContractChecks.require(budgets.get(budgetRef).owner().equals(new ExecutionOwner(grant.tenantId(), grant.workspaceId(), grant.subjectId())),
                        "Grant budget owner mismatch");
            }
        }
    }

    private static <K, V> Map<K, V> index(List<V> values, Function<V, K> key) {
        var result = new HashMap<K, V>();
        for (var value : values) {
            ContractChecks.require(result.put(key.apply(value), value) == null, "Duplicate configuration definition");
        }
        return Map.copyOf(result);
    }
}
