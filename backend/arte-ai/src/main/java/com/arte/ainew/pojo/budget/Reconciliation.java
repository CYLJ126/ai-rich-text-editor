package com.arte.ainew.pojo.budget;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.Invocation;

import java.time.Instant;
import java.util.Objects;

/**
 * 账单人工核对契约；只能由重新授权的预算管理员组装，所有金额使用十进制。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 10:20 ✾
 */
public final class Reconciliation {
    private Reconciliation() {
    }

    public record Pending(String invocationId, String conversationId, Invocation.State state, long invocationVersion,
                          String reservationId, long reservationVersion, String budgetRef, Money reserved,
                          String remoteRequestId, Instant acceptedAt) {
    }

    public record Confirm(ExecutionOwner owner, ExecutionPrincipal reviewer, String traceId, String key,
                          String budgetRef, String invocationId, long invocationVersion, String reservationId,
                          long reservationVersion,
                          Money charge, String evidenceRef, String note, boolean executionEnded) {
        public Confirm {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(reviewer);
            Objects.requireNonNull(charge);
            ContractChecks.require(owner.subjectId().equals(reviewer.subjectId()), "Reviewer must be current owner");
            ContractChecks.id(traceId, "traceId");
            ContractChecks.id(key, "key");
            ContractChecks.id(budgetRef, "budgetRef");
            ContractChecks.id(invocationId, "invocationId");
            ContractChecks.id(reservationId, "reservationId");
            ContractChecks.range(invocationVersion, "invocationVersion", 0, Long.MAX_VALUE - 1);
            ContractChecks.range(reservationVersion, "reservationVersion", 0, Long.MAX_VALUE - 1);
            ContractChecks.id(evidenceRef, "evidenceRef");
            ContractChecks.text(note, "note", 2000);
            ContractChecks.require(executionEnded, "Bill verification must confirm execution has ended");
        }
    }

    /**
     * 不可变核对回执，与费用、执行收敛、门闩及事件在同一事务保存。
     */
    public record Receipt(String key, String invocationId, String reservationId, String budgetRef, Money charge,
                          String evidenceRef, String note, ExecutionPrincipal reviewer, String traceId,
                          Invocation.State state, long invocationVersion, long reservationVersion,
                          Instant confirmedAt) {
    }
}
