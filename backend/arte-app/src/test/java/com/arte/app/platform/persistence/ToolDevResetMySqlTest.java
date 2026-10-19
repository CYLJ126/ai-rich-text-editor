package com.arte.app.platform.persistence;

import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.tool.ToolDefinition;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.app.platform.ai.tool.LocalTextTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 开发重置脚本仅在随机隔离 MySQL 库验证，覆盖重新发现／发布／执行和拒绝未排空状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 23:00 ✾
 */
@EnabledIfSystemProperty(named = "platform.test.mysql", matches = ".+")
class ToolDevResetMySqlTest {
    private static final String OLD_MANIFEST = "331756c89f707d42a1142275e1be4d4711b6ef76ef6ac7df4b996ce463cd6569";

    @Test
    void resetsOnlyTerminalTextToolDataAndAllowsFreshDiscoveryAndInvocation() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                String id = rig.post(mvc, "tool/invokeTool", rig.command(mvc, rig.prepare(mvc), "reset test"), "before-reset", 202).get("executionId").asString();
                c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                assertEquals("SUCCEEDED", rig.post(mvc, "invocation/getInvocationStatus", Map.of("scope", rig.scope, "invocationId", id), null, 200).get("state").asString());
                var keep = revision(rig.tools, new DefinitionRef("tool", "text.keepThisTool", "v1"), Duration.ofSeconds(30));
                var catalog = c.getBean(ToolCatalogStore.class);
                assertTrue(catalog.put(new ToolCatalogStore.Batch(List.of(), List.of(), List.of(keep))).block(Duration.ofSeconds(15)).successful());
                long users = rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_user", Long.class);
                seedOldDefinitionAndPool(rig, c);
                runReset(rig);
                assertNull(catalog.findTool(LocalTextTools.TOOL).block(Duration.ofSeconds(15)));
                assertEquals(keep, catalog.findTool(keep.tool().definition()).block(Duration.ofSeconds(15)));
                for (String table : List.of("arte_ai_tool_binding", "arte_ai_tool_binding_revision", "arte_ai_tool_binding_resolution",
                        "arte_ai_tool_binding_command", "arte_ai_tool_release", "arte_ai_tool_release_command", "arte_ai_tool_execution_snapshot",
                        "arte_ai_invocation", "arte_ai_event", "arte_ai_outbox", "arte_ai_dispatch_pool"))
                    assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
                assertEquals(users, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_user", Long.class));
                rig.publish(mvc);
                String next = rig.post(mvc, "tool/invokeTool", rig.command(mvc, rig.prepare(mvc), "A😀中\n"), "after-reset", 202).get("executionId").asString();
                c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                assertEquals("{\"codePoints\":4}", rig.post(mvc, "invocation/getInvocationResult", Map.of("scope", rig.scope, "invocationId", next), null, 200).get("outputJson").asString());
            });
        }
    }

    @Test
    void refusesCurrentContractAndUndeliveredWorkWithoutDeletingRows() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                String binding = rig.prepare(mvc);
                assertThrows(RuntimeException.class, () -> runReset(rig));
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_binding", Integer.class));
                rig.post(mvc, "tool/invokeTool", rig.command(mvc, binding, "must remain accepted"), "active", 202);
                seedOldDefinitionAndPool(rig, c);
                assertThrows(RuntimeException.class, () -> runReset(rig));
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_execution_snapshot", Integer.class));
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_definition", Integer.class));
            });
        }
    }

    private static ToolDefinitionRevision revision(LocalTextTools tools, DefinitionRef ref, Duration timeout) {
        var t = tools.revision().tool();
        var p = t.defaultPolicy();
        var unsigned = new ToolDefinition(ref, t.providerId(), t.title(), t.description(), t.tags(), t.capability(), t.inputSchema(),
                t.outputSchema(), t.configurationSchema(), t.defaultConfiguration(), t.capabilities(), t.risk(),
                new ToolExecutionPolicy(timeout, p.maxAttempts(), p.retryBackoff(), p.maxInputBytes(), p.maxOutputBytes(), p.approvalMode()), "0".repeat(64));
        var signed = new ToolDefinition(ref, t.providerId(), t.title(), t.description(), t.tags(), t.capability(), t.inputSchema(),
                t.outputSchema(), t.configurationSchema(), t.defaultConfiguration(), t.capabilities(), t.risk(), unsigned.defaultPolicy(), ToolCatalogDigests.tool(unsigned));
        return new ToolDefinitionRevision(signed, tools.revision().provider(), tools.revision().target());
    }

    private static void seedOldDefinitionAndPool(ToolAcceptanceRig rig, ApplicationContext c) {
        var old = revision(rig.tools, LocalTextTools.TOOL, Duration.ofSeconds(5));
        String encoded = CanonicalJson.normalize(c.getBean(ExecutionRecordCodec.class).encode(old));
        rig.base.f.jdbc.update("UPDATE arte_ai_tool_definition SET workspace_snapshot=?,payload_digest=? WHERE id_key=?", encoded,
                CanonicalJson.sha256(encoded), CanonicalJson.key("tool", "text.countCodePoints", "v1"));
        rig.base.f.jdbc.update("UPDATE arte_ai_dispatch_pool SET manifest_digest=?", OLD_MANIFEST);
    }

    private static void runReset(ToolAcceptanceRig rig) throws Exception {
        try (var connection = rig.base.f.source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("../arte-ai/scripts/arte-ai-tool-text-v1-dev-reset-mysql.sql"));
            } catch (RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }
}
