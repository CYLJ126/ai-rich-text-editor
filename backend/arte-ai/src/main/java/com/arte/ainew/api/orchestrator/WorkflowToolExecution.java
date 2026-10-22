package com.arte.ainew.api.orchestrator;

import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.tool.ToolWorkflowStep;
import reactor.core.publisher.Mono;

/**
 * 受信工作流宿主消费 TOOL 的内部契约
 * <p>
 * 提供提交工具操作、查询原状态和结果的统一接口。
 * 每次操作都使用当前主体资格，不以工作流 ID 作为授权依据。
 * 工作流保存步骤检查点，TOOL 保存 Invocation／Attempt／结果，两者不另建一套工具状态机。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 13:40 ✾
 */
public interface WorkflowToolExecution {

    /**
     * 提交已耐久保存的逻辑步骤；并发提交或响应丢失后以同一意图重放，仍返回原 Invocation。
     * 当前上下文必须是独立受理上下文；收费账户由 step.input.budgetRef 指定，沿用现有 TOOL 预算规则。
     */
    Mono<AcceptedExecution> submit(ToolWorkflowStep step, ExecutionContext current);

    /**
     * 当前读取权限下恢复原状态、预算及结果；UNKNOWN 原样返回，失败／不存在／不可见均不触发重新提交。
     * 恢复读取期限与原工具执行期限独立，不能借读取或重放延长原 Invocation deadline。
     */
    Mono<ExecutionEventService.InvocationSnapshot> snapshot(ToolWorkflowStep.Key key, ExecutionContext current);
}
