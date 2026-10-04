package com.arte.ainew.context;

import com.arte.ainew.common.execution.ExecutionContext;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * 实例内运行上下文，将可持久化数据和取消对象分开。
 * Worker 恢复时必须重新授权并创建新的运行对象，不能恢复旧进程的取消信号。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public record ExecutionRuntimeContext(ExecutionContext execution, ExecutionCancellation cancellation) {
    public ExecutionRuntimeContext {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    public static ExecutionRuntimeContext start(ExecutionContext execution) {
        return new ExecutionRuntimeContext(execution, new ExecutionCancellation());
    }

    public void checkActive(Clock clock) throws TimeoutException {
        cancellation.checkCancelled();
        if (!Objects.requireNonNull(clock, "clock").instant().isBefore(execution.deadline())) {
            throw new TimeoutException("Execution deadline exceeded: " + execution.executionId());
        }
    }
}
