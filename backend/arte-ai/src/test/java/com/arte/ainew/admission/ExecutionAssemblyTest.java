package com.arte.ainew.admission;

import com.arte.ainew.api.execution.ExecutionControl;
import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.execution.DefaultInvocationCoordinator;
import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.config.NewAiAdmissionConfiguration;
import com.arte.ainew.config.NewAiExecutionConfiguration;
import com.arte.ainew.config.NewAiGenerationConfiguration;
import org.junit.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 启用执行但未启用 Worker 时，Spring 装配必须保持启动零 SQL／零模型交互。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
public class ExecutionAssemblyTest {
    private AnnotationConfigApplicationContext context(boolean example) throws Exception {
        var context = new AnnotationConfigApplicationContext();
        if (example) {
            new YamlPropertySourceLoader().load("generation-example", new FileSystemResource("examples/ainew-generation.yml"))
                    .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            context.registerBean("appDataSource", DataSource.class, () -> new AbstractDataSource() {
                @Override
                public Connection getConnection() {
                    throw new AssertionError("Assembly must not access the database");
                }

                @Override
                public Connection getConnection(String name, String password) {
                    return getConnection();
                }
            });
        }
        return context;
    }

    private void refresh(AnnotationConfigApplicationContext context) {
        context.register(NewAiAdmissionConfiguration.class, NewAiGenerationConfiguration.class, NewAiExecutionConfiguration.class);
        context.refresh();
    }

    @Test
    public void defaultDisabledCreatesNoWorkerOrReadService() throws Exception {
        try (var context = context(false)) {
            refresh(context);
            assertTrue(context.getBeansOfType(InvocationDispatchWorker.class).isEmpty());
            assertTrue(context.getBeansOfType(ExecutionEventService.class).isEmpty());
        }
    }

    @Test
    public void explicitlyEnabledExecutionAssemblesWithoutAutomaticallyPolling() throws Exception {
        try (var context = context(true)) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("execution",
                    Map.of("arte.ai-new-execution.enabled", "true")));
            refresh(context);
            assertEquals(1, context.getBeansOfType(InvocationCoordinator.class).size());
            assertTrue(context.getBean(InvocationCoordinator.class) instanceof DefaultInvocationCoordinator);
            assertNotNull(context.getBean(ExecutionControl.class));
            assertNotNull(context.getBean(ExecutionEventService.class));
            var worker = context.getBean(InvocationDispatchWorker.class);
            assertFalse(worker.isRunning());
            assertFalse(worker.isAutoStartup());
        }
    }

    @Test
    public void enabledExecutionWithoutGatewayFailsRatherThanKeepingAnAdmissionOnlyCoordinator() throws Exception {
        try (var context = context(true)) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("execution",
                    Map.of("arte.ai-new-execution.enabled", "true", "arte.ai-new-generation.enabled", "false")));
            assertThrows(org.springframework.beans.BeansException.class, () -> refresh(context));
        }
    }

    @Test
    public void invalidLeaseAndUnknownExecutionOptionFailStrictly() throws Exception {
        try (var context = context(true)) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("bad-execution",
                    Map.of("arte.ai-new-execution.enabled", "true", "arte.ai-new-execution.attempt-lease", "1s")));
            assertThrows(org.springframework.beans.BeansException.class, () -> refresh(context));
        }
        try (var context = context(true)) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("typo",
                    Map.of("arte.ai-new-execution.enabled", "true", "arte.ai-new-execution.max-retries", "3")));
            assertThrows(org.springframework.beans.BeansException.class, () -> refresh(context));
        }
    }
}
