package com.arte.core.diagnostics;

import com.arte.core.constant.CoreConstant;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 通用观测能力
 * <p>
 * 阶段计时、上下文关联、SQL 统计、完成信号和生命周期点。
 * TODO 暂只支持工具阶段观测，后续可扩展为通用操作阶段观测，如聊天流程观测。
 * <p>
 * 有界操作阶段诊断；不持有业务对象、身份、输入或 SQL 文本，不参与授权、事务及期限决策。
 * 子阶段的 SQL 同时计入父阶段，统计为包含子阶段的口径；父子耗时及 SQL 不能相加。
 * 复用 RequestObservation 的 Reactor Hook 和 ObservedScheduler，只传播诊断信息。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 16:52 ✾
 */
@Slf4j
public final class OperationObservation {

    static final String KEY = OperationObservation.class.getName();
    private static final ThreadLocal<Span> CURRENT = new ThreadLocal<>();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String NODE = UUID.randomUUID().toString();

    private OperationObservation() {
    }

    public static Span current() {
        return CURRENT.get();
    }

    static Span from(ContextView context) {
        return context.getOrDefault(KEY, null);
    }

    public static Scope bind(Span span) {
        return new Scope(span);
    }

    public static final class Scope implements AutoCloseable {
        private final Span previous = CURRENT.get();
        private final String trace = MDC.get(CoreConstant.LOG_TRACE_ID);

        private Scope(Span span) {
            if (span == null) CURRENT.remove();
            else {
                CURRENT.set(span);
                if (span.traceId != null) MDC.put(CoreConstant.LOG_TRACE_ID, span.traceId);
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

    /**
     * 每次订阅创建独立阶段；不重试、不缓存、不改变源的成功／空结果／错误／取消信号。
     */
    public static <T> Mono<T> observe(String stage, String invocationId, String traceId, Instant deadline,
                                      Function<Span, Mono<T>> work) {
        return Mono.deferContextual(context -> {
            var span = new Span(stage, invocationId, traceId, deadline, context.getOrDefault(KEY, null));
            Mono<T> source;
            try (var ignored = bind(span)) {
                source = work.apply(span);
            } catch (RuntimeException | Error error) {
                reactor.core.Exceptions.throwIfFatal(error);
                span.finish("ERROR", error);
                return Mono.error(error);
            }
            return source.doOnSuccess(value -> span.finish(value == null ? "EMPTY" : "SUCCESS", null))
                    .doOnError(error -> span.finish("ERROR", error))
                    .doOnCancel(() -> span.finish("CANCELLED", null))
                    .contextWrite(value -> value.put(KEY, span));
        });
    }

    /**
     * 同步边界包含真实事务结束；调用方取消等待不会提前结算仍在 JDBC 线程运行的阶段。
     */
    public static <T> T call(String stage, String invocationId, String traceId, Instant deadline, Supplier<T> work) {
        var span = new Span(stage, invocationId, traceId, deadline, current());
        try (var ignored = bind(span)) {
            try {
                T value = work.get();
                span.finish("SUCCESS", null);
                return value;
            } catch (RuntimeException | Error failure) {
                span.finish("ERROR", failure);
                throw failure;
            }
        }
    }

    /**
     * 无 SQL 的生命周期点；跨进程只能在校准墙钟后比较 epochMs，不相减单调时钟。
     */
    public static void point(String stage, String invocationId, String traceId, Instant deadline) {
        var values = identity(stage, invocationId, traceId);
        long at = System.currentTimeMillis();
        values.put("epochMs", at);
        values.put("deadlineEpochMs", epoch(deadline));
        values.put("remainingMs", remaining(deadline, at));
        emit("AI_TOOL_POINT", values);
    }

    /**
     * 一次订阅或同步操作的独立生命周期；结束日志是快照，在途 SQL 的迟到结果不会改写既有日志。
     */
    public static final class Span {
        private final String stage;
        private volatile String invocationId;
        private final String requestedInvocationId;
        private volatile String traceId;
        private volatile Instant deadline;
        private final Span parent;
        private final long started = System.nanoTime(), startedEpochMs = System.currentTimeMillis();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final LongAdder sqlCount = new LongAdder(), sqlCompleted = new LongAdder(), sqlNanos = new LongAdder(), sqlErrors = new LongAdder(), lockingNanos = new LongAdder();
        private final AtomicLong sqlInFlight = new AtomicLong();
        private final java.util.Set<Long> mysqlConnections = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean connectionIdsTruncated = new AtomicBoolean();
        private volatile Map<String, Object> report;

        private Span(String stage, String invocationId, String traceId, Instant deadline, Span parent) {
            this.stage = stage;
            this.invocationId = invocationId;
            this.requestedInvocationId = invocationId;
            // 子阶段沿用本次请求 trace；历史重放仍可关联新的 HTTP，Worker 独立根沿用耐久 trace。
            this.traceId = parent != null && parent.traceId != null ? parent.traceId : traceId;
            this.deadline = deadline;
            this.parent = parent;
            // 选版后得到的有效期限同步到同一次调用的父阶段；只收紧诊断值，不修改业务上下文。
            for (var ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
                if (deadline != null && invocationId != null && invocationId.equals(ancestor.invocationId)
                        && (ancestor.deadline == null || deadline.isBefore(ancestor.deadline)))
                    ancestor.deadline = deadline;
            }
        }

        /**
         * Worker 首次读出可信记录后关联原 trace／deadline；不修改任何业务上下文。
         */
        public void correlate(String traceId, Instant deadline) {
            this.traceId = traceId;
            this.deadline = deadline;
        }

        public String traceId() {
            return traceId;
        }

        /**
         * 取得可靠回执后关联真正胜出的 Invocation；保留最初请求 ID 供并发重放核对。
         */
        public void resolvedInvocation(String invocationId) {
            this.invocationId = invocationId;
        }

        public Map<String, Object> report() {
            return report;
        }

        private void finish(String outcome, Throwable error) {
            if (!finished.compareAndSet(false, true)) return;
            long ended = System.currentTimeMillis();
            var values = identity(stage, invocationId, traceId);
            values.put("requestedInvocationId", requestedInvocationId);
            values.put("parentStage", parent == null ? null : parent.stage);
            values.put("outcome", outcome);
            values.put("errorType", error == null ? null : error.getClass().getSimpleName());
            values.put("startedEpochMs", startedEpochMs);
            values.put("finishedEpochMs", ended);
            values.put("elapsedMs", millis(System.nanoTime() - started));
            values.put("deadlineEpochMs", epoch(deadline));
            values.put("remainingStartMs", remaining(deadline, startedEpochMs));
            values.put("remainingEndMs", remaining(deadline, ended));
            values.put("sqlCount", sqlCount.sum());
            values.put("sqlCompletedCount", sqlCompleted.sum());
            values.put("sqlInFlight", sqlInFlight.get());
            values.put("sqlExecuteMs", millis(sqlNanos.sum()));
            values.put("sqlErrors", sqlErrors.sum());
            values.put("forUpdateExecuteMs", millis(lockingNanos.sum()));
            // 没有 JDBC 事件不能据此断言零 SQL；可能是所用 DataSource 没有接入过滤器。
            values.put("jdbcObserved", sqlCount.sum() > 0);
            values.put("mysqlConnectionIds", mysqlConnections.stream().sorted().toList());
            values.put("connectionIdsTruncated", connectionIdsTruncated.get());
            values.put("lockWaitMs", null);
            report = Collections.unmodifiableMap(values);
            emit("AI_TOOL_STAGE", values);
        }
    }

    /**
     * 捕获 SQL 开始时的阶段链；完成时不重新取 ThreadLocal，避免迟到 SQL 被归给下一阶段。
     */
    public static Sql sqlStarted() {
        var spans = new ArrayList<Span>();
        for (var span = current(); span != null; span = span.parent) {
            if (!span.finished.get()) {
                span.sqlCount.increment();
                span.sqlInFlight.incrementAndGet();
                spans.add(span);
            }
        }
        return spans.isEmpty() ? null : new Sql(spans);
    }

    public record Sql(java.util.List<Span> spans) {
        public Sql {
            spans = java.util.List.copyOf(spans);
        }

        public void completed(long nanos, boolean locking, boolean failed) {
            completed(nanos, locking, failed, null);
        }

        public void completed(long nanos, boolean locking, boolean failed, Long mysqlConnectionId) {
            for (var span : spans) {
                span.sqlInFlight.decrementAndGet();
                span.sqlCompleted.increment();
                span.sqlNanos.add(nanos);
                if (locking) span.lockingNanos.add(nanos);
                if (failed) span.sqlErrors.increment();
                if (mysqlConnectionId != null) {
                    if (span.mysqlConnections.size() < 64) span.mysqlConnections.add(mysqlConnectionId);
                    else if (!span.mysqlConnections.contains(mysqlConnectionId)) span.connectionIdsTruncated.set(true);
                }
            }
        }
    }

    private static LinkedHashMap<String, Object> identity(String stage, String invocationId, String traceId) {
        var values = new LinkedHashMap<String, Object>();
        values.put("nodeId", NODE);
        values.put("invocationId", invocationId);
        values.put("traceId", traceId);
        values.put("stage", stage);
        return values;
    }

    private static Long epoch(Instant deadline) {
        try {
            return deadline == null ? null : deadline.toEpochMilli();
        } catch (ArithmeticException unsupported) {
            return null;
        }
    }

    private static Long remaining(Instant deadline, long at) {
        var epoch = epoch(deadline);
        try {
            return epoch == null ? null : Math.subtractExact(epoch, at);
        } catch (ArithmeticException unsupported) {
            return null;
        }
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 1000.0) / 1000.0;
    }

    private static void emit(String kind, Map<String, Object> values) {
        try {
            log.info("{} {}", kind, JSON.writeValueAsString(values));
        } catch (RuntimeException ignored) { /* 诊断编码失败不能改变原操作的结果。 */ }
    }
}
