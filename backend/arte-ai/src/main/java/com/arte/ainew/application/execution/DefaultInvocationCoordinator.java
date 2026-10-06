package com.arte.ainew.application.execution;

import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.execution.*;
import reactor.core.publisher.Mono;

/**
 * 组合已完成的受理和单次生成派发
 * <p>
 * 同一 Invocation 权威仍由 ExecutionStore 推进。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
public final class DefaultInvocationCoordinator implements InvocationCoordinator {

    private final AdmissionInvocationCoordinator admissionInvocationCoordinator;
    private final GenerationDispatcher generationDispatcher;

    public DefaultInvocationCoordinator(AdmissionInvocationCoordinator admissionInvocationCoordinator, GenerationDispatcher generationDispatcher) {
        this.admissionInvocationCoordinator = admissionInvocationCoordinator;
        this.generationDispatcher = generationDispatcher;
    }

    @Override
    public Mono<AcceptedExecution> submit(InvocationSubmission<?> submission) {
        return admissionInvocationCoordinator.submit(submission);
    }

    @Override
    public Mono<Void> dispatch(OutboxMessage message, ExecutionRuntimeContext runtime) {
        return generationDispatcher.dispatch(message, runtime);
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
