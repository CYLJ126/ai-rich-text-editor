package com.arte.ainew.tool.gateway;

import com.arte.ainew.application.gateway.DefaultToolGateway;
import com.arte.ainew.application.tool.discovery.StaticToolDiscovery;
import com.arte.ainew.application.tool.discovery.StaticToolRegistry;
import com.arte.ainew.config.NewAiToolConfiguration;
import com.arte.ainew.config.NewAiToolGatewayConfiguration;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.spi.auth.ToolExecutionAccess;
import com.arte.ainew.spi.gateway.ToolGateway;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolBindingStore;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.ainew.spi.persistence.ToolReleaseStore;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import reactor.core.scheduler.Scheduler;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.fixture;
import static org.junit.Assert.*;

/**
 * 双开关、显式宿主执行授权及资源释放；构造期间禁止存储、发现、授权或业务执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 15:21 ✾
 */
public class ToolGatewayAssemblyTest {

    @Test
    public void bothSwitchesAreRequired() {
        for (var flags : List.of(Map.<String, Object>of(), Map.<String, Object>of("arte.ai-new.enabled", true),
                Map.<String, Object>of("arte.ai-new-tool.enabled", true))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", flags));
                access(context);
                context.register(NewAiToolGatewayConfiguration.class);
                context.refresh();
                assertTrue(context.getBeansOfType(ToolGateway.class).isEmpty());
                assertFalse(context.containsBean("newAiToolInvocationScheduler"));
            }
        }
    }

    @Test
    public void managementOnlyInstallationDoesNotInventExecutionAuthorization() {
        try (var context = context()) {
            dependencies(context);
            context.register(NewAiToolConfiguration.class, NewAiToolGatewayConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(StaticToolDiscovery.class));
            assertTrue(context.getBeansOfType(ToolGateway.class).isEmpty());
            assertFalse(context.containsBean("newAiToolInvocationScheduler"));
        }
    }

    @Test
    public void explicitAccessAssemblesWithoutStartupEffectsAndDisposesResources() {
        Scheduler invocation, metadata;
        try (var context = context()) {
            dependencies(context);
            access(context);
            context.register(NewAiToolConfiguration.class, NewAiToolGatewayConfiguration.class);
            context.refresh();
            assertTrue(context.getBean(ToolGateway.class) instanceof DefaultToolGateway);
            assertNull(context.getBean(StaticToolDiscovery.class).state().snapshot());
            invocation = context.getBean("newAiToolInvocationScheduler", Scheduler.class);
            metadata = context.getBean("newAiToolDiscoveryScheduler", Scheduler.class);
            assertFalse(invocation.isDisposed());
            assertFalse(metadata.isDisposed());
        }
        assertTrue(invocation.isDisposed());
        assertTrue(metadata.isDisposed());
    }

    @Test
    public void incompleteExecutionDependenciesCannotProduceAFallbackGateway() {
        try (var context = context()) {
            access(context);
            context.register(NewAiToolGatewayConfiguration.class);
            assertThrows(BeansException.class, context::refresh);
        }
    }

    @Test
    public void gatewayConfigurationIsRegisteredForBootAutoconfiguration() throws Exception {
        var declarations = new ClassPathResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .getContentAsString(StandardCharsets.UTF_8).lines().toList();
        assertTrue(declarations.contains(NewAiToolGatewayConfiguration.class.getName()));
    }

    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("arte.ai-new.enabled", true, "arte.ai-new-tool.enabled", true)));
        return context;
    }

    private void access(AnnotationConfigApplicationContext context) {
        context.registerBean(ToolExecutionAccess.class, () -> call -> {
            throw new AssertionError("No startup authorization");
        });
    }

    private void dependencies(AnnotationConfigApplicationContext context) {
        context.registerBean(ToolCatalogStore.class, () -> noIo(ToolCatalogStore.class));
        context.registerBean(ToolBindingStore.class, () -> noIo(ToolBindingStore.class));
        context.registerBean(ToolReleaseStore.class, () -> noIo(ToolReleaseStore.class));
        context.registerBean(ExecutionRecordCodec.class, JacksonExecutionRecordCodec::new);
        context.registerBean("newAiClock", Clock.class, Clock::systemUTC);
        context.registerBean(StaticToolRegistry.class, () -> {
            var fixture = fixture("text", "v1");
            return new StaticToolRegistry(List.of(fixture.registration(() -> {
                throw new AssertionError("No startup discovery");
            })));
        });
    }

    private <T> T noIo(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "NoIoTestStore";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new AssertionError(method.getName());
            };
            throw new AssertionError("No startup storage operation");
        }));
    }
}
