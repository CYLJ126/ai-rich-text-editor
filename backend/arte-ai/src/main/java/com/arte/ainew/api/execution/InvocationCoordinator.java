package com.arte.ainew.api.execution;

import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.execution.*;
import reactor.core.publisher.Mono;

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

    /**
     * 重新授权、校验、计算摘要并原子提交受理与派发依据；同键同请求返回原身份，冲突明确失败。
     */
    Mono<AcceptedExecution> submit(InvocationSubmission<?> submission);

    /**
     * 仅供内部 Worker 消费已领取的 DISPATCH Outbox；核验消息租约，再领取 Attempt 并预留预算。
     * 发送前提交 dispatch 标记；处理失败、显式安全重试、结果落盘、终态及结算。
     * 完成表示本次消息已耐久处理／移交；重复投递不得产生第二次未经许可的调用。
     * 订阅取消或 Worker 崩溃交由租约恢复，不能靠 doFinally 宣称无副作用或释放预算。
     */
    Mono<Void> dispatch(OutboxMessage message, ExecutionRuntimeContext runtime);

    /**
     * 仅核对 UNKNOWN 的原远端操作；有证据才收敛状态及费用，不能重发原调用。
     * runtime 使用当前授权及独立核对期限，不延长原 Invocation 的调用期限。
     */
    Mono<Invocation> reconcile(ExecutionCommands.Version target, ExecutionRuntimeContext runtime);

    /**
     * 控制命令经耐久防重后路由至执行权威；不支持暂停／继续时返回 UNSUPPORTED。
     */
    Mono<ControlReceipt> control(ExecutionControlRequest request, ExecutionContext context);
}
