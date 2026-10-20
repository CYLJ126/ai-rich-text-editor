package com.arte.app.platform.persistence;

import com.arte.ainew.persistence.mybatis.MybatisToolOutcomeStore;
import com.arte.app.platform.identity.RbacIdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实双主体 RBAC、空间治理、MVC、回执重验和同库提交；只使用隔离 H2。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 12:28 ✾
 */
class PlatformToolReviewIntegrationTest extends ToolWriteAcceptanceRig {

    record Case(MockMvc mvc, String id) {
    }

    private Case prepare(ToolAcceptanceRig r, ApplicationContext c) throws Exception {
        var mvc = r.mvc(c);
        var p = submit(r, mvc, publish(r, c, mvc), "0", "Manual confirmed workspace");
        approve(r, mvc, p);
        run(c);
        assertEquals("UNKNOWN", status(r, mvc, p.id()).get("state").asString());
        addReviewer(r, c);
        return new Case(mvc, p.id());
    }

    private void addReviewer(ToolAcceptanceRig r, ApplicationContext c) {
        r.base.f.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-10 10:00:00"));
        var bob = c.getBean(RbacIdentityProvider.class).resolveAuthenticatedName("bob").orElseThrow();
        for (String table : List.of("arte_platform_tenant_member", "arte_platform_workspace_member")) {
            var row = r.base.f.jdbc.queryForMap("SELECT * FROM " + table + " WHERE subject_id=?", r.base.f.actor.principal().subjectId());
            row.entrySet().forEach(e -> {
                if (e.getKey().equalsIgnoreCase("id")) e.setValue(UUID.randomUUID().toString());
                if (e.getKey().equalsIgnoreCase("subject_id")) e.setValue(bob.subjectId());
                if (e.getKey().equalsIgnoreCase("role")) e.setValue(1);
            });
            r.base.f.jdbc.update("INSERT INTO " + table + " (" + String.join(",", row.keySet()) + ") VALUES (" + String.join(",", Collections.nCopies(row.size(), "?")) + ")", row.values().toArray());
        }
        r.base.f.jdbc.update("INSERT INTO arte_rbac_relation(source,binding_type,target) VALUES('bob','user_to_operation','aiTool:reconcile')");
    }

    private JsonNode post(ToolAcceptanceRig r, Case v, String path, Map<String, ?> body, String key, int expected) throws Exception {
        var result = r.requestAs(v.mvc, "tool/" + path, r.json.writeValueAsString(body), key, 2L, "bob");
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return r.json.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode detail(ToolAcceptanceRig r, Case v) throws Exception {
        return post(r, v, "getReconciliation", Map.of("scope", r.scope, "invocationId", v.id, "managed", true), null, 200);
    }

    private Map<String, Object> command(ToolAcceptanceRig r, JsonNode d) {
        var result = new LinkedHashMap<String, Object>();
        result.put("scope", r.scope);
        for (String k : List.of("invocationId", "attemptId", "operationKey")) result.put(k, d.get(k).asString());
        result.put("expectedVersion", d.get("version").asString());
        result.put("expectedInvocationVersion", d.get("invocationVersion").asString());
        result.put("reason", "核验原操作的领域命令回执");
        result.put("nextStep", "保存核对证据并通知原发起者查看");
        return result;
    }

    @Test
    void independentReviewerClaimsVerifiesAndConfirmsWithIdempotentAudit() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var v = prepare(r, c);
                var d = detail(r, v);
                assertTrue(d.get("mayClaim").asBoolean());
                assertFalse(d.get("mayConfirm").asBoolean());
                var claim = command(r, d);
                post(r, v, "claimReconciliation", claim, "claim", 200);
                assertEquals(0, detail(r, v).get("checks").asInt());
                var evidence = post(r, v, "getOutcomeEvidence", Map.of("scope", r.scope, "invocationId", v.id), null, 200);
                assertEquals("CONFIRMED_SUCCEEDED", evidence.get("decision").asString());
                var confirm = command(r, detail(r, v));
                var proof = new HashMap<String, Object>();
                for (String k : List.of("decision", "reference", "digest", "sideEffect", "outputJson"))
                    proof.put(k, evidence.get(k).asString());
                proof.put("source", "DOMAIN_RECEIPT");
                proof.put("verificationNote", "已对照原主体、范围、参数、操作键与领域回执");
                confirm.put("evidence", proof);
                var receipt = post(r, v, "confirmOutcome", confirm, "confirm", 200);
                assertEquals("SUCCEEDED", receipt.get("invocationState").asString());
                assertEquals(receipt, post(r, v, "confirmOutcome", confirm, "confirm", 200));
                assertEquals(1, version(r));
                assertEquals(1, receipts(r));
                assertEquals(1, r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_outcome_evidence", Integer.class));
                assertEquals(2, detail(r, v).get("history").size());
                assertEquals("RESOLVED", detail(r, v).get("state").asString());
                confirm.put("reason", "same key changed meaning");
                post(r, v, "confirmOutcome", confirm, "confirm", 409);
                r.base.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE source='bob'");
                post(r, v, "getReconciliation", Map.of("scope", r.scope, "invocationId", v.id, "managed", true), null, 403);
            });
        }
    }

    @Test
    void missingOrForgedReceiptCannotBecomeSuccessAndUnresolvableKeepsUnknown() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.NO_RECEIPT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var v = prepare(r, c);
                post(r, v, "claimReconciliation", command(r, detail(r, v)), "claim", 200);
                var proposal = command(r, detail(r, v));
                proposal.put("evidence", Map.of("source", "DOMAIN_RECEIPT", "decision", "CONFIRMED_SUCCEEDED", "reference", "fake", "digest", "a".repeat(64), "sideEffect", "CONFIRMED", "outputJson", "{}", "verificationNote", "不能凭主观断言证明成功"));
                post(r, v, "confirmOutcome", proposal, "forged", 400);
                assertEquals("UNKNOWN", status(r, v.mvc, v.id).get("state").asString());
                assertEquals(0, receipts(r));
                post(r, v, "markOutcomeUnresolvable", command(r, detail(r, v)), "unresolvable", 200);
                assertEquals("UNRESOLVABLE", detail(r, v).get("state").asString());
                assertEquals("UNKNOWN", status(r, v.mvc, v.id).get("state").asString());
                assertEquals(0, detail(r, v).get("checks").asInt());
                assertEquals(0, version(r));
                assertEquals(0, r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
            });
        }
    }

    @Test
    void requesterCannotSelfReviewAndCheckRequestsDoNotRenewAutomaticBudget() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var v = prepare(r, c);
                r.base.f.jdbc.update("INSERT INTO arte_rbac_relation(source,binding_type,target) VALUES('alice','user_to_operation','aiTool:reconcile')");
                var d = detail(r, v);
                var cmd = command(r, d);
                r.post(v.mvc, "tool/claimReconciliation", cmd, "self", 403);
                var before = r.base.f.jdbc.queryForMap("SELECT check_count,next_check,valid_until FROM arte_ai_tool_outcome_check");
                post(r, v, "requestOutcomeCheck", cmd, "request-check", 200);
                post(r, v, "requestOutcomeCheck", cmd, "request-check", 200);
                assertEquals(before, r.base.f.jdbc.queryForMap("SELECT check_count,next_check,valid_until FROM arte_ai_tool_outcome_check"));
                var read = r.post(v.mvc, "tool/getReconciliation", Map.of("scope", r.scope, "invocationId", v.id), null, 200);
                assertEquals(0, read.get("history").size());
                r.base.f.jdbc.update("UPDATE arte_ai_tool_outcome_check SET state='NEEDS_REVIEW'");
                assertEquals(1, c.getBean(MybatisToolOutcomeStore.class).alertReviews().block(WAIT)); // 后续改为 NEEDS_REVIEW 后告警。
            });
        }
    }

    private Map<String, Object> confirmation(ToolAcceptanceRig r, Case v) throws Exception {
        post(r, v, "claimReconciliation", command(r, detail(r, v)), "claim", 200);
        var evidence = post(r, v, "getOutcomeEvidence", Map.of("scope", r.scope, "invocationId", v.id), null, 200);
        var body = command(r, detail(r, v));
        var proof = new HashMap<String, Object>();
        for (String k : List.of("decision", "reference", "digest", "sideEffect", "outputJson"))
            proof.put(k, evidence.get(k).asString());
        proof.put("source", "DOMAIN_RECEIPT");
        proof.put("verificationNote", "已核验领域记录及固定原操作身份");
        body.put("evidence", proof);
        return body;
    }

    @Test
    void failedAuditInsertionRollsBackManualResultAndCanRetryOriginalCommand() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var v = prepare(r, c);
                var body = confirmation(r, v);
                var before = detail(r, v);
                int results = r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_result", Integer.class);
                r.base.f.jdbc.execute("ALTER TABLE arte_ai_tool_outcome_review_command ADD CONSTRAINT reject_review CHECK(action<>'CONFIRM')");
                post(r, v, "confirmOutcome", body, "atomic", 503);
                assertEquals("UNKNOWN", status(r, v.mvc, v.id).get("state").asString());
                assertEquals(before.get("version"), detail(r, v).get("version"));
                assertEquals(results, r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_result", Integer.class));
                assertEquals(0, r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_outcome_evidence", Integer.class));
                r.base.f.jdbc.execute("ALTER TABLE arte_ai_tool_outcome_review_command DROP CONSTRAINT reject_review");
                post(r, v, "confirmOutcome", body, "atomic", 200);
                assertEquals("SUCCEEDED", status(r, v.mvc, v.id).get("state").asString());
                assertEquals(1, receipts(r));
                assertEquals(1, version(r));
            });
        }
    }

    @Test
    void originalAutomaticLeaseBlocksManualTakeoverAndScopeReadsRemainPrivate() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var v = prepare(r, c);
                r.base.f.jdbc.update("UPDATE arte_ai_tool_outcome_check SET next_check=0");
                var store = c.getBean(MybatisToolOutcomeStore.class);
                var active = store.claim("other-node", 1).block(WAIT).getFirst();
                post(r, v, "claimReconciliation", command(r, detail(r, v)), "race", 409);
                assertTrue(store.finish(active, com.arte.ainew.pojo.tool.ToolOutcomeEvidence.unknown(active), "STILL_UNKNOWN", false).block(WAIT));
                r.base.f.jdbc.update("INSERT INTO arte_rbac_relation(source,binding_type,target) VALUES('bob','user_to_operation','aiTool:list')");
                post(r, v, "getReconciliation", Map.of("scope", r.scope, "invocationId", v.id, "managed", false), null, 404);
                assertEquals("UNKNOWN", status(r, v.mvc, v.id).get("state").asString());
                assertEquals(1, version(r));
            });
        }
    }

    @Test
    void manualEvidenceWorksAfterAutomaticWindowWithoutRenewingItAndRechecksDigest() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var v = prepare(r, c);
                long past = System.currentTimeMillis() - 2000000;
                r.base.f.jdbc.update("UPDATE arte_ai_tool_outcome_check SET state='NEEDS_REVIEW',created_at=?,valid_until=?,check_count=6", past, past + 1800000);
                var body = confirmation(r, v);
                var before = detail(r, v);
                var proof = (Map<String, Object>) body.get("evidence");
                var digest = proof.put("digest", "0".repeat(64));
                post(r, v, "confirmOutcome", body, "bad-digest", 400);
                assertEquals("UNKNOWN", status(r, v.mvc, v.id).get("state").asString());
                proof.put("digest", digest);
                post(r, v, "confirmOutcome", body, "valid-evidence", 200);
                assertEquals(before.get("autoExpiresAt"), detail(r, v).get("autoExpiresAt"));
                assertEquals(6, detail(r, v).get("checks").asInt());
                assertEquals("SUCCEEDED", status(r, v.mvc, v.id).get("state").asString());
                var repeat = command(r, detail(r, v));
                repeat.put("evidence", proof);
                assertEquals("ALREADY_RESOLVED", post(r, v, "confirmOutcome", repeat, "same-fact", 200).get("outcome").asString());
                assertEquals(1, r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_outcome_evidence", Integer.class));
            });
        }
    }

    @Test
    void roleRevocationAfterInitialPermitRejectsInTransactionAndAlertsAreThrottled() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.NO_RECEIPT).withPropertyValues("arte.ai-new-tool-outcome.enabled=true").run(c -> {
                var v = prepare(r, c);
                var d = detail(r, v);
                var created = r.base.f.jdbc.queryForObject("SELECT create_time FROM arte_rbac_user WHERE id=2", (rs, n) -> rs.getObject(1, java.time.LocalDateTime.class));
                var online = new com.arte.core.pojo.UserOnlineInfo().setId(2L).setUserName("bob").setAccountCreatedAt(created);
                var login = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(new com.arte.app.pojo.rbac.JwtUserDto(online), null, List.of());
                var permit = c.getBean(com.arte.ainew.spi.auth.ToolManagementAccess.class).capture(login).authorize(r.scope, com.arte.ainew.spi.auth.ToolManagementAccess.Operation.RECONCILE).block(WAIT);
                r.base.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE source='bob'");
                var command = new com.arte.ainew.pojo.tool.ToolReconciliation.Command(v.id, d.get("attemptId").asString(), d.get("operationKey").asString(), Long.parseLong(d.get("version").asString()), Long.parseLong(d.get("invocationVersion").asString()), com.arte.ainew.pojo.tool.ToolReconciliation.Action.CLAIM, "must recheck", "retain original", null);
                var store = c.getBean(MybatisToolOutcomeStore.class);
                assertThrows(RuntimeException.class, () -> store.reviewCommand(permit, "revoked", command).block(WAIT));
                assertEquals(0, r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_outcome_review_command", Integer.class));
                r.base.f.jdbc.update("UPDATE arte_ai_tool_outcome_check SET state='NEEDS_REVIEW'");
                assertEquals(1, store.alertReviews().block(WAIT));
                assertEquals(0, store.alertReviews().block(WAIT));
            });
        }
    }

    @Test
    void managementAuthorityDoesNotExposeOtherUsersUnreviewedPrivateArguments() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            r.context().withPropertyValues("arte.ai-new-tool-outcome.enabled=true")
                    .withBean("unknownText", org.springframework.beans.factory.config.BeanPostProcessor.class, () -> new org.springframework.beans.factory.config.BeanPostProcessor() {
                        public Object postProcessAfterInitialization(Object bean, String name) {
                            return bean instanceof com.arte.ainew.spi.gateway.ToolGateway ? (com.arte.ainew.spi.gateway.ToolGateway) call -> reactor.core.publisher.Mono.error(new IllegalStateException("Injected read uncertainty")) : bean;
                        }
                    }).run(c -> {
                        var mvc = r.mvc(c);
                        r.publish(mvc);
                        String binding = r.prepare(mvc);
                        String id = r.post(mvc, "tool/invokeTool", r.command(mvc, binding, "private-draft-body"), "private-text", 202).get("executionId").asString();
                        run(c);
                        addReviewer(r, c);
                        var v = new Case(mvc, id);
                        var managed = detail(r, v);
                        assertFalse(managed.get("evidenceSupported").asBoolean());
                        assertFalse(managed.get("argumentsJson").asString().contains("private-draft-body"));
                        var own = r.post(mvc, "tool/getReconciliation", Map.of("scope", r.scope, "invocationId", id), null, 200);
                        assertTrue(own.get("argumentsJson").asString().contains("private-draft-body"));
                    });
        }
    }
}
