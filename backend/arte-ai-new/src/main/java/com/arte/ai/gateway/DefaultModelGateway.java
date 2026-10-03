package com.arte.ai.gateway;

import com.arte.ai.api.gateway.ModelGateway;
import com.arte.ai.model.generation.GenerationRequest;
import com.arte.ai.model.generation.ModelPlan;
import com.arte.ai.model.generation.PreparedModelCall;
import com.arte.ai.spi.adapter.ProviderAdapter;

import java.util.List;

/**
 * 按适配能力选择唯一提供者，未知／重复提供者明确拒绝。
 */
public final class DefaultModelGateway implements ModelGateway {
    private final List<ProviderAdapter> adapters;

    public DefaultModelGateway(List<ProviderAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    public PreparedModelCall prepare(ModelPlan plan, GenerationRequest request) {
        return prepare(plan, request, new com.arte.ai.model.execution.ExecutionOptions(java.time.Duration.ofSeconds(90), false));
    }

    public PreparedModelCall prepare(ModelPlan plan, GenerationRequest request, com.arte.ai.model.execution.ExecutionOptions options) {
        var candidates = adapters.stream().filter(adapter -> adapter.supports(plan)).toList();
        if (candidates.size() != 1) throw new IllegalArgumentException("exactly one model adapter is required");
        return candidates.getFirst().prepare(plan, request, options);
    }
}
