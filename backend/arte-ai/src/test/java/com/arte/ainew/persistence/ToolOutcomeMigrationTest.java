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
 * 新装基线与 S3-03 增量的三张核对表一致，增量不掩盖重复部署或结构漂移。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
public class ToolOutcomeMigrationTest {

    @Test
    public void incrementalDdlMatchesBaselineAndUpgradesAnExistingDatabase() throws Exception {
        var baseline = new ClassPathResource("arte-ai-new-ddl-mysql.sql");
        var migration = new ClassPathResource("arte-ai-tool-outcome-migration.sql");
        String full = baseline.getContentAsString(StandardCharsets.UTF_8), incremental = migration.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(full.substring(full.indexOf("create table arte_ai_tool_outcome_check")).trim(), incremental.substring(incremental.indexOf("create table arte_ai_tool_outcome_check")).trim());
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        try {
            new ResourceDatabasePopulator(baseline).execute(ds);
            for (String table : new String[]{"arte_ai_tool_outcome_evidence", "arte_ai_tool_outcome_check", "arte_ai_tool_outcome_scan"})
                jdbc.execute("DROP TABLE " + table);
            new ResourceDatabasePopulator(migration).execute(ds);
            assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_outcome_check", Integer.class));
            assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_outcome_evidence", Integer.class));
            assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_outcome_scan", Integer.class));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class, () -> new ResourceDatabasePopulator(migration).execute(ds));
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }
}
