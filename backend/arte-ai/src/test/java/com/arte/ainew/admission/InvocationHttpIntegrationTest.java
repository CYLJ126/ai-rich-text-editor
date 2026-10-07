package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.execution.*;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.pojo.execution.ExecutionCommands;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.Usage;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationEvent;
import com.arte.ainew.pojo.generation.GenerationSignal;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.ConversationHttpContext;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiChatController;
import com.arte.ainew.web.controller.NewAiConversationController;
import com.arte.ainew.web.controller.NewAiInvocationController;
import com.arte.ainew.web.request.InvocationRequests;
import com.arte.core.enums.ResultCodeEnum;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * MVC＋隔离 H2＋真实派发／结果／事件存储，网关仅提供测试信号，不访问外部模型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:24 ✾
 */
public class InvocationHttpIntegrationTest {
    private static final String STATUS = "/ai-new/invocation/getInvocationStatus";
    private static final String RESULT = "/ai-new/invocation/getInvocationResult";
    private static final String EVENTS = "/ai-new/invocation/invocationEvent";
    private static final Map<String, String> SCOPE = Map.of("tenantId", "tenant", "workspaceId", "workspace");
    private static final String OUTPUT = "你好 世界\n  保留空白  ";
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private JdbcTemplate jdbc;
    private Scheduler database;
    private Scheduler timer;
    private AdmissionFixture fixture;
    private InvocationDispatchWorker worker;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    private String mode = "success";

    @Before
    public void setup() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        database = Schedulers.newBoundedElastic(4, 128, "invocation-http-db");
        timer = Schedulers.newSingle("invocation-http-timer");
        fixture = new AdmissionFixture(dataSource, database);
        fixture.initializeBudget("alice");
        var authorization = authorization(fixture.properties, Clock.systemUTC());
        var settings = new NewAiExecutionProperties(true, false, 4, Duration.ofSeconds(1),
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofDays(1));
        var dispatcher = new GenerationDispatcher(authorization, fixture.catalog, fixture.executions, fixture.executions,
                fixture.executions, fixture.payloads, fixture.payloads, fixture.executions,
                call -> Flux.defer(() -> {
                    modelCalls.incrementAndGet();
                    boolean complete = mode.equals("success");
                    var usage = complete ? new Usage(Usage.Basis.PROVIDER_REPORTED, 3L, 2L, 5L) : Usage.unknown();
                    var result = new ModelResult("test-result", new ModelResult.ModelIdentity("test-provider", "test-model", null),
                            List.of(new ChatMessage("answer", ChatMessage.Role.ASSISTANT, List.of(new ChatMessage.Text(OUTPUT)), List.of(), null)),
                            complete ? ModelResult.FinishReason.STOP : ModelResult.FinishReason.LENGTH, complete, null, usage, List.of());
                    GenerationSignal terminal = mode.equals("unknown")
                            ? new GenerationSignal.Failure(new ExecutionError("STREAM_INTERRUPTED", ExecutionError.Phase.INVOCATION,
                            false, ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "safe-correlation"), usage, result)
                            : new GenerationSignal.Result(result);
                    return Flux.just(new GenerationSignal.Delta(new GenerationEvent.TextDelta(OUTPUT)), terminal);
                }), fixture.properties, settings, Clock.systemUTC());
        var coordinator = new DefaultInvocationCoordinator(fixture.coordinator, dispatcher);
        worker = new InvocationDispatchWorker(fixture.executions, fixture.executions, coordinator, settings, timer);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        configureMvc(fixture.properties, Clock.systemUTC());
        login("alice");
    }

    private AdmissionAuthorization authorization(NewAiProperties properties, Clock clock) {
        return new AdmissionAuthorization(new FixedExecutionAuthorizationResolver(properties), properties, clock);
    }

    private void configureMvc(NewAiProperties properties, Clock clock) {
        var factory = new ExecutionContextFactory(new FixedExecutionAuthorizationResolver(properties), clock);
        var http = new NewAiHttpContext(factory, properties);
        var authorization = authorization(properties, clock);
        mvc = standaloneSetup(
                new NewAiConversationController(fixture.conversations, new ConversationHttpContext(http, properties)),
                new NewAiChatController(fixture.chat, fixture.catalog, http, properties),
                new NewAiInvocationController(new DefaultExecutionControl(authorization, fixture.executions, fixture.coordinator),
                        new DefaultExecutionEventService(authorization, fixture.executions, fixture.executions, fixture.payloads), http, properties))
                .setControllerAdvice(new ConversationExceptionHandler()).setValidator(validator).setAsyncRequestTimeout(10000).build();
    }

    @After
    public void cleanup() {
        SecurityContextHolder.clearContext();
        worker.stop();
        validator.close();
        jdbc.execute("DROP ALL OBJECTS");
        database.dispose();
        timer.dispose();
    }

    private void login(String name) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, "unused", List.of()));
    }

    private JsonNode send(String path, Map<String, ?> request, int status) throws Exception {
        var result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "en")
                .content(json.writeValueAsString(request))).andReturn();
        return finish(result, status);
    }

    private JsonNode finish(MvcResult result, int status) throws Exception {
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(5000);
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertEquals(result.getResponse().getContentAsString(), status, result.getResponse().getStatus());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String submit() throws Exception {
        var created = mvc.perform(post("/ai-new/conversation/createConversation").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "create-" + UUID.randomUUID()).content(json.writeValueAsString(Map.of("scope", SCOPE, "title", "HTTP invocation")))).andReturn();
        var conversationId = finish(created, 200).path("data").path("conversationId").asString();
        var request = Map.of("scope", SCOPE, "conversationId", conversationId, "expectedVersion", 0,
                "text", "private user input", "capability", AdmissionFixture.CAP, "binding", AdmissionFixture.BINDING,
                "budgetRef", "alice-budget", "maxInputTokens", 768,
                "generationOptions", Map.of("maxOutputTokens", 128, "stopSequences", List.of()), "timeoutSeconds", 60);
        var accepted = mvc.perform(post("/ai-new/chat/turnsForChat").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "submit-" + UUID.randomUUID()).content(json.writeValueAsString(request))).andReturn();
        return finish(accepted, 202).path("data").path("invocationId").asString();
    }

    private Map<String, Object> query(String id) {
        var request = new LinkedHashMap<String, Object>();
        request.put("scope", SCOPE);
        request.put("invocationId", id);
        return request;
    }

    private Map<String, Object> replay(String id, long after, int limit) {
        var request = query(id);
        request.put("afterSequence", after);
        request.put("limit", limit);
        return request;
    }

    private void dispatch() {
        assertEquals(1, worker.pollOnce().block(Duration.ofSeconds(10)).intValue());
    }

    @Test
    public void acceptedThenCompletedInvocationIsQueriedWithoutExposingStoredRequestOrRedispatching() throws Exception {
        var id = submit();
        var accepted = send(STATUS, query(id), 200);
        assertEquals(ResultCodeEnum.SUCCESS.getDesc(Locale.ENGLISH), accepted.path("desc").asString());
        assertEquals("ACCEPTED", accepted.path("data").path("state").asString());
        assertFalse(accepted.path("data").path("resultAvailable").asBoolean());
        assertTrue(accepted.path("data").path("partial").isNull());
        assertEquals(0, modelCalls.get());
        dispatch();
        var status = send(STATUS, query(id), 200).path("data");
        assertEquals(id, status.path("invocationId").asString());
        assertEquals("GENERATION", status.path("kind").asString());
        assertEquals("SUCCEEDED", status.path("state").asString());
        assertTrue(status.path("resultAvailable").asBoolean());
        assertFalse(status.path("partial").asBoolean());
        assertFalse(status.path("conversation").path("turnId").asString().isBlank());
        for (var field : List.of("request", "context", "owner", "authorization", "requestDigest", "contextSnapshotId", "result")) {
            assertFalse(field, status.has(field));
        }
        assertFalse(status.toString().contains("private user input"));
        assertFalse(status.toString().contains("alice-budget"));
        var result = send(RESULT, query(id), 200).path("data");
        assertEquals(id, result.path("invocationId").asString());
        assertEquals("GENERATION", result.path("kind").asString());
        var model = result.path("result").path("value");
        assertTrue(model.path("complete").asBoolean());
        assertEquals(OUTPUT, model.path("outputs").get(0).path("content").get(0).path("text").asString());
        assertEquals(5, model.path("usage").path("totalTokens").asInt());
        send(RESULT, query(id), 200);
        send(EVENTS, replay(id, 0, 256), 200);
        assertEquals(1, modelCalls.get());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Long.class).longValue());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_settlement", Long.class).longValue());
    }

    @Test
    public void unavailableResultIsConflictAndOrphanBytesAreNotReturned() throws Exception {
        var id = submit();
        assertEquals(ResultCodeEnum.AI_RESULT_NOT_AVAILABLE.getCode(), send(RESULT, query(id), 409).path("code").asString());
        var owner = AdmissionFixture.owner("alice-id");
        var invocation = fixture.executions.find(owner, id).block();
        var attempt = fixture.executions.createAttempt(new ExecutionCommands.CreateAttempt(
                new ExecutionCommands.Version(owner, id, invocation.version()), "orphan-attempt", "test-worker", Duration.ofMinutes(1))).block().value();
        var result = new ModelResult("orphan", new ModelResult.ModelIdentity("test-provider", "test-model", null),
                List.of(new ChatMessage("orphan-answer", ChatMessage.Role.ASSISTANT, List.of(new ChatMessage.Text("uncommitted answer")), List.of(), null)),
                ModelResult.FinishReason.STOP, true, null, Usage.unknown(), List.of());
        assertTrue(fixture.payloads.put(owner, id, attempt.attemptId(), "orphan-result", new InvocationResult.Generation(result)).block().successful());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_result", Long.class).longValue());
        assertEquals(ResultCodeEnum.AI_RESULT_NOT_AVAILABLE.getCode(), send(RESULT, query(id), 409).path("code").asString());
        assertEquals(0, modelCalls.get());
        var status = send(STATUS, query(id), 200).path("data");
        assertEquals("RUNNING", status.path("state").asString());
        assertFalse(status.path("resultAvailable").asBoolean());
    }

    @Test
    public void incompleteOutputIsReadableAndNotReportedAsSuccess() throws Exception {
        mode = "incomplete";
        var id = submit();
        dispatch();
        var status = send(STATUS, query(id), 200).path("data");
        assertEquals("FAILED", status.path("state").asString());
        assertEquals("MODEL_OUTPUT_INCOMPLETE", status.path("error").path("code").asString());
        assertTrue(status.path("partial").asBoolean());
        assertFalse(send(RESULT, query(id), 200).path("data").path("result").path("value").path("complete").asBoolean());
    }

    @Test
    public void unknownOutcomePreservesPartialOutputAndUnknownUsage() throws Exception {
        mode = "unknown";
        var id = submit();
        dispatch();
        var status = send(STATUS, query(id), 200).path("data");
        assertEquals("UNKNOWN", status.path("state").asString());
        assertEquals("UNKNOWN", status.path("error").path("certainty").asString());
        assertTrue(status.path("partial").asBoolean());
        var result = send(RESULT, query(id), 200).path("data").path("result").path("value");
        assertFalse(result.path("complete").asBoolean());
        assertEquals("UNKNOWN", result.path("usage").path("basis").asString());
        assertTrue(result.path("usage").path("inputTokens").isNull());
        assertTrue(result.path("usage").path("outputTokens").isNull());
        send(STATUS, query(id), 200);
        assertEquals(1, modelCalls.get());
    }

    @Test
    public void eventPagesHaveExclusiveCursorsAndEmptyPageDoesNotEndInvocation() throws Exception {
        var id = submit();
        var first = send(EVENTS, replay(id, 0, 1), 200).path("data");
        assertEquals("ACCEPTED", first.path("events").get(0).path("kind").asString());
        assertEquals(1, first.path("nextCursor").path("afterSequence").asLong());
        assertEquals(id, first.path("nextCursor").path("executionId").asString());
        var empty = send(EVENTS, replay(id, 1, 1), 200).path("data");
        assertEquals(0, empty.path("events").size());
        assertEquals(1, empty.path("nextCursor").path("afterSequence").asLong());
        assertEquals("ACCEPTED", send(STATUS, query(id), 200).path("data").path("state").asString());
        dispatch();
        long after = 1;
        var kinds = new HashSet<String>();
        for (int i = 0; i < 8; i++) {
            var page = send(EVENTS, replay(id, after, 1), 200).path("data");
            if (page.path("events").isEmpty()) {
                assertEquals(after, page.path("nextCursor").path("afterSequence").asLong());
                break;
            }
            var event = page.path("events").get(0);
            assertTrue(event.path("sequence").asLong() > after);
            kinds.add(event.path("kind").asString());
            if (event.path("kind").asString().equals("OUTPUT")) {
                assertEquals(OUTPUT, event.path("payload").path("events").get(0).path("text").asString());
            }
            after = page.path("nextCursor").path("afterSequence").asLong();
        }
        assertEquals(java.util.Set.of("STARTED", "OUTPUT", "TERMINAL"), kinds);
        assertEquals(1, modelCalls.get());
    }

    @Test
    public void expiredCursorReturnsGoneRatherThanSkippingDiscardedEvents() throws Exception {
        var id = submit();
        dispatch();
        jdbc.update("UPDATE arte_ai_outbox SET delivered=1 WHERE kind='EVENT'");
        assertTrue(fixture.executions.discardThrough(AdmissionFixture.owner("alice-id"), id, 1).block().successful());
        assertEquals(ResultCodeEnum.AI_CURSOR_EXPIRED.getCode(), send(EVENTS, replay(id, 0, 100), 410).path("code").asString());
        assertEquals(1, send(EVENTS, replay(id, 1, 100), 200).path("data").path("retainedAfterSequence").asLong());
    }

    @Test
    public void foreignAndMissingInvocationsHaveTheSameNotFoundResponseAcrossAllReads() throws Exception {
        var id = submit();
        dispatch();
        login("bob");
        for (var path : List.of(STATUS, RESULT, EVENTS)) {
            var foreign = path.equals(EVENTS) ? replay(id, 0, 100) : query(id);
            var missing = path.equals(EVENTS) ? replay("missing", 0, 100) : query("missing");
            assertEquals(send(path, missing, 404), send(path, foreign, 404));
        }
    }

    @Test
    public void anonymousWrongSpaceAndMissingReadGrantAreRejected() throws Exception {
        var id = submit();
        for (var path : List.of(STATUS, RESULT, EVENTS)) {
            var request = path.equals(EVENTS) ? replay(id, 0, 100) : query(id);
            SecurityContextHolder.clearContext();
            send(path, request, 401);
            login("alice");
            request.put("scope", Map.of("tenantId", "tenant", "workspaceId", "other"));
            send(path, request, 403);
        }
        var original = fixture.properties;
        var grants = original.grants().stream().map(grant -> {
            var scopes = new HashSet<>(grant.scopes());
            scopes.remove(AdmissionAuthorization.READ);
            return new NewAiProperties.Grant(grant.subjectName(), grant.subjectId(), grant.principalKind(), grant.tenantId(),
                    grant.workspaceId(), grant.grantRef(), grant.enabled(), scopes, grant.bindingIds(), grant.budgetRefs());
        }).toList();
        var restricted = new NewAiProperties(original.enabled(), original.dataSourceBean(), original.releaseRef(), original.persistence(),
                original.limits(), grants, original.capabilities(), original.bindings(), original.connections(), original.rates(), original.budgets());
        configureMvc(restricted, Clock.systemUTC());
        for (var path : List.of(STATUS, RESULT, EVENTS)) {
            send(path, path.equals(EVENTS) ? replay(id, 0, 100) : query(id), 403);
        }
        assertEquals(0, modelCalls.get());
    }

    @Test
    public void readingNeedsOnlyReadPermissionRatherThanInvokeOrConversationPermission() throws Exception {
        var id = submit();
        dispatch();
        var original = fixture.properties;
        var grants = original.grants().stream().map(grant -> new NewAiProperties.Grant(grant.subjectName(), grant.subjectId(),
                grant.principalKind(), grant.tenantId(), grant.workspaceId(), grant.grantRef(), grant.enabled(),
                java.util.Set.of(AdmissionAuthorization.READ), grant.bindingIds(), grant.budgetRefs())).toList();
        var readOnly = new NewAiProperties(original.enabled(), original.dataSourceBean(), original.releaseRef(), original.persistence(),
                original.limits(), grants, original.capabilities(), original.bindings(), original.connections(), original.rates(), original.budgets());
        configureMvc(readOnly, Clock.systemUTC());
        send(STATUS, query(id), 200);
        send(RESULT, query(id), 200);
        send(EVENTS, replay(id, 0, 100), 200);
    }

    @Test
    public void nullFieldsMalformedJsonAndCursorBoundsAreBadRequests() throws Exception {
        for (var path : List.of(STATUS, RESULT, EVENTS)) {
            var original = path.equals(EVENTS) ? replay("id", 0, 100) : query("id");
            for (var field : original.keySet()) {
                var invalid = new LinkedHashMap<>(original);
                invalid.put(field, null);
                send(path, invalid, 400);
            }
            var invalid = new LinkedHashMap<>(original);
            invalid.put("invocationId", " ");
            send(path, invalid, 400);
            invalid.put("invocationId", "a".repeat(257));
            send(path, invalid, 400);
            invalid = new LinkedHashMap<>(original);
            invalid.put("scope", Map.of("tenantId", " ", "workspaceId", "workspace"));
            send(path, invalid, 400);
            finish(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{")).andReturn(), 400);
        }
        send(EVENTS, replay("id", -1, 100), 400);
        send(EVENTS, replay("id", 0, 0), 400);
        send(EVENTS, replay("id", 0, 257), 400);
        assertEquals(0, modelCalls.get());
    }

    @Test
    public void currentReadContextWorksAfterOriginalInvocationDeadline() throws Exception {
        var id = submit();
        dispatch();
        var afterDeadline = Clock.offset(Clock.systemUTC(), Duration.ofHours(1));
        configureMvc(fixture.properties, afterDeadline);
        send(STATUS, query(id), 200);
        send(RESULT, query(id), 200);
        send(EVENTS, replay(id, 0, 100), 200);
    }

    @Test
    public void committedButMissingResultBytesRemainAnInfrastructureError() throws Exception {
        var id = submit();
        dispatch();
        jdbc.update("DELETE FROM arte_ai_result");
        var error = send(RESULT, query(id), 500);
        assertFalse(error.path("success").asBoolean());
        assertFalse(error.toString().contains("Committed result bytes are missing"));
        assertTrue(send(STATUS, query(id), 200).path("data").path("resultAvailable").asBoolean());
    }

    @Test
    public void identityIsCapturedBeforeAsyncReadAndControllerDeclaresAuthentication() throws Exception {
        var id = submit();
        var pending = mvc.perform(post(STATUS).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(query(id)))).andReturn();
        SecurityContextHolder.clearContext();
        assertEquals(id, finish(pending, 200).path("data").path("invocationId").asString());
        for (var name : List.of("getInvocationStatus", "getInvocationResult", "invocationEvent")) {
            var requestClass = name.equals("invocationEvent") ? InvocationRequests.Replay.class : InvocationRequests.Query.class;
            var method = NewAiInvocationController.class.getMethod(name, requestClass, Locale.class);
            var authorization = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
            if (authorization == null) {
                authorization = AnnotatedElementUtils.findMergedAnnotation(NewAiInvocationController.class, PreAuthorize.class);
            }
            assertNotNull(authorization);
            assertEquals("isAuthenticated()", authorization.value());
        }
    }

    @Test
    public void controllerIsAbsentUnlessBothAdmissionAndExecutionAreEnabled() {
        for (var properties : List.of(Map.<String, Object>of(), Map.<String, Object>of("arte.ai-new.enabled", "true"),
                Map.<String, Object>of("arte.ai-new-execution.enabled", "true"),
                Map.<String, Object>of("arte.ai-new.enabled", "true", "arte.ai-new-execution.enabled", "false"))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("flags", properties));
                context.register(NewAiInvocationController.class);
                context.refresh();
                assertTrue(context.getBeansOfType(NewAiInvocationController.class).isEmpty());
            }
        }
    }
}
