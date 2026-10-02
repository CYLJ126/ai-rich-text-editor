package com.arte.ai.model.budget;

import com.arte.base.model.resource.ResourceRef;

import java.math.BigDecimal;

/**
 * 预算原子预留的账本快照，独立于快速准入。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record BudgetReservation(
        String reservationId,
        ResourceRef budgetRef,
        String executionId,
        BigDecimal reservedAmount,
        String currency,
        BudgetStatus status
) {
}
