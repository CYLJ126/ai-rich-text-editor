package com.arte.ai.model.execution;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.generation.ModelResult;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.identity.ExecutionScope;

import java.time.Instant;

/**
 * 最小模型 Invocation／Attempt 的耐久查询快照；当前每次调用只执行一次尝试。
 */
public record ModelExecution(String executionId, String attemptId, ExecutionScope scope,
                             DefinitionRef capabilityRef, DefinitionRef bindingRef, DefinitionRef connectionRef,
                             ExecutionStatus status, long revision, boolean dispatched, Instant acceptedAt,
                             ModelResult result, ExecutionError error) {
}
