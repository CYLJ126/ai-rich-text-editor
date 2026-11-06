package com.arte.app.platform.persistence;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.api.model.ModelPublicationService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.application.execution.GenerationDispatcher;
import com.arte.ainew.application.gateway.DefaultModelGateway;
import com.arte.ainew.common.execution.*;
import com.arte.ainew.config.NewAiAdmissionConfiguration;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.config.NewAiGenerationConfiguration;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ChatConfigurationOptions;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.model.ModelDrafts.Probe;
import com.arte.ainew.pojo.model.ModelDrafts.ProbeResult;
import com.arte.ainew.pojo.model.ModelDrafts.Settings;
import com.arte.ainew.pojo.model.ModelDrafts.ValidationState;
import com.arte.ainew.pojo.model.*;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.control.PublishedAiCatalog;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import com.arte.ainew.spi.model.ModelConnectionValidator;
import com.arte.app.platform.ai.*;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.platform.identity.PlatformIdentityConfiguration;
import com.arte.app.platform.identity.RbacAccountMapperConfiguration;
import com.arte.app.platform.identity.RbacAccountReader;
import com.arte.app.platform.workspace.PlatformWorkspaceConfiguration;
import com.arte.app.web.controller.platform.ModelPublicationController;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.platform.AuditSink;
import com.arte.core.spi.workspace.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Spring、DDL、Mapper、普通用户发布、受理／外发复核及回环生成和结算；不连接业务库或真实供应商。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
class PersonalModelPublicationIntegrationTest {

    final ModelAclRuntimeIntegrationTest f = new ModelAclRuntimeIntegrationTest();
    static final Duration WAIT = Duration.ofSeconds(20);
    static final String TOKEN = "private-personal-key-for-test";
    ModelResource model;
    String validation;

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        for (String file : List.of("arte-ai-model-draft-ddl-mysql.sql", "arte-ai-model-publication-ddl-mysql.sql")) {
            String sql = Files.readString(Path.of("scripts", file));
            if (f.f.f.mysql == null) {
                sql = sql.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "").replaceAll("(?i)ENGINE=InnoDB", "").replaceAll("(?im)^SET\\s+[^;]*;", "");
            }
            try (java.sql.Connection connection = f.f.f.source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    ApplicationContextRunner context() {
        return personalConfiguration(f.context());
    }

    ApplicationContextRunner personalConfiguration(ApplicationContextRunner base) {
        return base.withUserConfiguration(PlatformModelDraftConfiguration.class, PlatformModelPublicationConfiguration.class, ModelPublicationStartupSafety.class)
                .withPropertyValues("arte.ai-new-model-management.enabled=true", "arte.ai-new-model-publication.enabled=true",
                        "arte.ai-new-model-management.active-key-id=primary", "arte.ai-new-model-management.fingerprint-key-id=primary",
                        "arte.ai-new-model-management.encryption-keys.primary=" + Base64.getEncoder().encodeToString(new byte[16]));
    }

    ApplicationContextRunner realGenerationContext() {
        return realGenerationContext(true);
    }

    ApplicationContextRunner realGenerationContext(boolean fileSeed) {
        ApplicationContextRunner base = new ApplicationContextRunner().withUserConfiguration(
                        PlatformWorkspaceConfiguration.class,
                        PlatformIdentityConfiguration.class, PlatformPersistenceConfiguration.class,
                        DatabaseAiControlService.class, DatabaseAiControlIntegrationTest.Stores.class, NewAiAdmissionConfiguration.class,
                        PlatformModelResourceConfiguration.class, PlatformModelAclConfiguration.class, ModelAclStartupSafety.class)
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(NewAiGenerationConfiguration.class))
                .withPropertyValues("arte.platform.identity.enabled=true", "arte.platform.identity.legacy-user-id-ceiling=0",
                        "arte.platform.persistence.enabled=true", "arte.platform.workspace.enabled=true", "arte.ai-new-generation.enabled=true")
                .withBean("appDataSource", javax.sql.DataSource.class, () -> f.f.f.source)
                .withBean(RbacAccountReader.class, () -> new RbacAccountReader(f.f.f.source,
                        new RbacAccountMapperConfiguration().identityUserMapper(f.f.f.source)))
                .withBean(org.redisson.api.RedissonClient.class, () -> org.mockito.Mockito.mock(org.redisson.api.RedissonClient.class));
        base = fileSeed ? base.withPropertyValues(f.f.seedProperties()) : base.withPropertyValues(
                "arte.ai-new.enabled=true", "arte.ai-new.configuration-mode=DATABASE", "arte.ai-new.release-ref=release-v1");
        return personalConfiguration(base);
    }

    void policy(ApplicationContext c, String host, boolean allowed) {
        f.f.f.tx.required(() -> {
            new WorkspaceGovernanceLocks(f.f.f.tx, c.getBean(TenantStore.class), c.getBean(WorkspaceStore.class),
                    c.getBean(TenantMembershipStore.class), c.getBean(WorkspaceMembershipStore.class), c.getBean(PreferenceStore.class))
                    .scopes(f.f.f.actor.principal().subjectId(), List.of(f.scope));
            PolicyStore store = c.getBean(PolicyStore.class);
            TenantPolicy tenant = store.findCurrentTenantPolicy(f.scope.tenantId()).orElseThrow();
            PolicyRules old = tenant.rules();
            PolicyRules rules = new PolicyRules(old.allowedCreationTypes(), old.extraCreationRoles(), old.guestAiAllowed(), allowed,
                    Set.of("chat-completions-sse"), Set.of("deepseek"), Set.of(host), old.exportTypes(), old.egressTypes(), old.shareTargetKinds());
            assertTrue(store.replaceTenantPolicy(new TenantPolicy(tenant.tenantId(), rules, tenant.membersMayCreateWorkspace(), tenant.policySchemaVersion(), tenant.rolePresetVersion(), tenant.version() + 1, tenant.personalTemplateVersion()), tenant.version()));
            WorkspacePolicy workspace = store.findCurrentWorkspacePolicy(f.scope).orElseThrow();
            assertTrue(store.replaceWorkspacePolicy(new WorkspacePolicy(f.scope, rules, workspace.policySchemaVersion(), workspace.rolePresetVersion(), workspace.version() + 1), workspace.version()));
            return null;
        });
    }

    void prepare(ApplicationContext c) {
        prepare(c, "api.deepseek.com", false);
    }

    void prepare(ApplicationContext c, String host, boolean network) {
        DatabaseAiControlService db = c.getBean(DatabaseAiControlService.class);
        DatabaseAiTestCatalog.publish(db, c, f.f.f.actor, f.f.meta("import"));
        DatabaseAiTestCatalog.prepare(c, f.f.f.actor, f.scope);
        policy(c, host, true);
        f.f.f.jdbc.update("DELETE FROM arte_rbac_relation");
        model = c.getBean(ModelResourceService.class).create(f.f.f.actor, new ModelResourceCommands.Create(f.f.meta("create-personal"), f.scope, "Personal")).value();
        save(c, 0, "deepseek-chat");
        ModelDraftService drafts = c.getBean(ModelDraftService.class);
        drafts.replaceCredential(f.f.f.actor, f.f.meta("credential"), f.scope, model.resource(), 0, TOKEN);
        validate(c, 1, 1, network);
    }

    void save(ApplicationContext c, long version, String remote) {
        c.getBean(ModelDraftService.class).save(f.f.f.actor, f.f.meta("draft-" + model.resource().resourceId() + "-" + version), f.scope, model.resource(), version, "Personal",
                new Settings(f.f.ref("model-plan", "text"), 8192, 128));
    }

    void validate(ApplicationContext c, long dv, long cv, boolean network) {
        ModelDraftService drafts = c.getBean(ModelDraftService.class);
        validation = drafts.requestValidation(f.f.f.actor, f.f.meta("validation-" + model.resource().resourceId() + "-" + dv + "-" + cv), f.scope, model.resource(), dv, cv).value().id();
        Probe probe = drafts.claimValidation(f.f.f.actor, f.scope, validation).orElseThrow();
        ProbeResult result = network ? c.getBean(ModelConnectionValidator.class).validate(probe).block(WAIT) : new ProbeResult(true, "MODEL_FOUND");
        assertEquals(ValidationState.SUCCEEDED, drafts.completeValidation(f.f.f.actor, f.scope, validation, result).state());
    }

    ModelResourceVersion publishSelectedFixture(ApplicationContext c, String key, long mv, long dv, long cv) {
        ModelResourceVersion version = c.getBean(ModelPublicationService.class).publish(f.f.f.actor, f.f.meta(key), f.scope, model.resource(), mv, dv, cv,
                validation, f.f.ref("model-plan", "text"), 1).value();
        // 发布后显式调用真实空间启用与本人准备接口。
        DatabaseAiTestCatalog.select(c, f.f.f.actor, f.scope, model.resource().resourceId(), version.publication().binding().definition());
        DatabaseAiTestCatalog.prepareOwn(c, f.f.f.actor, f.scope, model.resource().resourceId(), version.publication().binding().definition());
        return version;
    }

    ModelPublications.Status status(ApplicationContext c, ModelResourceVersion version) {
        return c.getBean(ModelPublicationService.class).status(f.f.f.actor, f.scope, model.resource(), version.publication().binding().definition());
    }

    Invocation accept(ApplicationContext c, ModelResourceVersion version, int output) {
        return accept(c, version, output, f.conversation(c));
    }

    Invocation accept(ApplicationContext c, ModelResourceVersion version, int output, String conversation) {
        ExecutionContext execution = f.execution(c, "submit-" + UUID.randomUUID());
        ContextRequest input = new ContextRequest(List.of(new ChatMessage("user", ChatMessage.Role.USER, List.of(new ChatMessage.Text("hello")), List.of(), null)),
                null, List.of(), List.of(), null, new ContextBudget(8192, 4096, 512, 0));
        EntryRequests.Chat request = new EntryRequests.Chat(conversation, 0, null, null, input,
                version.publication().binding().capability().definition(), version.publication().binding().definition(),
                new GenerationOptions(output, null, null, List.of()), new ExecutionOptions(execution.deadline(), 1, 4096, 0, 0, Duration.ofSeconds(60)));
        AcceptedExecution accepted = c.getBean(ChatService.class).submit(request, execution).block(WAIT);
        return c.getBean(MybatisExecutionPersistence.class).find(ExecutionOwners.from(execution), accepted.executionId()).block(WAIT);
    }

    AiAdmissionAuthorizationGuard.Check check(ApplicationContext c, Invocation invocation, ModelResourceVersion version, AiAdmissionAuthorizationGuard.Boundary boundary) {
        ContextSnapshot snapshot = c.getBean(MybatisPayloadPersistence.class).find(ExecutionOwners.from(invocation.request().context()), invocation.contextSnapshotId()).block(WAIT);
        return new AiAdmissionAuthorizationGuard.Check(invocation.request(), version.publication().binding(), snapshot, null, boundary);
    }

    @Test
    void ordinaryUserPublishesWithoutLegacyMigrationAndDiscoversSameImmutableDefinitions() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            long accounts = f.count("arte_ai_account");
            assertEquals(1, c.getBean(ModelPublicationService.class).options(f.f.f.actor, f.scope, model.resource()).size());
            ModelResourceVersion version = publishSelectedFixture(c, "publish", 0, 1, 1);
            assertEquals(version, publishSelectedFixture(c, "publish", 0, 1, 1));
            assertEquals(0, f.count("arte_ai_model_acl_scope"));
            assertEquals(accounts + 1, f.count("arte_ai_account"));
            assertEquals("VALID", status(c, version).reason());
            ExecutionContext execution = f.execution(c, "discover");
            AiEntitlement entitlement = c.getBean(AiResourceEntitlementService.class).resolve(version.publication().binding().definition(), version.publication().binding().capability().definition(), execution).block(WAIT);
            assertEquals(model.resource(), entitlement.modelAccess().resource());
            assertEquals(ModelPublication.Source.RESOURCE, entitlement.modelAccess().definitionSource());
            assertEquals(ExecutionOwners.from(execution), entitlement.executionOwner());
            ChatConfigurationOptions.ModelOption option = c.getBean(ChatConfigurationQueryService.class).discover(execution).block(WAIT).options().stream()
                    .filter(value -> value.binding().equals(version.publication().binding().definition())).findFirst().orElseThrow();
            assertEquals(128, option.limits().maxOutputTokens());
            assertEquals("Personal", option.displayName());
            assertEquals(model.resource().resourceId(), option.resourceId());
            assertEquals("RESOURCE", option.source());
            assertThrows(RuntimeException.class, () -> c.getBean(ModelDraftService.class).save(f.f.f.actor, f.f.meta("unreviewed-plan"), f.scope, model.resource(), 1,
                    "Unreviewed", new Settings(new DefinitionRef("model-plan", "missing", "v1"), 8192, 128)));
            assertEquals(version.publication().binding(), c.getBean(BindingManager.class).resolve(version.publication().binding().definition(), version.publication().binding().capability().definition(), execution).block(WAIT));
            assertEquals(Invocation.State.ACCEPTED, accept(c, version, 128).state());
            assertThrows(RuntimeException.class, () -> accept(c, version, 129));
        });
    }

    @Test
    void publicationRejectsStaleValidationCatalogAndForgedTemplateWithoutWrites() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            ModelPublicationService service = c.getBean(ModelPublicationService.class);
            assertThrows(RuntimeException.class, () -> service.publish(f.f.f.actor, f.f.meta("wrong-catalog"), f.scope, model.resource(), 0, 1, 1, validation, f.f.ref("model-plan", "text"), 2));
            assertThrows(RuntimeException.class, () -> service.publish(f.f.f.actor, f.f.meta("wrong-binding"), f.scope, model.resource(), 0, 1, 1, validation, f.f.ref("binding", "other"), 1));
            save(c, 1, "deepseek-chat");
            assertThrows(RuntimeException.class, () -> publishSelectedFixture(c, "stale-validation", 0, 2, 1));
            assertEquals(0, f.count("arte_ai_model_publication"));
            assertEquals(0, f.count("arte_ai_model_version"));
            validate(c, 2, 1, false);
            assertNotNull(publishSelectedFixture(c, "fresh", 0, 2, 1));
            assertThrows(RuntimeException.class, () -> publishSelectedFixture(c, "fresh", 1, 2, 1));
        });
    }

    @Test
    void credentialRotationBlocksOldVersionAndRepublishingKeepsHistoryAndIdentity() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            ModelResourceVersion first = publishSelectedFixture(c, "first", 0, 1, 1);
            String firstValidation = validation;
            ModelDraftService drafts = c.getBean(ModelDraftService.class);
            assertEquals(Boolean.TRUE, drafts.get(f.f.f.actor, f.scope, model.resource()).currentPublicationMatchesDraft());
            drafts.replaceCredential(f.f.f.actor, f.f.meta("rotate"), f.scope, model.resource(), 1, "replacement-personal-key");
            assertEquals(Boolean.FALSE, drafts.get(f.f.f.actor, f.scope, model.resource()).currentPublicationMatchesDraft());
            assertEquals("CREDENTIAL_STALE", status(c, first).reason());
            assertThrows(RuntimeException.class, () -> accept(c, first, 128));
            validate(c, 1, 2, false);
            ModelResourceVersion second = publishSelectedFixture(c, "second", 1, 1, 2);
            assertEquals(first.resource(), second.resource());
            assertEquals(first.publication().binding().definition().id(), second.publication().binding().definition().id());
            assertNotEquals(first.publication().connection().credential(), second.publication().connection().credential());
            assertEquals(2, f.count("arte_ai_model_version"));
            assertEquals("VALID", status(c, second).reason());
            assertEquals(first, c.getBean(ModelPublicationService.class).publish(f.f.f.actor, f.f.meta("first"), f.scope,
                    model.resource(), 0, 1, 1, firstValidation, f.f.ref("model-plan", "text"), 1).value());
            c.getBean(ModelDraftService.class).revokeCredential(f.f.f.actor, f.f.meta("revoke"), f.scope, model.resource(), 2);
            assertEquals("CREDENTIAL_STALE", status(c, second).reason());
        });
    }

    @Test
    void missingBudgetKeepsPublishedRecordButFiltersChatDiscovery() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            ModelResourceVersion version = publishSelectedFixture(c, "publish", 0, 1, 1);
            f.f.f.jdbc.update("DELETE FROM arte_ai_account");
            f.f.f.jdbc.update("DELETE FROM arte_ai_budget_definition");
            assertEquals("VALID", status(c, version).reason());
            ExecutionContext execution = f.f.execution(c.getBean(DatabaseAiControlService.class), f.scope, Set.of(AdmissionAuthorization.INVOKE), "discover");
            assertTrue(c.getBean(ChatConfigurationQueryService.class).discover(execution).block(WAIT).options().isEmpty());
            assertEquals(1, f.count("arte_ai_model_publication"));
        });
    }

    @Test
    void currentPermitAndExternalSendRejectRevocationAndDisabledResource() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            ModelResourceVersion version = publishSelectedFixture(c, "publish", 0, 1, 1);
            Invocation invocation = accept(c, version, 128);
            AiAdmissionAuthorizationGuard guard = c.getBean(AiAdmissionAuthorizationGuard.class);
            AiAdmissionAuthorizationGuard.Permit permit = guard.authorize(check(c, invocation, version, AiAdmissionAuthorizationGuard.Boundary.DISPATCH)).block(WAIT);
            f.revoke(c, c.getBean(ModelResourceService.class).get(f.f.f.actor, f.scope, model.resource()));
            assertThrows(RuntimeException.class, () -> f.f.f.tx.required(() -> {
                permit.requireInCurrentTransaction(invocation.request());
                return null;
            }));
            assertThrows(RuntimeException.class, () -> guard.authorize(check(c, invocation, version, AiAdmissionAuthorizationGuard.Boundary.EXTERNAL_SEND)).block(WAIT));
            assertEquals(0, f.count("arte_ai_reservation"));
            c.getBean(ModelResourceService.class).disable(f.f.f.actor, new ModelResourceCommands.Disable(f.f.meta("disable"), f.scope, model.resource(), 1));
            assertEquals("MODEL_DISABLED", status(c, version).reason());
        });
    }

    @Test
    void policyTighteningAndExpiredValidationRejectPublication() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            ModelPublicationService service = c.getBean(ModelPublicationService.class);
            ReflectionTestUtils.setField(service, "clock", Clock.offset(Clock.systemUTC(), Duration.ofMinutes(11)));
            assertThrows(RuntimeException.class, () -> publishSelectedFixture(c, "expired", 0, 1, 1));
            ReflectionTestUtils.setField(service, "clock", Clock.systemUTC());
            policy(c, "api.deepseek.com", false);
            assertThrows(RuntimeException.class, () -> publishSelectedFixture(c, "policy-denied", 0, 1, 1));
            assertEquals(0, f.count("arte_ai_model_version"));
        });
    }

    @Test
    void auditFailureRollsBackPublicationReceiptAndDefinitions() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            Object effects = ReflectionTestUtils.getField(c.getBean(ModelPublicationService.class), "effects");
            ReflectionTestUtils.setField(effects, "audit", (AuditSink) entry -> {
                throw new IllegalStateException("test audit failure");
            });
            long receipts = f.count("arte_platform_command");
            assertThrows(RuntimeException.class, () -> publishSelectedFixture(c, "rollback", 0, 1, 1));
            assertEquals(0, f.count("arte_ai_model_publication"));
            assertEquals(0, f.count("arte_ai_model_version"));
            assertEquals(0, f.count("arte_ai_model_definition_source"));
            assertEquals(receipts, f.count("arte_platform_command"));
        });
    }

    @Test
    void secretReferenceAndAcceptedInvocationAloneNeverDecryptAndRestartCannotDisableGate() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            ModelResourceVersion version = publishSelectedFixture(c, "publish", 0, 1, 1);
            ConnectionCredentialResolver resolver = c.getBean(ConnectionCredentialResolver.class);
            assertThrows(RuntimeException.class, () -> resolver.resolve(version.publication().connection().credential(), f.execution(c, "made-up-execution")).block(WAIT));
            Invocation accepted = accept(c, version, 128);
            assertThrows(RuntimeException.class, () -> resolver.resolve(version.publication().connection().credential(), accepted.request().context()).block(WAIT));
        });
        f.context().withUserConfiguration(ModelPublicationStartupSafety.class).run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void httpContractsRejectExtraFieldsAndReturnOnlySafePublicationViews() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            PlatformAuthenticatedExecutor executor = new PlatformAuthenticatedExecutor(c.getBean(com.arte.app.platform.identity.RbacIdentityProvider.class),
                    new PlatformPersistenceExecutor(c.getBean("newAiPersistenceScheduler", reactor.core.scheduler.Scheduler.class), f.f.f.tx));
            ModelPublicationController controller = new ModelPublicationController(executor, c.getBean(ModelPublicationService.class));
            String scopeJson = f.f.f.json.write(f.scope);
            String body = "{\"scope\":" + scopeJson + ",\"resourceId\":\"" + model.resource().resourceId() + "\"}";
            try (ManagementHttpHarness http = new ManagementHttpHarness(controller, new com.arte.app.web.controller.platform.ReviewedModelPlanController(executor, c.getBean(ModelDraftService.class)))) {
                org.springframework.security.core.Authentication auth = ManagementHttpHarness.login(f.f.f.jdbc, 1, "alice");
                http.ok("/ai-new/model-draft/listReviewedModelPlans", body, null, auth);
                http.rejected("/ai-new/model-draft/listReviewedModelPlans", body.substring(0, body.length() - 1) + ",\"endpoint\":\"https://attacker.invalid\"}", null, auth, 400, "INVALID_ARGUMENT");
                String publish = body.substring(0, body.length() - 1) + ",\"expectedVersion\":0,\"draftVersion\":1,\"credentialVersion\":1,\"validationId\":\"" + validation + "\",\"planRef\":" + f.f.f.json.write(f.f.ref("model-plan", "text")) + ",\"catalogRevision\":1}";
                String result = http.ok("/ai-new/model-resource/publishModel", publish, "http-publish", auth).toString();
                assertTrue(result.contains("PERSONAL"));
                assertFalse(result.contains(TOKEN));
                assertFalse(result.contains("ciphertext"));
                assertFalse(result.contains("endpoint"));
                assertFalse(result.contains("model-resource-"));
            }
        });
    }

    @Test
    void realLoopbackValidationGenerationAndDurableSettlementUseOnlyPersonalKey() {
        loopback(false);
    }

    @Test
    void newDatabaseOnlyModelValidatesPublishesChatsSettlesAndSurvivesReassemblyWithoutFileSeed() {
        AtomicInteger calls = new AtomicInteger();
        reactor.netty.DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).route(routes -> routes
                .get("/v1/models", (request, response) -> {
                    assertEquals("Bearer " + TOKEN, request.requestHeaders().get("Authorization"));
                    return response.header("Content-Type", "application/json").sendString(Mono.just("{\"data\":[{\"id\":\"independent-model\"}]}"));
                }).post("/v1/chat/completions", (request, response) -> {
                    assertEquals("Bearer " + TOKEN, request.requestHeaders().get("Authorization"));
                    calls.incrementAndGet();
                    return request.receive().aggregate().asString().flatMap(body -> {
                        assertTrue(body.contains("independent-model"));
                        return response.header("Content-Type", "text/event-stream").sendString(Mono.just(
                                "data: {\"id\":\"independent\",\"model\":\"independent-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\ndata: [DONE]\n\n")).then();
                    });
                })).bindNow();
        try {
            String origin = "http://127.0.0.1:" + server.port();
            f.f.allowOrigins(origin);
            ApplicationContextRunner runner = realGenerationContext(false);
            java.util.concurrent.atomic.AtomicReference<ModelResourceVersion> published = new java.util.concurrent.atomic.AtomicReference<>();
            runner.run(c -> {
                assertNull(c.getStartupFailure());
                NewAiProperties infrastructure = c.getBean(NewAiProperties.class);
                assertTrue(infrastructure.connections().isEmpty());
                assertTrue(infrastructure.bindings().isEmpty());
                assertTrue(infrastructure.rates().isEmpty());
                DatabaseAiControlService db = c.getBean(DatabaseAiControlService.class);
                assertTrue(db.definitions().connections().isEmpty());
                com.arte.core.pojo.reference.DefinitionRef connection = db.publishConnection(f.f.f.actor, f.f.meta("independent-connection"), 0,
                                new DatabaseAiControlService.ConnectionSettings("independent", "deepseek", java.net.URI.create(origin + "/v1"), 5, 60, 1048576))
                        .value().catalog().connections().getFirst().definition();
                AiControlData.Catalog reviewed = db.publishModelPlan(f.f.f.actor, f.f.meta("independent-plan"), 1,
                        new ReviewedModelSettings("independent", connection, "independent-model", 65536, "CNY", "1", "2", true, true)).value().catalog();
                com.arte.ainew.pojo.model.ReviewedModelPlan plan = reviewed.reviewedPlans().getFirst();
                // 预算审批是独立、显式操作；方案发布不分配预算。
                assertTrue(reviewed.defaults().budgets().isEmpty());
                db.updateDefaultAiPolicy(f.f.f.actor, f.f.meta("independent-budget"), 2, true,
                        List.of(new AiControlData.BudgetPreset(plan.rate(), f.f.money("100"))));
                DatabaseAiTestCatalog.prepare(c, f.f.f.actor, f.scope);
                policy(c, "127.0.0.1", true);
                f.f.f.jdbc.update("DELETE FROM arte_rbac_relation");
                model = c.getBean(ModelResourceService.class).create(f.f.f.actor,
                        new ModelResourceCommands.Create(f.f.meta("independent-model"), f.scope, "Independent model")).value();
                ModelDraftService drafts = c.getBean(ModelDraftService.class);
                drafts.save(f.f.f.actor, f.f.meta("independent-draft"), f.scope, model.resource(), 0, "Independent model", new Settings(reviewed.reviewedPlans().getFirst().planRef(), 8192, 128));
                drafts.replaceCredential(f.f.f.actor, f.f.meta("independent-key"), f.scope, model.resource(), 0, TOKEN);
                validate(c, 1, 1, true);
                ModelPublicationService publications = c.getBean(ModelPublicationService.class);
                ModelPublications.Option option = publications.options(f.f.f.actor, f.scope, model.resource()).getFirst();
                assertEquals(plan.planRef(), option.planRef());
                ModelResourceVersion version = publications.publish(f.f.f.actor, f.f.meta("independent-publish"), f.scope, model.resource(),
                        0, 1, 1, validation, option.planRef(), option.catalogRevision()).value();
                assertTrue(version.publication().binding().capability().features().contains(CapabilityDescriptor.Feature.TOOL_CALLS));
                published.set(version);
                DatabaseAiTestCatalog.select(c, f.f.f.actor, f.scope, model.resource().resourceId(), version.publication().binding().definition());
                DatabaseAiTestCatalog.prepareOwn(c, f.f.f.actor, f.scope, model.resource().resourceId(), version.publication().binding().definition());
                assertTrue(status(c, version).valid());
                Invocation accepted = accept(c, version, 128);
                MybatisExecutionPersistence executions = c.getBean(MybatisExecutionPersistence.class);
                MybatisPayloadPersistence payloads = c.getBean(MybatisPayloadPersistence.class);
                GenerationDispatcher dispatcher = new GenerationDispatcher(c.getBean(AdmissionAuthorization.class), c.getBean(CapabilityCatalog.class), c.getBean(BindingManager.class),
                        c.getBean(PublishedAiCatalog.class), c.getBean(AiExecutionPolicyProvider.class), c.getBean(AiAdmissionAuthorizationGuard.class), executions, executions, executions, payloads, payloads, executions,
                        c.getBean(com.arte.ainew.spi.gateway.ModelGateway.class), infrastructure,
                        new NewAiExecutionProperties(true, false, 0, null, null, null, null), Clock.systemUTC(), null);
                OutboxMessage message = executions.claim(OutboxMessage.Kind.DISPATCH, "independent-worker", Duration.ofSeconds(60), 1).block(WAIT).getFirst();
                dispatcher.dispatch(message, ExecutionRuntimeContext.start(accepted.request().context())).block(WAIT);
                Invocation complete = executions.find(message.owner(), message.invocationId()).block(WAIT);
                assertEquals(Invocation.State.SUCCEEDED, complete.state(), String.valueOf(complete.error()));
                assertEquals(1, calls.get());
                assertEquals(1L, f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_settlement WHERE evidence_kind='PROVIDER_BILL'", Long.class));
                assertTrue(f.f.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_settlement", String.class).contains("SETTLED"));

                String conversation = c.getBean(com.arte.ainew.api.conversation.ConversationService.class)
                        .create("出口撤销验证", null, List.of(), f.f.execution(db, f.scope,
                                Set.of(AdmissionAuthorization.CONVERSATION), "egress-conversation")).block(WAIT).conversationId();
                Invocation pending = accept(c, version, 128, conversation);
                f.f.f.jdbc.update("INSERT INTO arte_rbac_relation VALUES('alice','user_to_operation','aiSecurity:manageEgress')");
                db.updateEgressPolicy(f.f.f.actor, f.f.meta("revoke-independent-egress"), 0, List.of());
                OutboxMessage revoked = executions.claim(OutboxMessage.Kind.DISPATCH, "independent-worker", Duration.ofSeconds(60), 1).block(WAIT).getFirst();
                dispatcher.dispatch(revoked, ExecutionRuntimeContext.start(pending.request().context())).block(WAIT);
                Invocation denied = executions.find(revoked.owner(), revoked.invocationId()).block(WAIT);
                assertEquals(Invocation.State.FAILED, denied.state());
                assertEquals(1, calls.get());
                db.updateEgressPolicy(f.f.f.actor, f.f.meta("restore-independent-egress"), 1, List.of(origin));
            });
            runner.run(c -> {
                assertNull(c.getStartupFailure());
                assertTrue(c.getBean(NewAiProperties.class).bindings().isEmpty());
                assertEquals(3, c.getBean(DatabaseAiControlService.class).catalog().version());
                assertTrue(c.getBean(DatabaseAiControlService.class).definitions().bindings().isEmpty());
                assertEquals("independent-model", c.getBean(DatabaseAiControlService.class).catalog().catalog().reviewedPlans().getFirst().remoteModel());
                assertTrue(status(c, published.get()).valid());
                assertEquals(1, calls.get());
            });
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void missingDecryptionKeyFailsBeforeSendAndReleasesReservation() {
        loopback(true);
    }

    void loopback(boolean missingKey) {
        loopback(missingKey, null);
    }

    void loopback(boolean missingKey, String governanceChange) {
        java.util.concurrent.atomic.AtomicReference<Runnable> duringResponse = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        reactor.netty.DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).route(routes -> routes
                .get("/v1/models", (request, response) -> {
                    assertEquals("Bearer " + TOKEN, request.requestHeaders().get("Authorization"));
                    return response.header("Content-Type", "application/json").sendString(Mono.just("{\"data\":[{\"id\":\"deepseek-chat\"}]}"));
                }).post("/v1/chat/completions", (request, response) -> {
                    assertEquals("Bearer " + TOKEN, request.requestHeaders().get("Authorization"));
                    calls.incrementAndGet();
                    return request.receive().aggregate().asString().flatMap(body -> {
                        assertTrue(body.contains("deepseek-chat"));
                        assertFalse(body.contains(TOKEN));
                        Runnable change = duringResponse.get();
                        return Mono.fromRunnable(() -> {
                            if (change != null) {
                                change.run();
                            }
                        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then(response.header("Content-Type", "text/event-stream").sendString(Mono.just(
                                "data: {\"id\":\"personal-test\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\ndata: [DONE]\n\n")).then());
                    });
                })).bindNow();
        try {
            String origin = "http://127.0.0.1:" + server.port();
            f.f.allowOrigins(origin);
            realGenerationContext().withPropertyValues("arte.ai-new.connections[0].endpoint=" + origin + "/v1",
                    "arte.ai-new.capabilities[0].features[2]=" + ("CHILD".equals(governanceChange) ? "TOOL_CALLS" : "TEXT_INPUT"),
                    "arte.ai-new.bindings[0].capability.features[2]=" + ("CHILD".equals(governanceChange) ? "TOOL_CALLS" : "TEXT_INPUT")).run(c -> {
                assertNull(c.getStartupFailure());
                prepare(c, "127.0.0.1", true);
                ModelResourceVersion version = publishSelectedFixture(c, "publish", 0, 1, 1);
                Invocation accepted = accept(c, version, 128);
                com.arte.app.platform.ai.WorkspaceAiGovernanceService governance = c.getBean(com.arte.app.platform.ai.WorkspaceAiGovernanceService.class);
                if ("RULE".equals(governanceChange)) {
                    duringResponse.set(() -> {
                        com.arte.app.platform.ai.AiControlData.BudgetRulesView rules = governance.getRules(f.f.f.actor, f.scope);
                        governance.updateRules(f.f.f.actor, f.f.meta("in-flight-revoke"), f.scope, rules.workspaceVersion(), rules.configuration().version(), List.of());
                    });
                }
                if (missingKey) {
                    ReflectionTestUtils.setField(c.getBean(ModelPublicationCredentialResolver.class), "cipher", new Sm4ModelCredentialCipher(
                            new ModelManagementProperties(true, "replacement", "replacement", Map.of("replacement", Base64.getEncoder().encodeToString(new byte[16])))));
                }
                MybatisExecutionPersistence executions = c.getBean(MybatisExecutionPersistence.class);
                MybatisPayloadPersistence payloads = c.getBean(MybatisPayloadPersistence.class);
                {
                    com.arte.ainew.spi.gateway.ModelGateway gateway = c.getBean(com.arte.ainew.spi.gateway.ModelGateway.class);
                    assertInstanceOf(DefaultModelGateway.class, gateway);
                    assertEquals(1, c.getBeansOfType(ConnectionCredentialResolver.class).size());
                    assertInstanceOf(ModelPublicationCredentialResolver.class, c.getBean(ConnectionCredentialResolver.class));
                    GenerationDispatcher dispatcher = new GenerationDispatcher(c.getBean(AdmissionAuthorization.class), c.getBean(CapabilityCatalog.class), c.getBean(BindingManager.class),
                            c.getBean(PublishedAiCatalog.class), c.getBean(AiExecutionPolicyProvider.class), c.getBean(AiAdmissionAuthorizationGuard.class), executions, executions, executions, payloads, payloads, executions,
                            gateway, c.getBean(NewAiProperties.class), new NewAiExecutionProperties(true, false, 0, null, null, null, null), Clock.systemUTC(), null);
                    OutboxMessage message = executions.claim(OutboxMessage.Kind.DISPATCH, "personal-test-worker", Duration.ofSeconds(60), 1).block(WAIT).getFirst();
                    dispatcher.dispatch(message, ExecutionRuntimeContext.start(accepted.request().context())).block(WAIT);
                    Invocation complete = executions.find(message.owner(), message.invocationId()).block(WAIT);
                    String settlement = f.f.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_settlement", String.class);
                    if (missingKey) {
                        assertEquals(Invocation.State.FAILED, complete.state());
                        assertEquals("CREDENTIAL_NOT_AVAILABLE", complete.error().code());
                        assertEquals(ExecutionError.Certainty.KNOWN, complete.error().certainty());
                        assertEquals(ExecutionError.SideEffect.NONE, complete.error().sideEffect());
                        assertEquals(0, calls.get());
                        assertTrue(settlement.contains("RELEASED"));
                    } else {
                        assertEquals(Invocation.State.SUCCEEDED, complete.state(), String.valueOf(complete.error()));
                        assertEquals(1, calls.get());
                        assertEquals(1L, f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_settlement WHERE evidence_kind='PROVIDER_BILL'", Long.class));
                        assertTrue(settlement.contains("SETTLED"));
                    }
                    if ("RULE".equals(governanceChange)) {
                        assertEquals("BUDGET_RULE_NOT_CONFIGURED", c.getBean(com.arte.ainew.api.model.ModelUsagePreparationService.class)
                                .status(f.f.f.actor, f.scope, model.resource(), version.publication().binding().definition()).reason());
                        assertThrows(RuntimeException.class, () -> accept(c, version, 128));
                        BudgetCommands.Account account = new com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec().decode(
                                f.f.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class), BudgetCommands.Account.class);
                        assertTrue(account.charged().amount().signum() > 0);
                        assertEquals(0, account.held().amount().signum());
                    }
                    if ("VERSION".equals(governanceChange)) {
                        save(c, 1, "deepseek-chat");
                        validate(c, 2, 1, true);
                        ModelResourceVersion next = publishSelectedFixture(c, "version-switch", 1, 2, 1);
                        assertNotEquals(version.publication().binding().definition(), next.publication().binding().definition());
                        assertEquals("MODEL_VERSION_NOT_ENABLED", c.getBean(com.arte.ainew.api.model.ModelUsagePreparationService.class)
                                .status(f.f.f.actor, f.scope, model.resource(), version.publication().binding().definition()).reason());
                        ExecutionContext retry = f.execution(c, "regenerate-old-version");
                        long conversationVersion = c.getBean(com.arte.ainew.api.conversation.ConversationService.class)
                                .find(accepted.conversation().conversationId(), retry).block(WAIT).version();
                        EntryRequests.Regenerate regeneration = new EntryRequests.Regenerate(accepted.request().context().executionId(), conversationVersion,
                                new ExecutionOptions(retry.deadline(), 1, 4096, 0, 0, Duration.ofSeconds(60)));
                        assertThrows(RuntimeException.class, () -> c.getBean(ChatService.class).regenerate(regeneration, retry).block(WAIT));
                        assertEquals(1, f.count("arte_ai_invocation"));
                        assertEquals(1, calls.get());
                    }
                    if ("CHILD".equals(governanceChange)) {
                        com.arte.ainew.pojo.context.ContextSnapshot snapshot = payloads.find(message.owner(), accepted.contextSnapshotId()).block(WAIT);
                        com.arte.app.platform.ai.tool.LocalTextTools local = new com.arte.app.platform.ai.tool.LocalTextTools(Clock.systemUTC());
                        com.arte.ainew.pojo.tool.ToolDefinitionRevision tool = local.definitions().tools().getFirst();
                        com.arte.core.pojo.value.StructuredValue.ObjectValue configuration = new com.arte.core.pojo.value.StructuredValue.ObjectValue(Map.of());
                        com.arte.ainew.pojo.tool.ToolBindingResolution resolution = new com.arte.ainew.pojo.tool.ToolBindingResolution(
                                new DefinitionRef("binding", "m3-child-tool", "p1-" + "1".repeat(64)), new DefinitionRef("binding", "m3-child-tool", "r1"), message.owner(),
                                tool, tool.tool().checksum(), configuration, com.arte.ainew.serialization.ToolCatalogDigests.configuration(configuration),
                                tool.tool().defaultPolicy(), Set.of(), Map.of());
                        com.arte.ainew.pojo.tool.ToolSchemaDefinition schema = local.definitions().schemas().stream().filter(value -> value.definition().equals(tool.tool().inputSchema())).findFirst().orElseThrow();
                        com.arte.ainew.pojo.tool.ToolSetSnapshot tools = new com.arte.ainew.pojo.tool.ToolSetSnapshot(message.owner(),
                                List.of(new com.arte.ainew.pojo.tool.ToolSetSnapshot.Entry("tool_0", resolution, resolution.effectivePolicy(), schema)));
                        snapshot = com.arte.ainew.application.tool.chat.ToolChatInputs.snapshot(UUID.randomUUID().toString(), snapshot, snapshot.messages(), tools,
                                Clock.systemUTC().instant(), c.getBean(NewAiProperties.class).limits());
                        ExecutionContext original = accepted.request().context();
                        ExecutionContext root = new ExecutionContext("m3-tool-chat-run", original.traceId(), original.authorization(), original.deadline(),
                                null, null, original.releaseRef(), "m3-run");
                        com.arte.ainew.pojo.tool.ToolChatBudgetPlan plan = new com.arte.ainew.pojo.tool.ToolChatBudgetPlan(List.of(f.f.money("1")), List.of(
                                new com.arte.ainew.pojo.tool.ToolChatBudgetPlan.Selection(version.publication().binding().definition(), version.publication().binding().capability().definition(),
                                        CapabilityDescriptor.Kind.GENERATION, com.arte.ainew.spi.persistence.DispatchPreparationStore.BillingMode.CHARGE, original.budgetRef(),
                                        version.publication().rate().definition(), new com.arte.ainew.pojo.tool.ToolChatBudgetPlan.ModelPrice(version.publication().rate().inputPerMillion(), version.publication().rate().outputPerMillion())),
                                new com.arte.ainew.pojo.tool.ToolChatBudgetPlan.Selection(resolution.definition(), tool.tool().capability(), CapabilityDescriptor.Kind.TOOL,
                                        com.arte.ainew.spi.persistence.DispatchPreparationStore.BillingMode.FREE, null, null, null)));
                        com.arte.ainew.persistence.mybatis.MybatisToolChatRunStore runs = new com.arte.ainew.persistence.mybatis.MybatisToolChatRunStore(f.f.f.source,
                                new com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec(), c.getBean("newAiPersistenceScheduler", reactor.core.scheduler.Scheduler.class));
                        com.arte.ainew.pojo.tool.ToolChatRunSnapshot fixed = new com.arte.ainew.pojo.tool.ToolChatRunSnapshot(root, snapshot, version.publication().binding(),
                                new GenerationOptions(128, null, null, List.of()), tools,
                                new com.arte.ainew.pojo.tool.ToolChatRunSnapshot.Limits(Duration.ofSeconds(60), 2, 2, 1), Clock.systemUTC().instant(), plan);
                        assertTrue(runs.accept(fixed, value -> {
                        }).block(WAIT).successful());
                        com.arte.ainew.pojo.tool.ToolChatCommands.Lease lease = runs.claim("m3-run-worker", 1, Duration.ofSeconds(60)).block(WAIT).getFirst();
                        com.arte.ainew.pojo.tool.ToolChatStep step = runs.advance(new com.arte.ainew.pojo.tool.ToolChatCommands.Advance(lease, 0, "m3-plan-model",
                                com.arte.ainew.pojo.tool.ToolChatRun.State.RUNNING, com.arte.ainew.pojo.tool.ToolChatRun.Phase.WAITING_MODEL, 0,
                                List.of(new com.arte.ainew.pojo.tool.ToolChatStep.Plan(0, new com.arte.ainew.pojo.tool.ToolChatStep.Model(snapshot))), Clock.systemUTC().instant(), null, null), value -> {
                        }).block(WAIT).value().steps().getFirst();
                        com.arte.ainew.application.tool.chat.ToolChatBudgetSelector selector = new com.arte.ainew.application.tool.chat.ToolChatBudgetSelector(runs,
                                c.getBean(AdmissionAuthorization.class), c.getBean(AiResourceEntitlementService.class), c.getBean(PublishedAiCatalog.class),
                                c.getBean(com.arte.ainew.spi.control.BudgetDefinitionProvider.class), c.getBean(com.arte.ainew.api.execution.BudgetService.class), null, Clock.systemUTC());
                        ExecutionContext childContext = selector.child(root.executionId(), step.childExecutionId(), Set.of(AdmissionAuthorization.INVOKE), original.deadline(), root).block(WAIT);
                        com.arte.ainew.pojo.execution.InvocationRequest<com.arte.ainew.pojo.generation.GenerationRequest> childRequest = new com.arte.ainew.pojo.execution.InvocationRequest<>(
                                accepted.request().capability(), accepted.request().binding(), accepted.request().kind(), new com.arte.ainew.pojo.generation.GenerationRequest(snapshot.messages(), new GenerationOptions(128, null, null, List.of()), tools.descriptions(), new com.arte.ainew.pojo.generation.GenerationRequest.TextOutput()),
                                new ExecutionOptions(childContext.deadline(), 1, 4096, 1, 1, Duration.ofSeconds(60)), childContext);
                        AcceptedExecution child = c.getBean(com.arte.ainew.api.execution.InvocationCoordinator.class).submit(
                                new com.arte.ainew.pojo.execution.InvocationSubmission<>(childRequest, snapshot, null, null, null)).block(WAIT);
                        Invocation childInvocation = executions.find(message.owner(), child.executionId()).block(WAIT);
                        assertEquals("m3-tool-chat-run", childInvocation.request().context().parentExecutionId());
                        com.arte.app.platform.ai.AiControlData.BudgetRulesView rules = governance.getRules(f.f.f.actor, f.scope);
                        governance.updateRules(f.f.f.actor, f.f.meta("child-rule-revoke"), f.scope, rules.workspaceVersion(), rules.configuration().version(), List.of());
                        ContextSnapshot childSnapshot = snapshot;
                        assertThrows(RuntimeException.class, () -> selector.child(root.executionId(), step.childExecutionId(), Set.of(AdmissionAuthorization.INVOKE), original.deadline(), root).block(WAIT));
                        assertThrows(RuntimeException.class, () -> c.getBean(AiAdmissionAuthorizationGuard.class).authorize(new AiAdmissionAuthorizationGuard.Check(childRequest, version.publication().binding(), childSnapshot, tools,
                                AiAdmissionAuthorizationGuard.Boundary.EXTERNAL_SEND)).block(WAIT));
                        assertEquals(1, calls.get());
                    }
                    assertEquals(ExecutionOwners.from(accepted.request().context()), message.owner());
                }
            });
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void sharedModelActionsDoNotGrantUseOfAnotherPersonsCredential() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            ModelResourceVersion version = publishSelectedFixture(c, "publish", 0, 1, 1);
            f.f.f.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-30 10:00:00"));
            com.arte.app.platform.identity.RbacIdentityProvider identity = c.getBean(com.arte.app.platform.identity.RbacIdentityProvider.class);
            TrustedActor bob = new TrustedActor(identity.resolveAuthenticatedName("bob").orElseThrow(), Instant.now());
            f.f.f.tx.required(() -> {
                new WorkspaceGovernanceLocks(f.f.f.tx, c.getBean(TenantStore.class), c.getBean(WorkspaceStore.class), c.getBean(TenantMembershipStore.class),
                        c.getBean(WorkspaceMembershipStore.class), c.getBean(PreferenceStore.class)).tenant(bob.principal().subjectId(), f.scope.tenantId());
                assertTrue(c.getBean(TenantMembershipStore.class).insert(new TenantMembership("bob-tenant", f.scope.tenantId(), bob.principal().subjectId(),
                        com.arte.core.enums.workspace.TenantRoleEnum.MEMBER, com.arte.core.enums.workspace.MembershipStateEnum.ACTIVE, 0, 0)));
                assertTrue(c.getBean(WorkspaceMembershipStore.class).insert(new WorkspaceMembership("bob-space", f.scope, bob.principal().subjectId(),
                        com.arte.core.enums.workspace.WorkspaceRoleEnum.MEMBER, com.arte.core.enums.workspace.MembershipStateEnum.ACTIVE, 0, 0)));
                return null;
            });
            long acl = c.getBean(com.arte.core.spi.access.ResourceGrantStore.class).findAccess(model.resource()).orElseThrow().aclVersion();
            c.getBean(com.arte.core.application.workspace.DefaultWorkspaceServices.class).resources().grant(f.f.f.actor,
                    new com.arte.core.pojo.access.ResourceAccessCommands.GrantResource(f.f.meta("share"), f.scope, model.resource(), acl,
                            new com.arte.core.pojo.access.GrantRecipient.Subject(bob.principal().subjectId()),
                            Set.of(com.arte.core.constant.platform.PlatformActions.MODEL_READ, com.arte.core.constant.platform.PlatformActions.MODEL_USE,
                                    com.arte.core.constant.platform.PlatformActions.MODEL_PUBLISH), null));
            ModelPublicationService service = c.getBean(ModelPublicationService.class);
            assertEquals("VALID", service.status(bob, f.scope, model.resource(), version.publication().binding().definition()).reason());
            assertThrows(RuntimeException.class, () -> service.publish(bob, f.f.meta("bob-publish"), f.scope, model.resource(), 1, 1, 1, validation, f.f.ref("model-plan", "text"), 1));
            ExecutionAuthorization authorization = c.getBean(DatabaseAiControlService.class).resolve("bob", f.scope.tenantId(), f.scope.workspaceId(), Set.of(AdmissionAuthorization.INVOKE)).block(WAIT);
            ExecutionContext execution = new ExecutionContext(UUID.randomUUID().toString(), "bob-test", authorization, Instant.now().plusSeconds(60), null, null, "release-v1", "bob");
            assertThrows(RuntimeException.class, () -> c.getBean(AiResourceEntitlementService.class).resolve(version.publication().binding().definition(), version.publication().binding().capability().definition(), execution).block(WAIT));
        });
    }

    @Test
    void concurrentPublishWithSameExpectedVersionCommitsOnlyOneVersion() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            try (java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    String key = "concurrent-" + i;
                    results.add(threads.submit(() -> {
                        start.await();
                        try {
                            publishSelectedFixture(c, key, 0, 1, 1);
                            return true;
                        } catch (com.arte.core.exception.platform.PlatformException conflict) {
                            assertEquals(com.arte.core.enums.platform.PlatformErrorCodeEnum.VERSION_CONFLICT, conflict.getCode());
                            return false;
                        }
                    }));
                }
                start.countDown();
                int successes = 0;
                for (java.util.concurrent.Future<Boolean> result : results) {
                    if (result.get(15, java.util.concurrent.TimeUnit.SECONDS)) {
                        successes++;
                    }
                }
                assertEquals(1, successes);
                assertEquals(1, f.count("arte_ai_model_publication"));
                assertEquals(1, f.count("arte_ai_model_version"));
            }
        });
    }

    @Test
    void mysqlSourceConstraintMigrationAcceptsPersonalAndRejectsRepeatedExecution() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(f.f.f.mysql != null, "Requires isolated MySQL");
        f.f.f.jdbc.execute("ALTER TABLE arte_ai_model_definition_source DROP CHECK ck_ai_model_definition_source, ADD CONSTRAINT old_source_check CHECK (source >= 0 AND source <= 1)");
        String sql = Files.readString(Path.of("scripts/arte-ai-model-personal-source-migration-mysql.sql"));
        try (java.sql.Connection connection = f.f.f.source.getConnection()) {
            ByteArrayResource script = new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8));
            ScriptUtils.executeSqlScript(connection, script);
            assertThrows(RuntimeException.class, () -> ScriptUtils.executeSqlScript(connection, script));
        }
        assertEquals(1, f.f.f.jdbc.update("INSERT INTO arte_ai_model_definition_source VALUES(?,?,?,?,?,?)", "a".repeat(64), "binding", "migration-test", "v1", 2, "b".repeat(64)));
        assertThrows(RuntimeException.class, () -> f.f.f.jdbc.update("INSERT INTO arte_ai_model_definition_source VALUES(?,?,?,?,?,?)", "c".repeat(64), "binding", "invalid-source", "v1", 3, "d".repeat(64)));
    }
}
