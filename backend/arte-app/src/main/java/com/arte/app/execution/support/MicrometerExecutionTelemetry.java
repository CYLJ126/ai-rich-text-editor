package com.arte.app.execution.support;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.observability.AuditOutcome;
import com.arte.base.spi.observability.Telemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 低基数指标 + 阶段耗时日志；不记录消息正文、用户 ID、凭据和外发正文。
 */
public final class MicrometerExecutionTelemetry implements Telemetry {
    private static final Logger LOG = LoggerFactory.getLogger(MicrometerExecutionTelemetry.class);
    private final MeterRegistry registry;

    public MicrometerExecutionTelemetry(MeterRegistry registry) {
        this.registry = java.util.Objects.requireNonNull(registry);
    }

    @Override
    public Span startSpan(ExecutionContext context, String operation) {
        long started = System.nanoTime();
        return new Span() {
            private AuditOutcome outcome = AuditOutcome.UNKNOWN;
            private final AtomicBoolean closed = new AtomicBoolean();

            public void outcome(AuditOutcome value) {
                outcome = value;
            }

            public void close() {
                if (!closed.compareAndSet(false, true)) return;
                var elapsed = Duration.ofNanos(System.nanoTime() - started);
                var labels = Map.of(Label.COMPONENT, "ai-new", Label.OPERATION, operation, Label.OUTCOME, outcome.name());
                duration("arte.execution.duration", elapsed, labels);
                increment("arte.execution.operations", 1, labels);
                if (outcome == AuditOutcome.FAILED || outcome == AuditOutcome.UNKNOWN)
                    LOG.warn("ai-new operation={} outcome={} elapsedMs={} traceId={}", operation, outcome, elapsed.toMillis(), context.traceId());
                else if (elapsed.toMillis() >= 1000)
                    LOG.info("ai-new operation={} outcome={} elapsedMs={} traceId={}", operation, outcome, elapsed.toMillis(), context.traceId());
                else
                    LOG.debug("ai-new operation={} outcome={} elapsedMs={} traceId={}", operation, outcome, elapsed.toMillis(), context.traceId());
            }
        };
    }

    @Override
    public void increment(String metric, long amount, Map<Label, String> labels) {
        try {
            registry.counter(metric, tags(labels)).increment(amount);
        } catch (RuntimeException unavailable) {
            LOG.debug("ai-new metric unavailable: {}", metric);
        }
    }

    @Override
    public void duration(String metric, Duration elapsed, Map<Label, String> labels) {
        try {
            Timer.builder(metric).tags(tags(labels)).publishPercentileHistogram().register(registry).record(elapsed);
        } catch (RuntimeException unavailable) {
            LOG.debug("ai-new metric unavailable: {}", metric);
        }
    }

    private static Tags tags(Map<Label, String> labels) {
        var tags = Tags.empty();
        for (var label : Label.values()) {
            if (labels.containsKey(label))
                tags = tags.and(label.name().toLowerCase(java.util.Locale.ROOT), labels.get(label));
        }
        return tags;
    }
}
