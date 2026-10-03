package com.arte.app.ainew;

import com.arte.app.api.rbac.TokenService;
import com.arte.app.execution.support.NewExecutionSupportConfiguration;
import com.arte.app.security.bridge.NewSecurityConfiguration;
import com.arte.base.model.security.SecretRef;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NewAiConfigurationProfileTest {
    @TempDir
    Path directory;

    private StandardEnvironment profileEnvironment() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("configuration-test", Map.of(
                // 同时搜索依赖模块的 profile 配置，并以实际编译的主应用配置覆盖测试 application.yml。
                "spring.config.location", "classpath:/;" + Path.of("target/classes").toAbsolutePath().toUri(),
                "arte.execution.support.artifact-directory", directory.toString())));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }

    @Test
    void mainApplicationIncludesTheMavenFilteredNewAiProfile() throws Exception {
        var environment = profileEnvironment();
        assertTrue(Set.of(environment.getActiveProfiles()).containsAll(Set.of("core", "ai", "ai-new")));
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../profile/app.properties"))) {
            properties.load(reader);
        }
        for (var key : properties.stringPropertyNames()) {
            if ((key.startsWith("arte.ai-new.") || key.startsWith("arte.security.bridge.")
                    || key.startsWith("arte.execution.support.")) && !key.endsWith("artifact-directory")) {
                // 不把密钥内容放进断言诊断。
                assertTrue(Objects.equals(properties.getProperty(key), environment.getProperty(key)), key);
            }
        }
        try (var resource = getClass().getResourceAsStream("/application-ai-new.yml")) {
            assertNotNull(resource);
            assertFalse(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("@arte."));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void profileConfigurationWiresChatAndItsDependenciesWithoutCallingTheProvider(boolean existingRegistry) throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(profileEnvironment());
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            var datasource = new JdbcDataSource();
            datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            try (var connection = datasource.getConnection()) {
                for (var name : List.of("arte-ai-new-model-ddl-mysql.sql", "arte-ai-new-work-ddl-mysql.sql", "arte-ai-new-chat-ddl-mysql.sql"))
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                            com.arte.app.testsupport.MySqlTestScripts.h2Resource(chatDdl(name)));
            }
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(datasource));
            context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
            context.registerBean(TokenService.class, () -> mock(TokenService.class));
            context.registerBean(SqlSessionFactory.class, () -> mock(SqlSessionFactory.class));
            var suppliedRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            if (existingRegistry)
                context.registerBean(io.micrometer.core.instrument.MeterRegistry.class, () -> suppliedRegistry);
            context.register(Transactions.class, NewSecurityConfiguration.class, NewExecutionSupportConfiguration.class,
                    NewModelConfiguration.class, NewChatConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(NewChatBootstrapService.class));
            assertNotNull(context.getBean(NewChatCallService.class));
            assertNotNull(context.getBean(PinnedHttpConnectionRuntime.class));
            var registry = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            if (existingRegistry) assertSame(suppliedRegistry, registry);
            assertInstanceOf(com.arte.app.execution.support.MicrometerExecutionTelemetry.class,
                    context.getBean(com.arte.base.spi.observability.Telemetry.class));
            assertEquals(0, registry.get("arte.execution.workers.active").gauge().value());
            assertEquals(0, registry.get("arte.execution.workers.queued").gauge().value());
            if (!existingRegistry) suppliedRegistry.close();
        }
    }

    private static String chatDdl(String name) throws java.io.IOException {
        String sql = Files.readString(Path.of("scripts", name));
        return name.contains("chat") ? sql.substring(sql.indexOf("-- CHAT_TABLES_BEGIN")) : sql;
    }

    @Test
    void compiledApiKeyIsUsedOnlyForItsMatchingReferenceAndEmptyKeyFallsBackToEnvironment() {
        var ref = new SecretRef("ARTE_NEW_MODEL_API_KEY", null);
        var configured = NewModelConfiguration.modelCredentials(ref.secretId(), "test-configured-key", name -> {
            fail("configured key must not read the environment");
            return null;
        });
        assertArrayEquals("test-configured-key".toCharArray(), configured.apply(ref));
        assertThrows(IllegalArgumentException.class, () -> configured.apply(new SecretRef("OTHER_KEY", null)));
        var fallback = NewModelConfiguration.modelCredentials(ref.secretId(), "", name -> "test-environment-key");
        assertArrayEquals("test-environment-key".toCharArray(), fallback.apply(ref));
        assertNull(NewModelConfiguration.modelCredentials(ref.secretId(), "", name -> null).apply(ref));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {
    }
}
