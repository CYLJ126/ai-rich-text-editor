package com.arte.ainew.api.execution;

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
}
