package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.ConversationHttpContext;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiChatController;
import com.arte.ainew.web.controller.NewAiConversationController;
import com.arte.core.enums.ResultCodeEnum;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * HTTP 创建会话到耐久受理，使用隔离 H2、真实授权／Service／存储，不调用模型或当前数据库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 16:28 ✾
 */
public class ChatHttpIntegrationTest {
    private static final String SUBMIT = "/ai-new/chat/turnsForChat";
    private static final Map<String, String> SCOPE = Map.of("tenantId", "tenant", "workspaceId", "workspace");
    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private Scheduler scheduler;
    private AdmissionFixture fixture;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @Before
    public void setup() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        scheduler = Schedulers.newBoundedElastic(4, 128, "chat-http-test");
        fixture = new AdmissionFixture(dataSource, scheduler);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        configureMvc();
        login("alice");
        fixture.initializeBudget("alice");
    }

    private void configureMvc() {
        var httpContext = new NewAiHttpContext(fixture.factory, fixture.properties);
        mvc = MockMvcBuilders.standaloneSetup(
                        new NewAiConversationController(fixture.conversations, new ConversationHttpContext(httpContext, fixture.properties)),
                        new NewAiChatController(fixture.chat, fixture.catalog, httpContext, fixture.properties))
                .setControllerAdvice(new ConversationExceptionHandler()).setValidator(validator).setAsyncRequestTimeout(10000).build();
    }

    @After
    public void cleanup() {
        SecurityContextHolder.clearContext();
        validator.close();
        jdbc.execute("DROP ALL OBJECTS");
        scheduler.dispose();
    }

    private void login(String name) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, "unused", List.of()));
    }

    private MvcResult send(MockHttpServletRequestBuilder request, int status) throws Exception {
        var result = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "en")).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(5000);
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertEquals(result.getResponse().getContentAsString(), status, result.getResponse().getStatus());
        return result;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String create() throws Exception {
        return body(send(post("/ai-new/conversation/createConversation").header("Idempotency-Key", "create-" + UUID.randomUUID())
                .content(json.writeValueAsString(Map.of("scope", SCOPE, "title", "HTTP chat"))), 200))
                .path("data").path("conversationId").asString();
    }

    private Map<String, Object> request(String id, long version, String text) {
        var request = new LinkedHashMap<String, Object>();
        request.put("scope", SCOPE);
        request.put("conversationId", id);
        request.put("expectedVersion", version);
        request.put("text", text);
        request.put("capability", AdmissionFixture.CAP);
        request.put("binding", AdmissionFixture.BINDING);
        request.put("budgetRef", "alice-budget");
        request.put("maxInputTokens", 768);
        request.put("generationOptions", Map.of("maxOutputTokens", 128, "stopSequences", List.of()));
        request.put("timeoutSeconds", 60);
        return request;
    }

    private JsonNode submit(Map<String, Object> request, String key, int status) throws Exception {
        return body(send(post(SUBMIT).header("Idempotency-Key", key).content(json.writeValueAsString(request)), status));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    public void acceptedResponseCorrespondsToCommittedInvocationTurnAndOutboxWithoutDispatch() throws Exception {
        var id = create();
        var response = submit(request(id, 0, "你好 世界\n  保留空白  "), "submit", 202);
        assertTrue(response.path("success").asBoolean());
        assertEquals(ResultCodeEnum.SUCCESS.getDesc(Locale.ENGLISH), response.path("desc").asString());
        var data = response.path("data");
        assertEquals(id, data.path("conversationId").asString());
        assertEquals("INVOCATION", data.path("kind").asString());
        assertFalse(data.has("owner"));
        assertFalse(data.has("context"));
        assertFalse(data.has("state"));
        var stored = fixture.executions.find(AdmissionFixture.owner("alice-id"), data.path("invocationId").asString()).block();
        assertNotNull(stored);
        assertEquals(Invocation.State.ACCEPTED, stored.state());
        assertEquals(stored.acceptedAt().toString(), data.path("acceptedAt").asString());
        assertEquals(id, stored.conversation().conversationId());
        var input = (GenerationRequest) stored.request().input();
        assertEquals(ChatMessage.Role.USER, input.messages().getFirst().role());
        assertEquals("你好 世界\n  保留空白  ", ((ChatMessage.Text) input.messages().getFirst().content().getFirst()).text());
        assertEquals(Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION), stored.request().context().authorization().scopes());
        assertEquals("release-v1", stored.request().context().releaseRef());
        assertEquals(Duration.ofSeconds(60), stored.request().options().requestedTimeout());
        assertEquals(1, stored.request().options().maxAttempts());
        assertEquals(4096, stored.request().options().maxOutputBytes());
        var turn = fixture.conversations.turn(id, stored.conversation().turnId(), fixture.context("alice", "read-turn")).block();
        assertEquals(List.of(data.path("invocationId").asString()), turn.invocationIds());
        assertEquals(1, fixture.conversations.find(id, fixture.context("alice", "find")).block().version());
        assertEquals(1, count("arte_ai_invocation"));
        assertEquals(1, count("arte_ai_turn"));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind = 'DISPATCH'", Long.class).longValue());
        assertEquals(2, count("arte_ai_outbox"));
        assertEquals(0, count("arte_ai_attempt"));
        assertEquals(0, fixture.executions.account(AdmissionFixture.owner("alice-id"), "alice-budget").block().held().amount().signum());
    }

    @Test
    public void replayReturnsOriginalReceiptAfterVersionAdvanceAndRejectsChangedSemantics() throws Exception {
        var request = request(create(), 0, "hello");
        var first = submit(request, "same", 202);
        var repeated = submit(request, "same", 202);
        assertEquals(first.path("data"), repeated.path("data"));
        for (String field : List.of("text", "timeoutSeconds", "maxInputTokens", "generationOptions")) {
            var changed = new LinkedHashMap<>(request);
            changed.put(field, switch (field) {
                case "text" -> "changed";
                case "timeoutSeconds" -> 61;
                case "maxInputTokens" -> 700;
                default -> Map.of("maxOutputTokens", 129, "stopSequences", List.of());
            });
            assertEquals(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT.getCode(), submit(changed, "same", 409).path("code").asString());
        }
        assertEquals(1, count("arte_ai_invocation"));
        assertEquals(1, count("arte_ai_context_snapshot"));
    }

    @Test
    public void concurrentDuplicateHttpSubmissionsCreateOnlyOneInvocation() throws Exception {
        var request = request(create(), 0, "once");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                login("alice");
                try {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return submit(request, "concurrent", 202).path("data");
                } finally {
                    SecurityContextHolder.clearContext();
                }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("arte_ai_invocation"));
        assertEquals(1, count("arte_ai_turn"));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind = 'DISPATCH'", Long.class).longValue());
        assertEquals(2, count("arte_ai_outbox"));
    }

    @Test
    public void newRequestsRejectStaleVersionsAndBusyConversations() throws Exception {
        var id = create();
        submit(request(id, 0, "first"), "first", 202);
        assertEquals(ResultCodeEnum.AI_VERSION_CONFLICT.getCode(), submit(request(id, 0, "second"), "stale", 409).path("code").asString());
        assertEquals(ResultCodeEnum.AI_CONVERSATION_BUSY.getCode(), submit(request(id, 1, "second"), "busy", 409).path("code").asString());
        assertEquals(1, count("arte_ai_invocation"));
    }

    @Test
    public void anonymousAndUnauthorizedSpaceAreRejectedWithoutAccepting() throws Exception {
        var request = request(create(), 0, "hello");
        SecurityContextHolder.clearContext();
        submit(request, "anonymous", 401);
        login("alice");
        request.put("scope", Map.of("tenantId", "other", "workspaceId", "workspace"));
        submit(request, "other-space", 403);
        assertEquals(0, count("arte_ai_invocation"));
    }

    @Test
    public void missingInvokeOrConversationGrantIsForbidden() throws Exception {
        var request = request(create(), 0, "hello");
        var original = fixture.properties;
        for (var denied : List.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION)) {
            var grants = original.grants().stream().map(grant -> {
                var scopes = new HashSet<>(grant.scopes());
                scopes.remove(denied);
                return new NewAiProperties.Grant(grant.subjectName(), grant.subjectId(), grant.principalKind(), grant.tenantId(),
                        grant.workspaceId(), grant.grantRef(), grant.enabled(), scopes, grant.bindingIds(), grant.budgetRefs());
            }).toList();
            var restricted = new NewAiProperties(original.enabled(), original.dataSourceBean(), original.releaseRef(),
                    original.persistence(), original.limits(), grants, original.capabilities(), original.bindings(),
                    original.connections(), original.rates(), original.budgets());
            fixture = new AdmissionFixture(dataSource, scheduler, fixture.codec, restricted);
            configureMvc();
            submit(request, "missing-" + denied, 403);
        }
        assertEquals(0, count("arte_ai_invocation"));
    }

    @Test
    public void foreignConversationsAndBudgetsAreNotUsable() throws Exception {
        var id = create();
        login("bob");
        var foreign = request(id, 0, "hello");
        foreign.put("budgetRef", "bob-budget");
        var missing = request("missing", 0, "hello");
        missing.put("budgetRef", "bob-budget");
        assertEquals(submit(missing, "missing", 404), submit(foreign, "foreign", 404));
        var bobId = create();
        assertEquals(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE.getCode(), submit(request(bobId, 0, "hello"), "foreign-budget", 400).path("code").asString());
        var uninitialized = request(bobId, 0, "hello");
        uninitialized.put("budgetRef", "bob-budget");
        assertEquals(ResultCodeEnum.AI_BUDGET_NOT_INITIALIZED.getCode(), submit(uninitialized, "uninitialized", 400).path("code").asString());
        assertEquals(0, count("arte_ai_invocation"));
    }

    @Test
    public void missingHeadersNullFieldsAndInvalidValuesAreBadRequests() throws Exception {
        var request = request(create(), 0, "hello");
        send(post(SUBMIT).content(json.writeValueAsString(request)), 400);
        submit(request, " ", 400);
        submit(request, "k".repeat(257), 400);
        for (var field : request.keySet()) {
            var invalid = new LinkedHashMap<>(request);
            invalid.put(field, null);
            submit(invalid, "null-" + field, 400);
        }
        for (var field : List.of("text", "conversationId", "budgetRef")) {
            var invalid = new LinkedHashMap<>(request);
            invalid.put(field, " ");
            submit(invalid, "blank-" + field, 400);
        }
        for (var field : List.of("expectedVersion", "timeoutSeconds", "maxInputTokens")) {
            var invalid = new LinkedHashMap<>(request);
            invalid.put(field, -1);
            submit(invalid, "negative-" + field, 400);
        }
        request.put("expectedVersion", Long.MAX_VALUE);
        submit(request, "overflow-version", 400);
        assertEquals(0, count("arte_ai_invocation"));
    }

    @Test
    public void serverCapacityTimeoutAndOutputLimitsAreEnforced() throws Exception {
        var request = request(create(), 0, "hello");
        var excessive = new LinkedHashMap<>(request);
        excessive.put("timeoutSeconds", 121);
        submit(excessive, "timeout", 400);
        excessive = new LinkedHashMap<>(request);
        excessive.put("maxInputTokens", 1024);
        submit(excessive, "capacity", 400);
        excessive = new LinkedHashMap<>(request);
        excessive.put("maxInputTokens", 700);
        excessive.put("generationOptions", Map.of("maxOutputTokens", 257, "stopSequences", List.of()));
        assertEquals(ResultCodeEnum.AI_EXECUTION_LIMIT_EXCEEDED.getCode(), submit(excessive, "output", 400).path("code").asString());
        excessive = new LinkedHashMap<>(request);
        excessive.put("text", "a".repeat(705));
        assertEquals(ResultCodeEnum.AI_CONTEXT_CAPACITY_EXCEEDED.getCode(), submit(excessive, "input", 400).path("code").asString());
        excessive = new LinkedHashMap<>(request);
        excessive.put("text", "中".repeat(683));
        assertEquals(ResultCodeEnum.AI_INPUT_LIMIT_EXCEEDED.getCode(), submit(excessive, "bytes", 400).path("code").asString());
        excessive = new LinkedHashMap<>(request);
        excessive.put("generationOptions", Map.of("maxOutputTokens", 128, "temperature", 3, "stopSequences", List.of()));
        submit(excessive, "temperature", 400);
        excessive = new LinkedHashMap<>(request);
        excessive.put("binding", Map.of("type", "binding", "id", "text", "version", "latest"));
        submit(excessive, "latest", 400);
        excessive = new LinkedHashMap<>(request);
        excessive.put("binding", Map.of("type", "binding", "id", "missing", "version", "v1"));
        assertEquals(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE.getCode(), submit(excessive, "binding", 400).path("code").asString());
        assertEquals(0, count("arte_ai_invocation"));
        assertEquals(0, count("arte_ai_context_snapshot"));
    }

    @Test
    public void loginIsCapturedBeforeAsyncExecutionAndEndpointDeclaresSecurityAndFeatureFlag() throws Exception {
        var request = request(create(), 0, "captured");
        var result = mvc.perform(post(SUBMIT).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "capture").content(json.writeValueAsString(request))).andReturn();
        SecurityContextHolder.clearContext();
        result.getAsyncResult(5000);
        result = mvc.perform(asyncDispatch(result)).andReturn();
        assertEquals(202, result.getResponse().getStatus());
        var id = body(result).path("data").path("invocationId").asString();
        assertNotNull(fixture.executions.find(new ExecutionOwner("tenant", "workspace", "alice-id"), id).block());
        var method = NewAiChatController.class.getMethod("turnsForChat", com.arte.ainew.web.request.ChatRequests.Submit.class, String.class, Locale.class);
        assertEquals("isAuthenticated()", method.getAnnotation(PreAuthorize.class).value());
        var conditional = NewAiChatController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("arte.ai-new", conditional.prefix());
        assertEquals("true", conditional.havingValue());
    }
}
