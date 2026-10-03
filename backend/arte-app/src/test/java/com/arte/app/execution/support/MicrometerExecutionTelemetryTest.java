package com.arte.app.execution.support;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MicrometerExecutionTelemetryTest {
    private ExecutionContext viewer(String trace) {
        return new ExecutionContext(new ExecutionScope("tenant", "workspace", new PrincipalRef("private-user", PrincipalType.USER)),
                trace, null, null, null, Set.of(), null, null, null);
    }

    @Test
    void recordsStagesAndFailuresWithoutHighCardinalityOrContentLabels() {
        var registry = new SimpleMeterRegistry();
        try {
            var telemetry = new MicrometerExecutionTelemetry(registry);
            assertEquals("answer", telemetry.observe(viewer("trace-one"), "chat.history", () -> "answer"));
            telemetry.observe(viewer("trace-two"), "chat.history", () -> "answer");
            var timer = registry.get("arte.execution.duration").tag("operation", "chat.history").tag("outcome", "SUCCEEDED").timer();
            assertEquals(2, timer.count());
            assertEquals(2, registry.get("arte.execution.operations").tag("outcome", "SUCCEEDED").counter().count());
            var original = new IllegalStateException("private message");
            assertSame(original, assertThrows(IllegalStateException.class,
                    () -> telemetry.observe(viewer("trace-three"), "chat.history", () -> {
                        throw original;
                    })));
            assertEquals(1, registry.get("arte.execution.operations").tag("outcome", "FAILED").counter().count());
            registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                    assertTrue(Set.of("component", "operation", "outcome").contains(tag.getKey()))));
        } finally {
            registry.close();
        }
    }

    @Test
    void unavailableMetricBackendDoesNotChangeBusinessResult() {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new MeterFilter() {
                public Meter.Id map(Meter.Id id) {
                    throw new IllegalStateException("metrics unavailable");
                }
            });
            var telemetry = new MicrometerExecutionTelemetry(registry);
            assertEquals(42, telemetry.observe(viewer("trace"), "chat.history", () -> 42));
        } finally {
            registry.close();
        }
    }
}
