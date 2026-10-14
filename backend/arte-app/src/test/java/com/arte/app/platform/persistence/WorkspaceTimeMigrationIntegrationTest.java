package com.arte.app.platform.persistence;

import com.arte.core.pojo.workspace.WorkspaceCandidateKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用冻结的旧结构及真实 MySQL 验证迁移、重跑、时区与时间并列分页。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 15:28 ✾
 */
@EnabledIfSystemProperty(named = "platform.test.mysql", matches = ".+")
class WorkspaceTimeMigrationIntegrationTest {
    private final PlatformPersistenceIntegrationTest f = new PlatformPersistenceIntegrationTest() {
        @Override
        Path workspaceDdl() {
            return Path.of("src/test/resources/workspace-time-legacy-ddl-mysql.sql");
        }
    };

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        String subject = f.actor.principal().subjectId();
        f.jdbc.update("INSERT INTO arte_platform_tenant VALUES('legacy','legacy',1,NULL,0,?,0,0,1791600000,123456789,1791600001,999999999)", subject);
        f.jdbc.update("INSERT INTO arte_platform_tenant_member VALUES('tm','legacy',?,0,0,0,0)", subject);
        for (String id : List.of("a", "b", "c")) {
            // a/b 在截断后并列；c 位于纪元之前，用于避免 FROM_UNIXTIME 的范围和会话时区依赖。
            long seconds = id.equals("c") ? -1 : 1791600000;
            int nanos = id.equals("a") ? 123456999 : id.equals("b") ? 123456001 : 999999999;
            f.jdbc.update("INSERT INTO arte_platform_workspace VALUES('legacy',?,?, '',0,?,0,0,?,?,?,?)",
                    id, id, subject, seconds, nanos, seconds, nanos);
            f.jdbc.update("INSERT INTO arte_platform_workspace_member SELECT ?,tenant_id,id,?,SHA2(?,256),created_sec,created_nano,0,0,0,0 FROM arte_platform_workspace WHERE id=?",
                    "member-" + id, subject, subject, id);
        }
        // 原始字符串不解析也不重写，迁移前后按字节比较。
        f.jdbc.update("INSERT INTO arte_platform_command VALUES('legacy-command',SHA2('command',256),?,'legacy','a','workspace:rename','key','digest','workspace:view:v1',?,0)",
                subject, "{\"acceptedAt\":\"2026-10-09T00:00:00.123456789Z\"}");
        f.jdbc.update("INSERT INTO arte_platform_outbox(id,event_type,event,occurred_sec,occurred_nano,state,attempts) VALUES('event','workspace:changed',?,1791600000,123456789,0,0)",
                "{\"occurredAt\":\"2026-10-09T00:00:00.123456789Z\"}");
        f.jdbc.update("INSERT INTO arte_platform_audit VALUES('audit',?,'legacy','a','workspace:changed','legacy-command','',1791600000,123456789)", subject);
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private void migrate() throws Exception {
        String sql = Files.readString(Path.of("scripts", "arte-platform-workspace-time-micros-migration-mysql.sql"))
                .replace("DELIMITER $$", "").replace("DELIMITER ;", "")
                .replace("SET time_zone = '+00:00';", "SET time_zone = '+00:00'$$");
        try (var connection = f.source.getConnection()) {
            connection.createStatement().execute("SET @arte_workspace_time_migration_confirmed=1");
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.support.EncodedResource(
                            new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8),
                    false, false, "--", "$$", "/*", "*/");
        }
    }

    @Test
    void migrationPreservesDataHistoryAndStableScanAcrossTruncationTies() throws Exception {
        var receipts = f.jdbc.queryForList("SELECT * FROM arte_platform_command");
        var events = f.jdbc.queryForList("SELECT * FROM arte_platform_outbox");
        var audits = f.jdbc.queryForList("SELECT * FROM arte_platform_audit");
        migrate();
        migrate();
        assertEquals(receipts, f.jdbc.queryForList("SELECT * FROM arte_platform_command"));
        assertEquals(events, f.jdbc.queryForList("SELECT * FROM arte_platform_outbox"));
        assertEquals(audits, f.jdbc.queryForList("SELECT * FROM arte_platform_audit"));
        var seen = new ArrayList<String>();
        WorkspaceCandidateKey boundary = null;
        boolean exhausted;
        do {
            var page = f.workspaces.scanMembershipCandidates(f.actor.principal().subjectId(), boundary, 1);
            seen.addAll(page.records().stream().map(key -> key.scope().workspaceId()).toList());
            if (!page.records().isEmpty()) boundary = page.records().getLast();
            exhausted = page.exhausted();
        } while (!exhausted);
        assertEquals(List.of("a", "b", "c"), seen);
        assertEquals(Instant.parse("1969-12-31T23:59:59.999999Z"), boundary.createdAt());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('arte_platform_tenant','arte_platform_workspace','arte_platform_workspace_member') AND column_name LIKE '%\\_nano'", Integer.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> f.jdbc.update(
                "UPDATE arte_platform_workspace_member SET workspace_created_at=TIMESTAMPADD(MICROSECOND,1,workspace_created_at) WHERE workspace_id='a'"));
        var invalidTime = assertThrows(org.springframework.dao.DataAccessException.class, () -> f.jdbc.update(
                "UPDATE arte_platform_workspace SET updated_at=TIMESTAMPADD(MICROSECOND,-1,created_at) WHERE id='a'"));
        assertEquals(3819, ((java.sql.SQLException) invalidTime.getRootCause()).getErrorCode());
    }

    @Test
    void utcMappingDoesNotDependOnJvmOrDatabaseSessionTimeZone() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            migrate();
            try (var connection = f.source.getConnection()) {
                connection.createStatement().execute("SET time_zone='+08:00'");
                var statement = connection.prepareStatement("SELECT created_at FROM arte_platform_workspace WHERE id='a'");
                var result = statement.executeQuery();
                assertTrue(result.next());
                assertEquals(Instant.ofEpochSecond(1791600000, 123456000),
                        result.getObject(1, java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC));
            }
            assertEquals(Instant.ofEpochSecond(1791600000, 123456000),
                    f.workspaces.find(new com.arte.core.pojo.platform.Scope("legacy", "a")).orElseThrow().createdAt());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void invalidLegacyTimeIsRejectedBeforeAnyColumnConversion() {
        f.jdbc.update("UPDATE arte_platform_tenant SET created_nano=-1 WHERE id='legacy'");
        assertThrows(Exception.class, this::migrate);
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='arte_platform_tenant' AND column_name='created_at'", Integer.class));
        assertEquals(3, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_workspace", Integer.class));
    }

    @Test
    void partiallyAddedColumnsCanBeRecoveredWithoutChangingRecordCounts() throws Exception {
        f.jdbc.execute("ALTER TABLE arte_platform_workspace ADD COLUMN created_at DATETIME(6) NULL");
        migrate();
        assertEquals(3, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_workspace", Integer.class));
        assertEquals(3, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_workspace_member", Integer.class));
    }
}
