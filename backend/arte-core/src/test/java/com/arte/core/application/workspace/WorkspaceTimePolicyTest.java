package com.arte.core.application.workspace;

import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.pojo.workspace.TenantCommands;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.NOW;
import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.meta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(latest.truncatedTo(ChronoUnit.MICROS), saved.updatedAt());
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
    void laterWallTimeAdvancesMetadataWithMicrosecondPrecision() {
        var actor = f.user("alice");
        var scope = f.initialize(actor).workspace().scope();
        Instant later = NOW.plusSeconds(1).plusNanos(987654321);
        clock.set(later);
        f.services.workspaces().rename(actor, new WorkspaceCommands.RenameWorkspace(meta("later"), scope, 0, "later", ""));
        assertEquals(later.truncatedTo(java.time.temporal.ChronoUnit.MICROS), f.workspaces.get(scope).updatedAt());
    }

    @Test
    void nanosecondClockIsNormalizedBeforeModelsReceiptsAndEffectsAreCreated() {
        Instant raw = Instant.parse("1969-12-31T23:59:59.999999999Z");
        Instant expected = Instant.parse("1969-12-31T23:59:59.999999Z");
        clock.set(raw);
        var actor = f.user("alice");
        var receipt = f.services.personal().initialize(actor, meta("precision"));
        var scope = receipt.value().workspace().scope();
        assertEquals(expected, f.workspaces.get(scope).createdAt());
        assertEquals(expected, f.tenants.get(scope.tenantId()).createdAt());
        assertEquals(expected, receipt.acceptedAt());
        assertEquals(expected, receipt.completedAt());
        assertEquals(expected, f.events.getFirst().occurredAt());
        assertEquals(expected, f.audits.getFirst().occurredAt());
        // 同一微秒内再次更新，时间允许相等，状态版本必须推进。
        clock.set(raw.minusNanos(1));
        f.services.workspaces().rename(actor, new WorkspaceCommands.RenameWorkspace(meta("same-micro"), scope, 0, "changed", ""));
        assertEquals(expected, f.workspaces.get(scope).updatedAt());
        assertEquals(1, f.workspaces.get(scope).version());
        assertThrows(IllegalArgumentException.class, () -> new com.arte.core.pojo.workspace.WorkspaceCandidateKey(raw, scope));
    }

    @Test
    void historicalReceiptReplayKeepsItsOriginalNanoseconds() {
        var actor = f.user("alice");
        var command = meta("historical");
        var applied = f.services.personal().initialize(actor, command);
        var entry = f.receipts.entrySet().iterator().next();
        var old = entry.getValue();
        var original = new com.arte.core.pojo.platform.CommandReceipt<>(applied.operationId(), applied.status(), applied.delivery(),
                applied.value(), applied.resultVersion(), NOW.plusNanos(123456789), NOW.plusNanos(987654321));
        f.receipts.put(entry.getKey(), new com.arte.core.pojo.platform.StoredCommandReceipt(old.identity(), old.requestDigest(),
                old.resultSchema(), original, old.version()));
        var replay = f.services.personal().initialize(actor, command);
        assertEquals(original.acceptedAt(), replay.acceptedAt());
        assertEquals(original.completedAt(), replay.completedAt());
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(1, f.events.size());
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
