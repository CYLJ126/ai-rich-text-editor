package com.arte.ainew.pojo.budget;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 单次 Attempt 的权威预算预留
 * <p>
 * 预留身份唯一，快照不证明账本已经提交，过期不自动证明可释放费用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record BudgetReservation(String reservationId, String budgetRef, String invocationId,
                                String attemptId, Money reserved, DefinitionRef rateVersion, State state,
                                long version, Instant reservedAt, Instant expiresAt) implements Serializable {
    public enum State {RESERVED, PENDING_RECONCILIATION, SETTLED, RELEASED}

    public BudgetReservation {
        ContractChecks.id(reservationId, "reservationId");
        ContractChecks.id(budgetRef, "budgetRef");
        ContractChecks.id(invocationId, "invocationId");
        ContractChecks.id(attemptId, "attemptId");
        Objects.requireNonNull(reserved, "reserved");
        Objects.requireNonNull(rateVersion, "rateVersion").requireType("rate");
        Objects.requireNonNull(state, "state");
        ContractChecks.range(version, "version", 0, Long.MAX_VALUE);
        ContractChecks.require(Objects.requireNonNull(expiresAt, "expiresAt")
                .isAfter(Objects.requireNonNull(reservedAt, "reservedAt")), "Reservation expiry must follow reservation");
    }
}
