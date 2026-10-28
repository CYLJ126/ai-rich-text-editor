package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.*;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * 执行派发前的原子存储边界
 * <p>
 * 将领取校验、Attempt 创建、按需预算预留及派发标记合并为同库短事务。
 * 应用层取得已提交的准备结果后才允许调用模型或工具；本接口不执行外部调用，也不接管可能已发送的 Attempt。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 18:00 ✾
 */
public interface DispatchPreparationStore {

    /**
     * 准备结果的计费分类，用于区分无费用预留与必须取得预留的派发。
     */
    enum BillingMode {FREE, CHARGE}

    /**
     * 一次派发的计费计划，仅由可信应用层根据受信配置选择；免费不是金额为零的收费预留。
     */
    sealed interface BillingPlan permits Free, Charge {
        BillingMode mode();
    }

    /**
     * 无预算引用的免费 TOOL 派发计划，不携带金额、费率或预留有效期，也不产生预算账本记录。
     * 免费仅免除费用预留，仍须通过领取、取消、执行期限及租约检查。
     */
    record Free() implements BillingPlan {
        @Override
        public BillingMode mode() {
            return BillingMode.FREE;
        }
    }

    /**
     * 收费派发计划，准备时须按固定费率和费用上界取得预算预留；零金额仍沿用收费流程。
     *
     * @param amount    本次 Attempt 申请预留的费用上界，包含币种
     * @param rate      固定版本的费率引用，类型必须为 rate
     * @param retention 预算预留的有效时长，不是执行租约或记录清理期限
     */
    record Charge(Money amount, DefinitionRef rate, Duration retention) implements BillingPlan {

        public Charge {
            Objects.requireNonNull(amount);
            Objects.requireNonNull(rate).requireType("rate");
            Objects.requireNonNull(retention);
            ContractChecks.require(retention.compareTo(Duration.ofSeconds(1)) >= 0
                    && retention.compareTo(Duration.ofDays(30)) <= 0, "Invalid reservation retention");
        }

        @Override
        public BillingMode mode() {
            return BillingMode.CHARGE;
        }
    }

    /**
     * 一次派发准备的内部命令
     * <p>
     * 将预期执行版本、已领取工作项与计费计划绑定，防止跨调用使用领取身份。
     * 参数来自可信应用层，不能绑定客户端请求；Attempt 序号和 fencing 仍由数据库分配。
     *
     * @param invocation   待派发 Invocation 的归属、ID 及预期版本
     * @param claim        已领取的 DISPATCH 工作项，携带 Worker 身份、领取 token 及租约
     * @param attemptId    本次拟创建的 Attempt ID
     * @param attemptLease 新 Attempt 的执行租约时长，独立于工作项领取租约
     * @param billingPlan  本次派发的免费或收费计划
     */
    record Command(ExecutionCommands.Version invocation, OutboxMessage claim, String attemptId,
                   Duration attemptLease, BillingPlan billingPlan) {

        public Command {
            Objects.requireNonNull(invocation);
            Objects.requireNonNull(claim);
            ContractChecks.require(claim.kind() == OutboxMessage.Kind.DISPATCH
                            && claim.invocationId().equals(invocation.invocationId()) && claim.owner().equals(invocation.owner()),
                    "Dispatch claim does not match invocation");
            ContractChecks.id(attemptId, "attemptId");
            ExecutionCommands.validLease(attemptLease);
            Objects.requireNonNull(billingPlan);
        }

        /**
         * 保留原模型派发调用方式；旧构造明确对应 CHARGE。命令不进入持久化快照。
         */
        public Command(ExecutionCommands.Version invocation, OutboxMessage claim, String attemptId,
                       Duration attemptLease, Money amount, DefinitionRef rate, Duration retention) {
            this(invocation, claim, attemptId, attemptLease, new Charge(amount, rate, retention));
        }

        public Money amount() {
            return charge().amount();
        }

        public DefinitionRef rate() {
            return charge().rate();
        }

        public Duration retention() {
            return charge().retention();
        }

        private Charge charge() {
            if (billingPlan instanceof Charge charge) return charge;
            throw new IllegalStateException("FREE dispatch has no reservation parameters");
        }
    }

    /**
     * 派发准备提交后的结果
     * <p>
     * 返回同一事务中的 Invocation 与活跃 Attempt。
     * ready=true 表示已耐久标记 MAY_HAVE_EXECUTED，可进入本次外部调用，不证明外部操作已经执行。
     * ready=false 仅表示预算不足，保留未发送的 Attempt，供应用提交已知失败终态；该结果不允许发送。
     *
     * @param invocation  创建 Attempt 后的 Invocation，包含最新版本及活跃尝试引用
     * @param attempt     本次准备的活跃 Attempt，收费准备成功时带预算预留引用
     * @param ready       是否完成本次派发准备，不能作为可重放的发送许可
     * @param billingMode 实际准备采用的计费模式，必须与派发状态及预算预留一致
     */
    record PreparedDispatch(Invocation invocation, Attempt attempt, boolean ready, BillingMode billingMode) {

        public PreparedDispatch {
            Objects.requireNonNull(invocation);
            Objects.requireNonNull(attempt);
            Objects.requireNonNull(billingMode);
            ContractChecks.require(attempt.attemptId().equals(invocation.activeAttemptId())
                    && attempt.invocationId().equals(invocation.request().context().executionId()), "Attempt mismatch");
            ContractChecks.require(ready ? attempt.dispatch() == Attempt.Dispatch.MAY_HAVE_EXECUTED
                            && attempt.state() == Attempt.State.RUNNING
                            : attempt.dispatch() == Attempt.Dispatch.NOT_STARTED && attempt.budgetReservationId() == null,
                    "Invalid dispatch preparation result");
            ContractChecks.require(billingMode == BillingMode.FREE
                            ? ready && invocation.request().kind() == CapabilityDescriptor.Kind.TOOL
                            && invocation.request().context().budgetRef() == null && attempt.budgetReservationId() == null
                            : !ready || attempt.budgetReservationId() != null,
                    "Dispatch result does not match billing mode");
        }

        public PreparedDispatch(Invocation invocation, Attempt attempt, boolean ready) {
            this(invocation, attempt, ready, BillingMode.CHARGE);
        }
    }

    /**
     * 同一短事务核验领取、版本、取消和租约，创建 Attempt 并耐久标记派发；CHARGE 同时预留预算。
     * FREE 仅接受无预算引用的 TOOL，不读写预算账本；不替代受理层的工具计费策略校验。
     * 失败整体回滚；预算不足只提交未发送 Attempt，不增加 held，也不标记派发。
     * 成功结果不可当作可重放的发送许可；重复或过期领取必须拒绝。
     *
     * @param command 可信派发准备命令
     * @return 事务提交后的准备结果，或版本、领取、状态等拒绝原因
     */
    Mono<StoreOutcome<PreparedDispatch>> prepareDispatch(Command command);
}
