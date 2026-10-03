package com.arte.ai.model.execution;

import com.arte.ai.model.generation.GenerationRequest;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;

/**
 * 可恢复的受控输入与同意引用，不持久化凭据、SDK 对象或可执行回调。
 */
public record QueuedModelCall(InvocationRequest<GenerationRequest> request, ResourceRef consent, String fingerprint,
                              Instant executeBy) {
    public QueuedModelCall {
        request = ContractChecks.required(request, "request");
        consent = ContractChecks.required(consent, "consent");
        fingerprint = ContractChecks.identifier(fingerprint, "fingerprint");
        executeBy = ContractChecks.required(executeBy, "executeBy");
        if (request.context().deadline() != null && executeBy.isAfter(request.context().deadline()))
            throw new IllegalArgumentException("work deadline exceeds authorized context");
    }

    /**
     * 执行期限独立于授权上下文；后者的完整指纹绑定任务及外发同意，恢复时不能改写。
     */
    public ExecutionContext workerContext() {
        var context = request.context();
        return new ExecutionContext(context.scope(), context.traceId(), context.parentExecutionId(), executeBy,
                context.cancellation(), context.authorizationScopes(), context.budgetRef(), context.releaseRef(), context.idempotencyKey());
    }
}
