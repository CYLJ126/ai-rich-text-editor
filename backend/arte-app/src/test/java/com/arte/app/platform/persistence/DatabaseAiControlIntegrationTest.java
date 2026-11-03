package com.arte.app.platform.persistence;

import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
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
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
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

                    public boolean supportsToolChat(ResolvedBinding b, ConnectionDefinition c) {
                        return b.capability().features().contains(CapabilityDescriptor.Feature.TOOL_CALLS);
                    }

                    public Flux<com.arte.ainew.pojo.generation.GenerationSignal> generate(com.arte.ainew.pojo.execution.GatewayCall<com.arte.ainew.pojo.generation.GenerationRequest> call) {
                        return Flux.error(new AssertionError("Discovery must not invoke provider"));
                    }
                });
    }

    CommandMeta meta(String key) {
        return new CommandMeta(key, "database-ai-test");
    }

    @Test
    void readPortsUsePublishedDatabaseAuthorityAndKeepHistoricalRatesAfterRevocation() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            var published = c.getBean(com.arte.ainew.spi.control.PublishedAiCatalog.class);
            var definitions = c.getBean(com.arte.ainew.spi.control.BudgetDefinitionProvider.class);
            var rate = ref("rate", "text-price");
            // 文件中已有费率，但未发布时不能用文件种子补历史权威。
            assertThrows(com.arte.core.exception.platform.PlatformException.class, () -> published.historicalRate(rate).block(WAIT));
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("ports-import"));
            db.ensure(f.actor, scope);
            var ctx = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null);
            assertTrue(published.read(ctx).block(WAIT).bindings().isEmpty());
            var budget = definitions.available(ctx, com.arte.ainew.spi.control.BudgetDefinitionProvider.Operation.READ).block(WAIT).getFirst();
            assertEquals(new ExecutionOwner(scope.tenantId(), scope.workspaceId(), f.actor.principal().subjectId()), budget.owner());
            assertEquals(money("100"), budget.limit());
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
            var old = db.catalog().catalog();
            var v2 = new com.arte.core.pojo.reference.DefinitionRef("rate", rate.id(), "v2");
            var rates = new ArrayList<>(old.rates());
            rates.add(new NewAiProperties.Rate(v2, money("3"), money("4")));
            db.publish(f.actor, meta("ports-rate-v2"), 1, new AiControlData.Catalog(old.capabilities(), old.bindings(),
                    old.connections(), rates, old.legacyGrants(), old.legacyBudgets(), old.defaults(), old.disabledConnections(), old.reviewedPlans()));
            f.jdbc.update("UPDATE arte_platform_workspace_member SET state=1,version=version+1 WHERE workspace_id=?", scope.workspaceId());
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> definitions.available(ctx, com.arte.ainew.spi.control.BudgetDefinitionProvider.Operation.READ).block(WAIT));
            assertEquals(money("1"), published.historicalRate(rate).block(WAIT).inputPerMillion());
            assertEquals(money("3"), published.historicalRate(v2).block(WAIT).inputPerMillion());
            assertThrows(com.arte.ainew.application.support.AdmissionException.class,
                    () -> published.historicalRate(new DefinitionRef("rate", rate.id(), "v3")).block(WAIT));
        });
    }

    @Test
    void modelEntitlementDiscoveryDoesNotAuthorizeLaterResolutionAfterMemberRevocation() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("model-import"));
            db.ensure(f.actor, scope);
            var ctx = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null);
            var service = c.getBean(com.arte.ainew.spi.control.AiResourceEntitlementService.class);
            assertTrue(service.discover(CapabilityDescriptor.Kind.GENERATION, 256, ctx).collectList().block(WAIT).isEmpty());
            assertThrows(RuntimeException.class, () -> service.resolve(ref("binding", "text"), ref("capability", "text"), ctx).block(WAIT));
            f.jdbc.update("UPDATE arte_platform_workspace_member SET state=1,version=version+1 WHERE workspace_id=?", scope.workspaceId());
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> service.resolve(ref("binding", "text"), ref("capability", "text"), ctx).block(WAIT));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> c.getBean(com.arte.ainew.api.control.BindingManager.class).resolve(ref("binding", "text"), ref("capability", "text"), ctx).block(WAIT));
        });
    }

    @Test
    void replacingEntitlementPortControlsDiscoveryAndActualChatAdmission() {
        var scope = f.initialize().value().workspace().scope();
        var veto = new com.arte.ainew.spi.control.AiResourceEntitlementService() {
            public Flux<com.arte.ainew.pojo.control.AiEntitlement> discover(CapabilityDescriptor.Kind kind, int limit, ExecutionContext ctx) {
                return Flux.empty();
            }

            public reactor.core.publisher.Mono<com.arte.ainew.pojo.control.AiEntitlement> resolve(DefinitionRef binding, DefinitionRef capability,
                                                                                                  com.arte.core.pojo.access.Action action, ExecutionContext ctx) {
                return reactor.core.publisher.Mono.error(new org.springframework.security.access.AccessDeniedException("Injected model qualification denied"));
            }
        };
        context().withBean(com.arte.ainew.spi.control.AiResourceEntitlementService.class, () -> veto).run(c -> {
            assertNull(c.getStartupFailure());
            assertSame(veto, c.getBean(com.arte.ainew.spi.control.AiResourceEntitlementService.class));
            var db = c.getBean(DatabaseAiControlService.class);
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("custom-model-import"));
            db.ensure(f.actor, scope);
            var ctx = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ, AdmissionAuthorization.CONVERSATION), "custom-model-call");
            var budget = c.getBean(com.arte.ainew.spi.control.BudgetDefinitionProvider.class)
                    .available(ctx, com.arte.ainew.spi.control.BudgetDefinitionProvider.Operation.EXECUTE).block(WAIT).getFirst().budgetRef();
            var submissionContext = new ExecutionContext(ctx.executionId(), ctx.traceId(), ctx.authorization(), ctx.deadline(),
                    ctx.parentExecutionId(), budget, ctx.releaseRef(), ctx.idempotencyKey());
            assertTrue(c.getBean(ChatConfigurationQueryService.class).discover(submissionContext).block(WAIT).options().isEmpty());
            var conversation = c.getBean(ConversationService.class).create("Denied model", null, List.of(),
                    execution(db, scope, Set.of(AdmissionAuthorization.CONVERSATION), "create-custom-model-conversation")).block(WAIT);
            var message = new com.arte.ainew.pojo.generation.ChatMessage("model-message", com.arte.ainew.pojo.generation.ChatMessage.Role.USER,
                    List.of(new com.arte.ainew.pojo.generation.ChatMessage.Text("hello")), List.of(), null);
            var input = new com.arte.ainew.pojo.context.ContextRequest(List.of(message), null, List.of(), List.of(), null,
                    new com.arte.ainew.pojo.context.ContextBudget(65536, 60000, 512, 0));
            var request = new com.arte.ainew.pojo.entry.EntryRequests.Chat(conversation.conversationId(), 0, null, null, input,
                    ref("capability", "text"), ref("binding", "text"),
                    new com.arte.ainew.pojo.generation.GenerationOptions(512, null, null, List.of()),
                    new com.arte.ainew.pojo.execution.ExecutionOptions(submissionContext.deadline(), 1, 1000000, 0, 0, Duration.ofSeconds(120)));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> c.getBean(com.arte.ainew.api.entry.ChatService.class).submit(request, submissionContext).block(WAIT));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_turn", Integer.class));
        });
    }

    @Test
    void reviewedDefinitionsAndApprovedBudgetCannotProduceAnExecutableModel() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("reviewed-definitions"));
            db.ensure(f.actor, scope);
            var ctx = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), "raw-call");
            assertTrue(c.getBean(ChatConfigurationQueryService.class).discover(ctx).block(WAIT).options().isEmpty());
            assertThrows(RuntimeException.class, () -> c.getBean(com.arte.ainew.spi.control.AiResourceEntitlementService.class)
                    .resolve(ref("binding", "text"), ref("capability", "text"), ctx).block(WAIT));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
        });
    }

    @Test
    void capabilityUpgradeAppendsImmutableVersionsAndRequiresExplicitWorkspaceSelection() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("import"));
            var workspace = db.ensure(f.actor, scope);
            var old = db.catalog().catalog();
            var v1 = old.capabilities().getFirst();
            var features = new HashSet<>(v1.features());
            features.add(CapabilityDescriptor.Feature.TOOL_CALLS);
            var v2 = new CapabilityDescriptor(new DefinitionRef("capability", v1.definition().id(), "v2"), v1.kind(),
                    v1.inputSchema(), v1.outputSchema(), features, v1.sideEffect(), v1.availability());
            var modelV1 = old.bindings().getFirst();
            var modelV2 = new ResolvedBinding(new DefinitionRef("binding", modelV1.definition().id(), "v2"), v2,
                    modelV1.connection(), modelV1.remoteOperation(), modelV1.contextWindowTokens(), modelV1.rate());
            var upgraded = new AiControlData.Catalog(List.of(v1, v2), List.of(modelV1, modelV2), old.connections(), old.rates(),
                    old.legacyGrants(), old.legacyBudgets(), old.defaults(), old.disabledConnections(), old.reviewedPlans());
            var receipt = db.publish(f.actor, meta("upgrade"), 1, upgraded);
            assertEquals(2, receipt.resultVersion());
            assertEquals(receipt.operationId(), db.publish(f.actor, meta("upgrade"), 1, upgraded).operationId());
            assertEquals(workspace.configuration(), db.get(f.actor, scope).configuration());
            var discovery = c.getBean(ChatConfigurationQueryService.class);
            var current = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null);
            assertTrue(discovery.discover(current).block(WAIT).options().isEmpty());
            var preset = new AiControlData.Preset(true, List.of(modelV2.definition()), workspace.configuration().preset().budgets());
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> db.update(f.actor, meta("unapproved-v2"), scope,
                    workspace.workspaceVersion(), workspace.configuration().version(), preset));
            var approved = new AiControlData.Preset(true, List.of(modelV1.definition(), modelV2.definition()), old.defaults().budgets());
            db.publish(f.actor, meta("approve-v2"), 2, new AiControlData.Catalog(upgraded.capabilities(), upgraded.bindings(), old.connections(), old.rates(),
                    old.legacyGrants(), old.legacyBudgets(), approved, old.disabledConnections(), old.reviewedPlans()));
            assertEquals(workspace.configuration(), db.get(f.actor, scope).configuration());
            db.update(f.actor, meta("select-v2"), scope, workspace.workspaceVersion(), workspace.configuration().version(), preset);
            current = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null);
            var selectedContext = current;
            var entitlements = c.getBean(com.arte.ainew.spi.control.AiResourceEntitlementService.class);
            assertTrue(entitlements.discover(CapabilityDescriptor.Kind.GENERATION, 256, selectedContext).collectList().block(WAIT).isEmpty());
            assertThrows(RuntimeException.class, () -> entitlements.resolve(modelV2.definition(), v2.definition(), selectedContext).block(WAIT));
            assertThrows(RuntimeException.class, () -> entitlements.resolve(modelV1.definition(), v1.definition(), selectedContext).block(WAIT));
            assertTrue(discovery.discover(current).block(WAIT).options().isEmpty());
            assertEquals(v1, db.catalog().catalog().capabilities().getFirst());

            var redefined = new CapabilityDescriptor(v1.definition(), v1.kind(), v1.inputSchema(), v1.outputSchema(), features, v1.sideEffect(), v1.availability());
            assertThrows(IllegalArgumentException.class, () -> db.publish(f.actor, meta("rewrite"), 3,
                    new AiControlData.Catalog(List.of(redefined, v2), upgraded.bindings(), old.connections(), old.rates(), old.legacyGrants(), old.legacyBudgets(), old.defaults(), old.disabledConnections(), old.reviewedPlans())));
            assertThrows(IllegalArgumentException.class, () -> db.publish(f.actor, meta("remove"), 3,
                    new AiControlData.Catalog(List.of(v2), List.of(modelV2), old.connections(), old.rates(), old.legacyGrants(), old.legacyBudgets(), old.defaults(), old.disabledConnections(), old.reviewedPlans())));
            assertThrows(IllegalArgumentException.class, () -> db.publish(f.actor, meta("duplicate"), 3,
                    new AiControlData.Catalog(List.of(v1, v2, v2), upgraded.bindings(), old.connections(), old.rates(), old.legacyGrants(), old.legacyBudgets(), old.defaults(), old.disabledConnections(), old.reviewedPlans())));
            assertThrows(IllegalArgumentException.class, () -> new DefinitionRef("capability", v1.definition().id(), "latest"));
            assertEquals(3, db.catalog().version());
        });
    }

    ExecutionContext execution(DatabaseAiControlService source, Scope scope, Set<String> scopes, String key) {
        var auth = source.resolve("alice", scope.tenantId(), scope.workspaceId(), scopes).block(WAIT);
        return new ExecutionContext(UUID.randomUUID().toString(), "test", auth, Instant.now().plusSeconds(60), null, null, "release-v1", key);
    }

    @Test
    void publicWorkspaceCommandsWorkWithoutAiControlTables() {
        for (String table : List.of("arte_ai_budget_definition", "arte_ai_workspace_configuration",
                "arte_ai_control_head", "arte_ai_control_revision"))
            f.jdbc.execute("DROP TABLE " + table);
        var recorded = new SqlRecorder(f.source);
        context(recorded).run(c -> {
            assertNull(c.getStartupFailure());
            var services = c.getBean(DefaultWorkspaceServices.class);
            recorded.sql.clear();
            Scope scope;
            try (var workers = java.util.concurrent.Executors.newFixedThreadPool(3)) {
                var requests = java.util.stream.IntStream.range(0, 3).mapToObj(i ->
                        java.util.concurrent.CompletableFuture.supplyAsync(() -> services.personal()
                                .initialize(f.actor, meta("independent-init-" + i)).value().workspace().scope(), workers)).toList();
                scope = requests.getFirst().orTimeout(15, java.util.concurrent.TimeUnit.SECONDS).join();
                for (var request : requests)
                    assertEquals(scope, request.orTimeout(15, java.util.concurrent.TimeUnit.SECONDS).join());
            }
            assertEquals(1, f.count("tenant"));
            assertEquals(1, f.count("workspace"));
            var command = new WorkspaceCommands.CreateWorkspace(meta("independent-create"), scope.tenantId(), "Second", "");
            var created = services.workspaces().create(f.actor, command);
            assertEquals(created.operationId(), services.workspaces().create(f.actor, command).operationId());
            var second = created.value().scope();
            services.workspaces().create(f.actor, new WorkspaceCommands.CreateWorkspace(meta("independent-third"), scope.tenantId(), "Third", ""));
            assertEquals(3, services.workspaces().browseAccessible(f.actor,
                    new com.arte.core.pojo.workspace.WorkspaceBrowseQuery(20, ""), null).records().size());
            services.workspaces().rename(f.actor, new WorkspaceCommands.RenameWorkspace(meta("independent-rename"),
                    second, created.value().version(), "Renamed", "Without AI"));
            var preference = services.preferences().get(f.actor);
            services.preferences().update(f.actor, new WorkspaceCommands.UpdatePreference(meta("independent-preference"),
                    preference.version(), second, second, Set.of(second)));
            assertEquals(second, services.preferences().get(f.actor).startupScope());
            assertTrue(recorded.sql.stream().noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("arte_ai_")), recorded.sql::toString);

            assertThrows(RuntimeException.class, () -> c.getBean(DatabaseAiControlService.class).ensure(f.actor, second));
            assertEquals("Renamed", services.workspaces().get(f.actor, second).name());
            assertEquals(scope, services.personal().initialize(f.actor, meta("after-ai-failure")).value().workspace().scope());
            assertEquals(3, f.count("workspace"));
        });
    }

    @Test
    void spacesPrepareAiOnDemandAndHaveIndependentBudgetsAndHistory() {
        var existing = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            var imported = DatabaseAiTestCatalog.publish(db, c, f.actor, meta("import"));
            assertEquals(1, imported.resultVersion());
            assertEquals(imported.operationId(), DatabaseAiTestCatalog.publish(db, c, f.actor, meta("import")).operationId());
            var prepared = db.ensure(f.actor, existing);
            assertEquals(0, prepared.configuration().version());
            var services = c.getBean(DefaultWorkspaceServices.class);
            var second = services.workspaces().create(f.actor, new WorkspaceCommands.CreateWorkspace(meta("new-space"), existing.tenantId(), "Second", "")).value().scope();
            assertNull(db.get(f.actor, second).configuration());
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
            assertNotNull(db.ensure(f.actor, second).configuration());
            var discoveries = c.getBean(ChatConfigurationQueryService.class);
            var firstContext = execution(db, existing, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null);
            var secondContext = execution(db, second, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null);
            assertTrue(discoveries.discover(firstContext).block(WAIT).options().isEmpty());
            assertTrue(discoveries.discover(secondContext).block(WAIT).options().isEmpty());
            var first = c.getBean(com.arte.ainew.spi.control.BudgetDefinitionProvider.class).available(firstContext, com.arte.ainew.spi.control.BudgetDefinitionProvider.Operation.READ).block(WAIT).getFirst();
            var next = c.getBean(com.arte.ainew.spi.control.BudgetDefinitionProvider.class).available(secondContext, com.arte.ainew.spi.control.BudgetDefinitionProvider.Operation.READ).block(WAIT).getFirst();
            assertNotEquals(first.budgetRef(), next.budgetRef());
            var accounts = c.getBean(BudgetAccountQueryService.class);
            assertEquals(money("100"), accounts.account(first.budgetRef(), firstContext).block(WAIT).limit());
            assertEquals(money("100"), accounts.account(next.budgetRef(), secondContext).block(WAIT).limit());
            var conversations = c.getBean(ConversationService.class);
            var created = conversations.create("First space only", null, List.of(), execution(db, existing, Set.of(AdmissionAuthorization.CONVERSATION), "conversation")).block(WAIT);
            assertEquals(1, conversations.list(new com.arte.core.pojo.PageParam(), execution(db, existing, Set.of(AdmissionAuthorization.CONVERSATION), null)).block(WAIT).total());
            assertEquals(0, conversations.list(new com.arte.core.pojo.PageParam(), execution(db, second, Set.of(AdmissionAuthorization.CONVERSATION), null)).block(WAIT).total());
            assertEquals(existing.workspaceId(), created.owner().workspaceId());
            assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
            db.ensure(f.actor, existing);
            db.ensure(f.actor, second);
            assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> db.resolve("alice", "legacy-tenant", "legacy-space", Set.of(AdmissionAuthorization.CONVERSATION)).block(WAIT));
        });
    }

    static final class SqlRecorder extends org.springframework.jdbc.datasource.DelegatingDataSource {
        final List<String> sql = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();

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
                        if (method.getName().equals("commit")) commits.incrementAndGet();
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
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("import"));
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
                assertTrue(options.isEmpty());
                int discoveryQueries = recorded.sql.size();
                assertTrue(discoveryQueries <= 20, recorded.sql.toString());
                String budgetRef = c.getBean(com.arte.ainew.spi.control.BudgetDefinitionProvider.class)
                        .available(execution(db, scope, Set.of(AdmissionAuthorization.READ), null), com.arte.ainew.spi.control.BudgetDefinitionProvider.Operation.READ).block(WAIT).getFirst().budgetRef();
                recorded.sql.clear();
                http.read(target, Set.of(AdmissionAuthorization.READ), WAIT, true,
                        ctx -> c.getBean(BudgetAccountQueryService.class).account(budgetRef, ctx)).block(WAIT);
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
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("import"));
            db.ensure(f.actor, scope);
            var execution = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), "snapshot");
            var mapper = org.mockito.Mockito.spy(c.getBean(com.arte.app.platform.persistence.mapper.PlatformAiControlMapper.class));
            org.springframework.test.util.ReflectionTestUtils.setField(db, "mapper", mapper);
            var source = org.mockito.Mockito.spy(db);
            var scheduler = c.getBean("newAiPersistenceScheduler", reactor.core.scheduler.Scheduler.class);
            var authorization = new AdmissionAuthorization(source, seed(), Clock.systemUTC(), source, scheduler);
            var catalog = new FixedControlCatalog(seed(), authorization, Clock.systemUTC(), source, scheduler);
            assertThrows(RuntimeException.class, () -> catalog.resolve(ref("binding", "text"), ref("capability", "text"), execution).block(WAIT));
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
    void bareDatabaseModelIsRejectedBeforeAnyChatExecutionWrites() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            var db = c.getBean(DatabaseAiControlService.class);
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("raw-import"));
            db.ensure(f.actor, scope);
            var ctx = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), "raw-submit");
            assertThrows(RuntimeException.class, () -> c.getBean(com.arte.ainew.api.control.BindingManager.class)
                    .resolve(ref("binding", "text"), ref("capability", "text"), ctx).block(WAIT));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_turn", Integer.class));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox", Integer.class));
        });
    }

    private void verifyDispatchPreparation(org.springframework.context.ApplicationContext c, SqlRecorder recorded) {
        var executions = c.getBean(com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence.class);
        var payloads = c.getBean(com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence.class);
        var bindings = c.getBean(com.arte.ainew.api.control.BindingManager.class);
        var capabilities = c.getBean(com.arte.ainew.api.control.CapabilityCatalog.class);
        var connections = c.getBean(com.arte.ainew.api.control.ConnectionManager.class);
        var settings = new com.arte.ainew.config.NewAiExecutionProperties(true, false, 1, null, null, null, null);
        var message = executions.claim(com.arte.ainew.pojo.execution.OutboxMessage.Kind.DISPATCH, "preparation-worker",
                settings.outboxLease(), 1).block(WAIT).getFirst();
        var invocation = executions.find(message.owner(), message.invocationId()).block(WAIT);
        var beforeText = new java.util.concurrent.atomic.AtomicInteger();
        var commitsBeforeText = new java.util.concurrent.atomic.AtomicInteger();
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
                return capabilities.validate(call.request()).then(bindings.resolve(call.request().binding(), call.request().capability(), call.runtime().execution()))
                        .then(connections.resolve(call.binding().connection(), call.runtime().execution()))
                        .then(connections.resolve(call.binding().connection(), call.runtime().execution()))
                        .then(connections.resolveCurrent(call.binding().connection(), call))
                        .doOnNext(ignored -> {
                            beforeText.set(recorded.sql.size());
                            commitsBeforeText.set(recorded.commits.get());
                        })
                        .thenMany(Flux.just(new com.arte.ainew.pojo.generation.GenerationSignal.Delta(
                                        new com.arte.ainew.pojo.generation.GenerationEvent.TextDelta("ok")),
                                new com.arte.ainew.pojo.generation.GenerationSignal.Result(result)));
            }
        };
        var dispatcher = new com.arte.ainew.application.execution.GenerationDispatcher(c.getBean(AdmissionAuthorization.class), capabilities, bindings,
                c.getBean(com.arte.ainew.spi.control.PublishedAiCatalog.class), c.getBean(com.arte.ainew.spi.control.AiExecutionPolicyProvider.class),
                c.getBean(com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard.class), executions, executions, executions, payloads, payloads, executions, gateway, c.getBean(NewAiProperties.class), settings, Clock.systemUTC(), null);
        recorded.sql.clear();
        recorded.commits.set(0);
        dispatcher.dispatch(message, com.arte.ainew.context.ExecutionRuntimeContext.start(invocation.request().context())).block(WAIT);
        assertEquals(1, calls.get());
        assertEquals(com.arte.ainew.pojo.execution.Invocation.State.SUCCEEDED,
                executions.find(message.owner(), message.invocationId()).block(WAIT).state());
        // 预检、原子派发写事务、实际发送分别复核；同一次事务内的模型／预算／政策共享当前配置。
        assertTrue(beforeText.get() <= 140, "dispatch preparation SQL=" + beforeText.get());
        assertEquals(3, recorded.sql.stream().filter(q -> q.contains("FROM arte_ai_workspace_configuration")
                && q.toUpperCase().contains("FOR UPDATE")).count());
        System.out.println("DISPATCH_BEFORE_FIRST_TEXT_SQL_COUNT=" + beforeText.get());
        System.out.println("DISPATCH_BEFORE_FIRST_TEXT_COMMITS=" + commitsBeforeText.get());
    }

    @Test
    void sendRevalidationChecksCurrentQualificationAndReusesOnlyUnchangedVersions() {
        var scope = f.initialize().value().workspace().scope();
        var recorded = new SqlRecorder(f.source);
        context(recorded).run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("import"));
            var view = db.ensure(f.actor, scope);
            var ctx = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), "revalidate");
            var prepared = db.prepareConfiguration(ctx);
            recorded.sql.clear();
            assertSame(prepared, db.revalidate(ctx, prepared));
            assertTrue(recorded.sql.stream().anyMatch(q -> q.contains("FROM arte_ai_control_head")));
            assertTrue(recorded.sql.stream().noneMatch(q -> q.contains("FROM arte_ai_control_revision")
                    || q.contains("FROM arte_ai_budget_definition")), recorded.sql.toString());
            int stableQueries = recorded.sql.size();
            db.update(f.actor, meta("same-preset"), scope, view.workspaceVersion(), view.configuration().version(), view.configuration().preset());
            recorded.sql.clear();
            var changed = db.revalidate(ctx, prepared);
            assertNotSame(prepared, changed);
            assertTrue(recorded.sql.stream().anyMatch(q -> q.contains("FROM arte_ai_control_revision")));
            assertTrue(recorded.sql.stream().anyMatch(q -> q.contains("FROM arte_ai_budget_definition")));
            db.publish(f.actor, meta("next-catalog"), 1, db.catalog().catalog());
            recorded.sql.clear();
            var published = db.revalidate(ctx, changed);
            assertEquals(2L, published.catalogRevision());
            assertTrue(recorded.sql.stream().anyMatch(q -> q.contains("FROM arte_ai_control_revision")));
            f.jdbc.update("UPDATE arte_platform_workspace_member SET state=1,version=version+1 WHERE workspace_id=?", scope.workspaceId());
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> db.revalidate(ctx, published));
            System.out.println("SEND_STABLE_REVALIDATION_SQL_COUNT=" + stableQueries);
        });
    }

    @Test
    void revokedMemberCannotUseARemovedBareDatabaseBinding() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("import"));
            db.ensure(f.actor, scope);
            var conversation = c.getBean(ConversationService.class).create("Revocation", null, List.of(),
                    execution(db, scope, Set.of(AdmissionAuthorization.CONVERSATION), "revoke-conversation")).block(WAIT);
            String budgetRef = c.getBean(com.arte.ainew.spi.control.BudgetDefinitionProvider.class).available(
                    execution(db, scope, Set.of(AdmissionAuthorization.READ), null), com.arte.ainew.spi.control.BudgetDefinitionProvider.Operation.READ).block(WAIT).getFirst().budgetRef();
            var properties = c.getBean(NewAiProperties.class);
            var http = new com.arte.ainew.web.NewAiHttpContext(c.getBean(com.arte.ainew.context.ExecutionContextFactory.class), properties);
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
            try {
                assertThrows(com.arte.ainew.application.support.AdmissionException.class, () -> http.prepare(
                        new com.arte.ainew.web.request.ConversationRequests.Scope(scope.tenantId(), scope.workspaceId()),
                        Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION, AdmissionAuthorization.READ),
                        WAIT, budgetRef, "revoked-chat", ctx -> {
                            // Revoke after the entry snapshot was acquired, while internal assembly reuses it.
                            f.jdbc.update("UPDATE arte_platform_workspace_member SET state=1,version=version+1 WHERE workspace_id=?", scope.workspaceId());
                            var message = new com.arte.ainew.pojo.generation.ChatMessage("user-message",
                                    com.arte.ainew.pojo.generation.ChatMessage.Role.USER,
                                    List.of(new com.arte.ainew.pojo.generation.ChatMessage.Text("test")), List.of(), null);
                            var selection = new com.arte.ainew.pojo.context.ContextRequest(List.of(message), null, List.of(), List.of(), null,
                                    new com.arte.ainew.pojo.context.ContextBudget(65536, 32768, 512, 0));
                            var request = new com.arte.ainew.pojo.entry.EntryRequests.Chat(conversation.conversationId(), 0, null, null,
                                    selection, ref("capability", "text"), ref("binding", "text"),
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
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("import"));
            var initial = db.ensure(f.actor, scope);
            var context = execution(db, scope, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), null);
            var budgetRef = c.getBean(com.arte.ainew.spi.control.BudgetDefinitionProvider.class).available(context, com.arte.ainew.spi.control.BudgetDefinitionProvider.Operation.READ).block(WAIT).getFirst().budgetRef();
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
            var other = c.getBean(DefaultWorkspaceServices.class).workspaces().create(f.actor, new WorkspaceCommands.CreateWorkspace(meta("next"), scope.tenantId(), "Prepare later", "")).value().scope();
            assertNull(db.get(f.actor, other).configuration());
            db.publish(f.actor, meta("template"), 1, new AiControlData.Catalog(old.capabilities(), old.bindings(), old.connections(), old.rates(), old.legacyGrants(), old.legacyBudgets(), defaults, old.disabledConnections(), old.reviewedPlans()));
            assertEquals(preset, db.get(f.actor, scope).configuration().preset());
            assertNull(db.get(f.actor, other).configuration());
            var prepared = db.ensure(f.actor, other).configuration();
            assertEquals(defaults, prepared.preset());
            assertEquals(2, prepared.templateVersion());
        });
    }

    @Test
    void missingAiTemplateDoesNotBlockSpaceCreationAndDeniedMembersCannotBorrowLegacyAuthority() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            assertTrue(db.ensure(f.actor, scope).needsImport());
            var services = c.getBean(DefaultWorkspaceServices.class);
            var command = new WorkspaceCommands.CreateWorkspace(meta("missing"), scope.tenantId(), "Without AI", "");
            var created = services.workspaces().create(f.actor, command);
            assertEquals(created.operationId(), services.workspaces().create(f.actor, command).operationId());
            assertEquals(2, f.count("workspace"));
            assertTrue(db.ensure(f.actor, created.value().scope()).needsImport());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_workspace_configuration", Integer.class));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("import"));
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
    void aiAuditFailureRollsBackAiPreparationAndBudgetChangesButLeavesSpaceUsable() {
        var scope = f.initialize().value().workspace().scope();
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            DatabaseAiTestCatalog.publish(db, c, f.actor, meta("import"));
            var initial = db.ensure(f.actor, scope);
            var beforeAccount = f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class);
            var originalEffects = org.springframework.test.util.ReflectionTestUtils.getField(db, "effects");
            var failing = new com.arte.core.application.workspace.WorkspaceMutationEffects(f.outbox,
                    entry -> {
                        throw new IllegalStateException("Injected AI audit failure");
                    }, () -> UUID.randomUUID().toString(), Clock.systemUTC());
            org.springframework.test.util.ReflectionTestUtils.setField(db, "effects", failing);
            var services = c.getBean(DefaultWorkspaceServices.class);
            var created = services.workspaces().create(f.actor,
                    new WorkspaceCommands.CreateWorkspace(meta("audit-failed-space"), scope.tenantId(), "Independent space", ""));
            long audits = f.count("audit"), events = f.count("outbox"), receipts = f.count("command");
            assertThrows(IllegalStateException.class, () -> db.ensure(f.actor, created.value().scope()));
            assertEquals(created.value().scope(), services.workspaces().get(f.actor, created.value().scope()).scope());
            var increased = new AiControlData.Preset(true, initial.configuration().preset().bindings(),
                    List.of(new AiControlData.BudgetPreset(ref("rate", "text-price"), money("200"))));
            assertThrows(IllegalStateException.class, () -> db.update(f.actor, meta("audit-failed-quota"), scope, initial.workspaceVersion(), 0, increased, true));
            assertEquals(2, f.count("workspace"));
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_workspace_configuration", Integer.class));
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_budget_definition", Integer.class));
            assertEquals(beforeAccount, f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class));
            assertEquals(initial.configuration(), db.get(f.actor, scope).configuration());
            assertEquals(initial.workspaceVersion(), db.get(f.actor, scope).workspaceVersion());
            assertEquals(audits, f.count("audit"));
            assertEquals(events, f.count("outbox"));
            assertEquals(receipts, f.count("command"));
            org.springframework.test.util.ReflectionTestUtils.setField(db, "effects", originalEffects);
            assertNotNull(db.ensure(f.actor, created.value().scope()).configuration());
            assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
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
                        assertEquals(403, http(mvc, "initializeCatalog", "{}", "import", auth, online, false));
                        assertEquals(403, http(mvc, "getCatalog", "{}", null, auth, online, false));
                        DatabaseAiTestCatalog.publish(c.getBean(DatabaseAiControlService.class), c, f.actor, meta("http-seed"));
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

    @Test
    void httpCatalogIsReadOnlySafeAndRejectsTheFormerFullCatalogWriteRoute() {
        f.initialize();
        context().withUserConfiguration(HttpSecurity.class, com.arte.app.web.controller.platform.WorkspaceAiConfigurationController.class)
                .withBean("pcs", com.arte.app.web.security.PermissionCheckService.class, com.arte.app.web.security.PermissionCheckService::new)
                .run(c -> {
                    try {
                        tools.jackson.databind.json.JsonMapper json = com.arte.core.serialize.SerializerFactory.buildJsonMapperWithoutTypeProperty();
                        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                                .standaloneSetup(c.getBean(com.arte.app.web.controller.platform.WorkspaceAiConfigurationController.class))
                                .setControllerAdvice(new com.arte.app.web.advice.PlatformExceptionHandler())
                                .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(json)).build();
                        com.arte.core.pojo.UserOnlineInfo online = new com.arte.core.pojo.UserOnlineInfo().setId(1L).setUserName("alice").setAccountCreatedAt(f.jdbc.queryForObject(
                                "SELECT create_time FROM arte_rbac_user WHERE id=1", (row, index) -> row.getObject(1, java.time.LocalDateTime.class)));
                        org.springframework.security.core.Authentication auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                                .authenticated(new com.arte.app.pojo.rbac.JwtUserDto(online), null, List.of());
                        DatabaseAiTestCatalog.publish(c.getBean(DatabaseAiControlService.class), c, f.actor, meta("http-seed"));
                        org.springframework.mock.web.MockHttpServletResponse response = httpResponse(mvc, "getCatalog", "{}", null, auth, online, true);
                        assertEquals(200, response.getStatus());
                        String body = response.getContentAsString();
                        assertTrue(body.contains("reviewedPlans"));
                        assertTrue(body.contains("rateCaps"));
                        assertFalse(body.contains("legacyGrants"));
                        assertFalse(body.contains("legacyBudgets"));
                        assertFalse(body.contains("credential"));
                        assertFalse(body.contains("bindings"));
                        assertEquals(404, http(mvc, "publishCatalog", "{\"expectedVersion\":1,\"catalog\":{}}", "blocked", auth, online, true));
                        assertEquals(1, c.getBean(DatabaseAiControlService.class).catalog().version());
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
    }

    int http(org.springframework.test.web.servlet.MockMvc mvc, String endpoint, String body, String key,
             org.springframework.security.core.Authentication auth, com.arte.core.pojo.UserOnlineInfo online, boolean administrator) throws Exception {
        return httpResponse(mvc, endpoint, body, key, auth, online, administrator).getStatus();
    }

    org.springframework.mock.web.MockHttpServletResponse httpResponse(org.springframework.test.web.servlet.MockMvc mvc, String endpoint, String body, String key,
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
            return result.getResponse();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            com.arte.core.pojo.UserContext.clear();
        }
    }
}
