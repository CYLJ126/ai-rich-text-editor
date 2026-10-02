package com.arte.ai.spi.store;

import com.arte.ai.model.execution.*;
import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.generation.ModelResult;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.identity.ExecutionScope;

import java.util.Optional;

/**
 * 模型受理／尝试权威存储。受理与预算预留、终态与最终结果／事件／结算各须同事务提交。
 */
public interface ExecutionStore {
    record Acceptance(ModelExecution execution, boolean created) {
    }

    Acceptance accept(ModelSubmission submission, BudgetQuote quote);

    Optional<ModelExecution> findIdempotent(ExecutionScope scope, String key, String digest);

    Optional<ModelExecution> find(ExecutionScope scope, String executionId);

    boolean start(ExecutionScope scope, String executionId);

    void markDispatched(ExecutionScope scope, String executionId);

    void finish(ExecutionScope scope, String executionId, ExecutionStatus status, ModelResult result, ExecutionError error);
}
