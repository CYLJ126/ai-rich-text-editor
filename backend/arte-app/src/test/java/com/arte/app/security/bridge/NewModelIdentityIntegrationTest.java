package com.arte.app.security.bridge;

import com.arte.ai.api.control.BindingManager;
import com.arte.ai.api.control.CapabilityCatalog;
import com.arte.ai.api.control.ConnectionManager;
import com.arte.ai.api.execution.BudgetService;
import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.ai.execution.ModelBindingResolver;
import com.arte.ai.gateway.DefaultModelGateway;
import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.capability.CapabilityDescriptor;
import com.arte.ai.model.capability.CapabilityKind;
import com.arte.ai.model.capability.SideEffectKind;
import com.arte.ai.model.definition.CapabilityDefinition;
import com.arte.ai.model.definition.ConnectionDefinition;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.definition.DefinitionStatus;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.model.generation.GenerationRequest;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.model.message.Message;
import com.arte.ai.model.message.MessageRole;
import com.arte.ai.model.message.TextPart;
import com.arte.app.ainew.*;
import com.arte.app.execution.support.JdbcAuditSink;
import com.arte.base.admission.LocalAdmissionController;
import com.arte.base.exception.BaseException;
import com.arte.base.execution.BoundedTaskExecutor;
import com.arte.base.model.admission.AdmissionKey;
import com.arte.base.model.admission.AdmissionLimits;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.security.CommonResourceAction;
import com.arte.base.model.security.SecretRef;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NewModelIdentityIntegrationTest extends SecurityBridgeFixture {
    @Test
    void modelEntryUsesRealAccountTaskApplicationAndExactConsentThenRevocationBlocksQueries() throws Exception {
        for (String file : List.of("arte-ai-new-model-ddl-mysql.sql", "arte-execution-support-ddl-mysql.sql")) {
            String sql = Files.readString(Path.of("scripts", file)).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
            try (var connection = datasource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            }
        }
        policy(CommonResourceAction.AI_PROCESS.code());
        policy(CommonResourceAction.EGRESS.code());
        String key = SecurityFingerprints.resource(CONNECTION);
        jdbc.update("INSERT INTO arte_security_connection VALUES (?, ?, ?, 'https://provider.example', TRUE, 1)", TENANT, WORKSPACE, key);
        jdbc.update("INSERT INTO arte_security_egress_rule VALUES (?, ?, 'new-ai', 'chat', ?, 'model.generate', TRUE, 1)", TENANT, WORKSPACE, key);
        var scope = new ExecutionScope(TENANT, WORKSPACE, ALICE);
        jdbc.update("INSERT INTO arte_ai_new_budget(scope_key,amount_limit,currency,enabled) VALUES (?,10,'USD',TRUE)", JdbcModelExecutionStore.budgetKey(scope));
        var definitions = new ConfiguredModelDefinitions(new CapabilityDefinition(new CapabilityDescriptor(new DefinitionRef("ai-capability", "model", "v1"), CapabilityKind.MODEL, null, null, Set.of("text"), SideEffectKind.EXTERNAL_EFFECT), DefinitionStatus.PUBLISHED),
                new ConnectionDefinition(new DefinitionRef("ai-connection", "model", "v1"), "test", "chat-completions", URI.create("https://provider.example/v1/chat/completions"), new SecretRef("TEST", null), DefinitionStatus.PUBLISHED), new DefinitionRef("ai-binding", "chat", "v1"), TENANT, WORKSPACE);
        var manager = new DataSourceTransactionManager(datasource);
        var store = new JdbcModelExecutionStore(jdbc, manager, clock);
        var quote = new BudgetQuote(BigDecimal.ONE, "USD");
        var calls = new AtomicInteger();
        var provider = new CompatibleChatProviderAdapter((connection, body, checkpoint) -> {
            calls.incrementAndGet();
            return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"answer\"}}]}".getBytes(StandardCharsets.UTF_8);
        }, "test", BigDecimal.ZERO, BigDecimal.ZERO, quote, 16384, 10);
        try (var tasks = new BoundedTaskExecutor(1, 1, clock); var admission = new LocalAdmissionController(1, 0, Map.of(new AdmissionKey(TENANT, "ai.interactive", null, null), new AdmissionLimits(1, 100, Duration.ofMinutes(1))), clock)) {
            var coordinator = new InvocationCoordinator(new ModelBindingResolver(new CapabilityCatalog(definitions), new ConnectionManager(definitions), new BindingManager(definitions)),
                    new DefaultModelGateway(List.of(provider)), new ExistingModelAccessPolicy(repository, clock, "new-ai"), egress, admission, tasks, store, store, new BudgetService(quote, store), new JdbcAuditSink(jdbc, manager, clock), clock);
            var service = new NewModelCallService(coordinator, contexts, consents, definitions, "new-ai");
            var input = new GenerationRequest(List.of(new Message(MessageRole.USER, List.of(new TextPart("question")))), new ModelOptions(null, 10), List.of(), null);
            assertThrows(AccessDeniedException.class, () -> service.generate(http, TENANT, WORKSPACE, "key", input, false));
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new NewModelController(service)).build();
            String body = "{\"tenantId\":\"" + TENANT + "\",\"workspaceId\":\"" + WORKSPACE + "\",\"messages\":[{\"role\":\"USER\",\"text\":\"question\"}],\"options\":{\"maxOutputTokens\":10},\"externalTransferConfirmed\":true}";
            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ai-new/model/generate").header("Idempotency-Key", "key")
                    .contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted()).andReturn();
            var accepted = new com.arte.base.model.execution.AcceptedExecution(
                    com.google.gson.JsonParser.parseString(response.getResponse().getContentAsString()).getAsJsonObject().get("executionId").getAsString(), "ai.model", "ACCEPTED",
                    URI.create(com.google.gson.JsonParser.parseString(response.getResponse().getContentAsString()).getAsJsonObject().get("statusUri").getAsString()),
                    URI.create(com.google.gson.JsonParser.parseString(response.getResponse().getContentAsString()).getAsJsonObject().get("eventsUri").getAsString()));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (store.find(scope, accepted.executionId()).orElseThrow().status() != ExecutionStatus.SUCCEEDED && System.nanoTime() < end)
                Thread.sleep(5);
            assertEquals(ExecutionStatus.SUCCEEDED, store.find(scope, accepted.executionId()).orElseThrow().status());
            assertEquals(1, calls.get());
            assertEquals(accepted, service.generate(http, TENANT, WORKSPACE, "key", input, true));
            assertEquals(1, calls.get());
            var viewer = service.viewer(http, TENANT, WORKSPACE);
            assertEquals(3, coordinator.events(viewer, accepted.executionId(), -1, 10).size());
            jdbc.update("UPDATE arte_security_connection SET enabled=FALSE");
            assertThrows(BaseException.class, () -> coordinator.find(viewer, accepted.executionId()));
            assertThrows(BaseException.class, () -> service.generate(http, TENANT, WORKSPACE, "another", input, true));
            assertEquals(1, calls.get());
            jdbc.update("UPDATE arte_security_connection SET enabled=TRUE");
            jdbc.update("UPDATE arte_rbac_user SET status='3' WHERE id=1");
            assertThrows(AccessDeniedException.class, () -> service.viewer(http, TENANT, WORKSPACE));
        }
    }
}
