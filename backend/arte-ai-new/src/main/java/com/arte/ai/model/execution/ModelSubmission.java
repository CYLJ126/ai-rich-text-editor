package com.arte.ai.model.execution;

import com.arte.ai.model.context.ResourceContextSnapshot;
import com.arte.ai.model.generation.ModelPlan;
import com.arte.base.model.execution.ExecutionContext;

/**
 * 服务端准备的受理事实；输入摘要由实际发送正文计算，不能信任客户端自报摘要。
 */
public record ModelSubmission(String executionId, String attemptId, ModelPlan plan,
                              ExecutionContext context, String idempotencyKey, String requestDigest, ResourceContextSnapshot resourceContext) {
    public ModelSubmission {
        if (resourceContext != null && (!resourceContext.scope().equals(context.scope()) || !resourceContext.bindingRef().equals(plan.binding().ref())))
            throw new IllegalArgumentException("submission must agree with resource context");
    }
    public ModelSubmission(String executionId, String attemptId, ModelPlan plan, ExecutionContext context,
                           String idempotencyKey, String requestDigest) {
        this(executionId, attemptId, plan, context, idempotencyKey, requestDigest, null);
    }
}
