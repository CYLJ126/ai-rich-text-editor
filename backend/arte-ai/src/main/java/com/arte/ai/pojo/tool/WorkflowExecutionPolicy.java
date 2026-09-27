package com.arte.ai.pojo.tool;

import java.time.Duration;

/**
 * 工作流版本级执行预算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record WorkflowExecutionPolicy(
        int maximumSteps,
        Duration timeout,
        int maximumNodeRetries,
        int maximumParallelism,
        boolean checkpointAfterEachNode
) {
    public WorkflowExecutionPolicy {
        if (maximumSteps <= 0) throw new IllegalArgumentException("maximumSteps must be positive");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maximumNodeRetries < 0) throw new IllegalArgumentException("maximumNodeRetries must not be negative");
        if (maximumParallelism <= 0) throw new IllegalArgumentException("maximumParallelism must be positive");
    }

    public static WorkflowExecutionPolicy defaults() {
        return new WorkflowExecutionPolicy(100, Duration.ofMinutes(30), 0, 8, true);
    }
}
