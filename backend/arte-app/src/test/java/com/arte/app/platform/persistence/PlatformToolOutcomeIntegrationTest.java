package com.arte.app.platform.persistence;

import com.arte.ainew.application.tool.outcome.ToolOutcomeQueryRegistry;
import com.arte.ainew.application.tool.outcome.ToolOutcomeWorker;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.persistence.mybatis.MybatisToolOutcomeStore;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.tool.ToolOutcomeCheck;
import com.arte.ainew.pojo.tool.ToolOutcomeEvidence;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 正式写工具与原领域回执、UNKNOWN 核对事务及多 Worker fencing；只操作隔离测试库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
class PlatformToolOutcomeIntegrationTest extends ToolWriteAcceptanceRig {

    private record Setup(MockMvc mvc, Pending pending, MybatisToolOutcomeStore store) {
    }

    private Setup unknown(ToolAcceptanceRig r, ApplicationContext c) throws Exception {
        var mvc = r.mvc(c);
        var pending = submit(r, mvc, publish(r, c, mvc), "0", "Recovered workspace");
        approve(r, mvc, pending);
        run(c);
        assertEquals("UNKNOWN", status(r, mvc, pending.id()).get("state").asString());
        assertEquals(1, count(r, "arte_ai_tool_outcome_check"));
        return new Setup(mvc, pending, c.getBean(MybatisToolOutcomeStore.class));
    }

    private int count(ToolAcceptanceRig r, String table) {
        return r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void due(ToolAcceptanceRig r, ApplicationContext c) {
        r.base.f.jdbc.update("UPDATE arte_ai_tool_outcome_check SET next_check=0");
        expireAttempt(r, c);
    }

    private void expireAttempt(ToolAcceptanceRig r, ApplicationContext c) {
        var codec = c.getBean(ExecutionRecordCodec.class);
        var a = codec.decode(r.base.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_attempt", String.class), Attempt.class);
        var expired = new Attempt(a.attemptId(), a.invocationId(), a.attemptNumber(), a.workerId(), a.fencingToken(), a.createdAt().plusMillis(1), a.version(), a.state(), a.dispatch(), a.remoteRequestId(), a.budgetReservationId(), a.usage(), a.error(), a.createdAt(), a.updatedAt());
        r.base.f.jdbc.update("UPDATE arte_ai_attempt SET workspace_snapshot=?", codec.encode(expired));
    }

    private ToolOutcomeCheck.Case work(ToolAcceptanceRig r, Setup s) {
        var owner = new ExecutionOwner(r.scope.tenantId(), r.scope.workspaceId(), r.base.f.actor.principal().subjectId());
        return s.store.find(owner, s.pending.id()).block(WAIT);
    }

    private int poll(ApplicationContext c) {
        return c.getBean(ToolOutcomeWorker.class).pollOnce().block(WAIT);
    }

    @Test
    void committedWriteLostResultIsRecoveredFromOriginalReceiptExactlyOnce() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var s = unknown(r, c);
                var before = work(r, s);
                assertEquals(ToolOutcomeCheck.State.CHECKING, before.state());
                assertEquals(0, before.checks());
                assertEquals(5000, before.nextCheck() - before.createdAt());
                assertEquals(1800000, before.validUntil() - before.createdAt());
                assertEquals(0, poll(c)); // 持久化的首轮五秒延迟，浏览器刷新或 Worker 轮询不能提前。
                due(r, c);
                assertEquals(1, poll(c));
                assertEquals("SUCCEEDED", status(r, s.mvc, s.pending.id()).get("state").asString());
                assertEquals(ToolOutcomeCheck.State.RESOLVED, work(r, s).state());
                assertEquals(1, work(r, s).checks());
                assertEquals(1, version(r));
                assertEquals(1, receipts(r));
                assertEquals(1, count(r, "arte_ai_attempt"));
                assertEquals(1, count(r, "arte_ai_tool_outcome_evidence"));
                assertEquals(0, count(r, "arte_ai_scheduling_permit"));
                var result = r.post(s.mvc, "invocation/getInvocationResult", Map.of("scope", r.scope, "invocationId", s.pending.id()), null, 200);
                assertEquals("CONFIRMED", result.get("result").get("value").get("sideEffect").asString());
                assertEquals("Recovered workspace", r.json.readTree(result.get("outputJson").asString()).get("name").asString());
                assertEquals(0, poll(c));
                run(c);
                assertEquals(1, version(r));
                assertEquals(1, receipts(r));
                assertEquals(1, count(r, "arte_ai_tool_outcome_evidence"));
            });
        }
    }

    @Test
    void missingReceiptStaysUnknownAndSixChecksEndInNeedsReviewWithoutRedispatch() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.NO_RECEIPT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var s = unknown(r, c);
                long originalWindow = work(r, s).validUntil();
                for (int n = 1; n <= 6; n++) {
                    due(r, c);
                    assertEquals(1, poll(c));
                    var job = work(r, s);
                    assertEquals(n, job.checks());
                    assertEquals(originalWindow, job.validUntil());
                    assertEquals(n == 6 ? ToolOutcomeCheck.State.NEEDS_REVIEW : ToolOutcomeCheck.State.CHECKING, job.state());
                    assertEquals("UNKNOWN", status(r, s.mvc, s.pending.id()).get("state").asString());
                    assertEquals(0, version(r));
                    assertEquals(0, receipts(r));
                    assertEquals(1, count(r, "arte_ai_attempt"));
                }
                due(r, c);
                assertEquals(0, poll(c));
                run(c);
                assertEquals(6, work(r, s).checks());
                assertEquals(0, count(r, "arte_ai_tool_outcome_evidence"));
            });
        }
    }

    @Test
    void onlyOneWorkerClaimsAndForeignEvidenceCannotCompleteOriginalInvocation() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var s = unknown(r, c);
                due(r, c);
                var both = Mono.zip(s.store.claim("outcome-A", 1), s.store.claim("outcome-B", 1)).block(WAIT);
                assertEquals(1, both.getT1().size() + both.getT2().size());
                var claim = both.getT1().isEmpty() ? both.getT2().getFirst() : both.getT1().getFirst();
                assertEquals(1, claim.work().checks());
                var evidence = c.getBean(ToolOutcomeQueryRegistry.class).find(claim.snapshot()).orElseThrow().query(claim).block(WAIT);
                var forged = new ToolOutcomeEvidence(new ExecutionOwner(claim.owner().tenantId(), claim.owner().workspaceId(), "foreign-subject"), evidence.invocationId(), evidence.attemptId(), evidence.operationKey(), evidence.decision(), evidence.reference(), evidence.digest(), evidence.result());
                assertThrows(RuntimeException.class, () -> s.store.finish(claim, forged, "CONFIRMED_SUCCEEDED", false).block(WAIT));
                assertEquals("UNKNOWN", status(r, s.mvc, s.pending.id()).get("state").asString());
                assertEquals(0, count(r, "arte_ai_tool_outcome_evidence"));
                assertTrue(s.store.finish(claim, evidence, "CONFIRMED_SUCCEEDED", false).block(WAIT));
                assertThrows(RuntimeException.class, () -> s.store.finish(claim, evidence, "CONFIRMED_SUCCEEDED", false).block(WAIT));
                assertEquals(1, count(r, "arte_ai_tool_outcome_evidence"));
                assertEquals(1, version(r));
            });
        }
    }

    @Test
    void abandonedQueryKeepsCountAndDeadlineAndRejectsOldWorker() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var s = unknown(r, c);
                due(r, c);
                var old = s.store.claim("outcome-old", 1).block(WAIT).getFirst();
                r.base.f.jdbc.update("UPDATE arte_ai_tool_outcome_check SET lease_until=0,query_deadline=1");
                expireAttempt(r, c);
                var replacement = s.store.claim("outcome-new", 1).block(WAIT).getFirst();
                assertEquals(old.work().checks(), replacement.work().checks());
                assertEquals(1, replacement.work().queryDeadline());
                assertTrue(replacement.attempt().fencingToken() > old.attempt().fencingToken());
                assertThrows(RuntimeException.class, () -> s.store.finish(old, ToolOutcomeEvidence.unknown(old), "STILL_UNKNOWN", false).block(WAIT));
                assertThrows(RuntimeException.class, () -> c.getBean(ToolOutcomeQueryRegistry.class).find(replacement.snapshot()).orElseThrow().query(replacement).block(WAIT));
                assertTrue(s.store.finish(replacement, ToolOutcomeEvidence.unknown(replacement), "OUTCOME_QUERY_INTERRUPTED", false).block(WAIT));
                assertEquals(1, work(r, s).checks());
                due(r, c);
                assertEquals(1, poll(c));
                assertEquals(2, work(r, s).checks());
                assertEquals("SUCCEEDED", status(r, s.mvc, s.pending.id()).get("state").asString());
                assertEquals(1, version(r));
                assertEquals(1, receipts(r));
            });
        }
    }

    @Test
    void revokedInvokeAndDisabledAiDoNotBlockRestrictedReceiptCleanup() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var s = unknown(r, c);
                r.base.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE target='aiTool:invoke'");
                r.setAiEnabled(false);
                due(r, c);
                assertEquals(1, poll(c));
                assertEquals(ToolOutcomeCheck.State.RESOLVED, work(r, s).state());
                assertEquals(1, version(r));
                assertEquals(1, receipts(r));
                r.post(s.mvc, "tool/invokeTool", s.pending.command(), s.pending.key(), 403);
                r.post(s.mvc, "invocation/getInvocationResult", Map.of("scope", r.scope, "invocationId", s.pending.id()), null, 200);
                r.base.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE target='aiTool:list'");
                r.post(s.mvc, "invocation/getInvocationResult", Map.of("scope", r.scope, "invocationId", s.pending.id()), null, 404);
            });
        }
    }

    @Test
    void elapsedWindowStopsWithoutQueryAndHistoricalScanDoesNotRenewIt() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var s = unknown(r, c);
                long first = work(r, s).createdAt();
                r.base.f.jdbc.update("DELETE FROM arte_ai_tool_outcome_check");
                assertEquals(1, s.store.recoverUnknownBatch().block(WAIT));
                assertEquals(first, work(r, s).createdAt());
                assertEquals(0, s.store.recoverUnknownBatch().block(WAIT));
                long past = Instant.now().minusSeconds(1805).toEpochMilli();
                r.base.f.jdbc.update("UPDATE arte_ai_tool_outcome_check SET created_at=?,valid_until=?,next_check=0", past, past + 1800000);
                assertEquals(0, poll(c));
                assertEquals(ToolOutcomeCheck.State.NEEDS_REVIEW, work(r, s).state());
                assertEquals(0, work(r, s).checks());
                assertEquals("UNKNOWN", status(r, s.mvc, s.pending.id()).get("state").asString());
                assertEquals(0, count(r, "arte_ai_tool_outcome_evidence"));
                assertEquals(1, version(r));
            });
        }
    }

    @Test
    void evidenceWriteFailureRollsBackResultTerminalAndEventsThenSameClaimCanCommit() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var s = unknown(r, c);
                due(r, c);
                var claim = s.store.claim("atomic-outcome", 1).block(WAIT).getFirst();
                var evidence = c.getBean(ToolOutcomeQueryRegistry.class).find(claim.snapshot()).orElseThrow().query(claim).block(WAIT);
                int results = count(r, "arte_ai_result"), events = count(r, "arte_ai_event");
                r.base.f.jdbc.execute("ALTER TABLE arte_ai_tool_outcome_evidence ADD CONSTRAINT reject_test CHECK(decision='NEVER')");
                assertThrows(RuntimeException.class, () -> s.store.finish(claim, evidence, "CONFIRMED_SUCCEEDED", false).block(WAIT));
                assertEquals(results, count(r, "arte_ai_result"));
                assertEquals(events, count(r, "arte_ai_event"));
                assertEquals(0, count(r, "arte_ai_tool_outcome_evidence"));
                assertEquals(ToolOutcomeCheck.State.CHECKING, work(r, s).state());
                assertEquals("UNKNOWN", status(r, s.mvc, s.pending.id()).get("state").asString());
                r.base.f.jdbc.execute("ALTER TABLE arte_ai_tool_outcome_evidence DROP CONSTRAINT reject_test");
                assertTrue(s.store.finish(claim, evidence, "CONFIRMED_SUCCEEDED", false).block(WAIT));
                assertEquals(results + 1, count(r, "arte_ai_result"));
                assertEquals(events + 1, count(r, "arte_ai_event"));
                assertEquals("SUCCEEDED", status(r, s.mvc, s.pending.id()).get("state").asString());
            });
        }
    }

    @Test
    void trustedFailureAndNotExecutedEvidencePreserveTheirDistinctFacts() throws Exception {
        for (var decision : List.of(ToolOutcomeEvidence.Decision.CONFIRMED_FAILED, ToolOutcomeEvidence.Decision.PROVEN_NOT_EXECUTED)) {
            try (var r = new ToolAcceptanceRig()) {
                context(r, Fault.NO_RECEIPT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                    var s = unknown(r, c);
                    due(r, c);
                    var claim = s.store.claim("trusted-failure", 1).block(WAIT).getFirst();
                    var side = decision == ToolOutcomeEvidence.Decision.CONFIRMED_FAILED ? com.arte.ainew.common.execution.ExecutionError.SideEffect.CONFIRMED : com.arte.ainew.common.execution.ExecutionError.SideEffect.NONE;
                    var error = new com.arte.ainew.common.execution.ExecutionError("VERIFIED_DOMAIN_FAILURE", com.arte.ainew.common.execution.ExecutionError.Phase.INVOCATION, false, side, com.arte.ainew.common.execution.ExecutionError.Certainty.KNOWN, "verified-test");
                    var result = new com.arte.ainew.pojo.tool.ToolResult(claim.snapshot().request().input().callId(), com.arte.ainew.pojo.tool.ToolResult.Outcome.FAILED, null, List.of(), List.of(), side, error);
                    var evidence = new ToolOutcomeEvidence(claim.owner(), claim.work().invocationId(), claim.work().attemptId(), claim.work().operationKey(), decision, "trusted-test-receipt", com.arte.ainew.serialization.CanonicalJson.sha256("verified"), result);
                    assertTrue(s.store.finish(claim, evidence, decision.name(), false).block(WAIT));
                    var state = status(r, s.mvc, s.pending.id());
                    assertEquals("FAILED", state.get("state").asString());
                    assertEquals(side.name(), state.get("error").get("sideEffect").asString());
                    assertEquals(1, count(r, "arte_ai_attempt"));
                    assertEquals(1, count(r, "arte_ai_tool_outcome_evidence"));
                    assertEquals(0, receipts(r));
                });
            }
        }
    }

    @Test
    void corruptDomainReceiptRequiresReviewInsteadOfClaimingSuccess() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var s = unknown(r, c);
                r.base.f.jdbc.update("UPDATE arte_platform_command SET request_digest=? WHERE command_type='workspace:rename'", "0".repeat(64));
                due(r, c);
                assertEquals(1, poll(c));
                assertEquals(ToolOutcomeCheck.State.NEEDS_REVIEW, work(r, s).state());
                assertEquals("OUTCOME_EVIDENCE_REVIEW", work(r, s).lastCode());
                assertEquals("UNKNOWN", status(r, s.mvc, s.pending.id()).get("state").asString());
                assertEquals(0, count(r, "arte_ai_tool_outcome_evidence"));
                assertEquals(1, version(r));
            });
        }
    }

    @Test
    void provenNotExecutedAfterCancelCanConvergeToCancelled() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            var active = new java.util.concurrent.atomic.AtomicReference<ApplicationContext>();
            context(r, Fault.NONE).withPropertyValues("arte.ai-new-tool-outcome.enabled=true")
                    .withBean("cancelDuringDispatch", org.springframework.beans.factory.config.BeanPostProcessor.class,
                            () -> new org.springframework.beans.factory.config.BeanPostProcessor() {
                                public Object postProcessAfterInitialization(Object bean, String name) {
                                    if (bean instanceof com.arte.ainew.spi.gateway.ToolGateway)
                                        return (com.arte.ainew.spi.gateway.ToolGateway) call -> active.get()
                                                .getBean(com.arte.ainew.spi.persistence.ExecutionStore.class)
                                                .requestControl(ExecutionOwner.from(call.request().context()),
                                                        new com.arte.ainew.pojo.execution.ExecutionControlRequest("cancel-proof",
                                                                call.request().context().executionId(), "cancel-before-unknown",
                                                                com.arte.ainew.pojo.execution.ControlReceipt.Command.CANCEL))
                                                .then(Mono.error(new IllegalStateException("Injected uncertainty after cancel intent")));
                                    return bean;
                                }
                            }).run(c -> {
                        active.set(c);
                        var s = unknown(r, c);
                        due(r, c);
                        var claim = s.store.claim("cancel-proof", 1).block(WAIT).getFirst();
                        assertTrue(c.getBean(com.arte.ainew.spi.persistence.ExecutionStore.class)
                                .cancellationRequested(claim.owner(), s.pending.id()).block(WAIT));
                        var error = new com.arte.ainew.common.execution.ExecutionError("VERIFIED_NOT_EXECUTED",
                                com.arte.ainew.common.execution.ExecutionError.Phase.INVOCATION, false,
                                com.arte.ainew.common.execution.ExecutionError.SideEffect.NONE,
                                com.arte.ainew.common.execution.ExecutionError.Certainty.KNOWN, "verified-test");
                        var result = new com.arte.ainew.pojo.tool.ToolResult(claim.snapshot().request().input().callId(),
                                com.arte.ainew.pojo.tool.ToolResult.Outcome.FAILED, null, List.of(), List.of(), error.sideEffect(), error);
                        var evidence = new ToolOutcomeEvidence(claim.owner(), claim.work().invocationId(), claim.work().attemptId(),
                                claim.work().operationKey(), ToolOutcomeEvidence.Decision.PROVEN_NOT_EXECUTED, "trusted-not-executed",
                                com.arte.ainew.serialization.CanonicalJson.sha256("not-executed"), result);
                        assertTrue(s.store.finish(claim, evidence, "PROVEN_NOT_EXECUTED", false).block(WAIT));
                        assertEquals("CANCELLED", status(r, s.mvc, s.pending.id()).get("state").asString());
                        assertEquals(0, version(r));
                        assertEquals(0, receipts(r));
                    });
        }
    }

    @Test
    void defaultOffDoesNotInstallWorkerOrRequireOutcomeTables() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            r.base.f.jdbc.execute("DROP TABLE arte_ai_tool_outcome_review_command");
            r.base.f.jdbc.execute("DROP TABLE arte_ai_tool_outcome_review");
            r.base.f.jdbc.execute("DROP TABLE arte_ai_tool_outcome_evidence");
            r.base.f.jdbc.execute("DROP TABLE arte_ai_tool_outcome_check");
            r.base.f.jdbc.execute("DROP TABLE arte_ai_tool_outcome_scan");
            context(r, Fault.LOSE_RESULT).run(c -> {
                assertNull(c.getStartupFailure());
                assertTrue(c.getBeansOfType(ToolOutcomeWorker.class).isEmpty());
                var mvc = r.mvc(c);
                var p = submit(r, mvc, publish(r, c, mvc), "0", "Old behavior");
                approve(r, mvc, p);
                run(c);
                assertEquals("UNKNOWN", status(r, mvc, p.id()).get("state").asString());
                assertEquals(1, version(r));
            });
        }
    }
}
