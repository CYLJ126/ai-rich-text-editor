package com.arte.core.application.workspace;

import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.*;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessDecision;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.NOW;
import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.workspaceKey;
import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.ARTICLE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证批量读取的调用上限、共用规则及事实失效；不把内存适配计数冒充数据库性能测试。
 */
class WorkspaceBatchAuthorizationTest {
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture();
    private final TrustedActor alice = f.user("alice");

    @Test
    void directoryLoadsFactsByBatchWithoutPerCandidateReadsOrRepeatedSnapshots() {
        var scopes = seed(205);
        resetCounts();
        var page = f.services.workspaces().listAccessible(alice, new PageQuery(2, 100));
        assertEquals(205, page.total());
        assertEquals(scopes.subList(100, 200), page.records().stream().map(WorkspaceViews.WorkspaceSummary::scope).toList());
        assertEquals(3, f.candidateScans);
        assertEquals(3, f.factBatches);
        assertEquals(List.of(100, 100, 5), f.factBatchSizes);
        assertEquals(List.of(1, 1, 1), f.tenantBatchSizes);
        assertEquals(List.of(false, false, false), f.defaultPreferenceBatches);
        assertEquals(0, f.singleFactReads);
        assertEquals(5, f.identityReads); // 入口、每批及返回前，而非每个候选两次。
        assertEquals(1, f.preferenceReads);
    }

    @Test
    void repeatedActionsShareFactsAndMatchSingleChecksInInputOrder() {
        var scopes = seed(2);
        var actions = List.of(WORKSPACE_READ, WORKSPACE_UPDATE, WORKSPACE_ARCHIVE, WORKSPACE_CLOSE,
                WORKSPACE_AI_USE, ARTICLE_CREATE, MODEL_CREATE, WORKSPACE_OWNER_TRANSFER);
        List<AccessRequest> requests = new ArrayList<>();
        for (int i = 0; i < 98; i++) requests.add(request(scopes.get(i % 2), actions.get(i % actions.size())));
        requests.add(DefaultAuthorizationService.tenantRequest(scopes.getFirst().tenantId(), TENANT_READ, "test"));
        requests.add(request(scopes.getLast(), new Action("unknown:test")));
        var expected = requests.stream().map(r -> f.services.authorization().check(alice, r)).toList();
        resetCounts();
        var actual = f.services.authorization().batchCheck(alice, requests);
        assertEquals(expected, actual);
        assertEquals(1, f.factBatches);
        assertEquals(List.of(2), f.factBatchSizes);
        assertEquals(List.of(1), f.tenantBatchSizes);
        assertEquals(List.of(true), f.defaultPreferenceBatches);
        assertEquals(1, f.identityReads);
        assertEquals(0, f.singleFactReads);
        assertEquals(0, f.preferenceReads);
    }

    @Test
    void tenantGovernanceBatchDoesNotRequireAnyWorkspaceMembership() {
        Scope scope = seed(1).getFirst();
        f.workspaceMembers.clear();
        resetCounts();
        var decisions = f.services.authorization().batchCheck(alice, List.of(
                DefaultAuthorizationService.tenantRequest(scope.tenantId(), TENANT_READ, "test"),
                DefaultAuthorizationService.tenantRequest(scope.tenantId(), TENANT_POLICY_WRITE, "test")));
        assertTrue(decisions.stream().allMatch(AccessDecision::allowed));
        assertEquals(List.of(0), f.factBatchSizes);
        assertNull(decisions.getFirst().versions().workspaceEpoch());
        assertEquals(0, f.singleFactReads);
    }

    @Test
    void sameWorkspaceIdAcrossTenantsDoesNotReuseTheOtherTenantsMembership() {
        Scope original = seed(1).getFirst();
        Scope other = new Scope("other-tenant", original.workspaceId());
        f.tenants.put(other.tenantId(), new Tenant(other.tenantId(), "组织", TenantTypeEnum.ORGANIZATION, null,
                TenantStateEnum.ACTIVE, "alice", 0, 0, NOW, NOW));
        f.tenantMembers.put(List.of(other.tenantId(), "alice"), new TenantMembership("other-member", other.tenantId(),
                "alice", TenantRoleEnum.OWNER, MembershipStateEnum.ACTIVE, 0, 0));
        var policy = DefaultWorkspacePolicies.conservativePersonalDefaults().personalTenant(other.tenantId());
        f.tenantPolicies.put(other.tenantId(), policy);
        f.workspaces.put(other, new Workspace(other, "组织空间", "", WorkspaceStateEnum.ACTIVE, "alice", 0, 0, NOW, NOW));
        f.workspacePolicies.put(other, DefaultWorkspacePolicies.conservativePersonalDefaults().workspace(other, policy));
        resetCounts();
        var decisions = f.services.authorization().batchCheck(alice, List.of(request(original, WORKSPACE_READ), request(other, WORKSPACE_READ)));
        assertTrue(decisions.getFirst().allowed());
        assertEquals(AccessReasonEnum.NOT_FOUND, decisions.getLast().reason());
        assertEquals(List.of(2), f.factBatchSizes);
        assertEquals(List.of(2), f.tenantBatchSizes);
    }

    @Test
    void inactiveTenantDoesNotTriggerAnIdentityQueryForEveryCandidate() {
        var scopes = seed(205);
        var old = f.tenants.get(scopes.getFirst().tenantId());
        f.tenants.put(old.id(), new Tenant(old.id(), old.name(), old.type(), old.personalSubjectId(), TenantStateEnum.SUSPENDED,
                old.createdBy(), old.version(), old.authEpoch(), old.createdAt(), old.updatedAt()));
        resetCounts();
        var page = f.services.workspaces().listAccessible(alice, new PageQuery(1, 100));
        assertEquals(0, page.total());
        assertTrue(page.records().isEmpty());
        assertEquals(3, f.factBatches);
        assertEquals(0, f.singleFactReads);
        assertEquals(5, f.identityReads); // 租户不可用不触发额外身份重检。
    }

    @Test
    void missingMembersAndUnsupportedPoliciesAreIsolatedAndFollowSingleRules() {
        var scopes = seed(3);
        f.workspaceMembers.remove(workspaceKey(scopes.get(1), "alice"));
        var old = f.workspacePolicies.get(scopes.get(2));
        f.workspacePolicies.put(scopes.get(2), new WorkspacePolicy(old.scope(), old.rules(), 2, 1, old.version()));
        var requests = scopes.stream().map(s -> request(s, WORKSPACE_READ)).toList();
        var expected = requests.stream().map(r -> f.services.authorization().check(alice, r)).toList();
        assertEquals(expected, f.services.authorization().batchCheck(alice, requests));
        assertEquals(List.of(AccessReasonEnum.ALLOWED, AccessReasonEnum.NOT_FOUND, AccessReasonEnum.AUTHORITY_UNAVAILABLE),
                expected.stream().map(AccessDecision::reason).toList());
        assertCode(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE,
                () -> f.services.workspaces().listAccessible(alice, new PageQuery(1, 20)));
    }

    @Test
    void aMemberRevokedAfterCandidateScanIsFilteredUsingTheBatchFacts() {
        var scopes = seed(3);
        f.beforeFactBatch = batch -> f.workspaceMembers.remove(workspaceKey(scopes.get(1), "alice"));
        var page = f.services.workspaces().listAccessible(alice, new PageQuery(1, 20));
        assertEquals(2, page.total());
        assertEquals(List.of(scopes.getFirst(), scopes.getLast()), page.records().stream().map(WorkspaceViews.WorkspaceSummary::scope).toList());
    }

    @Test
    void laterBatchesReloadSharedTenantFactsAndDoNotReuseAStalePermit() {
        var scopes = seed(205);
        String tenantId = scopes.getFirst().tenantId();
        f.beforeFactBatch = batch -> {
            if (batch == 2) f.tenantMembers.remove(List.of(tenantId, "alice"));
        };
        var page = f.services.workspaces().listAccessible(alice, new PageQuery(1, 100));
        assertEquals(100, page.total());
        assertEquals(100, page.records().size());
        assertEquals(3, f.factBatches);
    }

    @Test
    void anotherRequestReloadsDefaultProtectionInsteadOfCachingTheEarlierResult() {
        var scope = seed(1).getFirst();
        var requests = List.of(request(scope, WORKSPACE_CLOSE));
        assertEquals(AccessReasonEnum.POLICY_DENIED, f.services.authorization().batchCheck(alice, requests).getFirst().reason());
        f.preferences.remove("alice");
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE, f.services.authorization().batchCheck(alice, requests).getFirst().reason());
        assertEquals(2, f.factBatches);
    }

    @Test
    void mismatchedMemberSubjectCannotAuthorizeAndDoesNotPoisonAnotherValidScope() {
        var scopes = seed(2);
        var old = f.workspaceMembers.get(workspaceKey(scopes.get(1), "alice"));
        f.transformBatchFacts = facts -> {
            Map<Scope, WorkspaceMembership> members = new HashMap<>(facts.workspaceMembers());
            members.put(old.scope(), new WorkspaceMembership(old.id(), old.scope(), "bob", old.role(), old.state(), old.generation(), old.version()));
            return factsWith(facts, facts.workspaces(), members);
        };
        var decisions = f.services.authorization().batchCheck(alice, scopes.stream().map(s -> request(s, WORKSPACE_READ)).toList());
        assertTrue(decisions.getFirst().allowed());
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE, decisions.getLast().reason());
    }

    @Test
    void factsOutsideTheRequestedFullScopeFailClosed() {
        Scope scope = seed(1).getFirst();
        f.transformBatchFacts = facts -> {
            Map<Scope, Workspace> rows = new HashMap<>(facts.workspaces());
            rows.put(new Scope("another-tenant", scope.workspaceId()), facts.workspaces().get(scope));
            return factsWith(facts, rows, facts.workspaceMembers());
        };
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE,
                f.services.authorization().batchCheck(alice, List.of(request(scope, WORKSPACE_READ))).getFirst().reason());
    }

    @Test
    void aWorkspaceStoredUnderTheWrongRequestedKeyCannotBeReturned() {
        var scopes = seed(2);
        f.transformBatchFacts = facts -> {
            Map<Scope, Workspace> rows = new HashMap<>(facts.workspaces());
            rows.put(scopes.get(1), rows.get(scopes.getFirst()));
            return factsWith(facts, rows, facts.workspaceMembers());
        };
        var decisions = f.services.authorization().batchCheck(alice, scopes.stream().map(s -> request(s, WORKSPACE_READ)).toList());
        assertTrue(decisions.getFirst().allowed());
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE, decisions.getLast().reason());
    }

    @Test
    void oneHundredDifferentTenantsAndScopesStillUseOneBoundedFactLoad() {
        List<AccessRequest> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) requests.add(request(new Scope("tenant-" + i, "space"), WORKSPACE_READ));
        resetCounts();
        var decisions = f.services.authorization().batchCheck(alice, requests);
        assertTrue(decisions.stream().allMatch(d -> d.reason() == AccessReasonEnum.NOT_FOUND));
        assertEquals(1, f.factBatches);
        assertEquals(List.of(100), f.factBatchSizes);
        assertEquals(List.of(100), f.tenantBatchSizes);
        assertEquals(1, f.identityReads);
        assertEquals(0, f.singleFactReads);
    }

    @Test
    void aggregateStoreFailureDoesNotFallBackToIndividualQueriesOrPretendTheDirectoryIsEmpty() {
        Scope scope = seed(1).getFirst();
        resetCounts();
        f.failBatchFacts = true;
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE,
                f.services.authorization().batchCheck(alice, List.of(request(scope, WORKSPACE_READ))).getFirst().reason());
        assertCode(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE,
                () -> f.services.workspaces().listAccessible(alice, new PageQuery(1, 20)));
        assertEquals(0, f.singleFactReads);
    }

    @Test
    void inactiveAndUnavailableIdentityPreventLoadingFacts() {
        Scope scope = seed(1).getFirst();
        var requests = List.of(request(scope, WORKSPACE_READ));
        resetCounts();
        f.identities.put("alice", new Principal("alice", PrincipalKindEnum.USER, PrincipalStatusEnum.SUSPENDED));
        assertEquals(AccessReasonEnum.SUBJECT_INACTIVE, f.services.authorization().batchCheck(alice, requests).getFirst().reason());
        f.failIdentity = true;
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE, f.services.authorization().batchCheck(alice, requests).getFirst().reason());
        assertEquals(0, f.factBatches);
    }

    @Test
    void emptyUnsupportedAndOversizedInputsDoNotIssueBatchReads() {
        Scope scope = seed(1).getFirst();
        resetCounts();
        assertTrue(f.services.authorization().batchCheck(alice, List.of()).isEmpty());
        var body = new AccessRequest(scope, new ResourceRef(scope.tenantId(), ARTICLE, "article"), ARTICLE_CREATE, "test");
        assertEquals(AccessReasonEnum.ACTION_NOT_GRANTED, f.services.authorization().batchCheck(alice, List.of(body)).getFirst().reason());
        assertCode(PlatformErrorCodeEnum.INVALID_ARGUMENT,
                () -> f.services.authorization().batchCheck(alice, Collections.nCopies(101, request(scope, WORKSPACE_READ))));
        assertEquals(0, f.factBatches);
        assertEquals(0, f.identityReads);
        assertEquals(0, f.singleFactReads);
    }

    private List<Scope> seed(int count) {
        Workspace root = f.workspaces.get(f.initialize(alice).workspace().scope());
        List<Scope> scopes = new ArrayList<>(List.of(root.scope()));
        for (int i = 1; i < count; i++) {
            Scope scope = new Scope(root.scope().tenantId(), "space-%03d".formatted(i));
            var createdAt = NOW.minusSeconds(i);
            f.workspaces.put(scope, new Workspace(scope, "空间 " + i, "", WorkspaceStateEnum.ACTIVE, "alice", 0, 0, createdAt, createdAt));
            f.workspaceMembers.put(workspaceKey(scope, "alice"), new WorkspaceMembership("member-" + i, scope, "alice", WorkspaceRoleEnum.OWNER, MembershipStateEnum.ACTIVE, 0, 0));
            f.workspacePolicies.put(scope, new WorkspacePolicy(scope, f.workspacePolicies.get(root.scope()).rules(), 1, 1, 0));
            scopes.add(scope);
        }
        return scopes;
    }

    private void resetCounts() {
        f.identityReads = f.singleFactReads = f.preferenceReads = f.factBatches = f.candidateScans = 0;
        f.factBatchSizes.clear();
        f.tenantBatchSizes.clear();
        f.defaultPreferenceBatches.clear();
    }

    private static AccessRequest request(Scope scope, Action action) {
        return DefaultAuthorizationService.workspaceRequest(scope, action, "test");
    }

    private static WorkspaceAccessFacts factsWith(WorkspaceAccessFacts facts, Map<Scope, Workspace> rows,
                                                  Map<Scope, WorkspaceMembership> members) {
        return new WorkspaceAccessFacts(facts.tenants(), facts.tenantMembers(), facts.tenantPolicies(), rows,
                members, facts.workspacePolicies(), facts.defaultPreferences());
    }

    private static void assertCode(PlatformErrorCodeEnum expected, Runnable action) {
        assertEquals(expected, assertThrows(PlatformException.class, action::run).getCode());
    }
}
