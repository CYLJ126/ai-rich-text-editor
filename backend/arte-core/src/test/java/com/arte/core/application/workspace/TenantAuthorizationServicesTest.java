package com.arte.core.application.workspace;

import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.*;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.AccessScope;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.NOW;
import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.workspaceKey;
import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantAuthorizationServicesTest {
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture();
    private final TrustedActor alice = f.user("alice");

    @ParameterizedTest
    @EnumSource(TenantRoleEnum.class)
    void tenantRoleMatrixNeedsNoWorkspaceAndIsSharedByAllAuthorizationEntrypoints(TenantRoleEnum role) {
        seedTenant("organization", role);
        for (Action action : List.of(TENANT_READ, TENANT_WORKSPACE_CREATE, TENANT_POLICY_WRITE,
                TENANT_MEMBER_MANAGE, TENANT_OWNER_TRANSFER, TENANT_MODEL_MANAGE, RESOURCE_ACCESS_MANAGE)) {
            var request = tenantRequest("organization", action);
            boolean expected = action.equals(TENANT_READ)
                    || (action.equals(TENANT_OWNER_TRANSFER) && role == TenantRoleEnum.OWNER)
                    || (List.of(TENANT_WORKSPACE_CREATE, TENANT_POLICY_WRITE, TENANT_MEMBER_MANAGE).contains(action)
                    && (role == TenantRoleEnum.OWNER || role == TenantRoleEnum.ADMIN));
            assertEquals(expected, f.services.authorization().check(alice, request).allowed(), action.value());
            assertEquals(expected, f.services.authorization().batchCheck(alice, List.of(request)).getFirst().allowed());
            if (expected) {
                var verified = f.services.authorization().require(alice, request);
                assertEquals(7, verified.versions().tenantEpoch());
                assertEquals(2, verified.versions().tenantMembershipGeneration());
                assertEquals(3, verified.versions().tenantMembershipVersion());
                assertEquals(Long.valueOf(4), verified.versions().resourceVersion());
                assertNull(verified.versions().workspaceEpoch());
                assertNull(verified.versions().workspaceMembershipGeneration());
                assertNull(verified.versions().workspaceMembershipVersion());
                assertEquals("done", f.transactions.required(() -> f.services.writeGuard().inCurrentTransaction(
                        alice, List.of(request), () -> {
                            assertEquals(verified, f.services.authorization().requireInCurrentTransaction(alice, request));
                            return "done";
                        })));
            } else {
                assertThrows(PlatformException.class, () -> f.services.authorization().require(alice, request));
                assertGuardDenies(request);
            }
        }
        assertTrue(f.workspaces.isEmpty());
        assertTrue(f.lockOrder.stream().noneMatch(lock -> lock.startsWith("workspace:") || lock.startsWith("workspace-member:")));
    }

    @ParameterizedTest
    @EnumSource(WorkspaceStateEnum.class)
    void legacyRequestWithWorkspaceNeverBindsTenantGovernanceToWorkspaceStateOrMembership(WorkspaceStateEnum state) {
        seedTenant("organization", TenantRoleEnum.OWNER);
        Scope scope = new Scope("organization", "unjoined-space");
        f.workspaces.put(scope, new Workspace(scope, "他人的空间", "", state, "bob", 0, 0, NOW, NOW));
        var tenantRequest = new AccessRequest(scope, new ResourceRef("organization", TENANT, "organization"),
                TENANT_MEMBER_MANAGE, "manage");
        assertTrue(f.services.authorization().check(alice, tenantRequest).allowed());
        assertEquals("done", f.transactions.required(() -> f.services.writeGuard().inCurrentTransaction(
                alice, List.of(tenantRequest), () -> "done")));
        assertTrue(f.lockOrder.stream().noneMatch(lock -> lock.startsWith("workspace:")));
        var workspaceRequest = DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "read");
        assertFalse(f.services.authorization().check(alice, workspaceRequest).allowed());
        assertGuardDenies(workspaceRequest);
    }

    @Test
    void missingOrRevokedAttachedWorkspaceCannotBlockTenantGovernance() {
        Scope scope = f.initialize(alice).workspace().scope();
        var member = f.workspaceMembers.get(workspaceKey(scope, "alice"));
        f.workspaceMembers.put(workspaceKey(scope, "alice"), new WorkspaceMembership(member.id(), scope, "alice",
                member.role(), MembershipStateEnum.REVOKED, member.generation(), member.version() + 1));
        var request = new AccessRequest(scope, new ResourceRef(scope.tenantId(), TENANT, scope.tenantId()),
                TENANT_POLICY_WRITE, "manage");
        assertTrue(f.services.authorization().check(alice, request).allowed());
        assertFalse(f.services.authorization().check(alice,
                DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_UPDATE, "write")).allowed());
        f.workspaces.remove(scope);
        assertTrue(f.services.authorization().check(alice, request).allowed());
        assertEquals("done", f.transactions.required(() -> f.services.writeGuard().inCurrentTransaction(
                alice, List.of(request), () -> "done")));
    }

    @Test
    void finalTenantAuthorizationRejectsMembershipRevocationHiddenByOldSnapshot() {
        seedTenant("organization", TenantRoleEnum.OWNER);
        var request = tenantRequest("organization", TENANT_MEMBER_MANAGE);
        f.captureOldReadSnapshot();
        var old = f.tenantMembers.get(List.of("organization", "alice"));
        f.tenantMembers.put(List.of("organization", "alice"), new TenantMembership(old.id(), old.tenantId(), old.subjectId(),
                old.role(), MembershipStateEnum.REVOKED, old.generation(), old.version() + 1));
        assertTrue(f.services.authorization().check(alice, request).allowed());
        assertGuardDenies(request);
    }

    @Test
    void finalTenantAuthorizationRejectsRoleDowngradeHiddenByOldSnapshot() {
        seedTenant("organization", TenantRoleEnum.ADMIN);
        var request = tenantRequest("organization", TENANT_POLICY_WRITE);
        f.captureOldReadSnapshot();
        var old = f.tenantMembers.get(List.of("organization", "alice"));
        f.tenantMembers.put(List.of("organization", "alice"), new TenantMembership(old.id(), old.tenantId(), old.subjectId(),
                TenantRoleEnum.MEMBER, old.state(), old.generation(), old.version() + 1));
        assertTrue(f.services.authorization().check(alice, request).allowed());
        assertGuardDenies(request);
    }

    @ParameterizedTest
    @EnumSource(value = TenantStateEnum.class, names = {"SUSPENDED", "CLOSING"})
    void finalTenantAuthorizationRejectsInactiveTenantHiddenByOldSnapshot(TenantStateEnum state) {
        seedTenant("organization", TenantRoleEnum.OWNER);
        var request = tenantRequest("organization", TENANT_MEMBER_MANAGE);
        f.captureOldReadSnapshot();
        var old = f.tenants.get("organization");
        f.tenants.put(old.id(), new Tenant(old.id(), old.name(), old.type(), old.personalSubjectId(), state,
                old.createdBy(), old.version() + 1, old.authEpoch() + 1, old.createdAt(), old.updatedAt()));
        assertTrue(f.services.authorization().check(alice, request).allowed());
        assertGuardDenies(request);
    }

    @Test
    void finalTenantAuthorizationUsesCurrentCreationPolicy() {
        seedTenant("organization", TenantRoleEnum.MEMBER);
        var policy = f.tenantPolicies.get("organization");
        f.tenantPolicies.put("organization", new TenantPolicy("organization", policy.rules(), true, 1, 1, 0));
        var request = tenantRequest("organization", TENANT_WORKSPACE_CREATE);
        f.captureOldReadSnapshot();
        f.tenantPolicies.put("organization", policy);
        assertTrue(f.services.authorization().check(alice, request).allowed());
        assertGuardDenies(request);
    }

    @Test
    void finalTenantAuthorizationReturnsLatestTenantAndMembershipVersions() {
        seedTenant("organization", TenantRoleEnum.OWNER);
        f.captureOldReadSnapshot();
        var tenant = f.tenants.get("organization");
        f.tenants.put(tenant.id(), new Tenant(tenant.id(), tenant.name(), tenant.type(), tenant.personalSubjectId(), tenant.state(),
                tenant.createdBy(), 5, 8, tenant.createdAt(), tenant.updatedAt()));
        var member = f.tenantMembers.get(List.of("organization", "alice"));
        f.tenantMembers.put(List.of("organization", "alice"), new TenantMembership(member.id(), member.tenantId(), member.subjectId(),
                member.role(), member.state(), 3, 4));
        var request = tenantRequest("organization", TENANT_POLICY_WRITE);
        assertEquals(7, f.services.authorization().require(alice, request).versions().tenantEpoch());
        var verified = f.transactions.required(() -> f.services.writeGuard().inCurrentTransaction(
                alice, List.of(request), () -> f.services.authorization().requireInCurrentTransaction(alice, request)));
        assertEquals(8, verified.versions().tenantEpoch());
        assertEquals(3, verified.versions().tenantMembershipGeneration());
        assertEquals(4, verified.versions().tenantMembershipVersion());
        assertEquals(Long.valueOf(5), verified.versions().resourceVersion());
        assertNull(verified.versions().workspaceEpoch());
    }

    @ParameterizedTest
    @EnumSource(value = PrincipalStatusEnum.class, names = {"SUSPENDED", "DELETED"})
    void finalTenantAuthorizationRejectsDisabledIdentityHiddenByOldSnapshot(PrincipalStatusEnum status) {
        seedTenant("organization", TenantRoleEnum.OWNER);
        f.captureOldReadSnapshot();
        f.identities.put("alice", new com.arte.core.pojo.platform.Principal("alice", alice.principal().kind(), status));
        var request = tenantRequest("organization", TENANT_POLICY_WRITE);
        assertTrue(f.services.authorization().check(alice, request).allowed());
        assertGuardDenies(request);
    }

    @Test
    void currentTenantPolicyMissingOrUnknownSchemaFailsClosed() {
        seedTenant("organization", TenantRoleEnum.OWNER);
        var policy = f.tenantPolicies.get("organization");
        f.captureOldReadSnapshot();
        var request = tenantRequest("organization", TENANT_MEMBER_MANAGE);
        f.tenantPolicies.remove("organization");
        assertGuardDenies(request);
        f.tenantPolicies.put("organization", new TenantPolicy("organization", policy.rules(), false, 2, 1, 1));
        assertGuardDenies(request);
    }

    @Test
    void tenantOnlyRangeDoesNotGrantWorkspaceOrContentActionsAndCannotCrossTenants() {
        seedTenant("organization", TenantRoleEnum.OWNER);
        for (Action action : List.of(WORKSPACE_READ, WORKSPACE_UPDATE, ARTICLE_READ, MODEL_USE, RESOURCE_ACCESS_MANAGE)) {
            assertFalse(f.services.authorization().check(alice, tenantRequest("organization", action)).allowed());
        }
        var article = new AccessRequest(AccessScope.tenant("organization"), new ResourceRef("organization", ARTICLE, "private"),
                ARTICLE_READ, "read");
        assertFalse(f.services.authorization().check(alice, article).allowed());
        assertGuardDenies(article);
        var other = f.user("bob");
        assertFalse(f.services.authorization().check(other, tenantRequest("organization", TENANT_POLICY_WRITE)).allowed());
        var wrongRoot = new AccessRequest(AccessScope.tenant("organization"), new ResourceRef("organization", TENANT, "other"),
                TENANT_POLICY_WRITE, "manage");
        assertFalse(f.services.authorization().check(alice, wrongRoot).allowed());
        assertGuardDenies(wrongRoot);
        assertThrows(IllegalArgumentException.class, () -> new AccessRequest(AccessScope.tenant("organization"),
                new ResourceRef("other", TENANT, "other"), TENANT_POLICY_WRITE, "manage"));
        assertThrows(IllegalArgumentException.class, () -> new AccessRequest(AccessScope.tenant("organization"),
                new ResourceRef("organization", WORKSPACE, "space"), WORKSPACE_READ, "read"));
    }

    @Test
    void mixedTenantAndWorkspaceTargetsShareOneDeterministicLockOrder() {
        Scope scope = f.initialize(alice).workspace().scope();
        seedTenant("a-organization", TenantRoleEnum.OWNER);
        f.lockOrder.clear();
        var requests = List.of(DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_UPDATE, "write"),
                tenantRequest("a-organization", TENANT_POLICY_WRITE));
        assertEquals("done", f.transactions.required(() -> f.services.writeGuard().inCurrentTransaction(
                alice, requests, () -> "done")));
        assertEquals(List.of("subject:alice", "tenant:a-organization", "tenant:" + scope.tenantId(),
                "workspace:" + scope.workspaceId(), "tenant-member:a-organization", "tenant-member:" + scope.tenantId(),
                "workspace-member:" + scope.workspaceId()), f.lockOrder.stream().distinct().toList());
    }

    @Test
    void currentTenantAuthorizationRequiresTransactionAndUnknownActionsAreRejected() {
        seedTenant("organization", TenantRoleEnum.OWNER);
        var request = tenantRequest("organization", TENANT_POLICY_WRITE);
        assertThrows(IllegalStateException.class,
                () -> f.services.authorization().requireInCurrentTransaction(alice, request));
        assertEquals(AccessReasonEnum.UNKNOWN_ACTION,
                f.services.authorization().check(alice, tenantRequest("organization", new Action("tenant:unknown"))).reason());
    }

    private AccessRequest tenantRequest(String tenantId, Action action) {
        return DefaultAuthorizationService.tenantRequest(tenantId, action, "manage");
    }

    private void seedTenant(String tenantId, TenantRoleEnum role) {
        f.tenants.put(tenantId, new Tenant(tenantId, "组织", TenantTypeEnum.ORGANIZATION, null, TenantStateEnum.ACTIVE,
                "alice", 4, 7, NOW, NOW));
        f.tenantMembers.put(List.of(tenantId, "alice"), new TenantMembership("member-" + tenantId, tenantId, "alice", role,
                MembershipStateEnum.ACTIVE, 2, 3));
        f.tenantPolicies.put(tenantId, new TenantPolicy(tenantId, DefaultWorkspacePolicies.conservativePersonalDefaults().personalTenant(tenantId).rules(),
                false, 1, 1, 0));
    }

    private void assertGuardDenies(AccessRequest request) {
        var ran = new AtomicBoolean();
        assertThrows(PlatformException.class, () -> f.transactions.required(() -> f.services.writeGuard().inCurrentTransaction(
                alice, List.of(request), () -> ran.getAndSet(true))));
        assertFalse(ran.get());
    }
}
