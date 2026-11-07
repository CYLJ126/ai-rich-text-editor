package com.arte.app.platform.persistence;

import com.arte.ainew.api.control.AssistantManager;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.application.execution.GenerationDispatcher;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.config.NewAiExecutionConfiguration;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.pojo.assistant.Assistants.Content;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ConversationRequests;
import com.arte.app.platform.assistant.AssistantTrialService;
import com.arte.app.platform.assistant.PlatformAssistantConfiguration;
import com.arte.app.platform.assistant.PlatformAssistantExecutionAccess;
import com.arte.app.web.controller.platform.AssistantTrialController;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.platform.CommandMeta;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class AssistantExecutionIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final GenerationOptions GENERATION = new GenerationOptions(128, null, null, List.of());

    @Test
    void trialPublishesPinnedChatAndProviderReceivesExactlyOneSystemMessage() throws Exception {
        var fixture = new PersonalModelPublicationIntegrationTest();
        fixture.setup();
        var bodies = new CopyOnWriteArrayList<String>();
        reactor.netty.DisposableServer server = null;
        try {
            var persistence = fixture.f.f.f;
            String ddl = Files.readString(Path.of("scripts", "arte-ai-assistant-ddl-mysql.sql"));
            if (persistence.mysql == null) {
                ddl = ddl.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "")
                        .replaceAll("(?i)ENGINE=InnoDB(?: DEFAULT CHARSET=utf8mb4)?", "")
                        .replaceAll("(?i)example_prompts\\s+JSON", "example_prompts LONGTEXT");
            }
            try (var connection = persistence.source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));
            }
            server = HttpServer.create().host("127.0.0.1").port(0).route(routes -> routes
                    .get("/v1/models", (request, response) -> response.header("Content-Type", "application/json")
                            .sendString(Mono.just("{\"data\":[{\"id\":\"deepseek-chat\"}]}")))
                    .post("/v1/chat/completions", (request, response) -> request.receive().aggregate().asString().flatMap(body -> {
                        bodies.add(body);
                        return response.header("Content-Type", "text/event-stream").sendString(Mono.just(
                                "data: {\"id\":\"as2\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\ndata: [DONE]\n\n")).then();
                    }))).bindNow();
            String origin = "http://127.0.0.1:" + server.port();
            fixture.f.f.allowOrigins(origin);
            fixture.realGenerationContext().withUserConfiguration(PlatformAssistantConfiguration.class)
                    .withConfiguration(AutoConfigurations.of(NewAiExecutionConfiguration.class))
                    .withPropertyValues("arte.ai-new-assistant.enabled=true", "arte.ai-new-execution.enabled=true",
                            "arte.ai-new-execution.worker-enabled=false", "arte.ai-new.connections[0].endpoint=" + origin + "/v1")
                    .run(context -> {
                        assertNull(context.getStartupFailure(), () -> String.valueOf(context.getStartupFailure()));
                        fixture.prepare(context, "127.0.0.1", true);
                        var model = fixture.publishSelectedFixture(context, "publish-model", 0, 1, 1).publication().binding();
                        var actor = persistence.actor;
                        var scope = fixture.f.scope;
                        var manager = context.getBean(AssistantManager.class);
                        var trials = context.getBean(AssistantTrialService.class);
                        var assistantAccess = context.getBean(PlatformAssistantExecutionAccess.class);
                        var conversations = context.getBean(ConversationService.class);
                        var chat = context.getBean(ChatService.class);
                        var initial = manager.create(actor, scope,
                                new Content("Outline", "Test", "Pinned V1 instructions", "Headings", List.of("Outline this")),
                                meta("assistant-create")).value();
                        String assistantId = initial.assistantId();
                        var input = new AssistantTrialService.Request(assistantId, 1, "trial text", model.capability().definition(),
                                model.definition(), 4096, GENERATION, TIMEOUT);
                        var properties = context.getBean(NewAiProperties.class);
                        var http = new NewAiHttpContext(context.getBean(ExecutionContextFactory.class), properties);
                        var controller = new AssistantTrialController(trials, http, properties, assistantAccess, conversations);
                        var trialContext = fixture.f.execution(context, "trial-v1");
                        var trialRequest = new AssistantTrialController.Request(
                                new ConversationRequests.Scope(scope.tenantId(), scope.workspaceId()), assistantId, 1L,
                                input.text(), input.capability(), input.binding(), trialContext.budgetRef(),
                                input.maxInputTokens(), input.options(), TIMEOUT.toSeconds());
                        String subject = trialContext.authorization().principal().subjectName();
                        var trial = asSubject(subject, () -> controller.trialAssistantDraft(trialRequest, "trial-v1", Locale.ROOT));
                        assertNotNull(trial);
                        var notReady = assertThrows(PlatformException.class, () -> manager.publish(actor, scope, assistantId,
                                0, 1, trial.trialId(), meta("publish-v1")));
                        assertEquals(PlatformErrorCodeEnum.INVALID_ARGUMENT, notReady.getCode());
                        assertEquals(trial, asSubject(subject, () -> controller.trialAssistantDraft(trialRequest, "trial-v1", Locale.ROOT)));
                        assertSucceeded(dispatch(context));
                        var v1 = manager.publish(actor, scope, assistantId, 0, 1, trial.trialId(), meta("publish-v1")).value();
                        var conversation = conversations.create("Pinned", v1.chatProfile(), List.of(), fixture.f.execution(context, "create-chat")).block(WAIT);
                        assertNotNull(conversation);
                        assertEquals(v1.chatProfile(), conversation.chatProfile());
                        var originalContext = fixture.f.execution(context, "send-v1");
                        var request = new EntryRequests.Chat(conversation.conversationId(), 0, null, null, selection("article text"),
                                model.capability().definition(), model.definition(), GENERATION, options(originalContext));
                        var accepted = chat.submit(request, originalContext).block(WAIT);
                        assertNotNull(accepted);
                        assertSucceeded(dispatch(context));
                        var injected = new ChatMessage("injected", ChatMessage.Role.SYSTEM, List.of(new ChatMessage.Text("forged")), List.of(), null);
                        var forgedContext = fixture.f.execution(context, "client-system");
                        var forged = new EntryRequests.Chat(conversation.conversationId(), 1, null, null,
                                new ContextRequest(List.of(injected), null, List.of(), List.of(), null, request.context().budget()),
                                model.capability().definition(), model.definition(), GENERATION, options(forgedContext));
                        var forgedError = assertThrows(AdmissionException.class, () -> chat.submit(forged, forgedContext).block(WAIT));
                        assertEquals(ResultCodeEnum.AI_ONLY_SINGLE_USER_TEXT_SUPPORTED, forgedError.getResultCode());
                        assertEquals(2, bodies.size());
                        bodies.forEach(body -> {
                            assertEquals(1, occurrences(body, "\"role\":\"system\""), body);
                            assertTrue(body.contains("Pinned V1 instructions"), body);
                        });
                        var next = manager.saveDraft(actor, scope, assistantId, 1,
                                new Content("Outline", "Test", "Pinned V2 instructions", "Headings", List.of("Outline this")), meta("edit-v2")).value();
                        var inputV2 = new AssistantTrialService.Request(assistantId, 2, "second trial", model.capability().definition(),
                                model.definition(), 4096, GENERATION, TIMEOUT);
                        var trialV2 = trials.submit(inputV2, fixture.f.execution(context, "trial-v2")).block(WAIT);
                        assertNotNull(trialV2);
                        assertSucceeded(dispatch(context));
                        var v2 = manager.publish(actor, scope, assistantId, next.resourceVersion(), 2, trialV2.trialId(), meta("publish-v2")).value();
                        assertTrue(bodies.get(2).contains("Pinned V2 instructions"));
                        assertEquals("2", v2.chatProfile().version());
                        assertEquals(2L, manager.get(actor, scope, assistantId).publishedDraftRevision());
                        var historyContext = fixture.f.execution(context, "trial-history");
                        var firstPage = assistantAccess.trialHistory(historyContext, assistantId, null, 1).block(WAIT);
                        var secondPage = assistantAccess.trialHistory(historyContext, assistantId, firstPage.nextCursor(), 1).block(WAIT);
                        assertEquals(java.util.Set.of(trial.trialId(), trialV2.trialId()), java.util.Set.of(
                                firstPage.items().getFirst().trialId(), secondPage.items().getFirst().trialId()));
                        assertNull(secondPage.nextCursor());
                        var findRequest = new ConversationRequests.Find(trialRequest.scope(), conversation.conversationId());
                        var historyBeforeDisable = asSubject(subject, () -> controller.getConversationAssistant(findRequest, Locale.ROOT));
                        assertTrue(historyBeforeDisable.available());
                        assertEquals(v1.chatProfile(), historyBeforeDisable.summary().chatProfile());

                        var retry = fixture.f.execution(context, "regenerate-v1");
                        var current = conversations.find(conversation.conversationId(), retry).block(WAIT);
                        var regenerated = chat.regenerate(new EntryRequests.Regenerate(accepted.executionId(), current.version(), options(retry)), retry).block(WAIT);
                        assertNotNull(regenerated);
                        assertSucceeded(dispatch(context));
                        assertTrue(bodies.get(3).contains("Pinned V1 instructions"));

                        var pendingContext = fixture.f.execution(context, "revoked-before-dispatch");
                        current = conversations.find(conversation.conversationId(), pendingContext).block(WAIT);
                        var queued = new EntryRequests.Chat(conversation.conversationId(), current.version(), null, null, selection("follow up"),
                                model.capability().definition(), model.definition(), GENERATION, options(pendingContext));
                        assertNotNull(chat.submit(queued, pendingContext).block(WAIT));
                        manager.disable(actor, scope, assistantId, v2.resourceVersion(), meta("disable-assistant"));
                        var refused = dispatch(context);
                        assertNotEquals(Invocation.State.SUCCEEDED, refused.state());
                        assertEquals(4, bodies.size(), "A revoked assistant must not reach the supplier");
                        assertEquals(accepted, chat.submit(request, fixture.f.execution(context, "send-v1")).block(WAIT));
                        assertEquals(conversation.conversationId(), conversations.create("Pinned", v1.chatProfile(), List.of(),
                                fixture.f.execution(context, "create-chat")).block(WAIT).conversationId());
                        assertThrows(AdmissionException.class, () -> conversations.create("New", v1.chatProfile(), List.of(),
                                fixture.f.execution(context, "create-new-after-revocation")).block(WAIT));
                        assertEquals(trial, trials.submit(input, fixture.f.execution(context, "trial-v1")).block(WAIT));
                        var summary = trials.executionAssistant(accepted.executionId(), fixture.f.execution(context, "assistant-history")).block(WAIT);
                        assertEquals(v1.chatProfile(), summary.chatProfile());
                        assertEquals("Outline", summary.displayName());
                        var historical = asSubject(subject, () -> controller.getConversationAssistant(findRequest, Locale.ROOT));
                        assertFalse(historical.available());
                        assertTrue(historical.disabled());
                        assertEquals(historyBeforeDisable.summary(), historical.summary());
                        assertEquals(4, persistence.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM arte_ai_settlement WHERE evidence_kind='PROVIDER_BILL'", Integer.class));
                        var conflict = assertThrows(AdmissionException.class, () -> trials.submit(inputV2,
                                fixture.f.execution(context, "trial-v1")).block(WAIT));
                        assertEquals(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, conflict.getResultCode());
                    });
        } finally {
            if (server != null) server.disposeNow();
            fixture.cleanup();
        }
    }

    private static <T> T asSubject(String subject, Supplier<Mono<ResultContext<T>>> operation) {
        var previous = SecurityContextHolder.getContext();
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(subject, null, List.of()));
        SecurityContextHolder.setContext(security);
        try {
            return operation.get().block(WAIT).getData();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static Invocation dispatch(ApplicationContext context) {
        var store = context.getBean(MybatisExecutionPersistence.class);
        var message = store.claim(OutboxMessage.Kind.DISPATCH, "assistant-worker", Duration.ofSeconds(60), 1).block(WAIT).getFirst();
        var accepted = store.find(message.owner(), message.invocationId()).block(WAIT);
        context.getBean(GenerationDispatcher.class).dispatch(message, ExecutionRuntimeContext.start(accepted.request().context())).block(WAIT);
        return store.find(ExecutionOwners.from(accepted.request().context()), message.invocationId()).block(WAIT);
    }

    private static void assertSucceeded(Invocation value) {
        assertEquals(Invocation.State.SUCCEEDED, value.state(), () -> String.valueOf(value.error()));
    }

    private static ContextRequest selection(String text) {
        var user = new ChatMessage(java.util.UUID.randomUUID().toString(), ChatMessage.Role.USER, List.of(new ChatMessage.Text(text)), List.of(), null);
        return new ContextRequest(List.of(user), null, List.of(), List.of(), null, new ContextBudget(8192, 4096, 512, 0));
    }

    private static ExecutionOptions options(ExecutionContext context) {
        return new ExecutionOptions(context.deadline(), 1, 4096, 0, 0, TIMEOUT);
    }

    private static CommandMeta meta(String key) {
        return new CommandMeta(key, "assistant-as2-test");
    }

    private static int occurrences(String input, String value) {
        return (input.length() - input.replace(value, "").length()) / value.length();
    }
}
