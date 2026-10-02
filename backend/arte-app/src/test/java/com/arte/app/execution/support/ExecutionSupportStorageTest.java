package com.arte.app.execution.support;

import com.arte.base.exception.BaseException;
import com.arte.base.model.artifact.Artifact;
import com.arte.base.model.artifact.ArtifactStatus;
import com.arte.base.model.artifact.ArtifactUpload;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.observability.AuditOutcome;
import com.arte.base.model.observability.AuditRecord;
import com.arte.base.model.resource.ResourceRef;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionSupportStorageTest {
    final Instant now = Instant.parse("2026-10-02T08:00:00Z");
    final ExecutionScope scope = new ExecutionScope("tenant", "workspace", new PrincipalRef("user", PrincipalType.USER));
    final ResourceRef owner = ResourceRef.current("execution", "execution-id");
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    Path directory;
    JdbcAuditSink audit;
    JdbcFileArtifactStore artifacts;

    @BeforeEach
    void setup() throws Exception {
        var datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(datasource);
        manager = new DataSourceTransactionManager(datasource);
        String sql = Files.readString(Path.of("scripts/arte-execution-support-ddl-mysql.sql"))
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
        try (var connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
        directory = Files.createTempDirectory("arte-artifacts-");
        audit = new JdbcAuditSink(jdbc, manager, Clock.fixed(now.plusNanos(12345), ZoneOffset.UTC));
        artifacts = store(now);
    }

    JdbcFileArtifactStore store(Instant at) {
        return new JdbcFileArtifactStore(jdbc, manager, directory, 1024, Clock.fixed(at, ZoneOffset.UTC));
    }

    AuditRecord record(String id, AuditOutcome outcome) {
        return new AuditRecord(id, "execution.authorization", scope, scope.principal(), "trace", "execution-id", now, outcome,
                List.of(owner), Set.of("policy.checked"), Map.of("policy", "1"));
    }

    Artifact upload(String content, Instant expiry) {
        return artifacts.upload(new ArtifactUpload(scope, owner, "text/plain", 1024, null, expiry), new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    Artifact publish(Artifact artifact) {
        var checking = artifacts.transition(scope, artifact.ref().artifactId(), artifact.revision(), ArtifactStatus.VALIDATING);
        return artifacts.transition(scope, artifact.ref().artifactId(), checking.revision(), ArtifactStatus.AVAILABLE);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (directory != null) try (var files = Files.walk(directory)) {
            for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void auditIsDurableIdempotentAndConflictingFactCannotOverwriteIt() {
        var receipt = audit.append(record("event-1", AuditOutcome.ALLOWED));
        assertEquals(receipt, audit.append(record("event-1", AuditOutcome.ALLOWED)));
        assertThrows(IllegalStateException.class, () -> audit.append(record("event-1", AuditOutcome.DENIED)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_execution_audit", Integer.class));
        byte[] payload = jdbc.queryForObject("SELECT payload FROM arte_execution_audit", byte[].class);
        assertEquals(receipt.contentDigest(), SupportEncoding.digest(payload));
    }

    @Test
    void auditCommitsIndependentlyOfOuterBusinessRollbackAndStorageFailureHasNoReceipt() {
        var outer = new TransactionTemplate(manager);
        outer.executeWithoutResult(status -> {
            audit.append(record("independent", AuditOutcome.ALLOWED));
            status.setRollbackOnly();
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_execution_audit", Integer.class));
        jdbc.execute("DROP TABLE arte_execution_audit");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> audit.append(record("failed", AuditOutcome.ALLOWED)));
    }

    @Test
    void changedAuditPayloadIsDetectedAndMetadataSizeIsBounded() {
        audit.append(record("event", AuditOutcome.ALLOWED));
        jdbc.update("UPDATE arte_execution_audit SET payload = ?", new byte[]{1, 2, 3});
        assertThrows(IllegalStateException.class, () -> audit.append(record("event", AuditOutcome.ALLOWED)));
        var enormous = new AuditRecord("big", "event", scope, scope.principal(), "x".repeat(70000), null, now, AuditOutcome.UNKNOWN, List.of(), Set.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> audit.append(enormous));
    }

    @Test
    void artifactQuarantineValidationAndRestartReadRoundTrip() throws Exception {
        var uploaded = upload("stored-result", now.plusSeconds(60));
        assertEquals(ArtifactStatus.QUARANTINED, uploaded.status());
        assertThrows(BaseException.class, () -> artifacts.open(scope, uploaded.ref()));
        var available = publish(uploaded);
        var restarted = store(now);
        assertEquals(available, restarted.find(scope, uploaded.ref().artifactId()).orElseThrow());
        try (var content = restarted.open(scope, available.ref())) {
            assertEquals("stored-result", new String(content.content().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void artifactScopeIsolationIncludesTenantWorkspacePrincipalAndType() {
        var available = publish(upload("private", null));
        for (var different : List.of(new ExecutionScope("other", "workspace", scope.principal()), new ExecutionScope("tenant", "other", scope.principal()),
                new ExecutionScope("tenant", "workspace", new PrincipalRef("other", PrincipalType.USER)), new ExecutionScope("tenant", "workspace", new PrincipalRef("user", PrincipalType.SERVICE)))) {
            assertTrue(artifacts.find(different, available.ref().artifactId()).isEmpty());
            assertThrows(BaseException.class, () -> artifacts.open(different, available.ref()));
        }
    }

    @Test
    void oversizedWrongDigestAndInterruptedInputNeverPublishPartialArtifacts() throws Exception {
        var limited = new ArtifactUpload(scope, owner, "text/plain", 1, null, null);
        assertThrows(IllegalArgumentException.class, () -> artifacts.upload(limited, new ByteArrayInputStream(new byte[2])));
        var providerLimited = new ArtifactUpload(scope, owner, "text/plain", 2048, null, null);
        assertThrows(IllegalArgumentException.class, () -> artifacts.upload(providerLimited, new ByteArrayInputStream(new byte[1025])));
        var digest = new ArtifactUpload(scope, owner, "text/plain", 10, "sha256:" + "0".repeat(64), null);
        assertThrows(IllegalArgumentException.class, () -> artifacts.upload(digest, new ByteArrayInputStream(new byte[0])));
        assertThrows(IllegalStateException.class, () -> artifacts.upload(providerLimited, new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("source interrupted");
            }
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_execution_artifact", Integer.class));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void staleLifecycleUpdateAndSkippingValidationAreRejected() {
        var uploaded = upload("data", null);
        assertThrows(IllegalArgumentException.class, () -> artifacts.transition(scope, uploaded.ref().artifactId(), 1, ArtifactStatus.AVAILABLE));
        var checking = artifacts.transition(scope, uploaded.ref().artifactId(), 1, ArtifactStatus.VALIDATING);
        assertThrows(BaseException.class, () -> artifacts.transition(scope, uploaded.ref().artifactId(), 1, ArtifactStatus.AVAILABLE));
        assertEquals(checking, artifacts.find(scope, uploaded.ref().artifactId()).orElseThrow());
    }

    @Test
    void expiredArtifactCannotBeReadAndReferencedArtifactCannotBeAutomaticallyDeleted() {
        var available = publish(upload("retained", now.plusSeconds(1)));
        assertThrows(BaseException.class, () -> store(now.plusSeconds(1)).open(scope, available.ref()));
        var referenced = artifacts.transition(scope, available.ref().artifactId(), available.revision(), ArtifactStatus.REFERENCED);
        assertNull(referenced.expiresAt());
        assertThrows(IllegalArgumentException.class, () -> artifacts.delete(scope, referenced.ref().artifactId(), referenced.revision()));
        assertTrue(referenced.isReadableAt(now.plusSeconds(100)));
    }

    @Test
    void byteIntegrityAndSymlinkAreCheckedBeforeReturningContent() throws Exception {
        var available = publish(upload("private", null));
        var data = directory.resolve(available.ref().artifactId() + ".data");
        Files.writeString(data, "changed");
        assertThrows(IllegalStateException.class, () -> artifacts.open(scope, available.ref()));
        Path external = Files.createTempFile("arte-external-", ".txt");
        try {
            Files.writeString(external, "private");
            Files.delete(data);
            Files.createSymbolicLink(data, external);
            assertThrows(IllegalStateException.class, () -> artifacts.open(scope, available.ref()));
        } finally {
            Files.deleteIfExists(data);
            Files.deleteIfExists(external);
        }
    }

    @Test
    void deletionPersistsTombstoneBeforeRemovingBytesAndCanRetryCleanup() throws Exception {
        var available = publish(upload("data", null));
        String id = available.ref().artifactId();
        artifacts.delete(scope, id, available.revision());
        var tombstone = artifacts.find(scope, id).orElseThrow();
        assertEquals(ArtifactStatus.DELETED, tombstone.status());
        assertFalse(Files.exists(directory.resolve(id + ".data")));
        artifacts.delete(scope, id, tombstone.revision());
        assertThrows(BaseException.class, () -> artifacts.open(scope, available.ref()));
    }

    @Test
    void metadataCommitFailureRetainsPrivateBytesForReconciliation() throws Exception {
        jdbc.execute("DROP TABLE arte_execution_artifact");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> upload("possibly-committed", null));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.filter(path -> path.toString().endsWith(".data")).count());
        }
    }
}
