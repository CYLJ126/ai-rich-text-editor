package com.arte.ainew.tool.execution;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.execution.*;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.management.ToolManagementService;
import com.arte.ainew.application.tool.release.StaticToolPublicationValidator;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.persistence.mybatis.MybatisToolDiscoveryManagementStore;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.ControlReceipt;
import com.arte.ainew.pojo.execution.ExecutionControlRequest;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.pojo.tool.ToolRiskProfile;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.spi.auth.ToolInvocationAccess;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.ToolManagementExceptionHandler;
import com.arte.ainew.web.controller.NewAiInvocationController;
import com.arte.ainew.web.controller.NewAiToolController;
import com.arte.ainew.web.controller.NewAiToolInvocationController;
import com.arte.ainew.web.controller.NewAiToolSubmissionController;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.platform.TrustedActor;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 真实公开预览／受理至 Worker／结果的隔离闭环，不加载生产业务授权或模型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 09:47 ✾
 */
public class ToolSubmissionExecutionTest {
    private static final Duration WAIT = Duration.ofSeconds(15);

    static final class Rig implements AutoCloseable {
        final ToolExecutionFixture f = new ToolExecutionFixture(
                new ToolExecutionPolicy(Duration.ofSeconds(30), 1, Duration.ZERO, 1024, 4096,
                        ToolExecutionPolicy.ApprovalMode.NONE),
                new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY,
                        false, ToolRiskProfile.Idempotency.NONE, Set.of("ai:invoke"), Set.of()));
        final AtomicBoolean read = new AtomicBoolean(true), cancel = new AtomicBoolean(true), commit = new AtomicBoolean(true);
        final NewAiProperties properties = new NewAiProperties(false, "appDataSource", "release", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        final ExecutionAuthorizationResolver resolver = (name, tenant, workspace, scopes) -> Mono.just(new ExecutionAuthorization(
                new ExecutionPrincipal("subject", name, ExecutionPrincipal.Kind.USER), tenant, workspace, scopes, "grant"));
        final ToolInvocationAccess access = (invocation, context, operation) -> Mono.defer(() -> {
            if (!(operation == ToolInvocationAccess.Operation.READ ? read : cancel).get())
                return Mono.error(new AccessDeniedException("Revoked"));
            return Mono.just(new ToolInvocationAccess.Permit() {
                public ExecutionAuthorization authorization() {
                    return context.authorization();
                }

                public void requireInCurrentTransaction() {
                    assertTrue(TransactionSynchronizationManager.hasResource(f.source));
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    if (!commit.get() || !cancel.get()) throw new AccessDeniedException("Revoked before commit");
                }
            });
        });
        final AdmissionAuthorization auth = new AdmissionAuthorization(resolver, properties, f.clock, null, f.io, access);
        final FixedControlCatalog catalog = new FixedControlCatalog(properties, auth, f.clock);
        final AdmissionInvocationCoordinator admission = new AdmissionInvocationCoordinator(auth, catalog, catalog, f.executions,
                f.payloads, f.executions, properties, f.clock);
        final DefaultInvocationCoordinator coordinator = new DefaultInvocationCoordinator(admission, null, f.executions, f.dispatcher);
        final DefaultExecutionControl control = new DefaultExecutionControl(auth, f.executions, coordinator);
        final InvocationBudgetStatusResolver budget = new InvocationBudgetStatusResolver(f.executions, f.executions);
        final LocalExecutionEventNotifier notifier = new LocalExecutionEventNotifier();
        final DefaultExecutionEventService events = new DefaultExecutionEventService(auth, f.executions, f.executions, f.payloads,
                notifier, f.clock, budget);

        Rig() throws Exception {
        }

        ExecutionContext context() {
            return new ExecutionContext("query", "trace-query", new ExecutionAuthorization(
                    new ExecutionPrincipal("subject", "alice", ExecutionPrincipal.Kind.USER), "tenant", "workspace", Set.of("ai:read"), "grant"),
                    f.clock.instant().plusSeconds(30), null, null, "release", "cancel-key");
        }

        ControlReceipt cancel(String key) {
            return control.request(new ExecutionControlRequest("command-" + key, "one", key,
                    ControlReceipt.Command.CANCEL), context()).block(WAIT);
        }

        MockMvc mvc() {
            var http = new NewAiHttpContext(new ExecutionContextFactory(resolver, f.clock), properties);
            return standaloneSetup(new NewAiInvocationController(control, events, http, properties, budget),
                    new NewAiToolInvocationController(control, http, properties), submission(http), management())
                    .setControllerAdvice(new ConversationExceptionHandler(), new ToolManagementExceptionHandler())
                    .setAsyncRequestTimeout(15000).build();
        }


        private NewAiToolSubmissionController submission(NewAiHttpContext http) {
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("service", f.service);
            return new NewAiToolSubmissionController(
                    beans.getBeanProvider(ToolAdmissionService.class), http, properties);
        }

        private NewAiToolController management() {
            var validator = new StaticToolPublicationValidator(f.discovery, f.registry,
                    new ToolSchemaValidator(), f.metadata, f.clock, Duration.ofSeconds(2));
            var service = new ToolManagementService(f.catalog, f.releases, f.bindings,
                    f.releaseService, f.bindingService, validator, f.discovery,
                    new MybatisToolDiscoveryManagementStore(f.source, f.codec, f.io, f.clock, f.catalog));
            ToolManagementAccess access = authentication -> (scope, operation) -> Mono.just(
                    new ToolManagementAccess.Permit() {
                        public TrustedActor actor() {
                            return f.actor;
                        }

                        public ExecutionOwner owner() {
                            return f.owner;
                        }

                        public ToolManagementAccess.Operation operation() {
                            return operation;
                        }

                        public Set<String> executionScopes() {
                            return Set.of("ai:invoke", "ai:read");
                        }

                        public void requireInCurrentTransaction(Set<String> scopes) {
                            throw new AssertionError("Read-only fixture management");
                        }
                    });
            return new NewAiToolController(service, access);
        }

        public void close() throws Exception {
            SecurityContextHolder.clearContext();
            f.close();
        }
    }

    @Test
    public void publicPreviewSubmitWorkerResultAndReplayStayOnOneInvocation() throws Exception {
        try (var r = new Rig()) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
            var mvc = r.mvc();
            var json = JsonMapper.builder().build();
            var input = Map.of("scope", Map.of("tenantId", "tenant", "workspaceId", "workspace"),
                    "input", Map.of("bindingId", r.f.binding.bindingId(), "arguments", Map.of(), "timeoutSeconds", 30));
            var preview = request(mvc, "/ai-new/tool/previewInvocation", json.writeValueAsString(input), null);
            assertEquals(preview.getResponse().getContentAsString(), 200, preview.getResponse().getStatus());
            assertEquals(0, r.f.invocations.get());
            var digest = json.readTree(preview.getResponse().getContentAsString()).get("data").get("planDigest").asString();
            var command = new java.util.HashMap<String, Object>(input);
            command.put("expectedPlanDigest", digest);
            var body = json.writeValueAsString(command);
            var first = request(mvc, "/ai-new/tool/invokeTool", body, "original-key");
            assertEquals(first.getResponse().getContentAsString(), 202, first.getResponse().getStatus());
            var id = json.readTree(first.getResponse().getContentAsString()).get("data").get("executionId").asString();
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            var query = json.writeValueAsString(Map.of("scope", input.get("scope"), "invocationId", id));
            var status = request(mvc, "/ai-new/invocation/getInvocationStatus", query, null);
            assertEquals("SUCCEEDED", json.readTree(status.getResponse().getContentAsString()).get("data").get("state").asString());
            var result = request(mvc, "/ai-new/invocation/getInvocationResult", query, null);
            assertEquals("{\"text\":\"result\"}", json.readTree(result.getResponse().getContentAsString()).get("data").get("outputJson").asString());
            var replay = request(mvc, "/ai-new/tool/invokeTool", body, "original-key");
            assertEquals(id, json.readTree(replay.getResponse().getContentAsString()).get("data").get("executionId").asString());
            assertEquals(1, r.f.invocations.get());
            assertEquals(1, r.f.count("arte_ai_invocation"));
            assertEquals(0, r.f.count("arte_ai_reservation"));
        }
    }

    static MvcResult request(MockMvc mvc, String path, String body, String key) throws Exception {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) builder.header("Idempotency-Key", key);
        var result = mvc.perform(builder).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(15000);
            return mvc.perform(asyncDispatch(result)).andReturn();
        }
        return result;
    }
}
