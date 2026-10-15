package com.arte.ainew.admission;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.persistence.mybatis.ExecutionSqlDiagnostics;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.ConversationHttpContext;
import com.arte.ainew.web.ConversationRequestDiagnostics;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiConversationController;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.PageParam;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 真实 MVC 参数绑定、响应序列化、认证上下文与隔离数据库分页；不调用外部模型。
 */
public class ConversationHttpIntegrationTest {
    private static final String ROOT = "/ai-new/conversation/";
    private static final String SCOPE = "\"scope\":{\"tenantId\":\"tenant\",\"workspaceId\":\"workspace\"}";
    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate jdbc;
    private Scheduler scheduler;
    private AdmissionFixture fixture;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @Before
    public void setup() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        scheduler = Schedulers.newBoundedElastic(4, 128, "conversation-http-test");
        fixture = new AdmissionFixture(dataSource, scheduler);
        var controller = new NewAiConversationController(fixture.conversations,
                new ConversationHttpContext(new NewAiHttpContext(fixture.factory, fixture.properties), fixture.properties));
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ConversationExceptionHandler())
                .setValidator(validator).setAsyncRequestTimeout(10000).build();
        login("alice");
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

    @Test
    public void diagnosticsPreserveSuccessfulEmptyListPagination() throws Exception {
        var response = body(send(post(ROOT + "listConversations")
                .content("{" + SCOPE + ",\"page\":{\"current\":2,\"size\":5}}"), 200));
        assertEquals(2, response.path("current").asLong());
        assertEquals(5, response.path("size").asLong());
        assertEquals(0, response.path("total").asLong());
        assertEquals(0, response.path("records").size());
    }

    @Test
    public void diagnosticsIncludeMissingTableCauseSqlAndAsyncRequestContext() throws Exception {
        assertDatabaseFailureDiagnostics("DROP TABLE arte_ai_conversation_new", "42S02", "countConversations", "owner_key=?");
    }

    @Test
    public void diagnosticsIncludeMissingColumnCauseWithoutLoggingBoundValues() throws Exception {
        assertDatabaseFailureDiagnostics("ALTER TABLE arte_ai_conversation_new DROP COLUMN owner_key", "42S22", "countConversations", "owner_key=?");
    }

    @Test
    public void diagnosticsIdentifySnapshotColumnFailureAfterCountSucceeds() throws Exception {
        createId("snapshot column", "create-snapshot");
        assertDatabaseFailureDiagnostics("ALTER TABLE arte_ai_conversation_new DROP COLUMN workspace_snapshot",
                "42S22", "conversationSnapshots", "SELECT workspace_snapshot");
    }

    private void assertDatabaseFailureDiagnostics(String faultSql, String expectedSqlState,
                                                  String statement, String sqlFragment) throws Exception {
        var events = new java.util.concurrent.CopyOnWriteArrayList<org.apache.logging.log4j.core.LogEvent>();
        var appender = new org.apache.logging.log4j.core.appender.AbstractAppender("conversation-diagnostics", null, null, false,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        var loggers = List.of(
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(ConversationExceptionHandler.class),
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(ExecutionSqlDiagnostics.class),
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(com.arte.ainew.application.conversation.DefaultConversationService.class));
        var levels = loggers.stream().map(org.apache.logging.log4j.core.Logger::getLevel).toList();
        appender.start();
        loggers.forEach(logger -> {
            logger.addAppender(appender);
            logger.setLevel(org.apache.logging.log4j.Level.DEBUG);
        });
        try {
            jdbc.execute(faultSql);
            var result = send(post(ROOT + "listConversations")
                    .header("Authorization", "Bearer debug-test-secret").header("Idempotency-Key", "private-debug-key")
                    .content("{" + SCOPE + ",\"page\":{\"current\":1,\"size\":5}}"), 500);
            var context = (ConversationRequestDiagnostics) result.getRequest().getAttribute(ConversationRequestDiagnostics.ATTRIBUTE);
            assertNotNull(context);
            assertEquals("listConversations", context.operation());
            assertEquals("tenant", context.tenantId());
            assertEquals("workspace", context.workspaceId());
            assertEquals("alice-id", context.subjectId());
            assertEquals(Long.valueOf(1), context.current());
            assertEquals(Long.valueOf(5), context.size());
            assertNotNull(context.traceId());
            assertNotNull(context.executionId());
            var failure = events.stream().filter(event -> event.getMessage().getFormattedMessage().startsWith("New AI HTTP failure")).findFirst().orElseThrow();
            assertNotNull(failure.getThrown());
            assertTrue(failure.getThrown().getStackTrace().length > 0);
            assertTrue(failure.getMessage().getFormattedMessage().contains("traceId=" + context.traceId()));
            assertTrue(failure.getMessage().getFormattedMessage().contains("sqlState=" + expectedSqlState));
            var sqlFailure = events.stream().map(event -> event.getMessage().getFormattedMessage())
                    .filter(message -> message.startsWith("New AI SQL failed")).findFirst().orElseThrow();
            assertTrue(sqlFailure, sqlFailure.contains("AdmissionMapper." + statement));
            assertTrue(sqlFailure, sqlFailure.contains(sqlFragment));
            assertTrue(sqlFailure, sqlFailure.contains("sqlState=" + expectedSqlState));
            assertTrue(events.stream().anyMatch(event -> event.getMessage().getFormattedMessage().contains("AI conversation query failed, operation=listConversations, traceId=" + context.traceId())));
            var messages = events.stream().map(event -> event.getMessage().getFormattedMessage()).toList().toString();
            assertFalse(messages.contains("debug-test-secret"));
            assertFalse(messages.contains("private-debug-key"));
            assertFalse(sqlFailure.contains("alice-id"));
        } finally {
            for (int index = 0; index < loggers.size(); index++) {
                loggers.get(index).removeAppender(appender);
                loggers.get(index).setLevel(levels.get(index));
            }
            appender.stop();
        }
    }

    private JsonNode create(String title, String key, int status) throws Exception {
        return body(send(post(ROOT + "createConversation").header("Idempotency-Key", key)
                .content("{" + SCOPE + ",\"title\":\"" + title + "\"}"), status));
    }

    private String createId(String title, String key) throws Exception {
        return create(title, key, 200).path("data").path("conversationId").asString();
    }

    private JsonNode turns(String id, long version, long current, long size, int status) throws Exception {
        return body(send(post(ROOT + "queryTurnsOfConversation").content("{" + SCOPE + ",\"conversationId\":\"" + id
                + "\",\"expectedVersion\":" + version + ",\"page\":{\"current\":" + current + ",\"size\":" + size + "}}"), status));
    }

    private JsonNode sidebar(String path, String fields, String key, int status) throws Exception {
        var request = post(ROOT + path).content("{" + SCOPE + (fields.isEmpty() ? "" : "," + fields) + "}");
        if (key != null) request.header("Idempotency-Key", key);
        return body(send(request, status));
    }

    @Test
    public void sidebarCommandsUseIndependentVersionsAndDurableReceipts() throws Exception {
        var created = sidebar("createDefaultConversation", "", "default", 200).path("data");
        String id = created.path("conversationId").asString();
        assertEquals("新会话" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), created.path("title").asString());
        assertEquals(id, sidebar("createDefaultConversation", "", "default", 200).path("data").path("conversationId").asString());
        String target = "\"conversationId\":\"" + id + "\"";
        var renamed = sidebar("renameConversation", target + ",\"expectedMetadataVersion\":0,\"title\":\"我的会话\"", "rename", 200).path("data");
        assertTrue(renamed.path("conversation").path("titleManuallyEdited").asBoolean());
        assertEquals(1, renamed.path("conversation").path("metadataVersion").asLong());
        var pinned = sidebar("setConversationPinned", target + ",\"expectedMetadataVersion\":1,\"pinned\":true", "pin", 200).path("data");
        assertEquals(1, pinned.path("pinnedCount").asLong());
        var detail = sidebar("getConversation", target, null, 200).path("data");
        assertEquals(0, detail.path("version").asLong());
        assertEquals("我的会话", detail.path("title").asString());
        var receipt = sidebar("getMetadataCommandReceipt", "\"commandId\":\"rename\"", null, 200).path("data");
        assertEquals("APPLIED", receipt.path("outcome").asString());
        assertEquals(renamed, receipt.path("receipt"));
        sidebar("renameConversation", target + ",\"expectedMetadataVersion\":0,\"title\":\"冲突\"", "stale", 409);
        var rejection = sidebar("getMetadataCommandReceipt", "\"commandId\":\"stale\"", null, 200).path("data");
        assertEquals("VERSION_CONFLICT", rejection.path("outcome").asString());
        assertTrue(rejection.path("receipt").isNull());
        sidebar("renameConversation", target + ",\"expectedMetadataVersion\":2,\"title\":\"变更原请求\"", "stale", 409);
        sidebar("renameConversation", target + ",\"title\":\"缺版本\"", "invalid", 400);
        sidebar("getMetadataCommandReceipt", "\"commandId\":\"pending\"", null, 404);
        login("bob");
        sidebar("getConversationMetadata", target, null, 404);
        sidebar("setConversationPinned", target + ",\"expectedMetadataVersion\":2,\"pinned\":false", "foreign", 404);
        sidebar("getMetadataCommandReceipt", "\"commandId\":\"rename\"", null, 404);
    }

    @Test
    public void sidebarCursorAndWindowHaveLightweightScopedShapesAndValidateBounds() throws Exception {
        for (int i = 0; i < 8; i++) sidebar("createDefaultConversation", "", "c" + i, 200);
        var page = sidebar("listConversationsCursor", "\"pinned\":false,\"size\":3,\"includeTotal\":true", null, 200).path("data");
        assertEquals(8, page.path("total").asLong());
        assertEquals(3, page.path("records").size());
        assertFalse(page.path("records").get(0).has("resources"));
        assertEquals(0, page.path("records").get(0).path("userMessageCount").asLong());
        var next = sidebar("listConversationsCursor", "\"pinned\":false,\"size\":3,\"cursor\":\"" + page.path("nextCursor").asString() + "\"", null, 200).path("data");
        assertTrue(next.path("total").isNull());
        var window = sidebar("queryConversationWindow", "\"normalSize\":100", null, 200).path("data");
        assertEquals(8, window.path("normal").path("records").size());
        assertEquals(0, window.path("pinned").path("records").size());
        sidebar("listConversationsCursor", "\"pinned\":false,\"size\":21", null, 400);
        sidebar("listConversationsCursor", "\"pinned\":false,\"size\":3,\"cursor\":\"broken\"", null, 400);
        sidebar("queryConversationWindow", "\"normalSize\":101", null, 400);
        login("bob");
        sidebar("listConversationsCursor", "\"pinned\":false,\"size\":3,\"cursor\":\"" + page.path("nextCursor").asString() + "\"", null, 400);
    }

    @Test
    public void defaultCreationBindsClientDateAndRejectsChangedOrInvalidDates() throws Exception {
        var fields = "\"localDate\":\"2026-10-14\"";
        var created = sidebar("createDefaultConversation", fields, "client-date", 200).path("data");
        assertEquals("新会话20261014", created.path("title").asString());
        assertEquals(created, sidebar("createDefaultConversation", fields, "client-date", 200).path("data"));
        sidebar("createDefaultConversation", "\"localDate\":\"2026-10-15\"", "client-date", 409);
        for (var invalid : List.of("2026-02-30", "2026-13-01", "2026-1-01", "0000-01-01", "")) {
            sidebar("createDefaultConversation", "\"localDate\":\"" + invalid + "\"", "invalid-date", 400);
        }
    }

    @Test
    public void defaultCreationReplayedAcrossMidnightKeepsFirstDateAndIdentity() {
        var resolver = new com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver(fixture.properties);
        var authorization = new com.arte.ainew.application.auth.AdmissionAuthorization(resolver, fixture.properties, java.time.Clock.systemUTC());
        var before = new com.arte.ainew.application.conversation.DefaultConversationService(fixture.executions, authorization,
                java.time.Clock.fixed(Instant.parse("2026-10-14T15:59:59Z"), java.time.ZoneOffset.UTC));
        var after = new com.arte.ainew.application.conversation.DefaultConversationService(fixture.executions, authorization,
                java.time.Clock.fixed(Instant.parse("2026-10-14T16:00:01Z"), java.time.ZoneOffset.UTC));
        var one = before.createDefault(fixture.context("alice", "midnight")).block();
        var two = after.createDefault(fixture.context("alice", "midnight")).block();
        assertEquals("新会话20261014", one.title());
        assertEquals(one, two);
        var next = after.createDefault(fixture.context("alice", "next-day")).block();
        assertEquals("新会话20261015", next.title());
        assertNotEquals(one.conversationId(), next.conversationId());
        // 首次到达／首次落库已经跨午夜，仍使用点击时固定的日期。
        var delayed = after.createDefault("2026-10-14", fixture.context("alice", "delayed-client-date")).block();
        assertEquals("新会话20261014", delayed.title());
        assertEquals(delayed, after.createDefault("2026-10-14", fixture.context("alice", "delayed-client-date")).block());
        assertThrows(AdmissionException.class, () -> after.createDefault("2026-10-15",
                fixture.context("alice", "delayed-client-date")).block());
        var readOnly = fixture.context("alice", "read-only", java.util.Set.of("ai:read"));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> before.createDefault(readOnly).block());
        var command = new com.arte.ainew.pojo.conversation.ConversationList.Command(one.conversationId(), 0, "不允许", null, "read-only");
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> before.changeMetadata(command, readOnly).block());
    }

    @Test
    public void allHttpEndpointsDeclareAuthentication() {
        // 授权可以声明在方法或 Controller 类上；URL 分类由 arte-app 的安全配置测试验证。
        var methods = Arrays.stream(NewAiConversationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)).toList();
        assertEquals(11, methods.size());
        for (var method : methods) {
            var authorization = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
            if (authorization == null) {
                authorization = AnnotatedElementUtils.findMergedAnnotation(NewAiConversationController.class, PreAuthorize.class);
            }
            assertNotNull(authorization);
            assertEquals("isAuthenticated()", authorization.value());
        }
    }

    @Test
    public void createIsDurablyIdempotentAndReturnsPublicMetadata() throws Exception {
        var first = create("中文 title", "same-key", 200);
        var repeated = create("中文 title", "same-key", 200);
        assertTrue(first.path("success").asBoolean());
        assertEquals(first.path("data"), repeated.path("data"));
        assertEquals("中文 title", first.path("data").path("title").asString());
        assertFalse(first.path("data").has("owner"));
        assertTrue(first.path("data").has("createdAt"));
        assertEquals(ResultCodeEnum.SUCCESS.getDesc(Locale.ENGLISH), first.path("desc").asString());
        assertEquals(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT.getCode(), create("changed", "same-key", 409).path("code").asString());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_conversation_new", Long.class).longValue());
    }

    @Test
    public void paginationUsesDatabaseScopeAndPreservesEmptyPageMetadata() throws Exception {
        for (int index = 0; index < 3; index++) {
            createId("alice-" + index, "create-" + index);
        }
        login("bob");
        createId("bob", "create-bob");
        login("alice");
        var first = body(send(post(ROOT + "listConversations").content("{" + SCOPE + ",\"page\":{\"size\":2}}"), 200));
        var second = body(send(post(ROOT + "listConversations").content("{" + SCOPE + ",\"page\":{\"current\":2,\"size\":2}}"), 200));
        assertEquals(3, first.path("total").asLong());
        assertEquals(2, first.path("records").size());
        assertEquals(1, second.path("records").size());
        assertNotEquals(first.path("records").get(0).path("conversationId"), second.path("records").get(0).path("conversationId"));
        var empty = body(send(post(ROOT + "listConversations").content("{" + SCOPE + ",\"page\":{\"current\":4,\"size\":2}}"), 200));
        assertEquals(4, empty.path("current").asLong());
        assertEquals(2, empty.path("size").asLong());
        assertEquals(3, empty.path("total").asLong());
        assertEquals(0, empty.path("records").size());
        var defaults = body(send(post(ROOT + "listConversations").content("{" + SCOPE + "}"), 200));
        assertEquals(20, defaults.path("size").asLong());
    }

    @Test
    public void detailAndTurnsHideForeignAndMissingConversationsIdentically() throws Exception {
        var id = createId("private", "create");
        var detail = body(send(post(ROOT + "getConversation").content("{" + SCOPE + ",\"conversationId\":\"" + id + "\"}"), 200));
        assertEquals(id, detail.path("data").path("conversationId").asString());
        login("bob");
        var foreign = body(send(post(ROOT + "getConversation").content("{" + SCOPE + ",\"conversationId\":\"" + id + "\"}"), 404));
        var missing = body(send(post(ROOT + "getConversation").content("{" + SCOPE + ",\"conversationId\":\"missing\"}"), 404));
        assertEquals(missing, foreign);
        assertEquals(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND.getCode(), turns(id, 0, 1, 20, 404).path("code").asString());
    }

    @Test
    public void turnsReturnPersistedUserMessageAndInvocationReferencesAndRejectStaleVersion() throws Exception {
        var id = createId("chat", "create");
        assertEquals(0, turns(id, 0, 1, 20, 200).path("total").asLong());
        fixture.initializeBudget("alice");
        var context = fixture.context("alice", "submit");
        var accepted = fixture.chat.submit(fixture.chatRequest(id, 0, "你好 世界", context), context).block();
        var page = turns(id, 1, 1, 20, 200);
        var turn = page.path("records").get(0);
        assertEquals(1, page.path("total").asLong());
        assertEquals(1, turn.path("sequence").asLong());
        assertEquals("你好 世界", turn.path("userMessage").path("content").get(0).path("text").asString());
        assertEquals(accepted.executionId(), turn.path("invocationIds").get(0).asString());
        var stale = turns(id, 0, 1, 20, 409);
        assertFalse(stale.path("success").asBoolean());
        assertEquals(ResultCodeEnum.AI_VERSION_CONFLICT.getCode(), stale.path("code").asString());
        assertTrue(stale.has("records"));
    }

    @Test
    public void validationRejectsMissingHeadersBlankTitleNullAndInvalidPagination() throws Exception {
        send(post(ROOT + "createConversation").content("{" + SCOPE + ",\"title\":\"test\"}"), 400);
        create(" ", "create", 400);
        create("a".repeat(257), "create", 400);
        create("valid", "a".repeat(257), 400);
        for (var pagination : List.of("null", "{\"current\":0}", "{\"size\":101}", "{\"size\":null}",
                "{\"current\":9223372036854775807,\"size\":2}")) {
            var failed = body(send(post(ROOT + "listConversations").content("{" + SCOPE + ",\"page\":" + pagination + "}"), 400));
            assertFalse(failed.path("success").asBoolean());
            assertTrue(failed.has("records"));
        }
        send(post(ROOT + "queryTurnsOfConversation").content("{" + SCOPE + ",\"conversationId\":\"test\"}"), 400);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_conversation_new", Long.class).longValue());
    }

    @Test
    public void authenticationAndScopeAreCheckedAndCapturedBeforeAsyncExecution() throws Exception {
        SecurityContextHolder.clearContext();
        create("test", "anonymous", 401);
        login("alice");
        send(post(ROOT + "listConversations").content("{\"scope\":{\"tenantId\":\"other\",\"workspaceId\":\"workspace\"}}"), 403);
        var result = mvc.perform(post(ROOT + "createConversation").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "capture").content("{" + SCOPE + ",\"title\":\"captured\"}")).andReturn();
        SecurityContextHolder.clearContext();
        result.getAsyncResult(5000);
        result = mvc.perform(asyncDispatch(result)).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("captured", body(result).path("data").path("title").asString());
    }

    @Test
    public void serviceRejectsInvalidPagesWithoutHttpAndDoesNotMutateRequest() {
        var page = new PageParam();
        page.setCurrent(3L);
        page.setSize(2L);
        var result = fixture.conversations.list(page, fixture.context("alice", "list")).block();
        assertEquals(3, result.current());
        assertEquals(Long.valueOf(3), page.getCurrent());
        assertEquals(Long.valueOf(2), page.getSize());
        page.setSize(101L);
        assertThrows(IllegalArgumentException.class, () -> fixture.conversations.list(page, fixture.context("alice", "list")).block());
    }

    @Test
    public void orderedTurnPagesAreBoundedAndAlwaysBelongToTheTargetConversation() {
        // 构造合法耐久轮次投影，验证大于单页的排序；不伪造模型完成或预算结算事实。
        var now = Instant.now();
        var conversation = new Conversation("ordered", AdmissionFixture.owner("alice-id"), "ordered", 3, null, List.of(),
                Conversation.State.ACTIVE, now, now);
        fixture.executions.createConversation(conversation).block();
        String key = jdbc.queryForObject("SELECT id_key FROM arte_ai_conversation_new", String.class);
        for (int sequence : new int[]{3, 1, 2}) {
            var turn = new Turn("turn-" + sequence, "ordered", sequence, null, null,
                    new ChatMessage("message-" + sequence, ChatMessage.Role.USER, List.of(new ChatMessage.Text("text-" + sequence)), List.of(), null),
                    List.of(), null, 0, now, now);
            jdbc.update("INSERT INTO arte_ai_turn(id_key,conversation_key,sequence_no,workspace_snapshot) VALUES(?,?,?,?)",
                    UUID.randomUUID().toString(), key, sequence, fixture.codec.encode(turn));
        }
        var page = new PageParam();
        page.setSize(2L);
        var first = fixture.conversations.turns("ordered", 3, page, fixture.context("alice", "turns")).block();
        assertEquals(List.of(1L, 2L), first.records().stream().map(Turn::sequence).toList());
        page.setCurrent(2L);
        var second = fixture.conversations.turns("ordered", 3, page, fixture.context("alice", "turns")).block();
        assertEquals(3, second.total());
        assertEquals(List.of(3L), second.records().stream().map(Turn::sequence).toList());
        assertEquals(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND,
                assertThrows(AdmissionException.class, () -> fixture.conversations.turns("ordered", 3, page,
                        fixture.context("bob", "turns")).block()).getResultCode());
    }
}
