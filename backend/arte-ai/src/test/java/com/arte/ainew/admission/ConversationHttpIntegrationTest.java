package com.arte.ainew.admission;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.ConversationHttpContext;
import com.arte.ainew.web.controller.NewAiConversationController;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.PageParam;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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

/** 真实 MVC 参数绑定、响应序列化、认证上下文与隔离数据库分页；不调用外部模型。 */
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
                new ConversationHttpContext(fixture.factory, fixture.properties));
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

    @Test
    public void allHttpMethodsDeclareAuthenticationForApplicationSecurityDiscovery() {
        // arte-app 的 URL 收集器只检查方法级 @PreAuthorize，遗漏会将该路径加入 denyAll。
        var methods = Arrays.stream(NewAiConversationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)).toList();
        assertEquals(4, methods.size());
        for (var method : methods) {
            assertNotNull(method.getAnnotation(PreAuthorize.class));
            assertEquals("isAuthenticated()", method.getAnnotation(PreAuthorize.class).value());
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
        for (int index = 0; index < 3; index++) { createId("alice-" + index, "create-" + index); }
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
            jdbc.update("INSERT INTO arte_ai_turn(id_key,conversation_key,sequence_no,snapshot) VALUES(?,?,?,?)",
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
