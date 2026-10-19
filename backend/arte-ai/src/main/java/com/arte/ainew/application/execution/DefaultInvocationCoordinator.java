package com.arte.ainew.application.execution;

import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.support.ToolObservation;
import com.arte.ainew.application.tool.execution.ToolDispatcher;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * 组合生成受理与按权威能力路由的单次派发
 * <p>
 * 同一 Invocation 权威仍由 ExecutionStore 推进。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
public final class DefaultInvocationCoordinator implements InvocationCoordinator {

    private final AdmissionInvocationCoordinator admissionInvocationCoordinator;
    private final GenerationDispatcher generationDispatcher;
    private final ExecutionStore executionStore;
    private final ToolDispatcher toolDispatcher;

    /**
     * 执行存储始终必填，派发按权威 Invocation.kind 路由；至少提供生成或 TOOL 派发器之一。
     * 未装配的种类拒绝派发，不改写其受理记录或伪造终态。
     */
    public DefaultInvocationCoordinator(AdmissionInvocationCoordinator admission, GenerationDispatcher generation,
                                        ExecutionStore executionStore, ToolDispatcher toolDispatcher) {
        this.admissionInvocationCoordinator = Objects.requireNonNull(admission);
        this.generationDispatcher = generation;
        this.executionStore = Objects.requireNonNull(executionStore);
        this.toolDispatcher = toolDispatcher;
        if (generation == null && toolDispatcher == null)
            throw new IllegalArgumentException("At least one invocation dispatcher is required");
    }

    @Override
    public Mono<AcceptedExecution> submit(InvocationSubmission<?> submission) {
        if (generationDispatcher == null)
            return Mono.error(new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY));
        return admissionInvocationCoordinator.submit(submission);
    }

    @Override
    public Mono<Void> dispatch(OutboxMessage message, ExecutionRuntimeContext runtime) {
        boolean tool = message.workClass() == OutboxMessage.WorkClass.TOOL_SHORT || message.workClass() == OutboxMessage.WorkClass.TOOL_LONG;
        var loading = tool ? ToolObservation.mono(ToolObservation.Stage.DISPATCH_ROUTING, runtime.execution(),
                () -> executionStore.find(message.owner(), message.invocationId()))
                : executionStore.find(message.owner(), message.invocationId());
        return loading
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                .flatMap(invocation -> switch (invocation.request().kind()) {
                    case GENERATION ->
                            generationDispatcher == null ? unavailable() : generationDispatcher.dispatch(message, runtime);
                    case TOOL -> toolDispatcher == null ? unavailable() : toolDispatcher.dispatch(message, runtime);
                    default -> unavailable();
                });
    }

    private static Mono<Void> unavailable() {
        return Mono.error(new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY));
    }

    @Override
    public Mono<Invocation> reconcile(ExecutionCommands.Version target, ExecutionRuntimeContext runtime) {
        return admissionInvocationCoordinator.reconcile(target, runtime);
    }

    @Override
    public Mono<ControlReceipt> control(ExecutionControlRequest request, ExecutionContext context) {
        return admissionInvocationCoordinator.control(request, context);
    }
}
