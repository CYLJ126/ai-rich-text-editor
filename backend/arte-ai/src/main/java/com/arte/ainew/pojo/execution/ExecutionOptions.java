package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 执行约束上限
 * <p>
 * maxAttempts 包含首次尝试；0 个工具步骤／并发表示禁止工具。不能延长上下文期限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ExecutionOptions(Instant deadline, int maxAttempts, long maxOutputBytes,
                               int maxToolSteps, int maxConcurrentTools) implements Serializable {
    public ExecutionOptions {
        Objects.requireNonNull(deadline, "deadline");
        ContractChecks.range(maxAttempts, "maxAttempts", 1, 10);
        ContractChecks.range(maxOutputBytes, "maxOutputBytes", 1, 32L * 1024 * 1024);
        ContractChecks.range(maxToolSteps, "maxToolSteps", 0, 100);
        ContractChecks.range(maxConcurrentTools, "maxConcurrentTools", 0, 16);
        ContractChecks.require((maxToolSteps == 0) == (maxConcurrentTools == 0),
                "Tool steps and concurrency must both be zero or positive");
    }
}
