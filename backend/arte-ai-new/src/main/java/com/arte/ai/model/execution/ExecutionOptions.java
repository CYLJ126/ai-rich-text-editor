package com.arte.ai.model.execution;
import java.time.Duration;

import com.arte.base.validation.ContractChecks;

/**
 * 调用的独立总期限，不能因观看者断开而无限运行。
 */
public record ExecutionOptions(Duration timeout, boolean streaming) {
    public ExecutionOptions {
        timeout = ContractChecks.required(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("timeout must be positive and at most ten minutes");
    }
}
