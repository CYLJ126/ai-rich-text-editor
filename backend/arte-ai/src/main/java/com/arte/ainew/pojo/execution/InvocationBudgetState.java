package com.arte.ainew.pojo.execution;

/**
 * 本次调用关联的预算预留状态
 * <p>
 * RESERVED 表示仍等待结算，待对账保留预留，不能当作零费用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 00:06 ✾
 */
public enum InvocationBudgetState {
    NOT_RESERVED, RESERVED, PENDING_RECONCILIATION, SETTLED, RELEASED;

    /**
     * 已取得本次结算结果；PENDING_RECONCILIATION 的费用仍未知，不能释放预算。
     */
    public boolean outcomeAvailable() {
        return this != RESERVED;
    }
}
