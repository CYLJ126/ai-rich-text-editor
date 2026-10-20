package com.arte.ainew.tool.execution;

import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.tool.chat.ToolChatQueryService;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiToolChatCommandController;
import com.arte.ainew.web.controller.NewAiToolChatController;
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

import java.time.Clock;
import java.util.List;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MVC 白名单、可靠 RUN 回执、停止门闩可见性及幂等；不启动监听端口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
public class ToolChatCommandHttpTest {

    ToolChatLoopTest.Rig r;
    MockMvc mvc;
    LocalValidatorFactoryBean validator;
    final JsonMapper json = JsonMapper.builder().build();
    final UsernamePasswordAuthenticationToken login = UsernamePasswordAuthenticationToken.authenticated("user", "unused", List.of());

    @Before
    public void setup() throws Exception {
        r = new ToolChatLoopTest.Rig();
        var contexts = new NewAiHttpContext(new ExecutionContextFactory(new FixedExecutionAuthorizationResolver(r.properties), Clock.systemUTC()), r.properties);
        ToolManagementAccess access = authentication -> {
            if (authentication == null || !authentication.isAuthenticated())
                throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("Login required");
            return r.session;
        };
        var commands = new NewAiToolChatCommandController(r.service, access, contexts, r.properties);
        var queries = new NewAiToolChatController(new ToolChatQueryService(r.runs, r.conversations, r.authorization, r.f.executions, r.f.payloads), contexts, r.properties);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(commands, queries).setControllerAdvice(new ConversationExceptionHandler()).setValidator(validator).build();
        SecurityContextHolder.getContext().setAuthentication(login);
    }

    @After
    public void close() throws Exception {
        SecurityContextHolder.clearContext();
        if (validator != null) validator.close();
        if (r != null) r.close();
    }

    String body() {
        return """
                {"scope":{"tenantId":"tenant","workspaceId":"workspace"},"conversationId":"%s","expectedVersion":0,
                "text":"question","binding":{"type":"binding","id":"model","version":"v1"},
                "capability":{"type":"capability","id":"model","version":"v1"},"modelBudgetRef":"wallet","maxInputTokens":20000,
                "generationOptions":{"maxOutputTokens":128,"temperature":null,"topP":null,"stopSequences":[]},
                "toolBindingIds":["binding"],"timeoutSeconds":90,"maxModelRounds":3,"maxToolCalls":4,"maxConcurrentTools":1,
                "budgetLimits":[{"currency":"CNY","amount":"1"}]}
                """.formatted(r.conversation).replace("\"toolBindingIds\":[\"binding\"]", "\"toolBindingIds\":[\"" + r.f.binding.bindingId() + "\"]");
    }

    String send(String endpoint, String body, String key, int status) throws Exception {
        var request = post("/ai-new/tool-chat/" + endpoint).principal(login).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
        var result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
        assertEquals(result.getResponse().getContentAsString(), status, result.getResponse().getStatus());
        return result.getResponse().getContentAsString();
    }

    @Test
    public void submitReplaysOriginalRunAndStopIsVisibleBeforeWorkerAdvances() throws Exception {
        var first = json.readTree(send("submitToolChat", body(), "http-submit", 202)).path("data");
        String id = first.path("executionId").asString();
        assertEquals("RUN", first.path("kind").asString());
        assertEquals(id, json.readTree(send("submitToolChat", body(), "http-submit", 202)).path("data").path("executionId").asString());
        assertEquals(0, r.modelCalls.get());
        assertEquals(0, r.count("arte_ai_invocation"));
        String query = "{\"scope\":{\"tenantId\":\"tenant\",\"workspaceId\":\"workspace\"},\"runId\":\"" + id + "\"}";
        var receipt = json.readTree(send("cancelRun", query, "cancel", 202)).path("data");
        assertTrue(receipt.path("cancellationRequested").asBoolean());
        var status = json.readTree(send("getRunStatus", query, "read", 200)).path("data");
        assertTrue(status.path("cancellationRequested").asBoolean());
        for (String secret : List.of("authorization", "budgetRef", "effectiveConfiguration", "inputSnapshot"))
            assertFalse(status.has(secret));
        var lease = r.runs.claim("http-worker", 1, java.time.Duration.ofSeconds(30)).block(ToolChatLoopTest.WAIT).getFirst();
        r.coordinator.process(lease).block(ToolChatLoopTest.WAIT);
        assertEquals("CANCELLED", json.readTree(send("getRunStatus", query, "read", 200)).path("data").path("state").asString());
    }

    @Test
    public void clientCannotInjectIntermediateMessagesIdentityOrInvalidBudget() throws Exception {
        for (String injected : List.of("\"parentExecutionId\":\"fake\"", "\"messages\":[]", "\"authorization\":{}", "\"tools\":[]")) {
            String original = body().trim();
            send("submitToolChat", original.substring(0, original.length() - 1) + "," + injected + "}", "bad", 400);
        }
        send("submitToolChat", body().replace("\"amount\":\"1\"", "\"amount\":\"-1\""), "bad", 400);
        assertEquals(0, r.count("arte_ai_tool_chat_run"));
    }

    @Test
    public void differentInputWithSameClientKeyIsConflict() throws Exception {
        send("submitToolChat", body(), "http-submit", 202);
        send("submitToolChat", body().replace("question", "changed"), "http-submit", 409);
        assertEquals(1, r.count("arte_ai_tool_chat_run"));
    }
}
