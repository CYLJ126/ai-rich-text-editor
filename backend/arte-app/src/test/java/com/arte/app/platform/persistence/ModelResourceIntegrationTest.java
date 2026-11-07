package com.arte.app.platform.persistence;

import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.application.model.DefaultModelResourceService;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.*;
import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelResourceCommands;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.ainew.spi.model.ModelPublicationSource;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.app.platform.ai.AiControlData;
import com.arte.app.platform.ai.ModelResourceAccessAdapter;
import com.arte.app.platform.ai.PlatformModelResourceConfiguration;
import com.arte.app.platform.identity.*;
import com.arte.app.service.platform.ModelResourceStoreServiceImpl;
import com.arte.core.application.workspace.DefaultWorkspacePolicies;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.workspace.WorkspaceBrowseBudget;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.spi.platform.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.arte.core.constant.platform.PlatformActions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 DDL、Mapper、RBAC、公共 ACL 和模型应用服务，不使用生产模型、网络或账本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
class ModelResourceIntegrationTest {

    final PlatformPersistenceIntegrationTest f = new PlatformPersistenceIntegrationTest();
    RbacIdentityProvider identity;
    PlatformServiceTestFactory stores;
    ModelResourceStoreServiceImpl store;
    DefaultWorkspaceServices platform;
    ModelResourceService models;
    PlatformAiControlMapperHandle catalog;
    Scope scope;
    ControlConfiguration seed;
    ModelPublicationSource publications;
    static final Clock CLOCK = Clock.systemUTC();
    static final DefinitionRef CAP = new DefinitionRef("capability", "text", "v1"), CONN = new DefinitionRef("connection", "test", "v1"), RATE = new DefinitionRef("rate", "tokens", "v1");
    static final DefinitionRef V1 = new DefinitionRef("binding", "model-text", "v1"), V2 = new DefinitionRef("binding", "model-text", "v2");

    // 保持测试访问平台私有 session 的工厂封装。
    record PlatformAiControlMapperHandle(com.arte.app.platform.persistence.mapper.PlatformAiControlMapper mapper) {
    }

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        for (String file : List.of("arte-platform-resource-acl-ddl-mysql.sql", "arte-platform-ai-control-ddl-mysql.sql", "arte-ai-model-egress-policy-ddl-mysql.sql", "arte-ai-model-resource-ddl-mysql.sql")) {
            var sql = Files.readString(Path.of("scripts", file));
            if (f.mysql == null)
                sql = sql.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "").replaceAll("(?i)ENGINE=InnoDB", "").replaceAll("(?im)^SET\\s+[^;]*;", "");
            try (var connection = f.source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        f.jdbc.execute("CREATE TABLE arte_rbac_relation(source VARCHAR(256),binding_type VARCHAR(96),target VARCHAR(256))");
        f.jdbc.execute("CREATE TABLE arte_rbac_role(role_code VARCHAR(96),status VARCHAR(8))");
        f.jdbc.update("INSERT INTO arte_rbac_relation VALUES('alice','user_to_operation','aiConfiguration:manage')");
        var accounts = new RbacAccountReader(f.source, new RbacAccountMapperConfiguration().identityUserMapper(f.source));
        identity = new RbacIdentityProvider(accounts, new JdbcPrincipalIdentityStore(f.source, accounts), new TransactionTemplate(new DataSourceTransactionManager(f.source)),
                new PlatformIdentityProperties(true, 0L, List.of()), List::of, CLOCK);
        stores = new PlatformServiceTestFactory(f.source, f.tx, f.json, null);
        store = stores.models();
        var adapter = new ModelResourceAccessAdapter(store, f.tx);
        platform = new DefaultWorkspaceServices(identity, f.tx, stores.tenants(), stores.workspaces(), stores.tenantMembers(), stores.workspaceMembers(), stores.policies(),
                stores.preferences(), stores.receipts(), stores.outbox(), stores.audit(), () -> UUID.randomUUID().toString(), CLOCK, new DefaultWorkspacePolicies(stores.templates()),
                stores.facts(), List.of(), WorkspaceBrowseBudget.defaults(), System::nanoTime, stores.grants(), List.of(adapter), List.of(adapter));
        scope = platform.personal().initialize(f.actor, meta("init-model-space")).value().workspace().scope();
        seed = seed("test-model");
        var cfg = new PlatformMybatisConfiguration();
        var session = cfg.platformMapperSession(f.source, new PlatformPersistenceProperties(true, 1, 1, 5, 15, 0, null, null, null, null, null, false));
        catalog = new PlatformAiControlMapperHandle(cfg.platformAiControlMapper(session));
        publications = new TestModelPublicationSource(seed, f.tx, identity, new RbacToolPermissionReader(f.source));
        models = graph(stores.audit(), publications);
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    ModelResourceService graph(AuditSink audit, ModelPublicationSource source) {
        return new DefaultModelResourceService(store, source, f.tx, identity, stores.tenants(), stores.workspaces(), stores.tenantMembers(), stores.workspaceMembers(), stores.preferences(),
                platform.authorization(), platform.resources(), stores.receipts(), stores.outbox(), audit, () -> UUID.randomUUID().toString(), CLOCK);
    }

    ControlConfiguration seed(String operation) {
        var cap = new CapabilityDescriptor(CAP, CapabilityDescriptor.Kind.GENERATION, new DefinitionRef("schema", "generation-request", "v1"), new DefinitionRef("schema", "model-result", "v1"),
                Set.of(CapabilityDescriptor.Feature.STREAMING, CapabilityDescriptor.Feature.TEXT_INPUT), CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
        var conn = new ConnectionDefinition(CONN, "test", new DefinitionRef("protocol", "http", "v1"), URI.create("https://example.invalid/v1"), new DefinitionRef("secret", "credential-independent", "v1"),
                ConnectionDefinition.State.ENABLED, Duration.ofSeconds(5), Duration.ofSeconds(60), 8192);
        var rate = new ControlConfiguration.Rate(RATE, new Money(new BigDecimal("1.00"), Currency.getInstance("CNY")), new Money(new BigDecimal("2"), Currency.getInstance("CNY")));
        return new ControlConfiguration(false, "release-v1", null, List.of(), List.of(cap), List.of(new ResolvedBinding(V1, cap, CONN, operation, 1024L, RATE), new ResolvedBinding(V2, cap, CONN, "test-model-v2", 1024L, RATE)), List.of(conn), List.of(rate), List.of());
    }

    CommandMeta meta(String key) {
        return new CommandMeta(key, "model-test");
    }

    ModelResource create(String key) {
        return models.create(f.actor, new ModelResourceCommands.Create(meta(key), scope, "Personal model")).value();
    }

    ModelResourceCommands.Associate association(String key, ModelResource model, DefinitionRef ref, ModelPublication.Source source, Long revision) {
        return new ModelResourceCommands.Associate(meta(key), scope, model.resource(), model.version(), ref, source, revision);
    }

    ModelResource current(ModelResource r) {
        return models.get(f.actor, scope, r.resource());
    }

    boolean allowed(ModelResource r, com.arte.core.pojo.access.Action a) {
        return platform.authorization().check(f.actor, new AccessRequest(scope, r.resource(), a, "test")).allowed();
    }

    long count(String table) {
        return f.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    void dbRevision(long revision, ControlConfiguration data) {
        var contents = new AiControlData.Catalog(data.capabilities(), data.bindings(), data.connections(), data.rates(), List.of(), List.of(), new AiControlData.Preset(false, List.of(), List.of()));
        f.tx.required(() -> {
            assertEquals(1, catalog.mapper().insertRevision(revision, f.json.write(contents), f.actor.principal().subjectId()));
            return null;
        });
    }

    @Test
    void createsServerIdentityAndPrivateAclBeforeAnyVersion() {
        var model = create("create");
        assertNotEquals(V1.id(), model.resource().resourceId());
        assertEquals(scope.workspaceId(), model.ownership().workspaceId());
        assertEquals(f.actor.principal().subjectId(), model.createdBy());
        assertEquals(ModelResource.State.DRAFT, model.state());
        assertTrue(allowed(model, MODEL_READ));
        assertFalse(allowed(model, MODEL_USE));
        assertTrue(allowed(model, MODEL_VALIDATE));
        assertEquals(8, count("arte_platform_resource_grant"));
        assertEquals(0, count("arte_ai_model_version"));
        assertEquals(model, models.create(f.actor, new ModelResourceCommands.Create(meta("create"), scope, "Personal model")).value());
        assertEquals(1, count("arte_ai_model_resource"));
    }

    @Test
    void versionsKeepIdentityAclAndExactHistoricalContent() {
        var draft = create("create");
        var first = models.associate(f.actor, association("v1", draft, V1, ModelPublication.Source.FIXED, null)).value();
        var v1 = current(draft);
        var acl = stores.grants().findAccess(draft.resource()).orElseThrow();
        assertTrue(allowed(v1, MODEL_USE));
        assertEquals(1, v1.version());
        models.associate(f.actor, association("v2", v1, V2, ModelPublication.Source.FIXED, null));
        var v2 = current(v1);
        assertEquals(draft.resource(), v2.resource());
        assertEquals(draft.createdBy(), v2.createdBy());
        assertEquals(acl, stores.grants().findAccess(v2.resource()).orElseThrow());
        assertEquals(first, store.findBinding(scope.tenantId(), V1).orElseThrow());
        assertEquals(2, models.versions(f.actor, scope, v2.resource(), new PageQuery(1, 100)).total());
        assertEquals(8, count("arte_platform_resource_grant"));
        assertEquals(5, count("arte_ai_model_definition_source"));
    }

    @Test
    void duplicateBindingCannotBeClaimedByAnotherResourceInSameTenant() {
        var one = create("one");
        models.associate(f.actor, association("v1", one, V1, ModelPublication.Source.FIXED, null));
        var two = create("two");
        assertThrows(PlatformException.class, () -> models.associate(f.actor, association("claim", two, V1, ModelPublication.Source.FIXED, null)));
        assertEquals(one.resource(), store.findBinding(scope.tenantId(), V1).orElseThrow().resource());
        assertEquals(ModelResource.State.DRAFT, current(two).state());
    }

    @Test
    void adoptionRequiresCurrentPlatformPermissionAsWellAsModelPublish() {
        var model = create("create");
        f.jdbc.update("DELETE FROM arte_rbac_relation");
        assertTrue(allowed(model, MODEL_PUBLISH));
        assertThrows(PlatformException.class, () -> models.associate(f.actor, association("unauthorized-source", model, V1, ModelPublication.Source.FIXED, null)));
        assertEquals(0, count("arte_ai_model_version"));
        assertEquals(0, count("arte_ai_model_definition_source"));
    }

    @Test
    void databaseDefinitionsCannotBeAdoptedAsActualModelReleases() {
        dbRevision(1, seed);
        ModelResource model = create("create");
        assertThrows(PlatformException.class, () -> models.associate(f.actor, association("db-adopt", model, V1, ModelPublication.Source.DATABASE, 1L)));
        assertEquals(0, count("arte_ai_model_version"));
        assertEquals(0, count("arte_ai_model_definition_source"));
        assertEquals(0, current(model).version());
    }

    @Test
    void sourceOrContentConflictCannotOverwriteExistingMapping() {
        var model = create("create");
        models.associate(f.actor, association("fixed", model, V1, ModelPublication.Source.FIXED, null));
        dbRevision(1, seed);
        assertThrows(PlatformException.class, () -> models.associate(f.actor, association("other-source", current(model), V1, ModelPublication.Source.DATABASE, 1L)));
        var changed = new TestModelPublicationSource(seed("mutated-same-version"), f.tx, identity, new RbacToolPermissionReader(f.source));
        assertThrows(PlatformException.class, () -> graph(stores.audit(), changed).associate(f.actor, association("changed", current(model), V1, ModelPublication.Source.FIXED, null)));
        assertEquals("test-model", store.findBinding(scope.tenantId(), V1).orElseThrow().publication().binding().remoteOperation());
    }

    @Test
    void reattachingIdenticalBindingKeepsOriginalEvidenceAndDoesNotBumpResource() {
        dbRevision(1, seed);
        dbRevision(2, seed);
        var model = create("create");
        var first = models.associate(f.actor, association("first", model, V1, ModelPublication.Source.FIXED, null)).value();
        var published = current(model);
        var second = models.associate(f.actor, association("same-definition", published, V1, ModelPublication.Source.FIXED, null)).value();
        assertEquals(first, second);
        assertEquals(published, current(model));
        assertEquals(1, count("arte_ai_model_version"));
    }

    @Test
    void commandReplayAndVersionCasAreIndependent() {
        var model = create("create");
        var c = association("link", model, V1, ModelPublication.Source.FIXED, null);
        var applied = models.associate(f.actor, c);
        long events = count("arte_platform_outbox");
        assertEquals(CommandDeliveryEnum.REPLAYED, models.associate(f.actor, c).delivery());
        assertEquals(applied.value(), models.associate(f.actor, c).value());
        assertEquals(events, count("arte_platform_outbox"));
        assertThrows(PlatformException.class, () -> models.associate(f.actor, association("stale", model, V2, ModelPublication.Source.FIXED, null)));
        assertThrows(PlatformException.class, () -> models.associate(f.actor, association("link", model, V2, ModelPublication.Source.FIXED, null)));
    }

    @Test
    void disableChangesCurrentEligibilityWithoutRewritingPublishedVersions() {
        var model = create("create");
        var version = models.associate(f.actor, association("link", model, V1, ModelPublication.Source.FIXED, null)).value();
        var published = current(model);
        models.disable(f.actor, new ModelResourceCommands.Disable(meta("disable"), scope, published.resource(), published.version()));
        assertFalse(allowed(model, MODEL_USE));
        assertTrue(allowed(model, MODEL_READ));
        assertEquals(version, store.findBinding(scope.tenantId(), V1).orElseThrow());
    }

    @Test
    void auditFailureRollsBackVersionSourceLedgerAndEpoch() {
        var model = create("create");
        long events = count("arte_platform_outbox"), receipts = count("arte_platform_command");
        var before = current(model);
        var broken = graph(entry -> {
            throw new IllegalStateException("audit failed");
        }, publications);
        assertThrows(IllegalStateException.class, () -> broken.associate(f.actor, association("failure", model, V1, ModelPublication.Source.FIXED, null)));
        assertEquals(before, current(model));
        assertEquals(0, count("arte_ai_model_version"));
        assertEquals(0, count("arte_ai_model_definition_source"));
        assertEquals(events, count("arte_platform_outbox"));
        assertEquals(receipts, count("arte_platform_command"));
    }

    @Test
    void createFailureRollsBackDomainAclAndNestedRegistrationReceipt() {
        var broken = graph(entry -> {
            throw new IllegalStateException("audit failed");
        }, publications);
        long commands = count("arte_platform_command");
        assertThrows(IllegalStateException.class, () -> broken.create(f.actor, new ModelResourceCommands.Create(meta("failed-create"), scope, "Model")));
        assertEquals(0, count("arte_ai_model_resource"));
        assertEquals(0, count("arte_platform_resource_access"));
        assertEquals(0, count("arte_platform_resource_grant"));
        assertEquals(commands, count("arte_platform_command"));
    }

    @Test
    void crossWorkspaceAndChangedBindingFamilyAreRejected() {
        var model = create("create");
        models.associate(f.actor, association("link", model, V1, ModelPublication.Source.FIXED, null));
        var other = platform.workspaces().create(f.actor, new WorkspaceCommands.CreateWorkspace(meta("other"), scope.tenantId(), "other", "")).value().scope();
        assertThrows(PlatformException.class, () -> models.get(f.actor, other, model.resource()));
        assertThrows(PlatformException.class, () -> models.associate(f.actor, association("new-family", current(model), new DefinitionRef("binding", "another-family", "v1"), ModelPublication.Source.FIXED, null)));
    }

    @Test
    void concurrentAssociationCasHasOneWinner() throws Exception {
        var model = create("create");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<Boolean>>();
            for (var ref : List.of(V1, V2))
                tasks.add(executor.submit(() -> {
                    start.await();
                    try {
                        models.associate(f.actor, association(ref.version(), model, ref, ModelPublication.Source.FIXED, null));
                        return true;
                    } catch (PlatformException conflict) {
                        assertEquals(PlatformErrorCodeEnum.VERSION_CONFLICT, conflict.getCode());
                        return false;
                    }
                }));
            start.countDown();
            int winners = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
            assertEquals(1, count("arte_ai_model_version"));
        }
    }

    @Test
    void sameExactFixedReferenceCanMapToDistinctTenantResources() {
        var first = create("first");
        models.associate(f.actor, association("first-link", first, V1, ModelPublication.Source.FIXED, null));
        f.jdbc.update("INSERT INTO arte_rbac_user(id,user_name,status,create_time) VALUES(2,'bob',1,CURRENT_TIMESTAMP)");
        var bob = new TrustedActor(identity.resolveAuthenticatedName("bob").orElseThrow(), Instant.now());
        f.jdbc.update("INSERT INTO arte_rbac_relation VALUES('bob','user_to_operation','aiConfiguration:manage')");
        var otherScope = platform.personal().initialize(bob, meta("bob-init")).value().workspace().scope();
        var second = models.create(bob, new ModelResourceCommands.Create(meta("second"), otherScope, "Bob model")).value();
        models.associate(bob, new ModelResourceCommands.Associate(meta("second-link"), otherScope, second.resource(), 0, V1, ModelPublication.Source.FIXED, null));
        assertEquals(first.resource(), store.findBinding(scope.tenantId(), V1).orElseThrow().resource());
        assertEquals(second.resource(), store.findBinding(otherScope.tenantId(), V1).orElseThrow().resource());
        assertNotEquals(first.resource(), second.resource());
        assertEquals(4, count("arte_ai_model_definition_source"));
        assertEquals(0, count("arte_ai_budget_definition"));
    }

    @Test
    void finalMutationRejectsVersionAdvancedAfterOuterRepeatableReadSnapshot() throws Exception {
        var draft = create("draft");
        var outer = new TransactionTemplate(new DataSourceTransactionManager(f.source));
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.execute(status -> {
            assertEquals(0, store.find(draft.resource()).orElseThrow().version());
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> models.associate(f.actor, association("winner", draft, V1, ModelPublication.Source.FIXED, null))).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            var failure = assertThrows(RuntimeException.class, () -> models.associate(f.actor, association("stale-outer", draft, V2, ModelPublication.Source.FIXED, null)));
            if (failure instanceof PlatformException conflict)
                assertEquals(PlatformErrorCodeEnum.VERSION_CONFLICT, conflict.getCode());
            else {
                assertNull(f.mysql);
                assertInstanceOf(org.springframework.dao.CannotAcquireLockException.class, failure);
            } // H2 RR 检出并发修改时直接中止；MySQL 当前读应取得胜出版本。
            status.setRollbackOnly();
            return null;
        });
        assertEquals(1, current(draft).version());
        assertEquals(1, count("arte_ai_model_version"));
    }

    @Test
    void dependencyContentConflictRollsBackNewBindingAuthority() {
        var model = create("create");
        models.associate(f.actor, association("link", model, V1, ModelPublication.Source.FIXED, null));
        var changedRate = new ControlConfiguration.Rate(RATE, new Money(new BigDecimal("99"), Currency.getInstance("CNY")), seed.rates().getFirst().outputPerMillion());
        var changed = new ControlConfiguration(false, seed.releaseRef(), seed.limits(), List.of(), seed.capabilities(), seed.bindings(), seed.connections(), List.of(changedRate), List.of());
        var source = new TestModelPublicationSource(changed, f.tx, identity, new RbacToolPermissionReader(f.source));
        assertThrows(PlatformException.class, () -> graph(stores.audit(), source).associate(f.actor, association("bad-price", current(model), V2, ModelPublication.Source.FIXED, null)));
        assertEquals(4, count("arte_ai_model_definition_source"));
        assertTrue(store.findBinding(scope.tenantId(), V2).isEmpty());
    }

    @Test
    void definitionDigestNormalizesCapabilityFeatureOrderAndMoneyScale() {
        var c = seed.capabilities().getFirst();
        var reordered = new CapabilityDescriptor(c.definition(), c.kind(), c.inputSchema(), c.outputSchema(), new LinkedHashSet<>(List.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING)), c.sideEffect(), c.availability());
        var binding = seed.bindings().getFirst();
        var rate = seed.rates().getFirst();
        var one = new ModelPublication(ModelPublication.Source.FIXED, null, seed.releaseRef(), binding, seed.connections().getFirst(), new RateDefinition(RATE, rate.inputPerMillion(), rate.outputPerMillion(), null));
        var two = new ModelPublication(ModelPublication.Source.FIXED, null, seed.releaseRef(), new ResolvedBinding(V1, reordered, CONN, binding.remoteOperation(), binding.contextWindowTokens(), RATE), seed.connections().getFirst(),
                new RateDefinition(RATE, new Money(new BigDecimal("1"), Currency.getInstance("CNY")), rate.outputPerMillion(), null));
        assertEquals(one.definitionDigests(), two.definitionDigests());
        assertEquals(one.contentDigest(), two.contentDigest());
    }

    @Test
    void sourceMustReturnRequestedExactReferenceAndIdentityMetadataIsImmutable() {
        var model = create("create");
        var wrong = graph(stores.audit(), (actor, scope, source, revision, binding) -> publications.requireCurrent(actor, scope, source, revision, V2));
        assertThrows(PlatformException.class, () -> wrong.associate(f.actor, association("wrong-ref", model, V1, ModelPublication.Source.FIXED, null)));
        assertEquals(0, count("arte_ai_model_version"));
        f.tx.required(() -> {
            var gates = new WorkspaceGovernanceLocks(f.tx, stores.tenants(), stores.workspaces(), stores.tenantMembers(), stores.workspaceMembers(), stores.preferences());
            gates.scopes(f.actor.principal().subjectId(), List.of(scope));
            assertThrows(IllegalArgumentException.class, () -> store.replace(new ModelResource(model.resource(), model.ownership(), model.name(), "different-creator", ModelResource.State.PUBLISHED, V1.id(), 1, model.createdAt()), 0));
            return null;
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(com.arte.ainew.config.NewAiProperties.class)
    static class ModelProperties {
    }

    @Test
    void productionAssemblyHasOneModelProviderAndNoAuthorizationCycle() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(com.arte.app.platform.workspace.PlatformWorkspaceConfiguration.class, PlatformModelResourceConfiguration.class, PlatformMybatisConfiguration.class, ModelProperties.class)
                .withPropertyValues("arte.ai-new.enabled=true", "arte.ai-new.release-ref=release-v1", "arte.platform.identity.enabled=true", "arte.platform.persistence.enabled=true", "arte.platform.workspace.enabled=true")
                .withBean("appDataSource", javax.sql.DataSource.class, () -> f.source)
                .withBean("platformTransactionRunner", PublicTransactionRunner.class, () -> f.tx)
                .withBean(RbacIdentityProvider.class, () -> identity)
                .withBean(com.arte.core.spi.workspace.TenantStore.class, stores::tenants)
                .withBean(com.arte.core.spi.workspace.WorkspaceStore.class, stores::workspaces)
                .withBean(com.arte.core.spi.workspace.TenantMembershipStore.class, stores::tenantMembers)
                .withBean(com.arte.core.spi.workspace.WorkspaceMembershipStore.class, stores::workspaceMembers)
                .withBean(com.arte.core.spi.workspace.PreferenceStore.class, stores::preferences)
                .withBean(com.arte.core.spi.workspace.PolicyStore.class, stores::policies)
                .withBean(com.arte.core.spi.workspace.PersonalTenantPolicyTemplateStore.class, stores::templates)
                .withBean(com.arte.core.spi.workspace.WorkspaceAccessFactsStore.class, stores::facts)
                .withBean(com.arte.core.spi.access.ResourceGrantStore.class, stores::grants)
                .withBean(ModelResourceStore.class, () -> store)
                .withBean(CommandReceiptStore.class, stores::receipts)
                .withBean(PublicOutboxStore.class, stores::outbox)
                .withBean(AuditSink.class, stores::audit)
                .withBean(PlatformIdGenerator.class, () -> () -> UUID.randomUUID().toString())
                .withBean(WorkspaceCursorStore.class, PlatformPersistenceIntegrationTest.Cursors::new)
                .withBean(PlatformPersistenceExecutor.class, () -> new PlatformPersistenceExecutor(reactor.core.scheduler.Schedulers.immediate(), f.tx))
                .withBean(PlatformJson.class, () -> f.json);
        runner.run(c -> {
            assertNull(c.getStartupFailure());
            assertEquals(1, c.getBeansOfType(com.arte.core.spi.access.ResourceDescriptorProvider.class).size());
            assertEquals(1, c.getBeansOfType(com.arte.core.spi.access.DomainAccessConditionProvider.class).size());
            var created = c.getBean(ModelResourceService.class).create(f.actor, new ModelResourceCommands.Create(meta("spring-model"), scope, "Spring model")).value();
            assertEquals(ModelResource.State.DRAFT, created.state());
        });
    }

    @Test
    void corruptedHistoricalSnapshotFailsIntegrityCheckInsteadOfChangingPublishedContent() {
        var model = create("create");
        models.associate(f.actor, association("link", model, V1, ModelPublication.Source.FIXED, null));
        var saved = store.findBinding(scope.tenantId(), V1).orElseThrow();
        var p = saved.publication();
        var corrupt = new ModelResourceVersion(saved.resource(), new ModelPublication(p.source(), p.catalogRevision(), p.releaseRef(),
                new ResolvedBinding(p.binding().definition(), p.binding().capability(), p.connection().definition(), "tampered-model", p.binding().contextWindowTokens(), p.rate().definition()), p.connection(), p.rate()), saved.linkedBy(), saved.linkedAt());
        f.jdbc.update("UPDATE arte_ai_model_version SET payload=?", f.json.write(corrupt));
        assertThrows(PlatformException.class, () -> store.findBinding(scope.tenantId(), V1));
        assertThrows(PlatformException.class, () -> models.versions(f.actor, scope, model.resource(), new PageQuery(1, 100)));
    }

}
