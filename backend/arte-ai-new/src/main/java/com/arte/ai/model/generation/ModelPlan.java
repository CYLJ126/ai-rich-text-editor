package com.arte.ai.model.generation;

import com.arte.ai.model.definition.BindingDefinition;
import com.arte.ai.model.definition.CapabilityDefinition;
import com.arte.ai.model.definition.ConnectionDefinition;
import com.arte.base.model.security.EgressDestination;

import java.net.URI;

/**
 * 解析后的固定配置，仅在服务端执行链使用。
 */
public record ModelPlan(CapabilityDefinition capability, BindingDefinition binding, ConnectionDefinition connection) {
    public ModelPlan {
        java.util.Objects.requireNonNull(capability);
        java.util.Objects.requireNonNull(binding);
        java.util.Objects.requireNonNull(connection);
    }

    public EgressDestination destination() {
        try {
            URI endpoint = connection.endpoint();
            return new EgressDestination(connection.ref().resource(), new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(), null, null, null));
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException("invalid connection origin");
        }
    }
}
