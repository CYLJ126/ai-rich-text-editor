package com.arte.ai.model.budget;

/**
 * AI 预算预留与结算状态；未知费用保持待对账。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum BudgetStatus {
    RESERVED,
    SETTLED,
    PENDING_RECONCILIATION,
    RELEASED
}
