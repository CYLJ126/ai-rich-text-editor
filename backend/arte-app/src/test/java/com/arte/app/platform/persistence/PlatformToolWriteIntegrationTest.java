package com.arte.app.platform.persistence;

import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.spi.gateway.ToolGateway;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.app.platform.ai.tool.LocalToolWriteProperties;
import com.arte.app.platform.ai.tool.LocalWorkspaceTools;
import com.arte.core.api.workspace.WorkspaceService;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 正式宿主、MVC、当前平台权限、审批、领域事务与 Worker；隔离数据库，不连接业务库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:48 ✾
 */
class PlatformToolWriteIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private enum Fault {NONE, DOUBLE_CALL, LOSE_RESULT, ROLLBACK_AFTER_DOMAIN, EXPIRE_ATTEMPT_IN_DOMAIN}

    private record Pending(String id, Map<String, Object> command, String key) {
    }

    private ApplicationContextRunner context(ToolAcceptanceRig rig, Fault fault) {
        // 只构造定义计算预期清单；实际执行、授权和事务全部使用正式容器 Bean。
        var writes = new LocalWorkspaceTools(new LocalToolWriteProperties(true), null, null, null, null, null, null, null, null, Clock.systemUTC());
        var text = rig.tools;
        var schemas = new ArrayList<>(text.definitions().schemas());
        schemas.addAll(writes.definitions().schemas());
        var definitions = new ArrayList<>(text.definitions().tools());
        definitions.addAll(writes.definitions().tools());
        var providers = new ArrayList<>(text.registry().registrations().stream().map(LocalToolRegistry.Registration::provider).toList());
        providers.add(writes.registration().provider());
        String manifest = LocalToolDiscovery.manifest(new ToolCatalogStore.Batch(providers, schemas, definitions));
        var context = rig.context().withPropertyValues("arte.ai-new-tool-write.enabled=true", "arte.ai-new-tool-approval.enabled=true",
                "arte.ai-new-execution.scheduling.tool-manifest=" + manifest);
        if (fault == Fault.NONE) return context;
        return context.withBean("writeToolFault", BeanPostProcessor.class, () -> new BeanPostProcessor() {
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof ToolGateway gateway && (fault == Fault.DOUBLE_CALL || fault == Fault.LOSE_RESULT))
                    return (ToolGateway) call -> gateway.invoke(call).flatMap(value -> fault == Fault.DOUBLE_CALL ? gateway.invoke(call)
                            : Mono.error(new IllegalStateException("Injected result loss after domain commit")));
                if (bean instanceof WorkspaceService service && (fault == Fault.ROLLBACK_AFTER_DOMAIN || fault == Fault.EXPIRE_ATTEMPT_IN_DOMAIN))
                    return Proxy.newProxyInstance(WorkspaceService.class.getClassLoader(), new Class[]{WorkspaceService.class}, (proxy, method, args) -> {
                        try {
                            var value = method.invoke(service, args);
                            if (method.getName().equals("rename")) {
                                if (fault == Fault.ROLLBACK_AFTER_DOMAIN)
                                    throw new PlatformException(PlatformErrorCodeEnum.VERSION_CONFLICT, "test-rollback");
                                var codec = new com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec();
                                var old = codec.decode(rig.base.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_attempt", String.class), com.arte.ainew.pojo.execution.Attempt.class);
                                var expired = new com.arte.ainew.pojo.execution.Attempt(old.attemptId(), old.invocationId(), old.attemptNumber(), old.workerId(), old.fencingToken(),
                                        old.createdAt().plusMillis(1), old.version(), old.state(), old.dispatch(), old.remoteRequestId(), old.budgetReservationId(), old.usage(), old.error(), old.createdAt(), old.updatedAt());
                                rig.base.f.jdbc.update("UPDATE arte_ai_attempt SET workspace_snapshot=?", codec.encode(expired));
                            }
                            return value;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
                return bean;
            }
        });
    }

    private String publish(ToolAcceptanceRig r, ApplicationContext c, MockMvc mvc) throws Exception {
        var refresh = r.post(mvc, "tool/refreshLocalCatalog", Map.of("scope", r.scope, "reason", "write acceptance"), UUID.randomUUID().toString(), 200);
        assertTrue(refresh.get("nodeDiscoveryHealthy").asBoolean());
        var review = Map.of("examples", List.of(Map.of("input", Map.of("expectedVersion", "0", "name", "New name", "description", "New description"),
                        "output", Map.of("workspaceId", "space", "name", "New name", "version", "1", "operationId", "operation"))),
                "riskEvidence", "Only current scope details; current WORKSPACE_UPDATE, fixed version and approval; no members or permissions changed",
                "idempotencyEvidence", "WorkspaceService.rename and arte_platform_command commit together; stable key from original invocation; integration verified",
                "minimumApproval", "REQUESTER_CONFIRMATION");
        r.post(mvc, "tool/publishTool", Map.of("scope", r.scope, "tool", LocalWorkspaceTools.TOOL, "expectedVersion", "0", "reason", "reviewed write fixture", "review", review), "publish-write", 200);
        return r.post(mvc, "tool/prepareDefaultBinding", Map.of("scope", r.scope, "tool", LocalWorkspaceTools.TOOL, "configuration", Map.of()), "write-binding", 200).get("bindingId").asString();
    }

    private Pending submit(ToolAcceptanceRig r, MockMvc mvc, String binding, String version, String name) throws Exception {
        return submit(r, mvc, binding, version, name, 4096);
    }

    private Pending submit(ToolAcceptanceRig r, MockMvc mvc, String binding, String version, String name, long outputLimit) throws Exception {
        var input = Map.of("bindingId", binding, "arguments", Map.of("expectedVersion", version, "name", name, "description", "Updated description"), "timeoutSeconds", 120, "maxOutputBytes", outputLimit);
        var preview = r.post(mvc, "tool/previewInvocation", Map.of("scope", r.scope, "input", input), null, 200);
        assertEquals("REQUESTER_CONFIRMATION", preview.get("approvalMode").asString());
        assertTrue(preview.get("costDescription").asString().contains("业务写"));
        var command = new HashMap<String, Object>();
        command.put("scope", r.scope);
        command.put("input", input);
        command.put("expectedPlanDigest", preview.get("planDigest").asString());
        String key = UUID.randomUUID().toString();
        String id = r.post(mvc, "tool/invokeTool", command, key, 202).get("executionId").asString();
        assertEquals("WAITING_APPROVAL", status(r, mvc, id).get("state").asString());
        return new Pending(id, command, key);
    }

    private JsonNode approve(ToolAcceptanceRig r, MockMvc mvc, Pending p) throws Exception {
        var d = r.post(mvc, "tool/getApproval", Map.of("scope", r.scope, "invocationId", p.id()), null, 200);
        assertEquals("RESOURCE_WRITE", d.get("sideEffect").asString());
        return r.post(mvc, "tool/decideApproval", Map.of("scope", r.scope, "approvalId", d.get("approvalId").asString(), "expectedVersion", d.get("version").asString(),
                "subjectDigest", d.get("subjectDigest").asString(), "decision", "APPROVE"), "decision-" + p.id(), 202);
    }

    private JsonNode status(ToolAcceptanceRig r, MockMvc mvc, String id) throws Exception {
        return r.post(mvc, "invocation/getInvocationStatus", Map.of("scope", r.scope, "invocationId", id), null, 200);
    }

    private long version(ToolAcceptanceRig r) {
        return r.base.f.jdbc.queryForObject("SELECT version FROM arte_platform_workspace WHERE id=?", Long.class, r.scope.workspaceId());
    }

    private int receipts(ToolAcceptanceRig r) {
        return r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_command WHERE command_type='workspace:rename'", Integer.class);
    }

    private void run(ApplicationContext c) {
        c.getBean(InvocationDispatchWorker.class).pollOnce().block(WAIT);
    }

    @Test
    void writeToolIsAbsentByDefaultAndReadOnlyManifestIsUnchanged() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            r.context().run(c -> {
                assertNull(c.getStartupFailure());
                assertTrue(c.getBean(LocalToolRegistry.class).findAdapter(LocalWorkspaceTools.ADAPTER).isEmpty());
                assertFalse(c.getBean(LocalToolWriteProperties.class).enabled());
            });
        }
    }

    @Test
    void publicApproveWriteAndSameKeyReplayMutateExactlyOnce() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.NONE).run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = r.mvc(c);
                String binding = publish(r, c, mvc);
                var p = submit(r, mvc, binding, "0", "Updated workspace");
                run(c);
                assertEquals(0, version(r));
                assertEquals(0, receipts(r));
                approve(r, mvc, p);
                run(c);
                assertEquals("SUCCEEDED", status(r, mvc, p.id()).get("state").asString());
                assertEquals(1, version(r));
                assertEquals(1, receipts(r));
                var result = r.post(mvc, "invocation/getInvocationResult", Map.of("scope", r.scope, "invocationId", p.id()), null, 200);
                assertEquals("CONFIRMED", result.get("result").get("value").get("sideEffect").asString());
                assertEquals("Updated workspace", r.json.readTree(result.get("outputJson").asString()).get("name").asString());
                assertEquals(p.id(), r.post(mvc, "tool/invokeTool", p.command(), p.key(), 202).get("executionId").asString());
                run(c);
                assertEquals(1, version(r));
                assertEquals(1, receipts(r));
                assertEquals(1, r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
                String snapshot = r.base.f.jdbc.queryForObject("SELECT snapshot FROM arte_ai_tool_execution_snapshot", String.class);
                assertEquals(3, r.json.readTree(snapshot).get("schemaVersion").asInt());
                var fixed = c.getBean(com.arte.ainew.spi.persistence.ExecutionRecordCodec.class).decode(snapshot, com.arte.ainew.pojo.tool.ToolExecutionSnapshot.class);
                com.arte.ainew.spi.auth.ToolAdmissionAccess oldHost = (input, ctx, bindingPlan) -> {
                    throw new AssertionError("Read-only host must not authorize writes");
                };
                assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> oldHost.authorizeFree(fixed.input(), fixed.request().context(), fixed.binding()).block(WAIT));

                assertThrows(IllegalArgumentException.class, () -> c.getBean(com.arte.ainew.spi.persistence.ExecutionRecordCodec.class)
                        .decode(snapshot.replace("\"schemaVersion\":3", "\"schemaVersion\":2"), com.arte.ainew.pojo.tool.ToolExecutionSnapshot.class));
            });
        }
    }

    @Test
    void expectedVersionConflictIsKnownFailureAndDoesNotOverwriteAnotherEdit() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.NONE).run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = r.mvc(c);
                String binding = publish(r, c, mvc);
                var p = submit(r, mvc, binding, "0", "Stale planned name");
                approve(r, mvc, p);
                c.getBean(WorkspaceService.class).rename(r.base.f.actor, new WorkspaceCommands.RenameWorkspace(new CommandMeta("outside-edit", "test"), r.scope, 0, "Concurrent edit", "Existing"));
                run(c);
                var state = status(r, mvc, p.id());
                assertEquals("FAILED", state.get("state").asString());
                assertEquals("TOOL_RESOURCE_VERSION_CONFLICT", state.get("error").get("code").asString());
                assertEquals("NONE", state.get("error").get("sideEffect").asString());
                assertEquals(1, version(r));
                assertEquals(1, receipts(r));
                assertEquals("Concurrent edit", c.getBean(WorkspaceService.class).get(r.base.f.actor, r.scope).name());
            });
        }
    }

    @Test
    void domainReceiptPreventsDuplicateMutationEvenIfAdapterIsEnteredTwice() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.DOUBLE_CALL).run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = r.mvc(c);
                var p = submit(r, mvc, publish(r, c, mvc), "0", "Once only");
                approve(r, mvc, p);
                run(c);
                assertEquals("SUCCEEDED", status(r, mvc, p.id()).get("state").asString());
                assertEquals(1, version(r));
                assertEquals(1, receipts(r));
            });
        }
    }

    @Test
    void rollbackAfterDomainWriteAlsoRollsBackReceiptAndBusinessFacts() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.ROLLBACK_AFTER_DOMAIN).run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = r.mvc(c);
                var p = submit(r, mvc, publish(r, c, mvc), "0", "Must roll back");
                approve(r, mvc, p);
                run(c);
                assertEquals("FAILED", status(r, mvc, p.id()).get("state").asString());
                assertEquals(0, version(r));
                assertEquals(0, receipts(r));
            });
        }
    }

    @Test
    void lostResultAfterDomainCommitStaysUnknownAndDoesNotRedispatch() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.LOSE_RESULT).run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = r.mvc(c);
                var p = submit(r, mvc, publish(r, c, mvc), "0", "Committed without result");
                approve(r, mvc, p);
                run(c);
                assertEquals("UNKNOWN", status(r, mvc, p.id()).get("state").asString());
                assertEquals(1, version(r));
                assertEquals(1, receipts(r));
                run(c);
                assertEquals("UNKNOWN", status(r, mvc, p.id()).get("state").asString());
                assertEquals(1, version(r));
                assertEquals(1, r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
            });
        }
    }

    @Test
    void resourceRevocationAfterApprovalStopsWriteAndReplayCannotRestoreIt() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.NONE).run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = r.mvc(c);
                var p = submit(r, mvc, publish(r, c, mvc), "0", "Denied writer");
                approve(r, mvc, p);
                r.base.f.jdbc.update("UPDATE arte_platform_workspace_member SET role=2,version=version+1 WHERE workspace_id=?", r.scope.workspaceId());
                run(c);
                assertNotEquals("SUCCEEDED", status(r, mvc, p.id()).get("state").asString());
                assertEquals(0, version(r));
                assertEquals(0, receipts(r));
                r.post(mvc, "tool/invokeTool", p.command(), p.key(), 403);
            });
        }
    }

    @Test
    void directAdapterCannotBypassUnapprovedInvocationFence() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.NONE).run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = r.mvc(c);
                var p = submit(r, mvc, publish(r, c, mvc), "0", "Forged direct call");
                var codec = c.getBean(com.arte.ainew.spi.persistence.ExecutionRecordCodec.class);
                var snapshot = codec.decode(r.base.f.jdbc.queryForObject("SELECT snapshot FROM arte_ai_tool_execution_snapshot", String.class), com.arte.ainew.pojo.tool.ToolExecutionSnapshot.class);
                var now = java.time.Instant.now();
                var fake = new com.arte.ainew.pojo.execution.Attempt("forged-attempt", p.id(), 1, "forged-worker", 1, now.plusSeconds(10), 0,
                        com.arte.ainew.pojo.execution.Attempt.State.RUNNING, com.arte.ainew.pojo.execution.Attempt.Dispatch.MAY_HAVE_EXECUTED, "forged-dispatch", null,
                        com.arte.ainew.pojo.execution.Usage.unknown(), null, now, now);
                var call = new com.arte.ainew.pojo.execution.GatewayCall<>(snapshot.request(), snapshot.binding().resolvedBinding(), fake,
                        com.arte.ainew.context.ExecutionRuntimeContext.start(snapshot.request().context()));
                assertThrows(RuntimeException.class, () -> c.getBean(LocalToolRegistry.class).findAdapter(LocalWorkspaceTools.ADAPTER).orElseThrow().invoke(call).block(WAIT));
                assertEquals(0, version(r));
                assertEquals(0, receipts(r));
                assertEquals("WAITING_APPROVAL", status(r, mvc, p.id()).get("state").asString());
            });
        }
    }

    @Test
    void leaseLostAfterDomainCommandRollsBackBeforeCommit() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.EXPIRE_ATTEMPT_IN_DOMAIN).run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = r.mvc(c);
                var p = submit(r, mvc, publish(r, c, mvc), "0", "Expired during write");
                approve(r, mvc, p);
                run(c);
                assertEquals("UNKNOWN", status(r, mvc, p.id()).get("state").asString());
                assertEquals(0, version(r));
                assertEquals(0, receipts(r));
            });
        }
    }

    @Test
    void insufficientOutputLimitRollsBackBusinessAndReceiptBeforeCommit() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.NONE).run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = r.mvc(c);
                var p = submit(r, mvc, publish(r, c, mvc), "0", "Result must fit", 64);
                approve(r, mvc, p);
                run(c);
                assertNotEquals("SUCCEEDED", status(r, mvc, p.id()).get("state").asString());
                assertEquals(0, version(r));
                assertEquals(0, receipts(r));
            });
        }
    }

    @Test
    void extraTargetAndNoncanonicalVersionCannotBeSubmitted() throws Exception {
        try (var r = new ToolAcceptanceRig()) {
            context(r, Fault.NONE).run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = r.mvc(c);
                String binding = publish(r, c, mvc);
                for (var args : List.of(Map.of("expectedVersion", "0", "name", "Name", "description", "", "workspaceId", "foreign"),
                        Map.of("expectedVersion", "9223372036854775808", "name", "Name", "description", ""), Map.of("expectedVersion", "0", "name", "  hidden trim  ", "description", "")))
                    r.post(mvc, "tool/previewInvocation", Map.of("scope", r.scope, "input", Map.of("bindingId", binding, "arguments", args)), null, args.containsKey("workspaceId") ? 409 : 400);
                assertEquals(0, r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
            });
        }
    }
}
