package com.arte.ai.model.action;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.execution.ExecutionOptions;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.model.message.Message;
import com.arte.base.model.execution.IdempotencyKey;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;
import java.util.List;

/**
 * 固定动作输入及版本；执行状态由统一模型账本提供，采纳由业务领域负责。
 */
public record AiActionExecution(
        String actionExecutionId,
        ExecutionScope scope,
        DefinitionRef actionRef,
        DefinitionRef capabilityRef,
        DefinitionRef bindingRef,
        List<Message> input,
        ModelOptions modelOptions,
        ExecutionOptions executionOptions,
        String regeneratesActionId,
        IdempotencyKey submissionKey,
        Instant createdAt
) {
    public AiActionExecution {
        actionExecutionId = ContractChecks.identifier(actionExecutionId, "actionExecutionId");
        scope = ContractChecks.required(scope, "scope");
        actionRef = ContractChecks.required(actionRef, "actionRef");
        capabilityRef = ContractChecks.required(capabilityRef, "capabilityRef");
        bindingRef = ContractChecks.required(bindingRef, "bindingRef");
        input = List.copyOf(ContractChecks.required(input, "input"));
        if (input.isEmpty() || input.size() > 128) throw new IllegalArgumentException("invalid action input");
        modelOptions = ContractChecks.required(modelOptions, "modelOptions");
        executionOptions = ContractChecks.required(executionOptions, "executionOptions");
        submissionKey = ContractChecks.required(submissionKey, "submissionKey");
        createdAt = ContractChecks.required(createdAt, "createdAt");
        if (regeneratesActionId != null) ContractChecks.identifier(regeneratesActionId, "regeneratesActionId");
    }
}
