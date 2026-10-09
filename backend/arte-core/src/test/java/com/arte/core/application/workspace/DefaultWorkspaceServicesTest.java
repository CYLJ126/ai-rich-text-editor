package com.arte.core.application.workspace;

import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.enums.workspace.WorkspaceStateEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessDecision;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.*;
import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultWorkspaceServicesTest {
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture();
    private final TrustedActor alice = f.user("alice");

    @Test
    void initializationCreatesCompleteAssociationsAndReusesThemAcrossKeys() {
        var first = f.services.personal().initialize(alice, meta("init-a"));
        var replay = f.services.personal().initialize(alice, new CommandMeta("init-a", "different-trace"));
        var anotherKey = f.services.personal().initialize(alice, meta("init-b"));
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(first.operationId(), replay.operationId());
        assertEquals(first.value(), anotherKey.value());
        assertEquals(TenantRoleEnum.OWNER, first.value().tenant().currentRole());
        assertEquals(WorkspaceRoleEnum.OWNER, first.value().workspace().currentRole());
        assertEquals(first.value().workspace().scope(), first.value().preference().defaultScope());
        assertEquals(1, f.tenants.size());
        assertEquals(1, f.workspaces.size());
        assertEquals(1, f.tenantMembers.size());
        assertEquals(1, f.workspaceMembers.size());
        assertEquals(1, f.tenantPolicies.size());
        assertEquals(1, f.workspacePolicies.size());
        assertEquals(2, f.receipts.size());
        assertEquals(1, f.events.size());
        assertEquals(1, f.audits.size());
        assertEquals(first.operationId(), f.audits.getFirst().operationId());
        assertTrue(f.lockOrder.getFirst().startsWith("subject:"));
        assertTrue(f.tenantPolicies.values().iterator().next().rules().allowedDestinations().isEmpty());
    }

    @Test
    void concurrentInitializationHasOneDefaultRelationshipAndOneSameKeyReceipt() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            List<Future<CommandReceipt<WorkspaceViews.PersonalWorkspaceView>>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++)
                futures.add(executor.submit(() -> {
                    start.await();
                    return f.services.personal().initialize(alice, meta("concurrent"));
                }));
            start.countDown();
            Set<String> operations = new HashSet<>();
            Set<Scope> defaults = new HashSet<>();
            for (var future : futures) {
                var receipt = future.get(10, TimeUnit.SECONDS);
                operations.add(receipt.operationId());
                defaults.add(receipt.value().preference().defaultScope());
            }
            assertEquals(1, operations.size());
            assertEquals(1, defaults.size());
            assertEquals(1, f.tenants.size());
            assertEquals(1, f.workspaces.size());
            assertEquals(1, f.events.size());
        }
    }

    @Test
    void initializationRejectsServiceUnknownInactiveAndChangedIdentity() {
        Principal service = new Principal("worker", PrincipalKindEnum.SERVICE, PrincipalStatusEnum.ACTIVE);
        f.identities.put(service.subjectId(), service);
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.personal().initialize(new TrustedActor(service, NOW), meta("service")));
        f.identities.remove("alice");
        assertCode(PlatformErrorCodeEnum.UNAUTHENTICATED, () -> f.services.personal().initialize(alice, meta("unknown")));
        f.identities.put("alice", new Principal("alice", PrincipalKindEnum.USER, PrincipalStatusEnum.DELETED));
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.personal().initialize(alice, meta("deleted")));
        f.identities.put("alice", new Principal("alice", PrincipalKindEnum.SERVICE, PrincipalStatusEnum.ACTIVE));
        assertCode(PlatformErrorCodeEnum.IDENTITY_CONFLICT, () -> f.services.personal().initialize(alice, meta("changed")));
        assertTrue(f.tenants.isEmpty());
        assertTrue(f.receipts.isEmpty());
    }

    @Test
    void partialInitializationAndReceiptFailuresRollBackAllWrites() {
        f.failEvents = true;
        assertThrows(IllegalStateException.class, () -> f.initialize(alice));
        assertTrue(f.tenants.isEmpty());
        assertTrue(f.workspaces.isEmpty());
        assertTrue(f.preferences.isEmpty());
        assertTrue(f.tenantMembers.isEmpty());
        assertTrue(f.workspaceMembers.isEmpty());
        assertTrue(f.tenantPolicies.isEmpty());
        f.failEvents = false;
        f.failReceipt = true;
        assertCode(PlatformErrorCodeEnum.VERSION_CONFLICT, () -> f.initialize(alice));
        assertTrue(f.tenants.isEmpty());
        assertTrue(f.events.isEmpty());
        assertTrue(f.audits.isEmpty());
        f.failReceipt = false;
        assertNotNull(f.initialize(alice));
    }

    @Test
    void servicesJoinOuterTransactionAndDoNotCommitBeforeItsFailure() {
        assertThrows(IllegalStateException.class, () -> f.transactions.required(() -> {
            f.initialize(alice);
            assertEquals(1, f.tenants.size());
            throw new IllegalStateException("Outer domain operation failed");
        }));
        assertTrue(f.tenants.isEmpty());
        assertTrue(f.preferences.isEmpty());
        assertTrue(f.receipts.isEmpty());
        f.initialize(alice);
        long epoch = f.tenants.values().iterator().next().authEpoch();
        int receipts = f.receipts.size();
        f.failEvents = true;
        assertThrows(IllegalStateException.class, () -> create("失败空间"));
        assertEquals(1, f.workspaces.size());
        assertEquals(epoch, f.tenants.values().iterator().next().authEpoch());
        assertEquals(receipts, f.receipts.size());
    }

    @Test
    void brokenExistingDefaultAssociationIsNotSilentlyRecreated() {
        f.initialize(alice);
        f.preferences.remove("alice");
        assertCode(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE,
                () -> f.services.personal().initialize(alice, meta("repair")));
        assertEquals(1, f.tenants.size());
        assertEquals(1, f.workspaces.size());
    }

    @Test
    void creatingSpaceCreatesOwnerAndPolicyWithoutChangingSystemDefault() {
        var personal = f.initialize(alice);
        var command = new WorkspaceCommands.CreateWorkspace(meta("create"), personal.tenant().id(), "  项目空间  ", null);
        var receipt = f.services.workspaces().create(alice, command);
        var replay = f.services.workspaces().create(alice, new WorkspaceCommands.CreateWorkspace(
                new CommandMeta("create", "new-trace"), personal.tenant().id(), "项目空间", ""));
        assertEquals("项目空间", receipt.value().name());
        assertEquals(WorkspaceRoleEnum.OWNER, receipt.value().currentRole());
        assertEquals(receipt.operationId(), replay.operationId());
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(personal.preference().defaultScope(), f.preferences.get("alice").defaultScope());
        assertNotEquals(personal.workspace().scope(), receipt.value().scope());
        assertEquals(2, f.workspaces.size());
        assertEquals(2, f.workspacePolicies.size());
        assertEquals(1, f.tenants.get(personal.tenant().id()).authEpoch());
    }

    @Test
    void sameKeyDifferentIntentConflictsBeforeCreatingAnotherSpace() {
        String tenant = f.initialize(alice).tenant().id();
        f.services.workspaces().create(alice, new WorkspaceCommands.CreateWorkspace(meta("same"), tenant, "第一", ""));
        assertCode(PlatformErrorCodeEnum.IDEMPOTENCY_CONFLICT, () -> f.services.workspaces().create(alice,
                new WorkspaceCommands.CreateWorkspace(meta("same"), tenant, "第二", "")));
        assertEquals(2, f.workspaces.size());
        assertEquals(2, f.events.size());
    }

    @Test
    void memberCreationRequiresTenantPolicyAndGuestCannotUseIt() {
        var personal = f.initialize(alice);
        setTenantRole(personal.tenant().id(), TenantRoleEnum.MEMBER);
        var command = new WorkspaceCommands.CreateWorkspace(meta("member-create"), personal.tenant().id(), "新空间", "");
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.workspaces().create(alice, command));
        var old = f.tenantPolicies.get(personal.tenant().id());
        f.tenantPolicies.put(old.tenantId(), new TenantPolicy(old.tenantId(), old.rules(), true, 1, 1, old.version() + 1));
        assertEquals(WorkspaceRoleEnum.OWNER, f.services.workspaces().create(alice, command).value().currentRole());
        setTenantRole(personal.tenant().id(), TenantRoleEnum.GUEST);
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.workspaces().create(alice,
                new WorkspaceCommands.CreateWorkspace(meta("guest-create"), old.tenantId(), "访客空间", "")));
    }

    @Test
    void renameProtectsVersionOwnershipAndReplaysOnlyWithCurrentReadPermission() {
        Scope scope = create("可改名");
        Workspace before = f.workspaces.get(scope);
        var command = new WorkspaceCommands.RenameWorkspace(meta("rename"), scope, 0, "新名称", "描述");
        var renamed = f.services.workspaces().rename(alice, command);
        assertEquals(1, renamed.resultVersion());
        assertEquals(before.createdBy(), f.workspaces.get(scope).createdBy());
        assertEquals(before.authEpoch(), f.workspaces.get(scope).authEpoch());
        assertCode(PlatformErrorCodeEnum.VERSION_CONFLICT, () -> f.services.workspaces().rename(alice,
                new WorkspaceCommands.RenameWorkspace(meta("stale"), scope, 0, "覆盖", "")));
        setWorkspaceRole(scope, WorkspaceRoleEnum.MEMBER);
        assertEquals(CommandDeliveryEnum.REPLAYED, f.services.workspaces().rename(alice, command).delivery());
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.workspaces().rename(alice,
                new WorkspaceCommands.RenameWorkspace(meta("member-rename"), scope, 1, "越权", "")));
        revokeSpace(scope);
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.workspaces().rename(alice, command));
    }

    @Test
    void personalDefaultMayBeRenamedButCannotBeArchivedClosedOrTransferredByPreset() {
        Scope scope = f.initialize(alice).workspace().scope();
        f.services.workspaces().rename(alice, new WorkspaceCommands.RenameWorkspace(meta("default-rename"), scope, 0, "我的工作区", ""));
        assertEquals("我的工作区", f.services.workspaces().get(alice, scope).name());
        for (Action action : List.of(WORKSPACE_ARCHIVE, WORKSPACE_CLOSE, WORKSPACE_OWNER_TRANSFER)) {
            assertEquals(AccessReasonEnum.POLICY_DENIED, check(scope, action).reason());
        }
        var tenantTransfer = new AccessRequest(scope, new ResourceRef(scope.tenantId(), TENANT, scope.tenantId()),
                TENANT_OWNER_TRANSFER, "transfer");
        assertFalse(f.services.authorization().check(alice, tenantTransfer).allowed());
        assertEquals(scope, f.preferences.get("alice").defaultScope());
    }

    @ParameterizedTest
    @EnumSource(WorkspaceRoleEnum.class)
    void workspaceRoleMatrixDoesNotInheritTenantOwnerOrPrivateResourceAccess(WorkspaceRoleEnum role) {
        Scope scope = create("权限矩阵");
        setWorkspaceRole(scope, role);
        assertTrue(check(scope, WORKSPACE_READ).allowed());
        assertEquals(role == WorkspaceRoleEnum.OWNER || role == WorkspaceRoleEnum.ADMIN, check(scope, WORKSPACE_UPDATE).allowed());
        assertEquals(role != WorkspaceRoleEnum.GUEST, check(scope, ARTICLE_CREATE).allowed());
        assertEquals(role == WorkspaceRoleEnum.OWNER || role == WorkspaceRoleEnum.ADMIN, check(scope, MODEL_CREATE).allowed());
        assertEquals(role == WorkspaceRoleEnum.OWNER, check(scope, WORKSPACE_POLICY_WRITE).allowed());
        assertEquals(role != WorkspaceRoleEnum.GUEST, check(scope, WORKSPACE_AI_USE).allowed());
        assertFalse(check(scope, RESOURCE_ACCESS_MANAGE).allowed());
        var article = new AccessRequest(scope, new ResourceRef(scope.tenantId(), ARTICLE, "alice-private-article"), ARTICLE_READ, "read");
        assertFalse(f.services.authorization().check(alice, article).allowed());
        var tenantModel = new AccessRequest(scope, new ResourceRef(scope.tenantId(), TENANT, scope.tenantId()), TENANT_MODEL_MANAGE, "manage");
        assertFalse(f.services.authorization().check(alice, tenantModel).allowed());
    }

    @Test
    void policyProhibitionWinsAndUnknownPolicyPresetDoesNotFallBack() {
        Scope scope = create("政策");
        var old = f.tenantPolicies.get(scope.tenantId());
        var deniedRules = new PolicyRules(Set.of(), Map.of(), false, false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        f.tenantPolicies.put(scope.tenantId(), new TenantPolicy(scope.tenantId(), deniedRules, false, 1, 1, 1));
        assertEquals(AccessReasonEnum.POLICY_DENIED, check(scope, ARTICLE_CREATE).reason());
        f.tenantPolicies.put(scope.tenantId(), new TenantPolicy(scope.tenantId(), old.rules(), false, 1, 2, 2));
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE, check(scope, WORKSPACE_READ).reason());
    }

    @Test
    void extraCreationRolesNeedBothPoliciesAndGuestAiSwitchIsNotAGrant() {
        Scope scope = create("访客");
        setWorkspaceRole(scope, WorkspaceRoleEnum.GUEST);
        var rules = new PolicyRules(Set.of(MODEL), Map.of(MODEL, Set.of(WorkspaceRoleEnum.GUEST)), true, false,
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        f.workspacePolicies.put(scope, new WorkspacePolicy(scope, rules, 1, 1, 1));
        assertFalse(check(scope, MODEL_CREATE).allowed());
        f.tenantPolicies.put(scope.tenantId(), new TenantPolicy(scope.tenantId(), rules, false, 1, 1, 1));
        assertTrue(check(scope, MODEL_CREATE).allowed());
        assertEquals(AccessReasonEnum.ACTION_NOT_GRANTED, check(scope, WORKSPACE_AI_USE).reason());
    }

    @Test
    void scopeRejectsForeignTenantRevokedMembersAndCurrentIdentityChanges() {
        Scope scope = f.initialize(alice).workspace().scope();
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.scopes().verify(alice, new Scope("other-tenant", scope.workspaceId())));
        revokeSpace(scope);
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.scopes().verify(alice, scope));
        setWorkspaceRole(scope, WorkspaceRoleEnum.OWNER);
        var tenantMember = f.tenantMembers.get(List.of(scope.tenantId(), "alice"));
        f.tenantMembers.put(List.of(scope.tenantId(), "alice"), new TenantMembership(tenantMember.id(), scope.tenantId(), "alice",
                tenantMember.role(), MembershipStateEnum.REVOKED, 0, 1));
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.scopes().verify(alice, scope));
        f.tenantMembers.put(List.of(scope.tenantId(), "alice"), tenantMember);
        f.identities.put("alice", new Principal("alice", PrincipalKindEnum.USER, PrincipalStatusEnum.SUSPENDED));
        assertFalse(check(scope, WORKSPACE_READ).allowed());
    }

    @Test
    void archivedIsReadableButNotWritableAndClosingDeletedAreExcluded() {
        Scope scope = create("状态");
        setState(scope, WorkspaceStateEnum.ARCHIVED);
        assertEquals(WorkspaceStateEnum.ARCHIVED, f.services.workspaces().get(alice, scope).state());
        assertTrue(check(scope, WORKSPACE_RESTORE).allowed());
        assertCode(PlatformErrorCodeEnum.SPACE_READ_ONLY, () -> f.services.workspaces().rename(alice,
                new WorkspaceCommands.RenameWorkspace(meta("archived"), scope, 0, "不应修改", "")));
        setState(scope, WorkspaceStateEnum.CLOSING);
        assertCode(PlatformErrorCodeEnum.SPACE_CLOSING, () -> f.services.scopes().verify(alice, scope));
        setState(scope, WorkspaceStateEnum.DELETED);
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.scopes().verify(alice, scope));
    }

    @Test
    void visiblePaginationFiltersBeforeCountingAndScansMultipleCandidateBatches() {
        f.initialize(alice);
        for (int i = 0; i < 101; i++) create("空间" + i);
        f.initialize(f.user("bob"));
        f.scanAll = true;
        var lastPage = f.services.workspaces().listAccessible(alice, new PageQuery(2, 100));
        assertEquals(102, lastPage.total());
        assertEquals(2, lastPage.records().size());
        assertTrue(lastPage.records().stream().allMatch(w -> w.scope().tenantId().equals(f.preferences.get("alice").defaultScope().tenantId())));
        assertTrue(f.services.workspaces().listAccessible(alice, new PageQuery(103, 1)).records().isEmpty());
    }

    @Test
    void listDoesNotHideAuthorityFailureOrInfiniteCandidateCursor() {
        Scope scope = f.initialize(alice).workspace().scope();
        f.workspacePolicies.remove(scope);
        assertCode(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, () -> f.services.workspaces().listAccessible(alice, new PageQuery(1, 20)));
        f.workspacePolicies.put(scope, new WorkspacePolicy(scope, f.tenantPolicies.get(scope.tenantId()).rules(), 1, 1, 0));
        f.loopCursor = true;
        assertCode(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, () -> f.services.workspaces().listAccessible(alice, new PageQuery(1, 20)));
    }

    @Test
    void preferenceUpdatesAreVersionedBoundToSelfAndNeverRewriteDefaultScope() {
        Scope defaultScope = f.initialize(alice).workspace().scope();
        Scope project = create("启动空间");
        var command = new WorkspaceCommands.UpdatePreference(meta("preferences"), 0, project, project, Set.of(project, defaultScope));
        var first = f.services.preferences().update(alice, command);
        var replay = f.services.preferences().update(alice, new WorkspaceCommands.UpdatePreference(
                new CommandMeta("preferences", "another-trace"), 0, project, project, new LinkedHashSet<>(List.of(defaultScope, project))));
        assertEquals(defaultScope, first.value().defaultScope());
        assertEquals(project, first.value().startupScope());
        assertEquals(1, first.value().version());
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertCode(PlatformErrorCodeEnum.VERSION_CONFLICT, () -> f.services.preferences().update(alice,
                new WorkspaceCommands.UpdatePreference(meta("stale-pref"), 0, null, null, Set.of())));
        revokeSpace(project);
        var view = f.services.preferences().get(alice);
        assertEquals(defaultScope, view.startupScope());
        assertNull(view.lastScope());
        assertEquals(Set.of(defaultScope), view.favorites());
        assertEquals(project, f.preferences.get("alice").startupScope()); // 查询不偷偷执行偏好写入。
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.preferences().update(alice, command));
        var initialized = f.services.personal().initialize(alice, meta("fresh-initialization"));
        assertEquals(defaultScope, initialized.value().preference().startupScope());
    }

    @Test
    void unauthorizedPreferenceTargetDoesNotChangeVersionOrCreatePermission() {
        var personal = f.initialize(alice);
        Scope bob = f.initialize(f.user("bob")).workspace().scope();
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.preferences().update(alice,
                new WorkspaceCommands.UpdatePreference(meta("foreign-pref"), 0, bob, null, Set.of(bob))));
        assertEquals(0, f.preferences.get("alice").version());
        assertEquals(personal.workspace().scope(), f.preferences.get("alice").defaultScope());
        assertFalse(f.services.authorization().check(alice, DefaultAuthorizationService.workspaceRequest(bob, WORKSPACE_READ, "read")).allowed());
    }

    @Test
    void writeGuardRequiresRealTransactionAndRechecksRevokedQualificationBeforeWork() {
        Scope scope = create("门控");
        var request = DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_UPDATE, "write");
        AtomicBoolean ran = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> f.services.writeGuard().inCurrentTransaction(alice, List.of(request), () -> ran.getAndSet(true)));
        revokeSpace(scope);
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.transactions.required(() ->
                f.services.writeGuard().inCurrentTransaction(alice, List.of(request), () -> ran.getAndSet(true))));
        assertFalse(ran.get());
        setWorkspaceRole(scope, WorkspaceRoleEnum.OWNER);
        assertEquals("done", f.transactions.required(() -> f.services.writeGuard().inCurrentTransaction(alice, List.of(request), () -> "done")));
        var article = new AccessRequest(scope, new ResourceRef(scope.tenantId(), ARTICLE, "article"), ARTICLE_EDIT, "edit");
        assertCode(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED, () -> f.transactions.required(() ->
                f.services.writeGuard().inCurrentTransaction(alice, List.of(article), () -> "forbidden")));
    }

    @Test
    void writeGuardLocksAllTenantsThenSpacesThenMembersInDeterministicOrder() {
        Scope first = create("空间一");
        Scope second = create("空间二");
        f.lockOrder.clear();
        var requests = List.of(DefaultAuthorizationService.workspaceRequest(second, WORKSPACE_UPDATE, "write"),
                DefaultAuthorizationService.workspaceRequest(first, WORKSPACE_UPDATE, "write"));
        f.transactions.required(() -> f.services.writeGuard().inCurrentTransaction(alice, requests, () -> "done"));
        var spaces = List.of(first, second).stream().sorted(Comparator.comparing(Scope::workspaceId)).toList();
        assertEquals(List.of("subject:alice", "tenant:" + first.tenantId(), "workspace:" + spaces.get(0).workspaceId(),
                "workspace:" + spaces.get(1).workspaceId(), "tenant-member:" + first.tenantId(),
                "workspace-member:" + spaces.get(0).workspaceId(), "workspace-member:" + spaces.get(1).workspaceId()), f.lockOrder.stream().distinct().toList());
    }

    @Test
    void unknownActionBatchLimitAndAuthorityUnavailableNeverGrantAccess() {
        Scope scope = f.initialize(alice).workspace().scope();
        var unknown = DefaultAuthorizationService.workspaceRequest(scope, new Action("workspace:unknown"), "test");
        assertEquals(AccessReasonEnum.UNKNOWN_ACTION, f.services.authorization().check(alice, unknown).reason());
        var read = DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "read");
        assertEquals(List.of(f.services.authorization().check(alice, read), f.services.authorization().check(alice, unknown)),
                f.services.authorization().batchCheck(alice, List.of(read, unknown)));
        assertCode(PlatformErrorCodeEnum.INVALID_ARGUMENT, () -> f.services.authorization().batchCheck(alice, Collections.nCopies(101, read)));
        f.failIdentity = true;
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE, f.services.authorization().check(alice, read).reason());
        assertCode(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, () -> f.services.personal().initialize(alice, meta("outage")));
    }

    @Test
    void commandDigestDistinguishesNullEmptyAndDelimiterLikeFields() {
        assertNotEquals(DefaultCommandExecutor.digest(List.of("a:b", "c")), DefaultCommandExecutor.digest(List.of("a", "b:c")));
        assertNotEquals(DefaultCommandExecutor.digest(Arrays.asList("a", null)), DefaultCommandExecutor.digest(List.of("a", "")));
    }

    private Scope create(String name) {
        var personal = f.services.personal().initialize(alice, meta("initialize")).value();
        return f.services.workspaces().create(alice, new WorkspaceCommands.CreateWorkspace(meta("create-" + name), personal.tenant().id(), name, "")).value().scope();
    }

    private AccessDecision check(Scope scope, Action action) {
        return f.services.authorization().check(alice, DefaultAuthorizationService.workspaceRequest(scope, action, "test"));
    }

    private void setWorkspaceRole(Scope scope, WorkspaceRoleEnum role) {
        var key = workspaceKey(scope, "alice");
        var old = f.workspaceMembers.get(key);
        f.workspaceMembers.put(key, new WorkspaceMembership(old.id(), scope, "alice", role, MembershipStateEnum.ACTIVE,
                old.generation(), old.version() + 1));
    }

    private void setTenantRole(String tenantId, TenantRoleEnum role) {
        var key = List.of(tenantId, "alice");
        var old = f.tenantMembers.get(key);
        f.tenantMembers.put(key, new TenantMembership(old.id(), tenantId, "alice", role, MembershipStateEnum.ACTIVE, old.generation(), old.version() + 1));
    }

    private void revokeSpace(Scope scope) {
        var key = workspaceKey(scope, "alice");
        var old = f.workspaceMembers.get(key);
        f.workspaceMembers.put(key, new WorkspaceMembership(old.id(), scope, "alice", old.role(), MembershipStateEnum.REVOKED,
                old.generation(), old.version() + 1));
    }

    private void setState(Scope scope, WorkspaceStateEnum state) {
        Workspace old = f.workspaces.get(scope);
        f.workspaces.put(scope, new Workspace(scope, old.name(), old.description(), state, old.createdBy(), old.version(),
                old.authEpoch() + 1, old.createdAt(), NOW));
    }

    private static void assertCode(PlatformErrorCodeEnum code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(PlatformException.class, work).getCode());
    }
}
