package com.arte.base.security;

import com.arte.base.model.security.AuthorizationRequest;
import com.arte.base.model.security.PolicyDecision;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.arte.base.security.SecurityFixtures.*;
import static org.junit.Assert.*;

public class PolicyDecisionTest {

    @Test
    public void callerCannotMutateRecordedPolicyVersionsOrReasons() {
        Map<String, String> versions = new HashMap<>(Map.of("resource-policy", "version-1"));
        Set<String> reasons = new HashSet<>(Set.of("action_not_allowed"));
        PolicyDecision decision = PolicyDecision.deny(reasons, versions, NOW);
        versions.put("resource-policy", "version-2");
        reasons.clear();
        assertEquals("version-1", decision.policyVersions().get("resource-policy"));
        assertEquals(Set.of("action_not_allowed"), decision.reasonCodes());
        assertThrows(UnsupportedOperationException.class, () -> decision.policyVersions().put("policy", "v2"));
        assertThrows(UnsupportedOperationException.class, () -> decision.reasonCodes().clear());
    }

    @Test
    public void allowRequiresObservedVersionAndPositiveExpiryWhileFailuresRequireReason() {
        assertThrows(IllegalArgumentException.class, () -> PolicyDecision.allow(Map.of(), NOW, NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> PolicyDecision.allow(Map.of("policy", "v1"), NOW, null));
        assertThrows(IllegalArgumentException.class, () -> PolicyDecision.allow(Map.of("policy", "v1"), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> PolicyDecision.deny(Set.of(), Map.of(), NOW));
        assertThrows(IllegalArgumentException.class, () -> PolicyDecision.indeterminate(Set.of(), NOW));
        assertThrows(IllegalArgumentException.class, () -> PolicyDecision.allow(Map.of("policy", ""), NOW, NOW.plusSeconds(1)));
    }

    @Test
    public void validityWindowIsInclusiveAtEvaluationAndExclusiveAtExpiry() {
        PolicyDecision policy = allow();
        assertFalse(policy.isAllowedAt(NOW.minusNanos(1)));
        assertTrue(policy.isAllowedAt(NOW));
        assertFalse(policy.isAllowedAt(NOW.plusSeconds(5)));
        assertFalse(deny().isAllowedAt(NOW));
        assertFalse(PolicyDecision.indeterminate(Set.of("unsupported"), NOW).isAllowedAt(NOW));
    }

    @Test
    public void customActionIsSnapshottedInsteadOfHoldingMutableExtension() {
        AtomicReference<String> action = new AtomicReference<>("article.custom_read");
        AuthorizationRequest request = AuthorizationRequest.of(context(), read().resource(), action::get);
        action.set("article.custom_write");
        assertEquals("article.custom_read", request.actionCode());
    }
}
