package com.arte.ainew.admission;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.control.BudgetAccountInitializer;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.config.NewAiAdmissionConfiguration;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiBudgetController;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
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
            context.register(NewAiAdmissionConfiguration.class, NewAiHttpContext.class, NewAiBudgetController.class);
            context.refresh();
            assertTrue(context.getBeansOfType(ChatService.class).isEmpty());
            assertTrue(context.getBeansOfType(MybatisExecutionPersistence.class).isEmpty());
            assertTrue(context.getBeansOfType(BudgetAccountQueryService.class).isEmpty());
            assertTrue(context.getBeansOfType(NewAiBudgetController.class).isEmpty());
            assertFalse(context.containsBean("newAiPersistenceScheduler"));
        }
    }

    @Test
    public void enabledConfigurationBindsFixedRecordsAndStartsWithoutDdlAccountsOrModelClients() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var values = new LinkedHashMap<String, Object>();
            flatten("arte.ai-new", AdmissionFixture.properties(), values);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("new-ai-test", values));
            context.registerBean("appDataSource", DataSource.class, () -> forbidden("physical app datasource"));
            context.registerBean("multipleDataSource", DataSource.class, () -> forbidden("primary routing datasource"), bd -> bd.setPrimary(true));
            context.registerBean("chatService", String.class, () -> "existing-bean");
            context.register(NewAiAdmissionConfiguration.class, NewAiHttpContext.class, NewAiBudgetController.class);
            context.refresh();
            assertEquals(AdmissionFixture.properties(), context.getBean(NewAiProperties.class));
            assertEquals("existing-bean", context.getBean("chatService"));
            assertNotNull(context.getBean("newAiChatService", ChatService.class));
            assertNotNull(context.getBean(CapabilityCatalog.class));
            assertNotNull(context.getBean(BindingManager.class));
            assertNotNull(context.getBean(ConnectionManager.class));
            assertNotNull(context.getBean(InvocationCoordinator.class));
            assertNotNull(context.getBean(BudgetAccountQueryService.class));
            assertNotNull(context.getBean(NewAiBudgetController.class));
            assertTrue(context.getBeansOfType(ModelGateway.class).isEmpty());
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
            var sources = new org.springframework.boot.env.YamlPropertySourceLoader().load("example",
                    new FileSystemResource("examples/ainew-admission.yml"));
            sources.forEach(source -> context.getEnvironment().getPropertySources().addFirst(source));
            context.registerBean("appDataSource", DataSource.class, () -> forbidden("example datasource"));
            context.register(NewAiAdmissionConfiguration.class);
            context.refresh();
            assertEquals("example-release-v1", context.getBean(NewAiProperties.class).releaseRef());
            assertNotNull(context.getBean(ChatService.class));
        }
    }

    private static void flatten(String prefix, Object value, Map<String, Object> result) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
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
