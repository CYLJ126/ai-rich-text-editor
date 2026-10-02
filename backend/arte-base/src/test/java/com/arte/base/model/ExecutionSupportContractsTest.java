package com.arte.base.model;

import com.arte.base.model.artifact.Artifact;
import com.arte.base.model.artifact.ArtifactRef;
import com.arte.base.model.artifact.ArtifactStatus;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.observability.AuditOutcome;
import com.arte.base.model.observability.AuditRecord;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.spi.observability.Telemetry;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

public class ExecutionSupportContractsTest {
    final ExecutionScope scope = new ExecutionScope("tenant", "workspace", new PrincipalRef("user", PrincipalType.USER));
    final ResourceRef owner = ResourceRef.current("execution", "execution-id");
    final ArtifactRef ref = new ArtifactRef("artifact", owner, "text/plain", 0, "sha256:" + "0".repeat(64));
    final Instant now = Instant.parse("2026-10-02T08:00:00Z");

    @Test
    public void artifactValidationAndExpiryAreIndependentOfBusinessApplication() {
        assertThrows(IllegalArgumentException.class, () -> new ArtifactRef("artifact", owner, "text/plain", -1, ref.contentDigest()));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactRef("artifact", owner, "text/plain;secret=value", 0, ref.contentDigest()));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactRef("artifact", owner, "text/plain", 0, "unverified"));
        var available = new Artifact(ref, scope, ArtifactStatus.AVAILABLE, 1, now, now.plusSeconds(1));
        assertTrue(available.isReadableAt(now));
        assertFalse(available.isReadableAt(now.plusSeconds(1)));
        assertFalse(new Artifact(ref, scope, ArtifactStatus.QUARANTINED, 1, now, null).isReadableAt(now));
        assertThrows(IllegalArgumentException.class, () -> new Artifact(ref, scope, ArtifactStatus.REFERENCED, 1, now, now.plusSeconds(1)));
        assertFalse(ArtifactStatus.REFERENCED.canTransitionTo(ArtifactStatus.DELETED));
        assertFalse(ArtifactStatus.QUARANTINED.canTransitionTo(ArtifactStatus.AVAILABLE));
    }

    @Test
    public void auditCollectionsAreDefensivelyCopiedAndDoNotAllowNullMetadata() {
        var resources = new ArrayList<ResourceRef>();
        resources.add(owner);
        var reasons = new HashSet<>(Set.of("policy.allowed"));
        var versions = new HashMap<>(Map.of("policy", "1"));
        var audit = new AuditRecord("event", "authorization", scope, scope.principal(), "trace", "execution", now, AuditOutcome.ALLOWED, resources, reasons, versions);
        resources.clear();
        reasons.clear();
        versions.clear();
        assertEquals(List.of(owner), audit.resources());
        assertEquals(Set.of("policy.allowed"), audit.reasonCodes());
        assertEquals(Map.of("policy", "1"), audit.policyVersions());
        assertThrows(UnsupportedOperationException.class, () -> audit.resources().clear());
    }

    @Test
    public void disabledTelemetryStillEnforcesMetricStructureAndNeverActsAsAuditSink() {
        var telemetry = Telemetry.disabled();
        telemetry.increment("executions", 1, Map.of(Telemetry.Label.OPERATION, "generate"));
        telemetry.duration("latency", Duration.ZERO, Map.of());
        assertThrows(IllegalArgumentException.class, () -> telemetry.increment("executions", -1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> telemetry.duration("latency", Duration.ofSeconds(-1), Map.of()));
    }
}
