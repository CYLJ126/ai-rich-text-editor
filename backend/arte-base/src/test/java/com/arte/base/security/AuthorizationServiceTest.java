package com.arte.base.security;

import com.arte.base.api.security.AuthorizationService;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.security.AuthorizationDecision;
import com.arte.base.model.security.AuthorizationRequest;
import com.arte.base.model.security.CommonResourceAction;
import com.arte.base.model.security.PolicyDecision;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arte.base.security.SecurityFixtures.*;
import static org.junit.Assert.*;

public class AuthorizationServiceTest {

    @Test
    public void readDoesNotImplicitlyAuthorizeEditAiUseOrEgress() {
        AuthorizationService service = request -> new AuthorizationDecision(request,
                request.actionCode().equals(CommonResourceAction.READ.code()) ? allow() : deny());
        assertNotNull(service.requireAuthorized(read(), CLOCK));
        for (CommonResourceAction action : List.of(CommonResourceAction.EDIT, CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS)) {
            AuthorizationRequest request = AuthorizationRequest.of(context(), read().resource(), action);
            assertEquals(CommonErrorCode.UNAUTHORIZED.code(),
                    assertThrows(BaseException.class, () -> service.requireAuthorized(request, CLOCK)).error().code());
        }
    }

    @Test
    public void nullIndeterminateAndProviderExceptionNeverBecomePermission() {
        AuthorizationRequest request = read();
        List<AuthorizationService> services = List.of(
                ignored -> null,
                input -> new AuthorizationDecision(input, PolicyDecision.indeterminate(Set.of("policy_unavailable"), NOW)),
                ignored -> {
                    throw new IllegalStateException("private-provider-diagnostic");
                });
        for (AuthorizationService service : services) {
            BaseException failure = assertThrows(BaseException.class, () -> service.requireAuthorized(request, CLOCK));
            assertEquals(CommonErrorCode.POLICY_UNAVAILABLE.code(), failure.error().code());
            assertEquals("authorization", failure.error().failureStage());
            assertFalse(failure.error().toString().contains("private-provider-diagnostic"));
        }
    }

    @Test
    public void permissionRevocationIsObservedByEvaluatingAgain() {
        AtomicInteger evaluations = new AtomicInteger();
        AuthorizationService service = request -> new AuthorizationDecision(request,
                evaluations.incrementAndGet() == 1 ? allow() : deny());
        service.requireAuthorized(read(), CLOCK);
        assertEquals(CommonErrorCode.UNAUTHORIZED.code(),
                assertThrows(BaseException.class, () -> service.requireAuthorized(read(), CLOCK)).error().code());
        assertEquals(2, evaluations.get());
    }

    @Test
    public void allowDecisionCannotBeReusedForAnotherBoundaryActionOrVersion() {
        AuthorizationRequest approved = read();
        AuthorizationService stale = ignored -> new AuthorizationDecision(approved, allow());
        List<AuthorizationRequest> changed = List.of(
                AuthorizationRequest.of(context("tenant-2", "workspace-1", "user-1"), approved.resource(), CommonResourceAction.READ),
                AuthorizationRequest.of(context("tenant-1", "workspace-2", "user-1"), approved.resource(), CommonResourceAction.READ),
                AuthorizationRequest.of(context("tenant-1", "workspace-1", "user-2"), approved.resource(), CommonResourceAction.READ),
                AuthorizationRequest.of(context(), approved.resource(), CommonResourceAction.EDIT),
                AuthorizationRequest.of(context(), ResourceRef.saved("article", "article-1", "rev-2"), CommonResourceAction.READ),
                AuthorizationRequest.of(context(), ResourceRef.saved("article", "article-2", "rev-1"), CommonResourceAction.READ),
                AuthorizationRequest.of(context(), approved.resource().withRange("selection-1"), CommonResourceAction.READ));
        for (AuthorizationRequest request : changed) {
            assertEquals(CommonErrorCode.POLICY_UNAVAILABLE.code(),
                    assertThrows(BaseException.class, () -> stale.requireAuthorized(request, CLOCK)).error().code());
        }
    }

    @Test
    public void expiredAndFutureDatedAllowDecisionsAreNotAccepted() {
        for (PolicyDecision policy : List.of(
                PolicyDecision.allow(Map.of("policy", "v1"), NOW.minusSeconds(5), NOW),
                PolicyDecision.allow(Map.of("policy", "v1"), NOW.plusSeconds(1), NOW.plusSeconds(5)))) {
            AuthorizationService service = request -> new AuthorizationDecision(request, policy);
            assertEquals(CommonErrorCode.POLICY_UNAVAILABLE.code(),
                    assertThrows(BaseException.class, () -> service.requireAuthorized(read(), CLOCK)).error().code());
        }
    }

    @Test
    public void expiredExecutionIsRejectedBeforeCallingProvider() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionContext context = new ExecutionContext(context().scope(), "trace-1", null, NOW,
                null, Set.of(), null, null, null);
        AuthorizationRequest request = AuthorizationRequest.of(context, read().resource(), CommonResourceAction.READ);
        AuthorizationService service = input -> {
            calls.incrementAndGet();
            return new AuthorizationDecision(input, allow());
        };
        assertEquals(CommonErrorCode.DEADLINE_EXCEEDED.code(),
                assertThrows(BaseException.class, () -> service.requireAuthorized(request, CLOCK)).error().code());
        assertEquals(0, calls.get());
    }

    @Test
    public void executionDeadlineIsCheckedAfterSlowPolicyEvaluation() {
        MutableClock clock = new MutableClock();
        ExecutionContext context = new ExecutionContext(context().scope(), "trace-1", null, NOW.plusSeconds(1),
                null, Set.of(), null, null, null);
        AuthorizationRequest request = AuthorizationRequest.of(context, read().resource(), CommonResourceAction.READ);
        AuthorizationService service = input -> {
            clock.now = NOW.plusSeconds(2);
            return new AuthorizationDecision(input, allow());
        };
        assertEquals(CommonErrorCode.DEADLINE_EXCEEDED.code(),
                assertThrows(BaseException.class, () -> service.requireAuthorized(request, clock)).error().code());
    }

    @Test
    public void serviceExecutionKeepsInitiatorAndExecutorSeparateAndBindsBoth() {
        AuthorizationRequest request = new AuthorizationRequest(context(),
                new PrincipalRef("worker-1", PrincipalType.SERVICE), read().resource(), CommonResourceAction.READ.code());
        AuthorizationService service = input -> {
            assertEquals("user-1", input.context().scope().principal().principalId());
            assertEquals("worker-1", input.executor().principalId());
            return new AuthorizationDecision(input, allow());
        };
        service.requireAuthorized(request, CLOCK);
        AuthorizationService differentExecutor = ignored -> new AuthorizationDecision(read(), allow());
        assertThrows(BaseException.class, () -> differentExecutor.requireAuthorized(request, CLOCK));
        assertThrows(IllegalArgumentException.class, () -> new AuthorizationRequest(context(),
                new PrincipalRef("user-2", PrincipalType.USER), read().resource(), CommonResourceAction.READ.code()));
    }
}
