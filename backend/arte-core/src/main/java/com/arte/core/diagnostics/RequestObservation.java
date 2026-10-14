package com.arte.core.diagnostics;

import com.arte.core.constant.CoreConstant;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 请求诊断只携带计数器和 trace，不传播身份、认证头、输入或 SQL 参数。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 09:41 ✾
 */
public final class RequestObservation {
    private static final String KEY = RequestObservation.class.getName();
    private static final ThreadLocal<RequestObservation> CURRENT = new ThreadLocal<>();
    public final String traceId = UUID.randomUUID().toString().replace("-", "");
    public final long started = System.nanoTime();
    public final long startedEpochMs = System.currentTimeMillis();
    public final String method;
    public final String path;
    public final String measurementId;
    private final AtomicBoolean complete = new AtomicBoolean();
    private final AtomicLong firstWrite = new AtomicLong();
    private final LongAdder bytes = new LongAdder();
    private final LongAdder sqlCount = new LongAdder(), sqlNanos = new LongAdder(), sqlErrors = new LongAdder(), lockingSqlNanos = new LongAdder();
    private final LongAdder sqlCompleted = new LongAdder();
    private final AtomicLong sqlInFlight = new AtomicLong();
    private final LongAdder connectionCount = new LongAdder(), connectionNanos = new LongAdder(), connectionErrors = new LongAdder();
    private final LongAdder authorizationCount = new LongAdder(), authorizationNanos = new LongAdder();
    private final ConcurrentHashMap<String, QueueMetric> queues = new ConcurrentHashMap<>();
    private final java.util.Set<Long> mysqlConnections = ConcurrentHashMap.newKeySet();
    private volatile Map<String, Object> report;

    public RequestObservation(String method, String path, String measurementId) {
        this.method = method;
        this.path = path;
        this.measurementId = measurementId != null && measurementId.matches("[A-Za-z0-9_-]{1,96}") ? measurementId : null;
    }

    public static RequestObservation current() {
        return CURRENT.get();
    }

    public Map<String, Object> report() {
        return report;
    }

    public static Scope bind(RequestObservation observation) {
        return new Scope(observation);
    }

    public static final class Scope implements AutoCloseable {
        private final RequestObservation previous = CURRENT.get();
        private final String trace = MDC.get(CoreConstant.LOG_TRACE_ID);

        private Scope(RequestObservation observation) {
            if (observation == null || observation.complete.get()) {
                CURRENT.remove();
                MDC.remove(CoreConstant.LOG_TRACE_ID);
            } else {
                CURRENT.set(observation);
                MDC.put(CoreConstant.LOG_TRACE_ID, observation.traceId);
            }
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
            if (trace == null) MDC.remove(CoreConstant.LOG_TRACE_ID);
            else MDC.put(CoreConstant.LOG_TRACE_ID, trace);
        }
    }

    public static String currentTraceId() {
        var value = current();
        return value == null ? null : value.traceId;
    }

    public static <T> Mono<T> observe(Mono<T> source) {
        var observation = current();
        return observation == null ? source : source.contextWrite(context -> context.put(KEY, observation));
    }

    public static <T> Flux<T> observe(Flux<T> source) {
        var observation = current();
        return observation == null ? source : source.contextWrite(context -> context.put(KEY, observation));
    }

    public static <T> Mono<T> initialAuthorization(Mono<T> source) {
        return Mono.deferContextual(context -> {
            var observation = context.<RequestObservation>getOrDefault(KEY, current());
            var start = System.nanoTime();
            var recorded = new AtomicBoolean();
            Runnable record = () -> {
                if (recorded.compareAndSet(false, true) && observation != null && !observation.complete.get()) {
                    observation.authorizationCount.increment();
                    observation.authorizationNanos.add(System.nanoTime() - start);
                }
            };
            return source.doOnSuccess(ignored -> record.run()).doOnError(ignored -> record.run()).doFinally(ignored -> record.run());
        });
    }

    /**
     * 只恢复本诊断上下文；不启用全局 SecurityContext 或其他 ThreadLocal 的自动传播。
     */
    public static void installReactorPropagation() {
        Hooks.onEachOperator(KEY, Operators.lift((source, subscriber) -> {
            var observation = subscriber.currentContext().<RequestObservation>getOrDefault(KEY, null);
            if (observation == null) return subscriber;
            return new CoreSubscriber<Object>() {
                @Override
                public @NonNull Context currentContext() {
                    return subscriber.currentContext();
                }

                @Override
                public void onSubscribe(@NonNull Subscription subscription) {
                    try (var ignored = bind(observation)) {
                        subscriber.onSubscribe(subscription);
                    }
                }

                @Override
                public void onNext(Object value) {
                    try (var ignored = bind(observation)) {
                        subscriber.onNext(value);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    try (var ignored = bind(observation)) {
                        subscriber.onError(error);
                    }
                }

                @Override
                public void onComplete() {
                    try (var ignored = bind(observation)) {
                        subscriber.onComplete();
                    }
                }
            };
        }));
    }

    public static void removeReactorPropagation() {
        Hooks.resetOnEachOperator(KEY);
    }

    public boolean sqlStarted() {
        if (complete.get()) return false;
        sqlCount.increment();
        sqlInFlight.incrementAndGet();
        return true;
    }

    public void sql(long nanos, boolean locking, boolean failed, Long mysqlConnectionId) {
        sqlInFlight.decrementAndGet();
        if (complete.get()) return;
        sqlCompleted.increment();
        sqlNanos.add(nanos);
        if (locking) lockingSqlNanos.add(nanos);
        if (failed) sqlErrors.increment();
        if (mysqlConnectionId != null) mysqlConnections.add(mysqlConnectionId);
    }

    public void connection(long nanos, boolean failed) {
        if (complete.get()) return;
        connectionCount.increment();
        connectionNanos.add(nanos);
        if (failed) connectionErrors.increment();
    }

    public void queued(String scheduler, long nanos) {
        if (complete.get()) return;
        var metric = queues.computeIfAbsent(scheduler, ignored -> new QueueMetric());
        metric.count.increment();
        metric.nanos.add(nanos);
        metric.max.accumulateAndGet(nanos, Math::max);
    }

    public void wrote(int count) {
        if (count > 0) {
            firstWrite.compareAndSet(0, System.nanoTime());
            bytes.add(count);
        }
    }

    public void committed() {
        firstWrite.compareAndSet(0, System.nanoTime());
    }

    public Map<String, Object> finish(int status) {
        if (!complete.compareAndSet(false, true)) return null;
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("traceId", traceId);
        values.put("measurementId", measurementId);
        values.put("method", method);
        values.put("path", path);
        values.put("status", status);
        values.put("mvcTotalMs", ms(System.nanoTime() - started));
        values.put("mvcStartedEpochMs", startedEpochMs);
        values.put("serverFirstWriteMs", firstWrite.get() == 0 ? null : ms(firstWrite.get() - started));
        values.put("responseBytes", bytes.sum());
        values.put("sqlCount", sqlCount.sum());
        values.put("sqlExecuteMs", ms(sqlNanos.sum()));
        values.put("sqlErrors", sqlErrors.sum());
        values.put("sqlCompletedCount", sqlCompleted.sum());
        values.put("sqlInFlight", sqlInFlight.get());
        values.put("forUpdateExecuteMs", ms(lockingSqlNanos.sum()));
        // FOR UPDATE 执行时间含网络和执行，不能命名为锁等待。实际锁等待由 MySQL 观测工具关联。
        values.put("lockWaitMs", null);
        values.put("mysqlConnectionIds", mysqlConnections.stream().sorted().toList());
        values.put("connectionCount", connectionCount.sum());
        values.put("connectionAcquireMs", ms(connectionNanos.sum()));
        values.put("connectionErrors", connectionErrors.sum());
        values.put("initialAuthorizationCount", authorizationCount.sum());
        values.put("initialAuthorizationMs", ms(authorizationNanos.sum()));
        var queueValues = new java.util.TreeMap<String, Object>();
        queues.forEach((name, value) -> queueValues.put(name, Map.of("tasks", value.count.sum(), "sumMs", ms(value.nanos.sum()), "maxMs", ms(value.max.get()))));
        values.put("schedulerQueues", queueValues);
        report = java.util.Collections.unmodifiableMap(values);
        return report;
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 1000.0) / 1000.0;
    }

    private static final class QueueMetric {
        final LongAdder count = new LongAdder(), nanos = new LongAdder();
        final AtomicLong max = new AtomicLong();
    }
}
