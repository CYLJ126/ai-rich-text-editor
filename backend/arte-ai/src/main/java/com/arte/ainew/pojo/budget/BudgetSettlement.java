package com.arte.ainew.pojo.budget;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.Usage;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 结算事实；settlementKey 与 reservationId 原子防重，同键不同内容拒绝，对账修订使用新结算键。
 * null charge 表示费用未知，只能待对账；取消或超时不能自动按零结算或释放。
 * 币种一致性、预留余额及最终结算唯一性由账本事务校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record BudgetSettlement(String settlementKey, String reservationId, State state,
                               Usage usage, Money charge, Instant recordedAt) implements Serializable {
    public enum State {PENDING_RECONCILIATION, SETTLED, RELEASED}

    public BudgetSettlement {
        ContractChecks.id(settlementKey, "settlementKey");
        ContractChecks.id(reservationId, "reservationId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(recordedAt, "recordedAt");
        ContractChecks.require(state == State.PENDING_RECONCILIATION || charge != null,
                "Unknown charge requires reconciliation");
        ContractChecks.require(state != State.RELEASED || charge.amount().signum() == 0,
                "Release requires known zero charge");
    }
}
