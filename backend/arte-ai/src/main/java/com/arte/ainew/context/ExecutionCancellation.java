package com.arte.ainew.context;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个运行任务的进程内取消信号，非持久化事实。
 * 显式 cancel 才触发；浏览器断开或 Publisher 被取消订阅不自动修改此信号。
 * 跨实例取消由执行控制记录／通知交付后调用此对象，不能序列化或放入静态全局表。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public final class ExecutionCancellation {
    private final AtomicReference<String> reason = new AtomicReference<>();
    private final Sinks.One<String> signal = Sinks.one();

    /**
     * 首个取消原因生效；并发和重复取消返回 false，晚订阅者仍能收到信号。
     */
    public boolean cancel(String cancellationReason) {
        if (cancellationReason == null || cancellationReason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason must not be blank");
        }
        if (!reason.compareAndSet(null, cancellationReason)) {
            return false;
        }
        signal.emitValue(cancellationReason, Sinks.EmitFailureHandler.FAIL_FAST);
        return true;
    }

    public boolean isCancelled() {
        return reason.get() != null;
    }

    public Mono<String> signal() {
        return signal.asMono();
    }

    public void checkCancelled() {
        String currentReason = reason.get();
        if (currentReason != null) {
            throw new CancellationException(currentReason);
        }
    }
}
