package com.arte.base.model;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.execution.IdempotencyKey;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import org.junit.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class ExecutionContextTest {

    private static ExecutionScope scope(String tenant, String workspace, String principal) {
        return new ExecutionScope(tenant, workspace, new PrincipalRef(principal, PrincipalType.USER));
    }

    @Test
    public void changingCallerOwnedScopesDoesNotChangeSubmittedContext() {
        Set<String> scopes = new HashSet<>(Set.of("resource.read"));
        ExecutionContext context = ExecutionContext.create(scope("tenant-1", "workspace-1", "user-1"), "trace-1", scopes);
        scopes.clear();
        scopes.add("resource.write");

        assertEquals(Set.of("resource.read"), context.authorizationScopes());
        assertThrows(UnsupportedOperationException.class, () -> context.authorizationScopes().add("resource.write"));
    }

    @Test
    public void minimalContextDoesNotInventIdentityPermissionsOrBudget() {
        ExecutionContext context = ExecutionContext.create(scope("tenant-1", "workspace-1", "user-1"), "trace-1", Set.of());
        assertTrue(context.authorizationScopes().isEmpty());
        assertNull(context.budgetRef());
        assertNull(context.releaseRef());
        assertNull(context.deadline());
        assertThrows(IllegalArgumentException.class, () -> ExecutionContext.create(null, "trace-1", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> scope("tenant-1", "", "user-1"));
        assertThrows(IllegalArgumentException.class, () -> new PrincipalRef("user-1", null));
    }

    @Test
    public void invalidIdentifiersAndNullScopeElementsAreRejectedWithoutEchoingTheirValues() {
        String unsafeValue = "secret-token\nforged-log-line";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PrincipalRef(unsafeValue, PrincipalType.USER));
        assertFalse(error.getMessage().contains("secret-token"));
        assertThrows(IllegalArgumentException.class, () -> new PrincipalRef(" user-1", PrincipalType.USER));
        assertThrows(IllegalArgumentException.class, () -> new PrincipalRef("\u00a0", PrincipalType.USER));
        assertThrows(IllegalArgumentException.class, () -> ExecutionContext.create(scope("tenant-1", "workspace-1", "user-1"), "trace-1", null));
        Set<String> invalid = new HashSet<>();
        invalid.add(null);
        assertThrows(IllegalArgumentException.class, () -> ExecutionContext.create(scope("tenant-1", "workspace-1", "user-1"), "trace-1", invalid));
    }

    @Test
    public void expiredHistoryRemainsReadableAndDeadlineIsInclusive() {
        Instant deadline = Instant.parse("2026-10-02T08:00:00Z");
        ExecutionContext history = new ExecutionContext(scope("tenant-1", "workspace-1", "user-1"), "trace-1",
                null, deadline, null, Set.of(), null, null, null);
        assertFalse(history.isExpiredAt(deadline.minusNanos(1)));
        assertTrue(history.isExpiredAt(deadline));
        assertTrue(history.isExpiredAt(deadline.plusSeconds(1)));
        assertFalse(ExecutionContext.create(history.scope(), "trace-2", Set.of()).isExpiredAt(deadline));
        assertThrows(IllegalArgumentException.class, () -> history.isExpiredAt(null));
    }

    @Test
    public void duplicateKeyWithDifferentInputHasSameLookupIdentityButDifferentDigest() {
        ExecutionScope scope = scope("tenant-1", "workspace-1", "user-1");
        IdempotencyKey first = new IdempotencyKey("request-1", "generate", "sha256:first");
        IdempotencyKey changed = new IdempotencyKey("request-1", "generate", "sha256:changed");
        assertEquals(first.identityIn(scope), changed.identityIn(scope));
        assertNotEquals(first.requestDigest(), changed.requestDigest());
    }

    @Test
    public void lookupIdentitySeparatesTenantWorkspacePrincipalKindAndOperation() {
        IdempotencyKey key = new IdempotencyKey("request-1", "generate", "sha256:first");
        ExecutionScope origin = scope("tenant-1", "workspace-1", "user-1");
        assertNotEquals(key.identityIn(origin), key.identityIn(scope("tenant-2", "workspace-1", "user-1")));
        assertNotEquals(key.identityIn(origin), key.identityIn(scope("tenant-1", "workspace-2", "user-1")));
        assertNotEquals(key.identityIn(origin), key.identityIn(scope("tenant-1", "workspace-1", "user-2")));
        assertNotEquals(key.identityIn(origin), key.identityIn(new ExecutionScope("tenant-1", "workspace-1",
                new PrincipalRef("user-1", PrincipalType.SERVICE))));
        assertNotEquals(key.identityIn(origin), new IdempotencyKey("request-1", "apply", "sha256:first").identityIn(origin));
        assertNotEquals(key.identityIn(origin), new IdempotencyKey("request-2", "generate", "sha256:first").identityIn(origin));
    }
}
