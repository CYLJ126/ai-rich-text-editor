package com.arte.ainew.tool.management;

import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.management.ToolManagementService;
import com.arte.ainew.application.tool.release.LocalToolPublicationValidator;
import com.arte.ainew.application.tool.release.ToolReleaseService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisToolBindingStore;
import com.arte.ainew.persistence.mybatis.MybatisToolCatalogStore;
import com.arte.ainew.persistence.mybatis.MybatisToolDiscoveryManagementStore;
import com.arte.ainew.persistence.mybatis.MybatisToolReleaseStore;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.pojo.tool.ToolRelease;
import com.arte.ainew.pojo.tool.ToolReleaseCommand;
import com.arte.ainew.pojo.tool.ToolReleaseReceipt;
import com.arte.ainew.spi.adapter.LocalToolDefinitionSource;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.web.ToolManagementExceptionHandler;
import com.arte.ainew.web.controller.NewAiToolController;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 真实 MVC、标准 JSON、隔离 MyBatis/H2 及事务内权限失效；不执行任何工具或外连。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public class ToolManagementHttpTest {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Map<String, Object> SCOPE = Map.of("tenantId", "tenant", "workspaceId", "workspace");
    private final Fixture v1 = managementFixture("v1"), v2 = managementFixture("v2"), v3 = managementFixture("v3");
    private final JsonMapper json = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private final Clock clock = Clock.systemUTC();
    private final AtomicBoolean revoked = new AtomicBoolean(), commitRevoked = new AtomicBoolean(), manager = new AtomicBoolean(true), sourceFailed = new AtomicBoolean();
    private final AtomicBoolean revokeAfterSource = new AtomicBoolean();
    private final AtomicInteger sourceReads = new AtomicInteger(), finalChecks = new AtomicInteger();
    private final AtomicReference<List<Fixture>> definitions = new AtomicReference<>(List.of(v1, v2));
    private JdbcDataSource ds;
    private JdbcTemplate jdbc;
    private Connection ddl;
    private Scheduler scheduler;
    private LocalValidatorFactoryBean beanValidator;
    private MockMvc mvc;
    private MybatisToolCatalogStore catalog;
    private MybatisToolReleaseStore releases;
    private MybatisToolBindingStore bindings;
    private MybatisToolDiscoveryManagementStore discoveryStore;
    private LocalToolDiscovery discovery;
    private ToolManagementService service;
    private LocalToolRegistry registry;
    private LocalToolPublicationValidator publication;
    private ToolReleaseService releaseService;
    private ToolBindingService bindingService;

    /**
     * 管理测试的固定 Schema 明确声明数值字段，保持额外属性默认拒绝。
     */
    private static Fixture managementFixture(String version) {
        var base = fixture("text", version);
        var schema = schema(base.schema().definition(), object("$schema", text(ToolSchemaValidator.DIALECT),
                "type", text("object"), "properties", object("text", object("type", text("string")),
                        "decimal", object("type", text("number")), "integer", object("type", text("integer")))));
        return new Fixture(base.provider(), schema, base.revision(), base.adapterRef());
    }

    @Before
    public void setup() throws Exception {
        var messages = new org.springframework.context.support.ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        com.arte.core.i18n.MessageUtils.setMessageSource(messages);
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        ddl = ds.getConnection();
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).populate(ddl);
        jdbc = new JdbcTemplate(ds);
        scheduler = Schedulers.newBoundedElastic(6, 64, "tool-http-test");
        var codec = new JacksonExecutionRecordCodec();
        catalog = new MybatisToolCatalogStore(ds, codec, scheduler, clock);
        releases = new MybatisToolReleaseStore(ds, codec, scheduler, clock);
        bindings = new MybatisToolBindingStore(ds, codec, scheduler, clock, new ToolExecutionPolicy(Duration.ofSeconds(60), 10, Duration.ZERO, 100_000, 100_000, ToolExecutionPolicy.ApprovalMode.NONE), catalog, releases);
        discoveryStore = new MybatisToolDiscoveryManagementStore(ds, codec, scheduler, clock, catalog);
        registry = new LocalToolRegistry(List.of(new LocalToolRegistry.Registration(v1.provider(), () -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            sourceReads.incrementAndGet();
            if (revokeAfterSource.get()) commitRevoked.set(true);
            if (sourceFailed.get()) return Mono.error(new IllegalStateException("do not leak secret-source-value"));
            return Mono.just(new LocalToolDefinitionSource.Definitions(List.of(v1.schema()), definitions.get().stream().map(Fixture::revision).toList()));
        }, Map.of(v1.adapterRef(), adapter(v1.revision().tool().discoveredCapability()), v2.adapterRef(), adapter(v2.revision().tool().discoveredCapability()), v3.adapterRef(), adapter(v3.revision().tool().discoveredCapability())))));
        discovery = new LocalToolDiscovery(registry, catalog, new ToolSchemaValidator(), scheduler, clock, Duration.ofSeconds(3));
        publication = new LocalToolPublicationValidator(discovery, registry, new ToolSchemaValidator(), scheduler, clock, Duration.ofSeconds(3));
        releaseService = new ToolReleaseService(releases, publication);
        bindingService = new ToolBindingService(bindings, discovery, registry, scheduler, Duration.ofSeconds(3));
        service = service(discovery);
        assertTrue(discovery.refresh().block(WAIT).state().discoveryHealthy());
        var review = new ToolReleaseCommand.Review(List.of(new ToolReleaseCommand.Example(object(), object())), "review", null, ToolExecutionPolicy.ApprovalMode.NONE, null, null);
        assertEquals(ToolReleaseReceipt.Rejection.NONE, releaseService.execute(actor("alice"), new ToolReleaseCommand(v1.revision().tool().definition(), ToolReleaseCommand.Action.PUBLISH, 0, "setup-publish", "setup", review, null)).block(WAIT).value().rejection());
        beanValidator = new LocalValidatorFactoryBean();
        beanValidator.afterPropertiesSet();
        mvc = standaloneSetup(new NewAiToolController(service, new TestAccess())).setValidator(beanValidator).setControllerAdvice(new ToolManagementExceptionHandler()).build();
    }

    private ToolManagementService service(LocalToolDiscovery node) {
        return new ToolManagementService(catalog, releases, bindings, releaseService, bindingService, publication, node, discoveryStore);
    }

    @After
    public void cleanup() throws Exception {
        com.arte.core.i18n.MessageUtils.setMessageSource(null);
        beanValidator.close();
        scheduler.dispose();
        jdbc.execute("DROP ALL OBJECTS");
        ddl.close();
    }

    @Test
    public void publishedDirectoryHidesDraftsWhileManagedModeCanInspectThem() throws Exception {
        var normal = ok("listTools", Map.of("scope", SCOPE));
        assertEquals(1, normal.path("data").path("records").size());
        var managed = ok("listTools", Map.of("scope", SCOPE, "managed", true));
        assertEquals(2, managed.path("data").path("records").size());
        assertEquals("0", ok("getTool", Map.of("scope", SCOPE, "tool", v2.revision().tool().definition(), "managed", true)).path("data").path("tool").path("release").path("version").asString());
        request("getTool", Map.of("scope", SCOPE, "tool", v2.revision().tool().definition()), null, "alice", 404);
        manager.set(false);
        request("listTools", Map.of("scope", SCOPE, "managed", true), null, "alice", 403);
    }

    @Test
    public void detailUsesStandardSchemasAndHidesInternalTargetsAndDefaultConfiguration() throws Exception {
        var body = ok("getTool", Map.of("scope", SCOPE, "tool", v1.revision().tool().definition()));
        assertEquals("object", body.path("data").path("configurationSchema").path("document").path("type").asString());
        assertTrue(body.path("data").path("defaultConfiguration").isNull());
        String text = body.toString();
        assertFalse(text.contains("target-local"));
        assertFalse(text.contains("tool-adapter"));
        assertFalse(text.contains("actorSubjectId"));
        assertFalse(text.contains("schemaVersion"));
        assertEquals("LOCAL", ok("listProviders", Map.of("scope", SCOPE)).path("data").path("records").get(0).path("kind").asString());
    }

    @Test
    public void bindingCanBeCreatedReadRevisedDisabledAndAuditedThroughHttp() throws Exception {
        var created = create("create", "value");
        String id = created.path("data").path("bindingId").asString();
        var data = ok("getBinding", Map.of("scope", SCOPE, "bindingId", id)).path("data");
        assertEquals("1", data.path("binding").path("version").asString());
        assertEquals("value", data.path("revision").path("settings").path("configuration").path("text").asString());
        assertFalse(data.path("revision").path("settings").path("configuration").has("fields"));
        request("saveBinding", Map.of("scope", SCOPE, "bindingId", id, "expectedVersion", "1", "reason", "clear", "settings", settings(v1, Map.of())), "replace", "alice", 200);
        assertEquals(0, ok("getBinding", Map.of("scope", SCOPE, "bindingId", id)).path("data").path("revision").path("settings").path("configuration").size());
        var oldRef = ref("binding", id, "r1");
        assertEquals("value", ok("getBindingRevision", Map.of("scope", SCOPE, "revision", oldRef)).path("data").path("settings").path("configuration").path("text").asString());
        request("setBindingState", Map.of("scope", SCOPE, "bindingId", id, "expectedVersion", "2", "reason", "disable", "enabled", false), "disable", "alice", 200);
        assertFalse(ok("getBinding", Map.of("scope", SCOPE, "bindingId", id)).path("data").path("binding").path("enabled").asBoolean());
        assertEquals(3, ok("getBindingHistory", Map.of("scope", SCOPE, "bindingId", id)).path("data").path("records").size());
        assertTrue(finalChecks.get() >= 3);
    }

    @Test
    public void exactConfigurationSurvivesHttpPersistenceReadAndIdempotentReplay() throws Exception {
        var decimal = new java.math.BigDecimal("0.12345678901234567890123456789012345678");
        var integer = new java.math.BigInteger("9007199254740993123456789");
        var body = Map.of("scope", SCOPE, "expectedVersion", "0", "reason", "precise values",
                "settings", settings(v1, Map.of("decimal", decimal, "integer", integer)));
        var created = request("saveBinding", body, "exact-configuration", "alice", 200);
        var configuration = ok("getBinding", Map.of("scope", SCOPE, "bindingId", created.path("data").path("bindingId").asString()))
                .path("data").path("revision").path("settings").path("configuration");
        assertEquals(decimal, new java.math.BigDecimal(configuration.path("decimal").asString()));
        assertEquals(integer.toString(), configuration.path("integer").asString());
        assertEquals("REPLAYED", request("saveBinding", body, "exact-configuration", "alice", 200).path("data").path("delivery").asString());
    }

    @Test
    public void commandReplayKeepsOriginalResultAndChangedSemanticsConflict() throws Exception {
        var one = create("same", "A");
        var replay = create("same", "A");
        assertEquals("REPLAYED", replay.path("data").path("delivery").asString());
        assertEquals(one.path("data").path("commandId"), replay.path("data").path("commandId"));
        assertEquals("205027", request("saveBinding", createBody("B"), "same", "alice", 409).path("code").asString());
        assertEquals(1, count("binding_revision"));
        assertEquals(1, count("binding_command"));
    }

    @Test
    public void wrongVersionIsAConflictWithDurableReceiptAndCorrectCurrentVersion() throws Exception {
        String id = create("create", "A").path("data").path("bindingId").asString();
        var body = Map.of("scope", SCOPE, "bindingId", id, "expectedVersion", "2", "reason", "stale", "settings", settings(v1, Map.of()));
        var result = request("saveBinding", body, "stale", "alice", 409);
        assertEquals("205030", result.path("code").asString());
        assertEquals("1", result.path("data").path("currentVersion").asString());
        assertEquals("REPLAYED", request("saveBinding", body, "stale", "alice", 409).path("data").path("delivery").asString());
    }

    @Test
    public void anotherSubjectCannotReadRevisePreviewOrListPrivateBindings() throws Exception {
        String id = create("create", "private").path("data").path("bindingId").asString();
        request("getBinding", Map.of("scope", SCOPE, "bindingId", id), null, "bob", 404);
        request("getBindingRevision", Map.of("scope", SCOPE, "revision", ref("binding", id, "r1")), null, "bob", 404);
        request("getBindingHistory", Map.of("scope", SCOPE, "bindingId", id), null, "bob", 404);
        var updated = request("saveBinding", Map.of("scope", SCOPE, "bindingId", id, "expectedVersion", "1", "reason", "unauthorized", "settings", settings(v1, Map.of())), "bob", "bob", 404);
        assertEquals("NOT_FOUND", updated.path("data").path("rejection").asString());
        assertTrue(updated.path("data").path("currentVersion").isNull());
        assertEquals(0, request("listBindings", Map.of("scope", SCOPE), null, "bob", 200).path("data").path("records").size());
    }

    @Test
    public void revokedAccessBlocksNewCommandsReplaysAndAuditReads() throws Exception {
        String id = create("create", "A").path("data").path("bindingId").asString();
        revoked.set(true);
        request("saveBinding", createBody("A"), "create", "alice", 403);
        request("saveBinding", createBody("A"), "new", "alice", 403);
        request("getBindingHistory", Map.of("scope", SCOPE, "bindingId", id), null, "alice", 403);
        assertEquals(1, count("binding_command"));
    }

    @Test
    public void finalAuthorizationRunsInsideActualBindingAndReleaseTransactions() throws Exception {
        commitRevoked.set(true);
        request("saveBinding", createBody("A"), "denied", "alice", 403);
        request("setToolState", Map.of("scope", SCOPE, "tool", v1.revision().tool().definition(), "action", "DISABLE", "expectedVersion", "1", "reason", "deny"), "deny-release", "alice", 403);
        assertEquals(0, count("binding_command"));
        assertEquals(1, count("release_command"));
        assertEquals(ToolRelease.State.PUBLISHED, releases.find(v1.revision().tool().definition()).block(WAIT).state());
    }

    @Test
    public void scopeAndIdentityAreNotClientSelectable() throws Exception {
        var body = new LinkedHashMap<>(createBody("A"));
        body.put("owner", Map.of("subjectId", "subject-bob"));
        request("saveBinding", body, "owner", "alice", 400);
        request("listTools", Map.of("scope", Map.of("tenantId", "other", "workspaceId", "workspace")), null, "alice", 403);
        request("listTools", Map.of("scope", SCOPE), null, null, 401);
        assertEquals(0, count("binding"));
    }

    @Test
    public void parametersMissingKeysUnknownTypesAndBadVersionsFailBeforeWriting() throws Exception {
        request("saveBinding", createBody("A"), null, "alice", 400);
        for (var version : List.of("-1", "latest", "9223372036854775808")) {
            var body = new LinkedHashMap<>(createBody("A"));
            body.put("expectedVersion", version);
            request("saveBinding", body, "bad-" + version, "alice", 400);
        }
        request("listTools", Map.of("scope", SCOPE, "size", 101), null, "alice", 400);
        request("getTool", Map.of("scope", SCOPE, "tool", ref("binding", "wrong", "v1")), null, "alice", 400);
        request("getBindingRevision", Map.of("scope", SCOPE, "revision", ref("binding", "missing", "p1-" + "0".repeat(64))), null, "alice", 400);
        assertEquals(0, count("binding_command"));
    }

    @Test
    public void invalidSchemaAndUnsupportedSecretsAreExplicitSafeFailures() throws Exception {
        var invalid = new LinkedHashMap<>(createBody("A"));
        invalid.put("settings", settings(v1, Map.of("extra", "secret-do-not-leak")));
        var result = request("saveBinding", invalid, "invalid", "alice", 422);
        assertEquals("205051", result.path("code").asString());
        assertFalse(result.toString().contains("secret-do-not-leak"));
        var s = new LinkedHashMap<>(settings(v1, Map.of()));
        s.put("secrets", Map.of("api", ref("secret", "api", "v1")));
        var body = new LinkedHashMap<>(createBody("A"));
        body.put("settings", s);
        assertEquals("205054", request("saveBinding", body, "secret", "alice", 422).path("code").asString());
        assertEquals(0, count("binding"));
    }

    @Test
    public void commonCredentialKeysAreRejectedAtTheTransportBoundary() throws Exception {
        for (var field : List.of("password", "api_key", "Authorization", "client-secret")) {
            var body = new LinkedHashMap<>(createBody("A"));
            body.put("settings", settings(v1, Map.of(field, "do-not-leak")));
            var result = request("saveBinding", body, field, "alice", 400);
            assertFalse(result.toString().contains("do-not-leak"));
        }
        assertEquals(0, count("binding_command"));
    }

    @Test
    public void cursorIsBoundToActorScopeOperationAndFilter() throws Exception {
        create("one", "A");
        create("two", "B");
        String cursor = ok("listBindings", Map.of("scope", SCOPE, "size", 1)).path("data").path("nextCursor").asString();
        assertEquals(1, ok("listBindings", Map.of("scope", SCOPE, "size", 1, "cursor", cursor)).path("data").path("records").size());
        request("listBindings", Map.of("scope", SCOPE, "cursor", cursor), null, "bob", 400);
        request("listProviders", Map.of("scope", SCOPE, "cursor", cursor), null, "alice", 400);
        request("listBindings", Map.of("scope", SCOPE, "cursor", "invalid"), null, "alice", 400);
    }

    @Test
    public void paginatedDirectoryReportsContinuationEvenWhenVisibilityFilteringEmptiesAPage() throws Exception {
        var first = ok("listTools", Map.of("scope", SCOPE, "size", 1));
        assertEquals(1, first.path("data").path("scanned").asInt());
        assertFalse(first.path("data").path("exhausted").asBoolean());
        String cursor = first.path("data").path("nextCursor").asString();
        var last = ok("listTools", Map.of("scope", SCOPE, "size", 1, "cursor", cursor));
        assertTrue(last.path("data").path("exhausted").asBoolean());
        assertEquals(1, first.path("data").path("records").size() + last.path("data").path("records").size());
    }

    @Test
    public void publicationValidationAndLifecycleCommandsUseServerPrechecks() throws Exception {
        var review = review();
        assertTrue(ok("validateTool", Map.of("scope", SCOPE, "tool", v2.revision().tool().definition(), "review", review)).path("data").path("valid").asBoolean());
        var publish = Map.of("scope", SCOPE, "tool", v2.revision().tool().definition(), "expectedVersion", "0", "reason", "reviewed", "review", review);
        assertEquals("PUBLISHED", request("publishTool", publish, "publish", "alice", 200).path("data").path("release").path("state").asString());
        var disable = Map.of("scope", SCOPE, "tool", v2.revision().tool().definition(), "action", "DISABLE", "expectedVersion", "1", "reason", "disable");
        request("setToolState", disable, "disable", "alice", 200);
        assertEquals("REPLAYED", request("setToolState", disable, "disable", "alice", 200).path("data").path("delivery").asString());
        request("setToolState", Map.of("scope", SCOPE, "tool", v2.revision().tool().definition(), "action", "REENABLE", "expectedVersion", "2", "reason", "restore"), "restore", "alice", 200);
        assertEquals(3, ok("getToolHistory", Map.of("scope", SCOPE, "tool", v2.revision().tool().definition())).path("data").path("records").size());
        manager.set(false);
        request("getToolHistory", Map.of("scope", SCOPE, "tool", v2.revision().tool().definition()), null, "alice", 403);
    }

    @Test
    public void stateEndpointCannotBypassPublicationReview() throws Exception {
        request("setToolState", Map.of("scope", SCOPE, "tool", v2.revision().tool().definition(), "action", "PUBLISH", "expectedVersion", "1", "reason", "bypass"), "bypass", "alice", 400);
        assertEquals(ToolRelease.State.DRAFT, releases.find(v2.revision().tool().definition()).block(WAIT).state());
    }

    @Test
    public void upgradePreviewAndConfirmationAreAnExplicitRoundTrip() throws Exception {
        request("publishTool", Map.of("scope", SCOPE, "tool", v2.revision().tool().definition(), "expectedVersion", "0", "reason", "new", "review", review()), "publish-v2", "alice", 200);
        String id = create("binding", "A").path("data").path("bindingId").asString();
        var settings = settings(v2, Map.of("text", "new"));
        var preview = ok("previewBindingUpgrade", Map.of("scope", SCOPE, "bindingId", id, "expectedVersion", "1", "settings", settings)).path("data");
        assertTrue(preview.path("valid").asBoolean());
        assertFalse(preview.path("compatible").asBoolean());
        assertFalse(preview.toString().contains("tool-adapter"));
        assertEquals(0, count("binding_resolution"));
        var command = new LinkedHashMap<String, Object>();
        command.put("scope", SCOPE);
        command.put("bindingId", id);
        command.put("expectedVersion", "1");
        command.put("reason", "upgrade");
        command.put("settings", settings);
        command.put("expectedPreviewDigest", preview.path("digest").asString());
        request("upgradeBinding", command, "without-ack", "alice", 409);
        command.put("acceptIncompatible", true);
        request("upgradeBinding", command, "with-ack", "alice", 200);
        assertEquals("2", ok("getBinding", Map.of("scope", SCOPE, "bindingId", id)).path("data").path("binding").path("version").asString());
    }

    @Test
    public void localCatalogRefreshIsDurableAtomicAndDoesNotPublishTools() throws Exception {
        definitions.set(List.of(v1, v2, v3));
        int reads = sourceReads.get();
        var result = request("refreshLocalCatalog", Map.of("scope", SCOPE, "reason", "refresh"), "refresh", "alice", 200).path("data");
        assertEquals(3, result.path("tools").asInt());
        assertTrue(result.path("nodeDiscoveryHealthy").asBoolean());
        assertEquals(result.path("checksum"), result.path("nodeChecksum"));
        assertEquals(1, count("discovery_command"));
        assertEquals(3, count("definition"));
        assertEquals(ToolRelease.State.DRAFT, releases.find(v3.revision().tool().definition()).block(WAIT).state());
        assertEquals("REPLAYED", request("refreshLocalCatalog", Map.of("scope", SCOPE, "reason", "refresh"), "refresh", "alice", 200).path("data").path("delivery").asString());
        assertEquals(reads + 1, sourceReads.get());
        request("refreshLocalCatalog", Map.of("scope", SCOPE, "reason", "changed"), "refresh", "alice", 409);
    }

    @Test
    public void refreshAuditFailureRollsBackNewDefinitionsAndDoesNotInstallAView() throws Exception {
        definitions.set(List.of(v1, v2, v3));
        String before = discovery.state().snapshot().checksum();
        jdbc.execute("ALTER TABLE arte_ai_tool_discovery_command ADD CONSTRAINT fail_audit CHECK(length(snapshot)<2)");
        request("refreshLocalCatalog", Map.of("scope", SCOPE, "reason", "rollback"), "rollback", "alice", 503);
        assertNull(catalog.findTool(v3.revision().tool().definition()).block(WAIT));
        assertEquals(0, count("discovery_command"));
        assertEquals(before, discovery.state().snapshot().checksum());
        assertFalse(discovery.state().discoveryHealthy());
    }

    @Test
    public void sourceFailureIsSafeDurableAndOriginalRejectionReplays() throws Exception {
        sourceFailed.set(true);
        var result = request("refreshLocalCatalog", Map.of("scope", SCOPE, "reason", "fail"), "fail", "alice", 503);
        assertEquals("SOURCE_FAILED", result.path("data").path("result").asString());
        assertFalse(result.toString().contains("secret-source-value"));
        sourceFailed.set(false);
        int reads = sourceReads.get();
        assertEquals("REPLAYED", request("refreshLocalCatalog", Map.of("scope", SCOPE, "reason", "fail"), "fail", "alice", 503).path("data").path("delivery").asString());
        assertEquals(reads, sourceReads.get());
        assertEquals(1, count("discovery_command"));
    }

    @Test
    public void refreshReplaysOnAnotherNodeDoNotInventDiscoveryHealth() {
        var result = service.refresh(new TestAccess().capture(login("alice")), scope(), "refresh", "refresh").block(WAIT);
        assertTrue(result.receipt().value().successful());
        var otherNode = new LocalToolDiscovery(registry, catalog, new ToolSchemaValidator(), scheduler, clock, Duration.ofSeconds(3));
        var replay = service(otherNode).refresh(new TestAccess().capture(login("alice")), scope(), "refresh", "refresh").block(WAIT);
        assertEquals(StoreOutcome.Code.REPLAYED, replay.receipt().code());
        assertFalse(replay.node().discoveryHealthy());
        assertNull(replay.node().snapshot());
    }

    @Test
    public void refreshChecksCurrentAuthorizationInTheCommittingTransaction() throws Exception {
        commitRevoked.set(true);
        request("refreshLocalCatalog", Map.of("scope", SCOPE, "reason", "deny"), "deny", "alice", 403);
        assertEquals(0, count("discovery_command"));
    }

    @Test
    public void revocationDuringMetadataLoadingCannotCommitCatalogFacts() throws Exception {
        definitions.set(List.of(v1, v2, v3));
        revokeAfterSource.set(true);
        request("refreshLocalCatalog", Map.of("scope", SCOPE, "reason", "race"), "race", "alice", 403);
        assertNull(catalog.findTool(v3.revision().tool().definition()).block(WAIT));
        assertEquals(0, count("discovery_command"));
    }

    @Test
    public void sqlFailuresAreSafeServiceErrorsAndDoNotReturnSqlOrConfiguration() throws Exception {
        jdbc.execute("DROP TABLE arte_ai_tool_discovery_command");
        var result = request("refreshLocalCatalog", Map.of("scope", SCOPE, "reason", "storage"), "storage", "alice", 503);
        assertEquals("305002", result.path("code").asString());
        assertFalse(result.toString().contains("SELECT"));
        assertFalse(result.toString().contains("arte_ai_tool"));
    }

    @Test
    public void bilingualFailuresUseExplicitRequestLocale() throws Exception {
        var body = new LinkedHashMap<>(createBody("A"));
        body.put("settings", settings(v1, Map.of("bad", "x")));
        var en = raw("saveBinding", json.writeValueAsString(body), "en-key", "alice", "en-US", 422);
        var zh = raw("saveBinding", json.writeValueAsString(body), "zh-key", "alice", "zh-CN", 422);
        assertEquals(en.path("code"), zh.path("code"));
        assertNotEquals(en.path("desc"), zh.path("desc"));
    }

    private final class TestAccess implements ToolManagementAccess {
        public Session capture(Authentication auth) {
            if (auth == null || !auth.isAuthenticated())
                throw new AuthenticationCredentialsNotFoundException("Required");
            String subject = "subject-" + auth.getName();
            return (scope, operation) -> Mono.defer(() -> {
                if (revoked.get() || !scope.equals(scope()) || operation == Operation.MANAGE && !manager.get())
                    return Mono.error(new AccessDeniedException("Denied"));
                return Mono.just(new Permit() {
                    public TrustedActor actor() {
                        return new TrustedActor(new Principal(subject, PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE), clock.instant());
                    }

                    public ExecutionOwner owner() {
                        return new ExecutionOwner(scope.tenantId(), scope.workspaceId(), subject);
                    }

                    public Operation operation() {
                        return operation;
                    }

                    public Set<String> executionScopes() {
                        return Set.of("ai:read", "ai:invoke");
                    }

                    public void requireInCurrentTransaction(Set<String> required) {
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                        assertTrue(TransactionSynchronizationManager.hasResource(ds));
                        assertFalse(Schedulers.isInNonBlockingThread());
                        finalChecks.incrementAndGet();
                        if (revoked.get() || commitRevoked.get()) throw new AccessDeniedException("Revoked at commit");
                    }
                });
            });
        }
    }

    private static TrustedActor actor(String name) {
        return new TrustedActor(new Principal("subject-" + name, PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE), Instant.now());
    }

    private static Authentication login(String name) {
        return UsernamePasswordAuthenticationToken.authenticated(name, null, List.of());
    }

    private static Scope scope() {
        return new Scope("tenant", "workspace");
    }

    private Map<String, Object> settings(Fixture fixture, Map<String, Object> configuration) {
        return Map.of("tool", fixture.revision().tool().definition(), "versionPolicy", "PINNED", "name", "My tool", "enabled", true, "configuration", configuration);
    }

    private Map<String, Object> createBody(String value) {
        return Map.of("scope", SCOPE, "expectedVersion", "0", "reason", "create", "settings", settings(v1, Map.of("text", value)));
    }

    private Map<String, Object> review() {
        return Map.of("examples", List.of(Map.of("input", Map.of(), "output", Map.of())), "riskEvidence", "reviewed", "minimumApproval", "NONE");
    }

    private JsonNode create(String key, String value) throws Exception {
        return request("saveBinding", createBody(value), key, "alice", 200);
    }

    private JsonNode ok(String path, Object body) throws Exception {
        return request(path, body, null, "alice", 200);
    }

    private JsonNode request(String path, Object body, String key, String name, int expected) throws Exception {
        return raw(path, json.writeValueAsString(body), key, name, "en-US", expected);
    }

    private JsonNode raw(String path, String body, String key, String name, String language, int expected) throws Exception {
        var request = post("/ai-new/tool/" + path).contentType("application/json").characterEncoding("UTF-8").header("Accept-Language", language).content(body);
        if (key != null) request.header("Idempotency-Key", key);
        if (name != null) request.principal(login(name));
        MvcResult result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(WAIT.toMillis());
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        String response = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(response, expected, result.getResponse().getStatus());
        return json.readTree(response);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_" + table, Integer.class);
    }
}
