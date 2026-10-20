package com.arte.app.platform.persistence;

import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.app.platform.ai.tool.LocalToolWriteProperties;
import com.arte.app.platform.ai.tool.LocalWorkspaceTools;
import com.arte.core.api.workspace.WorkspaceService;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 正式宿主、MVC、当前平台权限、审批、领域事务与 Worker；隔离数据库，不连接业务库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:48 ✾
 */
class PlatformToolWriteIntegrationTest extends ToolWriteAcceptanceRig {

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
