package com.arte.ai.execution;

import com.arte.ai.api.control.*;
import com.arte.ai.model.generation.*;
import com.arte.ai.model.execution.InvocationRequest;
import com.arte.ai.model.definition.*;
import com.arte.ai.model.capability.CapabilityKind;
import com.arte.base.execution.ExecutionFailures;
import com.arte.base.model.error.CommonErrorCode;

/**
 * 解析固定发布配置；绑定归属、操作范围和当前状态逐次检查。
 */
public final class ModelBindingResolver {
    private final CapabilityCatalog capabilities;
    private final ConnectionManager connections;
    private final BindingManager bindings;

    public ModelBindingResolver(CapabilityCatalog capabilities, ConnectionManager connections, BindingManager bindings) {
        this.capabilities = capabilities;
        this.connections = connections;
        this.bindings = bindings;
    }

    public ModelPlan resolve(InvocationRequest<GenerationRequest> request) {
        return resolve(request.context(), request.capabilityRef(), request.bindingRef());
    }

    public ModelPlan resolve(com.arte.base.model.execution.ExecutionContext context, DefinitionRef capabilityRef, DefinitionRef bindingRef) {
        var binding = bindings.find(context.scope(), bindingRef).orElseThrow(() -> ExecutionFailures.beforeStart(CommonErrorCode.UNAUTHORIZED, context, "binding"));
        var capability = capabilities.find(capabilityRef).orElseThrow(() -> ExecutionFailures.beforeStart(CommonErrorCode.NOT_FOUND, context, "capability"));
        var connection = connections.find(binding.connectionRef()).orElseThrow(() -> ExecutionFailures.beforeStart(CommonErrorCode.POLICY_UNAVAILABLE, context, "connection"));
        if (!"ai-capability".equals(capabilityRef.definitionType()) || !"ai-binding".equals(bindingRef.definitionType())
                || !"ai-connection".equals(connection.ref().definitionType()))
            throw ExecutionFailures.beforeStart(CommonErrorCode.INVALID_ARGUMENT, context, "definition");
        if (!binding.ref().equals(bindingRef) || !binding.scope().equals(context.scope())
                || !binding.capabilityRef().equals(capabilityRef) || !connection.ref().equals(binding.connectionRef())
                || !capability.descriptor().ref().equals(capabilityRef) || !binding.allowedOperations().contains("model.generate"))
            throw ExecutionFailures.beforeStart(CommonErrorCode.UNAUTHORIZED, context, "binding");
        if (binding.status() != DefinitionStatus.PUBLISHED || capability.status() != DefinitionStatus.PUBLISHED || connection.status() != DefinitionStatus.PUBLISHED)
            throw ExecutionFailures.beforeStart(CommonErrorCode.POLICY_UNAVAILABLE, context, "definition");
        if (capability.descriptor().kind() != CapabilityKind.MODEL)
            throw ExecutionFailures.beforeStart(CommonErrorCode.UNSUPPORTED, context, "model");
        return new ModelPlan(capability, binding, connection);
    }
}
