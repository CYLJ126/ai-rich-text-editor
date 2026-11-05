package com.arte.core.application.workspace;

import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.TenantStateEnum;
import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantDirectoryTest {

    @Test
    void budgetLimitedEmptyPageAdvancesPastRevokedAndNonmatchingCandidates() {
        var fixture = new InMemoryWorkspaceFixture(Clock.fixed(InMemoryWorkspaceFixture.NOW, ZoneOffset.UTC), List.of(),
                new WorkspaceBrowseBudget(2, 2, 1, Duration.ofSeconds(1)), () -> 0);
        var alice = fixture.user("alice");
        seed(fixture, "alpha", "Match revoked");
        seed(fixture, "beta", "Unrelated name");
        seed(fixture, "gamma", "Match current");
        fixture.beforeFactBatch = batch -> {
            if (batch == 1) {
                // Membership changes after candidate selection, before the authoritative batch read.
                fixture.tenantMembers.put(List.of("alpha", "alice"), new TenantMembership("alpha-member", "alpha",
                        "alice", TenantRoleEnum.MEMBER, MembershipStateEnum.REVOKED, 0, 1));
            }
        };
        var tenants = fixture.services.tenantQueries();
        var query = new TenantBrowseQuery(1, "  MATCH  ", TenantTypeEnum.ORGANIZATION);
        var first = tenants.browseAccessible(alice, query, null);
        assertTrue(first.records().isEmpty());
        assertFalse(first.exhausted());
        assertEquals("beta", first.nextBoundary());
        var second = tenants.browseAccessible(alice, query, first.nextBoundary());
        assertTrue(second.exhausted());
        assertEquals("gamma", second.records().getFirst().id());
        assertNull(second.nextBoundary());
        assertTrue(fixture.preferences.isEmpty());
    }

    private static void seed(InMemoryWorkspaceFixture fixture, String id, String name) {
        var now = InMemoryWorkspaceFixture.NOW;
        fixture.tenants.put(id, new Tenant(id, name, TenantTypeEnum.ORGANIZATION, null,
                TenantStateEnum.ACTIVE, "alice", 0, 0, now, now));
        fixture.tenantMembers.put(List.of(id, "alice"), new TenantMembership(id + "-member", id,
                "alice", TenantRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0));
        var rules = DefaultWorkspacePolicies.conservativePersonalDefaults().personalTenant(id).rules();
        fixture.tenantPolicies.put(id, new TenantPolicy(id, rules, false, 1, 1, 0));
    }
}
