package com.arte.ainew.application.execution;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationBudgetState;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

/**
 * 只读取已经授权且验证归属的 Invocation，沿耐久 Attempt 引用查询预算状态，不用账户总 held 推断本次结算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 00:06 ✾
 */
public final class InvocationBudgetStatusResolver {

    private final ExecutionStore executionStore;
    private final BudgetService budgetService;

    public InvocationBudgetStatusResolver(ExecutionStore executionStore, BudgetService budgetService) {
        this.executionStore = executionStore;
        this.budgetService = budgetService;
    }

    /**
     * 沿 Invocation 的 activeAttemptId 查询当前 Attempt，再沿其 budgetReservationId 读取本次预算预留状态。
     * 查询归属取自 Invocation 保存的可信执行上下文；调用方须先检查当前读取权限及该 Invocation 的归属。
     * <p>
     * 尚无 Attempt 或 Attempt 尚未关联预算预留时返回 NOT_RESERVED；存在引用但对应 Attempt 或预留记录缺失时，
     * 通过 Mono 返回 AI_NOT_FOUND 错误，避免将缺失记录当作未预留。
     * 预留存在时，将其状态映射为 RESERVED、PENDING_RECONCILIATION、SETTLED 或 RELEASED。
     * <p>
     * 读取的是当前 Attempt 的预算状态，不根据账户总 held 或 Invocation 终态推断结算结果。
     * 终态与预算结算可先后提交，因此调用已结束时仍可能返回 RESERVED；此查询不执行预算预留、扣费或释放。
     *
     * @param invocation 已经调用方授权并确认归属的耐久调用记录，使用其中的调用 ID 和当前 Attempt 引用定位预留。
     * @return 本次调用的预算状态 Mono；无预留关联时为 NOT_RESERVED，引用记录缺失时以 AI_NOT_FOUND 异常结束。
     */
    public Mono<InvocationBudgetState> resolve(Invocation invocation) {
        if (invocation.activeAttemptId() == null) {
            return Mono.just(InvocationBudgetState.NOT_RESERVED);
        }
        var owner = ExecutionOwner.from(invocation.request().context());
        return executionStore.findAttempt(owner, invocation.request().context().executionId(), invocation.activeAttemptId())
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                .flatMap(attempt -> attempt.budgetReservationId() == null ? Mono.just(InvocationBudgetState.NOT_RESERVED)
                        : budgetService.reservation(owner, attempt.budgetReservationId())
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                        .map(reservation -> InvocationBudgetState.valueOf(reservation.state().name())));
    }
}
