package com.arte.ai.model.definition;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.validation.ContractChecks;
import java.util.Set;

/**
 * 使用配置快照，不替代当前主体／任务授权。
 */
public record BindingDefinition(DefinitionRef ref, ExecutionScope scope, DefinitionRef capabilityRef,
                                DefinitionRef connectionRef, Set<String> allowedOperations, DefinitionStatus status) {
    public BindingDefinition {
        ref = ContractChecks.required(ref, "ref");
        scope = ContractChecks.required(scope, "scope");
        capabilityRef = ContractChecks.required(capabilityRef, "capabilityRef");
        connectionRef = ContractChecks.required(connectionRef, "connectionRef");
        allowedOperations = ContractChecks.identifiers(allowedOperations, "allowedOperations");
        status = ContractChecks.required(status, "status");
    }
}
