package com.arte.ai.model.execution;

import com.arte.ai.model.generation.ModelPlan;
import com.arte.base.model.execution.ExecutionContext;

/**
 * 服务端准备的受理事实；输入摘要由实际发送正文计算，不能信任客户端自报摘要。
 */
public record ModelSubmission(String executionId, String attemptId, ModelPlan plan,
                              ExecutionContext context, String idempotencyKey, String requestDigest) {
}
