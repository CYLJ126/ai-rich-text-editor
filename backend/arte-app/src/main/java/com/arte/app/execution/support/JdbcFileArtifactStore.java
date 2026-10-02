package com.arte.app.execution.support;

import com.arte.base.exception.BaseException;
import com.arte.base.model.artifact.*;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.spi.artifact.ArtifactStore;
import com.arte.base.validation.ContractChecks;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * 数据库元数据＋受控目录内不可变字节。没有公开文件 URL；上传先隔离，再由可信验证服务开放。
 */
public final class JdbcFileArtifactStore implements ArtifactStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    private final Path directory;
    private final long maxBytes;
    private final Clock clock;

    public JdbcFileArtifactStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Path directory, long maxBytes, Clock clock) {
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must not be negative");
        this.jdbc = ContractChecks.required(jdbc, "jdbc");
        this.writes = JdbcAuditSink.writeTransaction(manager);
        this.maxBytes = maxBytes;
        this.clock = ContractChecks.required(clock, "clock");
        ContractChecks.required(directory, "directory");
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException("artifact root cannot be a symbolic link");
            this.directory = directory.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("artifact directory unavailable", e);
        }
    }

    @Override
    public Artifact upload(ArtifactUpload request, InputStream bytes) {
        ContractChecks.required(request, "request");
        ContractChecks.required(bytes, "bytes");
        Instant created = clock.instant();
        if (request.expiresAt() != null && !created.isBefore(request.expiresAt()))
            throw new IllegalArgumentException("artifact expiry already reached");
        String id = UUID.randomUUID().toString();
        Path temporary = null, stored = file(id);
        boolean metadataAttempted = false;
        try {
            temporary = Files.createTempFile(directory, "receiving-", ".tmp");
            var digest = SupportEncoding.sha256();
            long size = 0, limit = Math.min(maxBytes, request.maxBytes());
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = bytes.read(buffer)) != -1) {
                    if (count == 0) {
                        int one = bytes.read();
                        if (one == -1) break;
                        buffer[0] = (byte) one;
                        count = 1;
                    }
                    if (count > limit - size) throw new IllegalArgumentException("artifact size exceeds limit");
                    size += count;
                    digest.update(buffer, 0, count);
                    ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, count);
                    while (chunk.hasRemaining()) output.write(chunk);
                }
                output.force(true);
            }
            String checksum = "sha256:" + HexFormat.of().formatHex(digest.digest());
            if (request.expectedDigest() != null && !request.expectedDigest().equals(checksum))
                throw new IllegalArgumentException("artifact digest mismatch");
            Files.move(temporary, stored, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory();
            var artifact = new Artifact(new ArtifactRef(id, request.owner(), request.mediaType(), size, checksum), request.scope(),
                    ArtifactStatus.QUARANTINED, 1, created, request.expiresAt());
            metadataAttempted = true;
            return writes.execute(status -> {
                jdbc.update("INSERT INTO arte_execution_artifact (artifact_id, scope_key, owner_ref, media_type, size_bytes, content_digest, status, revision, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        id, SupportEncoding.scopeKey(request.scope()), SupportEncoding.owner(request.owner()), request.mediaType(), artifact.ref().sizeBytes(), checksum,
                        artifact.status().name(), 1, Timestamp.from(created), timestamp(request.expiresAt()));
                return find(request.scope(), id).orElseThrow(() -> new IllegalStateException("artifact metadata not visible"));
            });
        } catch (IOException failure) {
            clean(temporary, failure);
            clean(stored, failure);
            throw new IllegalStateException("artifact upload failed", failure);
        } catch (RuntimeException failure) {
            clean(temporary, failure);
            // 提交错误可能表示结果未知，保留已写私有字节，避免删除已成功提交记录引用的数据。
            if (!metadataAttempted) clean(stored, failure);
            throw failure;
        }
    }

    @Override
    public Optional<Artifact> find(ExecutionScope scope, String artifactId) {
        ContractChecks.required(scope, "scope");
        ContractChecks.identifier(artifactId, "artifactId");
        return jdbc.query("SELECT * FROM arte_execution_artifact WHERE artifact_id = ? AND scope_key = ?", (rs, row) ->
                new Artifact(new ArtifactRef(rs.getString("artifact_id"), SupportEncoding.owner(rs.getBytes("owner_ref")), rs.getString("media_type"), rs.getLong("size_bytes"), rs.getString("content_digest")),
                        scope, ArtifactStatus.valueOf(rs.getString("status")), rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant()), artifactId, SupportEncoding.scopeKey(scope)).stream().findFirst();
    }

    @Override
    public ArtifactContent open(ExecutionScope scope, ArtifactRef expected) {
        ContractChecks.required(expected, "expected");
        var artifact = find(scope, expected.artifactId()).orElseThrow(() -> failure(CommonErrorCode.NOT_FOUND));
        if (!artifact.ref().equals(expected)) throw failure(CommonErrorCode.VERSION_CONFLICT);
        if (!artifact.isReadableAt(clock.instant())) throw failure(CommonErrorCode.UNAUTHORIZED);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(file(expected.artifactId()), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            if (channel.size() != expected.sizeBytes()) throw new IOException("artifact size integrity failure");
            var digest = SupportEncoding.sha256();
            ByteBuffer buffer = ByteBuffer.allocate(16384);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
            if (!("sha256:" + HexFormat.of().formatHex(digest.digest())).equals(expected.contentDigest()))
                throw new IOException("artifact digest integrity failure");
            channel.position(0);
            // 当前元数据撤销／删除优先于校验前的读取快照。
            var current = find(scope, expected.artifactId()).orElseThrow(() -> failure(CommonErrorCode.NOT_FOUND));
            if (!current.ref().equals(expected) || !current.isReadableAt(clock.instant()))
                throw failure(CommonErrorCode.UNAUTHORIZED);
            return new ArtifactContent(current, Channels.newInputStream(channel));
        } catch (IOException | RuntimeException error) {
            if (channel != null) try {
                channel.close();
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("artifact read failed", error);
        }
    }

    @Override
    public Artifact transition(ExecutionScope scope, String id, long expectedRevision, ArtifactStatus next) {
        ContractChecks.required(next, "next");
        if (expectedRevision <= 0) throw new IllegalArgumentException("expectedRevision must be positive");
        return writes.execute(status -> {
            var current = find(scope, id).orElseThrow(() -> failure(CommonErrorCode.NOT_FOUND));
            if (current.revision() != expectedRevision) throw failure(CommonErrorCode.VERSION_CONFLICT);
            if (!current.status().canTransitionTo(next))
                throw new IllegalArgumentException("invalid artifact transition");
            if (next == ArtifactStatus.EXPIRED && (current.expiresAt() == null || clock.instant().isBefore(current.expiresAt())))
                throw new IllegalArgumentException("artifact has not expired");
            if ((next == ArtifactStatus.AVAILABLE || next == ArtifactStatus.REFERENCED) && current.expiresAt() != null && !clock.instant().isBefore(current.expiresAt()))
                throw failure(CommonErrorCode.UNAUTHORIZED);
            var updated = new Artifact(current.ref(), scope, next, Math.addExact(expectedRevision, 1), current.createdAt(), next == ArtifactStatus.REFERENCED ? null : current.expiresAt());
            int changed = jdbc.update("UPDATE arte_execution_artifact SET status = ?, revision = ?, expires_at = ? WHERE artifact_id = ? AND scope_key = ? AND revision = ?",
                    next.name(), updated.revision(), timestamp(updated.expiresAt()), id, SupportEncoding.scopeKey(scope), expectedRevision);
            if (changed != 1) throw failure(CommonErrorCode.VERSION_CONFLICT);
            return updated;
        });
    }

    @Override
    public void delete(ExecutionScope scope, String id, long expectedRevision) {
        if (expectedRevision <= 0) throw new IllegalArgumentException("expectedRevision must be positive");
        var current = find(scope, id).orElseThrow(() -> failure(CommonErrorCode.NOT_FOUND));
        if (current.revision() != expectedRevision) throw failure(CommonErrorCode.VERSION_CONFLICT);
        if (current.status() != ArtifactStatus.DELETED) transition(scope, id, expectedRevision, ArtifactStatus.DELETED);
        // 墓碑先独立提交，之后删除文件。清理失败可用当前墓碑 revision 重试，不再开放下载。
        try {
            Files.deleteIfExists(file(id));
            forceDirectory();
        } catch (IOException error) {
            throw new IllegalStateException("artifact byte deletion failed", error);
        }
    }

    private Path file(String id) {
        if (!UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("invalid stored artifact id");
        return directory.resolve(id + ".data");
    }

    private void forceDirectory() throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static BaseException failure(CommonErrorCode code) {
        return new BaseException(ExecutionError.of(code, "artifact", false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
    }

    private static void clean(Path path, Throwable failure) {
        if (path != null) try {
            Files.deleteIfExists(path);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }
}
