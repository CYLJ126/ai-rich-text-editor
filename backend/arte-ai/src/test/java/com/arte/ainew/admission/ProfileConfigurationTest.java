package com.arte.ainew.admission;

import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.execution.DefaultInvocationCoordinator;
import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.config.*;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import org.junit.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * 验证 Maven 参数实际进入运行时配置，完整示例可绑定；不访问数据库、凭据值或模型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 19:35 ✾
 */
public class ProfileConfigurationTest {
    private static final String RESOURCE = "ainew/config/application.properties";

    private void verifyAssembly(List<PropertySource<?>> sources) {
        try (var context = new AnnotationConfigApplicationContext()) {
            sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("manual-test",
                    Map.of("arte.ai-new-execution.worker-enabled", "false", "arte.ai-new-events.transport", "local")));
            context.registerBean("appDataSource", DataSource.class, () -> new AbstractDataSource() {
                @Override
                public Connection getConnection() {
                    throw new AssertionError("Configuration must not access database");
                }

                @Override
                public Connection getConnection(String username, String password) {
                    return getConnection();
                }
            });
            context.registerBean("testCredentialResolver", ConnectionCredentialResolver.class, () -> (secret, execution) ->
                    Mono.error(new AssertionError("Configuration must not read credential values")));
            context.register(NewAiAdmissionConfiguration.class, NewAiGenerationConfiguration.class, NewAiExecutionConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(ChatService.class));
            assertTrue(context.getBean(InvocationCoordinator.class) instanceof DefaultInvocationCoordinator);
            var admission = context.getBean(NewAiProperties.class);
            assertEquals(admission.capabilities().getFirst(), admission.bindings().getFirst().capability());
            assertEquals(admission.connections().getFirst().definition(), admission.bindings().getFirst().connection());
            assertEquals(admission.rates().getFirst().definition(), admission.budgets().getFirst().rate());
            assertEquals("ARTE_DEEPSEEK_API_KEY", context.getBean(NewAiGenerationProperties.class)
                    .secrets().getFirst().environmentVariable());
            assertFalse(context.getBean(InvocationDispatchWorker.class).isRunning());
        }
    }

    @Test
    public void mavenFilteredProfileConfigurationIsCompleteAndCanAssemble() throws Exception {
        var resource = new ClassPathResource(RESOURCE);
        var text = resource.getContentAsString(StandardCharsets.UTF_8);
        assertFalse("Unresolved Maven profile parameters", text.contains("@arte.ai-new"));
        verifyAssembly(new PropertiesPropertySourceLoader().load("filtered-profile", resource));
    }

    @Test
    public void checkedInExampleProvidesAllTemplateParametersAndBinds() throws Exception {
        var parameters = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../profile/app.properties.example"), StandardCharsets.UTF_8)) {
            parameters.load(reader);
        }
        var template = Files.readString(Path.of("src/main/resources/" + RESOURCE));
        var matcher = Pattern.compile("@([^@\\s]+)@").matcher(template);
        var rendered = new StringBuilder();
        while (matcher.find()) {
            var value = parameters.getProperty(matcher.group(1));
            assertNotNull("Missing example parameter: " + matcher.group(1), value);
            matcher.appendReplacement(rendered, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        var values = new Properties();
        values.load(new java.io.StringReader(rendered.toString()));
        var source = new org.springframework.core.env.PropertiesPropertySource("example-profile", values);
        verifyAssembly(List.of(source));
    }

    @Test
    public void aiProfileImportsRuntimeConfigurationAndKeepsCredentialAsEnvironmentReference() throws Exception {
        var profile = new ClassPathResource("application-ai.yml");
        var sources = new YamlPropertySourceLoader().load("ai-profile", profile);
        assertEquals("classpath:" + RESOURCE, sources.getFirst().getProperty("spring.config.import"));
        var text = profile.getContentAsString(StandardCharsets.UTF_8);
        assertTrue("Legacy key must stay a runtime environment reference", text.contains("${ARTE_DEEPSEEK_API_KEY}"));
        assertFalse("Credential values must not enter application resource", Pattern.compile("sk-[a-zA-Z0-9]{20,}").matcher(text).find());
    }
}
