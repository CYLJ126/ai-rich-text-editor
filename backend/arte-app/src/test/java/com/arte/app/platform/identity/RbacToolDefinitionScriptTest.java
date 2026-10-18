package com.arte.app.platform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TOOL 权限部署脚本只登记定义，重复执行保留已有状态和授权关系。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
class RbacToolDefinitionScriptTest {
    @Test
    void registersMissingDefinitionsWithoutGrantsOrOverwritingDisabledDefinitions() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:tool-rbac-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        var script = Files.readString(Path.of("scripts/arte-ai-tool-rbac-dml-mysql.sql"));
        var baseline = Files.readString(Path.of("scripts/arte-rbac-dml-mysql.sql"));
        assertEquals(script, baseline.substring(baseline.indexOf("-- AI TOOL S1-08:")));
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(Files.readAllBytes(Path.of("scripts/arte-rbac-ddl-mysql.sql"))));
            jdbc.update("INSERT INTO arte_rbac_menu_operation(menu_code,operation_code,operation_name,status,row_version,create_time) VALUES('aiTool','list','保留已有定义',3,7,CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO arte_rbac_relation(source,target,binding_type,create_time) VALUES('alice','aiTool:list','user_to_operation',CURRENT_TIMESTAMP)");
            for (int i = 0; i < 2; i++)
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_menu_operation", Integer.class));
            assertEquals(3, jdbc.queryForObject("SELECT status FROM arte_rbac_menu_operation WHERE menu_code='aiTool' AND operation_code='list'", Integer.class));
            assertEquals(7, jdbc.queryForObject("SELECT row_version FROM arte_rbac_menu_operation WHERE menu_code='aiTool' AND operation_code='list'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_relation", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_menu", Integer.class));
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }
}
