package com.arte.app.platform.persistence;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisToolBindingStore;
import com.arte.ainew.persistence.mybatis.MybatisToolCatalogStore;
import com.arte.ainew.persistence.mybatis.MybatisToolReleaseStore;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.serialization.ToolReleaseDigests;
import com.arte.ainew.spi.auth.ToolInvocationAccess;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.app.platform.ai.AiControlData;
import com.arte.app.platform.ai.PlatformToolInvocationAccess;
import com.arte.app.platform.ai.PlatformToolManagementAccess;
import com.arte.app.platform.identity.*;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.value.StructuredValue;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.arte.ainew.spi.auth.ToolManagementAccess.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实公共身份／空间、现有 RBAC 表、当前 AI 配置及 TOOL MyBatis 事务，没有登录缓存或模型预算依赖。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
class PlatformToolManagementAccessTest {
    final PlatformPersistenceIntegrationTest f = new PlatformPersistenceIntegrationTest();
    final Clock clock = Clock.systemUTC();
    final Duration wait = Duration.ofSeconds(15);
    Scheduler scheduler;
    Connection ddl;
    Scope scope;
    RbacIdentityProvider identities;
    RbacToolPermissionReader permissions;
    com.arte.app.platform.persistence.mapper.PlatformAiControlMapper configuration;
    PlatformToolManagementAccess access;
    MybatisToolBindingStore bindings;
    MybatisToolReleaseStore releases;
    MybatisToolCatalogStore catalog;
    ToolBindingSettings settings;
    ToolDefinitionRevision tool;
    UserOnlineInfo online;

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        ddl = f.source.getConnection();
        for (String file : List.of("../arte-ai/scripts/arte-ai-new-ddl-mysql.sql", "scripts/arte-platform-ai-control-ddl-mysql.sql"))
            ScriptUtils.executeSqlScript(ddl, new ByteArrayResource(Files.readAllBytes(Path.of(file))));
        f.jdbc.execute("CREATE TABLE arte_rbac_relation(id BIGINT AUTO_INCREMENT PRIMARY KEY,source VARCHAR(64),binding_type VARCHAR(32),target VARCHAR(64), UNIQUE(source,binding_type,target))");
        f.jdbc.execute("CREATE TABLE arte_rbac_role(role_code VARCHAR(64) PRIMARY KEY,status VARCHAR(4))");
        var accounts = new RbacAccountReader(f.source, new RbacAccountMapperConfiguration().identityUserMapper(f.source));
        identities = new RbacIdentityProvider(accounts, new JdbcPrincipalIdentityStore(f.source, accounts), new TransactionTemplate(new DataSourceTransactionManager(f.source)),
                new PlatformIdentityProperties(true, 0L, List.of()), List::of, clock);
        permissions = new RbacToolPermissionReader(f.source);
        scope = f.initialize().value().workspace().scope();
        var config = new PlatformMybatisConfiguration();
        configuration = config.platformAiControlMapper(config.platformMapperSession(f.source, new PlatformPersistenceProperties(true, 0, 0, 0, 0, 0, null, null, null, null, null, false)));
        scheduler = Schedulers.newBoundedElastic(4, 64, "tool-access-test");
        online = new UserOnlineInfo().setId(1L).setUserName("alice").setAccountCreatedAt(LocalDateTime.parse("2026-10-10T10:00:00"));
        grant("aiTool:list");
        grant("aiTool:invoke");
        grant("aiTool:manage");
        grant("aiConfiguration:manage");
        enabled(true);
        access = adapter(true, properties(List.of()));
        var codec = new JacksonExecutionRecordCodec();
        catalog = new MybatisToolCatalogStore(f.source, codec, scheduler, clock);
        releases = new MybatisToolReleaseStore(f.source, codec, scheduler, clock);
        bindings = new MybatisToolBindingStore(f.source, codec, scheduler, clock, new ToolExecutionPolicy(Duration.ofSeconds(30), 1, Duration.ZERO, 100_000, 100_000, ToolExecutionPolicy.ApprovalMode.NONE), catalog, releases);
        var document = new StructuredValue.ObjectValue(Map.of("$schema", new StructuredValue.StringValue(com.arte.ainew.application.tool.schema.ToolSchemaValidator.DIALECT), "type", new StructuredValue.StringValue("object")));
        var schema = new ToolSchemaDefinition(ref("schema", "empty"), document, ToolCatalogDigests.schema(document));
        var provider = new ToolProviderDefinition(ref("tool-provider", "local"), ToolProviderDefinition.Kind.LOCAL, ref("tool-provider-adapter", "local-source"), null);
        var unsigned = new ToolDefinition(ref("tool", "local.read"), "local", "Read", "Read metadata", Set.of(), ref("capability", "local-read"), schema.definition(), schema.definition(), schema.definition(), new StructuredValue.ObjectValue(Map.of()),
                new ToolCapabilities(Set.of(), false), new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY, false, ToolRiskProfile.Idempotency.NONE, Set.of(), Set.of()),
                new ToolExecutionPolicy(Duration.ofSeconds(10), 1, Duration.ZERO, 1024, 1024, ToolExecutionPolicy.ApprovalMode.NONE), "0".repeat(64));
        var signed = new ToolDefinition(unsigned.definition(), unsigned.providerId(), unsigned.title(), unsigned.description(), unsigned.tags(), unsigned.capability(), unsigned.inputSchema(), unsigned.outputSchema(), unsigned.configurationSchema(), unsigned.defaultConfiguration(), unsigned.capabilities(), unsigned.risk(), unsigned.defaultPolicy(), ToolCatalogDigests.tool(unsigned));
        tool = new ToolDefinitionRevision(signed, provider.definition(), new ExecutionTarget.Local(ref("tool-adapter", "read")));
        catalog.put(new com.arte.ainew.spi.persistence.ToolCatalogStore.Batch(List.of(provider), List.of(schema), List.of(tool))).block(wait);
        var review = new ToolReleaseCommand.Review(List.of(new ToolReleaseCommand.Example(unsigned.defaultConfiguration(), unsigned.defaultConfiguration())), "review", null, ToolExecutionPolicy.ApprovalMode.NONE, null, null);
        var validation = new ToolPublicationValidation(ToolReleaseDigests.contract(tool, provider, List.of(schema)), "1".repeat(64), List.of(), clock.instant());
        var manage = permit(MANAGE);
        assertEquals(ToolReleaseReceipt.Rejection.NONE, releases.executeGuarded(manage.actor(), new ToolReleaseCommand(tool.tool().definition(), ToolReleaseCommand.Action.PUBLISH, 0, "setup-publish", "review", review, null), validation, () -> manage.requireInCurrentTransaction(Set.of())).block(wait).value().rejection());
        settings = new ToolBindingSettings(tool.tool().definition(), ToolBindingSettings.VersionPolicy.PINNED, "Private", true, unsigned.defaultConfiguration(), Map.of(), null, null);
    }

    @AfterEach
    void cleanup() throws Exception {
        scheduler.dispose();
        ddl.close();
        f.jdbc.execute("DROP ALL OBJECTS");
        f.cleanup();
    }

    @Test
    void existingAccountWorkspaceAndAiQualificationWorkWithoutModelsOrBudgetAccounts() {
        var permit = permit(BINDING_WRITE);
        assertEquals(f.actor.principal(), permit.actor().principal());
        assertEquals(new ExecutionOwner(scope.tenantId(), scope.workspaceId(), f.actor.principal().subjectId()), permit.owner());
        assertTrue(create(permit, "create").result().successful());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_budget_definition", Integer.class));
    }

    @Test
    void cachedTokenPermissionsAndRoleNamesCannotGrantAccess() {
        revoke("aiTool:invoke");
        online.setMenuOperations(Set.of("admin", "aiTool:invoke"));
        online.setRoles(Set.of("admin"));
        assertThrows(RuntimeException.class, () -> permit(BINDING_WRITE));
        assertEquals(0, count("binding_command"));
    }

    @Test
    void sameOwnerStillNeedsBothPlatformCatalogPermissions() {
        revoke("aiConfiguration:manage");
        assertThrows(RuntimeException.class, () -> permit(MANAGE));
        grant("aiConfiguration:manage");
        revoke("aiTool:manage");
        assertThrows(RuntimeException.class, () -> permit(MANAGE));
        assertNotNull(permit(LIST));
    }

    @Test
    void permissionRevocationAfterInitialPermitBlocksActualCommitAndDoesNotCreateReceipt() {
        var permit = permit(BINDING_WRITE);
        revoke("aiTool:invoke");
        assertThrows(RuntimeException.class, () -> create(permit, "denied"));
        assertEquals(0, count("binding"));
        assertEquals(0, count("binding_revision"));
        assertEquals(0, count("binding_command"));
    }

    @Test
    void anotherAuthorizationInTheSameTransactionSeesRbacRevocation() {
        var permit = permit(BINDING_WRITE);
        f.tx.required(() -> {
            permit.requireInCurrentTransaction(Set.of());
            revoke("aiTool:invoke");
            assertThrows(RuntimeException.class, () -> permit.requireInCurrentTransaction(Set.of()));
            return true;
        });
        assertEquals(0, count("binding_command"));
    }

    @Test
    void anotherAuthorizationInTheSameTransactionSeesSpaceClosing() {
        var permit = permit(BINDING_WRITE);
        f.tx.required(() -> {
            permit.requireInCurrentTransaction(Set.of());
            var current = f.workspaces.lock(scope).orElseThrow();
            assertTrue(f.workspaces.replace(new com.arte.core.pojo.workspace.Workspace(scope, current.name(), current.description(),
                    com.arte.core.enums.workspace.WorkspaceStateEnum.CLOSING, current.createdBy(), current.version() + 1,
                    current.authEpoch() + 1, current.createdAt(), clock.instant()), current.version()));
            assertThrows(RuntimeException.class, () -> permit.requireInCurrentTransaction(Set.of()));
            return true;
        });
        assertEquals(0, count("binding_command"));
    }

    @Test
    void replayAlsoRechecksCurrentRbac() {
        var permit = permit(BINDING_WRITE);
        assertTrue(create(permit, "same").result().successful());
        revoke("aiTool:invoke");
        assertThrows(RuntimeException.class, () -> create(permit, "same"));
        assertEquals(1, count("binding_revision"));
        assertEquals(1, count("binding_command"));
    }

    @Test
    void disabledAiBlocksEnableAndSaveButAllowsTheOwnerToDisableAndRead() {
        var created = create(permit(BINDING_WRITE), "create").result().value();
        enabled(false);
        assertThrows(RuntimeException.class, () -> permit(BINDING_WRITE));
        assertNotNull(permit(LIST));
        var permit = permit(BINDING_DISABLE);
        var command = new ToolBindingCommand(ToolBindingCommand.Action.SET_ENABLED, created.bindingId(), 1, "disable", "revoke", null, false, null, false);
        assertTrue(bindings.executeGuarded(permit.actor(), permit.owner(), command, () -> permit.requireInCurrentTransaction(Set.of())).block(wait).value().result().successful());
    }

    @Test
    void aiSwitchRevocationBetweenInitialPermitAndCommitIsEffective() {
        var permit = permit(BINDING_WRITE);
        enabled(false);
        assertThrows(RuntimeException.class, () -> create(permit, "disabled"));
        assertEquals(0, count("binding_command"));
    }

    @Test
    void accountSuspensionInvalidatesAnExistingPermit() {
        var permit = permit(BINDING_WRITE);
        f.jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=1");
        assertThrows(RuntimeException.class, () -> create(permit, "suspended"));
        assertEquals(0, count("binding_command"));
    }

    @Test
    void renamedOrRecycledAccountCannotReuseTheOldSession() {
        var permit = permit(BINDING_WRITE);
        f.jdbc.update("UPDATE arte_rbac_user SET user_name='renamed' WHERE id=1");
        assertThrows(RuntimeException.class, () -> create(permit, "renamed"));
        assertEquals(0, count("binding_command"));
    }

    @Test
    void sessionCopyDoesNotFollowMutableOnlineInfoAcrossThreads() {
        var session = access.capture(login());
        online.setId(999L).setUserName("mallory");
        var permit = session.authorize(scope, BINDING_WRITE).block(wait);
        assertEquals(f.actor.principal(), permit.actor().principal());
        assertTrue(create(permit, "copy").result().successful());
    }

    @Test
    void archivedSpaceBlocksNewBindingChangesFromAnExistingPermit() {
        scope = f.services.workspaces().create(f.actor, new WorkspaceCommands.CreateWorkspace(f.meta("second"), scope.tenantId(), "Second", "")).value().scope();
        enabled(true);
        var permit = permit(BINDING_WRITE);
        f.tx.required(() -> {
            f.gates(scope);
            var current = f.workspaces.lock(scope).orElseThrow();
            assertTrue(f.workspaces.replace(new com.arte.core.pojo.workspace.Workspace(scope, current.name(), current.description(), com.arte.core.enums.workspace.WorkspaceStateEnum.ARCHIVED,
                    current.createdBy(), current.version() + 1, current.authEpoch() + 1, current.createdAt(), clock.instant()), current.version()));
            return true;
        });
        assertThrows(RuntimeException.class, () -> create(permit, "archived"));
        assertEquals(0, count("binding_command"));
    }

    @Test
    void nonexistentScopeHasNoLegacyOrAdministratorBypass() {
        grant("admin");
        assertThrows(RuntimeException.class, () -> access.capture(login()).authorize(new Scope(scope.tenantId(), "missing"), MANAGE).block(wait));
    }

    @Test
    void tenantSuspensionAndMembershipRevocationAreCheckedAgainAtCommit() {
        var permit = permit(BINDING_WRITE);
        f.tx.required(() -> {
            f.gates(scope);
            f.jdbc.update("UPDATE arte_platform_workspace_member SET state=1,version=version+1 WHERE tenant_id=? AND workspace_id=? AND subject_id=?", scope.tenantId(), scope.workspaceId(), f.actor.principal().subjectId());
            return true;
        });
        assertThrows(RuntimeException.class, () -> create(permit, "removed"));
        assertEquals(0, count("binding_command"));
    }

    @Test
    void inactiveTenantCannotUseAnOldPermit() {
        var permit = permit(BINDING_WRITE);
        f.tx.required(() -> {
            f.gates(scope);
            f.jdbc.update("UPDATE arte_platform_tenant SET state=1,version=version+1,auth_epoch=auth_epoch+1 WHERE id=?", scope.tenantId());
            return true;
        });
        assertThrows(RuntimeException.class, () -> create(permit, "inactive-tenant"));
        assertEquals(0, count("binding_command"));
    }

    @Test
    void inheritedRoleOperationsAreCurrentAndDisabledRolesDoNotGrantThem() {
        revoke("aiTool:invoke");
        f.jdbc.update("INSERT INTO arte_rbac_role VALUES('writer','1')");
        relation("alice", "user_to_role", "writer");
        relation("writer", "role_to_operation", "aiTool:invoke");
        var permit = permit(BINDING_WRITE);
        f.jdbc.update("UPDATE arte_rbac_role SET status='3' WHERE role_code='writer'");
        assertThrows(RuntimeException.class, () -> create(permit, "disabled-role"));
        assertThrows(RuntimeException.class, () -> permit(BINDING_WRITE));
    }

    @Test
    void requiredToolScopesCannotBeManufacturedByMenuPermissions() {
        var permit = permit(BINDING_WRITE);
        grant("business:write");
        assertThrows(RuntimeException.class, () -> f.tx.required(() -> {
            permit.requireInCurrentTransaction(Set.of("business:write"));
            return true;
        }));
    }

    @Test
    void guardRequiresTheSamePhysicalWritableTransaction() {
        var permit = permit(BINDING_WRITE);
        assertThrows(IllegalStateException.class, () -> permit.requireInCurrentTransaction(Set.of()));
        assertThrows(IllegalStateException.class, () -> f.tx.readOnly(() -> {
            permit.requireInCurrentTransaction(Set.of());
            return true;
        }));
        var other = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        var foreign = new TransactionTemplate(new DataSourceTransactionManager(other));
        assertThrows(IllegalStateException.class, () -> foreign.execute(status -> {
            permit.requireInCurrentTransaction(Set.of());
            return true;
        }));
    }

    @Test
    void fixedModeUsesActualSubjectAndScopeGrantWithoutDependingOnABindingOrRate() {
        var grant = new NewAiProperties.Grant("alice", f.actor.principal().subjectId(), ExecutionPrincipal.Kind.USER, scope.tenantId(), scope.workspaceId(), "tool-test", true, Set.of("ai:invoke", "ai:read"), Set.of(), Set.of());
        var fixed = adapter(false, properties(List.of(grant)));
        assertNotNull(fixed.capture(login()).authorize(scope, BINDING_WRITE).block(wait));
        var mismatch = new NewAiProperties.Grant("alice", "wrong-subject", ExecutionPrincipal.Kind.USER, scope.tenantId(), scope.workspaceId(), "tool-test", true, Set.of("ai:invoke", "ai:read"), Set.of(), Set.of());
        assertThrows(RuntimeException.class, () -> adapter(false, properties(List.of(mismatch))).capture(login()).authorize(scope, BINDING_WRITE).block(wait));
    }

    @Test
    void permissionRowsStayLockedThroughActualToolCommit() throws Exception {
        var permit = permit(BINDING_WRITE);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var command = command("protected");
        var writing = bindings.executeGuarded(permit.actor(), permit.owner(), command, () -> {
            permit.requireInCurrentTransaction(Set.of());
            held.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }).toFuture();
        var pool = Executors.newSingleThreadExecutor();
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            var removing = pool.submit(() -> revoke("aiTool:invoke"));
            assertThrows(TimeoutException.class, () -> removing.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(writing.get(5, TimeUnit.SECONDS).value().result().successful());
            removing.get(5, TimeUnit.SECONDS);
            assertThrows(RuntimeException.class, () -> create(permit, "after-revoke"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void excessiveRolesFailClosedWithoutUnboundedRoleQueries() {
        for (int i = 0; i < 257; i++) relation("alice", "user_to_role", "role-" + i);
        assertThrows(RuntimeException.class, () -> permit(BINDING_WRITE));
    }

    @Test
    void permissionReaderCannotUseAThreadLocalConnectionOrAnOrdinarySnapshot() {
        assertThrows(IllegalStateException.class, () -> permissions.current("alice"));
        assertTrue(f.tx.required(() -> permissions.current("alice")).contains("aiTool:invoke"));
    }

    @Test
    void invocationHistoryAndCancellationRemainAvailableAfterAiIsDisabled() {
        var context = invocationContext();
        var invocation = invocation(context);
        var access = invocationAdapter();
        enabled(false);
        assertNotNull(access.authorize(invocation, context, ToolInvocationAccess.Operation.READ).block(wait));
        var permit = access.authorize(invocation, context, ToolInvocationAccess.Operation.CANCEL).block(wait);
        assertNotNull(permit);
        f.tx.required(() -> {
            permit.requireInCurrentTransaction();
            return true;
        });
    }

    @Test
    void invocationReadAndCancelCheckIndependentCurrentRbacOperations() {
        var context = invocationContext();
        var invocation = invocation(context);
        var access = invocationAdapter();
        revoke("aiTool:list");
        assertThrows(RuntimeException.class, () -> access.authorize(invocation, context, ToolInvocationAccess.Operation.READ).block(wait));
        assertNotNull(access.authorize(invocation, context, ToolInvocationAccess.Operation.CANCEL).block(wait));
        grant("aiTool:list");
        revoke("aiTool:invoke");
        assertNotNull(access.authorize(invocation, context, ToolInvocationAccess.Operation.READ).block(wait));
        assertThrows(RuntimeException.class, () -> access.authorize(invocation, context, ToolInvocationAccess.Operation.CANCEL).block(wait));
    }

    @Test
    void invocationCancelPermitRequiresOriginalWriteTransactionAndCurrentPermission() {
        var context = invocationContext();
        var permit = invocationAdapter().authorize(invocation(context), context, ToolInvocationAccess.Operation.CANCEL).block(wait);
        assertThrows(IllegalStateException.class, permit::requireInCurrentTransaction);
        revoke("aiTool:invoke");
        assertThrows(RuntimeException.class, () -> f.tx.required(() -> {
            permit.requireInCurrentTransaction();
            return true;
        }));
    }

    @Test
    void suspendedAccountInvalidatesInvocationReadAndExistingCancelPermit() {
        var context = invocationContext();
        var invocation = invocation(context);
        var access = invocationAdapter();
        var permit = access.authorize(invocation, context, ToolInvocationAccess.Operation.CANCEL).block(wait);
        f.jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=1");
        assertThrows(RuntimeException.class, () -> access.authorize(invocation, context, ToolInvocationAccess.Operation.READ).block(wait));
        assertThrows(RuntimeException.class, () -> f.tx.required(() -> {
            permit.requireInCurrentTransaction();
            return true;
        }));
    }

    @Test
    void invocationCannotBeReadUnderAForeignStableSubjectOrWorkspace() {
        var context = invocationContext();
        var invocation = invocation(context);
        var changed = new ExecutionContext("query", "trace",
                new ExecutionAuthorization(context.authorization().principal(), scope.tenantId(), "other", Set.of("ai:read"), "grant"),
                context.deadline(), null, null, "release", "key");
        assertThrows(RuntimeException.class, () -> invocationAdapter().authorize(invocation, changed,
                ToolInvocationAccess.Operation.READ).block(wait));
    }

    private PlatformToolInvocationAccess invocationAdapter() {
        return new PlatformToolInvocationAccess(identities, permissions, f.tx, f.services.writeGuard(),
                properties(List.of()), true, scheduler, clock);
    }

    @Test
    void independentApprovalRequiresExplicitCurrentOperationEvenForAdmin() {
        var context = invocationContext();
        grant("admin");
        assertThrows(RuntimeException.class, () -> f.tx.required(() -> {
            access.requireApprovalReadInCurrentTransaction(context, true);
            return true;
        }));
        grant("aiTool:approve");
        assertTrue(f.tx.required(() -> {
            access.requireApprovalReadInCurrentTransaction(context, true);
            return true;
        }));
        revoke("aiTool:approve");
        assertThrows(RuntimeException.class, () -> f.tx.required(() -> {
            access.requireApprovalReadInCurrentTransaction(context, true);
            return true;
        }));
    }

    @Test
    void requesterConfirmationRechecksInvokeFromReadContextWithoutExpandingExecutionScopes() {
        var context = invocationContext();
        assertTrue(f.tx.required(() -> {
            access.requireConfirmationInCurrentTransaction(context, Set.of("ai:invoke"));
            return true;
        }));
        assertEquals(Set.of("ai:read"), context.authorization().scopes());
        revoke("aiTool:invoke");
        assertThrows(RuntimeException.class, () -> f.tx.required(() -> {
            access.requireConfirmationInCurrentTransaction(context, Set.of());
            return true;
        }));
    }

    @Test
    void disabledAiRejectsNewApprovalButRequesterCanReadExistingContent() {
        grant("aiTool:approve");
        var context = invocationContext();
        enabled(false);
        assertThrows(RuntimeException.class, () -> f.tx.required(() -> {
            access.requireApprovalReadInCurrentTransaction(context, true);
            return true;
        }));
        assertTrue(f.tx.required(() -> {
            access.requireApprovalReadInCurrentTransaction(context, false);
            return true;
        }));
    }

    @Test
    void configuredApprovalSubjectNameUsesCurrentStableIdentityAndNeverCreatesUnknownIdentity() {
        assertEquals("alice", f.tx.required(() -> identities.currentSubjectName(f.actor.principal().subjectId()).orElseThrow()));
        assertTrue(f.tx.required(() -> identities.currentSubjectName("unknown").isEmpty()));
        f.jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=1");
        assertTrue(f.tx.required(() -> identities.currentSubjectName(f.actor.principal().subjectId()).isEmpty()));
    }

    @Test
    void approvalPlanPermitRechecksRequesterPermissionInActualTransaction() {
        var snapshot = approvalSnapshot(ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION);
        var plan = new ToolApproval.Plan(snapshot.policy().approvalMode(), Set.of(), snapshot.receivedAt().plusSeconds(90), Duration.ofSeconds(30));
        var approvals = approvalAdapter(snapshot, List.of());
        var permit = approvals.authorizePlan(snapshot, plan, snapshot.request().context()).block(wait);
        assertTrue(f.tx.required(() -> {
            permit.requireInCurrentTransaction(snapshot, plan);
            return true;
        }));
        revoke("aiTool:invoke");
        assertThrows(RuntimeException.class, () -> f.tx.required(() -> {
            permit.requireInCurrentTransaction(snapshot, plan);
            return true;
        }));
    }

    @Test
    void configuredApproverOperationDoesNotSubstituteForWorkspaceMembership() {
        f.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-10 10:00:00"));
        var bob = identities.resolveAuthenticatedName("bob").orElseThrow();
        relation("bob", "user_to_operation", "aiTool:approve");
        var snapshot = approvalSnapshot(ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL);
        var plan = new ToolApproval.Plan(snapshot.policy().approvalMode(), Set.of(bob.subjectId()), snapshot.receivedAt().plusSeconds(900), Duration.ofSeconds(30));
        var approvals = approvalAdapter(snapshot, List.of(new com.arte.ainew.config.NewAiToolApprovalProperties.Approvers(scope.tenantId(), scope.workspaceId(), Set.of(bob.subjectId()))));
        assertThrows(RuntimeException.class, () -> approvals.authorizePlan(snapshot, plan, snapshot.request().context()).block(wait));
    }

    /**
     * 仅替换正式文本工具的免费白名单为明确的长时测试契约；身份、成员、治理门控、RBAC 与事务均使用真实实现。
     */
    private com.arte.app.platform.ai.PlatformToolApprovalAccess approvalAdapter(ToolExecutionSnapshot snapshot,
                                                                                List<com.arte.ainew.config.NewAiToolApprovalProperties.Approvers> names) {
        var tools = org.mockito.Mockito.mock(com.arte.app.platform.ai.tool.LocalTextTools.class);
        org.mockito.Mockito.doAnswer(call -> {
                    assertEquals(snapshot.binding(), call.getArgument(1));
                    return null;
                }).when(tools)
                .requireFree(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        return new com.arte.app.platform.ai.PlatformToolApprovalAccess(access, identities,
                new com.arte.core.application.workspace.WorkspaceGovernanceLocks(f.tx, f.tenants, f.workspaces, f.tenantMembers, f.workspaceMembers, f.preferences),
                f.tx, tools, new com.arte.ainew.config.NewAiToolApprovalProperties(true, null, 0, names), scheduler, clock);
    }

    private ToolExecutionSnapshot approvalSnapshot(ToolExecutionPolicy.ApprovalMode mode) {
        var p = new ToolExecutionPolicy(Duration.ofMinutes(16), 1, Duration.ZERO, 1024, 1024, mode);
        var original = tool.tool();
        var unsigned = new ToolDefinition(original.definition(), original.providerId(), original.title(), original.description(), original.tags(), original.capability(),
                original.inputSchema(), original.outputSchema(), original.configurationSchema(), original.defaultConfiguration(), original.capabilities(), original.risk(), p, "0".repeat(64));
        var signed = new ToolDefinition(unsigned.definition(), unsigned.providerId(), unsigned.title(), unsigned.description(), unsigned.tags(), unsigned.capability(),
                unsigned.inputSchema(), unsigned.outputSchema(), unsigned.configurationSchema(), unsigned.defaultConfiguration(), unsigned.capabilities(), unsigned.risk(), p, ToolCatalogDigests.tool(unsigned));
        var old = invocationContext();
        var at = clock.instant();
        var context = new ExecutionContext("approval-execution", "trace", new ExecutionAuthorization(old.authorization().principal(), scope.tenantId(), scope.workspaceId(), Set.of("ai:read", "ai:invoke"), "grant"),
                at.plusSeconds(960), null, null, "release", "approval-key");
        var owner = ExecutionOwner.from(context);
        var empty = new StructuredValue.ObjectValue(Map.of());
        var binding = new ToolBindingResolution(new DefinitionRef("binding", "approval-binding", "p1-" + "a".repeat(64)), new DefinitionRef("binding", "approval-binding", "r1"), owner,
                new ToolDefinitionRevision(signed, tool.provider(), tool.target()), "b".repeat(64), empty, ToolCatalogDigests.configuration(empty), p, Set.of(), Map.of());
        var input = new ToolAdmissionInput("approval-binding", empty, null, null, null);
        var invocation = new ToolInvocation(com.arte.ainew.serialization.ToolAdmissionDigests.callId(owner, input.bindingId(), context.idempotencyKey()), signed.definition(), empty);
        var request = new InvocationRequest<>(signed.capability(), binding.definition(), CapabilityDescriptor.Kind.TOOL, invocation,
                new ExecutionOptions(context.deadline(), 1, 1024, 0, 0, Duration.ofMinutes(16)), context);
        return new ToolExecutionSnapshot(input, request, binding, p, List.of(catalog.findSchema(signed.inputSchema()).block(wait)), at);
    }

    private ExecutionContext invocationContext() {
        return new ExecutionContext("invocation", "trace",
                new ExecutionAuthorization(new ExecutionPrincipal(f.actor.principal().subjectId(), "alice", ExecutionPrincipal.Kind.USER),
                        scope.tenantId(), scope.workspaceId(), Set.of("ai:read"), "grant"), clock.instant().plusSeconds(30), null, null, "release", "key");
    }

    private Invocation invocation(ExecutionContext context) {
        var request = new InvocationRequest<>(tool.tool().capability(), ref("binding", "test"), CapabilityDescriptor.Kind.TOOL,
                new ToolInvocation("call", tool.tool().definition(), new StructuredValue.ObjectValue(Map.of())),
                new ExecutionOptions(context.deadline(), 1, 4096, 0, 0), context);
        return new Invocation(request, "1".repeat(64), null, null, null,
                Invocation.State.ACCEPTED, 0, null, null, null, clock.instant(), clock.instant());
    }

    private PlatformToolManagementAccess adapter(boolean database, NewAiProperties properties) {
        return new PlatformToolManagementAccess(identities, permissions, f.tx, f.services.writeGuard(), f.services.authorization(), configuration, f.json, properties, database, scheduler, clock);
    }

    private NewAiProperties properties(List<NewAiProperties.Grant> grants) {
        return new NewAiProperties(false, "appDataSource", "tool-test", null, null, grants, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private Authentication login() {
        return UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(online), null, List.of());
    }

    private ToolManagementAccess.Permit permit(ToolManagementAccess.Operation operation) {
        return access.capture(login()).authorize(scope, operation).block(wait);
    }

    private void enabled(boolean enabled) {
        var value = new AiControlData.WorkspaceConfiguration(scope, new AiControlData.Preset(enabled, List.of(), List.of()), 0, 0);
        int rows = f.jdbc.update("UPDATE arte_ai_workspace_configuration SET payload=? WHERE tenant_id=? AND workspace_id=?", f.json.write(value), scope.tenantId(), scope.workspaceId());
        if (rows == 0)
            f.jdbc.update("INSERT INTO arte_ai_workspace_configuration(tenant_id,workspace_id,payload,version) VALUES(?,?,?,0)", scope.tenantId(), scope.workspaceId(), f.json.write(value));
    }

    private void grant(String operation) {
        relation("alice", "user_to_operation", operation);
    }

    private void relation(String source, String kind, String target) {
        f.jdbc.update("INSERT INTO arte_rbac_relation(source,binding_type,target) VALUES(?,?,?)", source, kind, target);
    }

    private void revoke(String operation) {
        f.jdbc.update("DELETE FROM arte_rbac_relation WHERE source='alice' AND binding_type='user_to_operation' AND target=?", operation);
    }

    private ToolBindingCommand command(String key) {
        return new ToolBindingCommand(ToolBindingCommand.Action.SAVE, null, 0, key, "tool-test", settings, null, null, false);
    }

    private ToolBindingReceipt create(ToolManagementAccess.Permit permit, String key) {
        return bindings.executeGuarded(permit.actor(), permit.owner(), command(key), () -> permit.requireInCurrentTransaction(Set.of())).block(wait).value();
    }

    private int count(String table) {
        return f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_" + table, Integer.class);
    }

    private static DefinitionRef ref(String type, String id) {
        return new DefinitionRef(type, id, "v1");
    }
}
