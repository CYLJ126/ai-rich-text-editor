package com.arte.ai.spi.store;

import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.execution.ModelSubmission;
import com.arte.ai.model.execution.QueuedModelCall;
import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.model.identity.ExecutionScope;

/**
 * 受理、预算预占与工作登记须同事务；消费与恢复实现必须使用租约及写入围栏。
 */
public interface ModelWorkQueue {
    ExecutionStore.Acceptance accept(ModelSubmission submission, BudgetQuote quote, QueuedModelCall call);

    CancellationStatus requestCancellation(ExecutionScope scope, String executionId);
}
