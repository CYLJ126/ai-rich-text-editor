package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.Money;

import java.util.Objects;

/**
 * 每个收费 Attempt 的唯一 Run 占用，与原 reservation 绑定；未知费用保留 quote，不按到期／取消释放。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 20:20 ✾
 */
public record ToolChatBudgetAllocation(String runId, String invocationId, String attemptId, int attemptNumber,
                                       String chargeKind, String budgetRef, DefinitionRef rate, String reservationId,
                                       Money quote,
                                       BudgetReservation.State state, Money charge, long version) {

    public ToolChatBudgetAllocation {
        ContractChecks.id(runId, "allocation Run");
        ContractChecks.id(invocationId, "allocation invocation");
        ContractChecks.id(attemptId, "allocation attempt");
        ContractChecks.range(attemptNumber, "allocation attempt number", 1, 10);
        ContractChecks.require("MODEL".equals(chargeKind) || "TOOL".equals(chargeKind), "Unknown charge kind");
        ContractChecks.id(budgetRef, "allocation budget");
        Objects.requireNonNull(rate).requireType("rate");
        ContractChecks.id(reservationId, "allocation reservation");
        Objects.requireNonNull(quote);
        Objects.requireNonNull(state);
        boolean finished = state == BudgetReservation.State.SETTLED || state == BudgetReservation.State.RELEASED;
        ContractChecks.require(finished == (charge != null), "Allocation charge certainty mismatch");
        ContractChecks.require(charge == null || charge.currency().equals(quote.currency()), "Allocation currency mismatch");
        ContractChecks.require(state != BudgetReservation.State.RELEASED || charge.amount().signum() == 0, "Released allocation must have zero charge");
        ContractChecks.range(version, "allocation version", 0, Long.MAX_VALUE - 1);
    }
}
