package com.arte.core.application.workspace;

import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.*;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 12:10 ✾
 */
class WorkspaceCandidateScanTest {
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture();
    private final TrustedActor alice = f.user("alice");

    @Test
    void renamingAnUnscannedWorkspaceCannotMoveItAheadOfTheCursorAndOmitIt() {
        var scopes = seed(205);
        Scope renamed = scopes.get(150);
        f.beforeCandidateScan = batch -> {
            if (batch == 2) f.services.workspaces().rename(alice,
                    new WorkspaceCommands.RenameWorkspace(meta("rename-unscanned"), renamed, 0, "已改名", ""));
        };
        var page = f.services.workspaces().listAccessible(alice, new PageQuery(2, 100));
        assertEquals(205, page.total());
        assertEquals(scopes.subList(100, 200), page.records().stream().map(WorkspaceViews.WorkspaceSummary::scope).toList());
        assertEquals("已改名", page.records().stream().filter(row -> row.scope().equals(renamed)).findFirst().orElseThrow().name());
        assertEquals(3, f.candidateScans);
    }

    @Test
    void renamingAnAlreadyScannedWorkspaceCannotCauseDuplicatesOrShiftFollowingCandidates() {
        var scopes = seed(205);
        f.beforeCandidateScan = batch -> {
            if (batch == 2) f.services.workspaces().rename(alice,
                    new WorkspaceCommands.RenameWorkspace(meta("rename-scanned"), scopes.get(50), 0, "已改名", ""));
        };
        var page = f.services.workspaces().listAccessible(alice, new PageQuery(3, 100));
        assertEquals(205, page.total());
        assertEquals(scopes.subList(200, 205), page.records().stream().map(WorkspaceViews.WorkspaceSummary::scope).toList());
    }

    @Test
    void deletingTheCursorBoundaryDoesNotRequireLookingUpThatRowOrShiftContinuation() {
        var scopes = seed(205);
        var first = f.workspaceStore.scanMembershipCandidates("alice", null, 100);
        Scope boundary = first.records().getLast().scope();
        f.workspaces.remove(boundary);
        f.workspaceMembers.remove(workspaceKey(boundary, "alice"));
        var rest = scan(first.nextCursor(), 100);
        assertEquals(scopes.subList(100, 205), rest);
        assertEquals(rest.size(), rest.stream().distinct().count());
    }

    @Test
    void removingAnEarlierMemberCandidateCannotShiftAnOffsetAndSkipTheNextRow() {
        var scopes = seed(205);
        var first = f.workspaceStore.scanMembershipCandidates("alice", null, 100);
        f.workspaceMembers.remove(workspaceKey(scopes.getFirst(), "alice"));
        assertEquals(scopes.subList(100, 205), scan(first.nextCursor(), 100));
    }

    @Test
    void equalCreationTimesAndSameWorkspaceIdsAcrossTenantsUseTheFullScopeAsTiebreaker() {
        seed("z-tenant", "same-space", NOW);
        seed("a-tenant", "same-space", NOW);
        seed("a-tenant", "a-space", NOW);
        seed("a-tenant", "z-space", NOW);
        assertEquals(List.of(new Scope("a-tenant", "a-space"), new Scope("a-tenant", "same-space"),
                new Scope("a-tenant", "z-space"), new Scope("z-tenant", "same-space")), scan(null, 1));
    }

    @Test
    void insertingANewerWorkspaceAheadOfTheBoundaryDoesNotDisturbExistingContinuation() {
        var scopes = seed(8);
        var first = f.workspaceStore.scanMembershipCandidates("alice", null, 3);
        Scope newer = seed("tenant", "newer", NOW.plusSeconds(1));
        assertEquals(scopes.subList(3, 8), scan(first.nextCursor(), 3));
        assertEquals(newer, scan(null, 3).getFirst());
    }

    @Test
    void malformedAdapterOrderFailsClosedInsteadOfReturningAnIncompleteDirectory() {
        seed(3);
        f.reverseCandidateOrder = true;
        var error = assertThrows(PlatformException.class,
                () -> f.services.workspaces().listAccessible(alice, new PageQuery(1, 20)));
        assertEquals(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, error.getCode());
    }

    @Test
    void cursorIsOpaqueSubjectBoundAndLimitIsBounded() {
        seed(3);
        var first = f.workspaceStore.scanMembershipCandidates("alice", null, 1);
        assertNotNull(first.nextCursor());
        assertThrows(IllegalArgumentException.class,
                () -> f.workspaceStore.scanMembershipCandidates("bob", first.nextCursor(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> f.workspaceStore.scanMembershipCandidates("alice", "unknown", 1));
        assertThrows(IllegalArgumentException.class,
                () -> f.workspaceStore.scanMembershipCandidates("alice", null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> f.workspaceStore.scanMembershipCandidates("alice", null, 101));
    }

    @Test
    void replacementCannotChangeCreationTimeAndInvalidateExistingCursors() {
        Scope scope = seed(1).getFirst();
        var old = f.workspaces.get(scope);
        var changedKey = new Workspace(scope, old.name(), old.description(), old.state(), old.createdBy(),
                1, old.authEpoch(), old.createdAt().minusSeconds(1), NOW);
        assertFalse(f.transactions.required(() -> f.workspaceStore.replace(changedKey, 0)));
        assertEquals(old, f.workspaces.get(scope));
    }

    private List<Scope> scan(String cursor, int limit) {
        List<Scope> result = new ArrayList<>();
        do {
            var batch = f.workspaceStore.scanMembershipCandidates("alice", cursor, limit);
            result.addAll(batch.records().stream().map(Workspace::scope).toList());
            cursor = batch.nextCursor();
        } while (cursor != null);
        return result;
    }

    private List<Scope> seed(int count) {
        List<Scope> scopes = new ArrayList<>();
        for (int i = 0; i < count; i++) scopes.add(seed("tenant", "space-%03d".formatted(i), NOW.minusSeconds(i)));
        return scopes;
    }

    private Scope seed(String tenantId, String workspaceId, Instant createdAt) {
        Scope scope = new Scope(tenantId, workspaceId);
        f.tenants.putIfAbsent(tenantId, new Tenant(tenantId, "组织", TenantTypeEnum.ORGANIZATION, null,
                TenantStateEnum.ACTIVE, "alice", 0, 0, NOW, NOW));
        f.tenantMembers.putIfAbsent(List.of(tenantId, "alice"), new TenantMembership("member-" + tenantId,
                tenantId, "alice", TenantRoleEnum.OWNER, MembershipStateEnum.ACTIVE, 0, 0));
        var policy = DefaultWorkspacePolicies.conservativePersonalDefaults().personalTenant(tenantId);
        f.tenantPolicies.put(tenantId, policy);
        f.workspaces.put(scope, new Workspace(scope, workspaceId, "", WorkspaceStateEnum.ACTIVE, "alice", 0, 0, createdAt, createdAt));
        f.workspaceMembers.put(workspaceKey(scope, "alice"), new WorkspaceMembership("member-" + workspaceId, scope,
                "alice", WorkspaceRoleEnum.OWNER, MembershipStateEnum.ACTIVE, 0, 0));
        f.workspacePolicies.put(scope, DefaultWorkspacePolicies.conservativePersonalDefaults().workspace(scope, policy));
        return scope;
    }
}
