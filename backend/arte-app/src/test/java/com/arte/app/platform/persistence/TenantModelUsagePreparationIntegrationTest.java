package com.arte.app.platform.persistence;

import com.arte.ainew.api.model.ModelUsagePreparationService;
import com.arte.ainew.pojo.model.ModelUsagePreparation;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.CommandMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 租户模型使用目标空间和当前消费者预算，管理空间及凭据责任主体不代替调用用户。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/02 21:30 ✾
 */
class TenantModelUsagePreparationIntegrationTest {

    final TenantModelSharingIntegrationTest f = new TenantModelSharingIntegrationTest();

    @BeforeEach
    void setup() throws Exception {
        f.setup();
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    @Test
    void targetMemberPreparesOwnBudgetWithoutSourceMembershipAndRevocationBlocksReplay() {
        f.context().run(c -> {
            f.prepare(c);
            f.grant(c);
            f.scopes(c, 0, Set.of(f.target.workspaceId()));
            f.consumers(c, 1, Set.of(f.bob.principal().subjectId()));
            var jdbc = f.f.f.f.f.jdbc;
            String subject = f.bob.principal().subjectId();
            // 账户归属存在于耐久 JSON；只移除隔离测试中 Bob 在目标空间的账户和定义。
            var codec = new com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec();
            jdbc.query("SELECT id_key,workspace_snapshot FROM arte_ai_account", (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                var account = codec.decode(row.getString(2), com.arte.ainew.pojo.budget.BudgetCommands.Account.class);
                if (account.owner().subjectId().equals(subject) && account.owner().workspaceId().equals(f.target.workspaceId()))
                    jdbc.update("DELETE FROM arte_ai_account WHERE id_key=?", row.getString(1));
            });
            jdbc.update("DELETE FROM arte_ai_budget_definition WHERE tenant_id=? AND workspace_id=? AND subject_id=?", f.target.tenantId(), f.target.workspaceId(), subject);
            var service = c.getBean(ModelUsagePreparationService.class);
            var binding = f.version.publication().binding().definition();
            assertEquals(ModelUsagePreparation.State.PREPARABLE, service.status(f.bob, f.target, f.f.model.resource(), binding).state());
            var receipt = service.prepare(f.bob, new CommandMeta("bob-prepare", "a1"), f.target, f.f.model.resource(), binding);
            assertEquals(ModelUsagePreparation.State.READY, receipt.value().state());
            String ref = receipt.value().budgetRefs().getFirst();
            assertEquals(subject, jdbc.queryForObject("SELECT subject_id FROM arte_ai_budget_definition WHERE budget_ref=?", String.class, ref));
            assertEquals(f.target.workspaceId(), jdbc.queryForObject("SELECT workspace_id FROM arte_ai_budget_definition WHERE budget_ref=?", String.class, ref));
            f.consumers(c, 2, Set.of());
            assertEquals("CREDENTIAL_USE_DENIED", service.status(f.bob, f.target, f.f.model.resource(), binding).reason());
            assertThrows(PlatformException.class, () -> service.prepare(f.bob, new CommandMeta("bob-prepare", "a1"), f.target, f.f.model.resource(), binding));
        });
    }

    @Test
    void unpreparedTargetCannotBeInitializedByMemberAndMissingScopeOrAclDoesNotAllocate() {
        f.context().run(c -> {
            f.prepare(c);
            var jdbc = f.f.f.f.f.jdbc;
            var service = c.getBean(ModelUsagePreparationService.class);
            var binding = f.version.publication().binding().definition();
            assertThrows(PlatformException.class, () -> service.status(f.bob, f.target, f.f.model.resource(), binding));
            f.grant(c);
            f.scopes(c, 0, Set.of(f.target.workspaceId()));
            f.consumers(c, 1, Set.of(f.bob.principal().subjectId()));
            jdbc.update("DELETE FROM arte_ai_workspace_configuration WHERE tenant_id=? AND workspace_id=?", f.target.tenantId(), f.target.workspaceId());
            assertEquals("WORKSPACE_AI_NOT_PREPARED", service.status(f.bob, f.target, f.f.model.resource(), binding).reason());
            assertThrows(PlatformException.class, () -> service.prepare(f.bob, new CommandMeta("member-init", "a1"), f.target, f.f.model.resource(), binding));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_workspace_configuration WHERE tenant_id=? AND workspace_id=?", Integer.class, f.target.tenantId(), f.target.workspaceId()));
        });
    }
}
