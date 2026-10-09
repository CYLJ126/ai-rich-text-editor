package com.arte.core.application.workspace;

import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.enums.workspace.WorkspaceStateEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.*;
import static com.arte.core.constant.platform.PlatformActions.ARTICLE_CREATE;
import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_UPDATE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 旧普通读取与最新锁定读取分离，验证加入外层事务后的最终提交边界。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 23:35 ✾
 */
class CurrentReadWorkspaceServicesTest {
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture();
    private final TrustedActor alice = f.user("alice");

    @Test
    void revokedWorkspaceMembershipFromLockDeniesEvenWhenOrdinaryReadStillAllows() {
        Scope scope = f.initialize(alice).workspace().scope();
        var key = workspaceKey(scope, "alice");
        var previous = f.workspaceMembers.get(key);
        f.captureOldReadSnapshot();
        f.workspaceMembers.put(key, new WorkspaceMembership(previous.id(), scope, "alice", previous.role(),
                MembershipStateEnum.REVOKED, previous.generation(), previous.version() + 1));
        var request = request(scope);
        assertTrue(f.services.authorization().check(alice, request).allowed());
        assertGuardDenies(PlatformErrorCodeEnum.NOT_FOUND, request);
    }

    @Test
    void revokedTenantMembershipInvalidatesWorkspaceUsingCurrentLockResult() {
        Scope scope = f.initialize(alice).workspace().scope();
        var key = List.of(scope.tenantId(), "alice");
        var previous = f.tenantMembers.get(key);
        f.captureOldReadSnapshot();
        f.tenantMembers.put(key, new TenantMembership(previous.id(), scope.tenantId(), "alice", previous.role(),
                MembershipStateEnum.REVOKED, previous.generation(), previous.version() + 1));
        assertTrue(f.services.authorization().check(alice, request(scope)).allowed());
        assertGuardDenies(PlatformErrorCodeEnum.NOT_FOUND, request(scope));
    }

    @Test
    void downgradedRoleCannotRenameThroughOldSnapshotAndDoesNotWriteSideEffects() {
        Scope scope = f.initialize(alice).workspace().scope();
        var key = workspaceKey(scope, "alice");
        var previous = f.workspaceMembers.get(key);
        Workspace workspace = f.workspaces.get(scope);
        int events = f.events.size(), receipts = f.receipts.size(), audits = f.audits.size();
        f.captureOldReadSnapshot();
        f.workspaceMembers.put(key, new WorkspaceMembership(previous.id(), scope, "alice", WorkspaceRoleEnum.MEMBER,
                MembershipStateEnum.ACTIVE, previous.generation(), previous.version() + 1));
        assertTrue(f.services.authorization().check(alice, request(scope)).allowed());
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.transactions.required(() ->
                f.services.workspaces().rename(alice, new WorkspaceCommands.RenameWorkspace(meta("stale-role"),
                        scope, workspace.version(), "不应写入", ""))));
        assertEquals(workspace, f.workspaces.get(scope));
        assertEquals(events, f.events.size());
        assertEquals(receipts, f.receipts.size());
        assertEquals(audits, f.audits.size());
    }

    @ParameterizedTest
    @EnumSource(value = WorkspaceStateEnum.class, names = {"ARCHIVED", "CLOSING"})
    void lifecycleCurrentReadRejectsOldActiveWorkspace(WorkspaceStateEnum state) {
        Scope scope = f.initialize(alice).workspace().scope();
        Workspace old = f.workspaces.get(scope);
        f.captureOldReadSnapshot();
        f.workspaces.put(scope, new Workspace(scope, old.name(), old.description(), state, old.createdBy(),
                old.version() + 1, old.authEpoch() + 1, old.createdAt(), NOW));
        assertTrue(f.services.authorization().check(alice, request(scope)).allowed());
        assertGuardDenies(PlatformErrorCodeEnum.SPACE_READ_ONLY, request(scope));
    }

    @Test
    void currentPoliciesDenyCreationEvenWhenCachedPolicyAllows() {
        Scope scope = f.initialize(alice).workspace().scope();
        f.captureOldReadSnapshot();
        var old = f.workspacePolicies.get(scope);
        var denyCreation = new PolicyRules(Set.of(), Map.of(), false, false,
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        f.workspacePolicies.put(scope, new WorkspacePolicy(scope, denyCreation, 1, 1, old.version() + 1));
        var request = DefaultAuthorizationService.workspaceRequest(scope, ARTICLE_CREATE, "current-policy");
        assertTrue(f.services.authorization().check(alice, request).allowed());
        assertGuardDenies(PlatformErrorCodeEnum.POLICY_DENIED, request);
        // 租户政策也必须当前读取；空间的旧允许不能覆盖租户的当前禁止。
        f.workspacePolicies.put(scope, old);
        var tenantPolicy = f.tenantPolicies.get(scope.tenantId());
        f.tenantPolicies.put(scope.tenantId(), new TenantPolicy(scope.tenantId(), denyCreation,
                false, 1, 1, tenantPolicy.version() + 1));
        assertGuardDenies(PlatformErrorCodeEnum.POLICY_DENIED, request);
    }

    @Test
    void currentIdentitySuspensionStopsGuardAndCommandDespiteOldActiveIdentity() {
        Scope scope = f.initialize(alice).workspace().scope();
        f.captureOldReadSnapshot();
        f.identities.put("alice", new Principal("alice", PrincipalKindEnum.USER, PrincipalStatusEnum.SUSPENDED));
        assertTrue(f.services.authorization().check(alice, request(scope)).allowed());
        assertGuardDenies(PlatformErrorCodeEnum.ACCESS_DENIED, request(scope));
        int events = f.events.size(), receipts = f.receipts.size();
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.workspaces().rename(alice,
                new WorkspaceCommands.RenameWorkspace(meta("inactive-current"), scope, 0, "不应写入", "")));
        assertEquals(events, f.events.size());
        assertEquals(receipts, f.receipts.size());
    }

    @Test
    void initializationSeesAssociationAndReceiptCommittedAfterOldSnapshot() {
        f.captureOldReadSnapshot();
        var first = f.services.personal().initialize(alice, meta("latest-init"));
        assertTrue(f.tenantStore.findPersonal("alice").isEmpty());
        var replay = f.services.personal().initialize(alice, meta("latest-init"));
        var reused = f.services.personal().initialize(alice, meta("other-key"));
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(first.operationId(), replay.operationId());
        assertEquals(first.value(), reused.value());
        assertEquals(1, f.tenants.size());
        assertEquals(1, f.workspaces.size());
        assertEquals(1, f.events.size());
    }

    @Test
    void createReplayFindsLatestReceiptAndDoesNotExecuteAgain() {
        var personal = f.initialize(alice);
        f.captureOldReadSnapshot();
        var command = new WorkspaceCommands.CreateWorkspace(meta("current-create"), personal.tenant().id(), "新空间", "");
        var first = f.services.workspaces().create(alice, command);
        var replay = f.services.workspaces().create(alice, command);
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(first.operationId(), replay.operationId());
        assertEquals(first.value(), replay.value());
        assertEquals(2, f.workspaces.size());
        assertEquals(2, f.events.size());
    }

    @Test
    void currentPreferenceVersionWinsOverOldSnapshot() {
        Scope scope = f.initialize(alice).workspace().scope();
        f.captureOldReadSnapshot();
        var first = new WorkspaceCommands.UpdatePreference(meta("preference-one"), 0, scope, null, Set.of(scope));
        f.services.preferences().update(alice, first);
        assertEquals(0, f.preferenceStore.find("alice").orElseThrow().version());
        assertEquals(CommandDeliveryEnum.REPLAYED, f.services.preferences().update(alice, first).delivery());
        assertCode(PlatformErrorCodeEnum.VERSION_CONFLICT, () -> f.services.preferences().update(alice,
                new WorkspaceCommands.UpdatePreference(meta("preference-two"), 0, null, null, Set.of())));
        assertEquals(1, f.preferences.get("alice").version());
    }

    @Test
    void creationUsesCurrentTenantPolicyAndRejectsNewlyDisabledMemberCreation() {
        var personal = f.initialize(alice);
        String tenantId = personal.tenant().id();
        var key = List.of(tenantId, "alice");
        var member = f.tenantMembers.get(key);
        f.tenantMembers.put(key, new TenantMembership(member.id(), tenantId, "alice", TenantRoleEnum.MEMBER,
                MembershipStateEnum.ACTIVE, 0, 1));
        var policy = f.tenantPolicies.get(tenantId);
        f.tenantPolicies.put(tenantId, new TenantPolicy(tenantId, policy.rules(), true, 1, 1, 1));
        f.captureOldReadSnapshot();
        f.tenantPolicies.put(tenantId, new TenantPolicy(tenantId, policy.rules(), false, 1, 1, 2));
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.workspaces().create(alice,
                new WorkspaceCommands.CreateWorkspace(meta("disabled-create"), tenantId, "不应创建", "")));
        assertEquals(1, f.workspaces.size());
    }

    @Test
    void currentAuthorityMissingFailsClosedInsteadOfFallingBackToOldPolicy() {
        Scope scope = f.initialize(alice).workspace().scope();
        f.captureOldReadSnapshot();
        f.workspacePolicies.remove(scope);
        assertTrue(f.services.authorization().check(alice, request(scope)).allowed());
        assertGuardDenies(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, request(scope));
    }

    @Test
    void currentPermissionVersionsReflectLockedFactsAndRequireAnExistingTransaction() {
        Scope scope = f.initialize(alice).workspace().scope();
        f.captureOldReadSnapshot();
        var old = f.workspaces.get(scope);
        f.workspaces.put(scope, new Workspace(scope, old.name(), old.description(), old.state(), old.createdBy(),
                4, 7, old.createdAt(), NOW));
        var key = workspaceKey(scope, "alice");
        var member = f.workspaceMembers.get(key);
        f.workspaceMembers.put(key, new WorkspaceMembership(member.id(), scope, "alice", member.role(),
                MembershipStateEnum.ACTIVE, 3, 5));
        var request = request(scope);
        assertThrows(IllegalStateException.class, () -> f.services.authorization().requireInCurrentTransaction(alice, request));
        var verified = f.transactions.required(() -> f.services.writeGuard().inCurrentTransaction(alice, List.of(request),
                () -> f.services.authorization().requireInCurrentTransaction(alice, request)));
        assertEquals(7, verified.versions().workspaceEpoch());
        assertEquals(3, verified.versions().workspaceMembershipGeneration());
        assertEquals(5, verified.versions().workspaceMembershipVersion());
        assertEquals(Long.valueOf(4), verified.versions().resourceVersion());
    }

    private AccessRequest request(Scope scope) {
        return DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_UPDATE, "current-read");
    }

    private void assertGuardDenies(PlatformErrorCodeEnum code, AccessRequest request) {
        AtomicBoolean ran = new AtomicBoolean();
        assertCode(code, () -> f.transactions.required(() ->
                f.services.writeGuard().inCurrentTransaction(alice, List.of(request), () -> ran.getAndSet(true))));
        assertFalse(ran.get());
    }

    private static void assertCode(PlatformErrorCodeEnum code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(PlatformException.class, work).getCode());
    }
}
