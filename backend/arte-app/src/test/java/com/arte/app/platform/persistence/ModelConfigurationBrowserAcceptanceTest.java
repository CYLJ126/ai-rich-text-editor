package com.arte.app.platform.persistence;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.model.*;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.application.execution.GenerationDispatcher;
import com.arte.ainew.application.model.DefaultModelSharingService;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.ainew.spi.control.PublishedAiCatalog;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.model.ModelConnectionValidator;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiBudgetController;
import com.arte.ainew.web.controller.NewAiConfigurationController;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.ai.ModelSharingStartupSafety;
import com.arte.app.platform.ai.PlatformModelSharingConfiguration;
import com.arte.app.platform.ai.WorkspaceAiGovernanceService;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.workspace.ReactiveWorkspaceOperations;
import com.arte.app.web.controller.platform.*;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.workspace.TenantMembership;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspaceMembership;
import com.arte.core.spi.workspace.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.netty.DisposableServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5 正式页面的隔离 HTTP 桥
 * <p>
 * 空审核目录、真实 MVC／H2／授权／回环模型及结算，固定测试会话。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 22:20 ✾
 */
@EnabledIfSystemProperty(named = "model.browser.m5", matches = "true")
class ModelConfigurationBrowserAcceptanceTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private record RunRequest(Scope scope, DefinitionRef binding, String budgetRef, int maxOutputTokens) {
    }

    private final AtomicInteger providerCalls = new AtomicInteger();

    @Test
    void browserBridge() throws Exception {
        assertNull(System.getProperty("platform.test.mysql"), "浏览器桥仅允许隔离 H2");
        TenantModelSharingIntegrationTest tenant = new TenantModelSharingIntegrationTest();
        tenant.setup();
        PersonalModelPublicationIntegrationTest fixture = tenant.f;
        DisposableServer provider = reactor.netty.http.server.HttpServer.create().host("127.0.0.1").port(0)
                .route(routes -> routes.get("/v1/models", (request, response) -> {
                    assertEquals("Bearer fixture-model-key", request.requestHeaders().get("Authorization"));
                    return response.header("Content-Type", "application/json").sendString(Mono.just("{\"data\":[{\"id\":\"deepseek-chat\"}]}"));
                }).post("/v1/chat/completions", (request, response) -> {
                    assertEquals("Bearer fixture-model-key", request.requestHeaders().get("Authorization"));
                    providerCalls.incrementAndGet();
                    return request.receive().aggregate().then(response.header("Content-Type", "text/event-stream").sendString(Mono.just(
                            "data: {\"id\":\"m5\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"M5 回环模型实际回答\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\ndata: [DONE]\n\n")).then());
                })).bindNow();
        String origin = "http://127.0.0.1:" + provider.port();
        fixture.f.f.allowOrigins(origin);
        try {
            fixture.realGenerationContext(false).withUserConfiguration(PlatformModelSharingConfiguration.class, ModelSharingStartupSafety.class)
                    .withPropertyValues("arte.ai-new-model-sharing.enabled=true").run(context -> {
                        assertNull(context.getStartupFailure());
                        fixture.policy(context, "127.0.0.1", true);
                        DefaultWorkspaceServices services = context.getBean(DefaultWorkspaceServices.class);
                        Scope home = fixture.f.scope;
                        TrustedActor actor = fixture.f.f.f.actor;
                        Scope target = services.workspaces().create(actor, new WorkspaceCommands.CreateWorkspace(
                                new CommandMeta("m5-space-b", "m5"), home.tenantId(), "执行空间 B", "M5 isolated")).value().scope();
                        JdbcTemplate jdbc = fixture.f.f.f.jdbc;
                        Map<String, TrustedActor> actors = new LinkedHashMap<>();
                        actors.put("alice", actor);
                        actors.put("publisher", addMember(context, fixture, 2, "publisher", home));
                        actors.put("bob", addMember(context, fixture, 3, "bob", target));
                        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_control_revision", Integer.class));
                        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
                        PlatformPersistenceExecutor persistence = new PlatformPersistenceExecutor(context.getBean("newAiPersistenceScheduler", Scheduler.class), fixture.f.f.f.tx);
                        PlatformAuthenticatedExecutor executor = new PlatformAuthenticatedExecutor(context.getBean(RbacIdentityProvider.class), persistence);
                        NewAiHttpContext aiHttp = new NewAiHttpContext(context.getBean(ExecutionContextFactory.class), context.getBean(NewAiProperties.class));
                        try (ManagementHttpHarness http = new ManagementHttpHarness(
                                new ModelResourceController(executor, context.getBean(ModelResourceService.class), context.getBean(ModelAclMigrationService.class)),
                                new ModelDraftController(executor, context.getBean(ModelDraftService.class), context.getBean(ModelConnectionValidator.class), context.getBean(ModelResourceService.class)),
                                new ModelPublicationController(executor, context.getBean(ModelPublicationService.class)),
                                new ReviewedModelPlanController(executor, context.getBean(ModelDraftService.class)),
                                new ModelUsagePreparationController(executor, context.getBean(ModelUsagePreparationService.class)),
                                new ModelSharingController(executor, context.getBean(DefaultModelSharingService.class)),
                                new WorkspaceController(context.getBean(ReactiveWorkspaceOperations.class)),
                                new WorkspaceAiConfigurationController(context.getBean(DatabaseAiControlService.class), context.getBean(RbacIdentityProvider.class), persistence, context.getBean(WorkspaceAiGovernanceService.class)),
                                new NewAiConfigurationController(context.getBean(ChatConfigurationQueryService.class), aiHttp, context.getBean(NewAiProperties.class)),
                                new NewAiBudgetController(context.getBean(BudgetAccountQueryService.class), aiHttp, context.getBean(NewAiProperties.class)),
                                new ResourceAccessController(executor, services.resources(), context.getBean("platformAuthorizationService", AuthorizationService.class)),
                                new WorkspaceAccessController(context.getBean(ReactiveWorkspaceOperations.class)))) {
                            serve(context, fixture, http, actors, home, target, origin);
                        }
                    });
        } finally {
            provider.disposeNow();
            tenant.cleanup();
        }
    }

    /**
     * 创建隔离成员及导航偏好；不增加模型、凭据或预算资格。
     */
    private TrustedActor addMember(ApplicationContext context, PersonalModelPublicationIntegrationTest fixture, long id, String name, Scope scope) {
        fixture.f.f.f.jdbc.update("INSERT INTO arte_rbac_user VALUES(?,?,1,?)", id, name, java.sql.Timestamp.valueOf("2026-10-30 10:00:00"));
        TrustedActor member = new TrustedActor(context.getBean(RbacIdentityProvider.class).resolveAuthenticatedName(name).orElseThrow(), Instant.now());
        context.getBean(DefaultWorkspaceServices.class).personal().initialize(member, new CommandMeta("m5-personal-" + name, "m5"));
        fixture.f.f.f.tx.required(() -> {
            new WorkspaceGovernanceLocks(fixture.f.f.f.tx, context.getBean(TenantStore.class), context.getBean(WorkspaceStore.class),
                    context.getBean(TenantMembershipStore.class), context.getBean(WorkspaceMembershipStore.class), context.getBean(PreferenceStore.class))
                    .tenant(member.principal().subjectId(), scope.tenantId());
            assertTrue(context.getBean(TenantMembershipStore.class).insert(new TenantMembership("m5-tenant-" + name, scope.tenantId(), member.principal().subjectId(), TenantRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
            assertTrue(context.getBean(WorkspaceMembershipStore.class).insert(new WorkspaceMembership("m5-space-" + name, scope, member.principal().subjectId(), WorkspaceRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
            return null;
        });
        context.getBean(DefaultWorkspaceServices.class).preferences().update(member, new WorkspaceCommands.UpdatePreference(
                new CommandMeta("m5-startup-" + name, "m5"), 0, scope, null, Set.of()));
        return member;
    }

    /**
     * 仅固定测试会话的本机桥，真实业务请求仍进入正式 MVC 与应用授权。
     */
    private void serve(ApplicationContext context, PersonalModelPublicationIntegrationTest fixture, ManagementHttpHarness http,
                       Map<String, TrustedActor> actors, Scope home, Scope target, String origin) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 15203), 0);
        java.util.concurrent.ExecutorService requests = Executors.newFixedThreadPool(4);
        server.setExecutor(requests);
        Map<String, Long> ids = Map.of("alice", 1L, "publisher", 2L, "bob", 3L);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String login = Optional.ofNullable(exchange.getRequestHeaders().getFirst("X-Model-Fixture-User")).orElse("alice");
                if (!actors.containsKey(login)) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                byte[] output;
                int status = 200;
                if (path.equals("/fixture/info")) {
                    output = fixture.f.f.f.json.write(Map.of("origin", origin, "home", home, "target", target,
                            "users", ids, "subjects", actors.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().principal().subjectId())))).getBytes(StandardCharsets.UTF_8);
                } else if (path.equals("/fixture/state")) {
                    Map<String, Object> state = new LinkedHashMap<>();
                    state.put("accounts", fixture.f.f.f.jdbc.queryForList("SELECT workspace_snapshot FROM arte_ai_account"));
                    state.put("models", fixture.f.f.f.jdbc.queryForList("SELECT COUNT(*) AS total FROM arte_ai_model_resource"));
                    state.put("preparations", fixture.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_command WHERE command_type='model:usage:prepare'", Integer.class));
                    state.put("providerCalls", providerCalls.get());
                    state.put("settlements", fixture.f.f.f.jdbc.queryForList("SELECT workspace_snapshot FROM arte_ai_settlement"));
                    output = fixture.f.f.f.json.write(state).getBytes(StandardCharsets.UTF_8);
                } else if (path.equals("/fixture/run")) {
                    String body = new String(exchange.getRequestBody().readNBytes(8192), StandardCharsets.UTF_8);
                    RunRequest input = fixture.f.f.f.json.read(body, RunRequest.class);
                    output = fixture.f.f.f.json.write(run(context, fixture, login, input)).getBytes(StandardCharsets.UTF_8);
                } else if (path.startsWith("/arte/") && exchange.getRequestMethod().equals("POST")) {
                    byte[] body = exchange.getRequestBody().readNBytes(65537);
                    if (body.length > 65536) {
                        exchange.sendResponseHeaders(413, -1);
                        return;
                    }
                    org.springframework.test.web.servlet.MvcResult result = http.execute(path.substring(5), new String(body, StandardCharsets.UTF_8),
                            exchange.getRequestHeaders().getFirst("Idempotency-Key"), ManagementHttpHarness.login(fixture.f.f.f.jdbc, ids.get(login), login));
                    output = result.getResponse().getContentAsByteArray();
                    status = result.getResponse().getStatus();
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(status, output.length);
                exchange.getResponseBody().write(output);
            } catch (Throwable failure) {
                failure.printStackTrace();
                exchange.sendResponseHeaders(503, -1);
            } finally {
                exchange.close();
            }
        });
        Path ready = Path.of("target/model-m5-browser-ready");
        Path stop = Path.of("target/model-m5-browser-stop");
        Files.deleteIfExists(stop);
        server.start();
        Files.writeString(ready, "127.0.0.1:15203");
        try {
            long deadline = System.nanoTime() + Duration.ofMinutes(45).toNanos();
            while (!Files.exists(stop) && System.nanoTime() < deadline) {
                Thread.sleep(250);
            }
        } finally {
            server.stop(0);
            requests.shutdownNow();
            Files.deleteIfExists(ready);
            Files.deleteIfExists(stop);
        }
    }

    /**
     * 正式受理／派发／供应商 HTTP／耐久结算；范围和调用主体必须来自本次配置。
     */
    private Map<String, Object> run(ApplicationContext context, PersonalModelPublicationIntegrationTest fixture, String login, RunRequest applied) {
        Scope scope = applied.scope();
        DefinitionRef binding = applied.binding();
        ModelResourceVersion version = fixture.f.f.f.tx.readOnly(() -> context.getBean(ModelResourceStore.class).findBinding(scope.tenantId(), binding).orElseThrow());
        ExecutionAuthorization auth = context.getBean(DatabaseAiControlService.class).resolve(login, scope.tenantId(), scope.workspaceId(),
                Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ, AdmissionAuthorization.CONVERSATION)).block(WAIT);
        ExecutionContext initial = new ExecutionContext(UUID.randomUUID().toString(), "m5-browser", auth, Instant.now().plusSeconds(60), null, null, context.getBean(NewAiProperties.class).releaseRef(), UUID.randomUUID().toString());
        String budget = context.getBean(BudgetDefinitionProvider.class).available(initial, BudgetDefinitionProvider.Operation.READ).block(WAIT).stream()
                .filter(value -> value.rate().equals(version.publication().rate().definition()) && value.budgetRef().equals(applied.budgetRef())).findFirst().orElseThrow().budgetRef();
        ExecutionContext execution = new ExecutionContext(initial.executionId(), initial.traceId(), auth, initial.deadline(), null, budget, initial.releaseRef(), initial.idempotencyKey());
        String conversation = context.getBean(ConversationService.class).create("M5 loopback chat", null, List.of(), execution).block(WAIT).conversationId();
        ContextRequest input = new ContextRequest(List.of(new ChatMessage("user", ChatMessage.Role.USER, List.of(new ChatMessage.Text("M5 hello")), List.of(), null)),
                null, List.of(), List.of(), null, new ContextBudget(8192, 4096, 512, 0));
        EntryRequests.Chat request = new EntryRequests.Chat(conversation, 0, null, null, input, version.publication().binding().capability().definition(), binding,
                new GenerationOptions(applied.maxOutputTokens(), null, null, List.of()), new ExecutionOptions(execution.deadline(), 1, 4096, 0, 0, Duration.ofSeconds(60)));
        AcceptedExecution accepted = context.getBean(ChatService.class).submit(request, execution).block(WAIT);
        MybatisExecutionPersistence executions = context.getBean(MybatisExecutionPersistence.class);
        MybatisPayloadPersistence payloads = context.getBean(MybatisPayloadPersistence.class);
        GenerationDispatcher dispatcher = new GenerationDispatcher(context.getBean(AdmissionAuthorization.class), context.getBean(CapabilityCatalog.class), context.getBean(BindingManager.class),
                context.getBean(PublishedAiCatalog.class), context.getBean(AiExecutionPolicyProvider.class), context.getBean(AiAdmissionAuthorizationGuard.class), executions, executions, executions,
                payloads, payloads, executions, context.getBean(ModelGateway.class), context.getBean(NewAiProperties.class), new NewAiExecutionProperties(true, false, 0, null, null, null, null), Clock.systemUTC(), null);
        OutboxMessage message = executions.claim(OutboxMessage.Kind.DISPATCH, "m5-worker", Duration.ofSeconds(60), 1).block(WAIT).getFirst();
        Invocation acceptedInvocation = executions.find(ExecutionOwners.from(execution), accepted.executionId()).block(WAIT);
        dispatcher.dispatch(message, ExecutionRuntimeContext.start(acceptedInvocation.request().context())).block(WAIT);
        Invocation complete = executions.find(ExecutionOwners.from(execution), accepted.executionId()).block(WAIT);
        assertEquals(Invocation.State.SUCCEEDED, complete.state());
        String settlement = fixture.f.f.f.jdbc.queryForObject("SELECT s.workspace_snapshot FROM arte_ai_settlement s JOIN arte_ai_reservation r ON r.id_key=s.reservation_key WHERE r.workspace_snapshot LIKE ?", String.class, "%" + accepted.executionId() + "%");
        assertTrue(settlement.contains("SETTLED"));
        return Map.of("state", complete.state(), "settlement", settlement, "scope", scope, "subject", auth.principal().subjectId(), "binding", binding);
    }
}
