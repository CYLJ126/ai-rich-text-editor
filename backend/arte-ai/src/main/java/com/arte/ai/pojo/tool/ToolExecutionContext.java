package com.arte.ai.pojo.tool;

import com.arte.ai.api.tool.ToolCancellation;

import java.time.Instant;
import java.util.Map;

/**
 * 只传递给工具执行端、不暴露给模型的受信运行上下文。
 * <p>
 * 上下文不应被当作 Service Locator，业务依赖仍应通过构造器注入到工具实现中。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolExecutionContext(
        String runId,
        String workflowRunId,
        String agentRunId,
        String traceId,
        String parentSpanId,
        ToolPrincipal principal,
        Instant deadline,
        ToolCancellation cancellation,
        String idempotencyKey,
        String credentialBindingId,
        Map<String, Object> attributes
) {

    public ToolExecutionContext {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        if (principal == null) {
            throw new IllegalArgumentException("principal must not be null");
        }
        if (deadline == null) {
            throw new IllegalArgumentException("deadline must not be null");
        }
        if (cancellation == null) {
            throw new IllegalArgumentException("cancellation must not be null");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
