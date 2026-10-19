package com.arte.ainew.tool.execution;

import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.execution.DefaultInvocationCoordinator;
import com.arte.ainew.application.execution.GenerationDispatcher;
import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.execution.ToolDispatcher;
import com.arte.ainew.config.*;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.spi.auth.ToolAdmissionAccess;
import com.arte.ainew.spi.auth.ToolExecutionAccess;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.gateway.ToolGateway;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import reactor.core.scheduler.Scheduler;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.fixture;
import static org.junit.Assert.*;

/**
 * TOOL 可独立于模型定义／网关装配；启动不得 SQL、发现、授权或调用业务，缺资格不能发明默认授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 20:00 ✾
 */
public class ToolExecutionAssemblyTest {

    @Test
    public void toolOnlyAssemblesWithoutModelDefinitionsGatewayOrStartupIo() {
        Scheduler metadata, persistence;
        try (var context = base()) {
            ports(context, true, true);
            refresh(context);
            assertNotNull(context.getBean(ToolAdmissionService.class));
            assertNotNull(context.getBean(com.arte.ainew.spi.persistence.ToolApprovalStore.class));
            assertTrue(context.getBeansOfType(com.arte.ainew.application.tool.approval.ToolApprovalRecovery.class).isEmpty());
            assertNotNull(context.getBean(ToolDispatcher.class));
            assertNotNull(context.getBean(ToolGateway.class));
            assertTrue(context.getBean(InvocationCoordinator.class) instanceof DefaultInvocationCoordinator);
            assertTrue(context.getBeansOfType(ModelGateway.class).isEmpty());
            assertTrue(context.getBeansOfType(GenerationDispatcher.class).isEmpty());
            assertTrue(context.getBean(NewAiProperties.class).capabilities().isEmpty());
            assertTrue(context.getBean(NewAiProperties.class).connections().isEmpty());
            assertTrue(context.getBean(NewAiProperties.class).budgets().isEmpty());
            assertNull(context.getBean(LocalToolDiscovery.class).state().snapshot());
            assertFalse(context.getBean(InvocationDispatchWorker.class).isRunning());
            metadata = context.getBean("newAiToolDiscoveryScheduler", Scheduler.class);
            persistence = context.getBean("newAiPersistenceScheduler", Scheduler.class);
        }
        assertTrue(metadata.isDisposed());
        assertTrue(persistence.isDisposed());
    }

    @Test
    public void eachOfTheThreeSwitchesIsRequiredForToolExecutionAssembly() {
        for (var disabled : List.of("arte.ai-new.enabled", "arte.ai-new-tool.enabled", "arte.ai-new-execution.enabled")) {
            try (var context = new AnnotationConfigApplicationContext()) {
                var flags = flags();
                flags.put(disabled, false);
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("flags", flags));
                ports(context, true, true);
                context.register(NewAiToolExecutionConfiguration.class);
                context.refresh();
                assertTrue(context.getBeansOfType(ToolDispatcher.class).isEmpty());
                assertTrue(context.getBeansOfType(ToolAdmissionService.class).isEmpty());
            }
        }
    }

    @Test
    public void missingEitherHostPortPreservesGenerationWithoutInventingToolPermission() {
        for (var pair : List.of(new boolean[]{false, false}, new boolean[]{false, true}, new boolean[]{true, false})) {
            try (var context = base()) {
                ports(context, pair[0], pair[1]);
                context.registerBean(ModelGateway.class, () -> call -> {
                    throw new AssertionError("No startup model invocation");
                });
                refresh(context);
                assertTrue(context.getBeansOfType(ToolAdmissionService.class).isEmpty());
                assertTrue(context.getBeansOfType(ToolDispatcher.class).isEmpty());
                assertNotNull(context.getBean(GenerationDispatcher.class));
                assertNotNull(context.getBean(InvocationCoordinator.class));
            }
        }
    }

    @Test
    public void explicitlySuppliedHostPortsCannotFallBackWhenGatewayIsMissing() {
        try (var context = base()) {
            ports(context, true, true);
            context.register(NewAiAdmissionConfiguration.class, NewAiToolConfiguration.class, NewAiToolExecutionConfiguration.class,
                    NewAiExecutionConfiguration.class);
            assertThrows(BeansException.class, context::refresh);
        }
    }

    @Test
    public void missingAllDispatchersFailsInsteadOfAcceptingUnexecutableTasks() {
        try (var context = base()) {
            context.register(NewAiAdmissionConfiguration.class, NewAiExecutionConfiguration.class);
            assertThrows(BeansException.class, context::refresh);
        }
    }

    @Test
    public void generationAndToolDispatchersShareExactlyOneWorkerAndCoordinator() {
        try (var context = base()) {
            ports(context, true, true);
            context.registerBean(ModelGateway.class, () -> call -> {
                throw new AssertionError("No startup model invocation");
            });
            refresh(context);
            assertNotNull(context.getBean(GenerationDispatcher.class));
            assertNotNull(context.getBean(ToolDispatcher.class));
            assertEquals(1, context.getBeansOfType(InvocationCoordinator.class).size());
            assertEquals(1, context.getBeansOfType(InvocationDispatchWorker.class).size());
        }
    }

    @Test
    public void bootMetadataOrdersToolAssemblyBeforeRoutingCoordinator() throws Exception {
        var declarations = new ClassPathResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .getContentAsString(StandardCharsets.UTF_8).lines().toList();
        assertTrue(declarations.contains(NewAiToolExecutionConfiguration.class.getName()));
        assertTrue(List.of(NewAiExecutionConfiguration.class.getAnnotation(AutoConfiguration.class).after())
                .contains(NewAiToolExecutionConfiguration.class));
    }

    private static Map<String, Object> flags() {
        var flags = new HashMap<String, Object>();
        flags.put("arte.ai-new.enabled", true);
        flags.put("arte.ai-new.configuration-mode", "DATABASE");
        flags.put("arte.ai-new.release-ref", "tool-only");
        flags.put("arte.ai-new-tool.enabled", true);
        flags.put("arte.ai-new-execution.enabled", true);
        flags.put("arte.ai-new-execution.worker-enabled", false);
        return flags;
    }

    private static AnnotationConfigApplicationContext base() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", flags()));
        context.registerBean("appDataSource", DataSource.class, () -> new AbstractDataSource() {
            @Override
            public Connection getConnection() {
                throw new AssertionError("No startup SQL");
            }

            @Override
            public Connection getConnection(String user, String password) {
                return getConnection();
            }
        });
        context.registerBean(ExecutionAuthorizationResolver.class, () -> (name, tenant, workspace, scopes) -> {
            throw new AssertionError("No startup identity read");
        });
        context.registerBean(LocalToolRegistry.class, () -> {
            var tool = fixture("text", "v1");
            return new LocalToolRegistry(List.of(tool.registration(() -> {
                throw new AssertionError("No startup discovery");
            })));
        });
        return context;
    }

    private static void ports(AnnotationConfigApplicationContext context, boolean admission, boolean execution) {
        if (admission) context.registerBean(ToolAdmissionAccess.class, () -> (input, current, plan) -> {
            throw new AssertionError("No startup tool admission");
        });
        if (execution) context.registerBean(ToolExecutionAccess.class, () -> call -> {
            throw new AssertionError("No startup resource authorization");
        });
    }

    private static void refresh(AnnotationConfigApplicationContext context) {
        context.register(NewAiAdmissionConfiguration.class, NewAiToolConfiguration.class, NewAiToolGatewayConfiguration.class,
                NewAiToolExecutionConfiguration.class, NewAiExecutionConfiguration.class);
        context.refresh();
    }
}
