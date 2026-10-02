package com.arte.ai.api.execution;

import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.budget.BudgetReservation;
import com.arte.ai.spi.store.BudgetLedger;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.validation.ContractChecks;

/**
 * 最小预算入口：固定单次上限预留，结算由存储与终态提交原子组合。未知用量保持待对账。
 */
public class BudgetService {
    private final BudgetQuote quote;
    private final BudgetLedger ledger;

    public BudgetService(BudgetQuote quote, BudgetLedger ledger) {
        this.quote = ContractChecks.required(quote, "quote");
        this.ledger = ContractChecks.required(ledger, "ledger");
    }

    public BudgetQuote quote() {
        return quote;
    }

    public BudgetReservation find(ExecutionScope scope, String executionId) {
        return ledger.reservation(scope, executionId); }
}
