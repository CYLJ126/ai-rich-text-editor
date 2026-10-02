package com.arte.app.execution.support;

import com.arte.base.admission.LocalAdmissionController;
import com.arte.base.execution.BoundedTaskExecutor;
import com.arte.base.model.admission.AdmissionKey;
import com.arte.base.model.admission.AdmissionLimits;
import com.arte.base.spi.observability.Telemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * 独立启用的单实例实现；未完成权威全局准入前不自动给多实例付费调用放行。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "arte.execution.support.enabled", havingValue = "true")
public class NewExecutionSupportConfiguration {
    @Bean
    public JdbcAuditSink executionAuditSink(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcAuditSink(jdbc, manager, Clock.systemUTC());
    }

    @Bean
    public JdbcFileArtifactStore executionArtifactStore(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                                        @Value("${arte.execution.support.artifact-directory}") String directory,
                                                        @Value("${arte.execution.support.max-artifact-bytes:16777216}") long maxBytes) {
        return new JdbcFileArtifactStore(jdbc, manager, Path.of(directory), maxBytes, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    public BoundedTaskExecutor executionTaskExecutor(
            @Value("${arte.execution.support.mode}") String mode,
            @Value("${arte.execution.support.threads:4}") int threads,
            @Value("${arte.execution.support.queue-capacity:32}") int queueCapacity) {
        singleInstance(mode);
        return new BoundedTaskExecutor(threads, queueCapacity, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    public LocalAdmissionController executionAdmissionController(
            @Value("${arte.execution.support.mode}") String mode,
            @Value("${arte.execution.support.tenant-id}") String tenantId,
            @Value("${arte.execution.support.threads:4}") int maxConcurrent,
            @Value("${arte.execution.support.queue-capacity:32}") int maxQueued,
            @Value("${arte.execution.support.starts-per-minute:60}") int startsPerMinute) {
        singleInstance(mode);
        return new LocalAdmissionController(maxConcurrent, maxQueued, Map.of(new AdmissionKey(tenantId, "ai.interactive", null, null),
                new AdmissionLimits(maxConcurrent, startsPerMinute, Duration.ofMinutes(1))), Clock.systemUTC());
    }

    @Bean
    public Telemetry executionTelemetry() {
        return Telemetry.disabled();
    }

    private static void singleInstance(String mode) {
        if (!"single-instance".equals(mode))
            throw new IllegalArgumentException("local execution support requires explicit single-instance mode");
    }
}
