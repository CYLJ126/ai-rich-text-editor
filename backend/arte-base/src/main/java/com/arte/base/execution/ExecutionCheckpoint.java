package com.arte.base.execution;

import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 合作式停止检查。取消不会自行中断 SDK 或宣告远端已取消；适配器仍须设置 I/O 超时。
 */
public final class ExecutionCheckpoint {
    private final ExecutionContext context;
    private final Clock clock;
    private final AtomicBoolean cancellation;

    ExecutionCheckpoint(ExecutionContext context, Clock clock, AtomicBoolean cancellation) {
        this.context = context;
        this.clock = clock;
        this.cancellation = cancellation;
    }

    public ExecutionContext context() {
        return context;
    }

    public boolean isStopRequested() {
        return cancellation.get() || context.isExpiredAt(clock.instant()) || Thread.currentThread().isInterrupted();
    }

    public void check() {
        if (context.isExpiredAt(clock.instant()))
            throw ExecutionFailures.afterStart(CommonErrorCode.DEADLINE_EXCEEDED, context, "execution", null);
        if (cancellation.get() || Thread.currentThread().isInterrupted())
            throw ExecutionFailures.afterStart(CommonErrorCode.INTERRUPTED, context, "execution", null);
    }
}
