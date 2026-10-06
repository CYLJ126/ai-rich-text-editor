package com.arte.ainew.api.execution;

import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.execution.*;
import reactor.core.publisher.Mono;

/**
 * 执行协调器
 * <p>
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
     * 重新检查授权、校验输入并计算请求摘要，在同一事务中保存调用记录及派发 Outbox。
     * 相同幂等键和相同请求返回原调用的回执；同键不同请求明确拒绝。
     */
    Mono<AcceptedExecution> submit(InvocationSubmission<?> submission);

    /**
     * 仅供内部 Worker 处理已领取的 DISPATCH Outbox 消息。
     * 检查消息仍属于当前 Worker，且领取有效期未过，再取得 Attempt 执行资格并预留预算。
     * 发送前将“可能已发送”的 dispatch 标记成功提交数据库；协调失败处理、安全重试、结果保存、最终状态及结算。
     * 返回的 Mono 完成，表示本次消息的处理结果或后续接手任务已成功提交数据库，不代表模型调用必然成功。
     * 消息重复投递不得产生第二次未经许可的调用；Worker 崩溃后，领取有效期到期可由其他 Worker 重新领取。
     * 恢复处理必须核对已保存的执行记录；不能仅凭订阅取消或 doFinally 判断模型未执行，或直接释放预算。
     */
    Mono<Void> dispatch(OutboxMessage message, ExecutionRuntimeContext runtime);

    /**
     * 仅查询、核实状态为 UNKNOWN 的原远端操作；取得可信证据后才能确认执行状态和费用，不能重新发送原调用。
     * runtime 使用当前授权及单独的核对期限，不延长原 Invocation 的调用期限。
     */
    Mono<Invocation> reconcile(ExecutionCommands.Version target, ExecutionRuntimeContext runtime);

    /**
     * 将控制命令及防重记录成功提交数据库后，路由至执行权威（交给负责该任务状态变更的执行控制组件处理）。
     * 任务状态以数据库中已提交的执行记录为准，不以某个进程的本地状态为准。
     * 不支持暂停／继续时返回 UNSUPPORTED；收到控制命令的回执不代表任务已经停止或恢复。
     */
    Mono<ControlReceipt> control(ExecutionControlRequest request, ExecutionContext context);
}
