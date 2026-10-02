package com.arte.base.security;

import com.arte.base.api.security.AuthorizationService;
import com.arte.base.api.security.EgressPolicy;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;
import com.arte.base.model.security.*;
import org.junit.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arte.base.security.SecurityFixtures.*;
import static org.junit.Assert.*;

public class EgressPolicyTest {

    @Test
    public void readPermissionDoesNotOverrideEgressDenial() {
        AuthorizationService readService = request -> new AuthorizationDecision(request, allow());
        readService.requireAuthorized(read(), CLOCK);
        EgressPolicy policy = request -> new EgressDecision(request, deny());
        BaseException denial = assertThrows(BaseException.class, () -> policy.requireAllowed(egress(), CLOCK));
        assertEquals(CommonErrorCode.UNAUTHORIZED.code(), denial.error().code());
        assertEquals("egress", denial.error().failureStage());
    }

    @Test
    public void copiedSourcesCannotBeChangedAndEmptySourcesDoNotBypassEvaluation() {
        EgressRequest original = egress();
        List<SourceRef> sources = new ArrayList<>(original.sources());
        EgressRequest copied = EgressRequest.of(context(), sources, original.destination(), original.purpose(),
                original.contentDigest(), null);
        sources.clear();
        assertEquals(1, copied.sources().size());
        assertThrows(UnsupportedOperationException.class, () -> copied.sources().clear());

        AtomicInteger calls = new AtomicInteger();
        EgressPolicy policy = request -> {
            calls.incrementAndGet();
            return new EgressDecision(request, deny());
        };
        EgressRequest messageOnly = EgressRequest.of(context(), List.of(), original.destination(), original.purpose(),
                original.contentDigest(), null);
        assertThrows(BaseException.class, () -> policy.requireAllowed(messageOnly, CLOCK));
        assertEquals(1, calls.get());
    }

    @Test
    public void completePacketScopeIsBoundAndPartialSourcePermissionIsNotAccepted() {
        EgressRequest approved = egress();
        EgressPolicy stale = ignored -> new EgressDecision(approved, allow());
        List<EgressRequest> changed = List.of(
                EgressRequest.of(context(), approved.sources(), approved.destination(), approved.purpose(), "sha256:packet-2", null),
                EgressRequest.of(context(), approved.sources(), approved.destination(), "ai.embed", approved.contentDigest(), null),
                EgressRequest.of(context(), approved.sources(), destination("version-2", "https://example.test"), approved.purpose(), approved.contentDigest(), null),
                EgressRequest.of(context(), approved.sources(), destination("version-1", "https://other.test"), approved.purpose(), approved.contentDigest(), null),
                EgressRequest.of(context(), List.of(), approved.destination(), approved.purpose(), approved.contentDigest(), null),
                EgressRequest.of(context(), List.of(approved.sources().getFirst(),
                                new SourceRef(ResourceRef.saved("article", "article-2", "rev-1"), "source-2")),
                        approved.destination(), approved.purpose(), approved.contentDigest(), null),
                EgressRequest.of(context(), approved.sources(), approved.destination(), approved.purpose(),
                        approved.contentDigest(), ResourceRef.current("consent", "consent-1")),
                EgressRequest.of(context("tenant-1", "workspace-1", "user-2"), approved.sources(),
                        approved.destination(), approved.purpose(), approved.contentDigest(), null));
        for (EgressRequest request : changed) {
            assertEquals(CommonErrorCode.POLICY_UNAVAILABLE.code(),
                    assertThrows(BaseException.class, () -> stale.requireAllowed(request, CLOCK)).error().code());
        }
    }

    @Test
    public void equivalentOriginsNormalizeButNewConnectionVersionStaysDistinct() {
        assertEquals(destination("version-1", "HTTPS://EXAMPLE.TEST:443/"), destination("version-1", "https://example.test"));
        assertEquals(destination("version-1", "http://example.test:80/"), destination("version-1", "http://example.test"));
        assertNotEquals(destination("version-1", "https://example.test:8443"), destination("version-1", "https://example.test"));
        assertNotEquals(destination("version-1", "https://example.test"), destination("version-2", "https://example.test"));
    }

    @Test
    public void destinationRequiresResolvedVersionAndOriginCannotCarryCredentialsOrApiParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> new EgressDestination(ResourceRef.current("connection", "connection-1"), URI.create("https://example.test")));
        assertThrows(IllegalArgumentException.class,
                () -> new EgressDestination(ResourceRef.draft("connection", "connection-1", "v1", "draft-1", "digest"),
                        URI.create("https://example.test")));
        for (String origin : List.of("/local", "file:///tmp", "https://user:secret@example.test",
                "https://example.test/api", "https://example.test?api-key=secret", "https://example.test#fragment",
                "https://example.test:0", "https://example.test:65536")) {
            assertThrows(IllegalArgumentException.class, () -> destination("version-1", origin));
        }
    }

    @Test
    public void noResultProviderExceptionAndIndeterminateNeverSendAnything() {
        List<EgressPolicy> policies = List.of(
                ignored -> null,
                request -> new EgressDecision(request, PolicyDecision.indeterminate(Set.of("consent_unknown"), NOW)),
                ignored -> {
                    throw new IllegalStateException("private-provider-diagnostic");
                });
        for (EgressPolicy policy : policies) {
            assertEquals(CommonErrorCode.POLICY_UNAVAILABLE.code(),
                    assertThrows(BaseException.class, () -> policy.requireAllowed(egress(), CLOCK)).error().code());
        }
    }

    @Test
    public void decisionThatExpiresDuringEvaluationIsNotAccepted() {
        MutableClock clock = new MutableClock();
        EgressPolicy policy = request -> {
            clock.now = NOW.plusSeconds(5);
            return new EgressDecision(request, allow());
        };
        assertEquals(CommonErrorCode.POLICY_UNAVAILABLE.code(),
                assertThrows(BaseException.class, () -> policy.requireAllowed(egress(), clock)).error().code());
    }
}
