package com.arte.app.platform.persistence;

import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.tool.ToolChatRunSnapshot;
import com.arte.ainew.pojo.tool.ToolSetSnapshot;
import com.arte.ainew.spi.auth.ToolChatAccess;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.app.platform.identity.RbacIdentityProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实宿主身份、RBAC、空间和同库门控；不调用模型、不修改配置业务数据库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
class PlatformToolChatAccessIntegrationTest {

    static DefinitionRef ref(String type, String id) {
        return new DefinitionRef(type, id, "v1");
    }

    @Test
    void currentUseIsRecheckedInTransactionAndAiDisabledStillAllowsOwnedStop() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().withPropertyValues("arte.ai-new-tool-chat.enabled=true").run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                var selected = rig.prepare(mvc);
                var subject = c.getBean(RbacIdentityProvider.class).resolveAuthenticatedName("alice").orElseThrow().subjectId();
                var now = Instant.now();
                var principal = new ExecutionPrincipal(subject, "alice", ExecutionPrincipal.Kind.USER);
                var owner = new ExecutionOwner(rig.scope.tenantId(), rig.scope.workspaceId(), subject);
                var auth = new ExecutionAuthorization(principal, owner.tenantId(), owner.workspaceId(), Set.of("ai:invoke"), "test-current");
                var root = new ExecutionContext("run", "trace", auth, now.plusSeconds(30), null, null, "release", "key");
                var binding = c.getBean(ToolBindingService.class).resolve(owner, selected).block(Duration.ofSeconds(10)).value();
                var schema = c.getBean(ToolCatalogStore.class).findSchema(binding.tool().tool().inputSchema()).block(Duration.ofSeconds(10));
                var tools = new ToolSetSnapshot(owner, List.of(new ToolSetSnapshot.Entry("tool_0", binding, binding.effectivePolicy(), schema)));
                var capability = new CapabilityDescriptor(ref("capability", "test-model"), CapabilityDescriptor.Kind.GENERATION,
                        ref("schema", "request"), ref("schema", "result"), Set.of(CapabilityDescriptor.Feature.TEXT_INPUT,
                        CapabilityDescriptor.Feature.STREAMING, CapabilityDescriptor.Feature.TOOL_CALLS), CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
                var model = new ResolvedBinding(ref("binding", "model"), capability, ref("connection", "model"), "model", 8192L, ref("rate", "model"));
                var input = new ContextSnapshot("context", null, model.definition(), List.of(new ChatMessage("user", ChatMessage.Role.USER,
                        List.of(new ChatMessage.Text("question")), List.of(), null)), List.of(), new ContextBudget(8192, 4096, 1024, 0),
                        10, true, "test", List.of(), "0".repeat(64), now, now.plusSeconds(60));
                var fixed = new ToolChatRunSnapshot(root, input, model, new GenerationOptions(128, null, null, List.of()), tools,
                        new ToolChatRunSnapshot.Limits(Duration.ofSeconds(30), 3, 4, 1), now);
                var access = c.getBean(ToolChatAccess.class);
                var use = access.authorize(root, false).block(Duration.ofSeconds(10));
                assertTrue(rig.base.f.tx.required(() -> {
                    use.requireInCurrentTransaction(fixed);
                    return true;
                }));
                rig.setAiEnabled(false);
                assertThrows(RuntimeException.class, () -> rig.base.f.tx.required(() -> {
                    use.requireInCurrentTransaction(fixed);
                    return true;
                }));
                assertThrows(RuntimeException.class, () -> access.authorize(root, false).block(Duration.ofSeconds(10)));
                var control = new ExecutionContext("control", "trace", new ExecutionAuthorization(principal, owner.tenantId(), owner.workspaceId(), Set.of("ai:read"), "read"),
                        now.plusSeconds(30), null, null, "release", "cancel");
                var stop = access.authorize(control, true).block(Duration.ofSeconds(10));
                assertTrue(rig.base.f.tx.required(() -> {
                    stop.requireInCurrentTransaction(fixed);
                    return true;
                }));
                rig.base.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE target='aiTool:invoke'");
                assertThrows(RuntimeException.class, () -> rig.base.f.tx.required(() -> {
                    stop.requireInCurrentTransaction(fixed);
                    return true;
                }));
                assertThrows(RuntimeException.class, () -> access.authorize(control, true).block(Duration.ofSeconds(10)));
            });
        }
    }
}
