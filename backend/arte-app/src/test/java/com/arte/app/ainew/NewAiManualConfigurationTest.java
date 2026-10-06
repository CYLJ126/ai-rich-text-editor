package com.arte.app.ainew;

import com.alibaba.druid.pool.DruidDataSource;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.execution.DefaultInvocationCoordinator;
import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.config.*;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证手动联调容器可以独立装配，并绑定物理连接池；不访问数据库或模型。
 */
class NewAiManualConfigurationTest {

    @Test
    void assemblesAndBindsPhysicalPoolWithoutBootDataSourceAutoConfiguration() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            // 示例 profile 同样只有 bindings 的构建参数，没有运行资源中的嵌套 capability。
            NewAiManualIT.loadConfiguration(context, new FileSystemResource("../profile/app.properties.example"));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-only", Map.of(
                    "arte.ai-new.enabled", "true",
                    "arte.ai-new-generation.enabled", "true",
                    "arte.ai-new-execution.enabled", "true",
                    "arte.ai-new-execution.worker-enabled", "false",
                    "spring.datasource.druid.app.url", "jdbc:mysql://127.0.0.1:1/no_connection_expected",
                    "spring.datasource.druid.app.username", "test-user",
                    "spring.datasource.druid.app.password", "test-password",
                    "spring.datasource.druid.app.initial-size", "0",
                    "spring.datasource.druid.app.max-active", "3")));
            context.registerBean("testCredentialResolver", ConnectionCredentialResolver.class,
                    () -> (secret, execution) -> Mono.error(new AssertionError("Assembly must not read credentials")));
            context.register(NewAiManualIT.PhysicalDataSourceConfiguration.class,
                    NewAiAdmissionConfiguration.class, NewAiGenerationConfiguration.class, NewAiExecutionConfiguration.class);
            context.refresh();

            assertTrue(context.getBeansOfType(DataSourceProperties.class).isEmpty());
            var pool = context.getBean("appDataSource", DruidDataSource.class);
            assertEquals(DruidDataSource.class, pool.getClass());
            assertEquals("jdbc:mysql://127.0.0.1:1/no_connection_expected", pool.getUrl());
            assertEquals("test-user", pool.getUsername());
            assertEquals("test-password", pool.getPassword());
            assertEquals(3, pool.getMaxActive());
            assertFalse(pool.isInited(), "Assembly must not initialize database connections");
            assertEquals(0, pool.getCreateCount());
            assertNotNull(context.getBean(ChatService.class));
            assertInstanceOf(DefaultInvocationCoordinator.class, context.getBean(InvocationCoordinator.class));
            assertEquals("ARTE_DEEPSEEK_API_KEY", context.getBean(NewAiGenerationProperties.class)
                    .secrets().getFirst().environmentVariable());
            var properties = context.getBean(NewAiProperties.class);
            assertEquals(properties.capabilities().getFirst(), properties.bindings().getFirst().capability());
            assertFalse(context.getBean(InvocationDispatchWorker.class).isRunning());
        }
    }

    @Test
    void localProfileOnlySuppliesPhysicalDataSourceAndCannotOverrideAiListsOrStartWorker() throws Exception {
        var profile = """
                spring.datasource.druid.app.url=jdbc:mysql://127.0.0.1:1/local_test
                arte.ai-new.bindings[0].remote-operation=must-not-override-runtime
                arte.ai-new-execution.worker-enabled=true
                """;
        try (var context = new AnnotationConfigApplicationContext()) {
            NewAiManualIT.loadConfiguration(context, new ByteArrayResource(profile.getBytes(StandardCharsets.UTF_8)));
            var local = context.getEnvironment().getPropertySources().get("local-data-source");
            assertNotNull(local);
            assertEquals("jdbc:mysql://127.0.0.1:1/local_test", local.getProperty("spring.datasource.druid.app.url"));
            assertNull(local.getProperty("arte.ai-new.bindings[0].remote-operation"));
            assertEquals("deepseek-chat", context.getEnvironment().getProperty("arte.ai-new.bindings[0].remote-operation"));
            assertEquals("capability", context.getEnvironment().getProperty("arte.ai-new.bindings[0].capability.definition.type"));
            assertEquals("false", context.getEnvironment().getProperty("arte.ai-new-execution.worker-enabled"));
        }
    }
}
