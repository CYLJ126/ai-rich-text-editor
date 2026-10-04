package com.arte.app.ainew;

import com.arte.ai.api.action.AiActionService;
import com.arte.ai.api.context.ResourceContextService;
import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.app.security.bridge.EgressConsentService;
import com.arte.app.security.bridge.ExecutionContextFactory;
import com.arte.app.service.richtext.ArticleContextQueryService;
import com.arte.app.testsupport.MySqlTestScripts;
import com.arte.base.api.security.AuthorizationService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewAiActionConfigurationTest {
    @Test
    void defaultDisabledDoesNotRequireInfrastructure() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(NewAiActionConfiguration.class, NewAiActionController.class);
            context.refresh();
            assertEquals(0, context.getBeansOfType(AiActionService.class).size());
            assertEquals(0, context.getBeansOfType(NewAiActionController.class).size());
        }
    }

    @Test
    void enabledWithoutModelFailsAtStartup() {
        try (var context = new AnnotationConfigApplicationContext()) {
            enable(context);
            context.register(NewAiActionConfiguration.class);
            assertThrows(RuntimeException.class, context::refresh);
        }
    }

    @Test
    void actionsWireWithChatDisabledAndNoChatTables() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            enable(context);
            var datasource = new JdbcDataSource();
            datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            try (var connection = datasource.getConnection()) {
                for (var name : java.util.List.of("arte-ai-new-model-ddl-mysql.sql", "arte-ai-new-action-ddl-mysql.sql"))
                    ScriptUtils.executeSqlScript(connection, MySqlTestScripts.h2Resource(Files.readString(Path.of("scripts", name))));
            }
            var jdbc = new JdbcTemplate(datasource);
            var manager = new DataSourceTransactionManager(datasource);
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(DataSourceTransactionManager.class, () -> manager);
            context.registerBean(InvocationCoordinator.class, () -> mock(InvocationCoordinator.class));
            context.registerBean(ExecutionContextFactory.class, () -> mock(ExecutionContextFactory.class));
            context.registerBean(EgressConsentService.class, () -> mock(EgressConsentService.class));
            context.registerBean(AuthorizationService.class, () -> mock(AuthorizationService.class));
            context.registerBean(JdbcModelExecutionStore.class, () -> new JdbcModelExecutionStore(jdbc, manager, java.time.Clock.systemUTC()));
            var definitions = mock(ConfiguredModelDefinitions.class);
            when(definitions.capabilityRef()).thenReturn(new DefinitionRef("ai-capability", "default-model", "v1"));
            when(definitions.bindingRef()).thenReturn(new DefinitionRef("ai-binding", "default-model", "v1"));
            context.registerBean(ConfiguredModelDefinitions.class, () -> definitions);
            context.register(NewAiActionConfiguration.class, NewAiActionController.class, NewChatConfiguration.class,
                    ArticleContextQueryService.class, ArticleResourceContextAdapter.class, NewResourceContextController.class);
            context.refresh();
            assertNotNull(context.getBean(AiActionService.class));
            assertNotNull(context.getBean(NewAiActionController.class));
            assertNotNull(context.getBean(AiActionEventStreams.class));
            assertNotNull(context.getBean(ResourceContextService.class));
            assertNotNull(context.getBean(ArticleResourceContextAdapter.class));
            assertNotNull(context.getBean(NewResourceContextController.class));
            assertTrue(context.getBeansOfType(NewChatCallService.class).isEmpty());
            assertThrows(RuntimeException.class, () -> jdbc.queryForList("SELECT * FROM arte_ai_new_conversation"));
        }
    }

    @Test
    void missingActionSchemaFailsFast() {
        var datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID());
        assertThrows(RuntimeException.class, () -> new JdbcAiActionStore(new JdbcTemplate(datasource), new DataSourceTransactionManager(datasource)));
    }

    private static void enable(AnnotationConfigApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("actions", Map.of(
                "arte.ai-new.action.enabled", "true", "arte.ai-new.chat.enabled", "false")));
    }
}
