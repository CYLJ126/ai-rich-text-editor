package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.config.NewAiAdmissionConfiguration;
import com.arte.ainew.config.NewAiGenerationConfiguration;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.ainew.infrastructure.http.EnvironmentCredentialResolver;
import com.arte.ainew.infrastructure.http.HttpConnectionRuntime;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.core.pojo.reference.DefinitionRef;
import org.junit.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 装配不访问数据源、供应商或环境变量值，默认关闭及自定义凭据解析器可替换。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public class GenerationAssemblyTest {

    private static AnnotationConfigApplicationContext context(boolean example) throws Exception {
        var context = new AnnotationConfigApplicationContext();
        if (example) {
            new YamlPropertySourceLoader().load("generation-example", new FileSystemResource("examples/ainew-generation.yml"))
                    .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            context.registerBean("appDataSource", DataSource.class, () -> new AbstractDataSource() {
                public Connection getConnection() {
                    throw new AssertionError("Startup must not connect to database");
                }

                public Connection getConnection(String name, String password) {
                    return getConnection();
                }
            });
        }
        context.register(NewAiAdmissionConfiguration.class, NewAiGenerationConfiguration.class);
        return context;
    }

    @Test
    public void defaultDisabledRequiresNoResources() throws Exception {
        try (var context = context(false)) {
            context.refresh();
            assertTrue(context.getBeansOfType(ModelGateway.class).isEmpty());
            assertTrue(context.getBeansOfType(HttpConnectionRuntime.class).isEmpty());
        }
    }

    @Test
    public void generationAloneCannotBypassAdmissionAssembly() throws Exception {
        try (var context = context(false)) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("generation-only", Map.of("arte.ai-new-generation.enabled", "true")));
            context.refresh();
            assertTrue(context.getBeansOfType(ModelGateway.class).isEmpty());
        }
    }

    @Test
    public void documentedExampleBindsAndCreatesGatewayWithoutStartingAnyInteraction() throws Exception {
        try (var context = context(true)) {
            var credentialReads = new AtomicInteger();
            ConnectionCredentialResolver custom = (secret, execution) -> Mono.fromSupplier(() -> {
                credentialReads.incrementAndGet();
                throw new AssertionError("Startup must not resolve credential values");
            });
            context.registerBean("testCredentialResolver", ConnectionCredentialResolver.class, () -> custom);
            context.refresh();
            assertNotNull(context.getBean(ModelGateway.class));
            assertNotNull(context.getBean("newAiHttpConnectionRuntime"));
            assertSame(custom, context.getBean(ConnectionCredentialResolver.class));
            var configuration = context.getBean(NewAiProperties.class);
            var localCapability = new CapabilityDescriptor(new DefinitionRef("capability", "counter", "v1"),
                    CapabilityDescriptor.Kind.TOOL, new DefinitionRef("schema", "counter-input", "v1"),
                    new DefinitionRef("schema", "counter-output", "v1"), Set.of(),
                    CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
            var local = new ResolvedBinding(new DefinitionRef("binding", "counter", "v1"), localCapability,
                    new ExecutionTarget.Local(new DefinitionRef("tool-adapter", "counter", "v1")), null, null);
            assertFalse(context.getBean(ModelGateway.class).supportsTextChat(local, configuration.connections().getFirst()));
            var grant = configuration.grants().getFirst();
            var execution = context.getBean(ExecutionContextFactory.class).create(
                    UsernamePasswordAuthenticationToken.authenticated(grant.subjectName(), "unused", List.of()),
                    new ExecutionContextRequest(grant.tenantId(), grant.workspaceId(), Set.of(AdmissionAuthorization.INVOKE),
                            Duration.ofSeconds(30), null, null, configuration.releaseRef(), null)).block();
            var options = context.getBean(ChatConfigurationQueryService.class).discover(execution).block();
            assertNotNull(options);
            assertEquals(1, options.options().size());
            assertEquals("deepseek-chat", options.options().getFirst().displayName());
            assertEquals(List.of("example-budget"), options.options().getFirst().budgetRefs());
            assertEquals(0, credentialReads.get());
            assertEquals("ARTE_DEEPSEEK_API_KEY", context.getBean(NewAiGenerationProperties.class).secrets().getFirst().environmentVariable());
        }
    }

    @Test
    public void modelGatewayKeepsModelRuntimeWhenToolRuntimesArePresent() throws Exception {
        for (var names : List.of(List.of("newAiToolMcpRuntime"),
                List.of("newAiToolMcpRuntime", "newAiToolHttpRuntime"))) {
            try (var context = context(true)) {
                for (String name : names) {
                    context.registerBean(name, HttpConnectionRuntime.class, () -> new HttpConnectionRuntime(
                            (reference, execution) -> Mono.error(new AssertionError("Assembly must not resolve tool connections")),
                            null,
                            (reference, execution) -> Mono.error(new AssertionError("Assembly must not resolve tool credentials")),
                            new NewAiGenerationProperties(false, List.of(URI.create("https://tool.invalid")),
                                    List.of(), 0, 0, 0, null, 0, 0), Clock.systemUTC()));
                }
                context.refresh();
                assertEquals(names.size() + 1, context.getBeansOfType(HttpConnectionRuntime.class).size());
                assertSame(context.getBean("newAiHttpConnectionRuntime"),
                        ReflectionTestUtils.getField(context.getBean(ModelGateway.class), "runtime"));
            }
        }
    }

    @Test
    public void defaultEnvironmentResolverIsInstalledAndUnknownOptionsFailStrictly() throws Exception {
        try (var context = context(true)) {
            context.refresh();
            assertTrue(context.getBean(ConnectionCredentialResolver.class) instanceof EnvironmentCredentialResolver);
        }
        try (var context = context(true)) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("typo", Map.of("arte.ai-new-generation.max-retries", "3")));
            assertThrows(org.springframework.beans.BeansException.class, context::refresh);
        }
    }
}
