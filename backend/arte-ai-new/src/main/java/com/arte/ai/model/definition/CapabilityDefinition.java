package com.arte.ai.model.definition;
import com.arte.ai.model.capability.CapabilityDescriptor;
import com.arte.base.validation.ContractChecks;

/**
 * 能力定义快照。
 */
public record CapabilityDefinition(CapabilityDescriptor descriptor, DefinitionStatus status) {
    public CapabilityDefinition {
        descriptor = ContractChecks.required(descriptor, "descriptor");
        status = ContractChecks.required(status, "status");
    }
}
