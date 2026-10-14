package com.arte.core.application.workspace;

import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.enums.workspace.WorkspaceStateEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.NOW;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceBrowseTest {
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture();
    private final TrustedActor actor = f.user("alice");

    @Test
    void partialBatchBoundaryNeverSkipsUnreturnedVisibleRows() {
        var expected = seed(f, actor, 85, 0);
        expected.add(f.preferences.get("alice").defaultScope());
        var found = new ArrayList<Scope>();
        WorkspaceCandidateKey after = null;
        do {
            var page = f.services.workspaces().browseAccessible(actor, new WorkspaceBrowseQuery(20, ""), after);
            found.addAll(page.records().stream().map(WorkspaceViews.WorkspaceSummary::scope).toList());
            after = page.nextBoundary();
            if (page.exhausted()) break;
        } while (true);
        assertEquals(expected, found);
        assertEquals(5, f.candidateScans);
    }

    @Test
    void emptyBudgetLimitedPageCanContinueAndFindVisibleRows() {
        seed(f, actor, 450, 250);
        var first = f.services.workspaces().browseAccessible(actor, new WorkspaceBrowseQuery(20, "target"), null);
        assertTrue(first.records().isEmpty());
        assertFalse(first.exhausted());
        assertNotNull(first.nextBoundary());
        assertEquals(3, f.candidateScans);
        var second = f.services.workspaces().browseAccessible(actor, new WorkspaceBrowseQuery(20, "target"), first.nextBoundary());
        assertEquals(20, second.records().size());
        assertTrue(second.nextBoundary().compareTo(first.nextBoundary()) > 0);
        assertEquals(5, f.candidateScans);
    }

    @Test
    void monotonicWorkDeadlineStopsBeforeAnotherBatchAndStillAdvances() {
        var ticks = new AtomicLong();
        var timed = new InMemoryWorkspaceFixture(Clock.fixed(NOW, ZoneOffset.UTC), List.of(),
                new WorkspaceBrowseBudget(20, 100, 10, Duration.ofNanos(10)), ticks::get);
        var user = timed.user("alice");
        seed(timed, user, 70, 0);
        timed.beforeCandidateScan = batch -> ticks.addAndGet(20);
        var result = timed.services.workspaces().browseAccessible(user, new WorkspaceBrowseQuery(20, "missing"), null);
        assertTrue(result.records().isEmpty());
        assertFalse(result.exhausted());
        assertEquals(1, timed.candidateScans);
        assertNotNull(result.nextBoundary());
    }

    @Test
    void revocationBetweenPagesIsRecheckedAndDeletedBoundaryNeedsNoLookup() {
        var scopes = seed(f, actor, 70, 0);
        var first = f.services.workspaces().browseAccessible(actor, new WorkspaceBrowseQuery(20, "target"), null);
        var boundary = first.nextBoundary();
        f.workspaces.remove(boundary.scope());
        f.workspaceMembers.remove(InMemoryWorkspaceFixture.workspaceKey(boundary.scope(), "alice"));
        Scope removed = scopes.get(20);
        var member = f.workspaceMembers.get(InMemoryWorkspaceFixture.workspaceKey(removed, "alice"));
        f.workspaceMembers.put(InMemoryWorkspaceFixture.workspaceKey(removed, "alice"), new WorkspaceMembership(
                member.id(), removed, "alice", member.role(), MembershipStateEnum.REVOKED, member.generation(), 1));
        var next = f.services.workspaces().browseAccessible(actor, new WorkspaceBrowseQuery(20, "target"), boundary);
        assertEquals(scopes.subList(21, 41), next.records().stream().map(WorkspaceViews.WorkspaceSummary::scope).toList());
    }

    @Test
    void authorityFailureIsNotAFalseEmptyOrExhaustedPage() {
        var scopes = seed(f, actor, 30, 0);
        f.workspacePolicies.remove(scopes.getFirst());
        var failure = assertThrows(PlatformException.class,
                () -> f.services.workspaces().browseAccessible(actor, new WorkspaceBrowseQuery(20, ""), null));
        assertEquals(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, failure.getCode());
    }

    @Test
    void changedCreationFactCannotSilentlyInvalidateTheCandidateBoundary() {
        var scopes = seed(f, actor, 30, 0);
        f.beforeFactBatch = batch -> {
            var old = f.workspaces.get(scopes.getFirst());
            f.workspaces.put(old.scope(), new Workspace(old.scope(), old.name(), old.description(), old.state(),
                    old.createdBy(), old.version(), old.authEpoch(), old.createdAt().plusNanos(1000), old.updatedAt().plusNanos(1000)));
        };
        var error = assertThrows(PlatformException.class,
                () -> f.services.workspaces().browseAccessible(actor, new WorkspaceBrowseQuery(20, ""), null));
        assertEquals(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, error.getCode());
    }

    @Test
    void nameSearchUsesCurrentAuthorizedFactsAndCanExhaustWithoutMatches() {
        var scopes = seed(f, actor, 70, 0);
        f.beforeFactBatch = batch -> {
            var old = f.workspaces.get(scopes.getFirst());
            f.workspaces.put(old.scope(), new Workspace(old.scope(), "Needle", "", old.state(), old.createdBy(),
                    1, old.authEpoch(), old.createdAt(), old.updatedAt()));
        };
        var match = f.services.workspaces().browseAccessible(actor, new WorkspaceBrowseQuery(20, "  NEEDLE  "), null);
        assertTrue(match.exhausted());
        assertEquals(List.of(scopes.getFirst()), match.records().stream().map(WorkspaceViews.WorkspaceSummary::scope).toList());
    }

    @Test
    void exactVisibleCountIsAnIndependentExplicitOperation() {
        seed(f, actor, 70, 25);
        assertEquals(46, f.services.workspaces().countAccessible(actor));
        assertEquals(1, f.candidateScans);
    }

    @Test
    void accountRevokedDuringReadCannotPublishPreviouslyAccumulatedResults() {
        seed(f, actor, 60, 0);
        f.beforeFactBatch = batch -> f.identities.put("alice", new Principal("alice",
                actor.principal().kind(), PrincipalStatusEnum.SUSPENDED));
        assertThrows(PlatformException.class,
                () -> f.services.workspaces().browseAccessible(actor, new WorkspaceBrowseQuery(20, ""), null));
    }

    private static List<Scope> seed(InMemoryWorkspaceFixture fixture, TrustedActor user, int count, int revoked) {
        var base = fixture.initialize(user).workspace().scope();
        var result = new ArrayList<Scope>();
        var rules = fixture.workspacePolicies.get(base).rules();
        for (int i = 0; i < count; i++) {
            var scope = new Scope(base.tenantId(), "candidate-%04d".formatted(i));
            Instant created = NOW.plusSeconds(count - i);
            fixture.workspaces.put(scope, new Workspace(scope, "target-" + i, "", WorkspaceStateEnum.ACTIVE,
                    user.principal().subjectId(), 0, 0, created, created));
            fixture.workspaceMembers.put(InMemoryWorkspaceFixture.workspaceKey(scope, user.principal().subjectId()),
                    new WorkspaceMembership("member-" + i, scope, user.principal().subjectId(), WorkspaceRoleEnum.MEMBER,
                            i < revoked ? MembershipStateEnum.REVOKED : MembershipStateEnum.ACTIVE, 0, 0));
            fixture.workspacePolicies.put(scope, new WorkspacePolicy(scope, rules, 1, 1, 0));
            result.add(scope);
        }
        return result;
    }
}
