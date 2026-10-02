package com.arte.app.security.bridge;

import com.arte.ai.api.context.ContextService;
import com.arte.ai.api.control.BindingManager;
import com.arte.ai.api.control.CapabilityCatalog;
import com.arte.ai.api.control.ConnectionManager;
import com.arte.ai.api.conversation.ChatService;
import com.arte.ai.api.conversation.ConversationService;
import com.arte.ai.api.execution.BudgetService;
import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.ai.execution.ModelBindingResolver;
import com.arte.ai.gateway.DefaultModelGateway;
import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.capability.CapabilityDescriptor;
import com.arte.ai.model.capability.CapabilityKind;
import com.arte.ai.model.capability.SideEffectKind;
import com.arte.ai.model.conversation.ChatTurnResult;
import com.arte.ai.model.conversation.Conversation;
import com.arte.ai.model.conversation.TurnStatus;
import com.arte.ai.model.definition.CapabilityDefinition;
import com.arte.ai.model.definition.ConnectionDefinition;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.definition.DefinitionStatus;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.spi.store.ChatStore;
import com.arte.app.ainew.*;
import com.arte.app.execution.support.JdbcAuditSink;
import com.arte.app.testsupport.MySqlTestScripts;
import com.arte.base.admission.LocalAdmissionController;
import com.arte.base.exception.BaseException;
import com.arte.base.execution.BoundedTaskExecutor;
import com.arte.base.model.admission.AdmissionKey;
import com.arte.base.model.admission.AdmissionLimits;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.security.CommonResourceAction;
import com.arte.base.model.security.SecretRef;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NewChatIntegrationTest extends SecurityBridgeFixture {
    ConfiguredModelDefinitions definitions;
    JdbcChatStore chatStore;
    JdbcModelExecutionStore executions;
    InvocationCoordinator coordinator;
    ExistingChatAccessPolicy access;
    DataSourceTransactionManager manager;
    NewChatCallService service;
    ConversationService conversations;
    BoundedTaskExecutor tasks;
    LocalAdmissionController admission;
    Connection schemaConnection;
    AtomicInteger calls = new AtomicInteger();
    List<String> requests = new CopyOnWriteArrayList<>();
    volatile CountDownLatch block;
    volatile boolean unknown;
    MockMvc mvc;
    final ExecutionScope scope = new ExecutionScope(TENANT, WORKSPACE, ALICE);

    @BeforeEach
    void chatSetup() throws Exception {
        schemaConnection = datasource.getConnection();
        for (String name : List.of("arte-ai-new-model-ddl-mysql.sql", "arte-execution-support-ddl-mysql.sql", "arte-ai-new-chat-ddl-mysql.sql")) {
            String sql = Files.readString(Path.of("scripts", name));
            if (name.contains("chat")) sql = sql.substring(sql.indexOf("-- CHAT_TABLES_BEGIN"));
            ScriptUtils.executeSqlScript(schemaConnection, MySqlTestScripts.h2Resource(sql));
        }
        policy(CommonResourceAction.AI_PROCESS.code());
        policy(CommonResourceAction.EGRESS.code());
        String connectionKey = JdbcSecurityRepository.connectionKey(CONNECTION);
        jdbc.update("INSERT INTO arte_security_connection VALUES (?,?,?,'https://provider.example',TRUE,1)", TENANT, WORKSPACE, connectionKey);
        jdbc.update("INSERT INTO arte_security_egress_rule VALUES (?,?,'new-ai','chat',?,'model.generate',TRUE,1)", TENANT, WORKSPACE, connectionKey);
        jdbc.update("INSERT INTO arte_ai_new_budget(scope_key,amount_limit,currency,enabled) VALUES (?,100,'USD',TRUE)", JdbcModelExecutionStore.budgetKey(scope));
        manager = new DataSourceTransactionManager(datasource);
        definitions = new ConfiguredModelDefinitions(new CapabilityDefinition(new CapabilityDescriptor(new DefinitionRef("ai-capability", "model", "v1"), CapabilityKind.MODEL, null, null, Set.of("text"), SideEffectKind.EXTERNAL_EFFECT), DefinitionStatus.PUBLISHED),
                new ConnectionDefinition(new DefinitionRef("ai-connection", "model", "v1"), "test", "chat-completions", URI.create("https://provider.example/v1/chat/completions"), new SecretRef("TEST", null), DefinitionStatus.PUBLISHED),
                new DefinitionRef("ai-binding", "chat", "v1"), TENANT, WORKSPACE);
        var quote = new BudgetQuote(BigDecimal.ONE, "USD");
        var provider = new CompatibleChatProviderAdapter((connection, body, checkpoint) -> {
            int call = calls.incrementAndGet();
            requests.add(new String(body, StandardCharsets.UTF_8));
            if (block != null && !block.await(3, TimeUnit.SECONDS))
                throw new IllegalStateException("test provider blocked");
            if (unknown) throw new java.io.IOException("simulated unknown provider outcome");
            return ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"answer-" + call + "\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
        }, "test", BigDecimal.ZERO, BigDecimal.ZERO, quote, 16384, 10);
        executions = new JdbcModelExecutionStore(jdbc, manager, clock);
        tasks = new BoundedTaskExecutor(2, 2, clock);
        admission = new LocalAdmissionController(2, 0, Map.of(new AdmissionKey(TENANT, "ai.interactive", null, null), new AdmissionLimits(2, 100, Duration.ofMinutes(1))), clock);
        coordinator = new InvocationCoordinator(new ModelBindingResolver(new CapabilityCatalog(definitions), new ConnectionManager(definitions), new BindingManager(definitions)),
                new DefaultModelGateway(List.of(provider)), new ExistingModelAccessPolicy(repository, clock, "new-ai"), egress, admission, tasks, executions, executions, new BudgetService(quote, executions), new JdbcAuditSink(jdbc, manager, clock), clock);
        chatStore = new JdbcChatStore(jdbc, manager);
        access = new ExistingChatAccessPolicy(repository, definitions, "new-ai", clock);
        wire(chatStore, 4096);
    }

    void wire(ChatStore store, int bytes) {
        conversations = new ConversationService(store, access, clock);
        var prepared = new ContextService(store, coordinator, clock, bytes, 32, Duration.ofMinutes(10));
        var chats = new ChatService(conversations, prepared, store, coordinator, definitions.capabilityRef(), clock, 10);
        service = new NewChatCallService(conversations, chats, contexts, consents, definitions, "new-ai", manager);
        mvc = MockMvcBuilders.standaloneSetup(new NewChatController(service)).build();
    }

    @AfterEach
    void chatCleanup() throws Exception {
        if (block != null) block.countDown();
        if (tasks != null) tasks.close();
        if (admission != null) admission.close();
        if (schemaConnection != null) schemaConnection.close();
    }

    Conversation create() {
        return service.create(http, TENANT, WORKSPACE, "我的聊天");
    }

    ChatTurnResult submit(Conversation conversation, String text, String key) {
        return service.submit(http, TENANT, WORKSPACE, conversation.conversationId(), conversation.version(), text, null, key, true);
    }

    Conversation current(Conversation conversation) {
        return service.find(http, TENANT, WORKSPACE, conversation.conversationId());
    }

    ChatTurnResult finished(Conversation conversation, String turn) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        ChatTurnResult result;
        do {
            result = service.turn(http, TENANT, WORKSPACE, conversation.conversationId(), turn);
            if (result.execution() != null && result.execution().status() != ExecutionStatus.ACCEPTED && result.execution().status() != ExecutionStatus.RUNNING)
                return result;
            Thread.sleep(5);
        } while (System.nanoTime() < until);
        fail("model did not finish");
        return null;
    }

    @Test
    void metadataSupportsLiteralSearchVersionsAndSoftDeletionWithoutProviderCalls() {
        var conversation = create();
        assertEquals(1, service.list(http, TENANT, WORKSPACE, "聊天", 0, 10).size());
        assertTrue(service.list(http, TENANT, WORKSPACE, "%", 0, 10).isEmpty());
        var renamed = service.rename(http, TENANT, WORKSPACE, conversation.conversationId(), 1, "新标题");
        assertEquals(2, renamed.version());
        assertThrows(BaseException.class, () -> service.rename(http, TENANT, WORKSPACE, conversation.conversationId(), 1, "过期修改"));
        service.delete(http, TENANT, WORKSPACE, conversation.conversationId(), 2);
        assertThrows(BaseException.class, () -> current(conversation));
        assertTrue(service.list(http, TENANT, WORKSPACE, null, 0, 10).isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_conversation WHERE status='DELETED'", Integer.class));
        assertEquals(0, calls.get());
    }

    @Test
    void textHistoryIdempotencyAndRestartUsePersistedFacts() throws Exception {
        var conversation = create();
        var first = finished(conversation, submit(conversation, "问题一", "first").turn().turnId());
        assertEquals(ExecutionStatus.SUCCEEDED, first.execution().status());
        assertFalse(first.turn().occupiesConversationSlot());
        wire(new JdbcChatStore(jdbc, manager), 4096);
        var repeated = submit(conversation, "问题一", "first");
        assertEquals(first.turn().turnId(), repeated.turn().turnId());
        assertEquals(1, calls.get());
        var next = finished(conversation, submit(current(conversation), "问题二", "second").turn().turnId());
        var snapshot = chatStore.snapshot(scope, next.turn().contextSnapshotId()).orElseThrow();
        assertEquals(3, snapshot.messages().size());
        assertEquals(first.turn().executionId(), snapshot.history().getFirst().executionId());
        assertEquals(2, requests.size());
        assertTrue(requests.getLast().contains("answer-1"));
        assertEquals(2, service.history(http, TENANT, WORKSPACE, conversation.conversationId(), Long.MAX_VALUE, 10).size());
        assertThrows(BaseException.class, () -> submit(conversation, "不同输入", "first"));
    }

    @Test
    void regenerationPreservesResultsUsesOriginalContextAndOnlyContinuesLatestQuestion() throws Exception {
        var conversation = create();
        var original = finished(conversation, submit(conversation, "问题", "first").turn().turnId());
        var regeneration = service.regenerate(http, TENANT, WORKSPACE, conversation.conversationId(), current(conversation).version(), original.turn().turnId(), null, "regen", true);
        regeneration = finished(conversation, regeneration.turn().turnId());
        assertNotEquals(original.turn().executionId(), regeneration.turn().executionId());
        assertEquals(1, chatStore.snapshot(scope, regeneration.turn().contextSnapshotId()).orElseThrow().messages().size());
        assertFalse(requests.get(1).contains("answer-1"));
        var next = finished(conversation, submit(current(conversation), "追问", "next").turn().turnId());
        var snapshot = chatStore.snapshot(scope, next.turn().contextSnapshotId()).orElseThrow();
        assertEquals(1, snapshot.history().size());
        assertEquals(regeneration.turn().turnId(), snapshot.history().getFirst().turnId());
        assertTrue(requests.getLast().contains("answer-2"));
        assertFalse(requests.getLast().contains("answer-1"));
        assertThrows(BaseException.class, () -> service.regenerate(http, TENANT, WORKSPACE, conversation.conversationId(), current(conversation).version(), original.turn().turnId(), null, "old-regen", true));
    }

    @Test
    void scopeOwnershipAndCurrentRevocationProtectMetadataAndResults() throws Exception {
        var conversation = create();
        var turn = finished(conversation, submit(conversation, "私有问题", "first").turn().turnId());
        jdbc.update("INSERT INTO arte_security_member VALUES (?,?,2,TRUE,1)", TENANT, WORKSPACE);
        login("bob", 2);
        assertThrows(BaseException.class, () -> current(conversation));
        assertTrue(service.list(http, TENANT, WORKSPACE, null, 0, 10).isEmpty());
        assertThrows(BaseException.class, () -> service.turn(http, TENANT, WORKSPACE, conversation.conversationId(), turn.turn().turnId()));
        login("alice", 1);
        jdbc.update("UPDATE arte_security_member SET enabled=FALSE WHERE user_id=1");
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> current(conversation));
    }

    @Test
    void unconfirmedTransferAndRevokedConnectionCannotDispatch() {
        var conversation = create();
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.submit(http, TENANT, WORKSPACE, conversation.conversationId(), 1, "问题", null, "first", false));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_turn", Integer.class));
        jdbc.update("UPDATE arte_security_connection SET enabled=FALSE");
        assertThrows(BaseException.class, () -> submit(conversation, "问题", "first"));
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM arte_ai_new_turn", String.class));
        assertEquals(0, calls.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
    }

    @Test
    void standaloneModelEntryCannotReuseReservedChatKeys() {
        var standalone = new NewModelCallService(coordinator, contexts, consents, definitions, "new-ai");
        assertThrows(IllegalArgumentException.class, () -> standalone.generate(http, TENANT, WORKSPACE, "chat:reserved", null, true));
        assertEquals(0, calls.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
    }

    @Test
    void activeSubmissionBlocksConcurrentInputsAndMetadataChanges() throws Exception {
        var conversation = create();
        block = new CountDownLatch(1);
        var first = submit(conversation, "问题", "first");
        assertTrue(first.turn().occupiesConversationSlot());
        assertThrows(BaseException.class, () -> submit(current(conversation), "第二个问题", "second"));
        assertThrows(BaseException.class, () -> service.rename(http, TENANT, WORKSPACE, conversation.conversationId(), current(conversation).version(), "生成中修改"));
        assertThrows(BaseException.class, () -> service.delete(http, TENANT, WORKSPACE, conversation.conversationId(), current(conversation).version()));
        block.countDown();
        finished(conversation, first.turn().turnId());
        assertEquals(1, calls.get());
    }

    @Test
    void simultaneousSameKeyAcrossTwoServicesDispatchesOnce() throws Exception {
        var conversation = create();
        var caller = viewer();
        var core = new ChatService(conversations, new ContextService(chatStore, coordinator, clock, 4096, 32, Duration.ofMinutes(10)), chatStore, coordinator, definitions.capabilityRef(), clock, 10);
        var secondStore = new JdbcChatStore(jdbc, manager);
        var otherCore = new ChatService(new ConversationService(secondStore, access, clock), new ContextService(secondStore, coordinator, clock, 4096, 32, Duration.ofMinutes(10)), secondStore, coordinator, definitions.capabilityRef(), clock, 10);
        var consentTx = new org.springframework.transaction.support.TransactionTemplate(manager);
        consentTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            // Explicitly set the existing identity on each synchronous HTTP consent callback thread.
            var first = pool.submit(() -> {
                login("alice", 1);
                start.await();
                return core.submit(caller, conversation.conversationId(), 1, "问题", null, "same", request -> consentTx.execute(tx -> consents.confirm(http, request)));
            });
            var second = pool.submit(() -> {
                login("alice", 1);
                start.await();
                return otherCore.submit(caller, conversation.conversationId(), 1, "问题", null, "same", request -> consentTx.execute(tx -> consents.confirm(http, request)));
            });
            start.countDown();
            assertEquals(first.get(3, TimeUnit.SECONDS).turn().turnId(), second.get(3, TimeUnit.SECONDS).turn().turnId());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_turn", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
        String id = jdbc.queryForObject("SELECT turn_id FROM arte_ai_new_turn", String.class);
        assertEquals(ExecutionStatus.SUCCEEDED, finished(conversation, id).execution().status());
        assertEquals(1, calls.get());
    }

    ExecutionContext viewer() {
        return contexts.create(http, TENANT, WORKSPACE, "new-ai", "chat", Set.of(CommonResourceAction.AI_PROCESS.code(), CommonResourceAction.EGRESS.code()));
    }

    @Test
    void preparationRollbackLeavesInputRecoverableWithoutOrphanSnapshot() throws Exception {
        var conversation = create();
        var failReady = new AtomicBoolean(true);
        ChatStore faulted = (ChatStore) Proxy.newProxyInstance(ChatStore.class.getClassLoader(), new Class<?>[]{ChatStore.class}, (proxy, method, args) -> {
            Object result;
            try {
                result = method.invoke(chatStore, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
            if (method.getName().equals("ready") && failReady.get())
                throw new IllegalStateException("simulated readiness commit failure");
            return result;
        });
        wire(faulted, 4096);
        assertThrows(IllegalStateException.class, () -> submit(conversation, "问题", "first"));
        assertEquals("PREPARING", jdbc.queryForObject("SELECT status FROM arte_ai_new_turn", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_context_snapshot", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
        failReady.set(false);
        assertEquals(ExecutionStatus.SUCCEEDED, finished(conversation, submit(conversation, "问题", "first").turn().turnId()).execution().status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_context_snapshot", Integer.class));
    }

    @Test
    void cancellationRequestDoesNotReleaseSlotUntilExecutionTerminates() throws Exception {
        var conversation = create();
        block = new CountDownLatch(1);
        var accepted = submit(conversation, "问题", "first");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (calls.get() == 0 && System.nanoTime() < until) Thread.sleep(5);
        assertEquals(1, calls.get());
        service.cancel(http, TENANT, WORKSPACE, conversation.conversationId(), accepted.turn().turnId());
        assertNull(jdbc.queryForObject("SELECT slot_released_at FROM arte_ai_new_turn", java.sql.Timestamp.class));
        block.countDown();
        var completed = finished(conversation, accepted.turn().turnId());
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, completed.execution().status());
        assertFalse(completed.turn().occupiesConversationSlot());
    }

    @Test
    void corruptContextAndExpiredUnacceptedContextCannotBeDispatched() throws Exception {
        var conversation = create();
        ChatStore interrupted = (ChatStore) Proxy.newProxyInstance(ChatStore.class.getClassLoader(), new Class<?>[]{ChatStore.class}, (proxy, method, args) -> {
            if (method.getName().equals("withTurn")) {
                var current = chatStore.turn((ExecutionScope) args[0], (String) args[1]).orElseThrow();
                if (current.status() == TurnStatus.READY)
                    throw new IllegalStateException("stopped before model handoff");
            }
            try {
                return method.invoke(chatStore, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        wire(interrupted, 4096);
        assertThrows(IllegalStateException.class, () -> submit(conversation, "问题", "first"));
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM arte_ai_new_turn", String.class));
        wire(chatStore, 4096);
        jdbc.update("UPDATE arte_ai_new_context_snapshot SET used_input_bytes=1");
        assertThrows(BaseException.class, () -> submit(conversation, "问题", "first"));
        assertEquals(0, calls.get());
        jdbc.update("UPDATE arte_ai_new_context_snapshot SET used_input_bytes=6");
        clock.now = NOW.plus(Duration.ofMinutes(11));
        assertThrows(BaseException.class, () -> submit(conversation, "问题", "first"));
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM arte_ai_new_turn", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
    }

    @Test
    void springWiringKeepsConsentVisibleAcrossIndependentTransactions() throws Exception {
        clock.now = Instant.now();
        try (var spring = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            spring.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("chat", Map.of(
                    "arte.ai-new.chat.enabled", "true", "arte.ai-new.model.application-id", "new-ai", "arte.ai-new.model.max-output-tokens", "10")));
            spring.registerBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> jdbc);
            spring.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> manager);
            spring.registerBean(ConfiguredModelDefinitions.class, () -> definitions);
            spring.registerBean(InvocationCoordinator.class, () -> coordinator);
            spring.registerBean(JdbcSecurityRepository.class, () -> repository);
            spring.registerBean(ExistingIdentityAdapter.class, () -> identity);
            spring.registerBean(ExecutionContextFactory.class, () -> contexts);
            spring.registerBean(ExistingEgressPolicy.class, () -> egress);
            spring.registerBean(EgressConsentService.class, () -> consents);
            spring.register(TransactionWiring.class, NewChatConfiguration.class, NewChatController.class);
            spring.refresh();
            service = spring.getBean(NewChatCallService.class);
            var conversation = create();
            assertEquals(ExecutionStatus.SUCCEEDED, finished(conversation, submit(conversation, "问题", "first").turn().turnId()).execution().status());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_consent", Integer.class));
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class TransactionWiring {
    }

    @Test
    void associationWriteFailureRecoversWithoutAnotherProviderCallEvenAfterSnapshotExpiry() throws Exception {
        var conversation = create();
        var failAssociation = new AtomicBoolean(true);
        ChatStore faulted = (ChatStore) Proxy.newProxyInstance(ChatStore.class.getClassLoader(), new Class<?>[]{ChatStore.class}, (proxy, method, args) -> {
            if (method.getName().equals("accept") && failAssociation.get())
                throw new IllegalStateException("simulated association write failure");
            try {
                return method.invoke(chatStore, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        wire(faulted, 4096);
        assertThrows(IllegalStateException.class, () -> submit(conversation, "问题", "first"));
        String turnId = jdbc.queryForObject("SELECT turn_id FROM arte_ai_new_turn", String.class);
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM arte_ai_new_turn", String.class));
        // Existing acceptance is recovered before a fresh snapshot expiry check.
        clock.now = NOW.plus(Duration.ofMinutes(11));
        failAssociation.set(false);
        var recovered = finished(conversation, turnId);
        assertEquals(TurnStatus.ACCEPTED, recovered.turn().status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
        assertTrue(calls.get() <= 1);
    }

    @Test
    void contextByteLimitTrimsWholePairsAndRejectsOversizedCurrentText() throws Exception {
        wire(chatStore, 14);
        var conversation = create();
        finished(conversation, submit(conversation, "问题", "first").turn().turnId());
        var second = finished(conversation, submit(current(conversation), "新问", "second").turn().turnId());
        var snapshot = chatStore.snapshot(scope, second.turn().contextSnapshotId()).orElseThrow();
        assertEquals(1, snapshot.messages().size());
        assertTrue(snapshot.history().isEmpty());
        assertEquals(6, snapshot.budget().usedInputBytes());
        assertThrows(BaseException.class, () -> submit(current(conversation), "这是一个太长的问题", "large"));
        assertEquals(2, calls.get());
    }

    @Test
    void unknownProviderOutcomeIsRetainedAndDoesNotAutomaticallyRegenerate() throws Exception {
        unknown = true;
        var conversation = create();
        var first = finished(conversation, submit(conversation, "问题", "first").turn().turnId());
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, first.execution().status());
        assertEquals(TurnStatus.ACCEPTED, first.turn().status());
        assertEquals(first.turn().turnId(), submit(conversation, "问题", "first").turn().turnId());
        assertThrows(BaseException.class, () -> service.regenerate(http, TENANT, WORKSPACE, conversation.conversationId(), current(conversation).version(), first.turn().turnId(), null, "regen", true));
        assertEquals(1, calls.get());
        assertEquals("PENDING_RECONCILIATION", jdbc.queryForObject("SELECT budget_status FROM arte_ai_new_execution", String.class));
    }

    @Test
    void httpCreationSubmissionQueryAndVersionErrorsAreTyped() throws Exception {
        var created = mvc.perform(post("/api/ai-new/conversations").contentType("application/json").content("{\"tenantId\":\"personal-1\",\"workspaceId\":\"workspace-1\",\"title\":\"聊天\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonParser.parseString(created.getResponse().getContentAsString()).getAsJsonObject().get("conversationId").getAsString();
        String body = "{\"tenantId\":\"personal-1\",\"workspaceId\":\"workspace-1\",\"expectedVersion\":1,\"text\":\"问题\",\"externalTransferConfirmed\":true}";
        var accepted = mvc.perform(post("/api/ai-new/conversations/" + id + "/turns").header("Idempotency-Key", "http-first").contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andReturn();
        String turn = JsonParser.parseString(accepted.getResponse().getContentAsString()).getAsJsonObject().getAsJsonObject("turn").get("turnId").getAsString();
        mvc.perform(get("/api/ai-new/conversations/" + id + "/turns/" + turn).param("tenantId", TENANT).param("workspaceId", WORKSPACE)).andExpect(status().isOk());
        mvc.perform(patch("/api/ai-new/conversations/" + id).contentType("application/json").content("{\"tenantId\":\"personal-1\",\"workspaceId\":\"workspace-1\",\"expectedVersion\":1,\"title\":\"旧版本\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/ai-new/conversations/" + id + "/turns").header("Idempotency-Key", "invalid").contentType("application/json").content(body.replace("问题", "")))
                .andExpect(status().isBadRequest());
    }
}
