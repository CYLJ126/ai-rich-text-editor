package com.arte.core.application.workspace;

import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.TenantStateEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.meta;
import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.workspaceKey;
import static com.arte.core.constant.platform.PlatformActions.TENANT_READ;
import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_READ;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 租户生命周期与主体生命周期分别报告，普通、批量及事务内最终授权采用相同分类。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 12:57 ✾
 */
class TenantAvailabilityAuthorizationTest {
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture();
    private final TrustedActor alice = f.user("alice");

    @ParameterizedTest
    @EnumSource(value = TenantStateEnum.class, names = {"SUSPENDED", "CLOSING"})
    void singleAndBatchChecksReportTenantUnavailableWithoutDisablingThePrincipal(TenantStateEnum state) {
        Scope scope = f.initialize(alice).workspace().scope();
        setTenantState(scope, state);
        var requests = requests(scope);
        var batch = f.services.authorization().batchCheck(alice, requests);
        for (int i = 0; i < requests.size(); i++) {
            var request = requests.get(i);
            var decision = f.services.authorization().check(alice, request);
            assertEquals(AccessReasonEnum.TENANT_INACTIVE, decision.reason());
            assertFalse(decision.allowed());
            assertNull(decision.versions());
            assertEquals(decision, batch.get(i));
            assertCode(PlatformErrorCodeEnum.TENANT_INACTIVE, () -> f.services.authorization().require(alice, request));
        }
        assertCode(PlatformErrorCodeEnum.TENANT_INACTIVE, () -> f.services.scopes().verify(alice, scope));
        assertCode(PlatformErrorCodeEnum.TENANT_INACTIVE, () -> f.services.workspaces().get(alice, scope));
        assertEquals(PrincipalStatusEnum.ACTIVE, f.identities.get("alice").status());
    }

    @ParameterizedTest
    @EnumSource(value = TenantStateEnum.class, names = {"SUSPENDED", "CLOSING"})
    void finalAuthorizationUsesCurrentTenantStateAndKeepsItsOwnErrorCode(TenantStateEnum state) {
        Scope scope = f.initialize(alice).workspace().scope();
        f.captureOldReadSnapshot();
        setTenantState(scope, state);
        for (var request : requests(scope)) {
            assertTrue(f.services.authorization().check(alice, request).allowed());
            assertEquals(AccessReasonEnum.TENANT_INACTIVE,
                    f.transactions.required(() -> f.services.authorization().checkCurrent(alice, request)).reason());
            assertCode(PlatformErrorCodeEnum.TENANT_INACTIVE, () -> f.transactions.required(
                    () -> f.services.authorization().requireInCurrentTransaction(alice, request)));
            AtomicBoolean ran = new AtomicBoolean();
            assertCode(PlatformErrorCodeEnum.TENANT_INACTIVE, () -> f.transactions.required(
                    () -> f.services.writeGuard().inCurrentTransaction(alice, List.of(request), () -> ran.getAndSet(true))));
            assertFalse(ran.get());
        }
    }

    @ParameterizedTest
    @EnumSource(value = TenantStateEnum.class, names = {"SUSPENDED", "CLOSING"})
    void createAndRenameRejectTheTenantStateAndLeaveNoPartialWrites(TenantStateEnum state) {
        Scope scope = f.initialize(alice).workspace().scope();
        setTenantState(scope, state);
        Workspace old = f.workspaces.get(scope);
        int eventCount = f.events.size();
        int receiptCount = f.receipts.size();
        assertCode(PlatformErrorCodeEnum.TENANT_INACTIVE, () -> f.services.workspaces().create(alice,
                new WorkspaceCommands.CreateWorkspace(meta("create-unavailable"), scope.tenantId(), "新空间", "")));
        assertCode(PlatformErrorCodeEnum.TENANT_INACTIVE, () -> f.services.workspaces().rename(alice,
                new WorkspaceCommands.RenameWorkspace(meta("rename-unavailable"), scope, old.version(), "新名称", "")));
        assertEquals(1, f.workspaces.size());
        assertEquals(old, f.workspaces.get(scope));
        assertEquals(eventCount, f.events.size());
        assertEquals(receiptCount, f.receipts.size());
    }

    @ParameterizedTest
    @EnumSource(value = PrincipalStatusEnum.class, names = {"SUSPENDED", "DELETED"})
    void inactivePrincipalsStillReportSubjectInactive(PrincipalStatusEnum status) {
        Scope scope = f.initialize(alice).workspace().scope();
        f.identities.put("alice", new Principal("alice", alice.principal().kind(), status));
        var requests = requests(scope);
        for (var request : requests) {
            assertEquals(AccessReasonEnum.SUBJECT_INACTIVE, f.services.authorization().check(alice, request).reason());
            assertEquals(AccessReasonEnum.SUBJECT_INACTIVE,
                    f.transactions.required(() -> f.services.authorization().checkCurrent(alice, request)).reason());
            assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.authorization().require(alice, request));
        }
        assertTrue(f.services.authorization().batchCheck(alice, requests).stream()
                .allMatch(decision -> decision.reason() == AccessReasonEnum.SUBJECT_INACTIVE));
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED,
                () -> f.services.workspaces().listAccessible(alice, new PageQuery(1, 20)));
    }

    @ParameterizedTest
    @EnumSource(value = TenantStateEnum.class, names = {"SUSPENDED", "CLOSING"})
    void callersWithoutTenantMembershipCannotDiscoverItsLifecycle(TenantStateEnum state) {
        Scope scope = f.initialize(alice).workspace().scope();
        setTenantState(scope, state);
        f.tenantMembers.remove(List.of(scope.tenantId(), "alice"));
        var requests = requests(scope);
        assertTrue(requests.stream().allMatch(request -> f.services.authorization().check(alice, request).reason() == AccessReasonEnum.NOT_FOUND));
        assertTrue(f.services.authorization().batchCheck(alice, requests).stream()
                .allMatch(decision -> decision.reason() == AccessReasonEnum.NOT_FOUND));
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.scopes().verify(alice, scope));
    }

    @ParameterizedTest
    @EnumSource(value = TenantStateEnum.class, names = {"SUSPENDED", "CLOSING"})
    void unavailableTenantIsExcludedWithoutBlockingOtherAccessibleTenants(TenantStateEnum state) {
        Scope unavailable = f.initialize(alice).workspace().scope();
        Scope accessible = f.initialize(f.user("bob")).workspace().scope();
        f.tenantMembers.put(List.of(accessible.tenantId(), "alice"), new TenantMembership("alice-tenant-member", accessible.tenantId(),
                "alice", TenantRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0));
        f.workspaceMembers.put(workspaceKey(accessible, "alice"), new WorkspaceMembership("alice-workspace-member", accessible,
                "alice", WorkspaceRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0));
        setTenantState(unavailable, state);
        f.identityReads = 0;
        var page = f.services.workspaces().listAccessible(alice, new PageQuery(1, 20));
        assertEquals(1, page.total());
        assertEquals(List.of(accessible), page.records().stream().map(WorkspaceViews.WorkspaceSummary::scope).toList());
        assertEquals(3, f.identityReads); // 入口、批次及返回前；租户状态不额外查身份。
        assertTrue(f.services.authorization().check(alice, requests(accessible).getLast()).allowed());
    }

    @Test
    void missingTenantAndAuthorityFailureKeepTheirExistingReasons() {
        Scope scope = f.initialize(alice).workspace().scope();
        var requests = requests(scope);
        f.tenants.remove(scope.tenantId());
        assertTrue(f.services.authorization().batchCheck(alice, requests).stream()
                .allMatch(decision -> decision.reason() == AccessReasonEnum.NOT_FOUND));
        f.failBatchFacts = true;
        assertTrue(f.services.authorization().batchCheck(alice, requests).stream()
                .allMatch(decision -> decision.reason() == AccessReasonEnum.AUTHORITY_UNAVAILABLE));
    }

    private static List<AccessRequest> requests(Scope scope) {
        return List.of(DefaultAuthorizationService.tenantRequest(scope.tenantId(), TENANT_READ, "test"),
                DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "test"));
    }

    private void setTenantState(Scope scope, TenantStateEnum state) {
        Tenant old = f.tenants.get(scope.tenantId());
        f.tenants.put(old.id(), new Tenant(old.id(), old.name(), old.type(), old.personalSubjectId(), state,
                old.createdBy(), old.version() + 1, old.authEpoch() + 1, old.createdAt(), old.updatedAt()));
    }

    private static void assertCode(PlatformErrorCodeEnum code, Runnable work) {
        assertEquals(code, assertThrows(PlatformException.class, work::run).getCode());
    }
}
