package com.arte.app.execution.support;

import com.arte.base.api.admission.AdmissionController;
import com.arte.base.model.admission.AdmissionKey;
import com.arte.base.model.admission.AdmissionPriority;
import com.arte.base.model.admission.AdmissionRequest;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.spi.artifact.ArtifactStore;
import com.arte.base.spi.execution.TaskExecutor;
import com.arte.base.spi.observability.AuditSink;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionSupportConfigurationTest {
    @Test
    void configurationIsOptInAndRequiresExplicitSingleInstanceMode() throws Exception {
        Path directory = Files.createTempDirectory("arte-support-config-");
        try {
            try (var disabled = context(false, "single-instance", directory)) {
                assertTrue(disabled.getBeansOfType(AuditSink.class).isEmpty());
            }
            try (var enabled = context(true, "single-instance", directory)) {
                assertNotNull(enabled.getBean(AuditSink.class));
                assertNotNull(enabled.getBean(ArtifactStore.class));
                assertNotNull(enabled.getBean(TaskExecutor.class));
                var execution = ExecutionContext.create(new ExecutionScope("tenant", "workspace", new PrincipalRef("user", PrincipalType.USER)), "trace", Set.of());
                var attempt = enabled.getBean(AdmissionController.class).acquire(new AdmissionRequest(execution,
                        new AdmissionKey("tenant", "ai.interactive", null, null), AdmissionPriority.INTERACTIVE, Duration.ZERO));
                var permit = attempt.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
                var task = enabled.getBean(TaskExecutor.class).submit(execution, checkpoint -> "independent");
                task.completion().whenComplete((result, error) -> permit.close());
                assertEquals("independent", task.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            }
            assertThrows(org.springframework.beans.BeansException.class, () -> {
                try (var invalid = context(true, "multi-instance", directory)) {
                }
            });
        } finally {
            Files.deleteIfExists(directory);
        }
    }

    private AnnotationConfigApplicationContext context(boolean enabled, String mode, Path directory) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "arte.execution.support.enabled", enabled, "arte.execution.support.mode", mode,
                "arte.execution.support.artifact-directory", directory.toString(), "arte.execution.support.tenant-id", "tenant")));
        var datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID());
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(datasource));
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
        context.register(NewExecutionSupportConfiguration.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }
}
