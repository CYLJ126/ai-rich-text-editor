package com.arte.ainew.admission;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.control.BudgetAccountInitializer;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.config.NewAiAdmissionConfiguration;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.testing.TestAiConfiguration;
import com.arte.ainew.testing.TestSources;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiBudgetController;
import com.arte.ainew.web.controller.NewAiConfigurationController;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;

import static org.junit.Assert.*;

/**
 * 验证 Spring 属性绑定、默认关闭、Bean 名隔离、物理数据源选择及启动零 SQL。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public class ConfigurationAssemblyTest {
    private static DataSource forbidden(String name) {
        return new AbstractDataSource() {
            public Connection getConnection() {
                throw new AssertionError("Startup must not access " + name);
            }

            public Connection getConnection(String username, String password) {
                return getConnection();
            }
        };
    }

    @Test
    public void disabledConfigurationRequiresNoDataSourceOrGrantsAndAddsNoRuntimeBeans() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(NewAiAdmissionConfiguration.class, com.arte.ainew.config.NewAiToolConfiguration.class,
                    NewAiHttpContext.class, NewAiBudgetController.class, NewAiConfigurationController.class);
            TestSources.install(context);
            context.refresh();
            assertTrue(context.getBeansOfType(ChatService.class).isEmpty());
            assertTrue(context.getBeansOfType(MybatisExecutionPersistence.class).isEmpty());
            assertTrue(context.getBeansOfType(com.arte.ainew.spi.persistence.ToolCatalogStore.class).isEmpty());
            assertTrue(context.getBeansOfType(com.arte.ainew.spi.persistence.ToolReleaseStore.class).isEmpty());
            assertTrue(context.getBeansOfType(com.arte.ainew.application.tool.discovery.StaticToolDiscovery.class).isEmpty());
            assertTrue(context.getBeansOfType(BudgetAccountQueryService.class).isEmpty());
            assertTrue(context.getBeansOfType(NewAiBudgetController.class).isEmpty());
            assertTrue(context.getBeansOfType(ChatConfigurationQueryService.class).isEmpty());
            assertTrue(context.getBeansOfType(NewAiConfigurationController.class).isEmpty());
            assertFalse(context.containsBean("newAiPersistenceScheduler"));
        }
    }

    @Test
    public void enabledConfigurationBindsDeploymentParametersAndStartsWithoutDdlAccountsOrModelClients() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var values = new LinkedHashMap<String, Object>();
            flatten("arte.ai-new", AdmissionFixture.properties(), values);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("new-ai-test", values));
            context.registerBean("appDataSource", DataSource.class, () -> forbidden("physical app datasource"));
            context.registerBean("multipleDataSource", DataSource.class, () -> forbidden("primary routing datasource"), bd -> bd.setPrimary(true));
            context.registerBean("chatService", String.class, () -> "existing-bean");
            context.register(NewAiAdmissionConfiguration.class, com.arte.ainew.config.NewAiToolConfiguration.class,
                    NewAiHttpContext.class, NewAiBudgetController.class, NewAiConfigurationController.class);
            TestSources.install(context);
            context.refresh();
            assertEquals(AdmissionFixture.properties().deployment(), context.getBean(com.arte.ainew.config.NewAiProperties.class));
            assertEquals("existing-bean", context.getBean("chatService"));
            assertNotNull(context.getBean("newAiChatService", ChatService.class));
            assertNotNull(context.getBean(CapabilityCatalog.class));
            assertNotNull(context.getBean(BindingManager.class));
            assertNotNull(context.getBean(ConnectionManager.class));
            assertNotNull(context.getBean(InvocationCoordinator.class));
            assertNotNull(context.getBean(com.arte.ainew.spi.persistence.ToolCatalogStore.class));
            assertNotNull(context.getBean(com.arte.ainew.spi.persistence.ToolReleaseStore.class));
            assertTrue(context.getBeansOfType(com.arte.ainew.application.tool.discovery.StaticToolDiscovery.class).isEmpty());
            assertNotNull(context.getBean(BudgetAccountQueryService.class));
            assertEquals(1, context.getBeansOfType(com.arte.ainew.spi.control.PublishedAiCatalog.class).size());
            assertEquals(1, context.getBeansOfType(com.arte.ainew.spi.control.BudgetDefinitionProvider.class).size());
            assertEquals(1, context.getBeansOfType(com.arte.ainew.spi.control.AiResourceEntitlementService.class).size());
            assertEquals(1, context.getBeansOfType(com.arte.ainew.spi.control.AiExecutionPolicyProvider.class).size());
            assertEquals(1, context.getBeansOfType(com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard.class).size());
            assertNotNull(context.getBean(NewAiBudgetController.class));
            assertNotNull(context.getBean(ChatConfigurationQueryService.class));
            assertNotNull(context.getBean(NewAiConfigurationController.class));
            assertTrue(context.getBeansOfType(ModelGateway.class).isEmpty());
        }
    }

    @Test
    public void fixedGenerationConfigurationDoesNotSilentlyAcceptNewTargetFields() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var values = new LinkedHashMap<String, Object>();
            flatten("arte.ai-new", AdmissionFixture.properties(), values);
            values.put("arte.ai-new.bindings[0].target.kind", "REMOTE");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("unsupported-target", values));
            context.registerBean("appDataSource", DataSource.class, () -> forbidden("strict target configuration"));
            context.register(NewAiAdmissionConfiguration.class);
            assertThrows(org.springframework.beans.BeansException.class, context::refresh);
        }
    }

    @Test
    public void removedMetadataBackfillPropertyFailsStrictBinding() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var values = new LinkedHashMap<String, Object>();
            flatten("arte.ai-new", AdmissionFixture.properties(), values);
            String key = "arte.ai-new.conversation-metadata-backfill.enabled";
            values.put(key, "true");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("removed-backfill-property", values));
            context.registerBean("appDataSource", DataSource.class, () -> forbidden("invalid configuration datasource"));
            context.register(NewAiAdmissionConfiguration.class);
            var error = assertThrows(org.springframework.beans.BeansException.class, context::refresh);
            assertTrue(error.getMostSpecificCause() instanceof
                    org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException);
            assertTrue(error.getMostSpecificCause().getMessage().contains(key));
        }
    }

    @Test
    public void optedInButIncompleteConfigurationFailsInsteadOfPretendingReady() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("incomplete", Map.of("arte.ai-new.enabled", "true")));
            context.register(NewAiAdmissionConfiguration.class);
            assertThrows(org.springframework.beans.BeansException.class, context::refresh);
        }
    }

    @Test
    public void commandsUsePhysicalDatasourceEvenWhenRoutingDatasourceIsPrimary() {
        var physical = new JdbcDataSource();
        physical.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(physical);
        try (var context = new AnnotationConfigApplicationContext()) {
            var values = new LinkedHashMap<String, Object>();
            flatten("arte.ai-new", AdmissionFixture.properties(), values);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixed-config", values));
            context.registerBean("appDataSource", DataSource.class, () -> physical);
            context.registerBean("multipleDataSource", DataSource.class, () -> forbidden("routing datasource"), bd -> bd.setPrimary(true));
            context.register(NewAiAdmissionConfiguration.class);
            TestSources.install(context);
            context.refresh();
            var execution = context.getBean(ExecutionContextFactory.class).create(
                    UsernamePasswordAuthenticationToken.authenticated("alice", "unused", List.of()),
                    new ExecutionContextRequest("tenant", "workspace", Set.of("ai:budget:admin"), AdmissionFixture.TIMEOUT,
                            null, "alice-budget", "release-v1", "initialize")).block();
            assertNotNull(context.getBean(BudgetAccountInitializer.class).initialize("alice-budget", execution).block());
            assertEquals(1, new JdbcTemplate(physical).queryForObject("SELECT COUNT(*) FROM arte_ai_account", Long.class).longValue());
        } finally {
            new JdbcTemplate(physical).execute("DROP ALL OBJECTS");
        }
    }

    @Test
    public void documentedYamlExampleBindsAndStartsWithoutOpeningConnections() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            var sources = new YamlPropertySourceLoader().load("example",
                    new FileSystemResource("examples/ainew-admission.yml"));
            sources.forEach(source -> context.getEnvironment().getPropertySources().addFirst(source));
            context.registerBean("appDataSource", DataSource.class, () -> forbidden("example datasource"));
            context.register(NewAiAdmissionConfiguration.class);
            TestSources.install(context);
            context.refresh();
            assertEquals("example-release-v1", context.getBean(com.arte.ainew.config.NewAiProperties.class).releaseRef());
            assertNotNull(context.getBean(ChatService.class));
        }
    }

    static void flatten(String prefix, Object value, Map<String, Object> result) {
        if (value instanceof TestAiConfiguration data) value = data.deployment();
        if (value == null) {
            return;
        }
        if (value instanceof ResolvedBinding binding) {
            // 固定生成配置保持旧六字段格式，不能把新目标的 Java 结构当作已开放的配置格式。
            flatten(prefix + ".definition", binding.definition(), result);
            flatten(prefix + ".capability", binding.capability(), result);
            flatten(prefix + ".connection", binding.connection(), result);
            flatten(prefix + ".remote-operation", binding.remoteOperation(), result);
            flatten(prefix + ".context-window-tokens", binding.contextWindowTokens(), result);
            flatten(prefix + ".rate", binding.rate(), result);
        } else if (value instanceof Collection<?> collection) {
            int index = 0;
            for (var item : collection) {
                flatten(prefix + "[" + index++ + "]", item, result);
            }
        } else if (value.getClass().isRecord()) {
            for (var component : value.getClass().getRecordComponents()) {
                var name = component.getName().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
                try {
                    flatten(prefix + "." + name, component.getAccessor().invoke(value), result);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
        } else {
            result.put(prefix, value instanceof Enum<?> item ? item.name() : value.toString());
        }
    }
}
