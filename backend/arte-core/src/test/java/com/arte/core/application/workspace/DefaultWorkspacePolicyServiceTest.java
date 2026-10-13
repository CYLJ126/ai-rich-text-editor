package com.arte.core.application.workspace;

import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.meta;
import static com.arte.core.constant.platform.PlatformResourceTypes.ARTICLE;
import static com.arte.core.constant.platform.PlatformResourceTypes.MODEL;
import static org.junit.jupiter.api.Assertions.*;

class DefaultWorkspacePolicyServiceTest {
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture();
    private final TrustedActor alice = f.user("alice");
    private final Scope scope = f.services.personal().initialize(alice, meta("init")).value().workspace().scope();

    private TenantCommands.UpdateTenantPolicy tenantCommand(String key, long tenantVersion, long policyVersion, PolicyRules rules) {
        return new TenantCommands.UpdateTenantPolicy(meta(key), scope.tenantId(), tenantVersion, policyVersion, rules, true);
    }

    private WorkspaceCommands.UpdateWorkspacePolicy workspaceCommand(String key, long spaceVersion, long policyVersion, PolicyRules rules) {
        return new WorkspaceCommands.UpdateWorkspacePolicy(meta(key), scope, spaceVersion, policyVersion, rules);
    }

    private static PolicyRules restrictive() {
        return new PolicyRules(Set.of(ARTICLE), Map.of(), false, false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Test
    void tenantUpdateAtomicallyAdvancesVersionsEpochAndAuditAndReplaysWithoutEffects() {
        var before = f.tenantPolicies.get(scope.tenantId());
        var command = tenantCommand("tenant-policy", 0, 0, restrictive());
        var updated = f.services.policies().updateTenantPolicy(alice, command);
        assertEquals(1, updated.value().version());
        assertEquals(restrictive(), updated.value().rules());
        assertEquals(before.personalTemplateVersion(), updated.value().personalTemplateVersion());
        assertEquals(before.policySchemaVersion(), updated.value().policySchemaVersion());
        assertEquals(before.rolePresetVersion(), updated.value().rolePresetVersion());
        assertEquals(1, f.tenants.get(scope.tenantId()).version());
        assertEquals(1, f.tenants.get(scope.tenantId()).authEpoch());
        assertEquals(0, f.workspaces.get(scope).authEpoch());
        assertEquals("tenant:policy:updated", f.audits.getLast().action());
        assertEquals(updated.operationId(), f.audits.getLast().operationId());
        assertEquals("1", f.events.getLast().attributes().get("tenantEpoch"));
        var replay = f.services.policies().updateTenantPolicy(alice, command);
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(updated.operationId(), replay.operationId());
        assertEquals(2, f.audits.size());
        assertEquals(updated.value(), f.services.policies().getTenantPolicy(alice, scope.tenantId()));
        var view = f.services.policies().getTenantPolicyView(alice, scope.tenantId());
        assertEquals(1, view.tenantVersion());
        assertEquals(1, view.tenantEpoch());
        assertCode(PlatformErrorCodeEnum.VERSION_CONFLICT, () -> f.services.policies().updateTenantPolicy(alice,
                tenantCommand("stale-parent", 0, 1, restrictive())));
        assertCode(PlatformErrorCodeEnum.VERSION_CONFLICT, () -> f.services.policies().updateTenantPolicy(alice,
                tenantCommand("stale-policy", 1, 0, restrictive())));
        assertEquals(updated.value(), f.services.receiptQueries().get(alice, updated.operationId()).value());
        assertCode(PlatformErrorCodeEnum.IDEMPOTENCY_CONFLICT, () -> f.services.policies().updateTenantPolicy(alice,
                tenantCommand("tenant-policy", 0, 0, before.rules())));
    }

    @Test
    void workspaceUpdateChecksBothVersionsAndAdvancesOnlyWorkspaceEpoch() {
        var command = workspaceCommand("workspace-policy", 0, 0, restrictive());
        var updated = f.services.policies().updateWorkspacePolicy(alice, command);
        assertEquals(1, updated.value().version());
        assertEquals(1, f.workspaces.get(scope).version());
        assertEquals(1, f.workspaces.get(scope).authEpoch());
        assertEquals(0, f.tenants.get(scope.tenantId()).authEpoch());
        assertEquals(updated.value(), f.services.policies().getWorkspacePolicy(alice, scope));
        assertEquals(updated.value(), f.services.receiptQueries().get(alice, updated.operationId()).value());
        assertEquals(updated.operationId(), f.services.policies().updateWorkspacePolicy(alice, command).operationId());
        assertCode(PlatformErrorCodeEnum.VERSION_CONFLICT, () -> f.services.policies().updateWorkspacePolicy(alice,
                workspaceCommand("stale-parent", 0, 1, restrictive())));
        assertCode(PlatformErrorCodeEnum.VERSION_CONFLICT, () -> f.services.policies().updateWorkspacePolicy(alice,
                workspaceCommand("stale-policy", 1, 0, restrictive())));
        assertEquals(2, f.audits.size());
    }

    @Test
    void allWorkspaceEscalationFieldsAreRejectedAgainstTenantCeiling() {
        f.services.policies().updateTenantPolicy(alice, tenantCommand("restrict", 0, 0, restrictive()));
        var cases = List.of(
                new PolicyRules(Set.of(ARTICLE, MODEL), Map.of(), false, false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()),
                new PolicyRules(Set.of(ARTICLE), Map.of(ARTICLE, Set.of(WorkspaceRoleEnum.GUEST)), false, false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()),
                new PolicyRules(Set.of(ARTICLE), Map.of(), true, false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()),
                new PolicyRules(Set.of(ARTICLE), Map.of(), false, true, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()),
                new PolicyRules(Set.of(ARTICLE), Map.of(), false, false, Set.of("https"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()),
                new PolicyRules(Set.of(ARTICLE), Map.of(), false, false, Set.of(), Set.of("vendor"), Set.of(), Set.of(), Set.of(), Set.of()),
                new PolicyRules(Set.of(ARTICLE), Map.of(), false, false, Set.of(), Set.of(), Set.of("example.com"), Set.of(), Set.of(), Set.of()),
                new PolicyRules(Set.of(ARTICLE), Map.of(), false, false, Set.of(), Set.of(), Set.of(), Set.of(ARTICLE), Set.of(), Set.of()),
                new PolicyRules(Set.of(ARTICLE), Map.of(), false, false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(ARTICLE), Set.of()),
                new PolicyRules(Set.of(ARTICLE), Map.of(), false, false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(com.arte.core.enums.access.GrantTargetKindEnum.SUBJECT)));
        for (int i = 0; i < cases.size(); i++) {
            var command = workspaceCommand("escalate-" + i, 0, 0, cases.get(i));
            assertCode(PlatformErrorCodeEnum.POLICY_DENIED, () -> f.services.policies().updateWorkspacePolicy(alice, command));
        }
        assertEquals(0, f.workspaces.get(scope).version());
    }

    @Test
    void readReplayAndReceiptRequireCurrentGovernanceRole() {
        var tenant = f.services.policies().updateTenantPolicy(alice, tenantCommand("tenant", 0, 0, restrictive()));
        var space = f.services.policies().updateWorkspacePolicy(alice, workspaceCommand("space", 0, 0, restrictive()));
        var tm = f.tenantMembers.get(List.of(scope.tenantId(), "alice"));
        f.tenantMembers.put(List.of(scope.tenantId(), "alice"), new TenantMembership(tm.id(), tm.tenantId(), tm.subjectId(),
                TenantRoleEnum.MEMBER, tm.state(), tm.generation(), tm.version() + 1));
        var wm = f.workspaceMembers.get(List.of(scope.tenantId(), scope.workspaceId(), "alice"));
        f.workspaceMembers.put(List.of(scope.tenantId(), scope.workspaceId(), "alice"), new WorkspaceMembership(wm.id(), wm.scope(),
                wm.subjectId(), WorkspaceRoleEnum.ADMIN, wm.state(), wm.generation(), wm.version() + 1));
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.policies().getTenantPolicy(alice, scope.tenantId()));
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.policies().getWorkspacePolicy(alice, scope));
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.policies().updateTenantPolicy(alice, tenantCommand("tenant", 0, 0, restrictive())));
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.policies().updateWorkspacePolicy(alice, workspaceCommand("space", 0, 0, restrictive())));
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.receiptQueries().get(alice, tenant.operationId()));
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.receiptQueries().get(alice, space.operationId()));
        var outsider = f.user("bob");
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.policies().getWorkspacePolicy(outsider, scope));
    }

    @Test
    void tenantAdministratorDoesNotNeedWorkspaceMembershipToGovernTenantPolicy() {
        var bob = f.user("bob");
        f.tenantMembers.put(List.of(scope.tenantId(), "bob"), new TenantMembership("bob-tenant-member", scope.tenantId(), "bob",
                TenantRoleEnum.ADMIN, MembershipStateEnum.ACTIVE, 0, 0));
        assertNotNull(f.services.policies().getTenantPolicy(bob, scope.tenantId()));
        f.services.policies().updateTenantPolicy(bob, tenantCommand("tenant-admin", 0, 0, restrictive()));
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.policies().getWorkspacePolicy(bob, scope));
    }

    @Test
    void auditEventOrReceiptFailureRollsBackPolicyParentAndEpoch() {
        var beforeTenant = f.tenants.get(scope.tenantId());
        var beforePolicy = f.tenantPolicies.get(scope.tenantId());
        f.failEvents = true;
        assertThrows(RuntimeException.class, () -> f.services.policies().updateTenantPolicy(alice, tenantCommand("failure", 0, 0, restrictive())));
        assertEquals(beforeTenant, f.tenants.get(scope.tenantId()));
        assertEquals(beforePolicy, f.tenantPolicies.get(scope.tenantId()));
        f.failEvents = false;
        f.failReceipt = true;
        assertCode(PlatformErrorCodeEnum.VERSION_CONFLICT, () -> f.services.policies().updateWorkspacePolicy(alice, workspaceCommand("receipt-failure", 0, 0, restrictive())));
        assertEquals(0, f.workspacePolicies.get(scope).version());
        assertEquals(0, f.workspaces.get(scope).authEpoch());
        assertEquals(1, f.audits.size());
    }

    @Test
    void outerOldSnapshotCannotOverrideNewPolicyOrGovernanceRole() {
        f.captureOldReadSnapshot();
        f.services.policies().updateTenantPolicy(alice, tenantCommand("first", 0, 0, restrictive()));
        assertCode(PlatformErrorCodeEnum.VERSION_CONFLICT, () -> f.transactions.required(() ->
                f.services.policies().updateTenantPolicy(alice, tenantCommand("stale", 0, 0, restrictive()))));
        assertEquals(1, f.services.policies().getTenantPolicy(alice, scope.tenantId()).version());
    }

    private static void assertCode(PlatformErrorCodeEnum code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(PlatformException.class, work).getCode());
    }
}
