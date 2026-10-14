package com.arte.app.platform.persistence;

import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiAdmissionConfiguration;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.app.platform.ai.AiControlData;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.identity.PlatformIdentityConfiguration;
import com.arte.app.platform.identity.RbacAccountMapperConfiguration;
import com.arte.app.platform.identity.RbacAccountReader;
import com.arte.app.platform.workspace.PlatformWorkspaceConfiguration;
import com.arte.app.service.platform.AuditSinkServiceImpl;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DatabaseAiControlIntegrationTest {
    final PlatformPersistenceIntegrationTest f = new PlatformPersistenceIntegrationTest();
    static final Duration WAIT = Duration.ofSeconds(15);

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = AuditSinkServiceImpl.class)
    static class Stores {
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity(proxyTargetClass = true)
    static class HttpSecurity {
    }

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        for (String file : List.of("../arte-ai/scripts/arte-ai-new-ddl-mysql.sql", "scripts/arte-platform-ai-control-ddl-mysql.sql")) {
            String sql = Files.readString(Path.of(file));
            if (f.mysql == null) sql = sql.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "")
                    .replaceAll("(?i)ENGINE\\s*=\\s*InnoDB", "").replaceAll("(?im)^SET\\s+[^;]*;", "");
            try (var connection = f.source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    DefinitionRef ref(String type, String id) {
        return new DefinitionRef(type, id, "v1");
    }

    Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CNY"));
    }

    NewAiProperties seed() {
        var capability = new CapabilityDescriptor(ref("capability", "text"), CapabilityDescriptor.Kind.GENERATION,
                FixedControlCatalog.INPUT_SCHEMA, FixedControlCatalog.OUTPUT_SCHEMA,
                Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING),
                CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
        var rate = new NewAiProperties.Rate(ref("rate", "text-price"), money("1"), money("2"));
        var connection = new ConnectionDefinition(ref("connection", "deepseek"), "deepseek", ref("protocol", "chat-completions-sse"),
                URI.create("https://api.deepseek.com/chat/completions"), null, ConnectionDefinition.State.ENABLED, Duration.ofSeconds(10), Duration.ofSeconds(120), 1000000);
        var binding = new ResolvedBinding(ref("binding", "text"), capability, connection.definition(), "deepseek-chat", 65536L, rate.definition());
        var grant = new NewAiProperties.Grant("alice", f.actor.principal().subjectId(), ExecutionPrincipal.Kind.USER, "legacy-tenant", "legacy-space", "legacy-v1", true,
                Set.of(AdmissionAuthorization.CONVERSATION, AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), Set.of("text"), Set.of("legacy-budget"));
        var budget = new NewAiProperties.Budget("legacy-budget", new ExecutionOwner("legacy-tenant", "legacy-space", f.actor.principal().subjectId()), money("100"), rate.definition());
        return new NewAiProperties(true, "appDataSource", "release-v1", null, null, List.of(grant), List.of(capability), List.of(binding), List.of(connection), List.of(rate), List.of(budget), "DATABASE");
    }

    String[] seedProperties() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var value = mapper.readValue(f.json.write(seed()), Map.class);
        var values = new ArrayList<String>();
        flatten("arte.ai-new", value, values);
        return values.toArray(String[]::new);
    }

    void flatten(String prefix, Object value, List<String> values) {
        if (value instanceof Map<?, ?> map)
            map.forEach((key, child) -> flatten(prefix + "." + key.toString().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(), child, values));
        else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) flatten(prefix + "[" + i + "]", list.get(i), values);
        } else if (value != null) values.add(prefix + "=" + value);
    }

    ApplicationContextRunner context() {
        return context(f.source);
    }

    ApplicationContextRunner context(DataSource source) {
        return new ApplicationContextRunner().withUserConfiguration(PlatformWorkspaceConfiguration.class, PlatformIdentityConfiguration.class,
                        PlatformPersistenceConfiguration.class, DatabaseAiControlService.class, Stores.class, NewAiAdmissionConfiguration.class)
                .withPropertyValues("arte.platform.identity.enabled=true", "arte.platform.identity.legacy-user-id-ceiling=0",
                        "arte.platform.persistence.enabled=true", "arte.platform.workspace.enabled=true", "arte.ai-new.enabled=true", "arte.ai-new.configuration-mode=DATABASE")
                .withBean("appDataSource", DataSource.class, () -> source)
                .withBean(RbacAccountReader.class, () -> new RbacAccountReader(source, new RbacAccountMapperConfiguration().identityUserMapper(source)))
                .withPropertyValues(seedProperties())
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withBean(ModelGateway.class, () -> new ModelGateway() {
                    public boolean supportsTextChat(ResolvedBinding b, ConnectionDefinition c) {
                        return true;
                    }

                    public Flux<com.arte.ainew.pojo.generation.GenerationSignal> generate(com.arte.ainew.pojo.execution.GatewayCall<com.arte.ainew.pojo.generation.GenerationRequest> call) {
                        return Flux.error(new AssertionError("Discovery must not invoke provider"));
                    }
                });
    }

    CommandMeta meta(String key) {
        return new CommandMeta(key, "database-ai-test");
    }

    ExecutionContext execution(DatabaseAiControlService source, Scope scope, Set<String> scopes, String key) {
        var auth = source.resolve("alice", scope.tenantId(), scope.workspaceId(), scopes).block(WAIT);
        return new ExecutionContext(UUID.randomUUID().toString(), "test", auth, Instant.now().plusSeconds(60), null, null, "release-v1", key);
    }

    @Test
    void newSpacesAndExistingPersonalSpaceWorkImmediatelyAndHaveIndependentBudgetsAndHistory() {
        var existing = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            var imported = db.importConfiguration(f.actor, meta("import"));
            assertEquals(1, imported.resultVersion());
            assertEquals(imported.operationId(), db.importConfiguration(f.actor, meta("import")).operationId());
            var prepared = db.ensure(f.actor, existing);
            assertEquals(0, prepared.configuration().version());
            var services = c.getBean(DefaultWorkspaceServices.class);
            var second = services.workspaces().create(f.actor, new WorkspaceCommands.CreateWorkspace(meta("new-space"), existing.tenantId(), "Second", "")).value().scope();
            assertNotNull(db.get(f.actor, second).configuration());
            var discoveries = c.getBean(ChatConfigurationQueryService.class);
            var firstContext = execution(db, existing, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null);
            var secondContext = execution(db, second, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null);
            var first = discoveries.discover(firstContext).block(WAIT).options().getFirst();
            var next = discoveries.discover(secondContext).block(WAIT).options().getFirst();
            assertNotEquals(first.budgetRefs(), next.budgetRefs());
            var accounts = c.getBean(BudgetAccountQueryService.class);
            assertEquals(money("100"), accounts.account(first.budgetRefs().getFirst(), firstContext).block(WAIT).limit());
            assertEquals(money("100"), accounts.account(next.budgetRefs().getFirst(), secondContext).block(WAIT).limit());
            var conversations = c.getBean(ConversationService.class);
            var created = conversations.create("First space only", null, List.of(), execution(db, existing, Set.of(AdmissionAuthorization.CONVERSATION), "conversation")).block(WAIT);
            assertEquals(1, conversations.list(new com.arte.core.pojo.PageParam(), execution(db, existing, Set.of(AdmissionAuthorization.CONVERSATION), null)).block(WAIT).total());
            assertEquals(0, conversations.list(new com.arte.core.pojo.PageParam(), execution(db, second, Set.of(AdmissionAuthorization.CONVERSATION), null)).block(WAIT).total());
            assertEquals(existing.workspaceId(), created.owner().workspaceId());
            assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
            db.ensure(f.actor, existing);
            db.ensure(f.actor, second);
            assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
            var legacy = db.resolve("alice", "legacy-tenant", "legacy-space", Set.of(AdmissionAuthorization.CONVERSATION)).block(WAIT);
            assertEquals("legacy-v1", legacy.grantRef());
        });
    }

    static final class SqlRecorder extends org.springframework.jdbc.datasource.DelegatingDataSource {
        final List<String> sql = new java.util.concurrent.CopyOnWriteArrayList<>();

        SqlRecorder(DataSource source) {
            super(source);
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            var connection = super.getConnection();
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String query)
                            sql.add(query);
                        try {
                            return method.invoke(connection, args);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        void assertNoLocks() {
            assertTrue(sql.stream().noneMatch(q -> q.toUpperCase().contains("FOR UPDATE")), sql.toString());
        }
    }

    @Test
    void readOperationsUseOneFreshSnapshotNoWriteLocksAndOnlyLoadRequestedConfiguration() {
        var scope = f.initialize().value().workspace().scope();
        var recorded = new SqlRecorder(f.source);
        context(recorded).run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            db.importConfiguration(f.actor, meta("import"));
            db.ensure(f.actor, scope);
            var conversation = c.getBean(ConversationService.class).create("Read snapshot", null, List.of(),
                    execution(db, scope, Set.of(AdmissionAuthorization.CONVERSATION), "read-conversation")).block(WAIT);
            var source = org.mockito.Mockito.spy(db);
            var http = new com.arte.ainew.web.NewAiHttpContext(new com.arte.ainew.context.ExecutionContextFactory(source, Clock.systemUTC()), c.getBean(NewAiProperties.class));
            var target = new com.arte.ainew.web.request.ConversationRequests.Scope(scope.tenantId(), scope.workspaceId());
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
            try {
                var discovery = http.read(target, Set.of(AdmissionAuthorization.INVOKE), WAIT, true,
                        c.getBean(ChatConfigurationQueryService.class)::discover);
                recorded.sql.clear();
                var options = discovery.block(WAIT).options();
                recorded.assertNoLocks();
                org.mockito.Mockito.verify(source).readSnapshot("alice", scope.tenantId(), scope.workspaceId(), Set.of(AdmissionAuthorization.INVOKE), true);
                assertEquals(1, recorded.sql.stream().filter(q -> q.contains("FROM arte_ai_control_head")).count());
                assertEquals(1, recorded.sql.stream().filter(q -> q.contains("FROM arte_ai_budget_definition")).count());
                assertEquals(1, recorded.sql.stream().filter(q -> q.contains("FROM arte_ai_workspace_configuration")).count());
                int discoveryQueries = recorded.sql.size();
                assertTrue(discoveryQueries <= 20, recorded.sql.toString());
                recorded.sql.clear();
                http.read(target, Set.of(AdmissionAuthorization.READ), WAIT, true,
                        ctx -> c.getBean(BudgetAccountQueryService.class).account(options.getFirst().budgetRefs().getFirst(), ctx)).block(WAIT);
                recorded.assertNoLocks();
                org.mockito.Mockito.verify(source).readSnapshot("alice", scope.tenantId(), scope.workspaceId(), Set.of(AdmissionAuthorization.READ), true);
                assertEquals(1, recorded.sql.stream().filter(q -> q.contains("FROM arte_ai_budget_definition")).count());
                int budgetQueries = recorded.sql.size();
                assertTrue(budgetQueries <= 21, recorded.sql.toString());
                recorded.sql.clear();
                http.read(target, Set.of(AdmissionAuthorization.CONVERSATION), WAIT, false,
                        ctx -> c.getBean(ConversationService.class).find(conversation.conversationId(), ctx)).block(WAIT);
                recorded.assertNoLocks();
                org.mockito.Mockito.verify(source).readSnapshot("alice", scope.tenantId(), scope.workspaceId(), Set.of(AdmissionAuthorization.CONVERSATION), false);
                assertTrue(recorded.sql.stream().noneMatch(q -> q.contains("arte_ai_control_head") || q.contains("arte_ai_control_revision") || q.contains("arte_ai_budget_definition")));
                assertTrue(recorded.sql.size() <= 18, recorded.sql.toString());
                System.out.println("READ_SNAPSHOT_SQL_COUNTS discovery=" + discoveryQueries + " budget=" + budgetQueries + " conversation=" + recorded.sql.size());
                f.jdbc.update("UPDATE arte_platform_workspace_member SET state=1,version=version+1 WHERE workspace_id=?", scope.workspaceId());
                assertThrows(Exception.class, () -> discovery.block(WAIT));
                org.mockito.Mockito.verify(source, org.mockito.Mockito.times(2)).readSnapshot("alice", scope.tenantId(), scope.workspaceId(), Set.of(AdmissionAuthorization.INVOKE), true);
                org.mockito.Mockito.verify(source, org.mockito.Mockito.never()).resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anySet());
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        });
    }

    @Test
    void catalogResolutionReadsOneAuthorizedSnapshotAndNextReadSeesRevocation() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            db.importConfiguration(f.actor, meta("import"));
            db.ensure(f.actor, scope);
            var execution = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), "snapshot");
            var mapper = org.mockito.Mockito.spy(c.getBean(com.arte.app.platform.persistence.mapper.PlatformAiControlMapper.class));
            org.springframework.test.util.ReflectionTestUtils.setField(db, "mapper", mapper);
            var source = org.mockito.Mockito.spy(db);
            var scheduler = c.getBean("newAiPersistenceScheduler", reactor.core.scheduler.Scheduler.class);
            var authorization = new AdmissionAuthorization(source, seed(), Clock.systemUTC(), source, scheduler);
            var catalog = new FixedControlCatalog(seed(), authorization, Clock.systemUTC(), source, scheduler);
            assertEquals(ref("binding", "text"), catalog.resolve(ref("binding", "text"), ref("capability", "text"), execution).block(WAIT).definition());
            org.mockito.Mockito.verify(source).configuration(execution);
            org.mockito.Mockito.verify(source, org.mockito.Mockito.never()).grant(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(source, org.mockito.Mockito.never()).resolve(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anySet());
            org.mockito.Mockito.verify(mapper).workspace(scope.tenantId(), scope.workspaceId());
            org.mockito.Mockito.verify(mapper).budgets(scope.tenantId(), scope.workspaceId(), f.actor.principal().subjectId());
            f.jdbc.update("UPDATE arte_platform_workspace_member SET state=1,version=version+1 WHERE workspace_id=?", scope.workspaceId());
            assertThrows(Exception.class, () -> catalog.resolve(ref("binding", "text"), ref("capability", "text"), execution).block(WAIT));
        });
    }

    @Test
    void databaseChatSubmissionCommitsWithoutDispatchingProvider() {
        var scope = f.initialize().value().workspace().scope();
        var recorded = new SqlRecorder(f.source);
        context(recorded).run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            db.importConfiguration(f.actor, meta("import"));
            db.ensure(f.actor, scope);
            var conversation = c.getBean(ConversationService.class).create("Chat", null, List.of(),
                    execution(db, scope, Set.of(AdmissionAuthorization.CONVERSATION), "create-chat")).block(WAIT);
            var discovered = c.getBean(ChatConfigurationQueryService.class).discover(
                    execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null)).block(WAIT);
            var option = discovered.options().getFirst();
            var request = new LinkedHashMap<String, Object>();
            request.put("scope", scope);
            request.put("conversationId", conversation.conversationId());
            request.put("expectedVersion", 0);
            request.put("text", "梦游天姥吟留别全文");
            request.put("capability", option.capability());
            request.put("binding", option.binding());
            request.put("budgetRef", option.budgetRefs().getFirst());
            request.put("maxInputTokens", 32768);
            request.put("generationOptions", Map.of("maxOutputTokens", 512, "stopSequences", List.of()));
            request.put("timeoutSeconds", 60);
            var controller = new com.arte.ainew.web.controller.NewAiChatController(c.getBean(com.arte.ainew.api.entry.ChatService.class),
                    c.getBean(FixedControlCatalog.class), new com.arte.ainew.web.NewAiHttpContext(
                    c.getBean(com.arte.ainew.context.ExecutionContextFactory.class), c.getBean(NewAiProperties.class)), c.getBean(NewAiProperties.class));
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new com.arte.ainew.web.ConversationExceptionHandler()).build();
            var auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
            try {
                recorded.sql.clear();
                var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ai-new/chat/turnsForChat")
                        .header("Idempotency-Key", "chat").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(f.json.write(request))).andReturn();
                result.getAsyncResult(15000);
                result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result)).andReturn();
                assertEquals(202, result.getResponse().getStatus(), result.getResponse().getContentAsString());
                int queries = recorded.sql.size();
                assertTrue(queries <= 90, "chat preparation SQL=" + queries + ": " + recorded.sql);
                assertEquals(2, recorded.sql.stream().filter(q -> q.contains("FROM arte_ai_workspace_configuration")
                        && q.toUpperCase().contains("FOR UPDATE")).count());
                assertEquals(2, recorded.sql.stream().filter(q -> q.contains("FROM arte_ai_budget_definition")).count());
                assertTrue(recorded.sql.stream().noneMatch(q -> q.startsWith("SELECT external_created_at FROM")));
                System.out.println("CHAT_PREPARATION_SQL_COUNT=" + queries);
                var byTable = new java.util.TreeMap<String, Integer>();
                var tablePattern = java.util.regex.Pattern.compile("(?i)\\b(?:FROM|INTO|UPDATE)\\s+([a-z_]+)");
                recorded.sql.forEach(sql -> {
                    var match = tablePattern.matcher(sql);
                    if (match.find()) byTable.merge(match.group(1), 1, Integer::sum);
                });
                System.out.println("CHAT_PREPARATION_SQL_BY_TABLE=" + byTable);
                assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
                assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_turn", Integer.class));
                verifyDispatchPreparation(c, recorded);
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        });
    }

    private void verifyDispatchPreparation(org.springframework.context.ApplicationContext c, SqlRecorder recorded) {
        var executions = c.getBean(com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence.class);
        var payloads = c.getBean(com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence.class);
        var catalog = c.getBean(FixedControlCatalog.class);
        var connections = c.getBean(com.arte.ainew.api.control.ConnectionManager.class);
        var settings = new com.arte.ainew.config.NewAiExecutionProperties(true, false, 1, null, null, null, null);
        var message = executions.claim(com.arte.ainew.pojo.execution.OutboxMessage.Kind.DISPATCH, "preparation-worker",
                settings.outboxLease(), 1).block(WAIT).getFirst();
        var invocation = executions.find(message.owner(), message.invocationId()).block(WAIT);
        var beforeText = new java.util.concurrent.atomic.AtomicInteger();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ModelGateway gateway = new ModelGateway() {
            public boolean supportsTextChat(ResolvedBinding binding, ConnectionDefinition connection) {
                return true;
            }

            public Flux<com.arte.ainew.pojo.generation.GenerationSignal> generate(
                    com.arte.ainew.pojo.execution.GatewayCall<com.arte.ainew.pojo.generation.GenerationRequest> call) {
                calls.incrementAndGet();
                var usage = new com.arte.ainew.pojo.execution.Usage(com.arte.ainew.pojo.execution.Usage.Basis.PROVIDER_REPORTED, 1L, 1L, 2L);
                var output = new com.arte.ainew.pojo.generation.ChatMessage("reply", com.arte.ainew.pojo.generation.ChatMessage.Role.ASSISTANT,
                        List.of(new com.arte.ainew.pojo.generation.ChatMessage.Text("ok")), List.of(), null);
                var result = new com.arte.ainew.pojo.generation.ModelResult("result",
                        new com.arte.ainew.pojo.generation.ModelResult.ModelIdentity("deepseek", "deepseek-chat", null),
                        List.of(output), com.arte.ainew.pojo.generation.ModelResult.FinishReason.STOP, true, null, usage, List.of());
                // Gateway/acquire preparation reuses the dispatch snapshot; only final send reloads it.
                return catalog.validate(call.request()).then(catalog.resolve(call.request().binding(), call.request().capability(), call.runtime().execution()))
                        .then(connections.resolve(call.binding().connection(), call.runtime().execution()))
                        .then(connections.resolve(call.binding().connection(), call.runtime().execution()))
                        .then(connections.resolveCurrent(call.binding().connection(), call))
                        .doOnNext(ignored -> beforeText.set(recorded.sql.size()))
                        .thenMany(Flux.just(new com.arte.ainew.pojo.generation.GenerationSignal.Delta(
                                        new com.arte.ainew.pojo.generation.GenerationEvent.TextDelta("ok")),
                                new com.arte.ainew.pojo.generation.GenerationSignal.Result(result)));
            }
        };
        var dispatcher = new com.arte.ainew.application.execution.GenerationDispatcher(c.getBean(AdmissionAuthorization.class), catalog,
                executions, executions, executions, payloads, payloads, executions, gateway, c.getBean(NewAiProperties.class), settings, Clock.systemUTC());
        recorded.sql.clear();
        dispatcher.dispatch(message, com.arte.ainew.context.ExecutionRuntimeContext.start(invocation.request().context())).block(WAIT);
        assertEquals(1, calls.get());
        assertEquals(com.arte.ainew.pojo.execution.Invocation.State.SUCCEEDED,
                executions.find(message.owner(), message.invocationId()).block(WAIT).state());
        // Attempt/budget/fencing transactions are deliberately left for the next batch.
        assertTrue(beforeText.get() <= 140, "dispatch preparation SQL=" + beforeText.get());
        assertEquals(2, recorded.sql.stream().filter(q -> q.contains("FROM arte_ai_workspace_configuration")
                && q.toUpperCase().contains("FOR UPDATE")).count());
        System.out.println("DISPATCH_BEFORE_FIRST_TEXT_SQL_COUNT=" + beforeText.get());
    }

    @Test
    void revokedMembershipBetweenPreparationAndAdmissionCannotCommitAChat() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            db.importConfiguration(f.actor, meta("import"));
            db.ensure(f.actor, scope);
            var conversation = c.getBean(ConversationService.class).create("Revocation", null, List.of(),
                    execution(db, scope, Set.of(AdmissionAuthorization.CONVERSATION), "revoke-conversation")).block(WAIT);
            var option = c.getBean(ChatConfigurationQueryService.class).discover(
                            execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null))
                    .block(WAIT).options().getFirst();
            var properties = c.getBean(NewAiProperties.class);
            var http = new com.arte.ainew.web.NewAiHttpContext(c.getBean(com.arte.ainew.context.ExecutionContextFactory.class), properties);
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
            try {
                assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> http.prepare(
                        new com.arte.ainew.web.request.ConversationRequests.Scope(scope.tenantId(), scope.workspaceId()),
                        Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION, AdmissionAuthorization.READ),
                        WAIT, option.budgetRefs().getFirst(), "revoked-chat", ctx -> {
                            // Revoke after the entry snapshot was acquired, while internal assembly reuses it.
                            f.jdbc.update("UPDATE arte_platform_workspace_member SET state=1,version=version+1 WHERE workspace_id=?", scope.workspaceId());
                            var message = new com.arte.ainew.pojo.generation.ChatMessage("user-message",
                                    com.arte.ainew.pojo.generation.ChatMessage.Role.USER,
                                    List.of(new com.arte.ainew.pojo.generation.ChatMessage.Text("test")), List.of(), null);
                            var selection = new com.arte.ainew.pojo.context.ContextRequest(List.of(message), null, List.of(), List.of(), null,
                                    new com.arte.ainew.pojo.context.ContextBudget(65536, 32768, 512, 0));
                            var request = new com.arte.ainew.pojo.entry.EntryRequests.Chat(conversation.conversationId(), 0, null, null,
                                    selection, option.capability(), option.binding(),
                                    new com.arte.ainew.pojo.generation.GenerationOptions(512, null, null, List.of()),
                                    new com.arte.ainew.pojo.execution.ExecutionOptions(ctx.deadline(), 1,
                                            properties.limits().maxOutputBytes(), 0, 0, WAIT));
                            return c.getBean(com.arte.ainew.api.entry.ChatService.class).submit(request, ctx);
                        }).block(WAIT));
                assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
                assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_turn", Integer.class));
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        });
    }

    @Test
    void configurationCasAndReplayPreserveChargedHeldAndDefaultPublicationDoesNotRebaseExistingSpaces() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            db.importConfiguration(f.actor, meta("import"));
            var initial = db.ensure(f.actor, scope);
            var context = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null);
            var budgetRef = c.getBean(ChatConfigurationQueryService.class).discover(context).block(WAIT).options().getFirst().budgetRefs().getFirst();
            var codec = new com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec();
            String key = DatabaseAiControlService.hash(budgetRef);
            var account = codec.decode(f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account WHERE id_key=?", String.class, key), BudgetCommands.Account.class);
            f.jdbc.update("UPDATE arte_ai_account SET workspace_snapshot=? WHERE id_key=?", codec.encode(new BudgetCommands.Account(budgetRef, account.owner(), account.limit(), money("3"), money("7"), account.rateVersion(), 2)), key);
            var preset = new AiControlData.Preset(true, initial.configuration().preset().bindings(), List.of(new AiControlData.BudgetPreset(ref("rate", "text-price"), money("200"))));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> db.update(f.actor, meta("unauthorized-raise"), scope, initial.workspaceVersion(), 0, preset));
            assertEquals(0, db.get(f.actor, scope).configuration().version());
            assertEquals(money("100"), codec.decode(f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account WHERE id_key=?", String.class, key), BudgetCommands.Account.class).limit());
            var result = db.update(f.actor, meta("quota"), scope, initial.workspaceVersion(), 0, preset, true);
            assertEquals(result.operationId(), db.update(f.actor, meta("quota"), scope, initial.workspaceVersion(), 0, preset, true).operationId());
            var saved = codec.decode(f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account WHERE id_key=?", String.class, key), BudgetCommands.Account.class);
            assertEquals(money("3"), saved.held());
            assertEquals(money("7"), saved.charged());
            assertEquals(money("200"), saved.limit());
            assertEquals(3, saved.version());
            assertThrows(com.arte.core.exception.platform.PlatformException.class, () -> db.update(f.actor, meta("stale"), scope, initial.workspaceVersion(), 0, preset));
            var catalog = db.catalog();
            var old = catalog.catalog();
            var defaults = new AiControlData.Preset(true, old.defaults().bindings(), List.of(new AiControlData.BudgetPreset(ref("rate", "text-price"), money("50"))));
            db.publish(f.actor, meta("template"), 1, new AiControlData.Catalog(old.capabilities(), old.bindings(), old.connections(), old.rates(), old.legacyGrants(), old.legacyBudgets(), defaults));
            assertEquals(preset, db.get(f.actor, scope).configuration().preset());
            var other = c.getBean(DefaultWorkspaceServices.class).workspaces().create(f.actor, new WorkspaceCommands.CreateWorkspace(meta("next"), scope.tenantId(), "New default", "")).value().scope();
            assertEquals(defaults, db.get(f.actor, other).configuration().preset());
            assertEquals(2, db.get(f.actor, other).configuration().templateVersion());
        });
    }

    @Test
    void missingTemplateAndDeniedMembersCannotCreateResourcesOrBorrowLegacyAuthority() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            assertTrue(db.ensure(f.actor, scope).needsImport());
            var services = c.getBean(DefaultWorkspaceServices.class);
            assertThrows(com.arte.core.exception.platform.PlatformException.class, () -> services.workspaces().create(f.actor, new WorkspaceCommands.CreateWorkspace(meta("missing"), scope.tenantId(), "Must rollback", "")));
            assertEquals(1, f.count("workspace"));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
            db.importConfiguration(f.actor, meta("import"));
            db.ensure(f.actor, scope);
            assertThrows(Exception.class, () -> db.resolve("alice", scope.tenantId(), "unknown-space", Set.of(AdmissionAuthorization.INVOKE)).block(WAIT));
            var view = db.get(f.actor, scope);
            db.update(f.actor, meta("disable"), scope, view.workspaceVersion(), view.configuration().version(), new AiControlData.Preset(false, view.configuration().preset().bindings(), view.configuration().preset().budgets()));
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> db.resolve("alice", scope.tenantId(), scope.workspaceId(), Set.of(AdmissionAuthorization.INVOKE)).block(WAIT));
            assertNotNull(db.resolve("alice", scope.tenantId(), scope.workspaceId(), Set.of(AdmissionAuthorization.CONVERSATION)).block(WAIT));
            var adminContext = execution(db, scope, Set.of(AdmissionAuthorization.BUDGET_ADMIN), null);
            f.jdbc.update("UPDATE arte_platform_workspace_member SET role=1,version=version+1 WHERE workspace_id=?", scope.workspaceId());
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> db.grant(adminContext));
            f.jdbc.update("UPDATE arte_platform_workspace_member SET state=1,version=version+1 WHERE workspace_id=?", scope.workspaceId());
            assertThrows(Exception.class, () -> db.resolve("alice", scope.tenantId(), scope.workspaceId(), Set.of(AdmissionAuthorization.CONVERSATION)).block(WAIT));
        });
    }

    @Test
    void auditFailureRollsBackSpaceProvisioningAndBudgetChanges() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            db.importConfiguration(f.actor, meta("import"));
            var initial = db.ensure(f.actor, scope);
            var beforeAccount = f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class);
            long audits = f.count("audit"), events = f.count("outbox"), receipts = f.count("command");
            var failing = new com.arte.core.application.workspace.WorkspaceMutationEffects(f.outbox,
                    entry -> {
                        throw new IllegalStateException("Injected AI audit failure");
                    }, () -> UUID.randomUUID().toString(), Clock.systemUTC());
            org.springframework.test.util.ReflectionTestUtils.setField(db, "effects", failing);
            assertThrows(IllegalStateException.class, () -> c.getBean(DefaultWorkspaceServices.class).workspaces().create(f.actor,
                    new WorkspaceCommands.CreateWorkspace(meta("audit-failed-space"), scope.tenantId(), "Rollback AI and space", "")));
            var increased = new AiControlData.Preset(true, initial.configuration().preset().bindings(),
                    List.of(new AiControlData.BudgetPreset(ref("rate", "text-price"), money("200"))));
            assertThrows(IllegalStateException.class, () -> db.update(f.actor, meta("audit-failed-quota"), scope, initial.workspaceVersion(), 0, increased, true));
            assertEquals(1, f.count("workspace"));
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_workspace_configuration", Integer.class));
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_budget_definition", Integer.class));
            assertEquals(beforeAccount, f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class));
            assertEquals(initial.configuration(), db.get(f.actor, scope).configuration());
            assertEquals(initial.workspaceVersion(), db.get(f.actor, scope).workspaceVersion());
            assertEquals(audits, f.count("audit"));
            assertEquals(events, f.count("outbox"));
            assertEquals(receipts, f.count("command"));
        });
    }

    @Test
    void httpEnforcesPlatformPermissionOwnerScopeVersionsAndIdempotency() {
        var scope = f.initialize().value().workspace().scope();
        context().withUserConfiguration(HttpSecurity.class, com.arte.app.web.controller.platform.WorkspaceAiConfigurationController.class)
                .withBean("pcs", com.arte.app.web.security.PermissionCheckService.class, com.arte.app.web.security.PermissionCheckService::new)
                .run(c -> {
                    assertNull(c.getStartupFailure());
                    try (var validator = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean()) {
                        validator.afterPropertiesSet();
                        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(c.getBean(com.arte.app.web.controller.platform.WorkspaceAiConfigurationController.class))
                                .setControllerAdvice(new com.arte.app.web.advice.PlatformExceptionHandler()).setValidator(validator).build();
                        var online = new com.arte.core.pojo.UserOnlineInfo().setId(1L).setUserName("alice").setAccountCreatedAt(f.jdbc.queryForObject(
                                "SELECT create_time FROM arte_rbac_user WHERE id=1", (row, index) -> row.getObject(1, java.time.LocalDateTime.class)));
                        var auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(new com.arte.app.pojo.rbac.JwtUserDto(online), null, List.of());
                        assertEquals(403, http(mvc, "importConfiguration", "{}", "import", auth, online, false));
                        assertEquals(403, http(mvc, "getCatalog", "{}", null, auth, online, false));
                        assertEquals(200, http(mvc, "importConfiguration", "{}", "import", auth, online, true));
                        assertEquals(200, http(mvc, "ensure", f.json.write(Map.of("scope", scope)), null, auth, online, false));
                        var db = c.getBean(DatabaseAiControlService.class);
                        var view = db.get(f.actor, scope);
                        var increased = new AiControlData.Preset(true, view.configuration().preset().bindings(), List.of(new AiControlData.BudgetPreset(ref("rate", "text-price"), money("200"))));
                        String raise = f.json.write(Map.of("scope", scope, "expectedWorkspaceVersion", view.workspaceVersion(), "expectedVersion", 0, "preset", increased));
                        assertEquals(403, http(mvc, "updateConfiguration", raise, "raise", auth, online, false));
                        assertEquals(400, http(mvc, "updateConfiguration", raise, null, auth, online, true));
                        assertEquals(400, http(mvc, "updateConfiguration", f.json.write(Map.of("scope", scope, "preset", increased)), "invalid", auth, online, true));
                        assertEquals(200, http(mvc, "updateConfiguration", raise, "raise", auth, online, true));
                        assertEquals(200, http(mvc, "updateConfiguration", raise, "raise", auth, online, true));
                        assertEquals(409, http(mvc, "updateConfiguration", raise, "stale", auth, online, true));
                        f.jdbc.update("UPDATE arte_platform_workspace_member SET role=1,version=version+1 WHERE workspace_id=?", scope.workspaceId());
                        assertEquals(403, http(mvc, "updateConfiguration", raise, "raise", auth, online, true));
                    }
                });
    }

    int http(org.springframework.test.web.servlet.MockMvc mvc, String endpoint, String body, String key,
             org.springframework.security.core.Authentication auth, com.arte.core.pojo.UserOnlineInfo online, boolean administrator) throws Exception {
        online.setMenuOperations(administrator ? List.of("aiConfiguration:manage") : List.of());
        com.arte.core.pojo.UserContext.setUserOnlineInfo(online);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/workspace/ai/" + endpoint)
                    .principal(auth).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body);
            if (key != null) request.header("Idempotency-Key", key);
            var result = mvc.perform(request).andReturn();
            if (result.getRequest().isAsyncStarted()) {
                result.getAsyncResult(15000);
                result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result)).andReturn();
            }
            return result.getResponse().getStatus();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            com.arte.core.pojo.UserContext.clear();
        }
    }
}
