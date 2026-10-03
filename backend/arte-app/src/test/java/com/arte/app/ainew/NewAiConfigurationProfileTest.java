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

    @Test
    void profileConfigurationWiresChatAndItsDependenciesWithoutCallingTheProvider() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(profileEnvironment());
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            var datasource = new JdbcDataSource();
            datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID());
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(datasource));
            context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
            context.registerBean(TokenService.class, () -> mock(TokenService.class));
            context.registerBean(SqlSessionFactory.class, () -> mock(SqlSessionFactory.class));
            context.register(Transactions.class, NewSecurityConfiguration.class, NewExecutionSupportConfiguration.class,
                    NewModelConfiguration.class, NewChatConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(NewChatBootstrapService.class));
            assertNotNull(context.getBean(NewChatCallService.class));
            assertNotNull(context.getBean(PinnedHttpConnectionRuntime.class));
        }
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
