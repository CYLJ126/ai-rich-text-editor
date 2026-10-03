package com.arte.base.spi.observability;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.observability.AuditOutcome;
import com.arte.base.validation.ContractChecks;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 指标与追踪。
 *
 * <p>采集指标和关联执行链路的追踪信息；允许采样，不替代耐久审计、执行记录或费用账本。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 标签键限定为低基数类别；主体和执行 ID 只进入追踪上下文。
 */
public interface Telemetry {
    enum Label {COMPONENT, OPERATION, OUTCOME}

    interface Span extends AutoCloseable {
        void outcome(AuditOutcome outcome);

        @Override
        void close();
    }

    Span startSpan(ExecutionContext context, String operation);

    void increment(String metric, long amount, Map<Label, String> labels);

    void duration(String metric, Duration elapsed, Map<Label, String> labels);

    /**
     * 同步阶段观测；异步任务应在实际执行线程另外创建 Span。
     */
    default <T> T observe(ExecutionContext context, String operation, Supplier<T> work) {
        try (var span = startSpan(context, operation)) {
            try {
                T result = work.get();
                span.outcome(AuditOutcome.SUCCEEDED);
                return result;
            } catch (RuntimeException | Error failed) {
                span.outcome(AuditOutcome.FAILED);
                throw failed;
            }
        }
    }

    /**
     * 明确关闭采样观测时使用；不能替代审计、执行持久化或预算账本。
     */
    static Telemetry disabled() {
        return new Telemetry() {
            public Span startSpan(ExecutionContext context, String operation) {
                ContractChecks.required(context, "context");
                ContractChecks.identifier(operation, "operation");
                return new Span() {
                    public void outcome(AuditOutcome outcome) {
                        ContractChecks.required(outcome, "outcome");
                    }

                    public void close() {
                    }
                };
            }

            public void increment(String metric, long amount, Map<Label, String> labels) {
                validate(metric, labels);
                if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
            }

            public void duration(String metric, Duration elapsed, Map<Label, String> labels) {
                validate(metric, labels);
                ContractChecks.required(elapsed, "elapsed");
                if (elapsed.isNegative()) throw new IllegalArgumentException("elapsed must not be negative");
            }
        };
    }

    private static void validate(String metric, Map<Label, String> labels) {
        ContractChecks.identifier(metric, "metric");
        ContractChecks.required(labels, "labels").forEach((label, value) -> {
            ContractChecks.required(label, "label");
            ContractChecks.identifier(value, "label value");
        });
    }
}
