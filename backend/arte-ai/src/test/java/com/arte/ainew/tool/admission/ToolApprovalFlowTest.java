package com.arte.ainew.tool.admission;

import com.arte.ainew.application.gateway.DefaultToolGateway;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.approval.ToolApprovalPolicy;
import com.arte.ainew.application.tool.approval.ToolApprovalService;
import com.arte.ainew.application.tool.execution.ToolDispatcher;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.*;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.config.NewAiToolApprovalProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.persistence.mybatis.MybatisToolApprovalStore;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.spi.auth.ToolApprovalAccess;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.ToolManagementExceptionHandler;
import com.arte.ainew.web.controller.NewAiToolApprovalController;
import com.arte.ainew.web.controller.NewAiToolSubmissionController;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 真实同库受理／审批／网关／完成及 MVC 白名单；独立审批使用明确受信测试宿主，不代表正式 RBAC。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:05 ✾
 */
public class ToolApprovalFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final class Rig implements AutoCloseable {
        final ToolAdmissionFixture f;
        final MybatisToolApprovalStore approvals;
        final ToolAdmissionService admissions;
        final ToolApprovalService service;
        final NewAiToolApprovalProperties properties;
        final AtomicInteger guards = new AtomicInteger();
        final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        final MockMvc mvc;
        boolean permitted = true;

        Rig(boolean independent) throws Exception {
            var policy = new ToolExecutionPolicy(Duration.ofMinutes(independent ? 16 : 2), 1, Duration.ZERO, 100_000, 4096,
                    independent ? ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL : ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION);
            var limits = new ToolExecutionPolicy(policy.timeout(), 3, Duration.ZERO, 100_000, 4096, ToolExecutionPolicy.ApprovalMode.NONE);
            f = new ToolAdmissionFixture(policy, new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY, false, ToolRiskProfile.Idempotency.NONE, Set.of("resource:read"), Set.of()), limits);
            approvals = new MybatisToolApprovalStore(f.source, f.codec, f.io, f.executions, f.bindings);
            properties = new NewAiToolApprovalProperties(true, null, 0, independent ? List.of(new NewAiToolApprovalProperties.Approvers("tenant", "workspace", Set.of("reviewer"))) : List.of());
            ToolApprovalAccess access = new ToolApprovalAccess() {
                public Mono<PlanPermit> authorizePlan(ToolExecutionSnapshot s, ToolApproval.Plan p, ExecutionContext c) {
                    return Mono.fromCallable(() -> {
                        check();
                        return (PlanPermit) (actual, plan) -> {
                            tx();
                            check();
                        };
                    });
                }

                public Mono<Permit> authorize(ToolApproval a, ToolExecutionSnapshot s, ExecutionContext c, boolean deciding) {
                    return Mono.fromCallable(() -> {
                        check();
                        String subject = c.authorization().principal().subjectId();
                        if (!c.authorization().tenantId().equals(a.owner().tenantId()) || !c.authorization().workspaceId().equals(a.owner().workspaceId()))
                            throw new AccessDeniedException("scope");
                        boolean requester = a.owner().subjectId().equals(subject);
                        if (!requester && !a.plan().approverSubjectIds().contains(subject) || deciding && requester && independent)
                            throw new AccessDeniedException("actor");
                        return (Permit) (actual, fixed) -> {
                            tx();
                            check();
                        };
                    });
                }

                private void check() {
                    if (!permitted) throw new AccessDeniedException("revoked");
                }

                private void tx() {
                    assertTrue(TransactionSynchronizationManager.hasResource(f.source));
                    guards.incrementAndGet();
                }
            };
            admissions = new ToolAdmissionService(f.bindingService, f.catalog, f.store, f.access, new ToolSchemaValidator(), limits, f.metadata, f.clock,
                    new ToolApprovalPolicy(properties, access, approvals));
            service = new ToolApprovalService(approvals, f.store, f.executions, access);
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("admissions", admissions);
            beans.registerSingleton("approvals", service);
            var props = new NewAiProperties(false, "appDataSource", "release", null,
                    new NewAiProperties.Limits(65536, 4096, 100_000, Duration.ofMinutes(16), Duration.ofDays(1)), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            var factory = new com.arte.ainew.context.ExecutionContextFactory((name, t, w, scopes) -> Mono.just(new ExecutionAuthorization(
                    new ExecutionPrincipal(name.equals("reviewer") ? "reviewer" : "subject", name, ExecutionPrincipal.Kind.USER), t, w,
                    scopes, "test-current")), f.clock);
            validator.afterPropertiesSet();
            var contexts = new NewAiHttpContext(factory, props);
            mvc = standaloneSetup(new NewAiToolSubmissionController(beans.getBeanProvider(ToolAdmissionService.class), contexts, props),
                    new NewAiToolApprovalController(beans.getBeanProvider(ToolApprovalService.class), contexts, props))
                    .setControllerAdvice(new ToolManagementExceptionHandler()).setValidator(validator).build();
        }

        ExecutionContext context(String id, String key) {
            var old = f.context(id, key);
            return new ExecutionContext(id, old.traceId(), old.authorization(), Instant.now().plusSeconds(960), null, null, old.releaseRef(), key);
        }

        ExecutionContext reviewer() {
            var old = context("review", "key");
            return new ExecutionContext(old.executionId(), old.traceId(), new ExecutionAuthorization(new ExecutionPrincipal("reviewer", "reviewer", ExecutionPrincipal.Kind.USER),
                    "tenant", "workspace", Set.of("ai:read"), "review-current"), old.deadline(), null, null, null, "key");
        }

        String submit(String key) {
            var preview = admissions.previewPlan(f.input(), context("preview", key)).block(WAIT);
            return admissions.submit(f.input(), ToolApprovalPolicy.previewDigest(preview.snapshot(), preview.approval()), context(UUID.randomUUID().toString(), key)).block(WAIT).executionId();
        }

        ToolApprovalService.Detail detail(String id) {
            return service.get(null, id, context("read", "read")).block(WAIT);
        }

        Map<String, String> scope() {
            return Map.of("tenantId", "tenant", "workspaceId", "workspace");
        }

        MvcResult post(String name, Map<String, ?> body, String key, String user) throws Exception {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
            try {
                var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ai-new/tool/" + name).contentType("application/json").content(JSON.writeValueAsString(body));
                if (key != null) builder.header("Idempotency-Key", key);
                var result = mvc.perform(builder).andReturn();
                if (result.getRequest().isAsyncStarted()) {
                    result.getAsyncResult(15000);
                    result = mvc.perform(asyncDispatch(result)).andReturn();
                }
                return result;
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        JsonNode data(MvcResult r, int status) throws Exception {
            assertEquals(r.getResponse().getContentAsString(), status, r.getResponse().getStatus());
            return JSON.readTree(r.getResponse().getContentAsString()).get("data");
        }

        @Override
        public void close() throws Exception {
            validator.close();
            f.close();
        }
    }

    @Test
    public void requesterPreviewSubmitApproveAndRealGatewayCompleteOneAttempt() throws Exception {
        try (var r = new Rig(false)) {
            var preview = r.admissions.previewPlan(r.f.input(), r.context("preview", "k")).block(WAIT);
            assertEquals(ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION, preview.approval().mode());
            assertTrue(Duration.between(preview.snapshot().receivedAt(), preview.approval().expiresAt()).compareTo(Duration.ofSeconds(90)) <= 0);
            String id = r.submit("one");
            var detail = r.detail(id);
            assertEquals(Invocation.State.WAITING_APPROVAL, detail.invocation().state());
            assertTrue(detail.mayDecide());
            assertTrue(r.f.executions.claim(OutboxMessage.Kind.DISPATCH, "worker", Duration.ofSeconds(10), 1).block(WAIT).isEmpty());
            r.service.decide(detail.approval().approvalId(), 0, detail.approval().subjectDigest(), ToolApprovalCommand.Decision.APPROVE, "approve", r.context("decision", "approve")).block(WAIT);
            var calls = new AtomicInteger();
            var adapter = new com.arte.ainew.spi.adapter.BusinessToolAdapter() {
                public CapabilityDescriptor capability() {
                    return r.f.v1.revision().tool().discoveredCapability();
                }

                public Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call) {
                    calls.incrementAndGet();
                    return Mono.just(new ToolResult(call.request().input().callId(), ToolResult.Outcome.SUCCEEDED,
                            new com.arte.ainew.common.value.StructuredValue.ObjectValue(Map.of()), List.of(), List.of(), ExecutionError.SideEffect.NONE, null));
                }
            };
            var registry = new com.arte.ainew.application.tool.discovery.LocalToolRegistry(List.of(new com.arte.ainew.application.tool.discovery.LocalToolRegistry.Registration(
                    r.f.v1.provider(), () -> Mono.just(r.f.v1.definitions()), Map.of(r.f.v1.adapterRef(), adapter))));
            var gateway = new DefaultToolGateway(r.f.bindingService, r.f.catalog, registry, call -> Mono.just(call.call().runtime().execution().authorization()),
                    new ToolSchemaValidator(), r.f.codec, r.f.metadata, r.f.clock, r.approvals::requireApproved);
            var payloads = new MybatisPayloadPersistence(r.f.source, r.f.codec, r.f.io);
            var dispatcher = new ToolDispatcher(r.f.executions, r.f.executions, r.f.executions, r.f.store, r.f.bindingService, r.f.access, gateway, payloads, r.f.codec, r.f.metadata, r.f.clock, Duration.ofSeconds(10));
            var message = r.f.executions.claim(OutboxMessage.Kind.DISPATCH, "worker", Duration.ofSeconds(10), 1).block(WAIT).getFirst();
            var invocation = r.f.executions.find(r.f.owner, id).block(WAIT);
            dispatcher.dispatch(message, ExecutionRuntimeContext.start(invocation.request().context())).block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, r.f.executions.find(r.f.owner, id).block(WAIT).state());
            assertEquals(1, calls.get());
            assertEquals(1, r.f.count("arte_ai_attempt"));
            assertEquals(0, r.f.count("arte_ai_reservation"));
            assertTrue(r.guards.get() >= 2);
        }
    }

    @Test
    public void independentInboxIsScopedAndOnlyOtherConfiguredActorCanApprove() throws Exception {
        try (var r = new Rig(true)) {
            var id = r.submit("one");
            var original = r.detail(id);
            assertFalse(original.mayDecide());
            var page = r.service.list(null, 10, r.reviewer()).block(WAIT);
            assertEquals(1, page.records().size());
            assertTrue(page.records().getFirst().mayDecide());
            assertTrue(r.approvals.inbox(new ExecutionOwner("foreign", "workspace", "reviewer"), null, 10).block(WAIT).records().isEmpty());
            assertNull(r.approvals.visible(new ExecutionOwner("tenant", "workspace", "stranger"), original.approval().approvalId(), null).block(WAIT));
            assertThrows(AccessDeniedException.class, () -> r.service.decide(original.approval().approvalId(), 0, original.approval().subjectDigest(), ToolApprovalCommand.Decision.APPROVE, "self", r.context("self", "self")).block(WAIT));
            var result = r.service.decide(original.approval().approvalId(), 0, original.approval().subjectDigest(), ToolApprovalCommand.Decision.APPROVE, "other", r.reviewer()).block(WAIT);
            assertEquals("reviewer", result.approval().decidedBy());
            assertEquals(r.f.owner, ExecutionOwner.from(result.invocation().request().context()));
            assertTrue(r.service.list(null, 10, r.reviewer()).block(WAIT).records().isEmpty());
            assertNotNull(r.approvals.visible(new ExecutionOwner("tenant", "workspace", "reviewer"), original.approval().approvalId(), null).block(WAIT));
        }
    }

    @Test
    public void inboxPagesPendingActorsAndRetainsClosedHistory() throws Exception {
        try (var r = new Rig(true)) {
            r.submit("page-one");
            r.submit("page-two");
            var first = r.service.list(null, 1, r.reviewer()).block(WAIT);
            assertEquals(1, first.records().size());
            assertNotNull(first.nextCursor());
            var second = r.service.list(first.nextCursor(), 1, r.reviewer()).block(WAIT);
            assertEquals(1, second.records().size());
            assertNotEquals(first.records().getFirst().approval().approvalId(), second.records().getFirst().approval().approvalId());
            var selected = first.records().getFirst().approval();
            r.service.decide(selected.approvalId(), 0, selected.subjectDigest(), ToolApprovalCommand.Decision.REJECT, "reject-page", r.reviewer()).block(WAIT);
            assertEquals(1, r.service.list(null, 10, r.reviewer()).block(WAIT).records().size());
            assertEquals(ToolApproval.State.REJECTED, r.service.get(selected.approvalId(), null, r.reviewer()).block(WAIT).approval().state());
        }
    }

    @Test
    public void concurrentSameKeyAdmissionKeepsOriginalWindowAndOneApproval() throws Exception {
        try (var r = new Rig(false); var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> {
                start.await();
                return r.submit("same");
            });
            var b = pool.submit(() -> {
                start.await();
                return r.submit("same");
            });
            start.countDown();
            assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(1, r.f.count("arte_ai_invocation"));
            assertEquals(1, r.f.count("arte_ai_tool_approval"));
            assertEquals(1, r.f.count("arte_ai_tool_approval_audience"));
        }
    }

    @Test
    public void publicReadAndDecisionUseWhitelistAndNeverExposeExecutionContext() throws Exception {
        try (var r = new Rig(false)) {
            String id = r.submit("one");
            var detail = r.data(r.post("getApproval", Map.of("scope", r.scope(), "invocationId", id), null, "alice"), 200);
            assertTrue(detail.get("mayDecide").asBoolean());
            assertFalse(detail.has("snapshot"));
            assertFalse(detail.has("authorization"));
            assertFalse(detail.has("owner"));
            var command = Map.of("scope", r.scope(), "approvalId", detail.get("approvalId").asString(), "expectedVersion", "0", "subjectDigest", detail.get("subjectDigest").asString(), "decision", "REJECT");
            var first = r.data(r.post("decideApproval", command, "same", "alice"), 202);
            assertEquals("REJECTED", first.get("state").asString());
            assertEquals("FAILED", first.get("invocationState").asString());
            assertEquals(first, r.data(r.post("decideApproval", command, "same", "alice"), 202));
            var forged = new HashMap<String, Object>(command);
            forged.put("owner", Map.of("subjectId", "other"));
            r.data(r.post("decideApproval", forged, "forged", "alice"), 400);
            r.data(r.post("decideApproval", command, null, "alice"), 400);
            r.data(r.post("getApproval", Map.of("scope", r.scope(), "invocationId", id, "approvalId", "both"), null, "alice"), 400);
        }
    }

    @Test
    public void currentRevocationBlocksReadAndIdempotentDecisionReplay() throws Exception {
        try (var r = new Rig(false)) {
            String id = r.submit("one");
            var d = r.detail(id);
            r.service.decide(d.approval().approvalId(), 0, d.approval().subjectDigest(), ToolApprovalCommand.Decision.APPROVE, "yes", r.context("d", "d")).block(WAIT);
            r.permitted = false;
            assertThrows(AccessDeniedException.class, () -> r.detail(id));
            assertThrows(AccessDeniedException.class, () -> r.service.decide(d.approval().approvalId(), 0, d.approval().subjectDigest(), ToolApprovalCommand.Decision.APPROVE, "yes", r.context("d2", "d2")).block(WAIT));
        }
    }

    @Test
    public void independentWindowIsNotSilentlyShortenedAndMissingApproversFailClosed() throws Exception {
        try (var r = new Rig(true)) {
            var tooShort = r.f.context("short", "short");
            assertThrows(com.arte.ainew.application.support.AdmissionException.class, () -> r.admissions.preview(r.f.input(), tooShort).block(WAIT));
            var noNames = new ToolApprovalPolicy(new NewAiToolApprovalProperties(true, null, 0), null, r.approvals);
            assertThrows(com.arte.ainew.application.support.AdmissionException.class, () -> noNames.prepare(
                    r.admissions.preview(r.f.input(), r.context("ok", "ok")).block(WAIT), r.context("context", "context"), false).block(WAIT));
        }
    }
}
