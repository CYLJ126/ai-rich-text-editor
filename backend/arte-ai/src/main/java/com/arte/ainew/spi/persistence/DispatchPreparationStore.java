package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.execution.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * 同一物理库内的原子派发准备；事务提交后才允许调用模型，不接管可能已发送的 Attempt。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 18:00 ✾
 */
public interface DispatchPreparationStore {

    /**
     * 领取身份、执行版本和预算参数来自可信应用层，不能绑定客户端请求。
     * Attempt 序号和 fencing 仍由数据库分配。
     */
    record Command(ExecutionCommands.Version invocation, OutboxMessage claim, String attemptId,
                   Duration attemptLease, Money amount, DefinitionRef rate, Duration retention) {
        public Command {
            Objects.requireNonNull(invocation);
            Objects.requireNonNull(claim);
            ContractChecks.require(claim.kind() == OutboxMessage.Kind.DISPATCH
                            && claim.invocationId().equals(invocation.invocationId()) && claim.owner().equals(invocation.owner()),
                    "Dispatch claim does not match invocation");
            ContractChecks.id(attemptId, "attemptId");
            ExecutionCommands.validLease(attemptLease);
            Objects.requireNonNull(amount);
            Objects.requireNonNull(rate).requireType("rate");
            Objects.requireNonNull(retention);
            ContractChecks.require(retention.compareTo(Duration.ofSeconds(1)) >= 0
                    && retention.compareTo(Duration.ofDays(30)) <= 0, "Invalid reservation retention");
        }
    }

    /**
     * ready=false 仅表示预算不足；保留未发送的 Attempt，供应用提交已知失败终态。
     */
    record PreparedDispatch(Invocation invocation, Attempt attempt, boolean ready) {
        public PreparedDispatch {
            Objects.requireNonNull(invocation);
            Objects.requireNonNull(attempt);
            ContractChecks.require(attempt.attemptId().equals(invocation.activeAttemptId())
                    && attempt.invocationId().equals(invocation.request().context().executionId()), "Attempt mismatch");
            ContractChecks.require(ready ? attempt.dispatch() == Attempt.Dispatch.MAY_HAVE_EXECUTED
                            && attempt.state() == Attempt.State.RUNNING && attempt.budgetReservationId() != null
                            : attempt.dispatch() == Attempt.Dispatch.NOT_STARTED && attempt.budgetReservationId() == null,
                    "Invalid dispatch preparation result");
        }
    }

    /**
     * 同一短事务核验领取、版本、取消和租约，创建 Attempt、预留预算并耐久标记派发。
     * 失败整体回滚；预算不足只提交未发送 Attempt，不增加 held，也不标记派发。
     * 成功结果不可当作可重放的发送许可；重复或过期领取必须拒绝。
     *
     * @param command 可信派发准备命令
     * @return 事务提交后的准备结果，或版本、领取、状态等拒绝原因
     */
    Mono<StoreOutcome<PreparedDispatch>> prepareDispatch(Command command);
}
