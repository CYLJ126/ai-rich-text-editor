package com.arte.app.platform.persistence;

import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.application.tool.discovery.StaticToolDiscovery;
import com.arte.ainew.application.tool.discovery.StaticToolRegistry;
import com.arte.ainew.spi.gateway.ToolGateway;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.app.platform.ai.tool.LocalToolWriteProperties;
import com.arte.app.platform.ai.tool.LocalWorkspaceTools;
import com.arte.core.api.workspace.WorkspaceService;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正式审批写工具验收的共享装配；所有数据库均来自 ToolAcceptanceRig。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
class ToolWriteAcceptanceRig {

    static final Duration WAIT = Duration.ofSeconds(15);

    enum Fault {NONE, NO_RECEIPT, DOUBLE_CALL, LOSE_RESULT, ROLLBACK_AFTER_DOMAIN, EXPIRE_ATTEMPT_IN_DOMAIN}

    record Pending(String id, Map<String, Object> command, String key) {
    }

    ApplicationContextRunner context(ToolAcceptanceRig rig, Fault fault) {
        // 只构造定义计算预期清单；实际执行、授权和事务全部使用正式容器 Bean。
        var writes = new LocalWorkspaceTools(new LocalToolWriteProperties(true), null, null, null, null, null, null, null, null, Clock.systemUTC());
        var text = rig.tools;
        var schemas = new ArrayList<>(text.definitions().schemas());
        schemas.addAll(writes.definitions().schemas());
        var definitions = new ArrayList<>(text.definitions().tools());
        definitions.addAll(writes.definitions().tools());
        var providers = new ArrayList<>(text.registry().registrations().stream().map(StaticToolRegistry.Registration::provider).toList());
        providers.add(writes.registration().provider());
        String manifest = StaticToolDiscovery.manifest(new ToolCatalogStore.Batch(providers, schemas, definitions));
        var context = rig.context().withPropertyValues("arte.ai-new-tool-write.enabled=true", "arte.ai-new-tool-approval.enabled=true",
                "arte.ai-new-execution.scheduling.tool-manifest=" + manifest);
        if (fault == Fault.NONE) return context;
        return context.withBean("writeToolFault", BeanPostProcessor.class, () -> new BeanPostProcessor() {
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof ToolGateway gateway && (fault == Fault.DOUBLE_CALL || fault == Fault.LOSE_RESULT || fault == Fault.NO_RECEIPT))
                    return (ToolGateway) call -> fault == Fault.NO_RECEIPT ? Mono.error(new IllegalStateException("Injected transport uncertainty before receipt")) : gateway.invoke(call).flatMap(value -> fault == Fault.DOUBLE_CALL ? gateway.invoke(call)
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

    String publish(ToolAcceptanceRig r, ApplicationContext c, MockMvc mvc) throws Exception {
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

    Pending submit(ToolAcceptanceRig r, MockMvc mvc, String binding, String version, String name) throws Exception {
        return submit(r, mvc, binding, version, name, 4096);
    }

    Pending submit(ToolAcceptanceRig r, MockMvc mvc, String binding, String version, String name, long outputLimit) throws Exception {
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

    JsonNode approve(ToolAcceptanceRig r, MockMvc mvc, Pending p) throws Exception {
        var d = r.post(mvc, "tool/getApproval", Map.of("scope", r.scope, "invocationId", p.id()), null, 200);
        assertEquals("RESOURCE_WRITE", d.get("sideEffect").asString());
        return r.post(mvc, "tool/decideApproval", Map.of("scope", r.scope, "approvalId", d.get("approvalId").asString(), "expectedVersion", d.get("version").asString(),
                "subjectDigest", d.get("subjectDigest").asString(), "decision", "APPROVE"), "decision-" + p.id(), 202);
    }

    JsonNode status(ToolAcceptanceRig r, MockMvc mvc, String id) throws Exception {
        return r.post(mvc, "invocation/getInvocationStatus", Map.of("scope", r.scope, "invocationId", id), null, 200);
    }

    long version(ToolAcceptanceRig r) {
        return r.base.f.jdbc.queryForObject("SELECT version FROM arte_platform_workspace WHERE id=?", Long.class, r.scope.workspaceId());
    }

    int receipts(ToolAcceptanceRig r) {
        return r.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_command WHERE command_type='workspace:rename'", Integer.class);
    }

    void run(ApplicationContext c) {
        c.getBean(InvocationDispatchWorker.class).pollOnce().block(WAIT);
    }

}
