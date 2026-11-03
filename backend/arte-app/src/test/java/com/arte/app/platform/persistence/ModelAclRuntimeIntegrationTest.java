package com.arte.app.platform.persistence;

import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.model.ModelAclMigrationService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelResourceCommands;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.ai.ModelAclStartupSafety;
import com.arte.app.platform.ai.PlatformModelAclConfiguration;
import com.arte.app.platform.ai.PlatformModelResourceConfiguration;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.pojo.access.ResourceAccessCommands;
import com.arte.core.pojo.access.ResourceAccessViews;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 生产 Spring 装配、真实受理及原子派发写入；模型网络由禁止调用的测试 Gateway 隔离。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
class ModelAclRuntimeIntegrationTest {

    final DatabaseAiControlIntegrationTest f = new DatabaseAiControlIntegrationTest();
    static final Duration WAIT = Duration.ofSeconds(15);
    Scope scope;

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        for (String file : List.of("arte-platform-resource-acl-ddl-mysql.sql", "arte-ai-model-resource-ddl-mysql.sql", "arte-ai-model-acl-scope-ddl-mysql.sql")) {
            String sql = Files.readString(Path.of("scripts", file));
            if (f.f.mysql == null) {
                sql = sql.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "").replaceAll("(?i)ENGINE=InnoDB", "").replaceAll("(?im)^SET\\s+[^;]*;", "");
            }
            try (java.sql.Connection connection = f.f.source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        f.f.jdbc.execute("CREATE TABLE arte_rbac_relation(source VARCHAR(256),binding_type VARCHAR(96),target VARCHAR(256))");
        f.f.jdbc.execute("CREATE TABLE arte_rbac_role(role_code VARCHAR(96),status VARCHAR(8))");
        f.f.jdbc.update("INSERT INTO arte_rbac_relation VALUES('alice','user_to_operation','aiConfiguration:manage')");
        scope = f.f.initialize().value().workspace().scope();
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    org.springframework.boot.test.context.runner.ApplicationContextRunner context() {
        return f.context().withUserConfiguration(PlatformModelResourceConfiguration.class, PlatformModelAclConfiguration.class, ModelAclStartupSafety.class);
    }

    ModelResource prepare(ApplicationContext c) {
        return prepare(c, ModelPublication.Source.DATABASE);
    }

    ModelResource prepare(ApplicationContext c, ModelPublication.Source origin) {
        DatabaseAiControlService db = c.getBean(DatabaseAiControlService.class);
        DatabaseAiTestCatalog.publish(db, c, f.f.actor, f.meta("import"));
        db.ensure(f.f.actor, scope);
        ModelResourceService models = c.getBean(ModelResourceService.class);
        ModelResource model = models.create(f.f.actor, new ModelResourceCommands.Create(f.meta("model"), scope, "Published database model")).value();
        models.associate(f.f.actor, new ModelResourceCommands.Associate(f.meta("associate"), scope, model.resource(), model.version(), f.ref("binding", "text"), origin, origin == ModelPublication.Source.DATABASE ? 1L : null));
        c.getBean(ModelAclMigrationService.class).migrate(f.f.actor, new ModelAclMigrationService.Migrate(f.meta("migrate"), scope, AiEntitlement.Source.DATABASE, Set.of(f.ref("binding", "text")), 0));
        return models.get(f.f.actor, scope, model.resource());
    }

    ExecutionContext execution(ApplicationContext c, String key) {
        ExecutionContext original = f.execution(c.getBean(DatabaseAiControlService.class), scope,
                Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION, AdmissionAuthorization.READ), key);
        String budget = c.getBean(BudgetDefinitionProvider.class).available(original, BudgetDefinitionProvider.Operation.READ).block(WAIT).getFirst().budgetRef();
        return new ExecutionContext(original.executionId(), original.traceId(), original.authorization(), original.deadline(), null, budget, original.releaseRef(), key);
    }

    EntryRequests.Chat request(String conversation, ExecutionContext execution) {
        ChatMessage user = new ChatMessage("user", ChatMessage.Role.USER, List.of(new ChatMessage.Text("hello")), List.of(), null);
        ContextRequest input = new ContextRequest(List.of(user), null, List.of(), List.of(), null, new ContextBudget(65536, 32768, 512, 0));
        return new EntryRequests.Chat(conversation, 0, null, null, input, f.ref("capability", "text"), f.ref("binding", "text"),
                new GenerationOptions(128, null, null, List.of()), new ExecutionOptions(execution.deadline(), 1, 4096, 0, 0, Duration.ofSeconds(60)));
    }

    String conversation(ApplicationContext c) {
        return c.getBean(ConversationService.class).create("ACL chat", null, List.of(), f.execution(c.getBean(DatabaseAiControlService.class), scope,
                Set.of(AdmissionAuthorization.CONVERSATION), "conversation")).block(WAIT).conversationId();
    }

    Invocation accept(ApplicationContext c) {
        String conversation = conversation(c);
        ExecutionContext execution = execution(c, "submit");
        AcceptedExecution accepted = c.getBean(ChatService.class).submit(request(conversation, execution), execution).block(WAIT);
        return c.getBean(MybatisExecutionPersistence.class).find(com.arte.ainew.common.execution.ExecutionOwners.from(execution), accepted.executionId()).block(WAIT);
    }

    void revoke(ApplicationContext c, ModelResource model) {
        DefaultWorkspaceServices services = c.getBean(DefaultWorkspaceServices.class);
        ResourceAccessViews.GrantView grant = services.resources().listGrants(f.f.actor, scope, model.resource(), new PageQuery(1, 100)).records().stream()
                .filter(value -> value.action().equals(MODEL_USE)).findFirst().orElseThrow();
        long acl = c.getBean(com.arte.core.spi.access.ResourceGrantStore.class).findAccess(model.resource()).orElseThrow().aclVersion();
        services.resources().revoke(f.f.actor, new ResourceAccessCommands.RevokeGrant(f.meta("revoke"), scope, model.resource(), grant.id(), acl, grant.version()));
    }

    long count(String table) {
        return f.f.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    AiAdmissionAuthorizationGuard.Check check(ApplicationContext c, Invocation invocation, AiAdmissionAuthorizationGuard.Boundary boundary) {
        ContextSnapshot snapshot = c.getBean(MybatisPayloadPersistence.class).find(ExecutionOwners.from(invocation.request().context()), invocation.contextSnapshotId()).block(WAIT);
        return new AiAdmissionAuthorizationGuard.Check(invocation.request(), f.seed().bindings().getFirst(), snapshot, null, boundary);
    }

    @Test
    void databaseAssociationCannotCreateAnExecutableResource() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            assertThrows(RuntimeException.class, () -> prepare(c));
            assertEquals(0, count("arte_ai_model_version"));
            assertEquals(0, count("arte_ai_invocation"));
        });
    }

    @Test
    void fixedLabelCannotAdoptBareDatabaseContent() {
        context().run(c -> {
            assertThrows(RuntimeException.class, () -> prepare(c, ModelPublication.Source.FIXED));
            assertEquals(0, count("arte_ai_model_version"));
            assertEquals(0, count("arte_ai_model_definition_source"));
        });
    }

    @Test
    void legacyManifestCannotEnableDatabaseModelsOrCreateAnExecution() {
        context().run(c -> {
            DatabaseAiControlService db = c.getBean(DatabaseAiControlService.class);
            DatabaseAiTestCatalog.publish(db, c, f.f.actor, f.meta("reviewed"));
            db.ensure(f.f.actor, scope);
            assertThrows(RuntimeException.class, () -> c.getBean(ModelAclMigrationService.class).migrate(f.f.actor,
                    new ModelAclMigrationService.Migrate(f.meta("migration"), scope, AiEntitlement.Source.DATABASE, Set.of(f.ref("binding", "text")), 0)));
            assertTrue(c.getBean(ChatConfigurationQueryService.class).discover(execution(c, "empty")).block(WAIT).options().isEmpty());
            assertThrows(RuntimeException.class, () -> accept(c));
            assertEquals(0, count("arte_ai_invocation"));
            assertEquals(0, count("arte_ai_turn"));
            assertEquals(0, count("arte_ai_outbox"));
        });
    }
}
