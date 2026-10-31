package com.arte.app.platform.persistence;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.api.model.ModelAclMigrationService;
import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.api.model.PersonalModelPublicationService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.application.execution.GenerationDispatcher;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import com.arte.ainew.spi.control.PublishedAiCatalog;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.model.ModelConnectionValidator;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.workspace.ReactiveWorkspaceOperations;
import com.arte.app.web.controller.platform.*;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.pojo.reference.DefinitionRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.netty.DisposableServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 显式本机浏览器验收桥：真实 Spring、MVC、公共授权、H2 和回环供应商；固定测试会话，不验证生产 JWT。
 */
@EnabledIfSystemProperty(named = "model.browser.acceptance", matches = "true")
class PersonalModelBrowserAcceptanceTest {
    @Test
    void browserBridge() throws Exception {
        assertNull(System.getProperty("platform.test.mysql"), "浏览器验收只允许隔离 H2，不能配置外部数据库");
        PersonalModelPublicationIntegrationTest f = new PersonalModelPublicationIntegrationTest();
        f.setup();
        DisposableServer provider = reactor.netty.http.server.HttpServer.create().host("127.0.0.1").port(0)
                .route(routes -> routes.get("/v1/models", (request, response) -> {
                    assertEquals("Bearer fixture-personal-key", request.requestHeaders().get("Authorization"));
                    return response.header("Content-Type", "application/json").sendString(Mono.just("{\"data\":[{\"id\":\"deepseek-chat\"}]}"));
                }).post("/v1/chat/completions", (request, response) -> {
                    assertEquals("Bearer fixture-personal-key", request.requestHeaders().get("Authorization"));
                    return request.receive().aggregate().then(response.header("Content-Type", "text/event-stream").sendString(Mono.just(
                            "data: {\"id\":\"browser\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"场景验收回答\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\ndata: [DONE]\n\n")).then());
                })).bindNow();
        String origin = "http://127.0.0.1:" + provider.port();
        try {
            f.realGenerationContext().withPropertyValues("arte.ai-new.connections[0].endpoint=" + origin + "/v1",
                    "arte.ai-new-generation.allowed-origins[0]=" + origin).run(c -> {
                assertNull(c.getStartupFailure());
                DatabaseAiControlService control = c.getBean(DatabaseAiControlService.class);
                control.importConfiguration(f.f.f.f.actor, f.f.f.meta("browser-import"));
                control.ensure(f.f.f.f.actor, f.f.scope);
                f.policy(c, "127.0.0.1", true);
                f.f.f.f.jdbc.update("DELETE FROM arte_rbac_relation");
                PlatformAuthenticatedExecutor executor = new PlatformAuthenticatedExecutor(c.getBean(RbacIdentityProvider.class),
                        new PlatformPersistenceExecutor(c.getBean("newAiPersistenceScheduler", Scheduler.class), f.f.f.f.tx));
                DefaultWorkspaceServices services = c.getBean(DefaultWorkspaceServices.class);
                try (ManagementHttpHarness http = new ManagementHttpHarness(
                        new ModelResourceController(executor, c.getBean(ModelResourceService.class), c.getBean(ModelAclMigrationService.class)),
                        new ModelDraftController(executor, c.getBean(ModelDraftService.class), c.getBean(ModelConnectionValidator.class)),
                        new PersonalModelPublicationController(executor, c.getBean(PersonalModelPublicationService.class)),
                        new ResourceAccessController(executor, services.resources(), c.getBean("platformAuthorizationService", AuthorizationService.class)),
                        new WorkspaceAccessController(c.getBean(ReactiveWorkspaceOperations.class)))) {
                    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 15203), 0);
                    java.util.concurrent.ExecutorService requests = Executors.newFixedThreadPool(4);
                    server.setExecutor(requests);
                    server.createContext("/", exchange -> {
                        try {
                            String path = exchange.getRequestURI().getPath();
                            byte[] output;
                            int status = 200;
                            if (path.equals("/fixture/info")) {
                                output = f.f.f.f.json.write(Map.of("userId", "browser-user", "scope", f.f.scope)).getBytes(StandardCharsets.UTF_8);
                            } else if (path.equals("/fixture/discover")) {
                                output = f.f.f.f.json.write(c.getBean(ChatConfigurationQueryService.class).discover(f.f.execution(c, "browser-discover")).block(Duration.ofSeconds(15))).getBytes(StandardCharsets.UTF_8);
                            } else if (path.equals("/fixture/run")) {
                                String input = new String(exchange.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8);
                                DefinitionRef binding = f.f.f.f.json.read(input, DefinitionRef.class);
                                ModelResourceVersion published = f.f.f.f.tx.readOnly(() -> c.getBean(ModelResourceStore.class).findBinding(f.f.scope.tenantId(), binding).orElseThrow());
                                Invocation accepted = f.accept(c, published, 128);
                                MybatisExecutionPersistence executions = c.getBean(MybatisExecutionPersistence.class);
                                MybatisPayloadPersistence payloads = c.getBean(MybatisPayloadPersistence.class);
                                GenerationDispatcher dispatcher = new GenerationDispatcher(c.getBean(AdmissionAuthorization.class), c.getBean(CapabilityCatalog.class), c.getBean(BindingManager.class),
                                        c.getBean(PublishedAiCatalog.class), c.getBean(AiExecutionPolicyProvider.class), c.getBean(AiAdmissionAuthorizationGuard.class), executions, executions, executions, payloads, payloads, executions,
                                        c.getBean(ModelGateway.class), c.getBean(NewAiProperties.class), new NewAiExecutionProperties(true, false, 0, null, null, null, null), Clock.systemUTC(), null);
                                OutboxMessage message = executions.claim(OutboxMessage.Kind.DISPATCH, "browser-worker", Duration.ofSeconds(60), 1).block(Duration.ofSeconds(15)).getFirst();
                                dispatcher.dispatch(message, ExecutionRuntimeContext.start(accepted.request().context())).block(Duration.ofSeconds(20));
                                Invocation complete = executions.find(ExecutionOwners.from(accepted.request().context()), accepted.request().context().executionId()).block(Duration.ofSeconds(15));
                                assertEquals(Invocation.State.SUCCEEDED, complete.state());
                                output = f.f.f.f.json.write(Map.of("state", complete.state(), "invocationId", complete.request().context().executionId(),
                                        "settlement", f.f.f.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_settlement", String.class))).getBytes(StandardCharsets.UTF_8);
                            } else if (path.startsWith("/arte/") && exchange.getRequestMethod().equals("POST")) {
                                byte[] body = exchange.getRequestBody().readNBytes(65537);
                                if (body.length > 65536) {
                                    exchange.sendResponseHeaders(413, -1);
                                    return;
                                }
                                org.springframework.test.web.servlet.MvcResult result = http.execute(path.substring("/arte".length()), new String(body, StandardCharsets.UTF_8),
                                        exchange.getRequestHeaders().getFirst("Idempotency-Key"), ManagementHttpHarness.login(f.f.f.f.jdbc, 1, "alice"));
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
                    Path ready = Path.of("target/model-browser-ready");
                    Path stop = Path.of("target/model-browser-stop");
                    Files.deleteIfExists(stop);
                    server.start();
                    Files.writeString(ready, "127.0.0.1:15203");
                    try {
                        long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
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
            });
        } finally {
            provider.disposeNow();
            f.cleanup();
        }
    }
}
