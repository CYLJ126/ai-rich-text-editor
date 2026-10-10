package com.arte.core.application.workspace;

import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.enums.workspace.*;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.*;
import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_UPDATE;
import static org.junit.jupiter.api.Assertions.*;

class TransactionGovernanceLocksTest {
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture();
    private final TrustedActor alice = f.user("alice");

    @Test
    void sameTenantCanComposeWorkspaceWritesInEitherOrderAndReenterPreviousLocks() {
        Scope first = seed("tenant", "a-space");
        Scope second = seed("tenant", "z-space");
        f.transactions.required(() -> {
            rename(second, "second", 0);
            rename(first, "first", 0);
            rename(second, "second-again", 1);
            return null;
        });
        assertEquals(1, f.workspaces.get(first).version());
        assertEquals(2, f.workspaces.get(second).version());
        assertEquals(3, f.receipts.size());
    }

    @Test
    void ascendingNewTenantsCanComposeWithoutPredeclaringRanges() {
        Scope first = seed("a-tenant", "space");
        Scope second = seed("z-tenant", "space");
        f.transactions.required(() -> {
            rename(first, "first", 0);
            rename(second, "second", 0);
            return null;
        });
        assertEquals(1, f.workspaces.get(first).version());
        assertEquals(1, f.workspaces.get(second).version());
    }

    @Test
    void descendingNewTenantIsRejectedBeforeAnySecondStoreLockAndRollsBackPreviousWrite() {
        Scope first = seed("a-tenant", "space");
        Scope second = seed("z-tenant", "space");
        assertThrows(GovernanceLockOrderException.class, () -> f.transactions.required(() -> {
            rename(second, "second", 0);
            int before = f.lockOrder.size();
            assertThrows(GovernanceLockOrderException.class, () -> rename(first, "first", 0));
            assertEquals(before, f.lockOrder.size());
            // 嵌套调用失败后，即使捕获异常，也不能继续使用已经失败的事务锁状态。
            rename(second, "second-again", 1);
            return null;
        }));
        assertEquals(0, f.workspaces.get(first).version());
        assertEquals(0, f.workspaces.get(second).version());
        assertTrue(f.receipts.isEmpty());
        assertTrue(f.events.isEmpty());
    }

    @Test
    void catchingOrderFailureCannotCommitPreviouslyWrittenDomainDataOrReceipts() {
        Scope first = seed("a-tenant", "space");
        Scope second = seed("z-tenant", "space");
        assertThrows(IllegalStateException.class, () -> f.transactions.required(() -> {
            rename(second, "second", 0);
            assertThrows(GovernanceLockOrderException.class, () -> rename(first, "first", 0));
            return "caught";
        }));
        assertEquals(0, f.workspaces.get(second).version());
        assertTrue(f.receipts.isEmpty());
    }

    @Test
    void predeclaringAllRangesAllowsCommandCompositionInReverseBusinessOrder() {
        Scope first = seed("a-tenant", "space");
        Scope second = seed("z-tenant", "space");
        var requests = List.of(DefaultAuthorizationService.workspaceRequest(second, WORKSPACE_UPDATE, "write"),
                DefaultAuthorizationService.workspaceRequest(first, WORKSPACE_UPDATE, "write"));
        assertEquals("done", f.transactions.required(() -> f.services.writeGuard().inCurrentTransaction(alice, requests, () -> {
            rename(second, "second", 0);
            rename(first, "first", 0);
            return "done";
        })));
        assertEquals(1, f.workspaces.get(first).version());
        assertEquals(1, f.workspaces.get(second).version());
    }

    @Test
    void completeRangePreflightRejectsBeforeAcquiringAnyPartOfAnInvalidPlan() {
        seed("a-tenant", "space");
        seed("m-tenant", "space");
        seed("z-tenant", "space");
        var locks = locks();
        assertThrows(IllegalStateException.class, () -> f.transactions.required(() -> {
            locks.tenant("alice", "m-tenant");
            int before = f.lockOrder.size();
            assertThrows(GovernanceLockOrderException.class,
                    () -> locks.ranges("alice", List.of("z-tenant", "a-tenant"), List.of()));
            assertEquals(before, f.lockOrder.size());
            return null;
        }));
    }

    @Test
    void currentAuthorizationReadsParticipateAndDoNotSwallowOrderFailures() {
        Scope first = seed("a-tenant", "space");
        Scope second = seed("z-tenant", "space");
        assertThrows(GovernanceLockOrderException.class, () -> f.transactions.required(() -> {
            f.services.authorization().requireInCurrentTransaction(alice,
                    DefaultAuthorizationService.workspaceRequest(second, WORKSPACE_UPDATE, "write"));
            int before = f.lockOrder.size();
            try {
                f.services.authorization().checkCurrent(alice,
                        DefaultAuthorizationService.workspaceRequest(first, WORKSPACE_UPDATE, "write"));
                return fail("事务锁顺序错误不能转成普通授权拒绝");
            } finally {
                assertEquals(before, f.lockOrder.size());
            }
        }));
    }

    @Test
    void storeLockFailurePoisonsTransactionEvenIfCallerCatchesIt() {
        Scope first = seed("a-tenant", "space");
        seed("z-tenant", "space");
        var locks = locks();
        assertThrows(IllegalStateException.class, () -> f.transactions.required(() -> {
            rename(first, "renamed", 0);
            f.failTenantLock = true;
            var error = assertThrows(IllegalStateException.class, () -> locks.tenant("alice", "z-tenant"));
            assertEquals("Tenant lock unavailable", error.getMessage());
            f.failTenantLock = false;
            int before = f.lockOrder.size();
            assertThrows(GovernanceLockOrderException.class, () -> locks.tenant("alice", "a-tenant"));
            assertEquals(before, f.lockOrder.size());
            return "caught";
        }));
        assertEquals(0, f.workspaces.get(first).version());
        assertTrue(f.receipts.isEmpty());
    }

    @Test
    void independentLockAndSnapshotInstancesShareTheSameActualTransactionState() {
        seed("a-tenant", "space");
        seed("z-tenant", "space");
        var one = locks();
        var two = locks();
        var access = new WorkspaceAccessSupport(new CurrentPrincipalResolver(f.identity), f.tenantStore, f.workspaceStore,
                f.tenantMemberStore, f.workspaceMemberStore, f.policyStore, f.preferenceStore, f.clock, f.transactions, f.batchFactsStore);
        assertThrows(IllegalStateException.class, () -> f.transactions.required(() -> {
            one.tenant("alice", "z-tenant");
            int before = f.lockOrder.size();
            assertThrows(GovernanceLockOrderException.class, () -> two.tenant("alice", "a-tenant"));
            assertThrows(GovernanceLockOrderException.class, () -> access.tenantCurrent(alice, "z-tenant"));
            assertEquals(before, f.lockOrder.size());
            return null;
        }));
    }

    @Test
    void newSubjectAfterTenantIsRejectedBeforeAcquiringItsGate() {
        Scope scope = seed("tenant", "space");
        var bob = f.user("bob");
        assertThrows(IllegalStateException.class, () -> f.transactions.required(() -> {
            rename(scope, "renamed", 0);
            int before = f.lockOrder.size();
            assertThrows(GovernanceLockOrderException.class,
                    () -> f.services.personal().initialize(bob, meta("initialize-bob")));
            assertEquals(before, f.lockOrder.size());
            return null;
        }));
        assertEquals(0, f.workspaces.get(scope).version());
        assertFalse(f.preferences.containsKey("bob"));
    }

    @Test
    void preferenceUpdatesCannotBypassSubjectOrderInAnOuterTransaction() {
        var bob = f.user("bob");
        f.initialize(bob);
        Scope scope = seed("tenant", "space");
        assertThrows(IllegalStateException.class, () -> f.transactions.required(() -> {
            rename(scope, "renamed", 0);
            int before = f.lockOrder.size();
            assertThrows(GovernanceLockOrderException.class, () -> f.services.preferences().update(bob,
                    new WorkspaceCommands.UpdatePreference(meta("bob-preference"), 0, null, null, Set.of())));
            assertEquals(before, f.lockOrder.size());
            return null;
        }));
        assertEquals(0, f.preferences.get("bob").version());
    }

    @Test
    void subjectsMustBeAscendingBeforeTenantsButAlreadyHeldSubjectsMayReenter() {
        seed("tenant", "space");
        var locks = locks();
        f.transactions.required(() -> {
            locks.subject("alice");
            locks.subject("bob");
            locks.tenant("alice", "tenant");
            locks.subject("bob");
            return null;
        });
        assertThrows(GovernanceLockOrderException.class, () -> f.transactions.required(() -> {
            locks.subject("bob");
            int before = f.lockOrder.size();
            try {
                locks.subject("alice");
                return fail("新增主体门控不能逆序");
            } finally {
                assertEquals(before, f.lockOrder.size());
            }
        }));
    }

    @Test
    void childLockCannotBeAcquiredWithoutAnExistingExclusiveParentAndSubject() {
        Scope scope = seed("tenant", "space");
        var locks = locks();
        assertThrows(GovernanceLockOrderException.class, () -> f.transactions.required(() -> {
            locks.subject("alice");
            return locks.lockWorkspace(scope);
        }));
        assertTrue(f.lockOrder.stream().noneMatch(key -> key.startsWith("workspace:")));
        assertThrows(GovernanceLockOrderException.class, () -> f.transactions.required(() -> {
            locks.tenant("alice", "tenant");
            return locks.lockTenantMember("tenant", "unlocked-subject");
        }));
    }

    @Test
    void transactionResourceSurvivesRequiredNestingAndIsClearedAfterCommitAndRollback() {
        Object key = new Object();
        var factories = new AtomicInteger();
        Object first = f.transactions.required(() -> {
            Object value = f.transactions.resource(key, () -> {
                factories.incrementAndGet();
                return new Object();
            });
            assertSame(value, f.transactions.required(() -> f.transactions.resource(key, () -> fail("不得新建嵌套状态"))));
            return value;
        });
        Object second = f.transactions.required(() -> f.transactions.resource(key, Object::new));
        assertNotSame(first, second);
        assertEquals(1, factories.get());
        assertThrows(IllegalStateException.class, () -> f.transactions.required(() -> {
            f.transactions.resource(key, Object::new);
            throw new IllegalStateException("rollback");
        }));
        Object third = f.transactions.required(() -> f.transactions.resource(key, Object::new));
        assertNotSame(second, third);
        assertThrows(IllegalStateException.class, () -> f.transactions.resource(key, Object::new));
    }

    @Test
    void aNewTransactionMayAcquireLowerTenantAfterPriorCommitOrRollback() {
        seed("a-tenant", "space");
        seed("z-tenant", "space");
        var locks = locks();
        f.transactions.required(() -> {
            locks.tenant("alice", "z-tenant");
            return null;
        });
        f.transactions.required(() -> {
            locks.tenant("alice", "a-tenant");
            return null;
        });
        assertThrows(GovernanceLockOrderException.class, () -> f.transactions.required(() -> {
            locks.tenant("alice", "z-tenant");
            locks.tenant("alice", "a-tenant");
            return null;
        }));
        f.transactions.required(() -> {
            locks.tenant("alice", "a-tenant");
            return null;
        });
    }

    @Test
    void initializationAndWorkspaceCreationCanComposeInsideOneOuterTransaction() {
        f.transactions.required(() -> {
            var personal = f.initialize(alice);
            var first = f.services.workspaces().create(alice,
                    new WorkspaceCommands.CreateWorkspace(meta("create-one"), personal.tenant().id(), "一", ""));
            var second = f.services.workspaces().create(alice,
                    new WorkspaceCommands.CreateWorkspace(meta("create-two"), personal.tenant().id(), "二", ""));
            rename(first.value().scope(), "renamed", 0);
            assertNotEquals(first.value().scope(), second.value().scope());
            return null;
        });
        assertEquals(3, f.workspaces.size());
    }

    @Test
    void personalResultPrelocksCrossTenantPreferenceParentsAndFiltersMissingReferences() {
        Scope defaultScope = f.initialize(alice).workspace().scope();
        Scope low = seed("a-tenant", "favorite");
        Scope high = seed("z-tenant", "favorite");
        Scope missing = new Scope("missing-tenant", "missing-space");
        f.preferences.put("alice", new WorkspacePreference("alice", defaultScope, high, low, Set.of(low, high, missing), 1));
        var result = f.services.personal().initialize(alice, meta("existing-with-favorites")).value();
        assertEquals(Set.of(low, high), result.preference().favorites());
        assertEquals(high, result.preference().startupScope());
        assertEquals(low, result.preference().lastScope());
    }

    @Test
    void personalReplayPrelocksOriginalReferencesEvenIfCurrentPreferencesChanged() {
        Scope defaultScope = f.initialize(alice).workspace().scope();
        Scope low = seed("a-tenant", "old-favorite");
        Scope high = seed("z-tenant", "new-favorite");
        f.preferences.put("alice", new WorkspacePreference("alice", defaultScope, null, null, Set.of(low), 1));
        var command = meta("initialize-with-original-favorite");
        var first = f.services.personal().initialize(alice, command);
        f.preferences.put("alice", new WorkspacePreference("alice", defaultScope, null, null, Set.of(high), 2));
        var replay = f.services.personal().initialize(alice, command);
        assertEquals(com.arte.core.enums.platform.CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(first.value(), replay.value());
        assertEquals(first.operationId(), replay.operationId());
    }

    private WorkspaceGovernanceLocks locks() {
        return new WorkspaceGovernanceLocks(f.transactions, f.tenantStore, f.workspaceStore,
                f.tenantMemberStore, f.workspaceMemberStore, f.preferenceStore);
    }

    private void rename(Scope scope, String operation, long version) {
        f.services.workspaces().rename(alice,
                new WorkspaceCommands.RenameWorkspace(meta(operation), scope, version, operation, ""));
    }

    private Scope seed(String tenantId, String workspaceId) {
        Scope scope = new Scope(tenantId, workspaceId);
        f.tenants.putIfAbsent(tenantId, new Tenant(tenantId, "组织", TenantTypeEnum.ORGANIZATION, null,
                TenantStateEnum.ACTIVE, "alice", 0, 0, NOW, NOW));
        f.tenantMembers.putIfAbsent(List.of(tenantId, "alice"), new TenantMembership("member-" + tenantId,
                tenantId, "alice", TenantRoleEnum.OWNER, MembershipStateEnum.ACTIVE, 0, 0));
        var policy = DefaultWorkspacePolicies.conservativePersonalDefaults().personalTenant(tenantId);
        f.tenantPolicies.put(tenantId, policy);
        f.workspaces.put(scope, new Workspace(scope, "空间", "", WorkspaceStateEnum.ACTIVE, "alice", 0, 0, NOW, NOW));
        f.workspaceMembers.put(workspaceKey(scope, "alice"), new WorkspaceMembership("member-" + workspaceId, scope,
                "alice", WorkspaceRoleEnum.OWNER, MembershipStateEnum.ACTIVE, 0, 0));
        f.workspacePolicies.put(scope, DefaultWorkspacePolicies.conservativePersonalDefaults().workspace(scope, policy));
        return scope;
    }
}
