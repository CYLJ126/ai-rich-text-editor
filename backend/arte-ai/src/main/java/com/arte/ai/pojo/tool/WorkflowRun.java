package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工作流程运行实体。
 * <p>
 * 状态变更只能通过领域方法发生，version 可供持久化层执行乐观锁更新。
 */
public final class WorkflowRun {

    public enum Status {
        CREATED,
        RUNNING,
        WAITING_TOOL,
        WAITING_APPROVAL,
        PAUSED,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }

    private final String runId;
    private final String workflowId;
    private final String workflowVersion;
    private final Instant createdAt;
    private Status status;
    private Set<String> activeNodeIds;
    private Map<String, Object> outputs;
    private WorkflowError error;
    private String resumeToken;
    private Instant startedAt;
    private Instant completedAt;
    private Instant updatedAt;
    private long version;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public WorkflowRun(
            String runId,
            String workflowId,
            String workflowVersion,
            Instant createdAt,
            Status status,
            Set<String> activeNodeIds,
            Map<String, Object> outputs,
            WorkflowError error,
            String resumeToken,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt,
            long version
    ) {
        this.runId = requireText(runId, "runId");
        this.workflowId = requireText(workflowId, "workflowId");
        this.workflowVersion = requireText(workflowVersion, "workflowVersion");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.activeNodeIds = copySet(activeNodeIds);
        this.outputs = copyMap(outputs);
        this.error = error;
        this.resumeToken = resumeToken;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        validateState();
    }

    public static WorkflowRun create(
            String runId,
            String workflowId,
            String workflowVersion,
            Instant now
    ) {
        return new WorkflowRun(runId, workflowId, workflowVersion, now, Status.CREATED,
                Set.of(), Map.of(), null, null, null, null, now, 0);
    }

    public void start(Set<String> entryNodeIds, Instant now) {
        requireStatus(Status.CREATED);
        activeNodeIds = requireActiveNodes(entryNodeIds);
        status = Status.RUNNING;
        startedAt = Objects.requireNonNull(now, "now must not be null");
        touch(now);
    }

    public void advance(Set<String> newActiveNodeIds, Instant now) {
        requireStatus(Status.RUNNING);
        activeNodeIds = requireActiveNodes(newActiveNodeIds);
        touch(now);
    }

    public void waitForTool(Set<String> waitingNodeIds, String newResumeToken, Instant now) {
        requireStatus(Status.RUNNING);
        suspend(Status.WAITING_TOOL, waitingNodeIds, newResumeToken, now);
    }

    public void waitForApproval(Set<String> waitingNodeIds, String newResumeToken, Instant now) {
        requireStatus(Status.RUNNING);
        suspend(Status.WAITING_APPROVAL, waitingNodeIds, newResumeToken, now);
    }

    public void pause(String newResumeToken, Instant now) {
        requireNotTerminal();
        status = Status.PAUSED;
        resumeToken = requireText(newResumeToken, "resumeToken");
        touch(now);
    }

    public void resume(Set<String> resumedNodeIds, Instant now) {
        if (status != Status.WAITING_TOOL && status != Status.WAITING_APPROVAL && status != Status.PAUSED) {
            throw new IllegalStateException("workflow is not suspended");
        }
        status = Status.RUNNING;
        activeNodeIds = requireActiveNodes(resumedNodeIds);
        resumeToken = null;
        touch(now);
    }

    public void succeed(Map<String, Object> result, Instant now) {
        requireStatus(Status.RUNNING);
        status = Status.SUCCEEDED;
        outputs = copyMap(result);
        activeNodeIds = Set.of();
        complete(now);
    }

    public void fail(WorkflowError failure, Instant now) {
        requireNotTerminal();
        status = Status.FAILED;
        error = Objects.requireNonNull(failure, "failure must not be null");
        activeNodeIds = Set.of();
        complete(now);
    }

    public void cancel(WorkflowError cancellation, Instant now) {
        requireNotTerminal();
        status = Status.CANCELLED;
        error = cancellation;
        activeNodeIds = Set.of();
        complete(now);
    }

    public void timeout(WorkflowError timeout, Instant now) {
        requireNotTerminal();
        status = Status.TIMED_OUT;
        error = timeout;
        activeNodeIds = Set.of();
        complete(now);
    }

    private void suspend(Status suspendedStatus, Set<String> waitingNodeIds, String newResumeToken, Instant now) {
        status = suspendedStatus;
        activeNodeIds = requireActiveNodes(waitingNodeIds);
        resumeToken = requireText(newResumeToken, "resumeToken");
        touch(now);
    }

    private void complete(Instant now) {
        completedAt = Objects.requireNonNull(now, "now must not be null");
        resumeToken = null;
        touch(now);
    }

    private void touch(Instant now) {
        updatedAt = Objects.requireNonNull(now, "now must not be null");
        version++;
    }

    private void requireStatus(Status expected) {
        if (status != expected) {
            throw new IllegalStateException("expected status " + expected + " but was " + status);
        }
    }

    private void requireNotTerminal() {
        if (terminal()) {
            throw new IllegalStateException("terminal workflow runs cannot transition");
        }
    }

    private void validateState() {
        if (terminal() && completedAt == null) {
            throw new IllegalArgumentException("terminal workflow runs require completedAt");
        }
        if ((status == Status.WAITING_TOOL || status == Status.WAITING_APPROVAL || status == Status.PAUSED)
                && (resumeToken == null || resumeToken.isBlank())) {
            throw new IllegalArgumentException("suspended workflow runs require resumeToken");
        }
    }

    public boolean terminal() {
        return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED
                || status == Status.TIMED_OUT;
    }

    private static Set<String> requireActiveNodes(Set<String> value) {
        Set<String> copied = copySet(value);
        if (copied.isEmpty()) {
            throw new IllegalArgumentException("active node ids must not be empty");
        }
        return copied;
    }

    private static Set<String> copySet(Set<String> value) {
        return value == null ? Set.of() : Set.copyOf(value);
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public String runId() {
        return runId;
    }

    public String workflowId() {
        return workflowId;
    }

    public String workflowVersion() {
        return workflowVersion;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public Set<String> activeNodeIds() {
        return activeNodeIds;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    public WorkflowError error() {
        return error;
    }

    public String resumeToken() {
        return resumeToken;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
