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

/**
 * 人工核对增量、基线与原 S3-04 数据兼容；部署 SQL 不进入运行时。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 12:28 ✾
 */
public class ToolReviewMigrationTest {

    @Test
    public void incrementalSchemaMatchesBaselineAndPreservesOriginalTables() throws Exception {
        var baseline = new ClassPathResource("arte-ai-new-ddl-mysql.sql");
        var migration = new ClassPathResource("arte-ai-tool-review-migration.sql");
        String full = baseline.getContentAsString(StandardCharsets.UTF_8), part = migration.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(full.substring(full.indexOf("-- S3-05")).trim(), part.trim());
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        try {
            new ResourceDatabasePopulator(baseline).execute(ds);
            jdbc.execute("DROP TABLE arte_ai_tool_outcome_review_command");
            jdbc.execute("DROP TABLE arte_ai_tool_outcome_review");
            jdbc.execute("DROP INDEX ix_tool_outcome_scope");
            new ResourceDatabasePopulator(migration).execute(ds);
            assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_outcome_review_command", Integer.class));
            assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_outcome_check", Integer.class));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class, () -> new ResourceDatabasePopulator(migration).execute(ds));
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }
}
