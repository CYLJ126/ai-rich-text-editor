package com.arte.ainew.context;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 每个订阅独立的 Reactor 执行上下文，不使用 ThreadLocal 或全局 Reactor Hook。
 * contextWrite 必须放在需要读取上下文的链路下游；嵌套作用域不会改变父订阅。
 * 不应在带身份的链路上使用 cache/share 来跨用户复用结果，也不要内部 subscribe 丢失上下文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public final class ReactiveExecutionContext {
    private static final Object KEY = new Object();

    private ReactiveExecutionContext() { }

    public static Function<Context, Context> write(ExecutionRuntimeContext runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return context -> context.put(KEY, runtime);
    }

    public static ExecutionRuntimeContext require(ContextView context) {
        return context.<ExecutionRuntimeContext>getOrEmpty(KEY)
                .orElseThrow(() -> new IllegalStateException("Execution context is missing"));
    }

    public static Mono<ExecutionRuntimeContext> current() {
        return Mono.deferContextual(context -> Mono.just(require(context)));
    }

    public static <T> Mono<T> withContext(Mono<T> source, ExecutionRuntimeContext runtime) {
        return Objects.requireNonNull(source, "source").contextWrite(write(runtime));
    }

    public static <T> Flux<T> withContext(Flux<T> source, ExecutionRuntimeContext runtime) {
        return Objects.requireNonNull(source, "source").contextWrite(write(runtime));
    }

    /** 在执行器订阅边界使用；观看流不应关联生成任务的取消信号。 */
    public static <T> Mono<T> guard(Mono<T> source, Clock clock) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(clock, "clock");
        return Mono.deferContextual(view -> {
            var runtime = require(view);
            try {
                runtime.checkActive(clock);
            } catch (TimeoutException e) {
                return Mono.error(e);
            }
            // timeout 的控制 Publisher 报错时会取消主订阅；takeUntilOther 的错误路径不保证如此。
            return source.timeout(stopSignal(runtime, clock));
        });
    }

    /** deadline 是整个订阅的绝对期限，不是每个输出片段重新计时的空闲超时。 */
    public static <T> Flux<T> guard(Flux<T> source, Clock clock) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(clock, "clock");
        return Flux.deferContextual(view -> {
            var runtime = require(view);
            try {
                runtime.checkActive(clock);
            } catch (TimeoutException e) {
                return Flux.error(e);
            }
            // 终态成为信号后参与合并，dematerialize 在源终止时取消计时器；控制错误也取消源。
            // prefetch=1 保持缓冲有界，不按每个输出片段重新启动 deadline 计时器。
            Flux<Signal<T>> output = source.materialize();
            Flux<Signal<T>> stop = stopSignal(runtime, clock).thenMany(Flux.empty());
            return Flux.merge(1, output, stop).dematerialize();
        });
    }

    private static Mono<Void> stopSignal(ExecutionRuntimeContext runtime, Clock clock) {
        Mono<Void> cancelled = runtime.cancellation().signal()
                .flatMap(reason -> Mono.error(new CancellationException(reason)));
        Duration remaining = Duration.between(clock.instant(), runtime.execution().deadline());
        Mono<Void> expired = Mono.delay(remaining.isNegative() ? Duration.ZERO : remaining)
                .flatMap(ignored -> Mono.error(new TimeoutException(
                        "Execution deadline exceeded: " + runtime.execution().executionId())));
        return Mono.firstWithSignal(cancelled, expired);
    }
}
