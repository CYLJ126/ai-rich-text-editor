package com.arte.app.ainew;

import com.arte.ai.model.definition.*;
import com.arte.ai.spi.store.ModelDefinitionStore;
import com.arte.base.model.identity.ExecutionScope;

import java.util.Optional;
import java.util.Set;

/**
 * 首个受控默认模型配置；不复用旧模型表或自动发布任意客户端配置。
 */
public final class ConfiguredModelDefinitions implements ModelDefinitionStore {
    private final CapabilityDefinition capability;
    private final ConnectionDefinition connection;
    private final DefinitionRef binding;
    private final String tenant, workspace;

    public ConfiguredModelDefinitions(CapabilityDefinition capability, ConnectionDefinition connection, DefinitionRef binding, String tenant, String workspace) {
        this.capability = capability;
        this.connection = connection;
        this.binding = binding;
        this.tenant = tenant;
        this.workspace = workspace;
    }

    public Optional<CapabilityDefinition> capability(DefinitionRef ref) {
        return capability.descriptor().ref().equals(ref) ? Optional.of(capability) : Optional.empty();
    }

    public Optional<ConnectionDefinition> connection(DefinitionRef ref) {
        return connection.ref().equals(ref) ? Optional.of(connection) : Optional.empty();
    }

    public Optional<BindingDefinition> binding(ExecutionScope scope, DefinitionRef ref) {
        if (!binding.equals(ref) || !tenant.equals(scope.tenantId()) || !workspace.equals(scope.workspaceId()))
            return Optional.empty();
        return Optional.of(new BindingDefinition(binding, scope, capability.descriptor().ref(), connection.ref(), Set.of("model.generate"), DefinitionStatus.PUBLISHED));
    }

    public DefinitionRef capabilityRef() {
        return capability.descriptor().ref();
    }

    public DefinitionRef bindingRef() {
        return binding;
    }

    /**
     * 当前固定模型覆盖的作用域；调用方仍须验证成员和应用许可。
     */
    public ExecutionScope configuredScope(com.arte.base.model.identity.PrincipalRef principal) {
        return new ExecutionScope(tenant, workspace, principal);
    }
}
