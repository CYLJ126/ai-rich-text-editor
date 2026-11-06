package com.arte.app.platform.persistence;

import com.arte.ainew.api.control.AssistantManager;
import com.arte.ainew.application.assistant.DefaultAssistantManager;
import com.arte.ainew.pojo.assistant.Assistants.Content;
import com.arte.ainew.pojo.assistant.Assistants.State;
import com.arte.ainew.spi.assistant.AssistantTrialVerifier;
import com.arte.app.platform.assistant.AssistantAccessAdapter;
import com.arte.app.platform.assistant.PlatformAssistantConfiguration;
import com.arte.app.platform.identity.*;
import com.arte.app.platform.workspace.PlatformWorkspaceConfiguration;
import com.arte.app.web.controller.platform.AssistantManagementController;
import com.arte.core.application.workspace.DefaultWorkspacePolicies;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.TenantMembership;
import com.arte.core.pojo.workspace.WorkspaceBrowseBudget;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspaceMembership;
import com.arte.core.spi.access.ResourceGrantStore;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.arte.core.constant.platform.PlatformActions.RESOURCE_ACCESS_MANAGE;
import static com.arte.core.enums.platform.PlatformErrorCodeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistantControlIntegrationTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final Content FIRST = new Content("Outline", "Summarize", "Use the supplied article.", "Headings", List.of("Provide an outline."));
    private static final Content SECOND = new Content("Outline V2", "Summarize", "Use only the supplied article.", "Headings", List.of("Provide an outline."));
    private final PlatformPersistenceIntegrationTest fixture = new PlatformPersistenceIntegrationTest();
    private RbacIdentityProvider identity;
    private JdbcAssistantStore store;
    private DefaultWorkspaceServices platform;
    private AssistantManager assistants;
    private Scope scope;

    @BeforeEach
    void setup() throws Exception {
        fixture.setup();
        fixture.installDdl(Path.of("scripts", "arte-platform-resource-acl-ddl-mysql.sql"));
        String ddl = Files.readString(Path.of("scripts", "arte-ai-assistant-ddl-mysql.sql"));
        if (fixture.mysql == null) {
            ddl = ddl.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "")
                    .replaceAll("(?i)ENGINE=InnoDB(?: DEFAULT CHARSET=utf8mb4)?", "")
                    .replace("example_prompts JSON", "example_prompts LONGTEXT");
        }
        try (var connection = fixture.source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));
        }
        var accounts = new RbacAccountReader(fixture.source, new RbacAccountMapperConfiguration().identityUserMapper(fixture.source));
        identity = new RbacIdentityProvider(accounts, new JdbcPrincipalIdentityStore(fixture.source, accounts),
                new TransactionTemplate(new DataSourceTransactionManager(fixture.source)),
                new PlatformIdentityProperties(true, 0L, List.of()), List::of, CLOCK);
        var stores = new PlatformServiceTestFactory(fixture.source, fixture.tx, fixture.json, null);
        store = new JdbcAssistantStore(fixture.source, fixture.tx, fixture.json);
        var acl = new AssistantAccessAdapter(store);
        platform = new DefaultWorkspaceServices(identity, fixture.tx, fixture.tenants, fixture.workspaces,
                fixture.tenantMembers, fixture.workspaceMembers, fixture.policies, fixture.preferences, fixture.receipts,
                fixture.outbox, fixture.audit, AssistantControlIntegrationTest::id, CLOCK,
                new DefaultWorkspacePolicies(stores.templates()), fixture.facts, List.of(), WorkspaceBrowseBudget.defaults(),
                System::nanoTime, stores.grants(), List.of(acl), List.of(acl));
        scope = platform.personal().initialize(fixture.actor, meta("init")).value().workspace().scope();
        assistants = manager(null, fixture.audit);
    }

    @AfterEach
    void cleanup() {
        fixture.cleanup();
    }

    @Test
    void privateDraftSupportsCopyCursorAndOriginalReceipt() {
        var created = assistants.create(fixture.actor, scope, FIRST, meta("create"));
        String original = created.value().assistantId();
        var replay = assistants.create(fixture.actor, scope, FIRST, meta("create"));
        assertEquals(created.operationId(), replay.operationId());
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        rejected(IDEMPOTENCY_CONFLICT, () -> assistants.create(fixture.actor, scope, SECOND, meta("create")));
        var copy = assistants.copy(fixture.actor, scope, original, null, 1L, meta("copy")).value();
        assertNotEquals(original, copy.assistantId());
        assertEquals(FIRST, assistants.get(fixture.actor, scope, copy.assistantId()).draft().content());

        var page = assistants.managed(fixture.actor, scope, null, "outline", 1);
        assertEquals(1, page.items().size());
        assertNotNull(page.nextCursor());
        var next = assistants.managed(fixture.actor, scope, page.nextCursor(), "outline", 1);
        assertEquals(1, next.items().size());
        assertNotEquals(page.items().getFirst(), next.items().getFirst());
        assertNull(next.nextCursor());
        assertTrue(assistants.usable(fixture.actor, scope, null, null, 10).items().isEmpty());

        var otherScope = platform.workspaces().create(fixture.actor,
                new WorkspaceCommands.CreateWorkspace(meta("other"), scope.tenantId(), "Other", "")).value().scope();
        rejected(NOT_FOUND, () -> assistants.get(fixture.actor, otherScope, original));
        assertTrue(assistants.managed(fixture.actor, otherScope, null, null, 10).items().isEmpty());
        var resource = assistants.get(fixture.actor, scope, original).resource();
        assertFalse(platform.authorization().check(fixture.actor,
                new AccessRequest(scope, resource.ref(), RESOURCE_ACCESS_MANAGE, "test")).allowed());
    }

    @Test
    void concurrentDraftWritesProduceOneVersionAndReplayWorksAfterAdvance() throws Exception {
        String assistant = assistants.create(fixture.actor, scope, FIRST, meta("create")).value().assistantId();
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var left = workers.submit(update(assistant, "left", barrier));
            var right = workers.submit(update(assistant, "right", barrier));
            var outcomes = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(value -> value == VERSION_CONFLICT).count());
            assertEquals(1, outcomes.stream().filter(value -> value == CommandDeliveryEnum.APPLIED).count());
            String winner = outcomes.getFirst() == CommandDeliveryEnum.APPLIED ? "left" : "right";
            assistants.saveDraft(fixture.actor, scope, assistant, 2, FIRST, meta("next"));
            var replay = assistants.saveDraft(fixture.actor, scope, assistant, 1, SECOND, meta(winner));
            assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
            assertEquals(2, replay.value().draftRevision());
            assertEquals(3, assistants.get(fixture.actor, scope, assistant).draft().revision());
        }
    }

    @Test
    void publicationRequiresTrustedTrialAndKeepsOldVersionsAndReceipts() {
        var draft = assistants.create(fixture.actor, scope, FIRST, meta("create")).value();
        String assistant = draft.assistantId();
        String trial = insertTrial(assistant, 1, FIRST);
        rejected(CAPABILITY_NOT_ENABLED, () -> assistants.publish(fixture.actor, scope, assistant, 0, 1, trial, meta("publish")));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_assistant_release", Integer.class));

        var verified = manager((range, proof) -> {
            fixture.tx.requireWritable();
            assertEquals(scope, range);
            assertEquals(fixture.actor.principal().subjectId(), proof.actorId());
        }, fixture.audit);
        var v1 = verified.publish(fixture.actor, scope, assistant, 0, 1, trial, meta("publish"));
        assertEquals("1", v1.value().chatProfile().version());
        assertEquals(CommandDeliveryEnum.REPLAYED,
                verified.publish(fixture.actor, scope, assistant, 0, 1, trial, meta("publish")).delivery());
        rejected(VERSION_CONFLICT, () -> verified.publish(fixture.actor, scope, assistant, 1, 1, trial, meta("repeat")));

        var next = verified.saveDraft(fixture.actor, scope, assistant, 1, SECOND, meta("v2-draft")).value();
        rejected(VERSION_CONFLICT, () -> verified.publish(fixture.actor, scope, assistant, next.resourceVersion(), 2, trial, meta("old-trial")));
        String nextTrial = insertTrial(assistant, 2, SECOND);
        var v2 = verified.publish(fixture.actor, scope, assistant, next.resourceVersion(), 2, nextTrial, meta("v2")).value();
        assertEquals("2", v2.chatProfile().version());
        assertEquals(FIRST, verified.resolvePublished(fixture.actor, scope, v1.value().chatProfile(), false).content());
        rejected(VERSION_CONFLICT, () -> verified.resolvePublished(fixture.actor, scope, v1.value().chatProfile(), true));
        assertEquals(v2.chatProfile(), verified.usable(fixture.actor, scope, null, null, 10).items().getFirst().chatProfile());

        verified.disable(fixture.actor, scope, assistant, v2.resourceVersion(), meta("disable"));
        assertEquals(State.DISABLED, verified.get(fixture.actor, scope, assistant).resource().state());
        assertTrue(verified.usable(fixture.actor, scope, null, null, 10).items().isEmpty());
        rejected(ACCESS_DENIED, () -> verified.resolvePublished(fixture.actor, scope, v2.chatProfile(), false));
        assertEquals(2, verified.versions(fixture.actor, scope, assistant, 0, 10).items().size());
        assertEquals(v1.operationId(), verified.publish(fixture.actor, scope, assistant, 0, 1, trial, meta("publish")).operationId());
    }

    @Test
    void auditFailureRollsBackResourceDraftAclAndReceipt() {
        var broken = manager(null, entry -> {
            throw new IllegalStateException("audit unavailable");
        });
        assertThrows(IllegalStateException.class, () -> broken.create(fixture.actor, scope, FIRST, meta("create")));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_assistant_resource", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_assistant_draft", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_resource_access", Integer.class));
        assertEquals(CommandDeliveryEnum.APPLIED, assistants.create(fixture.actor, scope, FIRST, meta("create")).delivery());
    }

    @Test
    void mvcUsesCurrentIdentityAndHidesPrivateDraftFromAnotherMember() {
        fixture.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)",
                java.sql.Timestamp.valueOf("2026-10-30 10:00:00"));
        String subject = identity.resolveAuthenticatedName("bob").orElseThrow().subjectId();
        fixture.tx.required(() -> {
            var locks = new WorkspaceGovernanceLocks(fixture.tx, fixture.tenants, fixture.workspaces, fixture.tenantMembers,
                    fixture.workspaceMembers, fixture.preferences);
            locks.tenant(subject, scope.tenantId());
            assertTrue(fixture.tenantMembers.insert(new TenantMembership(id(), scope.tenantId(), subject,
                    TenantRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
            assertTrue(fixture.workspaceMembers.insert(new WorkspaceMembership(id(), scope, subject,
                    WorkspaceRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
            return null;
        });
        var scheduler = reactor.core.scheduler.Schedulers.newBoundedElastic(2, 16, "assistant-http-test");
        var executor = new PlatformAuthenticatedExecutor(identity, new PlatformPersistenceExecutor(scheduler, fixture.tx));
        String root = "/ai-new/assistant/";
        try (var http = new ManagementHttpHarness(new AssistantManagementController(executor, assistants))) {
            var alice = ManagementHttpHarness.login(fixture.jdbc, 1, "alice");
            var bob = ManagementHttpHarness.login(fixture.jdbc, 2, "bob");
            var created = http.ok(root + "createAssistant", fixture.json.write(Map.of("scope", scope, "content", FIRST)),
                    "http-create", alice).getJSONObject("data");
            String assistant = created.getJSONObject("value").getStr("assistantId");
            assertFalse(created.toString().contains(FIRST.systemInstruction()));
            String target = fixture.json.write(Map.of("scope", scope, "assistantId", assistant));
            assertEquals(FIRST.systemInstruction(), http.ok(root + "getAssistant", target, null, alice)
                    .getJSONObject("data").getJSONObject("draft").getJSONObject("content").getStr("systemInstruction"));
            http.rejected(root + "getAssistant", target, null, bob, 403, "ACCESS_DENIED");
            assertTrue(http.ok(root + "listManagedAssistants", fixture.json.write(Map.of("scope", scope, "limit", 10)),
                    null, bob).getJSONObject("data").getJSONArray("items").isEmpty());
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void productionBeansAreOptInAndCreateThroughTheCommonAclGraph() {
        var stores = new PlatformServiceTestFactory(fixture.source, fixture.tx, fixture.json, null);
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(PlatformWorkspaceConfiguration.class, PlatformMybatisConfiguration.class, PlatformAssistantConfiguration.class,
                        AssistantManagementController.class)
                .withPropertyValues("arte.ai-new.enabled=true", "arte.platform.identity.enabled=true",
                        "arte.platform.persistence.enabled=true", "arte.platform.workspace.enabled=true")
                .withBean("appDataSource", javax.sql.DataSource.class, () -> fixture.source)
                .withBean("platformTransactionRunner", PublicTransactionRunner.class, () -> fixture.tx)
                .withBean(RbacIdentityProvider.class, () -> identity)
                .withBean(TenantStore.class, stores::tenants)
                .withBean(WorkspaceStore.class, stores::workspaces)
                .withBean(TenantMembershipStore.class, stores::tenantMembers)
                .withBean(WorkspaceMembershipStore.class, stores::workspaceMembers)
                .withBean(PreferenceStore.class, stores::preferences)
                .withBean(PolicyStore.class, stores::policies)
                .withBean(PersonalTenantPolicyTemplateStore.class, stores::templates)
                .withBean(WorkspaceAccessFactsStore.class, stores::facts)
                .withBean(ResourceGrantStore.class, stores::grants)
                .withBean(CommandReceiptStore.class, stores::receipts)
                .withBean(PublicOutboxStore.class, stores::outbox)
                .withBean(AuditSink.class, stores::audit)
                .withBean(PlatformIdGenerator.class, () -> AssistantControlIntegrationTest::id)
                .withBean(WorkspaceCursorStore.class, PlatformPersistenceIntegrationTest.Cursors::new)
                .withBean(PlatformPersistenceExecutor.class, () -> new PlatformPersistenceExecutor(
                        reactor.core.scheduler.Schedulers.immediate(), fixture.tx))
                .withBean(PlatformJson.class, () -> fixture.json);
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(AssistantManager.class).isEmpty());
        });
        runner.withPropertyValues("arte.ai-new-assistant.enabled=true").run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(AssistantManagementController.class));
            var manager = context.getBean(AssistantManager.class);
            var created = manager.create(fixture.actor, scope, FIRST, meta("configured")).value();
            assertEquals(FIRST, manager.get(fixture.actor, scope, created.assistantId()).draft().content());
        });
    }

    private Callable<Enum<?>> update(String assistant, String key, CyclicBarrier barrier) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
                return assistants.saveDraft(fixture.actor, scope, assistant, 1, SECOND, meta(key)).delivery();
            } catch (PlatformException error) {
                return error.getCode();
            }
        };
    }

    private AssistantManager manager(AssistantTrialVerifier verifier, AuditSink audit) {
        var locks = new WorkspaceGovernanceLocks(fixture.tx, fixture.tenants, fixture.workspaces, fixture.tenantMembers,
                fixture.workspaceMembers, fixture.preferences);
        return new DefaultAssistantManager(store, fixture.tx, locks, platform.authorization(), platform.resources(), identity,
                fixture.receipts, fixture.outbox, audit, AssistantControlIntegrationTest::id, CLOCK, verifier);
    }

    private String insertTrial(String assistant, long revision, Content content) {
        String trial = id();
        String digest = DefaultAssistantManager.contentDigest(content);
        fixture.jdbc.update("""
                        INSERT INTO arte_ai_assistant_trial VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                        """, trial, assistant, revision, fixture.actor.principal().subjectId(),
                com.arte.core.application.platform.DefaultCommandExecutor.digest(List.of(trial)), digest, digest, id(), id());
        return trial;
    }

    private static void rejected(PlatformErrorCodeEnum expected, org.junit.jupiter.api.function.Executable work) {
        assertEquals(expected, assertThrows(PlatformException.class, work).getCode());
    }

    private static CommandMeta meta(String key) {
        return new CommandMeta(key, "assistant-control-test");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
