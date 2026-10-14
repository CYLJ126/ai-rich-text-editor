package com.arte.core.application.workspace;

import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.pojo.workspace.TenantCommands;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.NOW;
import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.meta;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceTimePolicyTest {
    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture(clock);

    @Test
    void slowerNodeCanRenameWithoutChangingCreationKeyOrMovingUpdatedTimeBackwards() {
        var actor = f.user("alice");
        var scope = f.initialize(actor).workspace().scope();
        Instant latest = NOW.plusSeconds(10).plusNanos(123456789);
        clock.set(latest);
        f.services.workspaces().rename(actor, new WorkspaceCommands.RenameWorkspace(meta("first"), scope, 0, "first", ""));
        clock.set(NOW.minusSeconds(10));
        var command = new WorkspaceCommands.RenameWorkspace(meta("slow-node"), scope, 1, "second", "");
        var receipt = f.services.workspaces().rename(actor, command);
        var saved = f.workspaces.get(scope);
        assertEquals(NOW, saved.createdAt());
        assertEquals(latest, saved.updatedAt());
        assertEquals(2, saved.version());
        assertEquals("second", saved.name());
        assertEquals(NOW.minusSeconds(10), f.events.getLast().occurredAt());
        assertEquals(f.events.getLast().occurredAt(), f.audits.getLast().occurredAt());
        clock.set(NOW.minusSeconds(30));
        var replay = f.services.workspaces().rename(actor, command);
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(receipt.acceptedAt(), replay.acceptedAt());
        assertEquals(receipt.completedAt(), replay.completedAt());
        assertEquals(3, f.events.size());
    }

    @Test
    void creatingASpaceOnASlowerNodeDoesNotRegressTheParentTenant() {
        var actor = f.user("alice");
        var initial = f.initialize(actor);
        clock.set(NOW.minusSeconds(5));
        var result = f.services.workspaces().create(actor, new WorkspaceCommands.CreateWorkspace(
                meta("new-space"), initial.tenant().id(), "new space", ""));
        assertEquals(NOW, f.tenants.get(initial.tenant().id()).updatedAt());
        assertEquals(NOW.minusSeconds(5), f.workspaces.get(result.value().scope()).createdAt());
        assertEquals(1, f.tenants.get(initial.tenant().id()).version());
    }

    @Test
    void tenantAndWorkspacePolicyChangesKeepTheirMetadataTimesNondecreasing() {
        var actor = f.user("alice");
        var initial = f.initialize(actor);
        var scope = initial.workspace().scope();
        clock.set(NOW.minusSeconds(5));
        var tenantPolicy = f.tenantPolicies.get(scope.tenantId());
        f.services.policies().updateTenantPolicy(actor, new TenantCommands.UpdateTenantPolicy(
                meta("tenant-policy"), scope.tenantId(), 0, 0, tenantPolicy.rules(), true));
        var workspacePolicy = f.workspacePolicies.get(scope);
        f.services.policies().updateWorkspacePolicy(actor, new WorkspaceCommands.UpdateWorkspacePolicy(
                meta("workspace-policy"), scope, 0, 0, workspacePolicy.rules()));
        assertEquals(NOW, f.tenants.get(scope.tenantId()).updatedAt());
        assertEquals(NOW, f.workspaces.get(scope).updatedAt());
        assertEquals(1, f.tenants.get(scope.tenantId()).authEpoch());
        assertEquals(1, f.workspaces.get(scope).authEpoch());
    }

    @Test
    void clockRollbackInsideACommandCannotInvalidateItsCompletionReceipt() {
        var rollbackFixture = new InMemoryWorkspaceFixture(clock,
                List.of((actor, scope, operationId) -> clock.set(NOW.minusSeconds(60))));
        var actor = rollbackFixture.user("alice");
        var receipt = rollbackFixture.services.personal().initialize(actor, meta("rollback-inside"));
        assertEquals(NOW, receipt.acceptedAt());
        assertEquals(NOW, receipt.completedAt());
        assertEquals(NOW.minusSeconds(60), rollbackFixture.audits.getFirst().occurredAt());
        var replay = rollbackFixture.services.personal().initialize(actor, meta("rollback-inside"));
        assertEquals(receipt.operationId(), replay.operationId());
        assertEquals(receipt.completedAt(), replay.completedAt());
        assertEquals(1, rollbackFixture.events.size());
    }

    @Test
    void laterWallTimeStillAdvancesMetadataWithExistingNanosecondPrecision() {
        var actor = f.user("alice");
        var scope = f.initialize(actor).workspace().scope();
        Instant later = NOW.plusSeconds(1).plusNanos(987654321);
        clock.set(later);
        f.services.workspaces().rename(actor, new WorkspaceCommands.RenameWorkspace(meta("later"), scope, 0, "later", ""));
        assertEquals(later, f.workspaces.get(scope).updatedAt());
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time;

        MutableClock(Instant initial) {
            time = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            time.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return time.get();
        }
    }
}
