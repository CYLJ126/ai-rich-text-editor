package com.arte.app.platform.persistence;

import com.arte.ainew.api.execution.ExecutionControl;
import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.application.execution.InvocationBudgetStatusResolver;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.management.ToolManagementService;
import com.arte.ainew.config.*;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.ToolManagementExceptionHandler;
import com.arte.ainew.web.controller.NewAiInvocationController;
import com.arte.ainew.web.controller.NewAiToolController;
import com.arte.ainew.web.controller.NewAiToolInvocationController;
import com.arte.ainew.web.controller.NewAiToolSubmissionController;
import com.arte.app.platform.ai.AiControlData;
import com.arte.app.platform.ai.PlatformToolExecutionConfiguration;
import com.arte.app.platform.ai.PlatformToolInvocationConfiguration;
import com.arte.app.platform.ai.PlatformToolManagementConfiguration;
import com.arte.app.platform.ai.tool.LocalTextTools;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.core.i18n.MessageUtils;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.platform.Scope;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 本机验收使用正式 Spring 装配、当前平台授权与独立测试数据库；不替换 TOOL 实现或授权端口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 11:18 ✾
 */
final class ToolAcceptanceRig implements AutoCloseable {
    final DatabaseAiControlIntegrationTest base = new DatabaseAiControlIntegrationTest();
    final JsonMapper json = JsonMapper.builder().build();
    final Scope scope;
    final LocalDateTime accountCreatedAt;
    final Connection ddl;
    final LocalTextTools tools = new LocalTextTools(Clock.systemUTC());
    final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();

    ToolAcceptanceRig() throws Exception {
        base.f.setup();
        // 登录快照取数据库实际 DATETIME 值，避免测试端 Timestamp 的时区转换改变账户生命周期证据。
        accountCreatedAt = base.f.jdbc.queryForObject("SELECT create_time FROM arte_rbac_user WHERE id=1",
                (row, index) -> row.getObject(1, LocalDateTime.class));
        ddl = base.f.source.getConnection();
        // H2 约束表达式持有建表会话；验收期间保持此连接存活，MySQL 仍执行原始 DDL。
        for (String file : List.of("../arte-ai/scripts/arte-ai-new-ddl-mysql.sql", "scripts/arte-platform-ai-control-ddl-mysql.sql")) {
            String sql = Files.readString(Path.of(file));
            if (base.f.mysql == null) sql = sql.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "")
                    .replaceAll("(?i)ENGINE\\s*=\\s*InnoDB", "").replaceAll("(?im)^SET\\s+[^;]*;", "");
            ScriptUtils.executeSqlScript(ddl,
                    new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
        var jdbc = base.f.jdbc;
        jdbc.execute("CREATE TABLE arte_rbac_relation(id BIGINT AUTO_INCREMENT PRIMARY KEY,source VARCHAR(64),binding_type VARCHAR(32),target VARCHAR(64), UNIQUE(source,binding_type,target))");
        jdbc.execute("CREATE TABLE arte_rbac_role(role_code VARCHAR(64) PRIMARY KEY,status VARCHAR(4))");
        for (var permission : List.of("aiTool:list", "aiTool:invoke", "aiTool:manage", "aiConfiguration:manage"))
            jdbc.update("INSERT INTO arte_rbac_relation(source,binding_type,target) VALUES('alice','user_to_operation',?)", permission);
        scope = base.f.initialize().value().workspace().scope();
        setAiEnabled(true);
        validator.afterPropertiesSet();
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        MessageUtils.setMessageSource(messages);
    }

    ApplicationContextRunner context() {
        return context(base.f.source);
    }

    ApplicationContextRunner context(DataSource source) {
        return base.context(source).withUserConfiguration(PlatformToolManagementConfiguration.class, PlatformToolInvocationConfiguration.class,
                        PlatformToolExecutionConfiguration.class)
                .withConfiguration(AutoConfigurations.of(NewAiToolConfiguration.class, NewAiToolGatewayConfiguration.class,
                        NewAiToolExecutionConfiguration.class, NewAiToolManagementConfiguration.class, NewAiExecutionConfiguration.class))
                .withPropertyValues("arte.ai-new-tool.enabled=true", "arte.ai-new-tool.discovery.refresh-on-startup=true", "arte.ai-new-execution.enabled=true", "arte.ai-new-execution.worker-enabled=false",
                        "arte.ai-new-execution.concurrency=6", "arte.ai-new-execution.scheduling.enabled=true",
                        "arte.ai-new-execution.scheduling.slots.generation=2", "arte.ai-new-execution.scheduling.slots.tool-short=2",
                        "arte.ai-new-execution.scheduling.slots.tool-long=1", "arte.ai-new-execution.scheduling.slots.outcome-check=1",
                        "arte.ai-new-execution.scheduling.tool-manifest=" + tools.manifest());
    }

    void setAiEnabled(boolean enabled) {
        var value = new AiControlData.WorkspaceConfiguration(scope, new AiControlData.Preset(enabled, List.of(), List.of()), 0, 0);
        int rows = base.f.jdbc.update("UPDATE arte_ai_workspace_configuration SET payload=? WHERE tenant_id=? AND workspace_id=?",
                base.f.json.write(value), scope.tenantId(), scope.workspaceId());
        if (rows == 0)
            base.f.jdbc.update("INSERT INTO arte_ai_workspace_configuration(tenant_id,workspace_id,payload,version) VALUES(?,?,?,0)",
                    scope.tenantId(), scope.workspaceId(), base.f.json.write(value));
    }

    MockMvc mvc(ApplicationContext c) {
        return mvc(c, false);
    }

    MockMvc mvc(ApplicationContext c, boolean observed) {
        var http = new NewAiHttpContext(c.getBean(ExecutionContextFactory.class), c.getBean(NewAiProperties.class));
        var builder = standaloneSetup(new NewAiToolController(c.getBean(ToolManagementService.class),
                        c.getBean(ToolManagementAccess.class)),
                new NewAiToolSubmissionController(c.getBeanProvider(ToolAdmissionService.class), http, c.getBean(NewAiProperties.class)),
                new NewAiInvocationController(c.getBean(ExecutionControl.class),
                        c.getBean(ExecutionEventService.class), http, c.getBean(NewAiProperties.class),
                        c.getBean(InvocationBudgetStatusResolver.class)),
                new NewAiToolInvocationController(c.getBean(ExecutionControl.class), http, c.getBean(NewAiProperties.class)))
                .setControllerAdvice(new ToolManagementExceptionHandler(), new ConversationExceptionHandler()).setValidator(validator);
        if (observed) builder.addFilters(new com.arte.app.diagnostics.NewAiHttpObservationFilter());
        return builder.build();
    }

    JsonNode post(MockMvc mvc, String path, Map<String, ?> body, String key, int expectedStatus) throws Exception {
        var result = request(mvc, path, json.writeValueAsString(body), key);
        if (expectedStatus != result.getResponse().getStatus() && result.getResolvedException() != null)
            result.getResolvedException().printStackTrace();
        assertEquals(expectedStatus, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }

    MvcResult request(MockMvc mvc, String path, String body, String key) throws Exception {
        var online = new UserOnlineInfo().setId(1L).setUserName("alice").setAccountCreatedAt(accountCreatedAt);
        var login = UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(online), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(login);
        try {
            var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ai-new/" + path)
                    .principal(login).contentType("application/json").content(body);
            if (key != null) builder.header("Idempotency-Key", key);
            var result = mvc.perform(builder).andReturn();
            if (result.getRequest().isAsyncStarted()) {
                result.getAsyncResult(15000);
                result = mvc.perform(asyncDispatch(result)).andReturn();
            }
            return result;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    void publish(MockMvc mvc) throws Exception {
        var refresh = post(mvc, "tool/refreshLocalCatalog", Map.of("scope", scope, "reason", "本机验收发现"), UUID.randomUUID().toString(), 200);
        assertTrue(refresh.get("nodeDiscoveryHealthy").asBoolean());
        assertEquals(tools.manifest(), refresh.get("nodeChecksum").asString());
        var review = Map.of("examples", List.of(Map.of("input", Map.of("text", "A😀中"), "output", Map.of("codePoints", 3))),
                "riskEvidence", "仅处理本次文本；无文件、网络、模型或业务资源访问；固定本地免费策略", "minimumApproval", "NONE");
        var result = post(mvc, "tool/publishTool", Map.of("scope", scope, "tool", LocalTextTools.TOOL, "expectedVersion", "0",
                "reason", "本机验收显式发布", "review", review), "publish", 200);
        assertNotNull(result);
    }

    String prepare(MockMvc mvc) throws Exception {
        return post(mvc, "tool/prepareDefaultBinding", Map.of("scope", scope, "tool", LocalTextTools.TOOL, "configuration", Map.of()),
                UUID.randomUUID().toString(), 200).get("bindingId").asString();
    }

    Map<String, Object> command(MockMvc mvc, String binding, String text) throws Exception {
        var input = Map.of("bindingId", binding, "arguments", Map.of("text", text), "timeoutSeconds", 5);
        var preview = post(mvc, "tool/previewInvocation", Map.of("scope", scope, "input", input), null, 200);
        return Map.of("scope", scope, "input", input, "expectedPlanDigest", preview.get("planDigest").asString());
    }

    @Override
    public void close() throws Exception {
        validator.close();
        SecurityContextHolder.clearContext();
        if (base.f.mysql == null) base.f.jdbc.execute("DROP ALL OBJECTS");
        ddl.close();
        base.cleanup();
    }
}
