package com.arte.ainew.search;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiConversationSearchController;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public class ConversationSearchHttpTest {

    SearchQueryFixture q;
    MockMvc mvc;
    LocalValidatorFactoryBean validator;
    final JsonMapper json = JsonMapper.builder().build();
    static final String SCOPE = "\"scope\":{\"tenantId\":\"search-tenant\",\"workspaceId\":\"search-space\"}";

    @Before
    public void setup() {
        q = new SearchQueryFixture();
        var properties = new NewAiProperties(true, "appDataSource", "release", new NewAiProperties.Persistence(2, 100),
                new NewAiProperties.Limits(1000, 1000, 10000, Duration.ofSeconds(30), Duration.ofMinutes(5)), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "DATABASE");
        var controller = new NewAiConversationSearchController(q.service, new NewAiHttpContext(new ExecutionContextFactory(q.resolver, q.clock), properties), properties);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ConversationExceptionHandler()).setValidator(validator).build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", "unused", List.of()));
    }

    @After
    public void cleanup() {
        SecurityContextHolder.clearContext();
        if (validator != null) validator.close();
        if (q != null) q.close();
    }

    private String send(String endpoint, String body, int status) throws Exception {
        var result = mvc.perform(post("/ai-new/conversation/" + endpoint).contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "en-US").content(body)).andReturn();
        if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
        assertEquals(result.getResponse().getContentAsString(), status, result.getResponse().getStatus());
        return result.getResponse().getContentAsString();
    }

    private String search() {
        return "{" + SCOPE + ",\"query\":\"marker\",\"size\":1}";
    }

    @Test
    public void endpointsSerializeOnlySafeFieldsAndUseStructuredHighlights() throws Exception {
        q.title("one");
        q.title("two");
        var value = json.readTree(send("searchConversations", search(), 200));
        var data = value.path("data");
        assertTrue(value.path("success").asBoolean());
        assertEquals("TITLE", data.path("records").get(0).path("matchKind").asString());
        assertTrue(data.path("total").isNull());
        var wire = value.toString();
        for (var privateField : List.of("sourceRevision", "ownerKey", "pit-open", "fallbackText", "conversation-search-v1-g01"))
            assertFalse(wire.contains(privateField));
        String id = data.path("searchSessionId").asString();
        send("closeSearch", "{" + SCOPE + ",\"searchSessionId\":\"" + id + "\"}", 200);
        send("closeSearch", "{" + SCOPE + ",\"searchSessionId\":\"" + id + "\"}", 200);
    }

    @Test
    public void blankInvisibleQueriesGroupsAndInvalidCursorsReturnSafe400() throws Exception {
        send("searchConversations", "{" + SCOPE + ",\"query\":\" \",\"size\":1}", 400);
        send("searchConversations", "{" + SCOPE + ",\"query\":\"\\u200b\",\"size\":1}", 400);
        String groups = send("searchConversations", "{" + SCOPE + ",\"query\":\"marker\",\"size\":1,\"groupIds\":[\"g\"]}", 400);
        assertEquals("205049", json.readTree(groups).path("code").asString());
        String invalid = send("searchConversations", "{" + SCOPE + ",\"query\":\"marker\",\"size\":1,\"cursor\":\"bad\"}", 400);
        assertEquals("205047", json.readTree(invalid).path("code").asString());
        assertTrue(q.sessions.values.isEmpty());
    }

    @Test
    public void authenticationAndCurrentAuthorizationAreRequired() throws Exception {
        SecurityContextHolder.clearContext();
        send("searchConversations", search(), 401);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", "unused", List.of()));
        q.denied.set(true);
        send("searchConversations", search(), 403);
    }

    @Test
    public void expiredCursorIs410QuotaIs429AndDependencyResponseDoesNotExposeCause() throws Exception {
        q.title("one");
        var first = json.readTree(send("searchConversations", search(), 200)).path("data");
        send("searchConversations", search(), 200);
        send("searchConversations", search(), 200);
        assertEquals("205045", json.readTree(send("searchConversations", search(), 429)).path("code").asString());
        q.clock.advance(Duration.ofMinutes(11));
        String expired = "{" + SCOPE + ",\"query\":\"marker\",\"size\":1,\"cursor\":" + json.writeValueAsString(first.path("firstCursor").asString()) + "}";
        assertEquals("205044", json.readTree(send("searchConversations", expired, 410)).path("code").asString());
        q.index.failAt = q.index.count + 1;
        String failed = send("searchConversations", search(), 503);
        assertEquals("205048", json.readTree(failed).path("code").asString());
        assertFalse(failed.contains("INCOMPLETE_RESPONSE"));
    }

    @Test
    public void aroundReturnsExistingTurnContractAndExplicitMissingAndVersionConflicts() throws Exception {
        q.f.conversation("conv", "title");
        q.f.turn("conv", "turn", "question", List.of());
        String request = "{" + SCOPE + ",\"conversationId\":\"conv\",\"turnId\":\"turn\",\"expectedVersion\":0,\"size\":1}";
        var data = json.readTree(send("queryTurnsAround", request, 200)).path("data");
        assertEquals("turn", data.path("records").get(0).path("turnId").asString());
        send("queryTurnsAround", request.replace("\"expectedVersion\":0", "\"expectedVersion\":1"), 409);
        send("queryTurnsAround", request.replace("\"turnId\":\"turn\"", "\"turnId\":\"missing\""), 404);
        q.f.archived.add("conv");
        send("queryTurnsAround", request, 404);
    }
}
