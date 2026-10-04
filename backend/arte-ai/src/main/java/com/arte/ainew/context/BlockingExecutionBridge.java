package com.arte.ainew.context;

import com.arte.ainew.common.execution.ExecutionContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.util.Objects;

/**
 * 在调用方提供的专用有界 Scheduler 上调用旧同步领域服务，并恢复 UserContext／MDC。
 * 整个同步服务及其事务必须在 callback 中执行，不返回待订阅的 Publisher 或异步工作。
 * 线程中断不保证数据库或远端副作用已回滚；结果确定性由执行记录和领域契约判断。
 * Scheduler 生命周期由组合配置管理，此桥接器不创建全局线程池或 CallerRuns 回退。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public final class BlockingExecutionBridge {
    private final Scheduler scheduler;
    private final Clock clock;

    public BlockingExecutionBridge(Scheduler scheduler, Clock clock) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @FunctionalInterface
    public interface Operation<T> {
        T call(ExecutionContext context) throws Exception;
    }

    public <T> Mono<T> call(Operation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        Mono<T> source = Mono.deferContextual(view -> {
            var runtime = ReactiveExecutionContext.require(view);
            return Mono.fromCallable(() -> {
                runtime.checkActive(clock);
                try (var scope = LegacyUserContextScope.open(runtime.execution())) {
                    return operation.call(runtime.execution());
                }
            }).subscribeOn(scheduler);
        });
        return ReactiveExecutionContext.guard(source, clock);
    }
}
