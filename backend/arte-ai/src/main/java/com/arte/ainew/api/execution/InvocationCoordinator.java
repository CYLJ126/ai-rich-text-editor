package com.arte.ainew.api.execution;

/**
 * 执行协调器
 * 负责协调各组件职责，实现时应委托给策略、存储、预算和调度组件，避免成为巨型服务。
 * <p>
 * 主要操作：受理、准入、派发、尝试记录、重试协调、结算
 * 边界：统一治理与生命周期，能力专有执行委托给对应 Gateway
 * <p>
 * ChatService、AiActionService、编排运行时通过 Coordinator 提交能力调用。
 * Coordinator 管理 Invocation／Attempt 生命周期；Gateway 返回能力事件或结果，不独立推进同一份执行状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 16:56 ✾
 **/
public interface InvocationCoordinator {
}
