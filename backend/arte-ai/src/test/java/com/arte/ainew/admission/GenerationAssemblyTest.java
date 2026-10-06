package com.arte.ainew.admission;

import com.arte.ainew.config.NewAiAdmissionConfiguration;
import com.arte.ainew.config.NewAiGenerationConfiguration;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.infrastructure.http.EnvironmentCredentialResolver;
import com.arte.ainew.infrastructure.http.HttpConnectionRuntime;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import com.arte.ainew.spi.gateway.ModelGateway;
import org.junit.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
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
            assertEquals(0, credentialReads.get());
            assertEquals("ARTE_DEEPSEEK_API_KEY", context.getBean(NewAiGenerationProperties.class).secrets().getFirst().environmentVariable());
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
