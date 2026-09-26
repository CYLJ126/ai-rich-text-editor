package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 可持久化、可重建执行上下文的工具任务实体。
 * <p>
 * 该实体保存 Worker 重启后重新执行所需的最小快照，不保存线程、Future、回调或明文凭据。
 * 状态修改集中在领域方法中，持久化层可使用 version 做乐观锁。
 */
public final class ToolTask {

    private final String taskId;
    private final String callId;
    private final ToolReference tool;
    private final Map<String, Object> arguments;
    private final ToolExecutionPolicy executionPolicy;
    private final String ownerId;
    private final String subjectId;
    private final String traceId;
    private final String idempotencyKey;
    private final String credentialBindingId;
    private final Instant createdAt;
    private final Map<String, Object> metadata;

    private ToolTaskHandle.Status status;
    private Double progress;
    private String progressMessage;
    private String resumeToken;
    private Instant updatedAt;
    private long version;
    private int attempt;
    private Instant nextAttemptAt;
    private String workerId;
    private Instant leaseUntil;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ToolTask(
            String taskId,
            String callId,
            ToolReference tool,
            Map<String, Object> arguments,
            ToolExecutionPolicy executionPolicy,
            String ownerId,
            String subjectId,
            String traceId,
            String idempotencyKey,
            String credentialBindingId,
            Instant createdAt,
            Map<String, Object> metadata,
            ToolTaskHandle.Status status,
            Double progress,
            String progressMessage,
            String resumeToken,
            Instant updatedAt,
            long version,
            int attempt,
            Instant nextAttemptAt,
            String workerId,
            Instant leaseUntil
    ) {
        this.taskId = requireText(taskId, "taskId");
        this.callId = requireText(callId, "callId");
        this.tool = Objects.requireNonNull(tool, "tool must not be null");
        this.arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy must not be null");
        this.ownerId = requireText(ownerId, "ownerId");
        this.subjectId = requireText(subjectId, "subjectId");
        this.traceId = requireText(traceId, "traceId");
        this.idempotencyKey = idempotencyKey;
        this.credentialBindingId = credentialBindingId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        this.status = Objects.requireNonNull(status, "status must not be null");
        validateProgress(progress);
        this.progress = progress;
        this.progressMessage = progressMessage;
        this.resumeToken = resumeToken;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (version < 0 || attempt < 0) {
            throw new IllegalArgumentException("version and attempt must not be negative");
        }
        this.version = version;
        this.attempt = attempt;
        this.nextAttemptAt = nextAttemptAt;
        this.workerId = workerId;
        this.leaseUntil = leaseUntil;
        validateState();
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public static ToolTask enqueue(
            String taskId,
            String callId,
            ToolReference tool,
            Map<String, Object> arguments,
            ToolExecutionPolicy executionPolicy,
            String ownerId,
            String subjectId,
            String traceId,
            String idempotencyKey,
            String credentialBindingId,
            Map<String, Object> metadata,
            Instant now
    ) {
        return new ToolTask(taskId, callId, tool, arguments, executionPolicy, ownerId, subjectId,
                traceId, idempotencyKey, credentialBindingId, now, metadata, ToolTaskHandle.Status.QUEUED,
                null, null, null, now, 0, 0, null, null, null);
    }

    public void claim(String newWorkerId, Instant newLeaseUntil, Instant now) {
        requireNotTerminal();
        boolean expiredLease = status == ToolTaskHandle.Status.RUNNING
                && leaseUntil != null && !leaseUntil.isAfter(now);
        if (status != ToolTaskHandle.Status.QUEUED && !expiredLease) {
            throw new IllegalStateException("only queued or lease-expired tasks can be claimed");
        }
        if (nextAttemptAt != null && nextAttemptAt.isAfter(now)) {
            throw new IllegalStateException("task is not ready for retry");
        }
        if (!newLeaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
        workerId = requireText(newWorkerId, "workerId");
        leaseUntil = newLeaseUntil;
        nextAttemptAt = null;
        status = ToolTaskHandle.Status.RUNNING;
        attempt++;
        touch(now);
    }

    public void renewLease(String claimingWorkerId, Instant newLeaseUntil, Instant now) {
        requireStatus(ToolTaskHandle.Status.RUNNING);
        if (!Objects.equals(workerId, claimingWorkerId)) {
            throw new IllegalStateException("task is owned by another worker");
        }
        if (!newLeaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
        leaseUntil = newLeaseUntil;
        touch(now);
    }

    public void updateProgress(double newProgress, String message, Instant now) {
        requireStatus(ToolTaskHandle.Status.RUNNING);
        validateProgress(newProgress);
        progress = newProgress;
        progressMessage = message;
        touch(now);
    }

    public void waitForApproval(String newResumeToken, Instant now) {
        requireStatus(ToolTaskHandle.Status.RUNNING);
        suspend(ToolTaskHandle.Status.WAITING_APPROVAL, newResumeToken, now);
    }

    public void pause(String newResumeToken, Instant now) {
        requireNotTerminal();
        suspend(ToolTaskHandle.Status.PAUSED, newResumeToken, now);
    }

    public void resume(Instant now) {
        if (status != ToolTaskHandle.Status.PAUSED && status != ToolTaskHandle.Status.WAITING_APPROVAL) {
            throw new IllegalStateException("only paused tasks can be resumed");
        }
        status = ToolTaskHandle.Status.QUEUED;
        resumeToken = null;
        releaseLease();
        touch(now);
    }

    public void scheduleRetry(Instant retryAt, String message, Instant now) {
        requireStatus(ToolTaskHandle.Status.RUNNING);
        if (attempt > executionPolicy.maxRetries()) {
            throw new IllegalStateException("maximum retry count has been reached");
        }
        if (retryAt.isBefore(now)) {
            throw new IllegalArgumentException("retryAt must not be before now");
        }
        status = ToolTaskHandle.Status.QUEUED;
        nextAttemptAt = retryAt;
        progressMessage = message;
        releaseLease();
        touch(now);
    }

    public void succeed(Instant now) {
        requireStatus(ToolTaskHandle.Status.RUNNING);
        status = ToolTaskHandle.Status.SUCCEEDED;
        progress = 1.0;
        finish(now);
    }

    public void fail(String message, Instant now) {
        requireNotTerminal();
        status = ToolTaskHandle.Status.FAILED;
        progressMessage = message;
        finish(now);
    }

    public void cancel(String message, Instant now) {
        requireNotTerminal();
        status = ToolTaskHandle.Status.CANCELLED;
        progressMessage = message;
        finish(now);
    }

    public void timeout(String message, Instant now) {
        requireNotTerminal();
        status = ToolTaskHandle.Status.TIMED_OUT;
        progressMessage = message;
        finish(now);
    }

    public ToolTaskHandle snapshot() {
        return new ToolTaskHandle(taskId, callId, tool, status, progress, progressMessage, resumeToken,
                createdAt, updatedAt, version, metadata);
    }

    private void suspend(ToolTaskHandle.Status suspendedStatus, String newResumeToken, Instant now) {
        status = suspendedStatus;
        resumeToken = requireText(newResumeToken, "resumeToken");
        releaseLease();
        touch(now);
    }

    private void finish(Instant now) {
        resumeToken = null;
        nextAttemptAt = null;
        releaseLease();
        touch(now);
    }

    private void releaseLease() {
        workerId = null;
        leaseUntil = null;
    }

    private void touch(Instant now) {
        updatedAt = Objects.requireNonNull(now, "now must not be null");
        version++;
    }

    private void requireNotTerminal() {
        if (terminal()) {
            throw new IllegalStateException("terminal tasks cannot transition");
        }
    }

    public boolean terminal() {
        return status == ToolTaskHandle.Status.SUCCEEDED || status == ToolTaskHandle.Status.FAILED
                || status == ToolTaskHandle.Status.CANCELLED || status == ToolTaskHandle.Status.TIMED_OUT;
    }

    private void requireStatus(ToolTaskHandle.Status expected) {
        if (status != expected) {
            throw new IllegalStateException("expected status " + expected + " but was " + status);
        }
    }

    private void validateState() {
        if (status == ToolTaskHandle.Status.RUNNING && (workerId == null || leaseUntil == null)) {
            throw new IllegalArgumentException("running tasks require workerId and leaseUntil");
        }
        if ((status == ToolTaskHandle.Status.PAUSED || status == ToolTaskHandle.Status.WAITING_APPROVAL)
                && (resumeToken == null || resumeToken.isBlank())) {
            throw new IllegalArgumentException("suspended tasks require resumeToken");
        }
    }

    private static void validateProgress(Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0.0 || value > 1.0)) {
            throw new IllegalArgumentException("progress must be between 0.0 and 1.0");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public String taskId() {
        return taskId;
    }

    public String callId() {
        return callId;
    }

    public ToolReference tool() {
        return tool;
    }

    public Map<String, Object> arguments() {
        return arguments;
    }

    public ToolExecutionPolicy executionPolicy() {
        return executionPolicy;
    }

    public String ownerId() {
        return ownerId;
    }

    public String subjectId() {
        return subjectId;
    }

    public String traceId() {
        return traceId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String credentialBindingId() {
        return credentialBindingId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public ToolTaskHandle.Status status() {
        return status;
    }

    public Double progress() {
        return progress;
    }

    public String progressMessage() {
        return progressMessage;
    }

    public String resumeToken() {
        return resumeToken;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public int attempt() {
        return attempt;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String workerId() {
        return workerId;
    }

    public Instant leaseUntil() {
        return leaseUntil;
    }
}
