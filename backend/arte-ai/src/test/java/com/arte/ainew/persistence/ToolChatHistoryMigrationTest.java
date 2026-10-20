package com.arte.ainew.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class ToolChatHistoryMigrationTest {

    @Test
    public void existingInvocationGateIsPreservedAndNewBaselineEqualsIncrementalDdl() throws Exception {
        var baseline = new ClassPathResource("arte-ai-new-ddl-mysql.sql");
        var migration = new ClassPathResource("arte-ai-tool-chat-history-migration.sql");
        String full = baseline.getContentAsString(StandardCharsets.UTF_8), part = migration.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(full.substring(full.indexOf("-- S4-03")).trim(), part.trim());
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        try {
            new ResourceDatabasePopulator(baseline).execute(source);
            jdbc.execute("DROP TABLE arte_ai_tool_chat_run_event");
            jdbc.execute("DROP TABLE arte_ai_tool_chat_link");
            jdbc.execute("ALTER TABLE arte_ai_conversation_new DROP COLUMN active_kind");
            jdbc.update("INSERT INTO arte_ai_conversation_new(id_key,owner_key,version_no,active_invocation,workspace_snapshot) VALUES('conv','owner',4,'old-invocation','old-snapshot')");
            new ResourceDatabasePopulator(migration).execute(source);
            assertEquals("INVOCATION", jdbc.queryForObject("SELECT active_kind FROM arte_ai_conversation_new", String.class));
            assertEquals("old-invocation", jdbc.queryForObject("SELECT active_invocation FROM arte_ai_conversation_new", String.class));
            assertEquals("old-snapshot", jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_conversation_new", String.class));
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }
}
