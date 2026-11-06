package com.arte.app.platform.persistence;

import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.model.DefaultModelSharingService;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.ExecutionCommands;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.model.*;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.ai.ModelSharingStartupSafety;
import com.arte.app.platform.ai.PlatformModelSharingConfiguration;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.core.api.access.ResourceAccessService;
import com.arte.core.api.workspace.WorkspacePolicyService;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.constant.platform.PlatformResourceTypes;
import com.arte.core.enums.access.ResourceOwnershipTypeEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.*;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import com.arte.core.serialize.SerializerFactory;
import com.arte.core.spi.access.ResourceGrantStore;
import com.arte.core.spi.platform.AuditSink;
import com.arte.core.spi.workspace.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.arte.core.constant.platform.PlatformActions.MODEL_READ;
import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;
import static org.junit.jupiter.api.Assertions.*;

class TenantModelSharingIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"SCOPE", "ACL", "CONSUMER", "MODEL", "TARGET_POLICY"})
    void everyCurrentBoundaryRejectsRevocationAfterEarlierDispatchPermit(String change) {
        context().run(c -> {
            prepare(c);
            grant(c);
            scopes(c, 0, Set.of(target.workspaceId()));
            consumers(c, 1, Set.of(bob.principal().subjectId()));
            Invocation invocation = acceptBob(c);
            AiAdmissionAuthorizationGuard guard = c.getBean(AiAdmissionAuthorizationGuard.class);
            AiAdmissionAuthorizationGuard.Permit permit = guard.authorize(f.check(c, invocation, version, AiAdmissionAuthorizationGuard.Boundary.DISPATCH)).block(Duration.ofSeconds(10));
            switch (change) {
                case "SCOPE" -> scopes(c, 2, Set.of());
                case "CONSUMER" -> consumers(c, 2, Set.of());
                case "MODEL" ->
                        c.getBean(ModelResourceService.class).disable(actor(), new ModelResourceCommands.Disable(meta("disable-tenant"), f.f.scope, f.model.resource(), 1));
                case "ACL" -> {
                    ResourceAccessService resources = c.getBean(DefaultWorkspaceServices.class).resources();
                    ResourceAccessViews.GrantView grant = resources.listGrants(actor(), target, f.model.resource(), new PageQuery(1, 100)).records().stream()
                            .filter(value -> value.action().equals(MODEL_USE) && value.recipient() instanceof GrantRecipient.Subject subject && subject.subjectId().equals(bob.principal().subjectId())).findFirst().orElseThrow();
                    long acl = resources.getAccess(actor(), target, f.model.resource()).aclVersion();
                    resources.revoke(actor(), new ResourceAccessCommands.RevokeGrant(meta("revoke-tenant"), target, f.model.resource(), grant.id(), acl, grant.version()));
                }
                case "TARGET_POLICY" -> {
                    WorkspacePolicyService policies = c.getBean(WorkspacePolicyService.class);
                    WorkspacePolicyViews.WorkspacePolicyView old = policies.getWorkspacePolicyView(actor(), target);
                    PolicyRules rules = old.policy().rules();
                    policies.updateWorkspacePolicy(actor(), new WorkspaceCommands.UpdateWorkspacePolicy(meta("target-policy"), target, old.workspaceVersion(), old.policy().version(),
                            new PolicyRules(rules.allowedCreationTypes(), rules.extraCreationRoles(), rules.guestAiAllowed(), false, rules.allowedProtocols(), rules.allowedProviders(), rules.allowedDestinations(), rules.exportTypes(), rules.egressTypes(), rules.shareTargetKinds())));
                }
                default -> throw new IllegalArgumentException("Unknown test change");
            }
            assertThrows(RuntimeException.class, () -> f.f.f.f.tx.required(() -> {
                permit.requireInCurrentTransaction(invocation.request());
                return null;
            }));
            assertThrows(RuntimeException.class, () -> guard.authorize(f.check(c, invocation, version, AiAdmissionAuthorizationGuard.Boundary.EXTERNAL_SEND)).block(Duration.ofSeconds(10)));
            assertThrows(RuntimeException.class, () -> acceptBob(c));
            assertEquals(0, f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
            assertEquals(0, f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
            assertEquals(1, f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
        });
    }

    final PersonalModelPublicationIntegrationTest f = new PersonalModelPublicationIntegrationTest();
    Scope target;
    TrustedActor bob;
    ModelResourceVersion version;

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        f.f.f.f.jdbc.update("UPDATE arte_platform_tenant SET type=1,personal_subject_id=NULL,name='Model team' WHERE id=?", f.f.scope.tenantId());
        String sql = Files.readString(Path.of("scripts/arte-ai-model-sharing-ddl-mysql.sql"));
        if (f.f.f.f.mysql == null) {
            sql = sql.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "").replaceAll("(?i)ENGINE=InnoDB", "");
        }
        try (Connection connection = f.f.f.f.source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    @Test
    void legacySpaceMetadataAndCredentialReceiptsRetainTheirOriginalMeaning() {
        ModelDrafts.CredentialView legacyView = f.f.f.f.json.read(
                "{\"resourceId\":\"legacy\",\"state\":\"CONFIGURED\",\"version\":1,\"mayMaintain\":true}", ModelDrafts.CredentialView.class);
        assertEquals("PERSONAL", legacyView.kind());
        ModelResource original = new ModelResource(new ResourceRef(f.f.scope.tenantId(), PlatformResourceTypes.MODEL, "legacy"),
                new ResourceOwnership(ResourceOwnershipTypeEnum.WORKSPACE, f.f.scope.tenantId(), f.f.scope.workspaceId()),
                "Legacy", actor().principal().subjectId(), ModelResource.State.DRAFT, null, 0, Instant.now());
        ObjectNode node = (ObjectNode) SerializerFactory.buildJsonMapperWithoutTypeProperty().readTree(f.f.f.f.json.write(original));
        node.remove("managementScope");
        assertEquals(original, f.f.f.f.json.read(node.toString(), ModelResource.class));
    }

    @Test
    void tenantModelExecutionUsesTargetSpacePolicyInsteadOfManagementSpacePolicy() {
        context().run(c -> {
            prepare(c);
            grant(c);
            scopes(c, 0, Set.of(target.workspaceId()));
            consumers(c, 1, Set.of(bob.principal().subjectId()));
            WorkspacePolicyService policies = c.getBean(WorkspacePolicyService.class);
            WorkspacePolicyViews.WorkspacePolicyView home = policies.getWorkspacePolicyView(actor(), f.f.scope);
            PolicyRules rules = home.policy().rules();
            policies.updateWorkspacePolicy(actor(), new WorkspaceCommands.UpdateWorkspacePolicy(meta("management-policy"), f.f.scope, home.workspaceVersion(), home.policy().version(),
                    new PolicyRules(rules.allowedCreationTypes(), rules.extraCreationRoles(), rules.guestAiAllowed(), false, rules.allowedProtocols(), rules.allowedProviders(), rules.allowedDestinations(), rules.exportTypes(), rules.egressTypes(), rules.shareTargetKinds())));
            assertTrue(status(c).valid(), status(c).reason());
        });
    }

    @Test
    void auditFailureRollsBackPolicyIndexTenantEpochAndCommandReceiptTogether() {
        context().run(c -> {
            prepare(c);
            DefaultModelSharingService service = c.getBean(DefaultModelSharingService.class);
            long epoch = f.f.f.f.jdbc.queryForObject("SELECT auth_epoch FROM arte_platform_tenant WHERE id=?", Long.class, f.f.scope.tenantId());
            Object effects = ReflectionTestUtils.getField(service, "effects");
            ReflectionTestUtils.setField(effects, "audit", (AuditSink) entry -> {
                throw new IllegalStateException("audit unavailable");
            });
            assertThrows(IllegalStateException.class, () -> scopes(c, 0, Set.of(target.workspaceId())));
            assertEquals(0, service.get(actor(), f.f.scope, f.model.resource()).version());
            assertEquals(Set.of(f.f.scope.workspaceId()), service.get(actor(), f.f.scope, f.model.resource()).workspaceIds());
            assertEquals(epoch, f.f.f.f.jdbc.queryForObject("SELECT auth_epoch FROM arte_platform_tenant WHERE id=?", Long.class, f.f.scope.tenantId()));
        });
    }

    @Test
    void tenantModelCannotAdoptLegacyBindingOutsideTheProtectedPublicationNamespace() {
        context().run(c -> {
            prepare(c);
            ModelResourceService models = c.getBean(ModelResourceService.class);
            ModelResource draft = models.create(actor(), new ModelResourceCommands.Create(meta("tenant-adoption-draft"), f.f.scope, "No legacy adoption", ModelResourceCommands.ModelKind.TENANT)).value();
            assertThrows(PlatformException.class, () -> models.associate(actor(), new ModelResourceCommands.Associate(
                    meta("tenant-adoption"), f.f.scope, draft.resource(), 0, f.f.f.ref("binding", "text"), ModelPublication.Source.DATABASE, 1L)));
            assertEquals(ModelResource.State.DRAFT, models.get(actor(), f.f.scope, draft.resource()).state());
        });
    }

    ApplicationContextRunner context() {
        return f.context().withUserConfiguration(PlatformModelSharingConfiguration.class, ModelSharingStartupSafety.class)
                .withPropertyValues("arte.ai-new-model-sharing.enabled=true");
    }

    TrustedActor actor() {
        return f.f.f.f.actor;
    }

    CommandMeta meta(String key) {
        return f.f.f.meta(key);
    }

    void prepare(ApplicationContext c) {
        prepare(c, "api.deepseek.com", false);
    }

    void prepare(ApplicationContext c, String host, boolean network) {
        DatabaseAiControlService db = c.getBean(DatabaseAiControlService.class);
        DatabaseAiTestCatalog.publish(db, c, actor(), meta("catalog"));
        DatabaseAiTestCatalog.prepareWithAccounts(c, actor(), f.f.scope);
        f.policy(c, host, true);
        target = c.getBean(DefaultWorkspaceServices.class).workspaces().create(actor(), new WorkspaceCommands.CreateWorkspace(meta("target-space"), f.f.scope.tenantId(), "Target", "")).value().scope();
        DatabaseAiTestCatalog.prepareWithAccounts(c, actor(), target);
        f.model = c.getBean(ModelResourceService.class).create(actor(), new ModelResourceCommands.Create(meta("tenant-model"), f.f.scope, "Tenant model", ModelResourceCommands.ModelKind.TENANT)).value();
        assertNull(f.model.ownership().workspaceId());
        assertEquals(f.f.scope, f.model.managementScope());
        f.save(c, 0, "deepseek-chat");
        c.getBean(ModelDraftService.class).replaceCredential(actor(), meta("tenant-key"), f.f.scope, f.model.resource(), 0, PersonalModelPublicationIntegrationTest.TOKEN, ModelDrafts.CredentialKind.TENANT);
        f.validate(c, 1, 1, network);
        version = f.publishSelectedFixture(c, "publish-tenant", 0, 1, 1);
        f.f.f.f.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-30 10:00:00"));
        RbacIdentityProvider identity = c.getBean(RbacIdentityProvider.class);
        bob = new TrustedActor(identity.resolveAuthenticatedName("bob").orElseThrow(), Instant.now());
        f.f.f.f.tx.required(() -> {
            WorkspaceGovernanceLocks locks = new WorkspaceGovernanceLocks(f.f.f.f.tx, c.getBean(TenantStore.class), c.getBean(WorkspaceStore.class),
                    c.getBean(TenantMembershipStore.class), c.getBean(WorkspaceMembershipStore.class), c.getBean(PreferenceStore.class));
            locks.tenant(bob.principal().subjectId(), target.tenantId());
            assertTrue(c.getBean(TenantMembershipStore.class).insert(new TenantMembership("tenant-bob", target.tenantId(), bob.principal().subjectId(),
                    TenantRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
            assertTrue(c.getBean(WorkspaceMembershipStore.class).insert(new WorkspaceMembership("space-bob", target, bob.principal().subjectId(),
                    WorkspaceRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
            return null;
        });
        assertTrue(c.getBean(WorkspaceMembershipStore.class).find(f.f.scope, bob.principal().subjectId()).isEmpty());
        DatabaseAiTestCatalog.prepareWithAccounts(c, bob, target);

    }

    void grant(ApplicationContext c) {
        long acl = c.getBean(ResourceGrantStore.class).findAccess(f.model.resource()).orElseThrow().aclVersion();
        c.getBean(DefaultWorkspaceServices.class).resources().grant(actor(), new ResourceAccessCommands.GrantResource(meta("grant-bob"), f.f.scope,
                f.model.resource(), acl, new GrantRecipient.Subject(bob.principal().subjectId()), Set.of(MODEL_READ, MODEL_USE), null));
    }

    void scopes(ApplicationContext c, long expected, Set<String> workspaces) {
        c.getBean(DefaultModelSharingService.class).scopes(actor(), meta("scopes-" + expected), f.f.scope, f.model.resource(), expected, workspaces);
        if (workspaces.contains(target.workspaceId())) {
            DatabaseAiTestCatalog.select(c, actor(), target, f.model.resource().resourceId(), version.publication().binding().definition());
        }
    }

    void consumers(ApplicationContext c, long expected, Set<String> subjects) {
        c.getBean(DefaultModelSharingService.class).consumers(actor(), meta("consumers-" + expected), f.f.scope, f.model.resource(), expected, 1, subjects);
    }

    ModelPublications.Status status(ApplicationContext c) {
        return f.f.f.f.tx.required(() -> {
            com.arte.ainew.application.model.DefaultModelPublicationService publications = c.getBean(com.arte.ainew.application.model.DefaultModelPublicationService.class);
            publications.lock(bob, target);
            com.arte.ainew.pojo.model.ModelPublications.Release release = c.getBean(com.arte.ainew.spi.model.ModelPublicationStore.class)
                    .find(target, version.publication().binding().definition(), true).orElseThrow();
            String reason = publications.readiness(bob, release, target);
            return new ModelPublications.Status(release.binding(), reason == null, reason == null ? "READY" : reason, release.maxOutputTokens());
        });
    }

    Invocation acceptBob(ApplicationContext c) {
        ExecutionAuthorization auth = c.getBean(DatabaseAiControlService.class).resolve("bob", target.tenantId(), target.workspaceId(),
                Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ, AdmissionAuthorization.CONVERSATION)).block(Duration.ofSeconds(10));
        ExecutionContext initial = new ExecutionContext(UUID.randomUUID().toString(), "tenant-bob", auth, Instant.now().plusSeconds(60), null, null, "release-v1", UUID.randomUUID().toString());
        String budget = c.getBean(BudgetDefinitionProvider.class).available(initial, BudgetDefinitionProvider.Operation.READ).block(Duration.ofSeconds(10)).getFirst().budgetRef();
        ExecutionContext context = new ExecutionContext(initial.executionId(), initial.traceId(), auth, initial.deadline(), null, budget, initial.releaseRef(), initial.idempotencyKey());
        String conversation = c.getBean(ConversationService.class).create("Tenant shared chat", null, List.of(), context).block(Duration.ofSeconds(10)).conversationId();
        ContextRequest input = new ContextRequest(List.of(new ChatMessage("user", ChatMessage.Role.USER,
                List.of(new ChatMessage.Text("hello")), List.of(), null)), null, List.of(), List.of(), null, new ContextBudget(8192, 4096, 512, 0));
        EntryRequests.Chat request = new EntryRequests.Chat(conversation, 0, null, null, input,
                version.publication().binding().capability().definition(), version.publication().binding().definition(),
                new GenerationOptions(128, null, null, List.of()),
                new ExecutionOptions(context.deadline(), 1, 4096, 0, 0, Duration.ofSeconds(60)));
        AcceptedExecution accepted = c.getBean(ChatService.class).submit(request, context).block(Duration.ofSeconds(10));
        assert accepted != null;
        return c.getBean(MybatisExecutionPersistence.class).find(ExecutionOwners.from(context), accepted.executionId()).block(Duration.ofSeconds(10));
    }

    @Test
    void tenantModelRequiresSeparateAclScopeAndCredentialUseAndDoesNotRequireSourceMembership() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            prepare(c);
            grant(c);
            assertThrows(RuntimeException.class, () -> status(c));
            scopes(c, 0, Set.of(f.f.scope.workspaceId(), target.workspaceId()));
            assertEquals("CREDENTIAL_USE_DENIED", status(c).reason());
            consumers(c, 1, Set.of(bob.principal().subjectId()));
            assertTrue(status(c).valid(), status(c).reason());
            ExecutionAuthorization auth = c.getBean(DatabaseAiControlService.class).resolve("bob", target.tenantId(), target.workspaceId(), Set.of(AdmissionAuthorization.INVOKE)).block(Duration.ofSeconds(10));
            ExecutionContext context = new ExecutionContext(UUID.randomUUID().toString(), "tenant-model", auth, Instant.now().plusSeconds(60), null, null, "release-v1", "test");
            assertEquals(version.publication().binding(), c.getBean(AiResourceEntitlementService.class).resolve(version.publication().binding().definition(),
                    version.publication().binding().capability().definition(), context).block(Duration.ofSeconds(10)).binding());
            consumers(c, 2, Set.of());
            assertEquals("CREDENTIAL_USE_DENIED", status(c).reason());
        });
    }

    @Test
    void rangeRemovalAndCredentialRotationCannotBeRevivedByOldPolicyOrReceiptReplay() {
        context().run(c -> {
            prepare(c);
            grant(c);
            scopes(c, 0, Set.of(target.workspaceId()));
            consumers(c, 1, Set.of(bob.principal().subjectId()));
            assertTrue(status(c).valid());
            c.getBean(ModelDraftService.class).replaceCredential(actor(), meta("rotate"), f.f.scope, f.model.resource(), 1, "replacement-test-key", ModelDrafts.CredentialKind.TENANT);
            assertEquals("CREDENTIAL_STALE", status(c).reason());
            assertEquals(2, c.getBean(DefaultModelSharingService.class).consumers(actor(), meta("consumers-1"), f.f.scope, f.model.resource(), 1, 1, Set.of(bob.principal().subjectId())).value().version());
            assertEquals("CREDENTIAL_STALE", status(c).reason());
            scopes(c, 2, Set.of());
            assertThrows(RuntimeException.class, () -> status(c));
        });
    }

    @Test
    void sharingRejectsForgedScopesStaleCasNonResponsibleConsumerChangesAndTenantCreateByMember() {
        context().run(c -> {
            prepare(c);
            grant(c);
            assertThrows(RuntimeException.class, () -> scopes(c, 0, Set.of("missing-space")));
            assertEquals(0, c.getBean(DefaultModelSharingService.class).get(actor(), f.f.scope, f.model.resource()).version());
            scopes(c, 0, Set.of(target.workspaceId()));
            assertThrows(RuntimeException.class, () -> scopes(c, 0, Set.of()));
            assertThrows(RuntimeException.class, () -> c.getBean(DefaultModelSharingService.class).consumers(bob, meta("stolen-key"), f.f.scope, f.model.resource(), 1, 1, Set.of(bob.principal().subjectId())));
            assertThrows(RuntimeException.class, () -> c.getBean(ModelResourceService.class).create(bob, new ModelResourceCommands.Create(meta("member-tenant"), target, "Denied", ModelResourceCommands.ModelKind.TENANT)));
        });
    }

    @Test
    void corruptUseIndexFailsClosedAndStoredTenantModelCannotDisableSharingOnRestart() {
        context().run(c -> {
            prepare(c);
            scopes(c, 0, Set.of(target.workspaceId()));
        });
        f.context().withUserConfiguration(ModelSharingStartupSafety.class).run(c -> assertNotNull(c.getStartupFailure()));
        context().run(c -> {
            assertNull(c.getStartupFailure());
            f.f.f.f.jdbc.update("DELETE FROM arte_ai_model_use_scope WHERE workspace_id=?", target.workspaceId());
            assertThrows(RuntimeException.class, () -> c.getBean(DefaultModelSharingService.class).get(actor(), f.f.scope, f.model.resource()));
        });
    }

    @Test
    void credentialRevocationAfterPreflightBlocksNewAdmissionDispatchAndExternalSend() {
        context().run(c -> {
            prepare(c);
            grant(c);
            scopes(c, 0, Set.of(target.workspaceId()));
            consumers(c, 1, Set.of(bob.principal().subjectId()));
            Invocation invocation = acceptBob(c);
            assertEquals(new ExecutionOwner(target.tenantId(), target.workspaceId(), bob.principal().subjectId()), ExecutionOwners.from(invocation.request().context()));
            AiAdmissionAuthorizationGuard guard = c.getBean(AiAdmissionAuthorizationGuard.class);
            AiAdmissionAuthorizationGuard.Permit permit = guard.authorize(f.check(c, invocation, version, AiAdmissionAuthorizationGuard.Boundary.DISPATCH)).block(Duration.ofSeconds(10));
            assertNotNull(guard.authorize(f.check(c, invocation, version, AiAdmissionAuthorizationGuard.Boundary.EXTERNAL_SEND)).block(Duration.ofSeconds(10)).connection());
            MybatisExecutionPersistence executions = c.getBean(MybatisExecutionPersistence.class);
            OutboxMessage message = executions.claim(OutboxMessage.Kind.DISPATCH, "tenant-worker", Duration.ofSeconds(60), 1).block(Duration.ofSeconds(10)).getFirst();
            consumers(c, 2, Set.of());
            DispatchPreparationStore.Command command = new DispatchPreparationStore.Command(
                    new ExecutionCommands.Version(message.owner(), message.invocationId(), invocation.version()), message, "attempt", Duration.ofSeconds(30), f.f.f.money("1"), f.f.f.ref("rate", "text-price"), Duration.ofMinutes(5));
            assertThrows(RuntimeException.class, () -> executions.prepareDispatch(command, () -> permit.requireInCurrentTransaction(invocation.request())).block(Duration.ofSeconds(10)));
            assertThrows(RuntimeException.class, () -> guard.authorize(f.check(c, invocation, version, AiAdmissionAuthorizationGuard.Boundary.EXTERNAL_SEND)).block(Duration.ofSeconds(10)));
            assertThrows(RuntimeException.class, () -> acceptBob(c));
            assertEquals(0, f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class));
            assertEquals(0, f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
            assertEquals(1, f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class));
        });
    }

    @Test
    void rejoiningTenantDoesNotReviveOldCredentialUseEvenAfterExplicitFreshModelAcl() {
        context().run(c -> {
            prepare(c);
            grant(c);
            scopes(c, 0, Set.of(target.workspaceId()));
            consumers(c, 1, Set.of(bob.principal().subjectId()));
            assertTrue(status(c).valid());
            f.f.f.f.jdbc.update("UPDATE arte_platform_tenant_member SET generation=generation+1,version=version+1 WHERE subject_id=? AND tenant_id=?", bob.principal().subjectId(), target.tenantId());
            long acl = c.getBean(com.arte.core.spi.access.ResourceGrantStore.class).findAccess(f.model.resource()).orElseThrow().aclVersion();
            c.getBean(DefaultWorkspaceServices.class).resources().grant(actor(), new ResourceAccessCommands.GrantResource(meta("rejoined-model-acl"), target, f.model.resource(), acl,
                    new GrantRecipient.Subject(bob.principal().subjectId()), Set.of(MODEL_READ, MODEL_USE), null));
            assertEquals("CREDENTIAL_USE_DENIED", status(c).reason());
            consumers(c, 2, Set.of(bob.principal().subjectId()));
            assertTrue(status(c).valid());
        });
    }

    @Test
    void realCrossSpaceChatUsesExplicitTenantCredentialAndTargetsOwnBudget() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        reactor.netty.DisposableServer server = reactor.netty.http.server.HttpServer.create().host("127.0.0.1").port(0).route(routes -> routes
                .get("/v1/models", (request, response) -> {
                    assertEquals("Bearer " + PersonalModelPublicationIntegrationTest.TOKEN, request.requestHeaders().get("Authorization"));
                    return response.header("Content-Type", "application/json").sendString(reactor.core.publisher.Mono.just("{\"data\":[{\"id\":\"deepseek-chat\"}]}"));
                }).post("/v1/chat/completions", (request, response) -> {
                    assertEquals("Bearer " + PersonalModelPublicationIntegrationTest.TOKEN, request.requestHeaders().get("Authorization"));
                    calls.incrementAndGet();
                    return response.header("Content-Type", "text/event-stream").sendString(reactor.core.publisher.Mono.just(
                            "data: {\"id\":\"tenant-shared\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"shared\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\ndata: [DONE]\n\n"));
                })).bindNow();
        try {
            String origin = "http://127.0.0.1:" + server.port();
            f.f.f.allowOrigins(origin);
            ApplicationContextRunner runner = f.realGenerationContext().withUserConfiguration(PlatformModelSharingConfiguration.class, ModelSharingStartupSafety.class)
                    .withPropertyValues("arte.ai-new-model-sharing.enabled=true", "arte.ai-new.connections[0].endpoint=" + origin + "/v1");
            runner.run(c -> {
                assertNull(c.getStartupFailure());
                prepare(c, "127.0.0.1", true);
                grant(c);
                scopes(c, 0, Set.of(target.workspaceId()));
                consumers(c, 1, Set.of(bob.principal().subjectId()));
                // 本例从无账户开始，经正式本人准备接口再进入真实跨空间生成与结算。
                com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec codec = new com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec();
                f.f.f.f.jdbc.query("SELECT id_key,workspace_snapshot FROM arte_ai_account", (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    com.arte.ainew.pojo.budget.BudgetCommands.Account account = codec.decode(row.getString(2), com.arte.ainew.pojo.budget.BudgetCommands.Account.class);
                    if (account.owner().subjectId().equals(bob.principal().subjectId()) && account.owner().workspaceId().equals(target.workspaceId())) {
                        f.f.f.f.jdbc.update("DELETE FROM arte_ai_account WHERE id_key=?", row.getString(1));
                    }
                });
                f.f.f.f.jdbc.update("DELETE FROM arte_ai_budget_definition WHERE tenant_id=? AND workspace_id=? AND subject_id=?", target.tenantId(), target.workspaceId(), bob.principal().subjectId());
                com.arte.ainew.api.model.ModelUsagePreparationService preparation = c.getBean(com.arte.ainew.api.model.ModelUsagePreparationService.class);
                assertEquals(com.arte.ainew.pojo.model.ModelUsagePreparation.State.PREPARABLE,
                        preparation.status(bob, target, f.model.resource(), version.publication().binding().definition()).state());
                assertEquals(com.arte.ainew.pojo.model.ModelUsagePreparation.State.READY,
                        preparation.prepare(bob, meta("real-target-own-budget"), target, f.model.resource(), version.publication().binding().definition()).value().state());
                com.arte.ainew.pojo.execution.Invocation accepted = acceptBob(c);
                com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence executions = c.getBean(com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence.class);
                com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence payloads = c.getBean(com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence.class);
                com.arte.ainew.application.execution.GenerationDispatcher dispatcher = new com.arte.ainew.application.execution.GenerationDispatcher(
                        c.getBean(com.arte.ainew.application.auth.AdmissionAuthorization.class), c.getBean(com.arte.ainew.api.control.CapabilityCatalog.class), c.getBean(com.arte.ainew.api.control.BindingManager.class),
                        c.getBean(com.arte.ainew.spi.control.PublishedAiCatalog.class), c.getBean(com.arte.ainew.spi.control.AiExecutionPolicyProvider.class), c.getBean(com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard.class),
                        executions, executions, executions, payloads, payloads, executions, c.getBean(com.arte.ainew.spi.gateway.ModelGateway.class), c.getBean(com.arte.ainew.config.NewAiProperties.class),
                        new com.arte.ainew.config.NewAiExecutionProperties(true, false, 0, null, null, null, null), Clock.systemUTC(), null);
                com.arte.ainew.pojo.execution.OutboxMessage message = executions.claim(com.arte.ainew.pojo.execution.OutboxMessage.Kind.DISPATCH, "tenant-real-worker", Duration.ofSeconds(60), 1).block(Duration.ofSeconds(20)).getFirst();
                dispatcher.dispatch(message, ExecutionRuntimeContext.start(accepted.request().context())).block(Duration.ofSeconds(20));
                com.arte.ainew.pojo.execution.Invocation complete = executions.find(message.owner(), message.invocationId()).block(Duration.ofSeconds(20));
                assertEquals(com.arte.ainew.pojo.execution.Invocation.State.SUCCEEDED, complete.state(), String.valueOf(complete.error()));
                assertEquals(new ExecutionOwner(target.tenantId(), target.workspaceId(), bob.principal().subjectId()), message.owner());
                assertEquals(1, calls.get());
                assertEquals(1L, f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_settlement WHERE evidence_kind='PROVIDER_BILL'", Long.class));
                assertTrue(f.f.f.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_settlement", String.class).contains("SETTLED"));
                assertEquals(0, f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_workspace_member WHERE workspace_id=? AND subject_id=?", Integer.class, f.f.scope.workspaceId(), bob.principal().subjectId()));
            });
            runner.run(c -> {
                assertNull(c.getStartupFailure());
                assertTrue(status(c).valid());
                assertEquals(1, calls.get());
            });
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void strictHttpPreservesCurrentAuthorizationAndDoesNotReturnSecretsOrMemberGenerations() {
        context().run(c -> {
            prepare(c);
            reactor.core.scheduler.Scheduler scheduler = reactor.core.scheduler.Schedulers.newBoundedElastic(2, 32, "tenant-sharing-http");
            com.arte.app.platform.identity.PlatformAuthenticatedExecutor executor = new com.arte.app.platform.identity.PlatformAuthenticatedExecutor(
                    c.getBean(com.arte.app.platform.identity.RbacIdentityProvider.class), new PlatformPersistenceExecutor(scheduler, f.f.f.f.tx));
            try (ManagementHttpHarness http = new ManagementHttpHarness(new com.arte.app.web.controller.platform.ModelSharingController(executor, c.getBean(DefaultModelSharingService.class)))) {
                org.springframework.security.core.Authentication auth = ManagementHttpHarness.login(f.f.f.f.jdbc, 1, "alice");
                Map<String, Object> request = Map.of("scope", f.f.scope, "resourceId", f.model.resource().resourceId(), "expectedVersion", 0, "workspaceIds", List.of(target.workspaceId()));
                String json = f.f.f.f.json.write(request);
                assertEquals(400, http.execute("/ai-new/model-sharing/updateModelUseScopes", json, null, auth).getResponse().getStatus());
                assertEquals(400, http.execute("/ai-new/model-sharing/updateModelUseScopes", json.replace("\"expectedVersion\"", "\"actor\":\"forged\",\"expectedVersion\""), "forged", auth).getResponse().getStatus());
                cn.hutool.json.JSONObject result = http.ok("/ai-new/model-sharing/updateModelUseScopes", json, "http-scope", auth);
                assertEquals(1, result.getJSONObject("data").getLong("resultVersion"));
                String policy = http.ok("/ai-new/model-sharing/getModelSharing", f.f.f.f.json.write(Map.of("scope", f.f.scope, "resourceId", f.model.resource().resourceId())), null, auth).toString();
                assertFalse(policy.contains("ciphertext"));
                assertFalse(policy.contains("generation"));
                assertFalse(policy.contains(PersonalModelPublicationIntegrationTest.TOKEN));
                assertEquals(200, http.execute("/ai-new/model-sharing/listTenantModelMembers", f.f.f.f.json.write(Map.of("scope", f.f.scope)), null, auth).getResponse().getStatus());
                assertEquals(404, http.execute("/ai-new/model-sharing/getModelSharing", f.f.f.f.json.write(Map.of("scope", target, "resourceId", f.model.resource().resourceId())), null, auth).getResponse().getStatus());
            } finally {
                scheduler.dispose();
            }
        });
    }
}
