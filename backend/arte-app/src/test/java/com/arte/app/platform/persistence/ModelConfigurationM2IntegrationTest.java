package com.arte.app.platform.persistence;

import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.api.model.ModelPublicationService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.application.model.DefaultModelPublicationService;
import com.arte.ainew.pojo.model.*;
import com.arte.ainew.spi.model.ReviewedModelPlanSource;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.web.controller.platform.ModelDraftController;
import com.arte.app.web.controller.platform.ModelPublicationController;
import com.arte.app.web.controller.platform.ReviewedModelPlanController;
import com.arte.core.pojo.reference.DefinitionRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.scheduler.Scheduler;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 使用新装隔离库验证实际发布边界；不提供空间启用事实，不借执行回归夹具自动启用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 14:20 ✾
 */
class ModelConfigurationM2IntegrationTest {

    private final PersonalModelPublicationIntegrationTest f = new PersonalModelPublicationIntegrationTest();

    @BeforeEach
    void setup() throws Exception {
        f.setup();
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    @Test
    void aNewDraftChoosesAReviewedPlanBeforeAnyCredentialOrBudgetIsRequired() {
        f.context().run(c -> {
            assertNull(c.getStartupFailure());
            f.prepare(c);
            ModelResource next = c.getBean(ModelResourceService.class).create(f.f.f.f.actor,
                    new ModelResourceCommands.Create(f.f.f.meta("new-resource"), f.f.scope, "Second", ModelResourceCommands.ModelKind.PERSONAL)).value();
            ModelDraftService drafts = c.getBean(ModelDraftService.class);
            assertNull(drafts.get(f.f.f.f.actor, f.f.scope, next.resource()).draft());
            ModelDrafts.ReviewedPlanList page = drafts.plans(f.f.f.f.actor, f.f.scope, next.resource(), null, 1);
            assertFalse(page.records().isEmpty());
            ReviewedModelPlan plan = page.records().getFirst();
            ModelDrafts.Settings settings = new ModelDrafts.Settings(plan.planRef(), 8192, 128);
            ModelDrafts.Draft saved = drafts.save(f.f.f.f.actor, f.f.f.meta("adopt-plan"), f.f.scope, next.resource(), 0, "Second", settings).value();
            assertEquals(plan, saved.plan());
            assertEquals(settings.planRef(), saved.plan().planRef());
            assertEquals("MISSING", drafts.get(f.f.f.f.actor, f.f.scope, next.resource()).credential().state());
            assertThrows(RuntimeException.class, () -> drafts.save(f.f.f.f.actor, f.f.f.meta("oversized"), f.f.scope, next.resource(), 1,
                    "Second", new ModelDrafts.Settings(plan.planRef(), plan.contextWindowTokens() + 1, 128)));
            assertThrows(IllegalArgumentException.class, () -> new ModelDrafts.Settings(new DefinitionRef("model-plan", plan.planRef().id(), "latest"), 8192, 128));
        });
    }

    @Test
    void publishingOnlyRecordsAResourceReleaseAndCannotEnableOrExecuteIt() {
        f.context().run(c -> {
            assertNull(c.getStartupFailure());
            f.prepare(c);
            long accounts = f.f.count("arte_ai_account");
            long spaces = f.f.count("arte_platform_workspace");
            ModelPublicationService publications = c.getBean(ModelPublicationService.class);
            ModelResourceVersion version = publications.publish(f.f.f.f.actor, f.f.f.meta("m2-publish"), f.f.scope, f.model.resource(), 0, 1, 1,
                    f.validation, f.f.f.ref("model-plan", "text"), 1).value();
            assertEquals(ModelPublication.Source.RESOURCE, version.publication().source());
            assertEquals(3, version.publication().source().code());
            assertEquals(1L, version.publication().catalogRevision());
            assertEquals(f.f.f.ref("model-plan", "text"), version.publication().planRef());
            assertEquals(ModelDrafts.CredentialKind.PERSONAL, version.publication().credentialKind());
            assertEquals(accounts, f.f.count("arte_ai_account"));
            assertEquals(spaces, f.f.count("arte_platform_workspace"));
            assertTrue(f.selections.list(f.f.scope, true).isEmpty());
            assertTrue(f.status(c, version).valid());
            assertEquals("VALID", f.status(c, version).reason());
            assertTrue(c.getBean(ChatConfigurationQueryService.class).discover(f.f.execution(c, "m2-discover")).block(PersonalModelPublicationIntegrationTest.WAIT).options().isEmpty());
            assertThrows(RuntimeException.class, () -> f.accept(c, version, 128));
            assertEquals(version, publications.publish(f.f.f.f.actor, f.f.f.meta("m2-publish"), f.f.scope, f.model.resource(), 0, 1, 1,
                    f.validation, f.f.f.ref("model-plan", "text"), 1).value());
            assertTrue(f.selections.list(f.f.scope, true).isEmpty());
        });
    }

    @Test
    void changingPlanContentsUnderTheSameReferenceRejectsPublicationAndCommitsNothing() {
        f.context().run(c -> {
            assertNull(c.getStartupFailure());
            f.prepare(c);
            DefaultModelPublicationService publications = c.getBean(DefaultModelPublicationService.class);
            ReviewedModelPlanSource.Snapshot snapshot = f.f.f.f.tx.required(() -> {
                publications.lock(f.f.f.f.actor, f.f.scope);
                return c.getBean(DatabaseAiControlService.class).reviewedModelCatalog();
            });
            ReviewedModelPlan original = snapshot.plans().getFirst();
            ReviewedModelPlan forged = new ReviewedModelPlan(original.planRef(), original.displayName(), original.connection(), "different-remote-model",
                    original.features(), original.contextWindowTokens(), original.rate(), original.publishedAt(), original.publishedBy(), original.catalogRevision());
            ReflectionTestUtils.setField(publications, "catalog", (ReviewedModelPlanSource) () -> new ReviewedModelPlanSource.Snapshot(
                    List.of(forged), snapshot.connections(), snapshot.rates(), snapshot.catalogRevision(), snapshot.releaseRef()));
            long receipts = f.f.count("arte_platform_command");
            assertThrows(RuntimeException.class, () -> publications.publish(f.f.f.f.actor, f.f.f.meta("bad-plan"), f.f.scope, f.model.resource(), 0, 1, 1,
                    f.validation, original.planRef(), 1));
            assertEquals(0, f.f.count("arte_ai_model_version"));
            assertEquals(0, f.f.count("arte_ai_model_publication"));
            assertEquals(receipts, f.f.count("arte_platform_command"));
        });
    }

    @Test
    void theDraftHttpContractRejectsDependencyOverridesAndTheOldPublicationRoutesAreGone() {
        f.context().run(c -> {
            assertNull(c.getStartupFailure());
            f.prepare(c);
            PlatformAuthenticatedExecutor executor = new PlatformAuthenticatedExecutor(c.getBean(RbacIdentityProvider.class),
                    new PlatformPersistenceExecutor(c.getBean("newAiPersistenceScheduler", Scheduler.class), f.f.f.f.tx));
            ModelDraftService drafts = c.getBean(ModelDraftService.class);
            try (ManagementHttpHarness http = new ManagementHttpHarness(
                    new ModelDraftController(executor, drafts, c.getBean(com.arte.ainew.spi.model.ModelConnectionValidator.class), c.getBean(ModelResourceService.class)),
                    new ReviewedModelPlanController(executor, drafts), new ModelPublicationController(executor, c.getBean(ModelPublicationService.class)))) {
                org.springframework.security.core.Authentication auth = ManagementHttpHarness.login(f.f.f.f.jdbc, 1, "alice");
                String located = "\"scope\":" + f.f.f.f.json.write(f.f.scope) + ",\"resourceId\":\"" + f.model.resource().resourceId() + "\"";
                String body = "{" + located + ",\"expectedVersion\":1,\"name\":\"Updated\",\"settings\":{\"planRef\":"
                        + f.f.f.f.json.write(f.f.f.ref("model-plan", "text")) + ",\"contextWindowTokens\":8192,\"maxOutputTokens\":128}}";
                http.ok("/ai-new/model-draft/saveModelDraft", body, "http-v2", auth);
                http.rejected("/ai-new/model-draft/saveModelDraft", body.replace("\"contextWindowTokens\":8192", "\"remoteModel\":\"forged\",\"contextWindowTokens\":8192"), "http-forged", auth, 400, "INVALID_ARGUMENT");
                long versions = f.f.count("arte_ai_model_version");
                org.springframework.test.web.servlet.MvcResult removed = http.execute("/ai-new/model-resource/publishPersonalModel", "{" + located + "}", "old-key", auth);
                assertFalse(cn.hutool.json.JSONUtil.parseObj(ManagementHttpHarness.contents(removed)).getBool("success"));
                assertEquals(versions, f.f.count("arte_ai_model_version"));
                assertTrue(java.util.Arrays.stream(ModelPublicationController.class.getDeclaredMethods()).noneMatch(method -> {
                    org.springframework.web.bind.annotation.PostMapping mapping = method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
                    return mapping != null && List.of(mapping.value()).contains("/publishPersonalModel");
                }));
            }
        });
    }

    @Test
    void currentExecutionPolicyStillRunsInsideTheResourceAdmissionTransaction() {
        java.util.concurrent.atomic.AtomicInteger checked = new java.util.concurrent.atomic.AtomicInteger();
        com.arte.ainew.spi.control.AiExecutionPolicyProvider policy = new com.arte.ainew.spi.control.AiExecutionPolicyProvider() {
            private com.arte.ainew.pojo.control.AiExecutionPolicy value(int tokens) {
                return new com.arte.ainew.pojo.control.AiExecutionPolicy(65536, tokens, 1000000,
                        java.time.Duration.ofSeconds(120), java.time.Duration.ofMinutes(10));
            }

            @Override
            public reactor.core.publisher.Mono<com.arte.ainew.pojo.control.AiExecutionPolicy> current(com.arte.ainew.common.execution.ExecutionContext context) {
                return reactor.core.publisher.Mono.just(value(512));
            }

            @Override
            public com.arte.ainew.pojo.control.AiExecutionPolicy inCurrentTransaction(com.arte.ainew.common.execution.ExecutionContext context) {
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(f.f.f.f.source));
                checked.incrementAndGet();
                return value(64);
            }
        };
        f.context().withBean(com.arte.ainew.spi.control.AiExecutionPolicyProvider.class, () -> policy).run(c -> {
            f.prepare(c);
            ModelResourceVersion version = f.publishSelectedFixture(c, "policy-fixture", 0, 1, 1);
            com.arte.ainew.application.support.AdmissionException error = assertThrows(com.arte.ainew.application.support.AdmissionException.class,
                    () -> f.accept(c, version, 128));
            assertEquals(com.arte.core.enums.ResultCodeEnum.AI_EXECUTION_LIMIT_EXCEEDED, error.getResultCode());
            assertEquals(1, checked.get());
            assertEquals(0, f.f.count("arte_ai_invocation"));
            assertEquals(0, f.f.count("arte_ai_turn"));
        });
    }

    @Test
    void oneCreateEndpointFixesPersonalAndTenantPresetsAndRejectsForgedOwnership() throws Exception {
        TenantModelSharingIntegrationTest tenant = new TenantModelSharingIntegrationTest();
        tenant.setup();
        try {
            tenant.context().run(c -> {
                assertNull(c.getStartupFailure());
                com.arte.core.pojo.platform.Scope scope = tenant.f.f.scope;
                ModelResourceService models = c.getBean(ModelResourceService.class);
                ModelResource value = models.create(tenant.actor(), new ModelResourceCommands.Create(tenant.meta("tenant-preset"), scope,
                        "Tenant", ModelResourceCommands.ModelKind.TENANT)).value();
                assertNull(value.ownership().workspaceId());
                assertEquals(scope, value.managementScope());
                assertEquals("TENANT", c.getBean(ModelDraftService.class).get(tenant.actor(), scope, value.resource()).credential().kind());
                assertThrows(RuntimeException.class, () -> models.create(tenant.actor(), new ModelResourceCommands.Create(tenant.meta("tenant-preset"), scope,
                        "Tenant", ModelResourceCommands.ModelKind.PERSONAL)));
                PlatformAuthenticatedExecutor executor = new PlatformAuthenticatedExecutor(c.getBean(RbacIdentityProvider.class),
                        new PlatformPersistenceExecutor(c.getBean("newAiPersistenceScheduler", Scheduler.class), tenant.f.f.f.f.tx));
                try (ManagementHttpHarness http = new ManagementHttpHarness(new com.arte.app.web.controller.platform.ModelResourceController(executor,
                        models, c.getBean(com.arte.ainew.api.model.ModelAclMigrationService.class)))) {
                    org.springframework.security.core.Authentication auth = ManagementHttpHarness.login(tenant.f.f.f.f.jdbc, 1, "alice");
                    String base = "{\"scope\":" + tenant.f.f.f.f.json.write(scope) + ",\"name\":\"HTTP personal\"";
                    http.rejected("/ai-new/model-resource/createModelResource", base + "}", "missing-kind", auth, 400, "INVALID_ARGUMENT");
                    http.rejected("/ai-new/model-resource/createModelResource", base + ",\"modelKind\":\"PERSONAL\",\"ownershipType\":\"TENANT\"}", "forged-owner", auth, 400, "INVALID_ARGUMENT");
                    cn.hutool.json.JSONObject resource = http.ok("/ai-new/model-resource/createModelResource", base + ",\"modelKind\":\"PERSONAL\"}", "http-create-m2", auth)
                            .getJSONObject("data").getJSONObject("value");
                    assertEquals("WORKSPACE", resource.getStr("ownershipType"));
                    assertEquals("PERSONAL", resource.getStr("credentialKind"));
                    assertTrue(resource.getJSONArray("allowedActions").contains("model:edit"));
                }
            });
        } finally {
            tenant.cleanup();
        }
    }

}
