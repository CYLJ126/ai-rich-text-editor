package com.arte.ainew.api.execution;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.execution.StoreOutcome;
import reactor.core.publisher.Mono;

/**
 * 预算服务
 * 用于管理任务执行过程中使用的资源，如模型资源、远程服务资源等。
 * <p>
 * 主要操作：AI 预算预留、结算、限额、用量与费用对账等。
 * 边界：原子账本与快速准入分开，未知费用保留待对账状态。
 * <p>
 * 预留按 Invocation／Attempt 关联，结算按 reservationId／settlementKey 原子防重；
 * 估算与报告用量区分，null 费用不可按零结算，取消或超时不自动释放预留。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:42 ✾
 **/
public interface BudgetService {
    /**
     * 原子检查币种／费率／余额，并预留和关联 Attempt；不得采用先读余额再扣减的实现。
     */
    Mono<StoreOutcome<BudgetReservation>> reserve(BudgetCommands.Reserve command);

    /**
     * reservationId + settlementKey 去重先于 CAS；同键异内容、重复最终结算均拒绝。
     */
    Mono<StoreOutcome<BudgetReservation>> settle(BudgetCommands.Settle command);

    Mono<BudgetCommands.Account> account(ExecutionOwner owner, String budgetRef);

    /**
     * 当前权威快照，用于断线恢复及核对 CAS；不存在或归属不符返回空 Mono。
     */
    Mono<BudgetReservation> reservation(ExecutionOwner owner, String reservationId);
}
