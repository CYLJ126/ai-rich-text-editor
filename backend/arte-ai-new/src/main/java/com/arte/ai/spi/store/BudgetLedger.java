package com.arte.ai.spi.store;

import com.arte.ai.model.budget.BudgetReservation;
import com.arte.base.model.identity.ExecutionScope;

/**
 * 原子账本；接受调用时须与执行受理在同一事务中预留，不在进程内假装权威限额。
 */
public interface BudgetLedger {
    BudgetReservation reservation(ExecutionScope scope, String executionId);
}
