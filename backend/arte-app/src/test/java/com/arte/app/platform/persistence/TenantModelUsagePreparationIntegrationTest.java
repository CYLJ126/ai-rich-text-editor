package com.arte.app.platform.persistence;

import com.arte.ainew.api.model.ModelUsagePreparationService;
import com.arte.ainew.pojo.model.ModelUsageChecks;
import com.arte.ainew.pojo.model.ModelUsagePreparation;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.CommandMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
            var checks = service.checks(f.bob, f.target, f.f.model.resource(), binding);
            assertFalse(checks.canConfigureBudget());
            assertEquals(f.target, checks.scope());
            assertEquals(binding, checks.binding());
            assertTrue(checks.spaceConditions().stream().allMatch(check -> check.status() == ModelUsageChecks.Status.PASS));
            assertEquals(ModelUsageChecks.Check.evaluated(ModelUsageChecks.Condition.OWN_BUDGET, "BUDGET_NOT_PREPARED"),
                    checks.personalConditions().getLast());
            assertTrue(checks.allowedActions().contains("PREPARE_OWN_BUDGET"));
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
    void targetOwnerCanConfigureReadableTenantModelWithoutUseOrCredentialConsumerPermission() {
        f.context().run(c -> {
            f.prepare(c);
            f.scopes(c, 0, Set.of(f.target.workspaceId()));
            com.arte.core.application.workspace.DefaultWorkspaceServices services = c.getBean(com.arte.core.application.workspace.DefaultWorkspaceServices.class);
            long acl = c.getBean(com.arte.core.spi.access.ResourceGrantStore.class).findAccess(f.f.model.resource()).orElseThrow().aclVersion();
            services.resources().grant(f.actor(), new com.arte.core.pojo.access.ResourceAccessCommands.GrantResource(
                    new CommandMeta("bob-read-only", "m3"), f.f.f.scope, f.f.model.resource(), acl,
                    new com.arte.core.pojo.access.GrantRecipient.Subject(f.bob.principal().subjectId()),
                    Set.of(com.arte.core.constant.platform.PlatformActions.MODEL_READ), null));
            // 当前批次未实现成员治理命令；隔离夹具原子建立唯一目标负责人，仍使用真实授权读取。
            c.getBean(com.arte.core.spi.platform.PublicTransactionRunner.class).required(() -> {
                f.f.f.f.f.jdbc.update("UPDATE arte_platform_workspace_member SET role=1,version=version+1 WHERE workspace_id=? AND subject_id=?", f.target.workspaceId(), f.actor().principal().subjectId());
                f.f.f.f.f.jdbc.update("UPDATE arte_platform_workspace_member SET role=0,version=version+1 WHERE workspace_id=? AND subject_id=?", f.target.workspaceId(), f.bob.principal().subjectId());
                return null;
            });
            com.arte.app.platform.ai.WorkspaceAiGovernanceService governance = c.getBean(com.arte.app.platform.ai.WorkspaceAiGovernanceService.class);
            com.arte.app.platform.ai.AiControlData.WorkspaceView view = governance.get(f.bob, f.target);
            org.junit.jupiter.api.Assertions.assertTrue(view.canManage());
            org.junit.jupiter.api.Assertions.assertFalse(governance.modelOptions(f.bob, f.target, null).items().isEmpty());
            governance.update(f.bob, new CommandMeta("bob-unselect", "m3"), f.target, view.workspaceVersion(), view.configuration().version(), true, java.util.List.of());
            var unselected = governance.get(f.bob, f.target);
            var service = c.getBean(ModelUsagePreparationService.class);
            var checks = service.checks(f.bob, f.target, f.f.model.resource(), f.version.publication().binding().definition());
            assertEquals(ModelUsageChecks.Check.evaluated(ModelUsageChecks.Condition.WORKSPACE_SELECTION, "MODEL_NOT_ENABLED"),
                    checks.spaceConditions().stream().filter(check -> check.condition() == ModelUsageChecks.Condition.WORKSPACE_SELECTION).findFirst().orElseThrow());
            assertTrue(checks.allowedActions().contains("CONFIGURE_WORKSPACE_AI"));
            assertTrue(checks.canConfigureBudget());
            assertFalse(checks.allowedActions().contains("PREPARE_OWN_BUDGET"));
            assertEquals(ModelUsageChecks.Check.evaluated(ModelUsageChecks.Condition.MODEL_ACCESS, "MODEL_USE_DENIED"),
                    checks.personalConditions().stream().filter(check -> check.condition() == ModelUsageChecks.Condition.MODEL_ACCESS).findFirst().orElseThrow());
            assertEquals(ModelUsageChecks.Status.UNKNOWN, checks.personalConditions().getLast().status());
            governance.update(f.bob, new CommandMeta("bob-select", "m3"), f.target, unselected.workspaceVersion(), unselected.configuration().version(), true,
                    java.util.List.of(new com.arte.ainew.pojo.model.ModelSelection(f.f.model.resource().resourceId(), f.version.publication().binding().definition())));
            assertThrows(PlatformException.class, () -> c.getBean(ModelUsagePreparationService.class)
                    .status(f.bob, f.target, f.f.model.resource(), f.version.publication().binding().definition()));
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
            assertThrows(PlatformException.class, () -> service.checks(f.bob, f.target, f.f.model.resource(), binding));
            f.grant(c);
            f.scopes(c, 0, Set.of(f.target.workspaceId()));
            f.consumers(c, 1, Set.of(f.bob.principal().subjectId()));
            jdbc.update("DELETE FROM arte_ai_workspace_budget_rule WHERE tenant_id=? AND workspace_id=?", f.target.tenantId(), f.target.workspaceId());
            jdbc.update("DELETE FROM arte_ai_workspace_configuration WHERE tenant_id=? AND workspace_id=?", f.target.tenantId(), f.target.workspaceId());
            assertEquals("WORKSPACE_AI_NOT_PREPARED", service.status(f.bob, f.target, f.f.model.resource(), binding).reason());
            assertThrows(PlatformException.class, () -> service.prepare(f.bob, new CommandMeta("member-init", "a1"), f.target, f.f.model.resource(), binding));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_workspace_configuration WHERE tenant_id=? AND workspace_id=?", Integer.class, f.target.tenantId(), f.target.workspaceId()));
        });
    }
}
