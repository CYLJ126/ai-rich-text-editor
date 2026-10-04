package com.arte.app.ainew;

import com.arte.ai.api.context.RagContextService;
import com.arte.ai.api.context.ResourceContextService;
import com.arte.app.api.richtext.ArticleService;
import com.arte.app.service.richtext.ArticleContextQueryService;
import com.arte.app.testsupport.MySqlTestScripts;
import com.arte.base.api.security.AuthorizationService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NewRagConfigurationTest {
    @Test void chatKeepsSourceAuthorizationWhenNewRetrievalIsDisabledWithoutRequiringEs() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("chat", Map.of(
                    "arte.ai-new.chat.enabled", "true", "arte.ai-new.chat.retrieval-enabled", "false")));
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(AuthorizationService.class, () -> mock(AuthorizationService.class));
            context.register(NewRagConfiguration.class, NewResourceContextConfiguration.class,
                    ArticleContextQueryService.class, ArticleResourceContextAdapter.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(ResourceContextService.class).size());
            assertNotNull(context.getBean(ArticleResourceContextAdapter.class));
            assertTrue(context.getBeansOfType(RagContextService.class).isEmpty());
            assertTrue(context.getBeansOfType(JdbcRetrievalPreviewStore.class).isEmpty());
        }
    }
    @Test void disabledRetrievalNeedsNoArticleOrPreviewInfrastructure() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(NewRagConfiguration.class, NewResourceContextConfiguration.class,
                    ArticleContextQueryService.class, ArticleResourceContextAdapter.class);
            context.refresh();
            assertTrue(context.getBeansOfType(RagContextService.class).isEmpty());
            assertTrue(context.getBeansOfType(ResourceContextService.class).isEmpty());
            assertTrue(context.getBeansOfType(ArticleContextQueryService.class).isEmpty());
        }
    }
    @Test void retrievalFlagWithoutChatDoesNotLoadInfrastructure() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("rag", Map.of("arte.ai-new.chat.retrieval-enabled", "true")));
            context.register(NewRagConfiguration.class, NewResourceContextConfiguration.class, ArticleContextQueryService.class, ArticleResourceContextAdapter.class);
            context.refresh(); assertTrue(context.getBeansOfType(ResourceContextService.class).isEmpty());
        }
    }
    @Test void ragWiresWithoutActionTablesAndSharesOneSourceVerifier() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("rag", Map.of(
                    "arte.ai-new.chat.enabled", "true", "arte.ai-new.chat.retrieval-enabled", "true", "arte.ai-new.action.enabled", "false")));
            var datasource = new JdbcDataSource();
            datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            try (var connection = datasource.getConnection()) {
                for (var name : List.of("arte-ai-new-model-ddl-mysql.sql", "arte-ai-new-chat-ddl-mysql.sql", "arte-ai-new-rag-ddl-mysql.sql")) {
                    var sql = Files.readString(Path.of("scripts", name));
                    if (name.contains("chat")) sql = sql.substring(sql.indexOf("-- CHAT_TABLES_BEGIN"));
                    ScriptUtils.executeSqlScript(connection, MySqlTestScripts.h2Resource(sql));
                }
            }
            var jdbc = new JdbcTemplate(datasource);
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(AuthorizationService.class, () -> mock(AuthorizationService.class));
            context.registerBean(ArticleService.class, () -> mock(ArticleService.class));
            context.register(NewRagConfiguration.class, NewAiActionConfiguration.class, ArticleContextQueryService.class, ArticleResourceContextAdapter.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(ResourceContextService.class).size());
            assertNotNull(context.getBean(RagContextService.class));
            assertNotNull(context.getBean(ArticleResourceContextAdapter.class));
            assertTrue(context.getBeansOfType(JdbcAiActionStore.class).isEmpty());
            assertThrows(RuntimeException.class, () -> jdbc.queryForList("SELECT * FROM arte_ai_new_action"));
        }
    }
}
