package com.arte.base.execution;

import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 合作式停止检查。取消不会自行中断 SDK 或宣告远端已取消；适配器仍须设置 I/O 超时。
 */
public final class ExecutionCheckpoint {
    private final ExecutionContext context;
    private final Clock clock;
    private final AtomicBoolean cancellation;
    private final Set<AutoCloseable> stopResources = ConcurrentHashMap.newKeySet();

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

    /**
     * 在停止或到期时关闭正在阻塞的 I/O；注册关闭不等于执行已退出。
     */
    public AutoCloseable onStop(AutoCloseable resource) {
        stopResources.add(resource);
        if (isStopRequested() && stopResources.remove(resource)) closeQuietly(resource);
        return () -> stopResources.remove(resource);
    }

    void signalStop() {
        for (var resource : stopResources) if (stopResources.remove(resource)) closeQuietly(resource);
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) { /* actual task records the I/O outcome */ }
    }
}
