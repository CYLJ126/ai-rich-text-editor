package com.arte.ai.model.execution;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.validation.ContractChecks;

/**
 * 类型化调用信封；只由已认证入口构造上下文。
 */
public record InvocationRequest<I>(DefinitionRef capabilityRef, DefinitionRef bindingRef, I input,
                                   ExecutionOptions options, ExecutionContext context) {
    public InvocationRequest {
        capabilityRef = ContractChecks.required(capabilityRef, "capabilityRef");
        bindingRef = ContractChecks.required(bindingRef, "bindingRef");
        input = ContractChecks.required(input, "input");
        options = ContractChecks.required(options, "options");
        context = ContractChecks.required(context, "context");
    }
}
