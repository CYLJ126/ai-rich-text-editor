package com.arte.ainew.tool.chat;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.pojo.tool.ToolChatRun;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiToolChatController;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MVC 白名单、当前认证／授权、归档及游标契约；不启动服务监听端口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 18:20 ✾
 */
public class ToolChatHttpTest {

    ToolChatHistoryStoreTest.Chat c;
    MockMvc mvc;
    LocalValidatorFactoryBean validator;
    final AtomicBoolean denied = new AtomicBoolean();
    final JsonMapper json = JsonMapper.builder().build();
    static final String QUERY = "{\"scope\":{\"tenantId\":\"tenant\",\"workspaceId\":\"workspace\"},\"runId\":\"run\"}";

    @Before
    public void setup() {
        c = new ToolChatHistoryStoreTest.Chat();
        c.accept();
        var properties = new NewAiProperties(true, "test", "release", null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "DATABASE");
        var factory = new ExecutionContextFactory((name, tenant, space, scopes) -> {
            if (denied.get() || !name.equals("user") || !tenant.equals("tenant") || !space.equals("workspace"))
                return Mono.error(new AccessDeniedException("Denied"));
            var original = c.context.authorization();
            return Mono.just(new ExecutionAuthorization(original.principal(), tenant, space, scopes, original.grantRef()));
        }, Clock.systemUTC());
        var controller = new NewAiToolChatController(c.queries, new NewAiHttpContext(factory, properties), properties);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ConversationExceptionHandler()).setValidator(validator).build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("user", "unused", List.of()));
    }

    @After
    public void cleanup() {
        SecurityContextHolder.clearContext();
        if (validator != null) validator.close();
        if (c != null) c.close();
    }

    String send(String endpoint, String body, int status) throws Exception {
        var result = mvc.perform(post("/ai-new/tool-chat/" + endpoint).contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "en-US").content(body)).andReturn();
        if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
        assertEquals(result.getResponse().getContentAsString(), status, result.getResponse().getStatus());
        return result.getResponse().getContentAsString();
    }

    @Test
    public void statusAndEventsExposeOnlySafeProjectionAndFinalAnswerUsesRealChildId() throws Exception {
        String status = send("getRunStatus", QUERY, 200);
        assertEquals("ACCEPTED", json.readTree(status).path("data").path("state").asString());
        for (String field : List.of("authorization", "owner", "binding", "inputSnapshot", "idempotencyKey", "budgetRef", "stateDigest"))
            assertFalse(status.contains("\"" + field + "\""));
        send("getRunResult", QUERY, 409);
        var events = QUERY.substring(0, QUERY.length() - 1) + ",\"afterSequence\":0,\"limit\":10}";
        assertEquals(1, json.readTree(send("replayRunEvents", events, 200)).path("data").size());
        var child = c.finalModel(ModelResult.FinishReason.STOP);
        assertTrue(c.r.advance(c.finish(ToolChatRun.State.SUCCEEDED, child.result())).successful());
        var answer = json.readTree(send("getRunResult", QUERY, 200)).path("data");
        assertEquals("run", answer.path("runId").asString());
        assertEquals(child.request().context().executionId(), answer.path("finalInvocationId").asString());
    }

    @Test
    public void missingAuthenticationRevokedScopeAndArchivedConversationRejectReads() throws Exception {
        SecurityContextHolder.clearContext();
        send("getRunStatus", QUERY, 401);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("user", "unused", List.of()));
        denied.set(true);
        send("getRunStatus", QUERY, 403);
        denied.set(false);
        send("getRunStatus", QUERY.replace("workspace\"", "other\""), 403);
        send("getRunStatus", QUERY.replace("\"run\"", "\"missing\""), 404);
        c.archived.set(true);
        send("getRunStatus", QUERY, 404);
    }

    @Test
    public void budgetQueryKeepsLegacyUnknownDistinctFromZeroAndHonorsArchive() throws Exception {
        assertEquals(0, json.readTree(send("getRunBudget", QUERY, 200)).path("data").size());
        c.archived.set(true);
        send("getRunBudget", QUERY, 404);
    }

    @Test
    public void invalidCursorAndUnknownFieldsFailWithoutCreatingMoreExecutions() throws Exception {
        send("getRunStatus", QUERY.substring(0, QUERY.length() - 1) + ",\"owner\":\"client\"}", 400);
        for (String values : List.of("\"afterSequence\":-1,\"limit\":10", "\"afterSequence\":0,\"limit\":101", "\"afterSequence\":2,\"limit\":10"))
            send("replayRunEvents", QUERY.substring(0, QUERY.length() - 1) + "," + values + "}", 400);
        assertEquals(1, c.r.count("arte_ai_tool_chat_run"));
        assertEquals(0, c.r.count("arte_ai_invocation"));
    }
}
