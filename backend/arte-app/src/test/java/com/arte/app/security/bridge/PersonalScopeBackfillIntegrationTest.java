package com.arte.app.security.bridge;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PersonalScopeBackfillIntegrationTest extends SecurityBridgeFixture {
    @Test
    void backfillIsRepeatableKeepsRevocationsAndDoesNotGrantAiOrEgress() throws Exception {
        jdbc.update("UPDATE arte_security_member SET enabled = FALSE, revision = 2 WHERE user_id = 1");
        jdbc.update("UPDATE arte_security_resource SET tenant_id = 'team-tenant', workspace_id = 'team-space' WHERE resource_id = '10'");
        jdbc.update("INSERT INTO arte_rbac_user VALUES (3, 'inactive', '3', 1)");
        jdbc.update("INSERT INTO arte_rt_article VALUES (13, NULL, 'inactive', FALSE, 0), (14, NULL, 'unknown-owner', FALSE, 0)");
        backfill();
        backfill();
        assertFalse(repository.membership(TENANT, WORKSPACE, ALICE).orElseThrow().enabled());
        assertEquals("team-tenant", repository.placement(ARTICLE).orElseThrow().tenantId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_member WHERE user_id = 2", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_member WHERE user_id = 3", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_resource WHERE resource_id IN ('13', '14')", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_resource_grant", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_application_policy", Integer.class));
    }

    private void backfill() throws Exception {
        // H2 的无长度 CHAR 默认只有一位；测试中用 VARCHAR 对应 MySQL CAST(... AS CHAR) 的字符串语义。
        String sql = Files.readString(Path.of("scripts/arte-security-bridge-personal-backfill-mysql.sql"))
                .replace("cast(a.id as char)", "cast(a.id as varchar)")
                .replace("cast(c.id as char)", "cast(c.id as varchar)");
        try (var connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
