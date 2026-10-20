package com.arte.ainew.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ToolChatRunMigrationTest {

    @Test
    public void incrementalDdlMatchesBaselineAndCanBeAppliedToAnExistingDatabase() throws Exception {
        var baseline = new ClassPathResource("arte-ai-new-ddl-mysql.sql");
        var migration = new ClassPathResource("arte-ai-tool-chat-run-migration.sql");
        String full = baseline.getContentAsString(StandardCharsets.UTF_8), part = migration.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(full.substring(full.indexOf("-- S4-02"), full.indexOf("-- S4-03")).trim(), part.trim());
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        try {
            new ResourceDatabasePopulator(baseline).execute(source);
            for (String table : new String[]{"arte_ai_tool_chat_budget_allocation", "arte_ai_tool_chat_budget", "arte_ai_tool_chat_link", "arte_ai_tool_chat_run_event", "arte_ai_tool_chat_run_command", "arte_ai_tool_chat_run_step", "arte_ai_tool_chat_run_work", "arte_ai_tool_chat_run"})
                jdbc.execute("DROP TABLE " + table);
            new ResourceDatabasePopulator(migration).execute(source);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_chat_run", Integer.class).intValue());
            assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class, () -> new ResourceDatabasePopulator(migration).execute(source));
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }
}
