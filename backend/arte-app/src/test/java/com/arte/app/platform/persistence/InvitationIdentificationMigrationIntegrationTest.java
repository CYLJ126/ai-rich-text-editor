package com.arte.app.platform.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 MySQL 验证旧自动命名 CHECK 升级、重复安装及邀请历史保留。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/10 16:50 ✾
 */
@EnabledIfSystemProperty(named = "platform.test.mysql", matches = ".+")
class InvitationIdentificationMigrationIntegrationTest {
    @Test
    void upgradesOldConstraintWithoutLosingHistoryOrWeakeningOtherChecks() throws Exception {
        var fixture = new PlatformPersistenceIntegrationTest();
        fixture.setup();
        try {
            var scope = fixture.initialize().value().workspace().scope();
            String subject = fixture.actor.principal().subjectId();
            fixture.jdbc.execute("ALTER TABLE arte_platform_membership_invitation DROP CHECK ck_platform_invitation_state,"
                    + " ADD CHECK (state BETWEEN 0 AND 3 AND version >= 0)");
            fixture.jdbc.update("INSERT INTO arte_platform_membership_invitation"
                            + " (id,tenant_id,workspace_id,target_subject_id,issued_by,role,state,expires_at,version)"
                            + " VALUES ('legacy',?,?,?,?,2,0,'2030-01-01',0)",
                    scope.tenantId(), scope.workspaceId(), subject, subject);
            var before = fixture.jdbc.queryForList("SELECT * FROM arte_platform_membership_invitation");
            Path migration = Path.of("scripts/arte-platform-membership-invitation-identification-migration-mysql.sql");
            fixture.installDdl(migration);
            fixture.installDdl(migration);
            assertEquals(before, fixture.jdbc.queryForList("SELECT * FROM arte_platform_membership_invitation"));
            assertEquals(1, fixture.jdbc.update("UPDATE arte_platform_membership_invitation SET state=4,version=1 WHERE id='legacy'"));
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> fixture.jdbc.update("UPDATE arte_platform_membership_invitation SET state=5 WHERE id='legacy'"));
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> fixture.jdbc.update("UPDATE arte_platform_membership_invitation SET version=-1 WHERE id='legacy'"));
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> fixture.jdbc.update("UPDATE arte_platform_membership_invitation SET state=1 WHERE id='legacy'"));
        } finally {
            fixture.cleanup();
        }
    }
}
