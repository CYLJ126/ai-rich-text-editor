package com.arte.app.platform.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 显式运行于独立 MySQL：对比全量新建与 TOOL 增量链的列及索引，不执行业务库 DDL。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 11:18 ✾
 */
class ToolMySqlMigrationAcceptanceTest {

    private static final List<String> SCHEDULING_TABLES = List.of("arte_ai_scheduling_permit", "arte_ai_dispatch_cursor",
            "arte_ai_dispatch_pool", "arte_ai_queue_capacity");

    @Test
    void freshAndSequentialUpgradeHaveMatchingColumnsAndIndexes() throws Exception {
        assertNotNull(System.getProperty("platform.test.mysql"), "Migration acceptance requires isolated MySQL");
        try (var rig = new ToolAcceptanceRig()) {
            var jdbc = rig.base.f.jdbc;
            var columns = columns(rig);
            var indexes = indexes(rig);
            var constraints = constraints(rig);
            var tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'arte_ai_tool_%'", String.class);
            // 仅撤去本次随机测试库的新增结构，按依赖反序恢复为升级前空基线，保留真实外键校验。
            for (var suffix : List.of("execution_snapshot", "binding_resolution", "binding_revision", "binding_command", "binding",
                    "release_command", "release", "discovery_command", "definition", "schema", "provider"))
                jdbc.execute("DROP TABLE arte_ai_tool_" + suffix);
            for (var table : SCHEDULING_TABLES) jdbc.execute("DROP TABLE " + table);
            for (var index : List.of("idx_arte_ai_outbox_class", "idx_arte_ai_outbox_queue", "idx_arte_ai_outbox_reserved"))
                jdbc.execute("DROP INDEX " + index + " ON arte_ai_outbox");
            for (var column : List.of("work_class", "provider_key", "space_key", "subject_key", "queued_at", "queue_deadline", "queue_pending", "queue_reserved", "finished"))
                jdbc.execute("ALTER TABLE arte_ai_outbox DROP COLUMN " + column);
            for (var script : List.of("catalog", "release", "binding", "management", "admission"))
                ScriptUtils.executeSqlScript(rig.ddl, new FileSystemResource("../arte-ai/scripts/arte-ai-tool-" + script + "-migration.sql"));
            ScriptUtils.executeSqlScript(rig.ddl, new FileSystemResource("../arte-ai/scripts/arte-ai-dispatch-scheduling-migration.sql"));
            assertEquals(columns, columns(rig));
            assertEquals(indexes, indexes(rig));
            assertEquals(constraints, constraints(rig));
            System.out.println("S209_MYSQL_MIGRATIONS tables=" + (tables.size() + SCHEDULING_TABLES.size()) + " columnsAndIndexes=MATCH");
        }
    }

    private static List<Map<String, Object>> columns(ToolAcceptanceRig rig) {
        return rig.base.f.jdbc.queryForList("SELECT table_name,column_name,column_type,is_nullable,column_default,extra FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name LIKE 'arte_ai_%' ORDER BY table_name,column_name");
    }

    private static List<Map<String, Object>> indexes(ToolAcceptanceRig rig) {
        return rig.base.f.jdbc.queryForList("SELECT table_name,index_name,non_unique,seq_in_index,column_name,sub_part,index_type FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name LIKE 'arte_ai_%' ORDER BY table_name,index_name,seq_in_index");
    }

    private static List<Map<String, Object>> constraints(ToolAcceptanceRig rig) {
        return rig.base.f.jdbc.queryForList("SELECT table_name,constraint_name,column_name,referenced_table_name,referenced_column_name "
                + "FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name LIKE 'arte_ai_%' "
                + "ORDER BY table_name,constraint_name,ordinal_position");
    }
}
