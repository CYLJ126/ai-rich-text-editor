package com.arte.ainew.application.tool.workflow;

import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.api.orchestrator.WorkflowToolExecution;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.tool.ToolWorkflowStep;
import com.arte.ainew.serialization.ToolAdmissionDigests;
import com.arte.ainew.spi.persistence.ToolAdmissionStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * 将稳定逻辑步骤映射到既有独立 TOOL 受理
 * <p>
 * 复用现有 TOOL 执行机制，处理授权、防重、审批和预算等规则。
 * 恢复只读权威，不调用 Gateway 或分配新操作键。
 * 不伪造聊天 Run parent，也不声明工作流根预算、停止传播或检查点事务已经实现。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 13:40 ✾
 */
public final class WorkflowToolService implements WorkflowToolExecution {

    private final ToolAdmissionService admission;
    private final ToolAdmissionStore snapshots;
    private final ExecutionEventService events;
    private final Clock clock;

    public WorkflowToolService(ToolAdmissionService admission, ToolAdmissionStore snapshots, ExecutionEventService events, Clock clock) {
        this.admission = Objects.requireNonNull(admission);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.events = Objects.requireNonNull(events);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Mono<AcceptedExecution> submit(ToolWorkflowStep step, ExecutionContext current) {
        return Mono.defer(() -> {
            Objects.requireNonNull(step);
            Objects.requireNonNull(current);
            // 不能静默丢弃调用方携带的父执行或根预算，否则会绕过既有子执行治理。
            require(current.parentExecutionId() == null && current.budgetRef() == null, ResultCodeEnum.AI_TOOL_POLICY_DENIED);
            var owner = ExecutionOwner.from(current);
            var id = step.key().invocationId(owner);
            var context = new ExecutionContext(id, current.traceId(), current.authorization(), current.deadline(),
                    null, null, current.releaseRef(), step.key().idempotencyKey(owner));
            var remaining = Duration.between(clock.instant(), current.deadline());
            require(!remaining.isNegative() && !remaining.isZero(), ResultCodeEnum.AI_DEADLINE_EXCEEDED);
            return snapshots.snapshot(owner, id).flatMap(previous -> {
                        // 原意图先经过受理重放的当前授权，再判断新意图是否冲突；不将内部快照直接返回消费者。
                        return admission.submit(previous.input(), context).map(receipt -> {
                            require(previous.submissionDigest().equals(ToolAdmissionDigests.submission(owner, step.input())),
                                    ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT);
                            return receipt;
                        });
                    }).switchIfEmpty(Mono.defer(() -> admission.submit(step.input(), context)))
                    .map(receipt -> {
                        require(receipt.kind() == AcceptedExecution.Kind.INVOCATION && id.equals(receipt.executionId()),
                                ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT);
                        return receipt;
                    }).timeout(remaining).onErrorMap(TimeoutException.class,
                            expired -> new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
        });
    }

    @Override
    public Mono<ExecutionEventService.InvocationSnapshot> snapshot(ToolWorkflowStep.Key key, ExecutionContext current) {
        return Mono.defer(() -> {
            var id = Objects.requireNonNull(key).invocationId(ExecutionOwner.from(current));
            // 既有查询服务复核当前 READ、完整 owner、工具读取资格及 ResultRef；不能直接读原始存储。
            return events.snapshots(List.of(id), current).map(values -> {
                if (values.size() != 1) throw new IllegalStateException("Workflow tool snapshot count mismatch");
                var value = values.getFirst();
                require(id.equals(value.invocationId()) && value.invocation() != null, ResultCodeEnum.AI_NOT_FOUND);
                require(value.invocation().request().kind() == CapabilityDescriptor.Kind.TOOL
                        && id.equals(value.invocation().request().context().executionId()), ResultCodeEnum.AI_NOT_FOUND);
                return value;
            });
        });
    }

    private static void require(boolean valid, ResultCodeEnum code) {
        if (!valid) throw new AdmissionException(code);
    }
}
