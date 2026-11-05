package com.arte.app.platform.persistence;

import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.application.model.DefaultModelDraftService;
import com.arte.ainew.pojo.model.ModelDrafts.*;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.spi.model.ModelConnectionTemplates;
import com.arte.ainew.spi.model.ModelConnectionValidator;
import com.arte.ainew.spi.model.ModelCredentialCipher;
import com.arte.ainew.spi.model.ModelDraftStore;
import com.arte.app.platform.ai.ModelManagementProperties;
import com.arte.app.platform.ai.ReviewedModelConnectionTemplates;
import com.arte.app.platform.ai.Sm4ModelCredentialCipher;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.service.platform.ModelDraftStoreServiceImpl;
import com.arte.app.web.controller.platform.ModelDraftController;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.GrantRecipient;
import com.arte.core.pojo.access.ResourceAccessCommands;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.platform.AuditSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.arte.core.constant.platform.PlatformActions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real DDL, mapper, ACL, receipts and HTTP; validation never contacts a supplier.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
class ModelDraftIntegrationTest {

    final ModelResourceIntegrationTest f = new ModelResourceIntegrationTest();
    ModelDraftService service;
    ModelDraftStore store;
    ModelCredentialCipher cipher;
    ModelConnectionTemplates templates;
    ModelResource model;
    MutableClock clock = new MutableClock();
    static final String TOKEN = "test-private-api-key-DO-NOT-RETURN";
    static final String ROOT = "/ai-new/model-draft/";
    Settings settings = new Settings(new com.arte.core.pojo.reference.DefinitionRef("model-plan", "test-plan", "v1"), 8192, 1024);

    static class MutableClock extends Clock {
        Instant value = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return value;
        }
    }

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        var original = f.seed.connections().getFirst();
        var reviewed = new com.arte.ainew.pojo.control.ConnectionDefinition(original.definition(), original.providerId(),
                com.arte.ainew.infrastructure.http.ChatCompletionsSseProtocolAdapter.DEFINITION, java.net.URI.create("https://example.invalid/v1/chat/completions"),
                original.credential(), original.state(), original.connectTimeout(), original.responseTimeout(), original.maxResponseBytes());
        f.seed = new com.arte.ainew.config.NewAiProperties(false, "appDataSource", f.seed.releaseRef(), f.seed.persistence(), f.seed.limits(), f.seed.grants(),
                f.seed.capabilities(), f.seed.bindings(), List.of(reviewed), f.seed.rates(), f.seed.budgets());
        String ddl = Files.readString(Path.of("scripts/arte-ai-model-draft-ddl-mysql.sql"));
        if (f.f.mysql == null) ddl = ddl.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "")
                .replaceAll("(?i)ENGINE=InnoDB", "").replaceAll("(?im)^SET\\s+[^;]*;", "");
        try (var connection = f.f.source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(ddl.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        var cfg = new PlatformMybatisConfiguration();
        var session = cfg.platformMapperSession(f.f.source, new PlatformPersistenceProperties(true, 1, 1, 5, 15, 0, null, null, null, null, null, false));
        store = new ModelDraftStoreServiceImpl(cfg.platformModelResourceMapper(session), cfg.platformModelDraftMapper(session), f.f.tx, f.f.json);
        cipher = new Sm4ModelCredentialCipher(new ModelManagementProperties(true, "primary", "primary", Map.of("primary", Base64.getEncoder().encodeToString(new byte[16]))));
        var http = new com.arte.ainew.config.NewAiGenerationProperties(false, List.of(java.net.URI.create("https://example.invalid")), List.of(), 0, 0, 0, null, 0, 0);
        templates = new ReviewedModelConnectionTemplates(() -> f.seed, f.stores.policies(), new com.arte.ainew.infrastructure.http.HttpEgressPolicy(http));
        policy(true);
        service = graph(f.stores.audit());
        model = f.create("create-draft-model");
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    ModelDraftService graph(AuditSink audit) {
        return new DefaultModelDraftService(f.store, store, templates, cipher, () -> new com.arte.ainew.spi.model.ReviewedModelPlanSource.Snapshot(
                List.of(new com.arte.ainew.pojo.model.ReviewedModelPlan(settings.planRef(), "Test model", ModelResourceIntegrationTest.CONN, "test-model",
                        Set.of(com.arte.ainew.pojo.control.CapabilityDescriptor.Feature.TEXT_INPUT, com.arte.ainew.pojo.control.CapabilityDescriptor.Feature.STREAMING),
                        8192, f.seed.rates().getFirst().definition(), Instant.parse("2026-01-01T00:00:00Z"), f.f.actor.principal().subjectId(), 1)),
                f.seed.connections(), f.seed.rates().stream().map(rate -> new com.arte.ainew.pojo.control.RateDefinition(rate.definition(), rate.inputPerMillion(), rate.outputPerMillion(), rate.toolPerCall())).toList(), 1, f.seed.releaseRef()),
                () -> org.mockito.Mockito.mock(com.arte.ainew.spi.gateway.ModelGateway.class, invocation -> invocation.getMethod().getName().startsWith("supports") ? true : org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)), f.f.tx, f.identity, f.stores.tenants(), f.stores.workspaces(),
                f.stores.tenantMembers(), f.stores.workspaceMembers(), f.stores.preferences(), f.platform.authorization(), f.stores.receipts(), f.stores.outbox(), audit,
                () -> UUID.randomUUID().toString(), clock);
    }

    WorkspaceGovernanceLocks locks() {
        return new WorkspaceGovernanceLocks(f.f.tx, f.stores.tenants(), f.stores.workspaces(), f.stores.tenantMembers(), f.stores.workspaceMembers(), f.stores.preferences());
    }

    void policy(boolean personal) {
        f.f.tx.required(() -> {
            locks().scopes(f.f.actor.principal().subjectId(), List.of(f.scope));
            var p = f.stores.policies().findCurrentTenantPolicy(f.scope.tenantId()).orElseThrow();
            var r = p.rules();
            var rules = new PolicyRules(r.allowedCreationTypes(), r.extraCreationRoles(), r.guestAiAllowed(), personal,
                    Set.of("chat-completions-sse"), Set.of("test"), Set.of("example.invalid"), r.exportTypes(), r.egressTypes(), r.shareTargetKinds());
            assertTrue(f.stores.policies().replaceTenantPolicy(new TenantPolicy(p.tenantId(), rules, p.membersMayCreateWorkspace(), p.policySchemaVersion(), p.rolePresetVersion(), p.version() + 1, p.personalTemplateVersion()), p.version()));
            var w = f.stores.policies().findCurrentWorkspacePolicy(f.scope).orElseThrow();
            assertTrue(f.stores.policies().replaceWorkspacePolicy(new WorkspacePolicy(w.scope(), rules, w.policySchemaVersion(), w.rolePresetVersion(), w.version() + 1), w.version()));
            return null;
        });
    }

    Draft save(String key, long expected) {
        return service.save(f.f.actor, f.meta(key), f.scope, model.resource(), expected, "My model", settings).value();
    }

    void credential() {
        service.replaceCredential(f.f.actor, f.meta("credential"), f.scope, model.resource(), 0, TOKEN);
    }

    Validation request(String key) {
        return service.requestValidation(f.f.actor, f.meta(key), f.scope, model.resource(), 1, 1).value();
    }

    void error(PlatformErrorCodeEnum code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(PlatformException.class, action).getCode());
    }

    @Test
    void draftsUseSeparateVersionsAndNeverPublishOrChangeExistingResource() {
        f.f.jdbc.update("DELETE FROM arte_rbac_relation"); // normal user, no platform management permission
        assertEquals(1, service.templates(f.f.actor, f.scope).size());
        Draft first = save("save", 0);
        assertEquals(first, save("save", 0));
        assertEquals(1, first.version());
        assertEquals(2, save("save-next", 1).version());
        assertEquals(ModelResource.State.DRAFT, f.current(model).state());
        assertEquals(0, f.current(model).version());
        assertEquals(0, f.count("arte_ai_model_version"));
        assertFalse(f.allowed(model, MODEL_USE));
        error(PlatformErrorCodeEnum.VERSION_CONFLICT, () -> save("stale", 1));
        error(PlatformErrorCodeEnum.IDEMPOTENCY_CONFLICT, () -> service.save(f.f.actor, f.meta("save"), f.scope, model.resource(), 0, "different", settings));
    }

    @Test
    void credentialEncryptionIsWriteOnlyAndReceiptsNeverPersistPlaintextOrBareKeyHash() {
        save("save", 0);
        credential();
        var result = service.get(f.f.actor, f.scope, model.resource());
        assertEquals("CONFIGURED", result.credential().state());
        assertTrue(result.credential().mayMaintain());
        for (String table : List.of("arte_ai_model_draft", "arte_ai_model_credential", "arte_platform_command")) {
            var rows = f.f.jdbc.queryForList("SELECT * FROM " + table).toString();
            assertFalse(rows.contains(TOKEN));
            assertFalse(rows.contains(com.arte.core.application.platform.DefaultCommandExecutor.digest(List.of(TOKEN))));
        }
        assertFalse(f.f.json.write(result).contains(TOKEN));
        assertEquals(1, service.replaceCredential(f.f.actor, f.meta("credential"), f.scope, model.resource(), 0, TOKEN).value().version());
        error(PlatformErrorCodeEnum.IDEMPOTENCY_CONFLICT, () -> service.replaceCredential(f.f.actor, f.meta("credential"), f.scope, model.resource(), 0, "another-key"));
    }

    @Test
    void unapprovedConnectionsAndPersonalCredentialPolicyFailClosed() {
        policy(false);
        assertTrue(service.templates(f.f.actor, f.scope).isEmpty());
        error(PlatformErrorCodeEnum.ACCESS_DENIED, () -> save("denied", 0));
        assertEquals(0, f.count("arte_ai_model_draft"));
    }

    @Test
    void auditFailureRollsBackDraftCredentialAndReceiptTogether() {
        var broken = graph(entry -> {
            throw new IllegalStateException("audit unavailable");
        });
        assertThrows(IllegalStateException.class, () -> broken.save(f.f.actor, f.meta("broken"), f.scope, model.resource(), 0, "Draft", settings));
        assertEquals(0, f.count("arte_ai_model_draft"));
        save("save", 0);
        assertThrows(IllegalStateException.class, () -> broken.replaceCredential(f.f.actor, f.meta("broken-key"), f.scope, model.resource(), 0, TOKEN));
        assertEquals(0, f.count("arte_ai_model_credential"));
    }

    @Test
    void durableValidationCanOnlyBeClaimedOnceAndDoesNotPublish() {
        save("save", 0);
        credential();
        var v = request("validate");
        var probe = service.claimValidation(f.f.actor, f.scope, v.id()).orElseThrow();
        assertEquals(TOKEN, probe.token());
        assertNull(probe.connection().credential());
        assertFalse(probe.toString().contains(TOKEN));
        assertTrue(service.claimValidation(f.f.actor, f.scope, v.id()).isEmpty());
        assertEquals(ValidationState.SUCCEEDED, service.completeValidation(f.f.actor, f.scope, v.id(), new ProbeResult(true, "MODEL_FOUND")).state());
        assertEquals(v.id(), request("validate").id());
        assertTrue(service.claimValidation(f.f.actor, f.scope, v.id()).isEmpty());
        assertFalse(f.allowed(model, MODEL_USE));
        assertEquals(0, f.count("arte_ai_model_version"));
    }

    @Test
    void credentialRotationInvalidatesAnInflightAndPreviouslySuccessfulValidation() {
        save("save", 0);
        credential();
        var v = request("validate");
        service.claimValidation(f.f.actor, f.scope, v.id());
        service.replaceCredential(f.f.actor, f.meta("rotate"), f.scope, model.resource(), 1, "replacement-key");
        assertEquals(ValidationState.STALE, service.completeValidation(f.f.actor, f.scope, v.id(), new ProbeResult(true, "MODEL_FOUND")).state());
        var next = service.requestValidation(f.f.actor, f.meta("next-validation"), f.scope, model.resource(), 1, 2).value();
        service.claimValidation(f.f.actor, f.scope, next.id());
        service.completeValidation(f.f.actor, f.scope, next.id(), new ProbeResult(true, "MODEL_FOUND"));
        save("save-next", 1);
        assertEquals(ValidationState.STALE, service.validation(f.f.actor, f.scope, next.id()).state());
    }

    @Test
    void crashOrLostResponseExpiresWithoutRepeatingRemoteWork() {
        save("save", 0);
        credential();
        var v = request("validate");
        service.claimValidation(f.f.actor, f.scope, v.id());
        clock.value = clock.value.plusSeconds(31);
        assertEquals(ValidationState.STALE, service.validation(f.f.actor, f.scope, v.id()).state());
        assertTrue(service.claimValidation(f.f.actor, f.scope, v.id()).isEmpty());
    }

    @Test
    void changedReviewedTemplateInvalidatesValidationEvenWhenItsReferenceIsUnchanged() {
        save("save", 0);
        credential();
        var v = request("validate");
        service.claimValidation(f.f.actor, f.scope, v.id());
        var old = f.seed.connections().getFirst();
        var changed = new com.arte.ainew.pojo.control.ConnectionDefinition(old.definition(), old.providerId(), old.protocol(),
                java.net.URI.create("https://example.invalid/v2/chat/completions"), old.credential(), old.state(), old.connectTimeout(), old.responseTimeout(), old.maxResponseBytes());
        f.seed = new com.arte.ainew.config.NewAiProperties(false, "appDataSource", f.seed.releaseRef(), f.seed.persistence(), f.seed.limits(), f.seed.grants(),
                f.seed.capabilities(), f.seed.bindings(), List.of(changed), f.seed.rates(), f.seed.budgets());
        assertEquals(ValidationState.STALE, service.completeValidation(f.f.actor, f.scope, v.id(), new ProbeResult(true, "MODEL_FOUND")).state());
    }

    @Test
    void validationIdDoesNotAuthorizeAnotherSpaceOrRevealItsResult() {
        save("save", 0);
        credential();
        var v = request("validate");
        var other = f.platform.workspaces().create(f.f.actor, new WorkspaceCommands.CreateWorkspace(f.meta("other"), f.scope.tenantId(), "Other", null)).value().scope();
        error(PlatformErrorCodeEnum.NOT_FOUND, () -> service.validation(f.f.actor, other, v.id()));
        error(PlatformErrorCodeEnum.NOT_FOUND, () -> service.claimValidation(f.f.actor, other, v.id()));
    }

    @Test
    void concurrentValidationClaimsGiveOnlyOneCallerTheProbe() throws Exception {
        save("save", 0);
        credential();
        var v = request("validate");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                results.add(pool.submit(() -> {
                    start.await();
                    return service.claimValidation(f.f.actor, f.scope, v.id()).isPresent();
                }));
            start.countDown();
            int claimed = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) claimed++;
            assertEquals(1, claimed);
        }
    }

    @Test
    void credentialsCanBeRevokedByResponsiblePersonAfterMembershipRemovalWithoutRestoringReads() {
        save("save", 0);
        credential();
        f.f.tx.required(() -> {
            locks().scopes(f.f.actor.principal().subjectId(), List.of(f.scope));
            var member = f.stores.workspaceMembers().lock(f.scope, f.f.actor.principal().subjectId()).orElseThrow();
            assertTrue(f.stores.workspaceMembers().replace(new WorkspaceMembership(member.id(), member.scope(), member.subjectId(), member.role(), MembershipStateEnum.REVOKED, member.generation(), member.version() + 1), member.version()));
            return null;
        });
        assertEquals("REVOKED", service.revokeCredential(f.f.actor, f.meta("revoke"), f.scope, model.resource(), 1).value().state());
        assertThrows(PlatformException.class, () -> service.get(f.f.actor, f.scope, model.resource()));
        assertFalse(f.f.jdbc.queryForObject("SELECT payload FROM arte_ai_model_credential", String.class).contains("ciphertext\":\""));
    }

    TrustedActor bob() {
        f.f.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-30 10:00:00"));
        var principal = f.identity.resolveAuthenticatedName("bob").orElseThrow();
        f.f.tx.required(() -> {
            locks().tenant(principal.subjectId(), f.scope.tenantId());
            assertTrue(f.stores.tenantMembers().insert(new TenantMembership("bob-tenant", f.scope.tenantId(), principal.subjectId(), TenantRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
            assertTrue(f.stores.workspaceMembers().insert(new WorkspaceMembership("bob-space", f.scope, principal.subjectId(), WorkspaceRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
            return null;
        });
        return new TrustedActor(principal, clock.instant());
    }

    @Test
    void readersEditorsAndValidatorsDoNotInheritCredentialMaintenance() {
        save("save", 0);
        credential();
        var bob = bob();
        assertTrue(service.list(bob, f.scope, null, 20).records().isEmpty());
        long acl = f.stores.grants().findAccess(model.resource()).orElseThrow().aclVersion();
        f.platform.resources().grant(f.f.actor, new ResourceAccessCommands.GrantResource(f.meta("share"), f.scope, model.resource(), acl,
                new GrantRecipient.Subject(bob.principal().subjectId()), Set.of(MODEL_READ, MODEL_EDIT, MODEL_VALIDATE), null));
        assertEquals(1, service.list(bob, f.scope, null, 20).records().size());
        assertFalse(service.get(bob, f.scope, model.resource()).credential().mayMaintain());
        error(PlatformErrorCodeEnum.ACCESS_DENIED, () -> service.replaceCredential(bob, f.meta("steal"), f.scope, model.resource(), 1, "bob-key"));
        error(PlatformErrorCodeEnum.ACCESS_DENIED, () -> service.revokeCredential(bob, f.meta("revoke"), f.scope, model.resource(), 1));
        error(PlatformErrorCodeEnum.ACCESS_DENIED, () -> service.requestValidation(bob, f.meta("probe"), f.scope, model.resource(), 1, 1));
    }

    @Test
    void boundedListCursorCannotBeReusedByAnotherScopeOrTampered() {
        f.create("second-model");
        var page = service.list(f.f.actor, f.scope, null, 1);
        assertEquals(1, page.records().size());
        assertNotNull(page.nextCursor());
        assertEquals(1, service.list(f.f.actor, f.scope, page.nextCursor(), 1).records().size());
        error(PlatformErrorCodeEnum.INVALID_ARGUMENT, () -> service.list(f.f.actor, f.scope, page.nextCursor().substring(0, 128) + "X", 1));
        var other = f.platform.workspaces().create(f.f.actor, new WorkspaceCommands.CreateWorkspace(f.meta("other"), f.scope.tenantId(), "Other", null)).value().scope();
        error(PlatformErrorCodeEnum.INVALID_ARGUMENT, () -> service.list(f.f.actor, other, page.nextCursor(), 1));
        error(PlatformErrorCodeEnum.NOT_FOUND, () -> service.get(f.f.actor, other, model.resource()));
    }

    @Test
    void filteredHttpDirectoryCanContinueAnEmptyBatchAndBindsQueryToCursor() {
        var candidates = new ArrayList<ModelResource>();
        candidates.add(model);
        for (int i = 0; i < 100; i++) {
            candidates.add(f.models.create(f.f.actor, new com.arte.ainew.pojo.model.ModelResourceCommands.Create(
                    f.meta("filter-" + i), f.scope, "Fixture model " + String.format(Locale.ROOT, "%03d", i))).value());
        }
        ModelResource last = candidates.stream()
                .max(Comparator.comparing(candidate -> DefaultModelDraftService.resourceKey(candidate.resource()))).orElseThrow();
        var scheduler = Schedulers.newBoundedElastic(2, 16, "model-filter-test");
        var executor = new PlatformAuthenticatedExecutor(f.identity, new PlatformPersistenceExecutor(scheduler, f.f.tx));
        try (var http = new ManagementHttpHarness(new ModelDraftController(executor, service,
                probe -> Mono.error(new AssertionError("Browsing must not probe")), f.models))) {
            var auth = ManagementHttpHarness.login(f.f.jdbc, 1, "alice");
            var request = new HashMap<String, Object>(Map.of("scope", f.scope, "size", 2,
                    "modelKind", "PERSONAL", "state", "DRAFT", "search", " " + last.name().toUpperCase(Locale.ROOT) + " "));
            var first = http.ok(ROOT + "listModelResources", f.f.json.write(request), null, auth).getJSONObject("data");
            assertTrue(first.getJSONArray("records").isEmpty());
            String cursor = first.getStr("nextCursor");
            assertNotNull(cursor);
            request.put("cursor", cursor);
            request.put("search", last.name());
            var second = http.ok(ROOT + "listModelResources", f.f.json.write(request), null, auth).getJSONObject("data");
            assertEquals(last.resource().resourceId(), second.getJSONArray("records").getJSONObject(0).getStr("resourceId"));
            assertNull(second.getStr("nextCursor"));

            for (var mismatch : Map.of("search", "other", "size", 3, "modelKind", "TENANT", "state", "PUBLISHED").entrySet()) {
                var changed = new HashMap<>(request);
                changed.put(mismatch.getKey(), mismatch.getValue());
                http.rejected(ROOT + "listModelResources", f.f.json.write(changed), null, auth, 400, "INVALID_ARGUMENT");
            }
            var hidden = bob();
            assertTrue(service.list(hidden, f.scope, null, 20,
                    new com.arte.ainew.pojo.model.ModelListFilter(last.name(), null, null)).records().isEmpty());
            request.remove("cursor");
            request.put("state", "NOT_A_STATE");
            http.rejected(ROOT + "listModelResources", f.f.json.write(request), null, auth, 400, "INVALID_ARGUMENT");
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void parallelDraftUpdatesHaveOneWinner() throws Exception {
        save("save", 0);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String key = "parallel-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        save(key, 1);
                        return true;
                    } catch (PlatformException conflict) {
                        assertEquals(PlatformErrorCodeEnum.VERSION_CONFLICT, conflict.getCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int success = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) success++;
            assertEquals(1, success);
            assertEquals(2, service.get(f.f.actor, f.scope, model.resource()).draft().version());
        }
    }

    @Test
    void httpPreparationUsesNormalUserAndDoesNetworkOutsideTransactionWithSafeViews() {
        var scheduler = Schedulers.newBoundedElastic(3, 32, "draft-http-test");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var executor = new PlatformAuthenticatedExecutor(f.identity, new PlatformPersistenceExecutor(scheduler, f.f.tx));
        var validator = (ModelConnectionValidator) probe -> Mono.fromCallable(() -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            calls.incrementAndGet();
            return new ProbeResult(true, "MODEL_FOUND");
        });
        try (var http = new ManagementHttpHarness(new ModelDraftController(executor, service, validator, f.models))) {
            var auth = ManagementHttpHarness.login(f.f.jdbc, 1, "alice");
            f.f.jdbc.update("DELETE FROM arte_rbac_relation");
            Map<String, Object> target = Map.of("scope", f.scope, "resourceId", model.resource().resourceId());
            var body = new HashMap<String, Object>(target);
            body.putAll(Map.of("expectedVersion", 0, "name", "HTTP model", "settings", settings));
            http.ok(ROOT + "saveModelDraft", f.f.json.write(body), "save-http", auth);
            var key = new HashMap<String, Object>(target);
            key.putAll(Map.of("expectedVersion", 0, "token", TOKEN));
            String response = http.ok(ROOT + "replaceModelCredential", f.f.json.write(key), "key-http", auth).toString();
            assertFalse(response.contains(TOKEN));
            var validate = new HashMap<String, Object>(target);
            validate.putAll(Map.of("draftVersion", 1, "credentialVersion", 1));
            var first = http.ok(ROOT + "validateModelDraft", f.f.json.write(validate), "validate-http", auth).getJSONObject("data");
            assertEquals("SUCCEEDED", first.getJSONObject("current").getStr("state"));
            http.ok(ROOT + "validateModelDraft", f.f.json.write(validate), "validate-http", auth);
            assertEquals(1, calls.get());
            var malicious = new HashMap<>(body);
            malicious.put("endpoint", "https://unreviewed.invalid");
            http.rejected(ROOT + "saveModelDraft", f.f.json.write(malicious), "injection", auth, 400, "INVALID_ARGUMENT");
            http.rejected(ROOT + "getModelDraft", f.f.json.write(target), null, null, 401, "UNAUTHENTICATED");
            var publicResult = http.ok(ROOT + "getModelDraft", f.f.json.write(target), null, auth).toString();
            assertFalse(publicResult.contains(TOKEN));
            assertFalse(publicResult.contains("ciphertext"));
            assertFalse(publicResult.contains("secret"));
        } finally {
            scheduler.dispose();
        }
    }
}
