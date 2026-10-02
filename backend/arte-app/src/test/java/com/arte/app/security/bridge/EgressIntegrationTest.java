package com.arte.app.security.bridge;

import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;
import com.arte.base.model.security.CommonResourceAction;
import com.arte.base.model.security.EgressDestination;
import com.arte.base.model.security.EgressRequest;
import com.arte.base.model.security.PolicyOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EgressIntegrationTest extends SecurityBridgeFixture {
    @Test
    void realResourceGrantsAndConfirmedFullRequestAllowEgress() {
        var context = context(CommonResourceAction.READ, CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS);
        grant("10", 1, CommonResourceAction.AI_PROCESS);
        grant("10", 1, CommonResourceAction.EGRESS);
        var request = prepared(context, List.of(new SourceRef(ARTICLE, "source-1")));
        assertEquals(PolicyOutcome.DENY, egress.evaluate(request).policy().outcome());
        var confirmed = confirmed(request);
        assertEquals(PolicyOutcome.ALLOW, egress.requireAllowed(confirmed, clock).policy().outcome());
        jdbc.update("UPDATE arte_rt_article SET is_delete = 1 WHERE id = 10");
        denied(confirmed);
    }

    @Test
    void standaloneMessagesStillRequirePurposeConnectionTaskAndConsent() {
        var context = context(CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS);
        var request = prepared(context, List.of());
        denied(request);
        var confirmed = confirmed(request);
        egress.requireAllowed(confirmed, clock);
        jdbc.update("UPDATE arte_security_application_policy SET application_enabled = FALSE WHERE action_code = 'resource.ai_process'");
        denied(confirmed);
    }

    @Test
    void readAndConsentCannotOverrideMissingResourceEgressGrant() {
        var context = context(CommonResourceAction.READ, CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS);
        grant("10", 1, CommonResourceAction.AI_PROCESS);
        var request = prepared(context, List.of(new SourceRef(ARTICLE, null)));
        assertThrows(AccessDeniedException.class, () -> consents.confirm(http, request));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_consent", Integer.class));
    }

    @Test
    void consentCannotBeReusedForChangedContentPurposeDestinationSourceOrTask() {
        var context = context(CommonResourceAction.READ, CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS);
        grant("10", 1, CommonResourceAction.AI_PROCESS);
        grant("10", 1, CommonResourceAction.EGRESS);
        var request = confirmed(prepared(context, List.of(new SourceRef(ARTICLE, "source-1"))));
        var changed = new ArrayList<EgressRequest>();
        changed.add(new EgressRequest(context, request.executor(), request.sources(), request.destination(), request.purpose(), "different-digest", request.consentRef()));
        changed.add(new EgressRequest(context, request.executor(), request.sources(), request.destination(), "training", request.contentDigest(), request.consentRef()));
        changed.add(new EgressRequest(context, request.executor(), List.of(), request.destination(), request.purpose(), request.contentDigest(), request.consentRef()));
        changed.add(new EgressRequest(context, request.executor(), List.of(new SourceRef(ARTICLE.withRange("paragraph-2"), "source-1")), request.destination(), request.purpose(), request.contentDigest(), request.consentRef()));
        changed.add(new EgressRequest(context, request.executor(), List.of(new SourceRef(ResourceRef.saved("ARTICLE", "10", "v2"), "source-1")), request.destination(), request.purpose(), request.contentDigest(), request.consentRef()));
        changed.add(new EgressRequest(context, request.executor(), request.sources(), new EgressDestination(CONNECTION, URI.create("https://other.example")), request.purpose(), request.contentDigest(), request.consentRef()));
        var otherContext = context(CommonResourceAction.READ, CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS);
        changed.add(new EgressRequest(otherContext, request.executor(), request.sources(), request.destination(), request.purpose(), request.contentDigest(), request.consentRef()));
        changed.forEach(this::denied);
        egress.requireAllowed(request, clock);
    }

    @Test
    void allSourcesMustBeAuthorizedAndNoPartialCollectionIsApproved() {
        var context = context(CommonResourceAction.READ, CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS);
        grant("10", 1, CommonResourceAction.AI_PROCESS);
        grant("10", 1, CommonResourceAction.EGRESS);
        var request = prepared(context, List.of(new SourceRef(ARTICLE, null), new SourceRef(ResourceRef.saved("ARTICLE", "11", "v1"), null)));
        assertThrows(AccessDeniedException.class, () -> consents.confirm(http, request));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_consent", Integer.class));
    }

    @Test
    void consentOwnerIsVerifiedFromCurrentSession() {
        var request = prepared(context(CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS), List.of());
        login("bob", 2);
        assertThrows(AccessDeniedException.class, () -> consents.confirm(http, request));
    }

    @Test
    void currentConsentConnectionAndPurposeRevocationsOverridePreviouslyAllowedRequest() {
        var request = confirmed(prepared(context(CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS), List.of()));
        egress.requireAllowed(request, clock);
        jdbc.update("UPDATE arte_security_consent SET enabled = FALSE, revision = 2");
        denied(request);
        jdbc.update("UPDATE arte_security_consent SET enabled = TRUE, revision = 1");
        jdbc.update("UPDATE arte_security_connection SET enabled = FALSE, revision = 2");
        denied(request);
        jdbc.update("UPDATE arte_security_connection SET enabled = TRUE");
        jdbc.update("UPDATE arte_security_egress_rule SET enabled = FALSE, revision = 2");
        denied(request);
    }

    @Test
    void expiredConsentIsDeniedWithoutDependingOnTaskExpiry() {
        var request = confirmed(prepared(context(CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS), List.of()));
        jdbc.update("UPDATE arte_security_consent SET valid_until = ?", java.sql.Timestamp.from(NOW));
        denied(request);
    }

    private void denied(EgressRequest request) {
        var error = assertThrows(BaseException.class, () -> egress.requireAllowed(request, clock));
        assertEquals(CommonErrorCode.UNAUTHORIZED.code(), error.error().code());
    }
}
