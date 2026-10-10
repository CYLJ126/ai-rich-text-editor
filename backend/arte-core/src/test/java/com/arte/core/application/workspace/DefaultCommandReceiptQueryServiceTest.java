package com.arte.core.application.workspace;

import com.arte.core.enums.platform.*;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspaceMembership;
import com.arte.core.pojo.workspace.WorkspaceViews;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.meta;
import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.workspaceKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultCommandReceiptQueryServiceTest {
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture();
    private final TrustedActor alice = f.user("alice");

    private CommandReceipt<WorkspaceViews.WorkspaceView> create() {
        var initial = f.initialize(alice);
        return f.services.workspaces().create(alice, new WorkspaceCommands.CreateWorkspace(meta("create"),
                initial.tenant().id(), "Second", ""));
    }

    private void assertCode(PlatformErrorCodeEnum code, Runnable work) {
        assertEquals(code, assertThrows(PlatformException.class, work::run).getCode());
    }

    @Test
    void allSupportedResultsAreQueriedWithoutReexecutionOrWrites() {
        var init = f.services.personal().initialize(alice, meta("initial-receipt"));
        var created = create();
        var renamed = f.services.workspaces().rename(alice, new WorkspaceCommands.RenameWorkspace(meta("rename"),
                created.value().scope(), 0, "Renamed", ""));
        var prefs = f.services.preferences().update(alice, new WorkspaceCommands.UpdatePreference(meta("preferences"),
                0, created.value().scope(), null, Set.of(created.value().scope())));
        int eventCount = f.events.size();
        int receiptCount = f.receipts.size();
        for (CommandReceipt<?> original : java.util.List.<CommandReceipt<?>>of(init, created, renamed, prefs)) {
            var queried = f.services.receiptQueries().get(alice, original.operationId());
            assertEquals(original.operationId(), queried.operationId());
            assertEquals(original.value(), queried.value());
            assertEquals(original.resultVersion(), queried.resultVersion());
            assertEquals(original.completedAt(), queried.completedAt());
            assertEquals(CommandDeliveryEnum.REPLAYED, queried.delivery());
        }
        assertEquals(eventCount, f.events.size());
        assertEquals(receiptCount, f.receipts.size());
    }

    @Test
    void foreignActorMissingOperationAndRevokedMembershipAreHidden() {
        var created = create();
        var bob = f.user("bob");
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.receiptQueries().get(bob, created.operationId()));
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.receiptQueries().get(alice, "unknown-operation"));
        revoke(created.value().scope());
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.receiptQueries().get(alice, created.operationId()));
    }

    @Test
    void historicalPreferenceReferencesMustStillBeVisibleAfterCurrentPreferenceIsCleared() {
        var created = create();
        var prefs = f.services.preferences().update(alice, new WorkspaceCommands.UpdatePreference(meta("prefs"),
                0, created.value().scope(), null, Set.of(created.value().scope())));
        f.services.preferences().update(alice, new WorkspaceCommands.UpdatePreference(meta("clear"), 1, null, null, Set.of()));
        revoke(created.value().scope());
        assertCode(PlatformErrorCodeEnum.NOT_FOUND, () -> f.services.receiptQueries().get(alice, prefs.operationId()));
    }

    @Test
    void readPermissionIsEnoughAndOldEditPermissionIsNotRequired() {
        var created = create();
        var scope = created.value().scope();
        var old = f.workspaceMembers.get(workspaceKey(scope, "alice"));
        f.workspaceMembers.put(workspaceKey(scope, "alice"), new WorkspaceMembership(old.id(), scope, "alice",
                WorkspaceRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, old.generation(), old.version() + 1));
        assertEquals(created.value(), f.services.receiptQueries().get(alice, created.operationId()).value());
    }

    @Test
    void unknownSchemaMismatchedScopeAndPendingReceiptsFailClosed() {
        var created = create();
        var identity = f.receipts.values().stream().filter(s -> s.receipt().operationId().equals(created.operationId()))
                .findFirst().orElseThrow().identity();
        var original = f.receipts.get(identity);
        f.receipts.put(identity, new StoredCommandReceipt(identity, original.requestDigest(), "unknown:view:v1", created, 0));
        assertCode(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, () -> f.services.receiptQueries().get(alice, created.operationId()));
        var wrongIdentity = new CommandIdentity("alice", new CommandScope("wrong-tenant", null), "workspace:create", "create");
        f.receipts.remove(identity);
        f.receipts.put(wrongIdentity, new StoredCommandReceipt(wrongIdentity, original.requestDigest(), original.resultSchema(), created, 0));
        assertCode(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, () -> f.services.receiptQueries().get(alice, created.operationId()));
        f.receipts.remove(wrongIdentity);
        var pending = new CommandReceipt<>(created.operationId(), CommandStatusEnum.PENDING, CommandDeliveryEnum.APPLIED,
                created.value(), created.resultVersion(), created.acceptedAt(), null);
        f.receipts.put(identity, new StoredCommandReceipt(identity, original.requestDigest(), original.resultSchema(), pending, 0));
        assertCode(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, () -> f.services.receiptQueries().get(alice, created.operationId()));
    }

    @Test
    void currentIdentityIsCheckedAfterTheSubjectGate() {
        var created = create();
        // 模拟外层已有旧身份快照；取得主体门控后必须读到当前已停用身份。
        f.captureOldReadSnapshot();
        f.identities.put("alice", new Principal("alice", PrincipalKindEnum.USER, PrincipalStatusEnum.SUSPENDED));
        assertCode(PlatformErrorCodeEnum.ACCESS_DENIED, () -> f.services.receiptQueries().get(alice, created.operationId()));
    }

    private void revoke(Scope scope) {
        var old = f.workspaceMembers.get(workspaceKey(scope, "alice"));
        f.workspaceMembers.put(workspaceKey(scope, "alice"), new WorkspaceMembership(old.id(), scope, "alice", old.role(),
                MembershipStateEnum.REVOKED, old.generation(), old.version() + 1));
    }
}
