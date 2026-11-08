package com.arte.app.platform.persistence;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.Money;
import com.arte.app.platform.ai.PlatformBudgetAccountAccess;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.access.GrantSourceEnum;
import com.arte.core.enums.access.GrantStateEnum;
import com.arte.core.pojo.access.GrantTarget;
import com.arte.core.pojo.access.ResourceAccess;
import com.arte.core.pojo.access.ResourceGrant;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.CommandScope;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.reference.DefinitionRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static com.arte.core.constant.platform.PlatformActions.BUDGET_ACCOUNT_ASSIGN_RECONCILER;
import static com.arte.core.constant.platform.PlatformActions.BUDGET_ACCOUNT_RECONCILE;
import static com.arte.core.constant.platform.PlatformResourceTypes.BUDGET_ACCOUNT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实公共 ACL／成员／RBAC／审计；测试身份映射不连接任何供应商。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/08 17:30 ✾
 */
class BudgetAccountAccessIntegrationTest {

    final ResourceAclIntegrationTest f = new ResourceAclIntegrationTest();
    PlatformBudgetAccountAccess access;
    BudgetCommands.Account account;

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        f.jdbc.execute("CREATE TABLE arte_ai_account(id_key VARCHAR(64) PRIMARY KEY, workspace_snapshot CLOB)");
        f.jdbc.execute("CREATE TABLE arte_rbac_relation(source VARCHAR(256),binding_type VARCHAR(64),target VARCHAR(256))");
        f.jdbc.execute("CREATE TABLE arte_rbac_role(role_code VARCHAR(64),status VARCHAR(10))");
        var owner = new ExecutionOwner(f.scope.tenantId(), f.scope.workspaceId(), "alice");
        var zero = new Money(BigDecimal.ZERO, Currency.getInstance("CNY"));
        account = new BudgetCommands.Account("alice-budget", owner, new Money(BigDecimal.TEN, zero.currency()), zero, zero, new DefinitionRef("rate", "text", "v1"), 0);
        f.jdbc.update("INSERT INTO arte_ai_account VALUES(?,?)", com.arte.app.platform.ai.DatabaseAiControlService.hash(account.budgetRef()), new JacksonExecutionRecordCodec().encode(account));
        var identities = org.mockito.Mockito.mock(RbacIdentityProvider.class);
        org.mockito.Mockito.when(identities.findBySubjectId(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> f.identity.findBySubjectId(call.getArgument(0)));
        org.mockito.Mockito.when(identities.findCurrentExecutionPrincipal(org.mockito.ArgumentMatchers.any(ExecutionPrincipal.class)))
                .thenAnswer(call -> {
                    f.tx.requireWritable();
                    ExecutionPrincipal expected = call.getArgument(0);
                    return f.identity.findBySubjectId(expected.subjectId()).filter(expected::sameIdentityAs);
                });
        org.mockito.Mockito.when(identities.subjectName(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> f.identity.findBySubjectId(call.getArgument(0)).map(Principal::subjectId));
        org.mockito.Mockito.when(identities.currentSubjectName(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    f.tx.requireWritable();
                    return f.identity.findBySubjectId(call.getArgument(0)).map(Principal::subjectId);
                });
        var beans = new DefaultListableBeanFactory();
        access = new PlatformBudgetAccountAccess(f.source, identities, new RbacToolPermissionReader(f.source), f.tx,
                new WorkspaceGovernanceLocks(f.tx, f.stores.tenants(), f.stores.workspaces(), f.stores.tenantMembers(), f.stores.workspaceMembers(), f.stores.preferences()),
                beans.getBeanProvider(AuthorizationService.class), f.grants, f.stores.audit(), f.clock, Schedulers.immediate());
        f.services = f.graph(f.stores.audit(), List.of(access), List.of(access));
        beans.registerSingleton("authorization", f.services.authorization());
        f.resource = BudgetAccountQueryService.accountRef(account);
        f.tx.required(() -> {
            var locks = new WorkspaceGovernanceLocks(f.tx, f.stores.tenants(), f.stores.workspaces(), f.stores.tenantMembers(), f.stores.workspaceMembers(), f.stores.preferences());
            locks.scopes("alice", List.of(f.scope));
            access.registerInCurrentTransaction(account);
            return null;
        });
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    ExecutionContext actor(String subject, boolean admin) {
        return new ExecutionContext("review", "trace", new ExecutionAuthorization(ExecutionPrincipal.from(f.principals.get(subject), subject),
                f.scope.tenantId(), f.scope.workspaceId(), admin ? Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.BUDGET_ADMIN) : Set.of(AdmissionAuthorization.READ), "test"),
                f.now.get().plusSeconds(30), null, null, null, "review-key");
    }

    void permission(String subject) {
        f.jdbc.update("INSERT INTO arte_rbac_relation VALUES(?,'user_to_operation',?)", subject, AdmissionAuthorization.BUDGET_ADMIN);
    }

    void bootstrapGovernor() {
        f.tx.required(() -> {
            var locks = new WorkspaceGovernanceLocks(f.tx, f.stores.tenants(), f.stores.workspaces(), f.stores.tenantMembers(), f.stores.workspaceMembers(), f.stores.preferences());
            locks.scopes("alice", List.of(f.scope));
            assertTrue(f.grants.insertGrantIfAbsent(new ResourceGrant("explicit-governor", f.resource,
                    new GrantTarget.Subject("alice", new CommandScope(f.scope.tenantId(), f.scope.workspaceId()), 0), BUDGET_ACCOUNT_ASSIGN_RECONCILER,
                    GrantStateEnum.ACTIVE, null, GrantSourceEnum.GOVERNANCE, "deployment-reviewed-account", "alice", f.now.get(), 0)));
            return null;
        });
    }

    @Test
    void roleAndGlobalOperationAloneGrantNoAccountAuthority() {
        permission("alice");
        permission("carol");
        assertThrows(AccessDeniedException.class, () -> access.authorize(f.resource, actor("alice", true), true).block());
        assertThrows(AccessDeniedException.class, () -> access.authorize(f.resource, actor("carol", true), true).block());
        assertThrows(com.arte.core.exception.platform.PlatformException.class, () -> f.give("role-alone", "bob", Set.of(BUDGET_ACCOUNT_RECONCILE)));
        assertEquals("UNASSIGNED", access.handling(account, actor("alice", true), true).block().status());
        assertEquals(0, f.grants.findGrants(f.resource, new PageQuery(1, 100)).total());
    }

    @Test
    void governorCanAssignWithoutReconciliationRightAndReassignmentRevokesOldPrimary() {
        bootstrapGovernor();
        permission("bob");
        permission("carol");
        f.give("assign-bob", "bob", Set.of(BUDGET_ACCOUNT_RECONCILE));
        assertFalse(f.allowed(f.alice, BUDGET_ACCOUNT_RECONCILE));
        var handling = access.handling(account, actor("alice", false), false).block();
        assertEquals("ASSIGNED", handling.status());
        assertEquals("bob", handling.responsible().subjectId());
        assertNotNull(access.authorize(f.resource, actor("bob", true), true).block());
        f.give("replace-carol", "carol", Set.of(BUDGET_ACCOUNT_RECONCILE));
        assertFalse(f.allowed(f.bob, BUDGET_ACCOUNT_RECONCILE));
        assertTrue(f.allowed(f.carol, BUDGET_ACCOUNT_RECONCILE));
        assertEquals("carol", access.handling(account, actor("alice", false), false).block().responsible().subjectId());
        assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_audit WHERE action='budget-account:reconciler-assigned'", Integer.class));
        assertEquals(1, f.grants.findGrants(f.resource, new PageQuery(1, 100)).records().stream()
                .filter(g -> g.action().equals(BUDGET_ACCOUNT_RECONCILE) && g.state() == GrantStateEnum.REVOKED).count());
    }

    @Test
    void permitRechecksGrantAndIndependentOperationInLedgerTransaction() {
        bootstrapGovernor();
        permission("bob");
        var grant = f.give("assign", "bob", Set.of(BUDGET_ACCOUNT_RECONCILE)).value().grants().getFirst();
        var permit = access.authorize(f.resource, actor("bob", true), true).block();
        assertThrows(IllegalStateException.class, permit::requireInCurrentTransaction);
        f.revoke(grant, "revoke");
        assertThrows(RuntimeException.class, () -> f.tx.required(permit::requireInCurrentTransaction));
        f.give("assign-again", "bob", Set.of(BUDGET_ACCOUNT_RECONCILE));
        var fresh = access.authorize(f.resource, actor("bob", true), true).block();
        f.jdbc.update("DELETE FROM arte_rbac_relation WHERE source='bob'");
        assertThrows(AccessDeniedException.class, () -> f.tx.required(fresh::requireInCurrentTransaction));
        assertEquals("UNASSIGNED", access.handling(account, actor("alice", false), false).block().status());
    }

    @Test
    void selfServiceRequiresConcreteAccountGrantAndMissingEvidenceDoesNotMutateFunds() {
        bootstrapGovernor();
        permission("alice");
        f.give("assign-self", "alice", Set.of(BUDGET_ACCOUNT_RECONCILE));
        assertEquals("SELF_SERVICE", access.handling(account, actor("alice", true), true).block().status());
        assertThrows(AccessDeniedException.class, () -> access.authorize(new ResourceRef("other-tenant", BUDGET_ACCOUNT, "alice-budget"), actor("alice", true), true).block());
        assertThrows(AccessDeniedException.class, () -> access.authorize(new ResourceRef(f.scope.tenantId(), BUDGET_ACCOUNT, "not-this-budget"), actor("alice", true), true).block());
        assertEquals(BigDecimal.ZERO, account.charged().amount());
    }

    @Test
    void responsibilityBecomesUnassignedWhenThePrimaryLeaves() {
        bootstrapGovernor();
        permission("bob");
        f.give("assign-member", "bob", Set.of(BUDGET_ACCOUNT_RECONCILE));
        assertEquals("ASSIGNED", access.handling(account, actor("alice", false), false).block().status());
        f.jdbc.update("UPDATE arte_platform_workspace_member SET state=1,version=version+1 WHERE tenant_id=? AND workspace_id=? AND subject_id='bob'", f.scope.tenantId(), f.scope.workspaceId());
        assertEquals("UNASSIGNED", access.handling(account, actor("alice", false), false).block().status());
        assertThrows(AccessDeniedException.class, () -> access.authorize(f.resource, actor("bob", true), true).block());
    }

    @Test
    void generatedBootstrapUsesTheActualPublicPayloadAndDigestContracts() throws Exception {
        var process = new ProcessBuilder("python3", "scripts/arte-budget-reconciliation-bootstrap.py", "--tenant", f.scope.tenantId(),
                "--workspace", f.scope.workspaceId(), "--budget-ref", account.budgetRef(), "--owner", "alice",
                "--governor", "carol", "--issued-by", "alice", "--membership-generation", "0", "--operation-id", "reviewed-bootstrap").start();
        String sql = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        var matcher = java.util.regex.Pattern.compile("CONVERT\\(0x([a-f0-9]+) USING utf8mb4\\)").matcher(sql);
        ResourceGrant seeded = null;
        ResourceAccess root = null;
        while (matcher.find()) {
            String value = new String(HexFormat.of().parseHex(matcher.group(1)), java.nio.charset.StandardCharsets.UTF_8);
            if (!value.startsWith("{")) continue;
            if (value.contains("membershipGeneration")) seeded = new PlatformJson().read(value, ResourceGrant.class);
            else if (value.contains("aclVersion")) root = new PlatformJson().read(value, ResourceAccess.class);
        }
        assertNotNull(seeded);
        assertNotNull(root);
        assertEquals(f.resource, seeded.resource());
        assertEquals(BUDGET_ACCOUNT_ASSIGN_RECONCILER, seeded.action());
        assertEquals(new GrantTarget.Subject("carol", new CommandScope(f.scope.tenantId(), f.scope.workspaceId()), 0), seeded.target());
        String resourceKey = com.arte.core.application.platform.DefaultCommandExecutor.digest(List.of(f.scope.tenantId(), "budget-account", account.budgetRef()));
        String sourceKey = com.arte.core.application.platform.DefaultCommandExecutor.digest(List.of(resourceKey, BUDGET_ACCOUNT_ASSIGN_RECONCILER.value(),
                "GOVERNANCE", "reviewed-bootstrap", "SUBJECT", "carol", f.scope.tenantId(), f.scope.workspaceId(), "0"));
        assertTrue(sql.contains(sourceKey));
        assertEquals(f.root(), root);
    }
}
