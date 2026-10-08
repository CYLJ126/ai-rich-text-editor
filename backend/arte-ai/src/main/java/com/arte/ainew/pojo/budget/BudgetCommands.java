package com.arte.ainew.pojo.budget;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.ExecutionCommands;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public final class BudgetCommands {
    private BudgetCommands() {
    }

    /**
     * charged 可超过 limit：已发生的实际费用不能丢弃；available 可为负，此后拒绝新的预留。
     */
    public record Account(String budgetRef, ExecutionOwner owner, Money limit, Money held,
                          Money charged, DefinitionRef rateVersion, long version) {
        public Account {
            ContractChecks.id(budgetRef, "budgetRef");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(limit, "limit");
            Objects.requireNonNull(held, "held");
            Objects.requireNonNull(charged, "charged");
            ContractChecks.require(limit.currency().equals(held.currency()) && limit.currency().equals(charged.currency()),
                    "Account currency mismatch");
            Objects.requireNonNull(rateVersion, "rateVersion").requireType("rate");
            ContractChecks.range(version, "version", 0, Long.MAX_VALUE - 1);
        }

        public BigDecimal available() {
            return limit.amount().subtract(held.amount()).subtract(charged.amount());
        }
    }

    /**
     * 一次 Attempt 最多一个预留；预留与 Attempt 关联写入同事务，并增加 Attempt.version。
     */
    public record Reserve(ExecutionCommands.Guard guard, String reservationId, Money amount,
                          DefinitionRef rateVersion, Duration retention) {
        public Reserve {
            Objects.requireNonNull(guard, "guard");
            ContractChecks.id(reservationId, "reservationId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(rateVersion, "rateVersion").requireType("rate");
            Objects.requireNonNull(retention, "retention");
            ContractChecks.require(retention.compareTo(Duration.ofSeconds(1)) >= 0
                    && retention.compareTo(Duration.ofDays(30)) <= 0, "Invalid reservation retention");
        }
    }

    public enum Evidence {UNKNOWN_COST, PROVIDER_BILL, MANUAL_PROVIDER_BILL, PROVEN_NOT_DISPATCHED, PROVEN_NO_EXECUTION}

    /**
     * 预留版本作 CAS；最终结算不可覆盖。普通客户端不能自报费用；
     * 自动证据由可信服务解析，人工账单输入须经预算管理员核实并保存凭据与审计。
     */
    public record Settle(ExecutionOwner owner, long expectedReservationVersion, BudgetSettlement settlement,
                         Evidence evidence, String evidenceRef) {
        public Settle {
            Objects.requireNonNull(owner, "owner");
            ContractChecks.range(expectedReservationVersion, "expectedReservationVersion", 0, Long.MAX_VALUE - 1);
            Objects.requireNonNull(settlement, "settlement");
            Objects.requireNonNull(evidence, "evidence");
            ContractChecks.optionalId(evidenceRef, "evidenceRef");
            ContractChecks.require(settlement.state() == BudgetSettlement.State.PENDING_RECONCILIATION
                    ? evidence == Evidence.UNKNOWN_COST
                    : evidence != Evidence.UNKNOWN_COST && evidenceRef != null, "Settlement requires matching evidence");
            ContractChecks.require((evidence != Evidence.PROVEN_NOT_DISPATCHED && evidence != Evidence.PROVEN_NO_EXECUTION)
                    || settlement.state() == BudgetSettlement.State.RELEASED, "No-dispatch evidence is only for release");
        }
    }
}
