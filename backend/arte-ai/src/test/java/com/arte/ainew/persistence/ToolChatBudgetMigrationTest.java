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

public class ToolChatBudgetMigrationTest {

    @Test
    public void incrementalDdlMatchesBaselineAndDoesNotInventFundsForExistingRuns() throws Exception {
        var baseline = new ClassPathResource("arte-ai-new-ddl-mysql.sql");
        var migration = new ClassPathResource("arte-ai-tool-chat-budget-migration.sql");
        String full = baseline.getContentAsString(StandardCharsets.UTF_8), part = migration.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(full.substring(full.indexOf("-- S4-04")).trim(), part.trim());
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        try {
            new ResourceDatabasePopulator(baseline).execute(source);
            jdbc.execute("DROP TABLE arte_ai_tool_chat_budget_allocation");
            jdbc.execute("DROP TABLE arte_ai_tool_chat_budget");
            jdbc.update("INSERT INTO arte_ai_tool_chat_run(run_key,owner_key,idempotency_key,submission_digest,input_digest,input_snapshot,state_digest,state_snapshot,version,deadline) VALUES('legacy','owner','key','digest','digest','old-input','digest','old-state',0,9999)");
            new ResourceDatabasePopulator(migration).execute(source);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_chat_budget", Integer.class).intValue());
            assertEquals("old-input", jdbc.queryForObject("SELECT input_snapshot FROM arte_ai_tool_chat_run", String.class));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class, () -> new ResourceDatabasePopulator(migration).execute(source));
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }
}
