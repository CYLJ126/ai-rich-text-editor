package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.execution.*;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiEventProperties;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationEvent;
import com.arte.ainew.pojo.generation.GenerationSignal;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.spi.persistence.ExecutionEventStore;
import com.arte.ainew.spi.persistence.ExecutionOutboxStore;
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
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final com.arte.ainew.application.execution.LiveTextNotifier liveText = new com.arte.ainew.application.execution.LiveTextNotifier();
    private volatile reactor.core.publisher.Sinks.One<Void> outputGate;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private JdbcTemplate jdbc;
    private Scheduler database;
    private Scheduler timer;
    private AdmissionFixture fixture;
    private InvocationDispatchWorker worker;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    private String mode = "success";
    private reactor.core.publisher.Sinks.Many<GenerationSignal> controlled;
    private final CountDownLatch gatewaySubscribed = new CountDownLatch(1);
    private LocalExecutionEventNotifier notifier;
    private ExecutionEventPublisher publisher;
    private DefaultExecutionEventService eventService;
    private final AtomicInteger wakeups = new AtomicInteger();

    @Before
    public void setup() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        database = Schedulers.newBoundedElastic(4, 128, "invocation-http-db");
        timer = Schedulers.newSingle("invocation-http-timer");
        notifier = new LocalExecutionEventNotifier();
        fixture = new AdmissionFixture(dataSource, database, new JacksonExecutionRecordCodec(), AdmissionFixture.properties(),
                () -> {
                    wakeups.incrementAndGet();
                    notifier.wakePublisher();
                });
        fixture.initializeBudget("alice");
        var authorization = authorization(fixture.properties, Clock.systemUTC());
        var settings = new NewAiExecutionProperties(true, false, 4, Duration.ofSeconds(1),
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofDays(1));
        var previewStore = new com.arte.ainew.spi.persistence.ExecutionEventStore() {
            public Mono<StoreOutcome<List<ExecutionEvent<?>>>> appendBatch(ExecutionCommands.Append command) {
                return Mono.defer(() -> (outputGate == null ? Mono.<Void>empty() : outputGate.asMono())
                        .then(fixture.executions.appendBatch(command)));
            }

            public Mono<StoreOutcome<Page>> replay(ExecutionOwner owner, ExecutionEvent.Cursor cursor, int limit) {
                return fixture.executions.replay(owner, cursor, limit);
            }

            public Mono<StoreOutcome<ExecutionEvent.Cursor>> discardThrough(ExecutionOwner owner, String id, long sequence) {
                return fixture.executions.discardThrough(owner, id, sequence);
            }
        };
        var dispatcher = new GenerationDispatcher(authorization, fixture.catalog, fixture.executions, previewStore,
                fixture.executions, fixture.payloads, fixture.payloads, fixture.executions,
                call -> Flux.defer(() -> {
                    modelCalls.incrementAndGet();
                    if (mode.equals("controlled")) {
                        gatewaySubscribed.countDown();
                        return controlled.asFlux();
                    }
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
                }), fixture.properties, settings, Clock.systemUTC(), liveText);
        var coordinator = new DefaultInvocationCoordinator(fixture.coordinator, dispatcher);
        publisher = new ExecutionEventPublisher(fixture.executions, notifier, settings, timer);
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
        eventService = new DefaultExecutionEventService(authorization, fixture.executions, fixture.executions, fixture.payloads, notifier, clock,
                new InvocationBudgetStatusResolver(fixture.executions, fixture.executions), liveText);
        mvc = standaloneSetup(
                new NewAiConversationController(fixture.conversations, new ConversationHttpContext(http, properties)),
                new NewAiChatController(fixture.chat, fixture.catalog, http, properties),
                new NewAiInvocationController(new DefaultExecutionControl(authorization, fixture.executions, fixture.coordinator),
                        eventService, http, properties, new InvocationBudgetStatusResolver(fixture.executions, fixture.executions)))
                .setControllerAdvice(new ConversationExceptionHandler()).setValidator(validator).setAsyncRequestTimeout(10000).build();
    }

    @After
    public void cleanup() {
        SecurityContextHolder.clearContext();
        worker.stop();
        publisher.stop();
        validator.close();
        jdbc.execute("DROP ALL OBJECTS");
        database.dispose();
        timer.dispose();
    }

    private void login(String name) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, "unused", List.of()));
    }

    private JsonNode send(String path, Map<String, ?> request, int status) throws Exception {
        var result = mvc.perform(post(path).accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "en")
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
    public void idleWatchReauthorizesAndRevocationEndsItWithoutPeriodicEventQueries() throws Exception {
        var id = submit();
        var revoked = new AtomicBoolean();
        var reads = new AtomicInteger();
        var resolver = new FixedExecutionAuthorizationResolver(fixture.properties);
        var authorization = new AdmissionAuthorization((name, tenant, workspace, scopes) -> Mono.defer(() ->
                revoked.get() ? Mono.error(new AccessDeniedException("revoked")) : resolver.resolve(name, tenant, workspace, scopes)),
                fixture.properties, Clock.systemUTC());
        ExecutionEventStore tracked = new ExecutionEventStore() {
            @Override
            public Mono<StoreOutcome<List<ExecutionEvent<?>>>> appendBatch(ExecutionCommands.Append command) {
                return fixture.executions.appendBatch(command);
            }

            @Override
            public Mono<StoreOutcome<Page>> replay(ExecutionOwner owner, ExecutionEvent.Cursor cursor, int limit) {
                reads.incrementAndGet();
                return fixture.executions.replay(owner, cursor, limit);
            }

            @Override
            public Mono<StoreOutcome<ExecutionEvent.Cursor>> discardThrough(ExecutionOwner owner, String invocationId, long through) {
                return fixture.executions.discardThrough(owner, invocationId, through);
            }
        };
        var service = new DefaultExecutionEventService(authorization, fixture.executions, tracked, fixture.payloads, notifier, Clock.systemUTC());
        var first = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var errors = new CopyOnWriteArrayList<Throwable>();
        var subscription = service.watch(new ExecutionEvent.Cursor(id, 0), fixture.context("alice", "revocable-watch"))
                .subscribe(event -> first.countDown(), error -> {
                    errors.add(error);
                    ended.countDown();
                });
        try {
            assertTrue(first.await(5, TimeUnit.SECONDS));
            var message = fixture.executions.claim(com.arte.ainew.pojo.execution.OutboxMessage.Kind.EVENT,
                    "duplicate-publisher", Duration.ofMinutes(1), 1).block().getFirst();
            notifier.publish(message);
            notifier.publish(message);
            revoked.set(true);
            assertTrue(ended.await(13, TimeUnit.SECONDS));
            assertEquals(1, errors.size());
            assertTrue(errors.getFirst() instanceof AccessDeniedException);
            assertEquals(1, reads.get());
        } finally {
            subscription.dispose();
        }
    }

    @Test
    public void eventTransactionRollbackDoesNotWakePublisherOrExposeRunningState() throws Exception {
        var id = submit();
        jdbc.execute("ALTER TABLE arte_ai_outbox ADD CONSTRAINT reject_new_events CHECK (sequence_no < 2)");
        var invocation = fixture.executions.find(AdmissionFixture.owner("alice-id"), id).block();
        assertThrows(RuntimeException.class, () -> fixture.executions.createAttempt(new ExecutionCommands.CreateAttempt(
                new ExecutionCommands.Version(AdmissionFixture.owner("alice-id"), id, invocation.version()),
                "rollback-attempt", "worker", Duration.ofMinutes(1))).block());
        assertEquals(1, wakeups.get());
        assertEquals("ACCEPTED", fixture.executions.find(AdmissionFixture.owner("alice-id"), id).block().state().name());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Long.class).longValue());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_event", Long.class).longValue());
    }

    @Test
    public void slowWatchReceivesBudgetSettlementBeforeStreamCompletes() throws Exception {
        var id = submit();
        dispatch();
        var events = eventService.watch(new ExecutionEvent.Cursor(id, 0), fixture.context("alice", "slow-budget"))
                .delayElements(Duration.ofMillis(30)).collectList().block(Duration.ofSeconds(5));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), events.stream().map(ExecutionEvent::sequence).toList());
        assertEquals(new ExecutionPayload.BudgetChanged(com.arte.ainew.pojo.execution.InvocationBudgetState.SETTLED, 1, 2), events.getLast().payload());
    }

    @Test
    public void alreadyDeliveredTerminalCursorClosesWithoutWaitingForLease() throws Exception {
        var id = submit();
        dispatch();
        assertTrue(eventService.watch(new ExecutionEvent.Cursor(id, 6), fixture.context("alice", "caught-up"))
                .collectList().block(Duration.ofSeconds(2)).isEmpty());
    }

    @Test
    public void sseReplaysCommittedTextAndKeepsOtherPayloadsPrivate() throws Exception {
        var id = submit();
        dispatch();
        var request = query(id);
        request.put("afterSequence", 0);
        var first = mvc.perform(post("/ai-new/invocation/watchInvocation")
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request))).andReturn();
        first.getAsyncResult(5000);
        var stream = mvc.perform(asyncDispatch(first)).andReturn();
        if (stream.getRequest().isAsyncStarted()) {
            stream.getAsyncResult(5000);
            stream = mvc.perform(asyncDispatch(stream)).andReturn();
        }
        assertEquals(200, stream.getResponse().getStatus());
        assertTrue(stream.getResponse().getContentType().startsWith("text/event-stream"));
        assertTrue(stream.getResponse().getContentType().toLowerCase(java.util.Locale.ROOT).contains("charset=utf-8"));
        assertEquals("no", stream.getResponse().getHeader("X-Accel-Buffering"));
        var body = stream.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(body, body.contains("\\u4f60"));
        assertTrue(body, body.contains("event:invocation"));
        assertTrue(body, body.contains("TERMINAL"));
        assertFalse(body.contains("private user input"));
        var notifications = Arrays.stream(body.split("\n")).filter(line -> line.startsWith("data:"))
                .map(line -> json.readTree(line.substring(5))).toList();
        assertEquals(OUTPUT, notifications.stream().filter(value -> value.path("kind").asString().equals("OUTPUT"))
                .map(value -> value.path("text").asString()).reduce("", String::concat));
        assertTrue(notifications.stream().filter(value -> !value.path("kind").asString().equals("OUTPUT"))
                .noneMatch(value -> value.has("text")));
        assertFalse(body.contains("authorization"));
        assertEquals(1, modelCalls.get());
    }

    @Test
    public void liveTextReachesBrowserWhileOutputTransactionAndEventPublisherAreBlocked() throws Exception {
        mode = "controlled";
        controlled = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        outputGate = reactor.core.publisher.Sinks.one();
        var id = submit();
        var request = query(id);
        request.put("afterSequence", 0);
        var response = mvc.perform(post("/ai-new/invocation/watchInvocation")
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request))).andReturn();
        response.getAsyncResult(5000);
        var stream = mvc.perform(asyncDispatch(response)).andReturn();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (liveText.subscriberCount() == 0 && System.nanoTime() < end) Thread.sleep(5);
        assertEquals(1, liveText.subscriberCount());
        var work = worker.pollOnce().toFuture();
        try {
            assertTrue(gatewaySubscribed.await(5, TimeUnit.SECONDS));
            controlled.tryEmitNext(new GenerationSignal.Delta(new GenerationEvent.TextDelta("你")));
            controlled.tryEmitNext(new GenerationSignal.Delta(new GenerationEvent.TextDelta(OUTPUT.substring(1))));
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!stream.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("保留空白")
                    && System.nanoTime() < end) Thread.sleep(5);
            var body = stream.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body, body.contains("event:text-delta"));
            assertTrue(body, body.contains("保留空白"));
            assertTrue(body, body.contains("\"offset\":1"));
            assertFalse(body, body.contains("\"kind\":\"OUTPUT\""));
            assertFalse(work.isDone());
            assertTrue(fixture.executions.replay(AdmissionFixture.owner("alice-id"), new ExecutionEvent.Cursor(id, 0), 256).block()
                    .value().events().stream().noneMatch(event -> event.kind() == ExecutionEvent.Kind.OUTPUT));
            outputGate.tryEmitEmpty();
            var model = new ModelResult("controlled", new ModelResult.ModelIdentity("test-provider", "test-model", null),
                    List.of(new ChatMessage("answer", ChatMessage.Role.ASSISTANT, List.of(new ChatMessage.Text(OUTPUT)), List.of(), null)),
                    ModelResult.FinishReason.STOP, true, null, new Usage(Usage.Basis.PROVIDER_REPORTED, 3L, 2L, 5L), List.of());
            controlled.tryEmitNext(new GenerationSignal.Result(model));
            controlled.tryEmitComplete();
            assertEquals(1, work.get(5, TimeUnit.SECONDS).intValue());
            publisher.pollOnce().block();
            stream.getAsyncResult(5000);
            mvc.perform(asyncDispatch(stream)).andReturn();
            assertEquals(0, liveText.subscriberCount());
            assertEquals("SUCCEEDED", send(STATUS, query(id), 200).path("data").path("state").asString());
            assertEquals(1, modelCalls.get());
        } finally {
            outputGate.tryEmitEmpty();
            work.cancel(true);
        }
    }

    @Test
    public void sseShowsFirstAndSparseTextBeforeModelFinishesAndReplaysBySequence() throws Exception {
        mode = "controlled";
        controlled = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        var id = submit();
        var owner = AdmissionFixture.owner("alice-id");
        var request = query(id);
        request.put("afterSequence", 0);
        var response = mvc.perform(post("/ai-new/invocation/watchInvocation")
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request))).andReturn();
        response.getAsyncResult(5000);
        var stream = mvc.perform(asyncDispatch(response)).andReturn();
        var work = worker.pollOnce().toFuture();
        assertTrue(gatewaySubscribed.await(5, TimeUnit.SECONDS));
        controlled.tryEmitNext(new GenerationSignal.Delta(new GenerationEvent.TextDelta("你")));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<ExecutionEvent<?>> outputs = List.of();
        while (outputs.isEmpty() && System.nanoTime() < end) {
            outputs = fixture.executions.replay(owner, new ExecutionEvent.Cursor(id, 0), 256).block().value().events().stream()
                    .filter(event -> event.kind() == ExecutionEvent.Kind.OUTPUT).toList();
            if (outputs.isEmpty()) Thread.sleep(10);
        }
        assertEquals(1, outputs.size());
        assertFalse(work.isDone());
        publisher.pollOnce().block();
        end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!stream.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("\"text\":\"你\"") && System.nanoTime() < end)
            Thread.sleep(10);
        assertTrue(stream.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("\"text\":\"你\""));
        assertFalse(stream.getResponse().getContentAsString().contains("TERMINAL"));
        controlled.tryEmitNext(new GenerationSignal.Delta(new GenerationEvent.TextDelta(OUTPUT.substring(1))));
        // 未达到 32 条，也未结束模型流，必须由时间阈值提交第二段。
        end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (outputs.size() < 2 && System.nanoTime() < end) {
            outputs = fixture.executions.replay(owner, new ExecutionEvent.Cursor(id, 0), 256).block().value().events().stream()
                    .filter(event -> event.kind() == ExecutionEvent.Kind.OUTPUT).toList();
            if (outputs.size() < 2) Thread.sleep(10);
        }
        assertEquals(2, outputs.size());
        assertFalse(work.isDone());
        var replay = eventService.watch(new ExecutionEvent.Cursor(id, outputs.getFirst().sequence()), fixture.context("alice", "resume"))
                .filter(event -> event.kind() == ExecutionEvent.Kind.OUTPUT).next().block(Duration.ofSeconds(3));
        assertEquals(outputs.getLast().sequence(), replay.sequence());
        var model = new ModelResult("controlled", new ModelResult.ModelIdentity("test-provider", "test-model", null),
                List.of(new ChatMessage("answer", ChatMessage.Role.ASSISTANT, List.of(new ChatMessage.Text(OUTPUT)), List.of(), null)),
                ModelResult.FinishReason.STOP, true, null, new Usage(Usage.Basis.PROVIDER_REPORTED, 3L, 2L, 5L), List.of());
        controlled.tryEmitNext(new GenerationSignal.Result(model));
        controlled.tryEmitComplete();
        assertEquals(1, work.get(5, TimeUnit.SECONDS).intValue());
        publisher.pollOnce().block();
        stream.getAsyncResult(5000);
        mvc.perform(asyncDispatch(stream)).andReturn();
        assertEquals("SUCCEEDED", send(STATUS, query(id), 200).path("data").path("state").asString());
        assertEquals(1, modelCalls.get());
    }

    @Test
    public void activeSseFlushesAcceptedAndThenPushesLiveTerminal() throws Exception {
        var id = submit();
        var request = query(id);
        request.put("afterSequence", 0);
        var first = mvc.perform(post("/ai-new/invocation/watchInvocation")
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request))).andReturn();
        first.getAsyncResult(5000);
        var stream = mvc.perform(asyncDispatch(first)).andReturn();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!stream.getResponse().getContentAsString().contains("ACCEPTED") && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertTrue(stream.getResponse().getContentAsString().contains("ACCEPTED"));
        assertFalse(stream.getResponse().getContentAsString().contains("TERMINAL"));
        dispatch();
        publisher.pollOnce().block();
        stream.getAsyncResult(5000);
        stream = mvc.perform(asyncDispatch(stream)).andReturn();
        assertEquals(200, stream.getResponse().getStatus());
        assertTrue(stream.getResponse().getContentAsString().contains("TERMINAL"));
        assertEquals(1, modelCalls.get());
    }

    @Test
    public void ssePreflightReturnsHttpErrorsForForeignAndExpiredCursors() throws Exception {
        var id = submit();
        login("bob");
        var request = query(id);
        request.put("afterSequence", 0);
        send("/ai-new/invocation/watchInvocation", request, 404);
        login("alice");
        dispatch();
        publisher.pollOnce().block();
        assertTrue(fixture.executions.discardThrough(AdmissionFixture.owner("alice-id"), id, 1).block().successful());
        send("/ai-new/invocation/watchInvocation", request, 410);
    }

    private BudgetReservation terminalWithReservedBudget(String id) {
        var owner = AdmissionFixture.owner("alice-id");
        var invocation = fixture.executions.find(owner, id).block();
        var attempt = fixture.executions.createAttempt(new ExecutionCommands.CreateAttempt(
                new ExecutionCommands.Version(owner, id, invocation.version()), "budget-attempt", "budget-worker", Duration.ofMinutes(1))).block().value();
        invocation = fixture.executions.find(owner, id).block();
        var account = fixture.executions.account(owner, "alice-budget").block();
        var reservation = fixture.executions.reserve(new BudgetCommands.Reserve(
                ExecutionCommands.Guard.from(owner, invocation, attempt), "budget-reservation", new Money(BigDecimal.ONE, account.limit().currency()),
                account.rateVersion(), Duration.ofHours(1))).block().value();
        attempt = fixture.executions.findAttempt(owner, id, attempt.attemptId()).block();
        assertTrue(fixture.executions.commitCompletion(new ExecutionCommands.Complete(
                ExecutionCommands.Guard.from(owner, invocation, attempt), "cancel-before-dispatch",
                new ExecutionPayload.Terminal(Invocation.State.CANCELLED, null, null), Usage.unknown(), null)).block().successful());
        return reservation;
    }

    private void releaseBudget(BudgetReservation reservation) {
        var command = new BudgetCommands.Settle(AdmissionFixture.owner("alice-id"), reservation.version(),
                new BudgetSettlement("release", reservation.reservationId(), BudgetSettlement.State.RELEASED,
                        Usage.unknown(), new Money(BigDecimal.ZERO, reservation.reserved().currency()), Instant.now()),
                BudgetCommands.Evidence.PROVEN_NOT_DISPATCHED, "not-dispatched");
        assertTrue(fixture.executions.settle(command).block().successful());
        assertEquals(StoreOutcome.Code.REPLAYED, fixture.executions.settle(command).block().code());
    }

    @Test
    public void terminalDoesNotCloseWatchBeforeBudgetSettlementCommits() throws Exception {
        var id = submit();
        var reservation = terminalWithReservedBudget(id);
        assertEquals("RESERVED", send(STATUS, query(id), 200).path("data").path("budgetState").asString());
        var events = new CopyOnWriteArrayList<ExecutionEvent<?>>();
        var terminal = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var errors = new CopyOnWriteArrayList<Throwable>();
        var watch = eventService.watch(new ExecutionEvent.Cursor(id, 0), fixture.context("alice", "budget-watch"))
                .subscribe(event -> {
                            events.add(event);
                            if (event.kind() == ExecutionEvent.Kind.TERMINAL) terminal.countDown();
                        },
                        error -> {
                            errors.add(error);
                            closed.countDown();
                        }, closed::countDown);
        try {
            assertTrue(terminal.await(5, TimeUnit.SECONDS));
            assertFalse(closed.await(150, TimeUnit.MILLISECONDS));
            releaseBudget(reservation);
            assertEquals(5, publisher.pollOnce().block().intValue());
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertTrue(errors.toString(), errors.isEmpty());
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), events.stream().map(ExecutionEvent::sequence).toList());
            assertEquals("RELEASED", send(STATUS, query(id), 200).path("data").path("budgetState").asString());
            assertEquals(0, fixture.executions.account(AdmissionFixture.owner("alice-id"), "alice-budget").block().held().amount().signum());
            assertEquals(0, modelCalls.get());
        } finally {
            watch.dispose();
        }
    }

    private RedissonClient isolatedRedis() {
        String address = System.getProperty("arte.ai-new.test.redis-address");
        Assume.assumeTrue("Run with an isolated Redis test endpoint", address != null && address.startsWith("redis://127.0.0.1:"));
        var config = new Config();
        config.useSingleServer().setAddress(address).setConnectionMinimumIdleSize(1).setConnectionPoolSize(2)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        return Redisson.create(config);
    }

    private void awaitReady(RedisExecutionEventBroadcast bus) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!bus.isReady() && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertTrue(bus.isReady());
    }

    @Test
    public void redisLiveTextReachesAuthorizedRemoteWatcherWithoutEventOutbox() throws Exception {
        var clientA = isolatedRedis();
        var clientB = isolatedRedis();
        var textB = new LiveTextNotifier();
        var options = new NewAiEventProperties(NewAiEventProperties.Transport.REDIS, "arte-test:" + UUID.randomUUID(), Duration.ofSeconds(2));
        var busA = new RedisExecutionEventBroadcast(clientA, notifier, options, liveText);
        var busB = new RedisExecutionEventBroadcast(clientB, new LocalExecutionEventNotifier(), options, textB);
        var remote = new DefaultExecutionEventService(authorization(fixture.properties, Clock.systemUTC()), fixture.executions,
                fixture.executions, fixture.payloads, notifier, Clock.systemUTC(), null, textB);
        var observed = new CopyOnWriteArrayList<com.arte.ainew.common.execution.LiveTextDelta>();
        var delivered = new CountDownLatch(2);
        var foreign = new AtomicInteger();
        reactor.core.Disposable watch = null;
        reactor.core.Disposable other = null;
        reactor.core.Disposable local = null;
        var localObserved = new CopyOnWriteArrayList<com.arte.ainew.common.execution.LiveTextDelta>();
        try {
            busA.start();
            busB.start();
            awaitReady(busA);
            awaitReady(busB);
            var id = submit();
            watch = remote.watchText(id, fixture.context("alice", "remote-text"))
                    .subscribe(delta -> {
                        observed.add(delta);
                        delivered.countDown();
                    });
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (textB.subscriberCount() == 0 && System.nanoTime() < end) Thread.sleep(5);
            assertEquals(1, textB.subscriberCount());
            other = textB.watch(AdmissionFixture.owner("bob-id"), id).subscribe(delta -> foreign.incrementAndGet());
            var first = new com.arte.ainew.common.execution.LiveTextDelta(AdmissionFixture.owner("alice-id"), id, "attempt", 0, "你🙂");
            var second = new com.arte.ainew.common.execution.LiveTextDelta(AdmissionFixture.owner("alice-id"), id, "attempt", 3, "\n 好");
            local = liveText.watch(AdmissionFixture.owner("alice-id"), id).subscribe(localObserved::add);
            liveText.emit(first);
            liveText.emit(second);
            busA.publishText(first).block(Duration.ofSeconds(3));
            busA.publishText(second).block(Duration.ofSeconds(3));
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            assertEquals(List.of(first, second), observed);
            assertEquals(List.of(first, second), localObserved); // Redis 不再把本地已显示的字回送第二次。
            assertEquals(0, foreign.get());
            assertEquals(0, modelCalls.get());
            assertTrue(fixture.executions.replay(AdmissionFixture.owner("alice-id"), new ExecutionEvent.Cursor(id, 0), 256).block()
                    .value().events().stream().noneMatch(event -> event.kind() == ExecutionEvent.Kind.OUTPUT));
            assertThrows(AdmissionException.class, () -> remote.watchText(id, fixture.context("bob", "denied-text")).next().block());
        } finally {
            if (watch != null) watch.dispose();
            if (other != null) other.dispose();
            if (local != null) local.dispose();
            busA.stop();
            busB.stop();
            clientA.shutdown();
            clientB.shutdown();
        }
        assertEquals(0, textB.subscriberCount());
    }

    @Test
    public void redisBroadcastReachesAnotherInstanceAndReconnectReplaysAcknowledgedEvents() throws Exception {
        var clientA = isolatedRedis();
        var clientB = isolatedRedis();
        var notifierB = new LocalExecutionEventNotifier();
        var options = new NewAiEventProperties(NewAiEventProperties.Transport.REDIS, "arte-test:" + UUID.randomUUID(), Duration.ofSeconds(2));
        var busA = new RedisExecutionEventBroadcast(clientA, notifier, options);
        var busB = new RedisExecutionEventBroadcast(clientB, notifierB, options);
        var remote = new DefaultExecutionEventService(authorization(fixture.properties, Clock.systemUTC()), fixture.executions,
                fixture.executions, fixture.payloads, notifierB, Clock.systemUTC());
        var settings = new NewAiExecutionProperties(true, false, 4, null, null, null, null);
        var redisPublisher = new ExecutionEventPublisher(fixture.executions, notifier, settings, timer, busA);
        var observed = new CopyOnWriteArrayList<ExecutionEvent<?>>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        var accepted = new CountDownLatch(1);
        var terminal = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        reactor.core.Disposable watch = null;
        try {
            busA.start();
            busB.start();
            awaitReady(busA);
            awaitReady(busB);
            var id = submit();
            watch = remote.watch(new ExecutionEvent.Cursor(id, 0), fixture.context("alice", "remote"))
                    .subscribe(event -> {
                                observed.add(event);
                                if (event.kind() == ExecutionEvent.Kind.ACCEPTED) accepted.countDown();
                                if (event.kind() == ExecutionEvent.Kind.TERMINAL) terminal.countDown();
                            },
                            error -> {
                                errors.add(error);
                                closed.countDown();
                            }, closed::countDown);
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            var reservation = terminalWithReservedBudget(id);
            assertEquals(4, redisPublisher.pollOnce().block().intValue());
            assertTrue(terminal.await(5, TimeUnit.SECONDS));
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS));
            // 订阅端断开；发布端成功发布并 ACK。重新订阅必须重放已 ACK 的数据库事件。
            busB.stop();
            releaseBudget(reservation);
            assertEquals(1, redisPublisher.pollOnce().block().intValue());
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS));
            busB.start();
            awaitReady(busB);
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertTrue(errors.toString(), errors.isEmpty());
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), observed.stream().map(ExecutionEvent::sequence).toList());
            assertFalse(settings.workerEnabled()); // HTTP-only 节点也订阅。
            assertEquals(0, modelCalls.get());
        } finally {
            if (watch != null) watch.dispose();
            busA.stop();
            busB.stop();
            clientA.shutdown();
            clientB.shutdown();
        }
    }

    @Test
    public void redisHintsAreOwnerIsolatedAndInvalidHintsAreSafe() throws Exception {
        var client = isolatedRedis();
        var options = new NewAiEventProperties(NewAiEventProperties.Transport.REDIS, "arte-test:" + UUID.randomUUID(), Duration.ofSeconds(2));
        var bus = new RedisExecutionEventBroadcast(client, notifier, options);
        var owner = AdmissionFixture.owner("alice-id");
        var delivered = new CountDownLatch(1);
        var other = new AtomicInteger();
        var expected = notifier.watch(owner, "inv").filter(seq -> seq > 0).subscribe(seq -> delivered.countDown());
        var foreign = notifier.watch(AdmissionFixture.owner("bob-id"), "inv").filter(seq -> seq > 0).subscribe(seq -> other.incrementAndGet());
        try {
            bus.start();
            awaitReady(bus);
            var topic = client.getTopic(options.channel(), StringCodec.INSTANCE);
            topic.publish("{invalid-json}");
            topic.publish(json.writeValueAsString(new RedisExecutionEventBroadcast.Signal(1, owner, "inv", 2)));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(0, other.get());
        } finally {
            expected.dispose();
            foreign.dispose();
            bus.stop();
            client.shutdown();
        }
    }

    @Test
    public void failedBroadcastDoesNotAcknowledgeAndLeaseRecoveryPublishesIt() throws Exception {
        var id = submit();
        var settings = new NewAiExecutionProperties(true, false, 4, null, null, null, null);
        var failed = new ExecutionEventPublisher(fixture.executions, notifier, settings, timer,
                message -> Mono.error(new IllegalStateException("isolated transport failure")));
        assertEquals(0, failed.pollOnce().block().intValue());
        assertEquals(0, publisher.pollOnce().block().intValue());
        jdbc.update("UPDATE arte_ai_outbox SET lease_until=0 WHERE kind='EVENT'");
        assertEquals(1, publisher.pollOnce().block().intValue());
        assertEquals(1, fixture.executions.replay(AdmissionFixture.owner("alice-id"), new ExecutionEvent.Cursor(id, 0), 256).block().value().events().size());
    }

    @Test
    public void eventPublisherWakesAfterCommitAndLiveWatchHasNoReplaySubscriptionGap() throws Exception {
        var id = submit();
        assertEquals(1, wakeups.get());
        var observed = new CopyOnWriteArrayList<ExecutionEvent<?>>();
        var first = new CountDownLatch(1);
        var complete = new CountDownLatch(1);
        var errors = new CopyOnWriteArrayList<Throwable>();
        eventService.watch(new ExecutionEvent.Cursor(id, 0), fixture.context("alice", "watch"))
                .subscribe(event -> {
                    observed.add(event);
                    first.countDown();
                }, error -> {
                    errors.add(error);
                    complete.countDown();
                }, complete::countDown);
        assertTrue(first.await(5, TimeUnit.SECONDS));
        dispatch();
        assertEquals(6, wakeups.get());
        assertEquals(6, publisher.pollOnce().block().intValue());
        assertTrue(complete.await(5, TimeUnit.SECONDS));
        assertTrue(errors.toString(), errors.isEmpty());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), observed.stream().map(ExecutionEvent::sequence).toList());
        assertEquals(ExecutionEvent.Kind.BUDGET_CHANGED, observed.getLast().kind());
        assertEquals(0, publisher.pollOnce().block().intValue());
    }

    @Test
    public void commitAfterEmptyReplayBeforeStatusReadStillDeliversTerminal() throws Exception {
        var id = submit();
        var reads = new AtomicInteger();
        ExecutionEventStore racing = new ExecutionEventStore() {
            @Override
            public Mono<StoreOutcome<List<ExecutionEvent<?>>>> appendBatch(ExecutionCommands.Append command) {
                return fixture.executions.appendBatch(command);
            }

            @Override
            public Mono<StoreOutcome<Page>> replay(ExecutionOwner owner, ExecutionEvent.Cursor cursor, int limit) {
                return fixture.executions.replay(owner, cursor, limit).doOnNext(ignored -> {
                    if (reads.incrementAndGet() == 1) {
                        // 首次读取完成后、结果交给订阅者前提交新事件并发布，制造原有空窗。
                        dispatch();
                        publisher.pollOnce().block();
                    }
                });
            }

            @Override
            public Mono<StoreOutcome<ExecutionEvent.Cursor>> discardThrough(ExecutionOwner owner, String invocationId, long through) {
                return fixture.executions.discardThrough(owner, invocationId, through);
            }
        };
        var service = new DefaultExecutionEventService(authorization(fixture.properties, Clock.systemUTC()), fixture.executions,
                racing, fixture.payloads, notifier, Clock.systemUTC());
        var events = service.watch(new ExecutionEvent.Cursor(id, 1), fixture.context("alice", "race"))
                .collectList().block(Duration.ofSeconds(5));
        assertEquals(List.of(2L, 3L, 4L, 5L, 6L), events.stream().map(ExecutionEvent::sequence).toList());
        assertEquals(1, modelCalls.get());
    }

    @Test
    public void fullEventBatchesDrainImmediatelyWithoutWaitingForRecoveryScan() throws Exception {
        var acknowledged = new CountDownLatch(130);
        var position = new AtomicInteger();
        ExecutionOutboxStore batches = new ExecutionOutboxStore() {
            @Override
            public Mono<List<OutboxMessage>> claim(OutboxMessage.Kind kind, String worker, Duration lease, int limit) {
                int start = position.getAndAdd(limit);
                return Mono.just(java.util.stream.IntStream.range(start, Math.min(start + limit, 130))
                        .mapToObj(index -> new OutboxMessage("message-" + index, "batch-invocation", AdmissionFixture.owner("alice-id"),
                                kind, index + 1L, worker, 1, java.time.Instant.now().plus(lease))).toList());
            }

            @Override
            public Mono<StoreOutcome<OutboxMessage>> validateClaim(OutboxMessage message) {
                return Mono.just(StoreOutcome.applied(message));
            }

            @Override
            public Mono<StoreOutcome<OutboxMessage>> renewClaim(OutboxMessage message, Duration lease) {
                return Mono.just(StoreOutcome.applied(message));
            }

            @Override
            public Mono<StoreOutcome<OutboxMessage>> acknowledge(OutboxMessage message) {
                acknowledged.countDown();
                return Mono.just(StoreOutcome.applied(message));
            }
        };
        var settings = new NewAiExecutionProperties(true, false, 4, Duration.ofSeconds(1),
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofDays(1));
        var eventPublisher = new ExecutionEventPublisher(batches, new LocalExecutionEventNotifier(), settings, timer);
        try {
            eventPublisher.start();
            assertTrue("All three batches must finish before the 5 second recovery timer", acknowledged.await(2, TimeUnit.SECONDS));
        } finally {
            eventPublisher.stop();
        }
    }

    @Test
    public void publisherRunsFromCommitWakeupWithoutWaitingForRecoveryTimer() throws Exception {
        publisher.start();
        var id = submit();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='EVENT' AND delivered=0", Integer.class) != 0
                && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='EVENT' AND delivered=0", Integer.class).intValue());
        assertEquals("ACCEPTED", fixture.executions.find(AdmissionFixture.owner("alice-id"), id).block().state().name());
        assertEquals(0, modelCalls.get());
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
        assertEquals("PENDING_RECONCILIATION", status.path("budgetState").asString());
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
        assertEquals(java.util.Set.of("STARTED", "OUTPUT", "TERMINAL", "BUDGET_CHANGED"), kinds);
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
