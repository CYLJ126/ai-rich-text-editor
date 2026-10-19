package com.arte.ainew.tool.admission;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.pojo.tool.ToolRiskProfile;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.ToolManagementExceptionHandler;
import com.arte.ainew.web.controller.NewAiToolSubmissionController;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 公开请求白名单及实际受理，不依赖聊天配置或预算；固定测试身份不代表生产 RBAC 验收。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 09:47 ✾
 */
public class ToolSubmissionHttpTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCOPE = "\"scope\":{\"tenantId\":\"tenant\",\"workspaceId\":\"workspace\"}";

    private static final class Rig implements AutoCloseable {
        final ToolAdmissionFixture f = new ToolAdmissionFixture(
                new ToolExecutionPolicy(Duration.ofSeconds(5), 1, Duration.ZERO, 1024, 4096, ToolExecutionPolicy.ApprovalMode.NONE),
                new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY, false, ToolRiskProfile.Idempotency.NONE, Set.of("ai:invoke"), Set.of()));
        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        final MockMvc mvc;
        Duration authorizationDelay = Duration.ZERO;

        Rig() throws Exception {
            beans.registerSingleton("service", f.service);
            var properties = new NewAiProperties(false, "appDataSource", "release", null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            var factory = new ExecutionContextFactory((name, tenant, workspace, scopes) -> Mono.just(new ExecutionAuthorization(
                    new ExecutionPrincipal("subject", name, ExecutionPrincipal.Kind.USER), tenant, workspace, scopes, "test-grant")).delayElement(authorizationDelay), f.clock);
            validator.afterPropertiesSet();
            mvc = standaloneSetup(new NewAiToolSubmissionController(beans.getBeanProvider(com.arte.ainew.application.tool.admission.ToolAdmissionService.class),
                    new NewAiHttpContext(factory, properties), properties)).setControllerAdvice(new ToolManagementExceptionHandler())
                    .setValidator(validator).setAsyncRequestTimeout(15000).build();
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        }

        String body() {
            return "{" + SCOPE + ",\"input\":{\"bindingId\":\"" + f.binding.bindingId() + "\",\"arguments\":{},\"timeoutSeconds\":5}}";
        }

        MvcResult postTo(String path, String body, String key) throws Exception {
            var builder = post("/ai-new/tool/" + path).contentType("application/json").content(body);
            if (key != null) builder.header("Idempotency-Key", key);
            var result = mvc.perform(builder).andReturn();
            return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)).andReturn() : result;
        }

        public void close() throws Exception {
            SecurityContextHolder.clearContext();
            validator.close();
            f.close();
        }
    }

    @Test
    public void previewThenSubmitAndReplayShareOneDurableInvocation() throws Exception {
        try (var r = new Rig()) {
            var preview = r.postTo("previewInvocation", r.body(), null);
            assertEquals(preview.getResponse().getContentAsString(), 200, preview.getResponse().getStatus());
            var data = JSON.readTree(preview.getResponse().getContentAsString()).get("data");
            assertEquals("FREE", data.get("billingMode").asString());
            assertFalse(data.has("target"));
            assertFalse(data.has("authorization"));
            assertEquals(0, r.f.count("arte_ai_tool_binding_resolution"));
            var body = r.body().substring(0, r.body().length() - 1) + ",\"expectedPlanDigest\":\"" + data.get("planDigest").asString() + "\"}";
            var first = r.postTo("invokeTool", body, "same");
            var replay = r.postTo("invokeTool", body, "same");
            assertEquals(first.getResponse().getContentAsString(), 202, first.getResponse().getStatus());
            assertEquals(202, replay.getResponse().getStatus());
            assertEquals(JSON.readTree(first.getResponse().getContentAsString()).get("data"), JSON.readTree(replay.getResponse().getContentAsString()).get("data"));
            assertEquals(1, r.f.count("arte_ai_invocation"));
            assertEquals(0, r.f.count("arte_ai_reservation"));
        }
    }

    @Test
    public void rejectsUnknownFieldsDuplicateArgumentsAndMissingConfirmationOrKey() throws Exception {
        try (var r = new Rig()) {
            for (var body : List.of(r.body().replace("\"input\":", "\"owner\":\"other\",\"input\":"),
                    r.body().replace("\"arguments\":{}", "\"arguments\":{\"text\":\"a\",\"text\":\"b\"}"),
                    r.body().replace("\"arguments\":{}", "\"arguments\":null"),
                    r.body().replace("\"timeoutSeconds\":5", "\"timeoutSeconds\":0")))
                assertEquals(400, r.postTo("previewInvocation", body, null).getResponse().getStatus());
            assertEquals(400, r.postTo("invokeTool", r.body(), "key").getResponse().getStatus());
            assertEquals(400, r.postTo("invokeTool", r.body(), null).getResponse().getStatus());
            assertEquals(0, r.f.count("arte_ai_invocation"));
        }
    }

    @Test
    public void requestedDeadlineIncludesCurrentIdentityResolution() throws Exception {
        try (var r = new Rig()) {
            var previewBody = r.body().replace("\"timeoutSeconds\":5", "\"timeoutSeconds\":1");
            var preview = r.postTo("previewInvocation", previewBody, null);
            assertEquals(200, preview.getResponse().getStatus());
            var digest = JSON.readTree(preview.getResponse().getContentAsString()).get("data").get("planDigest").asString();
            var submit = previewBody.substring(0, previewBody.length() - 1) + ",\"expectedPlanDigest\":\"" + digest + "\"}";
            r.authorizationDelay = Duration.ofMillis(1200);
            var result = r.postTo("invokeTool", submit, "deadline");
            assertEquals(result.getResponse().getContentAsString(), 504, result.getResponse().getStatus());
            assertEquals(0, r.f.count("arte_ai_invocation"));
        }
    }

    @Test
    public void missingTrustedHostServiceFailsClosed() throws Exception {
        try (var r = new Rig()) {
            r.beans.destroySingleton("service");
            assertEquals(503, r.postTo("previewInvocation", r.body(), null).getResponse().getStatus());
            assertEquals(0, r.f.count("arte_ai_invocation"));
        }
    }
}
